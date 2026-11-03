package com.demonlab.lune.core.format

import kotlin.math.roundToInt

/**
 * Universal Audio & Multiplex Container Format Registry + ITU-R BS.775 Multichannel Stereo Downmixer
 * + Smart Gapless vs Crossfade Transition Planner.
 *
 * Pure Kotlin Multiplatform with zero AVX/SSE4 dependencies so it runs identically on
 * Intel Pentium E5800 (SSE2/SSSE3), single-core legacy PCs, and Android API 24+ devices.
 */
object AudioFormatRegistry {

    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        // Standard lossy & lossless audio
        "mp3", "flac", "wav", "wave", "ogg", "oga", "opus", "aac", "m4a", "m4b", "m4r",
        // Rare / Audiophile lossless & uncompressed
        "alac", "aiff", "aif", "aifc", "ape", "wv", "dsf", "dff", "caf", "au", "snd", "pcm",
        // Modern / Multichannel & Broadcast codecs
        "eac3", "ec3", "ac3", "ac4", "dts", "amr", "3ga", "wma",
        // Audio-in-Video / Multiplex containers
        "mp4", "mkv", "mka", "webm", "3gp", "ts", "mts", "avi", "mov", "mid", "midi"
    )

    private val LOSSLESS_EXTENSIONS: Set<String> = setOf(
        "flac", "wav", "wave", "alac", "aiff", "aif", "aifc", "ape", "wv", "dsf", "dff", "pcm"
    )

    private val EXTENDED_DECODER_EXTENSIONS: Set<String> = setOf(
        "alac", "aiff", "aif", "aifc", "ape", "wv", "dsf", "dff", "caf", "au", "snd", "pcm",
        "eac3", "ec3", "ac3", "ac4", "dts", "wma",
        "mkv", "mka", "mp4", "webm", "ts", "mts", "avi", "mov"
    )

    fun isSupportedAudioExtension(extensionOrFileName: String): Boolean {
        val ext = extensionOrFileName.substringAfterLast('.', extensionOrFileName).lowercase().trim()
        return ext in SUPPORTED_EXTENSIONS
    }

    fun isLosslessOrHiFi(extension: String, codecHint: String? = null): Boolean {
        val ext = extension.lowercase().trim()
        if (ext in LOSSLESS_EXTENSIONS) return true
        val hint = codecHint?.lowercase() ?: return false
        return hint.contains("alac") || hint.contains("flac") || hint.contains("pcm") ||
            hint.contains("wavpack") || hint.contains("ape") || hint.contains("truehd")
    }

    fun prefersExtendedDecoderOnAndroid(extension: String, channels: Int = 2): Boolean {
        val ext = extension.lowercase().trim()
        return ext in EXTENDED_DECODER_EXTENSIONS || channels > 2
    }

    fun formatBadgeLabel(extension: String, codecHint: String? = null, channels: Int? = null): String {
        val ext = extension.lowercase().trim()
        val hint = codecHint?.lowercase().orEmpty()
        val base = when {
            hint.contains("alac") || ext == "alac" -> "ALAC"
            hint.contains("eac3") || hint.contains("ec-3") || ext == "eac3" || ext == "ec3" -> "E-AC3"
            hint.contains("ac-4") || hint.contains("ac4") || ext == "ac4" -> "AC-4"
            hint.contains("ac3") || hint.contains("ac-3") || ext == "ac3" -> "AC-3"
            hint.contains("opus") || ext == "opus" -> "OPUS"
            hint.contains("flac") || ext == "flac" -> "FLAC"
            ext == "aiff" || ext == "aif" || ext == "aifc" -> "AIFF"
            ext == "wv" -> "WAVPACK"
            ext == "ape" -> "APE"
            ext == "dsf" || ext == "dff" -> "DSD"
            ext == "wav" || ext == "wave" -> "WAV"
            ext == "mka" -> "MKA"
            ext == "mkv" -> "MKV"
            ext == "mp4" -> "MP4"
            ext == "webm" -> "WEBM"
            ext == "m4a" || ext == "m4b" || ext == "aac" -> "AAC/M4A"
            ext == "ogg" || ext == "oga" -> "OGG"
            ext == "wma" -> "WMA"
            ext == "caf" -> "CAF"
            ext == "dts" -> "DTS"
            ext == "mp3" -> "MP3"
            ext.isNotEmpty() -> ext.uppercase()
            else -> "AUDIO"
        }
        return if (channels != null && channels > 2) {
            val chLabel = when (channels) {
                6 -> "5.1"
                8 -> "7.1"
                else -> "${channels}ch"
            }
            "$base $chLabel"
        } else {
            base
        }
    }

    /**
     * Decides whether the transition between [currentAlbum]/[currentTrackNumber] and
     * [nextAlbum]/[nextTrackNumber] should use sample-accurate Gapless playback rather than Crossfade.
     */
    fun shouldUseGaplessTransition(
        gaplessEnabled: Boolean,
        crossfadeEnabled: Boolean,
        automixEnabled: Boolean,
        respectAlbumGapless: Boolean,
        isShuffleActive: Boolean,
        currentAlbum: String?,
        currentTrackNumber: Int,
        nextAlbum: String?,
        nextTrackNumber: Int
    ): Boolean {
        if (!gaplessEnabled) return false
        val anyFadeEnabled = crossfadeEnabled || automixEnabled
        if (!anyFadeEnabled) return true

        // When "Respect Gapless in Continuous Albums" is enabled and not shuffling,
        // consecutive tracks on the same album (Track N -> Track N+1) play gaplessly!
        if (respectAlbumGapless && !isShuffleActive &&
            !currentAlbum.isNullOrBlank() && !nextAlbum.isNullOrBlank() &&
            !currentAlbum.equals("<unknown>", ignoreCase = true) &&
            currentAlbum.trim().equals(nextAlbum.trim(), ignoreCase = true) &&
            currentTrackNumber > 0 && nextTrackNumber == currentTrackNumber + 1
        ) {
            return true
        }
        return false
    }

    /**
     * Pure integer/fixed-point ITU-R BS.775 multichannel 16-bit LE PCM to Stereo 16-bit LE PCM downmixer.
     * Zero floating-point SIMD/AVX requirement: runs fast even on single-core CPUs without SSE4/AVX.
     *
     * Supports 1ch (Mono -> Stereo), 3ch (L, R, C), 4ch (Quad), 5ch, 5.1 (6ch), and 7.1 (8ch).
     * Writes into [outStereoBuffer] (resized only if needed) and returns valid output byte count.
     */
    fun downmixPcm16leToStereo(
        inBuffer: ByteArray,
        inValidBytes: Int,
        inChannels: Int,
        outStereoBuffer: ByteArray
    ): Int {
        if (inChannels <= 0 || inValidBytes <= 0) return 0
        if (inChannels == 2) {
            val copyLen = minOf(inValidBytes, outStereoBuffer.size)
            inBuffer.copyInto(outStereoBuffer, 0, 0, copyLen)
            return copyLen
        }

        val inFrameSize = inChannels * 2
        val frames = inValidBytes / inFrameSize
        val maxFrames = outStereoBuffer.size / 4
        val count = minOf(frames, maxFrames)

        // Q10 fixed-point coefficients (1024 = 1.0, 724 = 0.7071 [-3 dB], 512 = 0.5 [-6 dB])
        var f = 0
        while (f < count) {
            val inBase = f * inFrameSize
            val outBase = f * 4

            var leftQ10: Int
            var rightQ10: Int

            when (inChannels) {
                1 -> {
                    val mono = readS16le(inBuffer, inBase)
                    leftQ10 = mono shl 10
                    rightQ10 = mono shl 10
                }
                3 -> {
                    val fl = readS16le(inBuffer, inBase)
                    val fr = readS16le(inBuffer, inBase + 2)
                    val fc = readS16le(inBuffer, inBase + 4)
                    leftQ10 = (fl * 724) + (fc * 512)
                    rightQ10 = (fr * 724) + (fc * 512)
                }
                4 -> {
                    val fl = readS16le(inBuffer, inBase)
                    val fr = readS16le(inBuffer, inBase + 2)
                    val bl = readS16le(inBuffer, inBase + 4)
                    val br = readS16le(inBuffer, inBase + 6)
                    leftQ10 = (fl * 640) + (bl * 452)
                    rightQ10 = (fr * 640) + (br * 452)
                }
                5 -> {
                    val fl = readS16le(inBuffer, inBase)
                    val fr = readS16le(inBuffer, inBase + 2)
                    val fc = readS16le(inBuffer, inBase + 4)
                    val bl = readS16le(inBuffer, inBase + 6)
                    val br = readS16le(inBuffer, inBase + 8)
                    leftQ10 = (fl * 580) + (fc * 410) + (bl * 410)
                    rightQ10 = (fr * 580) + (fc * 410) + (br * 410)
                }
                6 -> {
                    // Standard 5.1: FL, FR, FC, LFE, BL, BR
                    val fl = readS16le(inBuffer, inBase)
                    val fr = readS16le(inBuffer, inBase + 2)
                    val fc = readS16le(inBuffer, inBase + 4)
                    val lfe = readS16le(inBuffer, inBase + 6)
                    val bl = readS16le(inBuffer, inBase + 8)
                    val br = readS16le(inBuffer, inBase + 10)
                    leftQ10 = (fl * 560) + (fc * 396) + (bl * 396) + (lfe * 180)
                    rightQ10 = (fr * 560) + (fc * 396) + (br * 396) + (lfe * 180)
                }
                else -> {
                    // 7.1 (8ch) or N-channel: FL, FR, FC, LFE, BL, BR, SL, SR
                    val fl = readS16le(inBuffer, inBase)
                    val fr = readS16le(inBuffer, inBase + 2)
                    val fc = if (inChannels >= 3) readS16le(inBuffer, inBase + 4) else 0
                    val lfe = if (inChannels >= 4) readS16le(inBuffer, inBase + 6) else 0
                    val bl = if (inChannels >= 5) readS16le(inBuffer, inBase + 8) else 0
                    val br = if (inChannels >= 6) readS16le(inBuffer, inBase + 10) else 0
                    val sl = if (inChannels >= 7) readS16le(inBuffer, inBase + 12) else 0
                    val sr = if (inChannels >= 8) readS16le(inBuffer, inBase + 14) else 0
                    leftQ10 = (fl * 480) + (fc * 340) + (bl * 300) + (sl * 300) + (lfe * 150)
                    rightQ10 = (fr * 480) + (fc * 340) + (br * 300) + (sr * 300) + (lfe * 150)
                }
            }

            val outL = (leftQ10 shr 10).coerceIn(-32768, 32767)
            val outR = (rightQ10 shr 10).coerceIn(-32768, 32767)

            outStereoBuffer[outBase] = (outL and 0xFF).toByte()
            outStereoBuffer[outBase + 1] = ((outL shr 8) and 0xFF).toByte()
            outStereoBuffer[outBase + 2] = (outR and 0xFF).toByte()
            outStereoBuffer[outBase + 3] = ((outR shr 8) and 0xFF).toByte()
            f++
        }

        return count * 4
    }

    /**
     * Applies linear scalar gain (e.g. ReplayGain + Peak Limiter) in-place to 16-bit LE PCM samples
     * using fast Q12 fixed-point integer math (zero floating-point overhead per sample).
     */
    fun applyGainPcm16leInPlace(buffer: ByteArray, validBytes: Int, linearGain: Float) {
        if (validBytes <= 1 || linearGain in 0.995f..1.005f) return
        val gainQ12 = (linearGain.coerceIn(0f, 4.0f) * 4096f).roundToInt()
        var i = 0
        val limit = validBytes - 1
        while (i < limit) {
            val sample = readS16le(buffer, i)
            val scaled = ((sample * gainQ12) shr 12).coerceIn(-32768, 32767)
            buffer[i] = (scaled and 0xFF).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun readS16le(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() // sign-extended
        return (hi shl 8) or lo
    }
}
