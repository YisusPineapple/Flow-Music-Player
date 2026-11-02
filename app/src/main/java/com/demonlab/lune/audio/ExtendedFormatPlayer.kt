@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.demonlab.lune.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.demonlab.lune.core.format.AudioFormatRegistry
import com.demonlab.lune.tools.Song
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ITU-R BS.775 Multichannel-to-Stereo Downmix + Stereo Balance AudioProcessor for Media3 ExoPlayer.
 * Ensures 5.1 / 7.1 Opus, EAC3, AC3, AC4, FLAC, WAV, MKV, MP4, and WebM streams play cleanly
 * on any stereo Android DAC/speaker without failing or dropping center/surround channels.
 */
class MultichannelStereoBalanceProcessor : BaseAudioProcessor() {

    @Volatile var leftBalanceScalar: Float = 1.0f
    @Volatile var rightBalanceScalar: Float = 1.0f

    private var inTemp = ByteArray(0)
    private var outTemp = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            2,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val inChannels = inputAudioFormat.channelCount
        if (inTemp.size < remaining) {
            inTemp = ByteArray(remaining)
        }
        inputBuffer.get(inTemp, 0, remaining)

        val inFrames = remaining / (inChannels * 2)
        val outBytesNeeded = inFrames * 4
        if (outTemp.size < outBytesNeeded) {
            outTemp = ByteArray(outBytesNeeded)
        }

        val writtenBytes = AudioFormatRegistry.downmixPcm16leToStereo(
            inBuffer = inTemp,
            inValidBytes = remaining,
            inChannels = inChannels,
            outStereoBuffer = outTemp
        )

        val lGain = leftBalanceScalar
        val rGain = rightBalanceScalar
        if (lGain < 0.999f || rGain < 0.999f) {
            var i = 0
            while (i + 3 < writtenBytes) {
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
        }

        val outBuf = replaceOutputBuffer(writtenBytes).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.put(outTemp, 0, writtenBytes)
        outBuf.flip()
    }
}

/**
 * Extended format & container playback wrapper powered by Media3 ExoPlayer.
 * Handles ALAC, AIFF, MKV/MKA, MP4, WebM, multichannel Opus (5.1/7.1), EAC3, AC3, AC4,
 * and gapless media item chaining when legacy MediaPlayer cannot open a container or codec.
 */
class ExtendedFormatPlayer(private val context: Context) {

    private val stereoProcessor = MultichannelStereoBalanceProcessor()
    private var exoPlayer: ExoPlayer? = null
    private var queuedGaplessSongId: Long? = null

    var onPrepared: ((audioSessionId: Int) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onGaplessTransition: ((nextSongId: Long) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    val isActive: Boolean
        get() = exoPlayer != null

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentPositionMs: Int
        get() = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L).toInt()

    val durationMs: Int
        get() = (exoPlayer?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L).toInt()

    val audioSessionId: Int
        get() = exoPlayer?.audioSessionId ?: 0

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(stereoProcessor))
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        var notifiedPreparedForCurrentItem = false

        val created = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(attrs, false)
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
                    val transitionedId = mediaItem.mediaId.toLongOrNull()
                    if (transitionedId != null) {
                        queuedGaplessSongId = null
                        onGaplessTransition?.invoke(transitionedId)
                    }
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) {
                    notifiedPreparedForCurrentItem = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
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

    fun playSong(song: Song, loopSingle: Boolean, speed: Float, pitch: Float) {
        val player = ensurePlayer()
        queuedGaplessSongId = null
        player.repeatMode = if (loopSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3.0f), pitch.coerceIn(0.25f, 3.0f))
        player.setMediaItem(buildMediaItem(song))
        player.prepare()
        player.playWhenReady = true
    }

    fun queueNextGapless(nextSong: Song?) {
        val player = exoPlayer ?: return
        if (nextSong == null) {
            if (player.mediaItemCount > 1) {
                player.removeMediaItems(1, player.mediaItemCount)
            }
            queuedGaplessSongId = null
            return
        }
        if (queuedGaplessSongId == nextSong.id && player.mediaItemCount >= 2) return
        if (player.mediaItemCount > 1) {
            player.removeMediaItems(1, player.mediaItemCount)
        }
        player.addMediaItem(buildMediaItem(nextSong))
        queuedGaplessSongId = nextSong.id
    }

    fun setStereoVolume(left: Float, right: Float) {
        val maxVol = maxOf(left, right).coerceIn(0f, 1f)
        if (maxVol <= 0.0001f) {
            exoPlayer?.volume = 0f
            stereoProcessor.leftBalanceScalar = 1f
            stereoProcessor.rightBalanceScalar = 1f
        } else {
            exoPlayer?.volume = maxVol
            stereoProcessor.leftBalanceScalar = (left / maxVol).coerceIn(0f, 1f)
            stereoProcessor.rightBalanceScalar = (right / maxVol).coerceIn(0f, 1f)
        }
    }

    fun setLooping(loop: Boolean) {
        exoPlayer?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setPlaybackSpeedAndPitch(speed: Float, pitch: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3.0f), pitch.coerceIn(0.25f, 3.0f))
    }

    fun pause() {
        exoPlayer?.playWhenReady = false
    }

    fun resume() {
        exoPlayer?.playWhenReady = true
    }

    fun seekTo(positionMs: Int) {
        exoPlayer?.seekTo(positionMs.toLong().coerceAtLeast(0L))
    }

    fun release() {
        queuedGaplessSongId = null
        exoPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        exoPlayer = null
    }
}
