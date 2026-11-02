package com.demonlab.lune.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Multi-band dynamics & brickwall limiter wrapper safe across API 24–37.
 * Isolates all API 28+ DynamicsProcessing class references inside [Api28Impl] so Android 7.0–8.1
 * devices never trigger class verification / NoClassDefFoundError (Issue #170).
 */
class DynamicsEffect {
    private var dynamics: AudioEffect? = null
    private var secondaryDynamics: AudioEffect? = null
    private var currentPreset: Int = 0

    fun setup(sessionId: Int, isSecondary: Boolean, preset: Int) {
        release(isSecondary)
        currentPreset = preset
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || sessionId <= 0) return
        try {
            val d = Api28Impl.createAndApply(sessionId, preset)
            if (isSecondary) {
                secondaryDynamics = d
            } else {
                dynamics = d
            }
        } catch (_: Throwable) {
        }
    }

    fun setPreset(preset: Int) {
        currentPreset = preset
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            dynamics?.let { Api28Impl.applyPreset(it, preset) }
            secondaryDynamics?.let { Api28Impl.applyPreset(it, preset) }
        } catch (_: Throwable) {
        }
    }

    fun handover() {
        try {
            dynamics?.release()
        } catch (_: Throwable) {
        }
        dynamics = secondaryDynamics
        secondaryDynamics = null
    }

    fun release(isSecondary: Boolean = false) {
        if (isSecondary) {
            try {
                secondaryDynamics?.release()
            } catch (_: Throwable) {
            }
            secondaryDynamics = null
        } else {
            try {
                dynamics?.release()
            } catch (_: Throwable) {
            }
            dynamics = null
        }
    }

    fun releaseAll() {
        release(false)
        release(true)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28Impl {
        fun createAndApply(sessionId: Int, preset: Int): AudioEffect {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                false, 0,
                true, 5,
                false, 0,
                true
            ).build()
            val dp = DynamicsProcessing(0, sessionId, config)
            applyPreset(dp, preset)
            return dp
        }

        fun applyPreset(effect: AudioEffect, preset: Int) {
            val dp = effect as? DynamicsProcessing ?: return
            if (preset <= 0) {
                dp.enabled = false
                return
            }

            val params = when (preset) {
                1 -> floatArrayOf(2f, 50f, 1.5f, -20f, 0f, 0f)
                2 -> floatArrayOf(3f, 80f, 3.0f, -30f, 0f, 2f)
                3 -> floatArrayOf(4f, 100f, 5.0f, -40f, 3f, 5f)
                4 -> floatArrayOf(5f, 150f, 8.0f, -50f, 6f, 8f)
                else -> floatArrayOf(2f, 50f, 1.5f, -20f, 0f, 0f)
            }
            val attackMs = params[0]
            val releaseMs = params[1]
            val ratio = params[2]
            val thresholdDb = params[3]
            val preGainDb = params[4]
            val postGainDb = params[5]

            val cutoffs = floatArrayOf(120f, 450f, 1800f, 6000f, 20000f)
            for (b in 0 until 5) {
                val band = DynamicsProcessing.MbcBand(
                    true,
                    cutoffs[b],
                    attackMs,
                    releaseMs,
                    ratio,
                    thresholdDb,
                    2f,
                    -80f,
                    1f,
                    preGainDb,
                    postGainDb
                )
                dp.setMbcBandAllChannelsTo(b, band)
            }

            val limiterEnabled = preset >= 2
            val limitDb = when (preset) {
                2 -> -2f
                3 -> -3f
                else -> -5f
            }
            val limiter = DynamicsProcessing.Limiter(
                true,
                limiterEnabled,
                0,
                1f,
                if (preset == 4) 150f else 100f,
                10f,
                limitDb,
                0f
            )
            dp.setLimiterAllChannelsTo(limiter)
            dp.setInputGainAllChannelsTo(preGainDb)
            dp.enabled = true
        }
    }

    companion object {
        val presetNames = listOf("None", "Light", "Medium", "Strong", "Night")
        val presetValues = listOf(0, 1, 2, 3, 4)
    }
}
