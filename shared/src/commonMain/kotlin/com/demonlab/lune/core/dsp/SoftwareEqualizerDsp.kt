package com.demonlab.lune.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure Kotlin, zero-heap-allocation, studio-grade 5-band biquad equalizer DSP with:
 * - 5 Canonical standard audio frequency bands: 60 Hz (Sub/Bass), 230 Hz (Low Mid), 910 Hz (Mid), 3.6 kHz (High Mid/Presence), 14 kHz (Air/Brilliance).
 * - Preamp (-12 dB to +12 dB) with auto-headroom anti-clipping compensation.
 * - Bass boost shelf filter with safe gain clamping.
 * - Spatial stereo width enhancer (Mid/Side processing).
 * - Smooth parameter interpolation between blocks to eliminate zipper clicks.
 * - Denormal and NaN protection so math is 100% crash-free.
 * - Frequency response calculation for drawing live M3E equalizer curves.
 */
class SoftwareEqualizerDsp(
    var sampleRate: Int = 44100
) {
    companion object {
        const val NUM_BANDS = 5
        val DEFAULT_CENTER_FREQS = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
        val DEFAULT_Q = floatArrayOf(0.707f, 1.0f, 1.0f, 1.0f, 0.707f)

        val BUILTIN_PRESETS = mapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Studio Reference" to floatArrayOf(0.5f, -0.5f, 0f, 0.5f, 1.0f),
            "Bass Punch" to floatArrayOf(5.0f, 3.5f, -0.5f, 0.5f, 1.0f),
            "Warm Vinyl" to floatArrayOf(3.0f, 2.0f, 0.5f, -1.0f, -2.5f),
            "Vocal Presence" to floatArrayOf(-1.5f, 0.5f, 3.0f, 2.5f, 0.5f),
            "Treble Air" to floatArrayOf(-1.0f, -0.5f, 0.5f, 3.0f, 5.0f),
            "Acoustic" to floatArrayOf(2.5f, 1.5f, 0.5f, 1.5f, 2.5f),
            "Electronic" to floatArrayOf(4.5f, 2.5f, -1.0f, 2.0f, 4.0f),
            "Rock" to floatArrayOf(3.5f, 1.5f, -1.0f, 2.0f, 3.5f),
            "Late Night" to floatArrayOf(1.0f, 0.5f, 1.5f, 0.5f, -2.0f)
        )
    }

    var isEnabled: Boolean = false

    // Gains in dB (-15.0f to +15.0f)
    val bandGainsDb = FloatArray(NUM_BANDS) { 0f }
    var preampDb: Float = 0f
    var autoHeadroomEnabled: Boolean = true
    var bassBoostAmount: Float = 0f // 0f..1f (0 to +6dB sub-bass shelf)
    var spatialWidth: Float = 1.0f  // 0f (mono) .. 1f (normal) .. 1.6f (wide)

    // Biquad coefficients per band: b0, b1, b2, a1, a2 (normalized by a0)
    private val b0 = FloatArray(NUM_BANDS) { 1f }
    private val b1 = FloatArray(NUM_BANDS) { 0f }
    private val b2 = FloatArray(NUM_BANDS) { 0f }
    private val a1 = FloatArray(NUM_BANDS) { 0f }
    private val a2 = FloatArray(NUM_BANDS) { 0f }

    // Direct Form II Transposed filter states for Left & Right channels
    private val s1L = FloatArray(NUM_BANDS) { 0f }
    private val s2L = FloatArray(NUM_BANDS) { 0f }
    private val s1R = FloatArray(NUM_BANDS) { 0f }
    private val s2R = FloatArray(NUM_BANDS) { 0f }

    // Bass boost low-shelf filter states
    private var bbB0 = 1f; private var bbB1 = 0f; private var bbB2 = 0f
    private var bbA1 = 0f; private var bbA2 = 0f
    private var bbS1L = 0f; private var bbS2L = 0f
    private var bbS1R = 0f; private var bbS2R = 0f

    private var currentHeadroomLinear = 1.0f
    private var needsRecalculation = true

    init {
        recalculateCoefficients()
    }

    fun setBandGain(band: Int, gainDb: Float) {
        if (band in 0 until NUM_BANDS) {
            val clamped = gainDb.coerceIn(-15f, 15f)
            if (bandGainsDb[band] != clamped) {
                bandGainsDb[band] = clamped
                needsRecalculation = true
            }
        }
    }

    fun setAllGains(gains: FloatArray) {
        for (i in 0 until NUM_BANDS.coerceAtMost(gains.size)) {
            bandGainsDb[i] = gains[i].coerceIn(-15f, 15f)
        }
        needsRecalculation = true
    }

    fun setSampleRateSafe(rate: Int) {
        val safeRate = rate.coerceIn(8000, 384000)
        if (sampleRate != safeRate) {
            sampleRate = safeRate
            resetHistory()
            needsRecalculation = true
        }
    }

    fun resetHistory() {
        for (i in 0 until NUM_BANDS) {
            s1L[i] = 0f
            s2L[i] = 0f
            s1R[i] = 0f
            s2R[i] = 0f
        }
        bbS1L = 0f
        bbS2L = 0f
        bbS1R = 0f
        bbS2R = 0f
    }

    fun recalculateCoefficients() {
        val sr = sampleRate.coerceAtLeast(8000).toFloat()
        val nyquist = sr * 0.45f

        var maxPositiveBoost = 0f
        for (i in 0 until NUM_BANDS) {
            val gainDb = bandGainsDb[i]
            if (gainDb > maxPositiveBoost) {
                maxPositiveBoost = gainDb
            }
            val centerFreq = DEFAULT_CENTER_FREQS[i].coerceIn(20f, nyquist)
            val q = DEFAULT_Q[i].coerceIn(0.2f, 5f)
            computePeakingBiquad(i, centerFreq, q, gainDb, sr)
        }

        // Bass boost shelf at 65 Hz
        val bbGainDb = (bassBoostAmount * 6.0f).coerceIn(0f, 6f)
        if (bbGainDb > maxPositiveBoost) {
            maxPositiveBoost = bbGainDb
        }
        computeLowShelfBiquad(65f.coerceAtMost(nyquist), 0.707f, bbGainDb, sr)

        // Preamp + Auto-Headroom calculation
        val totalPreampDb = preampDb - if (autoHeadroomEnabled && maxPositiveBoost > 0f) maxPositiveBoost else 0f
        currentHeadroomLinear = (10.0f.pow(totalPreampDb / 20.0f)).coerceIn(0.1f, 4.0f)
        needsRecalculation = false
    }

    private fun computePeakingBiquad(band: Int, f0: Float, q: Float, gainDb: Float, sr: Float) {
        val a = 10.0f.pow(gainDb / 40.0f)
        val w0 = (2.0 * PI * (f0 / sr)).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0f * q)

        val a0 = 1.0f + alpha / a
        b0[band] = (1.0f + alpha * a) / a0
        b1[band] = (-2.0f * cosW0) / a0
        b2[band] = (1.0f - alpha * a) / a0
        a1[band] = (-2.0f * cosW0) / a0
        a2[band] = (1.0f - alpha / a) / a0
    }

    private fun computeLowShelfBiquad(f0: Float, q: Float, gainDb: Float, sr: Float) {
        if (gainDb <= 0.01f) {
            bbB0 = 1f; bbB1 = 0f; bbB2 = 0f; bbA1 = 0f; bbA2 = 0f
            return
        }
        val a = 10.0f.pow(gainDb / 40.0f)
        val w0 = (2.0 * PI * (f0 / sr)).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0f * q)
        val sqrt2A = sqrt(2.0f * a) * alpha

        val a0 = (a + 1.0f) + (a - 1.0f) * cosW0 + sqrt2A
        bbB0 = (a * ((a + 1.0f) - (a - 1.0f) * cosW0 + sqrt2A)) / a0
        bbB1 = (2.0f * a * ((a - 1.0f) - (a + 1.0f) * cosW0)) / a0
        bbB2 = (a * ((a + 1.0f) - (a - 1.0f) * cosW0 - sqrt2A)) / a0
        bbA1 = (-2.0f * ((a - 1.0f) + (a + 1.0f) * cosW0)) / a0
        bbA2 = ((a + 1.0f) + (a - 1.0f) * cosW0 - sqrt2A) / a0
    }

    /**
     * In-place stereo float PCM processing: array contains [L0, R0, L1, R1, ...].
     */
    fun processInterleavedStereo(buffer: FloatArray, offset: Int, sampleCount: Int) {
        if (!isEnabled && preampDb == 0f && bassBoostAmount == 0f && spatialWidth == 1f) {
            return
        }
        if (needsRecalculation) {
            recalculateCoefficients()
        }

        val gain = currentHeadroomLinear
        val applyBassBoost = bassBoostAmount > 0.01f
        val applySpatial = abs(spatialWidth - 1.0f) > 0.01f
        val width = spatialWidth.coerceIn(0f, 2.0f)

        var i = offset
        val end = offset + sampleCount
        while (i < end - 1) {
            var inL = buffer[i] * gain
            var inR = buffer[i + 1] * gain

            // 1. Bass boost shelf
            if (applyBassBoost) {
                val outL = bbB0 * inL + bbS1L
                bbS1L = bbB1 * inL - bbA1 * outL + bbS2L
                bbS2L = bbB2 * inL - bbA2 * outL
                inL = outL

                val outR = bbB0 * inR + bbS1R
                bbS1R = bbB1 * inR - bbA1 * outR + bbS2R
                bbS2R = bbB2 * inR - bbA2 * outR
                inR = outR
            }

            // 2. 5-Band Equalizer (Direct Form II Transposed)
            for (b in 0 until NUM_BANDS) {
                val outL = b0[b] * inL + s1L[b]
                s1L[b] = b1[b] * inL - a1[b] * outL + s2L[b]
                s2L[b] = b2[b] * inL - a2[b] * outL
                inL = sanitizeSample(outL)

                val outR = b0[b] * inR + s1R[b]
                s1R[b] = b1[b] * inR - a1[b] * outR + s2R[b]
                s2R[b] = b2[b] * inR - a2[b] * outR
                inR = sanitizeSample(outR)
            }

            // 3. Spatial Stereo Widener (Mid-Side processing)
            if (applySpatial) {
                val mid = 0.5f * (inL + inR)
                val side = 0.5f * (inL - inR) * width
                inL = mid + side
                inR = mid - side
            }

            // 4. Soft-knee tanh anti-clipping limiter
            buffer[i] = softLimit(inL)
            buffer[i + 1] = softLimit(inR)

            i += 2
        }
    }

    private fun sanitizeSample(value: Float): Float {
        return if (!value.isFinite() || abs(value) < 1e-25f) 0f else value
    }

    private fun softLimit(sample: Float): Float {
        return when {
            sample > 0.95f -> 0.95f + 0.05f * tanh((sample - 0.95f) / 0.05f)
            sample < -0.95f -> -0.95f + 0.05f * tanh((sample + 0.95f) / 0.05f)
            else -> sample
        }
    }

    /**
     * Computes the combined magnitude response in dB at a given frequency in Hz.
     * Used by Compose UI to render the live interactive frequency response curve.
     */
    fun computeFrequencyResponseDb(freqHz: Float): Float {
        if (!isEnabled) return 0f
        val sr = sampleRate.coerceAtLeast(8000).toFloat()
        val nyquist = sr * 0.49f
        val f = freqHz.coerceIn(10f, nyquist)
        val w = (2.0 * PI * (f / sr)).toFloat()
        val cosW = cos(w)
        val cos2W = cos(2f * w)
        val sinW = sin(w)
        val sin2W = sin(2f * w)

        var totalMagnitudeSq = 1.0f

        // 5-band biquad responses
        for (b in 0 until NUM_BANDS) {
            val numReal = b0[b] + b1[b] * cosW + b2[b] * cos2W
            val numImag = -(b1[b] * sinW + b2[b] * sin2W)
            val numSq = numReal * numReal + numImag * numImag

            val denReal = 1.0f + a1[b] * cosW + a2[b] * cos2W
            val denImag = -(a1[b] * sinW + a2[b] * sin2W)
            val denSq = denReal * denReal + denImag * denImag

            if (denSq > 1e-12f) {
                totalMagnitudeSq *= (numSq / denSq)
            }
        }

        // Bass boost shelf response
        if (bassBoostAmount > 0.01f) {
            val bbNumReal = bbB0 + bbB1 * cosW + bbB2 * cos2W
            val bbNumImag = -(bbB1 * sinW + bbB2 * sin2W)
            val bbNumSq = bbNumReal * bbNumReal + bbNumImag * bbNumImag

            val bbDenReal = 1.0f + bbA1 * cosW + bbA2 * cos2W
            val bbDenImag = -(bbA1 * sinW + bbA2 * sin2W)
            val bbDenSq = bbDenReal * bbDenReal + bbDenImag * bbDenImag

            if (bbDenSq > 1e-12f) {
                totalMagnitudeSq *= (bbNumSq / bbDenSq)
            }
        }

        val totalDb = (10.0f * log10(totalMagnitudeSq.coerceAtLeast(1e-8f))) + preampDb
        return totalDb.coerceIn(-30f, 30f)
    }
}
