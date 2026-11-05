@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.demonlab.lune.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import com.demonlab.lune.core.dsp.StreamingLufsAnalyzer
import com.demonlab.lune.core.format.AudioFormatRegistry
import com.demonlab.lune.core.format.DecodedS16leStream
import com.demonlab.lune.core.format.NativePcmContainerDecoder
import com.demonlab.lune.core.format.SeekableByteReader
import com.demonlab.lune.tools.Song
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * Universal Bit-Depth + ITU-R BS.775 Multichannel-to-Stereo Downmix + Real-Time BS.1770 LUFS
 * + Sample-Accurate ReplayGain AudioProcessor for Media3 ExoPlayer.
 *
 * Accepts 8/16/24/32-bit Int (LE/BE) and 32-bit Float PCM across 1..8 channels so 24-bit ALAC,
 * 24/32-bit FLAC, 5.1/7.1 float Opus, E-AC3, AC-3, AC-4, DTS, MKV/MKA, MP4, WebM, MP3, M4A,
 * and OGG streams play gaplessly on any stereo Android DAC while applying click-free per-track
 * ReplayGain/LUFS normalization and calculating live K-weighted LUFS on untagged tracks.
 */
class MultichannelStereoBalanceProcessor : BaseAudioProcessor() {

    @Volatile var leftBalanceScalar: Float = 1.0f
    @Volatile var rightBalanceScalar: Float = 1.0f
    @Volatile var targetReplayGainScalar: Float = 1.0f
    @Volatile private var currentReplayGainScalar: Float = 1.0f
    @Volatile var liveLufsEnabled: Boolean = false
    @Volatile var activeSongPath: String = ""

    var onRealtimeLoudnessMeasured: ((songPath: String, gainDb: Float, peak: Float) -> Unit)? = null
    val softwareEqDsp = com.demonlab.lune.core.dsp.SoftwareEqualizerDsp(48000)
    private var eqFloatTemp = FloatArray(0)

    private var inTemp = ByteArray(0)
    private var s16MultiTemp = ByteArray(0)
    private var outTemp = ByteArray(0)

