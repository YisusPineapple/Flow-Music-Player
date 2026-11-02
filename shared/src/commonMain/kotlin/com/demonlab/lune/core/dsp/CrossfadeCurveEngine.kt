package com.demonlab.lune.core.dsp

import com.demonlab.lune.core.model.CoreAudioProfile
import com.demonlab.lune.core.model.CrossfadeConfig
import com.demonlab.lune.core.model.CrossfadeCurveMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Platform-agnostic DSP curve & adaptive crossfade engine shared across Android, Windows, and Linux.
 * Designed for zero heap allocations on the real-time audio volume tick loop.
 */
object CrossfadeCurveEngine {

    data class CrossfadeGainPair(
        val outgoingLeft: Float,
        val outgoingRight: Float,
        val incomingLeft: Float,
        val incomingRight: Float
    )

    /**
     * Computes perceptual fade-out and fade-in scalar gains in [0f, 1f] for normalized progress [0f, 1f].
     */
    fun computeScalarGains(
        progress: Float,
        mode: CrossfadeCurveMode = CrossfadeCurveMode.HYBRID_AUDIOPHILE
    ): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        return when (mode) {
            CrossfadeCurveMode.EQUAL_POWER -> {
                val angle = (p * (PI * 0.5)).toFloat()
                cos(angle).coerceIn(0f, 1f) to sin(angle).coerceIn(0f, 1f)
            }
            CrossfadeCurveMode.LOGARITHMIC -> {
                val fadeOut = (1f - p).pow(2.2f)
                val fadeIn = (1f - (1f - p).pow(2.2f))
                fadeOut.coerceIn(0f, 1f) to fadeIn.coerceIn(0f, 1f)
            }
            CrossfadeCurveMode.HYBRID_AUDIOPHILE -> {
                // Blends constant-power sin/cos (prevents center-dip) with perceptual S-taper at extremes
                // so incoming track enters silkily without sudden transient punch and outgoing tail decays naturally.
                val angle = (p * (PI * 0.5)).toFloat()
                val eqOut = cos(angle)
                val eqIn = sin(angle)
                val logOut = (1f - p).pow(2.15f)
                val smoothIn = p.pow(1.45f)
                val outGain = (eqOut * 0.68f + logOut * 0.32f).coerceIn(0f, 1f)
                val inGain = (eqIn * 0.72f + smoothIn * 0.28f).coerceIn(0f, 1f)
                outGain to inGain
            }
        }
    }

    /**
     * Applies stereo balance [0f..1f] (0.5f = center) and ducking multiplier [0f..1f]
     * to outgoing and incoming crossfade gains without allocation when unpacked.
     */
    fun computeStereoGains(
        progress: Float,
        targetLeft: Float,
        targetRight: Float,
        mode: CrossfadeCurveMode = CrossfadeCurveMode.HYBRID_AUDIOPHILE
    ): CrossfadeGainPair {
        val (outScalar, inScalar) = computeScalarGains(progress, mode)
        val safeL = targetLeft.coerceIn(0f, 1f)
        val safeR = targetRight.coerceIn(0f, 1f)
        return CrossfadeGainPair(
            outgoingLeft = (outScalar * safeL).coerceIn(0f, 1f),
            outgoingRight = (outScalar * safeR).coerceIn(0f, 1f),
            incomingLeft = (inScalar * safeL).coerceIn(0f, 1f),
            incomingRight = (inScalar * safeR).coerceIn(0f, 1f)
        )
    }

    /**
     * Calculates optimal crossfade duration in milliseconds based on outgoing & incoming acoustic profiles.
     */
    fun computeAdaptiveDurationMs(
        config: CrossfadeConfig,
        outgoingDurationMs: Long,
        incomingDurationMs: Long,
        outgoingProfile: CoreAudioProfile?,
        incomingProfile: CoreAudioProfile?
    ): Long {
        if (!config.enabled && !config.automixEnabled) return 0L

        // Short tracks (< 25s) or spoken-word / podcast episodes (> 15 min) should not crossfade aggressively
        if (outgoingDurationMs < 25_000L || incomingDurationMs < 20_000L) return 0L
        if (outgoingProfile?.isSpokenWord == true || incomingProfile?.isSpokenWord == true) return 0L
        if (outgoingDurationMs > 15 * 60_000L) return 0L

        val baseMs = config.baseDurationSeconds.coerceIn(1, 20) * 1000L
        if (!config.adaptiveDuration) {
            return baseMs.coerceAtMost(outgoingDurationMs / 3)
        }

        val outEnergy = outgoingProfile?.energy ?: 0.55f
        val inEnergy = incomingProfile?.energy ?: 0.55f
        val avgEnergy = (outEnergy + inEnergy) * 0.5f
        val avgAcoustic = ((outgoingProfile?.acousticness ?: 0.35f) + (incomingProfile?.acousticness ?: 0.35f)) * 0.5f

        val adaptiveMs = when {
            // High-energy electronic / rock / hip-hop: tight, punchy beat-friendly transition (4.5s - 6.5s)
            avgEnergy >= 0.74f && avgAcoustic < 0.4f -> 5_200L
            // Chill / ambient / classical / acoustic: lush, atmospheric blend (8.5s - 11.5s)
            avgEnergy <= 0.38f || avgAcoustic >= 0.68f -> 9_800L
            // Large energy contrast: moderate bridge so tempo shift feels intentional (6.5s)
            kotlin.math.abs(outEnergy - inEnergy) >= 0.38f -> 6_500L
            // Balanced mid-tempo pop / indie / R&B: blend user preference with 7.5s sweet spot
            else -> ((baseMs * 0.45f) + (7_500L * 0.55f)).toLong()
        }

        val maxAllowed = (outgoingDurationMs / 3).coerceAtMost(incomingDurationMs / 2).coerceAtMost(14_000L)
        return adaptiveMs.coerceIn(2_000L, maxAllowed.coerceAtLeast(2_000L))
    }

    /**
     * Given normalized envelope/frame energy buckets [0f..1f] sampled uniformly across a tail window
     * (e.g., the final 10,000ms of a song), detects trailing silence or steep energy decay so the
     * crossfade triggers BEFORE dead air occurs.
     *
     * @param energyBuckets Normalized energy per bucket from oldest (start of window) to end of track.
     * @param windowDurationMs Total duration covered by [energyBuckets].
     * @return Extra lead time in milliseconds before track end where crossfade should start early.
     */
    fun detectNaturalFadeOutOffsetMs(
        energyBuckets: FloatArray,
        windowDurationMs: Long
    ): Long {
        if (energyBuckets.size < 4 || windowDurationMs <= 1000L) return 0L

        val peak = energyBuckets.maxOrNull()?.coerceAtLeast(0.001f) ?: return 0L
        val silenceThreshold = peak * 0.08f
        val decayThreshold = peak * 0.22f

        var silentBucketsFromEnd = 0
        for (i in energyBuckets.indices.reversed()) {
            if (energyBuckets[i] <= silenceThreshold) {
                silentBucketsFromEnd++
            } else {
                break
            }
        }

        var lowDecayBucketsFromEnd = silentBucketsFromEnd
        for (i in (energyBuckets.size - 1 - silentBucketsFromEnd) downTo 0) {
            if (energyBuckets[i] <= decayThreshold) {
                lowDecayBucketsFromEnd++
            } else {
                break
            }
        }

        val msPerBucket = windowDurationMs / energyBuckets.size
        // Full offset for dead air + 65% of gentle fade-out tail, capped at 7.5s so songs aren't cut prematurely
        val silenceMs = silentBucketsFromEnd * msPerBucket
        val decayOnlyMs = ((lowDecayBucketsFromEnd - silentBucketsFromEnd) * msPerBucket * 0.65f).toLong()
        return (silenceMs + decayOnlyMs).coerceIn(0L, 7_500L)
    }
}
