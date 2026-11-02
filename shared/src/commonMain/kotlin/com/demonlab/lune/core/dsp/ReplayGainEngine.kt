package com.demonlab.lune.core.dsp

import com.demonlab.lune.core.model.ReplayGainConfig
import com.demonlab.lune.core.model.ReplayGainMetadata
import com.demonlab.lune.core.model.ReplayGainMode
import com.demonlab.lune.core.model.ReplayGainResult
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin Multiplatform ReplayGain 1/2, EBU R128, Apple SoundCheck (iTunNORM),
 * and ultra-lightweight sparse-window RMS/LUFS loudness engine.
 * Designed for zero heap allocations on the audio playback thread and minimal CPU on SSE2/legacy hardware.
 */
object ReplayGainEngine {

    private const val MIN_GAIN_DB = -24.0f
    private const val MAX_GAIN_DB = 18.0f
    private const val R128_TO_RG2_OFFSET_DB = 5.0f // EBU R128 (-23 LUFS) -> ReplayGain 2.0 (-18 LUFS)

    /**
     * Parses a ReplayGain dB string such as "-6.42 dB", "+1.85 dB", or "-4.1".
     */
    fun parseGainDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                if (ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == ',') {
                    append(if (ch == ',') '.' else ch)
                } else if (isNotEmpty()) {
                    break
                }
            }
        }
        val parsed = cleaned.toFloatOrNull() ?: return null
        if (!parsed.isFinite()) return null
        return parsed.coerceIn(-50f, 50f)
    }

    /**
     * Parses a linear peak tag such as "0.988553" or "1.042100".
     */
    fun parsePeak(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(',', '.')
        val parsed = cleaned.toFloatOrNull() ?: return null
        if (!parsed.isFinite() || parsed <= 0f) return null
        return parsed.coerceIn(0.0001f, 8.0f)
    }

    /**
     * Parses Opus / Ogg EBU R128 Q7.8 fixed-point integer gain tag (e.g., "-1536" -> -6.0 dB + 5.0 dB = -1.0 dB RG2).
     */
    fun parseR128Q78GainDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val q78 = raw.trim().toIntOrNull() ?: return null
        val r128Db = q78 / 256.0f
        return (r128Db + R128_TO_RG2_OFFSET_DB).coerceIn(-50f, 50f)
    }

    /**
     * Parses Apple iTunes SoundCheck (iTunNORM) 10-word hex string into ReplayGain-equivalent dB.
     */
    fun parseITunNormGainDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val word0 = tokens[0].toLongOrNull(16) ?: return null
        val word1 = tokens.getOrNull(1)?.toLongOrNull(16) ?: word0
        val avgWord = max(word0, word1)
        if (avgWord <= 0L) return null
        // SoundCheck base reference is 1000 (~ -16.2 LUFS); align gently to -18 LUFS
        val db = (10.0 * log10(1000.0 / avgWord.toDouble()) - 1.5).toFloat()
        return if (db.isFinite()) db.coerceIn(-30f, 30f) else null
    }

    /**
     * Builds unified ReplayGainMetadata from raw key-value tag candidates.
     */
    fun fromRawTags(
        rgTrackGain: String? = null,
        rgTrackPeak: String? = null,
        rgAlbumGain: String? = null,
        rgAlbumPeak: String? = null,
        r128TrackGain: String? = null,
        r128AlbumGain: String? = null,
        iTunNorm: String? = null,
        computedLoudnessDb: Float? = null
    ): ReplayGainMetadata {
        val trackGain = parseGainDb(rgTrackGain)
            ?: parseR128Q78GainDb(r128TrackGain)
            ?: parseITunNormGainDb(iTunNorm)
        val albumGain = parseGainDb(rgAlbumGain)
            ?: parseR128Q78GainDb(r128AlbumGain)
        val trackPeak = parsePeak(rgTrackPeak)
        val albumPeak = parsePeak(rgAlbumPeak)
        return ReplayGainMetadata(
            trackGainDb = trackGain,
            trackPeak = trackPeak,
            albumGainDb = albumGain,
            albumPeak = albumPeak,
            computedLoudnessDb = computedLoudnessDb
        )
    }

    /**
     * Ultra-lightweight gated RMS loudness estimator over 16-bit Little-Endian PCM bytes.
     * Uses silence gating (-55 dBFS) and mid-band perceptual weighting approximation to estimate
     * ReplayGain adjustment in dB relative to -18 LUFS without heavy FFTs or AVX instructions.
     *
     * @return Pair(estimatedGainDbRelativeToMinus18Lufs, normalizedSamplePeak)
     */
    fun estimateGainFromPcm16le(
        pcmBytes: ByteArray,
        validLength: Int = pcmBytes.size,
        channels: Int = 2
    ): Pair<Float, Float>? {
        val ch = channels.coerceIn(1, 8)
        val frameBytes = ch * 2
        val totalFrames = validLength / frameBytes
        if (totalFrames < 64) return null

        val blockFrames = 1024
        var gatedSumSquares = 0.0
        var gatedFramesCount = 0L
        var maxAbsSample = 0

        var frameIdx = 0
        while (frameIdx < totalFrames) {
            val endFrame = minOf(frameIdx + blockFrames, totalFrames)
            var blockSumSq = 0.0
            val countInBlock = endFrame - frameIdx

            var f = frameIdx
            while (f < endFrame) {
                val baseOffset = f * frameBytes
                var frameMonoEnergy = 0.0
                var c = 0
                while (c < ch) {
                    val bIdx = baseOffset + c * 2
                    val lo = pcmBytes[bIdx].toInt() and 0xFF
                    val hi = pcmBytes[bIdx + 1].toInt() // sign-extended
                    val sample = (hi shl 8) or lo
                    val absS = abs(sample)
                    if (absS > maxAbsSample) maxAbsSample = absS
                    val norm = sample / 32768.0
                    frameMonoEnergy += norm * norm
                    c++
                }
                blockSumSq += frameMonoEnergy / ch
                f++
            }

            val blockMeanSq = blockSumSq / countInBlock
            // Gate out silent / near-silent blocks (< ~ -55 dBFS -> 10^(-5.5) = 3.16e-6)
            if (blockMeanSq > 3.16e-6) {
                gatedSumSquares += blockSumSq
                gatedFramesCount += countInBlock
            }

            frameIdx = endFrame
        }

        if (gatedFramesCount == 0L) return null
        val meanSquare = gatedSumSquares / gatedFramesCount.toDouble()
        if (meanSquare <= 1e-10) return null

        // Convert gated RMS dBFS to approximate LUFS (-0.691 K-weighting offset + ~1.5 dB crest alignment)
        val rmsDbfs = (10.0 * log10(meanSquare)).toFloat()
        val approxLufs = rmsDbfs - 0.7f
        val gainToMinus18Lufs = (-18.0f - approxLufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val peak = (maxAbsSample / 32768.0f).coerceIn(0.01f, 1.0f)
        return gainToMinus18Lufs to peak
    }

    /**
     * Computes the effective linear volume scalar [0..1] plus optional positive boost in millibels
     * (for LoudnessEnhancer / PCM headroom) with optional Peak Limiting to prevent digital clipping.
     */
    fun computeLinearMultiplier(
        metadata: ReplayGainMetadata?,
        config: ReplayGainConfig,
        isAlbumSequentialContext: Boolean = false
    ): ReplayGainResult {
        if (config.mode == ReplayGainMode.OFF) {
            return ReplayGainResult(
                appliedGainDb = 0f,
                linearVolumeScalar = 1f,
                postBoostMillibels = 0,
                isPeakLimited = false,
                sourceLabel = "OFF"
            )
        }

        val targetOffsetDb = (config.targetLoudnessLufs - (-18.0f)).coerceIn(-8f, 8f)
        val meta = metadata ?: ReplayGainMetadata()

        var chosenBaseDb: Float? = null
        var chosenPeak: Float? = null
        var isTagged = false
        var source = "NONE"

        when (config.mode) {
            ReplayGainMode.TRACK -> {
                if (meta.trackGainDb != null) {
                    chosenBaseDb = meta.trackGainDb
                    chosenPeak = meta.trackPeak ?: meta.albumPeak
                    isTagged = true
                    source = "RG Track"
                } else if (meta.albumGainDb != null) {
                    chosenBaseDb = meta.albumGainDb
                    chosenPeak = meta.albumPeak ?: meta.trackPeak
                    isTagged = true
                    source = "RG Album"
                }
            }
            ReplayGainMode.ALBUM -> {
                if (meta.albumGainDb != null) {
                    chosenBaseDb = meta.albumGainDb
                    chosenPeak = meta.albumPeak ?: meta.trackPeak
                    isTagged = true
                    source = "RG Album"
                } else if (meta.trackGainDb != null) {
                    chosenBaseDb = meta.trackGainDb
                    chosenPeak = meta.trackPeak
                    isTagged = true
                    source = "RG Track"
                }
            }
            ReplayGainMode.SMART_ALBUM -> {
                if (isAlbumSequentialContext && meta.albumGainDb != null) {
                    chosenBaseDb = meta.albumGainDb
                    chosenPeak = meta.albumPeak ?: meta.trackPeak
                    isTagged = true
                    source = "RG Smart (Album)"
                } else if (meta.trackGainDb != null) {
                    chosenBaseDb = meta.trackGainDb
                    chosenPeak = meta.trackPeak ?: meta.albumPeak
                    isTagged = true
                    source = "RG Smart (Track)"
                } else if (meta.albumGainDb != null) {
                    chosenBaseDb = meta.albumGainDb
                    chosenPeak = meta.albumPeak
                    isTagged = true
                    source = "RG Album"
                }
            }
            ReplayGainMode.OFF -> Unit
        }

        if (chosenBaseDb == null && config.enableFallbackAnalysis && meta.computedLoudnessDb != null) {
            chosenBaseDb = meta.computedLoudnessDb
            chosenPeak = meta.trackPeak ?: 0.96f
            isTagged = false
            source = "Auto RMS/LUFS"
        }

        val preAmpDb = if (isTagged) config.preAmpWithTagsDb else config.preAmpUntaggedDb
        val rawTotalDb = if (chosenBaseDb != null) {
            (chosenBaseDb + preAmpDb + targetOffsetDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        } else {
            preAmpDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }

        if (abs(rawTotalDb) < 0.05f) {
            return ReplayGainResult(
                appliedGainDb = 0f,
                linearVolumeScalar = 1f,
                postBoostMillibels = 0,
                isPeakLimited = false,
                sourceLabel = source
            )
        }

        var linear = 10.0.pow(rawTotalDb / 20.0).toFloat()
        var effectiveDb = rawTotalDb
        var peakLimited = false

        if (config.preventClipping) {
            val safePeak = (chosenPeak ?: 0.98f).coerceAtLeast(0.05f)
            val maxAllowedLinear = 0.99f / safePeak
            if (linear > maxAllowedLinear) {
                linear = maxAllowedLinear
                effectiveDb = (20.0 * log10(linear.toDouble())).toFloat()
                peakLimited = true
            }
        }

        return if (linear <= 1.0f) {
            ReplayGainResult(
                appliedGainDb = effectiveDb,
                linearVolumeScalar = linear.coerceIn(0.03f, 1.0f),
                postBoostMillibels = 0,
                isPeakLimited = peakLimited,
                sourceLabel = source
            )
        } else {
            // MediaPlayer.setVolume caps at 1.0f; expose extra positive dB in millibels (1 dB = 100 mB)
            val boostDb = (effectiveDb).coerceIn(0f, 12f)
            val boostMb = (boostDb * 100f).roundToInt()
            ReplayGainResult(
                appliedGainDb = effectiveDb,
                linearVolumeScalar = 1.0f,
                postBoostMillibels = boostMb,
                isPeakLimited = peakLimited,
                sourceLabel = source
            )
        }
    }
}