    private val lufsAnalyzer = StreamingLufsAnalyzer(48000, 2)
    private var reportedEarlyEstimate = false
    private var reportedRefinedEstimate = false
    private var trackedPathForAnalyzer: String = ""

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val supported = enc == C.ENCODING_PCM_16BIT ||
            enc == C.ENCODING_PCM_16BIT_BIG_ENDIAN ||
            enc == C.ENCODING_PCM_24BIT ||
            enc == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
            enc == C.ENCODING_PCM_32BIT ||
            enc == C.ENCODING_PCM_32BIT_BIG_ENDIAN ||
            enc == C.ENCODING_PCM_FLOAT ||
            enc == C.ENCODING_PCM_8BIT
        if (!supported || inputAudioFormat.channelCount !in 1..8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        lufsAnalyzer.reconfigure(inputAudioFormat.sampleRate, 2)
        reportedEarlyEstimate = false
        reportedRefinedEstimate = false
        trackedPathForAnalyzer = activeSongPath
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            2,
            C.ENCODING_PCM_16BIT
        )
    }

    fun setReplayGainScalar(scalar: Float, immediate: Boolean = false) {
        val clamped = scalar.coerceIn(0.03f, 3.98f)
        targetReplayGainScalar = clamped
        if (immediate) {
            currentReplayGainScalar = clamped
        }
    }

    fun resetLiveAnalyzerForTrack(songPath: String, needsAnalysis: Boolean) {
        activeSongPath = songPath
        liveLufsEnabled = needsAnalysis
        trackedPathForAnalyzer = songPath
        reportedEarlyEstimate = false
        reportedRefinedEstimate = false
        lufsAnalyzer.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val inChannels = inputAudioFormat.channelCount.coerceIn(1, 8)
        val encoding = inputAudioFormat.encoding
        val bytesPerInSample = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

        if (inTemp.size < remaining) {
            inTemp = ByteArray(remaining)
        }
        inputBuffer.get(inTemp, 0, remaining)

        val inFrames = remaining / (inChannels * bytesPerInSample)
        if (inFrames <= 0) return

        // Step 1: Convert input encoding to 16-bit LE multi-channel if not already PCM_16BIT LE
        val s16BytesCount = inFrames * inChannels * 2
        val s16Source: ByteArray = if (encoding == C.ENCODING_PCM_16BIT) {
            inTemp
        } else {
            if (s16MultiTemp.size < s16BytesCount) {
                s16MultiTemp = ByteArray(s16BytesCount)
            }
            convertAnyPcmToS16le(
                src = inTemp,
                totalSamples = inFrames * inChannels,
                encoding = encoding,
                dst = s16MultiTemp
            )
            s16MultiTemp
        }

        // Step 2: Downmix 1..8ch -> Stereo 16-bit LE
        val outBytesNeeded = inFrames * 4
        if (outTemp.size < outBytesNeeded) {
            outTemp = ByteArray(outBytesNeeded)
        }
        val writtenBytes = AudioFormatRegistry.downmixPcm16leToStereo(
            inBuffer = s16Source,
            inValidBytes = s16BytesCount,
            inChannels = inChannels,
            outStereoBuffer = outTemp
        )

        // Step 3: Real-time K-weighted LUFS measurement on unscaled audio for untagged tracks
        if (liveLufsEnabled && writtenBytes > 0) {
            val curPath = activeSongPath
            if (curPath != trackedPathForAnalyzer) {
                trackedPathForAnalyzer = curPath
                reportedEarlyEstimate = false
                reportedRefinedEstimate = false
                lufsAnalyzer.reset()
            }
            if (!reportedRefinedEstimate && curPath.isNotBlank()) {
                lufsAnalyzer.processPcm16le(outTemp, 0, writtenBytes)
                val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
                val framesSeen = lufsAnalyzer.processedFrames
                if (!reportedEarlyEstimate && framesSeen >= sr * 2L) {
                    reportedEarlyEstimate = true
                    lufsAnalyzer.currentReplayGainDbAndPeak()?.let { (gainDb, peak) ->
                        onRealtimeLoudnessMeasured?.invoke(curPath, gainDb, peak)
                    }
                } else if (!reportedRefinedEstimate && framesSeen >= sr * 12L) {
                    reportedRefinedEstimate = true
                    lufsAnalyzer.currentReplayGainDbAndPeak()?.let { (gainDb, peak) ->
                        onRealtimeLoudnessMeasured?.invoke(curPath, gainDb, peak)
                    }
                }
            }
        }

        // Step 4: Click-free per-track ReplayGain/LUFS + Stereo Balance scaling
        val lBal = leftBalanceScalar
        val rBal = rightBalanceScalar
        val targetRg = targetReplayGainScalar
        var curRg = currentReplayGainScalar
        val needsScaling = abs(targetRg - 1.0f) > 0.001f ||
            abs(curRg - 1.0f) > 0.001f ||
            lBal < 0.999f ||
            rBal < 0.999f

        if (needsScaling) {
            var i = 0
            while (i + 3 < writtenBytes) {
                curRg += (targetRg - curRg) * 0.004f
                val lGain = curRg * lBal
                val rGain = curRg * rBal

                val lLo = outTemp[i].toInt() and 0xFF
                val lHi = outTemp[i + 1].toInt()
                val lScaled = (((lHi shl 8) or lLo) * lGain).toInt().coerceIn(-32768, 32767)
                outTemp[i] = (lScaled and 0xFF).toByte()
                outTemp[i + 1] = ((lScaled shr 8) and 0xFF).toByte()

                val rLo = outTemp[i + 2].toInt() and 0xFF
                val rHi = outTemp[i + 3].toInt()
                val rScaled = (((rHi shl 8) or rLo) * rGain).toInt().coerceIn(-32768, 32767)
                outTemp[i + 2] = (rScaled and 0xFF).toByte()
                outTemp[i + 3] = ((rScaled shr 8) and 0xFF).toByte()
                i += 4
            }
            if (abs(targetRg - curRg) < 0.0005f) {
                curRg = targetRg
            }
            currentReplayGainScalar = curRg
        }

        // Step 5: Software Equalizer DSP (Parametric Biquads + Preamp + Anti-Clipping Limiter)
        if (softwareEqDsp.isEnabled || softwareEqDsp.preampDb != 0f || softwareEqDsp.bassBoostAmount > 0f) {
            val totalStereoSamples = writtenBytes / 2
            if (eqFloatTemp.size < totalStereoSamples) {
                eqFloatTemp = FloatArray(totalStereoSamples)
            }
            softwareEqDsp.setSampleRateSafe(inputAudioFormat.sampleRate.coerceAtLeast(8000))

            var si = 0
            var fi = 0
            while (si + 1 < writtenBytes) {
                val lo = outTemp[si].toInt() and 0xFF
                val hi = outTemp[si + 1].toInt()
                eqFloatTemp[fi++] = ((hi shl 8) or lo) / 32768.0f
                si += 2
            }

            softwareEqDsp.processInterleavedStereo(eqFloatTemp, 0, totalStereoSamples)

            si = 0
            fi = 0
            while (si + 1 < writtenBytes) {
                val sample = (eqFloatTemp[fi++] * 32767.0f).toInt().coerceIn(-32768, 32767)
                outTemp[si] = (sample and 0xFF).toByte()
                outTemp[si + 1] = ((sample shr 8) and 0xFF).toByte()
                si += 2
            }
        }

        val outBuf = replaceOutputBuffer(writtenBytes).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.put(outTemp, 0, writtenBytes)
        outBuf.flip()
    }

    private fun convertAnyPcmToS16le(src: ByteArray, totalSamples: Int, encoding: Int, dst: ByteArray) {
        var s = 0
        when (encoding) {
            C.ENCODING_PCM_8BIT -> {
                while (s < totalSamples) {
                    val v = ((src[s].toInt() and 0xFF) - 128) shl 8
                    val d = s * 2
                    dst[d] = (v and 0xFF).toByte()
                    dst[d + 1] = ((v shr 8) and 0xFF).toByte()
                    s++
                }
            }
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                while (s < totalSamples) {
                    val o = s * 2
                    dst[o] = src[o + 1]
                    dst[o + 1] = src[o]
                    s++
                }
            }
            C.ENCODING_PCM_24BIT -> {
                while (s < totalSamples) {
                    val o = s * 3
                    val d = s * 2
                    dst[d] = src[o + 1]
                    dst[d + 1] = src[o + 2]
                    s++
                }
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                while (s < totalSamples) {
                    val o = s * 3
                    val d = s * 2
                    dst[d] = src[o + 1]
                    dst[d + 1] = src[o]
                    s++
                }
            }
            C.ENCODING_PCM_32BIT -> {
                while (s < totalSamples) {
                    val o = s * 4
                    val d = s * 2
                    dst[d] = src[o + 2]
                    dst[d + 1] = src[o + 3]
                    s++
                }
            }
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                while (s < totalSamples) {
                    val o = s * 4
                    val d = s * 2
                    dst[d] = src[o + 1]
                    dst[d + 1] = src[o]
                    s++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (s < totalSamples) {
                    val o = s * 4
                    val bits = (src[o].toInt() and 0xFF) or
                        ((src[o + 1].toInt() and 0xFF) shl 8) or
                        ((src[o + 2].toInt() and 0xFF) shl 16) or
                        ((src[o + 3].toInt() and 0xFF) shl 24)
                    val f = Float.fromBits(bits)
                    val v = if (!f.isFinite()) 0 else (f * 32767.0f).toInt().coerceIn(-32768, 32767)
                    val d = s * 2
                    dst[d] = (v and 0xFF).toByte()
                    dst[d + 1] = ((v shr 8) and 0xFF).toByte()
                    s++
                }
            }
        }
    }
}

/**
 * Extended format, ReplayGain, and sample-accurate Gapless playback engine for Flow on Android.
 *
 * Dual-tier architecture with tight memory caps (< 6 MB buffer ceiling):
 * 1. Media3 ExoPlayer + [MultichannelStereoBalanceProcessor]: Pre-buffers the next track in the
 *    playlist queue for true zero-gap transitions across MP3 (Xing/LAME/iTunSMPB gapless tags),
 *    FLAC, ALAC, WAV, MP4, M4A, MKV/MKA, WebM, Opus (Stereo & 5.1/7.1), E-AC3, AC-3, AC-4, DTS,
 *    WMA, OGG, and AAC.
 * 2. Pure-Kotlin [NativePcmContainerDecoder] + [AudioTrack]: AIFF/AIF/AIFC, CAF, DSF (DSD64/128/256),
 *    DFF/DSDIFF, AU/SND — guaranteed gapless playback on ALL Android 7.0+ devices with zero JNI bloat.
 */
class ExtendedFormatPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stereoProcessor = MultichannelStereoBalanceProcessor()
    private var exoPlayer: ExoPlayer? = null
    private var queuedGaplessSong: Song? = null
    private var queuedGaplessGainScalar: Float = 1.0f
    private var currentActiveSong: Song? = null

    // Native PCM/DSD AudioTrack state (AIFF, AIFC, CAF, DSF, DFF, AU)
    @Volatile private var usingNativeTrackEngine: Boolean = false
    @Volatile private var nativeAudioTrack: AudioTrack? = null
    @Volatile private var nativeThread: Thread? = null
    @Volatile private var nativeStopRequested: Boolean = false
    @Volatile private var nativePaused: Boolean = false
    @Volatile private var nativeSeekPendingMs: Long = -1L
    @Volatile private var nativePositionMs: Int = 0
    @Volatile private var nativeDurationMs: Int = 0
    @Volatile private var nativeLoopSingle: Boolean = false
    @Volatile private var nativeQueuedNextSong: Song? = null
    @Volatile private var nativeQueuedNextGainScalar: Float = 1.0f
    @Volatile private var nativeTargetGainScalar: Float = 1.0f
    @Volatile private var leftVol: Float = 1.0f
    @Volatile private var rightVol: Float = 1.0f

    var onPrepared: ((audioSessionId: Int) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onGaplessTransition: ((nextSongId: Long) -> Unit)? = null
    var onRealtimeLoudnessMeasured: ((songPath: String, gainDb: Float, peak: Float) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    init {
        stereoProcessor.onRealtimeLoudnessMeasured = { path, gainDb, peak ->
            mainHandler.post { onRealtimeLoudnessMeasured?.invoke(path, gainDb, peak) }
        }
    }

    val isActive: Boolean
        get() = usingNativeTrackEngine || exoPlayer != null

    val isPlaying: Boolean
        get() = if (usingNativeTrackEngine) {
            nativeAudioTrack != null && !nativePaused && !nativeStopRequested
        } else {
            exoPlayer?.isPlaying == true
        }

    val currentPositionMs: Int
        get() = if (usingNativeTrackEngine) {
            nativePositionMs.coerceAtLeast(0)
        } else {
            (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L).toInt()
        }

    val durationMs: Int
        get() = if (usingNativeTrackEngine) {
            nativeDurationMs.coerceAtLeast(0)
        } else {
            (exoPlayer?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L).toInt()
        }

    val audioSessionId: Int
        get() = if (usingNativeTrackEngine) {
            nativeAudioTrack?.audioSessionId ?: 0
        } else {
            exoPlayer?.audioSessionId ?: 0
        }

    val queuedNextSongId: Long?
        get() = queuedGaplessSong?.id

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(stereoProcessor))
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        // Memory-capped LoadControl with zero back-buffer retention and 6 MB forward window
        // so ExoPlayer pre-buffers the next track in the playlist queue ahead of track end.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 3000,
                /* maxBufferMs = */ 10000,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 900
            )
            .setTargetBufferBytes(6 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .build()

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        var notifiedPreparedForCurrentItem = false

        val created = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(attrs, false)
            .setPauseAtEndOfMediaItems(false)
            .build()

        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !notifiedPreparedForCurrentItem) {
                    notifiedPreparedForCurrentItem = true
                    onPrepared?.invoke(created.audioSessionId)
                } else if (playbackState == Player.STATE_ENDED) {
                    onCompletion?.invoke()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                    // Prune finished head items so internal queue stays bounded to [current, next]
                    if (created.currentMediaItemIndex > 0) {
                        created.removeMediaItems(0, created.currentMediaItemIndex)
                    }
                    val promotedSong = queuedGaplessSong
                    val promotedGain = queuedGaplessGainScalar
                    queuedGaplessSong = null
                    queuedGaplessGainScalar = 1.0f

                    if (promotedSong != null) {
                        currentActiveSong = promotedSong
                        val needsLiveLufs = !promotedSong.toReplayGainMetadata().hasEmbeddedTags &&
                            promotedSong.computedLoudnessDb == null
                        stereoProcessor.resetLiveAnalyzerForTrack(promotedSong.path, needsLiveLufs)
                        setTrackReplayGainScalar(promotedGain, immediate = false)
                    }

                    val transitionedId = mediaItem.mediaId.toLongOrNull() ?: promotedSong?.id
                    if (transitionedId != null) {
                        onGaplessTransition?.invoke(transitionedId)
                    }
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) {
                    notifiedPreparedForCurrentItem = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val fallbackSong = currentActiveSong
                if (fallbackSong != null && tryStartNativeContainerEngine(
                        song = fallbackSong,
                        loopSingle = created.repeatMode == Player.REPEAT_MODE_ONE,
                        speed = created.playbackParameters.speed,
                        pitch = created.playbackParameters.pitch
                    )
                ) {
                    runCatching { created.stop() }
                    return
                }
                Log.e("ExtendedFormatPlayer", "ExoPlayer playback error: ${error.message}", error)
                onError?.invoke(error)
            }
        })

        exoPlayer = created
        return created
    }

    private fun buildMediaItem(song: Song): MediaItem {
        val resolvedUri = if (song.path.isNotBlank() && File(song.path).canRead()) {
            Uri.fromFile(File(song.path))
        } else {
            song.uri
        }
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(resolvedUri)
            .build()
    }

    private fun openSongReader(song: Song): SeekableByteReader? {
        if (song.path.isNotBlank()) {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                val raf = RandomAccessFile(file, "r")
                return object : SeekableByteReader {
                    override val length: Long get() = raf.length()
                    override var position: Long
                        get() = raf.filePointer
                        set(value) { raf.seek(value.coerceAtLeast(0L)) }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
                        raf.read(buffer, offset, count)
                    override fun close() = runCatching { raf.close() }.let {}
                }
            }
        }
        return runCatching {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(song.uri, "r") ?: return null
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel: FileChannel = fis.channel
            object : SeekableByteReader {
                override val length: Long get() = channel.size()
                override var position: Long
                    get() = channel.position()
                    set(value) { channel.position(value.coerceAtLeast(0L)) }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    val bb = ByteBuffer.wrap(buffer, offset, count)
                    return channel.read(bb)
                }
                override fun close() {
                    runCatching { channel.close() }
                    runCatching { fis.close() }
                    runCatching { pfd.close() }
                }
            }
        }.getOrNull()
    }

    private fun tryStartNativeContainerEngine(
        song: Song,
        loopSingle: Boolean,
        speed: Float,
        pitch: Float
    ): Boolean {
        val reader = openSongReader(song) ?: return false
        val decoded: DecodedS16leStream = try {
            NativePcmContainerDecoder.open(reader)
        } catch (_: Exception) {
            null
        } ?: run {
            reader.close()
            return false
        }

        stopNativeTrackEngine()
        exoPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        exoPlayer = null

        usingNativeTrackEngine = true
        nativeStopRequested = false
        nativePaused = false
        nativeLoopSingle = loopSingle
        nativeSeekPendingMs = -1L
        nativePositionMs = 0
        nativeDurationMs = if (decoded.durationMs > 0L) decoded.durationMs.toInt() else song.duration.toInt()

        val outRate = decoded.outputSampleRate.coerceIn(8000, 192000)
        val minBuf = AudioTrack.getMinBufferSize(
            outRate,
            PlatformAudioFormat.CHANNEL_OUT_STEREO,
            PlatformAudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(16384)
        val trackBufBytes = (minBuf * 2).coerceIn(32768, 131072)

        val audioTrack = AudioTrack(
            PlatformAudioAttributes.Builder()
                .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            PlatformAudioFormat.Builder()
                .setSampleRate(outRate)
                .setChannelMask(PlatformAudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT)
                .build(),
            trackBufBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        applyNativePlaybackParams(audioTrack, speed, pitch)
        audioTrack.setStereoVolume(leftVol, rightVol)
        audioTrack.play()
        nativeAudioTrack = audioTrack

        val sessionId = audioTrack.audioSessionId
        mainHandler.post { onPrepared?.invoke(sessionId) }

        val worker = Thread({
            var activeSong = song
            var activeStream: DecodedS16leStream? = decoded
            val pcmBuf = ByteArray(16384)
            val lufs = StreamingLufsAnalyzer(outRate, 2)
            var needsLufs = !activeSong.toReplayGainMetadata().hasEmbeddedTags && activeSong.computedLoudnessDb == null
            var reportedEarly = false
            var reportedRefined = false
            var framesWrittenForTrack = 0L
            var currentGain = nativeTargetGainScalar

            try {
                while (!nativeStopRequested && activeStream != null) {
                    val stream = activeStream
                    if (nativePaused) {
                        Thread.sleep(35L)
                        continue
                    }

                    val pendingSeek = nativeSeekPendingMs
                    if (pendingSeek >= 0L) {
                        nativeSeekPendingMs = -1L
                        stream.seekToMs(pendingSeek)
                        framesWrittenForTrack = (pendingSeek * stream.outputSampleRate) / 1000L
                        nativePositionMs = pendingSeek.toInt()
                    }

                    val n = stream.readStereoS16le(pcmBuf, 0, pcmBuf.size)
                    if (n <= 0) {
                        if (nativeLoopSingle) {
                            stream.seekToMs(0L)
                            framesWrittenForTrack = 0L
                            nativePositionMs = 0
                            continue
                        }
                        val nextQueued = nativeQueuedNextSong
                        val nextGain = nativeQueuedNextGainScalar
                        if (nextQueued != null) {
                            val nextReader = openSongReader(nextQueued)
                            val nextDecoded = nextReader?.let { r ->
                                NativePcmContainerDecoder.open(r) ?: run { r.close(); null }
                            }
                            if (nextDecoded != null && nextDecoded.outputSampleRate == stream.outputSampleRate) {
                                stream.close()
                                activeSong = nextQueued
                                currentActiveSong = nextQueued
                                activeStream = nextDecoded
                                nativeQueuedNextSong = null
                                queuedGaplessSong = null
                                nativeTargetGainScalar = nextGain
                                framesWrittenForTrack = 0L
                                nativePositionMs = 0
                                nativeDurationMs = if (nextDecoded.durationMs > 0L) nextDecoded.durationMs.toInt() else nextQueued.duration.toInt()
                                needsLufs = !nextQueued.toReplayGainMetadata().hasEmbeddedTags && nextQueued.computedLoudnessDb == null
                                reportedEarly = false
                                reportedRefined = false
                                lufs.reconfigure(nextDecoded.outputSampleRate, 2)
                                val transitionedId = nextQueued.id
                                mainHandler.post { onGaplessTransition?.invoke(transitionedId) }
                                continue
                            } else {
                                nextDecoded?.close()
                            }
                        }
                        break
                    }

                    if (needsLufs && !reportedRefined && activeSong.path.isNotBlank()) {
                        lufs.processPcm16le(pcmBuf, 0, n)
                        val seen = lufs.processedFrames
                        if (!reportedEarly && seen >= stream.outputSampleRate * 2L) {
                            reportedEarly = true
                            val songPath = activeSong.path
                            lufs.currentReplayGainDbAndPeak()?.let { (g, pk) ->
                                mainHandler.post { onRealtimeLoudnessMeasured?.invoke(songPath, g, pk) }
                            }
                        } else if (!reportedRefined && seen >= stream.outputSampleRate * 12L) {
                            reportedRefined = true
                            val songPath = activeSong.path
                            lufs.currentReplayGainDbAndPeak()?.let { (g, pk) ->
                                mainHandler.post { onRealtimeLoudnessMeasured?.invoke(songPath, g, pk) }
                            }
                        }
                    }

                    // Apply ReplayGain/LUFS multiplier smoothly in PCM sample domain
                    val targetGain = nativeTargetGainScalar
                    if (abs(targetGain - 1.0f) > 0.001f || abs(currentGain - 1.0f) > 0.001f) {
                        var sIdx = 0
                        while (sIdx + 1 < n) {
                            currentGain += (targetGain - currentGain) * 0.002f
                            val lo = pcmBuf[sIdx].toInt() and 0xFF
                            val hi = pcmBuf[sIdx + 1].toInt()
                            val scaled = (((hi shl 8) or lo) * currentGain).toInt().coerceIn(-32768, 32767)
                            pcmBuf[sIdx] = (scaled and 0xFF).toByte()
                            pcmBuf[sIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
                            sIdx += 2
                        }
                        if (abs(targetGain - currentGain) < 0.0005f) {
                            currentGain = targetGain
                        }
                    }

                    var offset = 0
                    while (offset < n && !nativeStopRequested) {
                        if (nativePaused) break
                        val w = audioTrack.write(pcmBuf, offset, n - offset)
                        if (w <= 0) break
                        offset += w
                    }
                    framesWrittenForTrack += (n / 4)
                    nativePositionMs = ((framesWrittenForTrack * 1000L) / stream.outputSampleRate).toInt()
                }
            } catch (t: Throwable) {
                if (!nativeStopRequested) {
                    Log.e("ExtendedFormatPlayer", "Native container stream error: ${t.message}", t)
                    mainHandler.post { onError?.invoke(t) }
                }
            } finally {
                runCatching { activeStream?.close() }
                if (!nativeStopRequested) {
                    mainHandler.post { onCompletion?.invoke() }
                }
            }
        }, "FlowNativeAudioDecoder").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
        nativeThread = worker
        worker.start()
        return true
    }

    private fun applyNativePlaybackParams(track: AudioTrack?, speed: Float, pitch: Float) {
        if (track == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val s = speed.coerceIn(0.25f, 3.0f)
        val p = pitch.coerceIn(0.25f, 3.0f)
        if (s == 1.0f && p == 1.0f) return
        runCatching {
            val params = PlaybackParams().setSpeed(s).setPitch(p)
            track.playbackParams = params
        }
    }

    private fun stopNativeTrackEngine() {
        nativeStopRequested = true
        nativePaused = false
        nativeQueuedNextSong = null
        val t = nativeThread
        nativeThread = null
        val track = nativeAudioTrack
        nativeAudioTrack = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        if (t != null && t != Thread.currentThread()) {
            runCatching { t.join(120L) }
        }
        usingNativeTrackEngine = false
    }

    fun setTrackReplayGainScalar(scalar: Float, immediate: Boolean = false) {
        val safeScalar = scalar.coerceIn(0.03f, 3.98f)
        nativeTargetGainScalar = safeScalar
        stereoProcessor.setReplayGainScalar(safeScalar, immediate)
    }

    fun playSong(
        song: Song,
        loopSingle: Boolean,
        speed: Float,
        pitch: Float,
        initialReplayGainScalar: Float = 1.0f
    ) {
        currentActiveSong = song
        queuedGaplessSong = null
        queuedGaplessGainScalar = 1.0f
        setTrackReplayGainScalar(initialReplayGainScalar, immediate = true)

        val ext = song.path.substringAfterLast('.', song.format).lowercase().trim()
        val needsLiveLufs = !song.toReplayGainMetadata().hasEmbeddedTags && song.computedLoudnessDb == null

        if (NativePcmContainerDecoder.isNativelyDecodableExtension(ext)) {
            if (tryStartNativeContainerEngine(song, loopSingle, speed, pitch)) {
                return
            }
        }

        stopNativeTrackEngine()
        stereoProcessor.resetLiveAnalyzerForTrack(song.path, needsLiveLufs)
        val player = ensurePlayer()
        player.repeatMode = if (loopSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3.0f), pitch.coerceIn(0.25f, 3.0f))
        player.setMediaItem(buildMediaItem(song))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Pre-buffers [nextSong] as the trailing [MediaItem] in ExoPlayer's playlist queue (or native
     * stream handoff slot) so Media3 decodes its header and initial PCM frames ahead of time for
     * seamless zero-gap playback transition, carrying [nextReplayGainScalar] for the incoming track.
     */
    fun queueNextGapless(nextSong: Song?, nextReplayGainScalar: Float = 1.0f) {
        val clampedNextGain = nextReplayGainScalar.coerceIn(0.03f, 3.98f)
        if (usingNativeTrackEngine) {
            nativeQueuedNextSong = if (nativeLoopSingle) null else nextSong
            nativeQueuedNextGainScalar = clampedNextGain
            queuedGaplessSong = nativeQueuedNextSong
            queuedGaplessGainScalar = clampedNextGain
            return
        }

        val player = exoPlayer ?: return
        val curIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val trailingIndex = curIndex + 1

        if (nextSong == null || player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (player.mediaItemCount > trailingIndex) {
                player.removeMediaItems(trailingIndex, player.mediaItemCount)
            }
            queuedGaplessSong = null
            queuedGaplessGainScalar = 1.0f
            return
        }

        val nextExt = nextSong.path.substringAfterLast('.', nextSong.format).lowercase().trim()
        if (NativePcmContainerDecoder.isNativelyDecodableExtension(nextExt)) {
            // Will transition cleanly on STATE_ENDED into NativePcmContainerDecoder
            if (player.mediaItemCount > trailingIndex) {
                player.removeMediaItems(trailingIndex, player.mediaItemCount)
            }
            queuedGaplessSong = null
            queuedGaplessGainScalar = 1.0f
            return
        }

        if (queuedGaplessSong?.id == nextSong.id && player.mediaItemCount > trailingIndex) {
            // Keep ExoPlayer's already pre-buffered MediaSource intact; update only its gain scalar
            queuedGaplessSong = nextSong
            queuedGaplessGainScalar = clampedNextGain
            return
        }

        if (player.mediaItemCount > trailingIndex) {
            player.removeMediaItems(trailingIndex, player.mediaItemCount)
        }
        player.addMediaItem(buildMediaItem(nextSong))
        queuedGaplessSong = nextSong
        queuedGaplessGainScalar = clampedNextGain
    }

    fun setStereoVolume(left: Float, right: Float) {
        leftVol = left.coerceIn(0f, 1f)
        rightVol = right.coerceIn(0f, 1f)
        if (usingNativeTrackEngine) {
            runCatching { nativeAudioTrack?.setStereoVolume(leftVol, rightVol) }
            return
        }
        val maxVol = maxOf(leftVol, rightVol)
        if (maxVol <= 0.0001f) {
            exoPlayer?.volume = 0f
            stereoProcessor.leftBalanceScalar = 1f
            stereoProcessor.rightBalanceScalar = 1f
        } else {
            exoPlayer?.volume = maxVol
            stereoProcessor.leftBalanceScalar = (leftVol / maxVol).coerceIn(0f, 1f)
            stereoProcessor.rightBalanceScalar = (rightVol / maxVol).coerceIn(0f, 1f)
        }
    }

    fun setLooping(loop: Boolean) {
        nativeLoopSingle = loop
        exoPlayer?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (loop) {
            queueNextGapless(null)
        }
    }

    fun setPlaybackSpeedAndPitch(speed: Float, pitch: Float) {
        if (usingNativeTrackEngine) {
            applyNativePlaybackParams(nativeAudioTrack, speed, pitch)
            return
        }
        exoPlayer?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3.0f), pitch.coerceIn(0.25f, 3.0f))
    }

    fun pause() {
        if (usingNativeTrackEngine) {
            nativePaused = true
            runCatching { nativeAudioTrack?.pause() }
            return
        }
        exoPlayer?.playWhenReady = false
    }

    fun resume() {
        if (usingNativeTrackEngine) {
            nativePaused = false
            runCatching { nativeAudioTrack?.play() }
            return
        }
        exoPlayer?.playWhenReady = true
    }

    fun seekTo(positionMs: Int) {
        if (usingNativeTrackEngine) {
            val clamped = positionMs.coerceAtLeast(0)
            nativePositionMs = clamped
            nativeSeekPendingMs = clamped.toLong()
            return
        }
        val player = exoPlayer ?: return
        player.seekTo(player.currentMediaItemIndex.coerceAtLeast(0), positionMs.toLong().coerceAtLeast(0L))
    }

    fun syncEqualizerDsp(
        enabled: Boolean,
        bandGainsDb: FloatArray,
        preampDb: Float,
        autoHeadroom: Boolean,
        bassBoostAmount: Float
    ) {
        val dsp = stereoProcessor.softwareEqDsp
        dsp.isEnabled = enabled
        dsp.setAllGains(bandGainsDb)
        dsp.preampDb = preampDb
        dsp.autoHeadroomEnabled = autoHeadroom
        dsp.bassBoostAmount = bassBoostAmount
        dsp.recalculateCoefficients()
    }

    fun release() {
        queuedGaplessSong = null
        queuedGaplessGainScalar = 1.0f
        currentActiveSong = null
        stopNativeTrackEngine()
        exoPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        exoPlayer = null
    }
}
