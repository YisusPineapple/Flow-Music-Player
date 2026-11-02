package com.demonlab.lune.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.demonlab.lune.core.dsp.ReplayGainEngine
import com.demonlab.lune.core.model.ReplayGainMetadata
import com.demonlab.lune.tools.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CachedLoudnessEntry(
    val path: String,
    val lastModified: Long,
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
    val computedLoudnessDb: Float? = null
)

/**
 * Lightweight, HDD-friendly ReplayGain & EBU R128 tag reader + sparse 3-window RMS/LUFS estimator.
 * Decodes < 450 ms of total audio across 3 seek points when tags are missing, caching results permanently
 * so modest/legacy devices never repeat disk or CPU work.
 */
object ReplayGainScanner {

    private val memoryCache = ConcurrentHashMap<String, CachedLoudnessEntry>()
    private val diskMutex = Mutex()
    private val gson = Gson()
    @Volatile private var cacheLoaded = false

    private val RG_TRACK_GAIN_KEYS = listOf(
        "REPLAYGAIN_TRACK_GAIN", "replaygain_track_gain", "TXXX:REPLAYGAIN_TRACK_GAIN", "----:com.apple.iTunes:replaygain_track_gain"
    )
    private val RG_TRACK_PEAK_KEYS = listOf(
        "REPLAYGAIN_TRACK_PEAK", "replaygain_track_peak", "TXXX:REPLAYGAIN_TRACK_PEAK", "----:com.apple.iTunes:replaygain_track_peak"
    )
    private val RG_ALBUM_GAIN_KEYS = listOf(
        "REPLAYGAIN_ALBUM_GAIN", "replaygain_album_gain", "TXXX:REPLAYGAIN_ALBUM_GAIN", "----:com.apple.iTunes:replaygain_album_gain"
    )
    private val RG_ALBUM_PEAK_KEYS = listOf(
        "REPLAYGAIN_ALBUM_PEAK", "replaygain_album_peak", "TXXX:REPLAYGAIN_ALBUM_PEAK", "----:com.apple.iTunes:replaygain_album_peak"
    )
    private val R128_TRACK_KEYS = listOf("R128_TRACK_GAIN", "r128_track_gain")
    private val R128_ALBUM_KEYS = listOf("R128_ALBUM_GAIN", "r128_album_gain")
    private val ITUNNORM_KEYS = listOf("ITUNNORM", "iTunNORM", "COMMENT ITUNNORM", "----:com.apple.iTunes:iTunNORM")

    private fun ensureCacheLoaded(context: Context) {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            try {
                val file = File(context.filesDir, "replaygain_cache.json")
                if (file.exists()) {
                    val type = object : TypeToken<List<CachedLoudnessEntry>>() {}.type
                    val list: List<CachedLoudnessEntry>? = gson.fromJson(file.readText(), type)
                    list?.forEach { entry -> memoryCache[entry.path] = entry }
                }
            } catch (_: Exception) {
            }
            cacheLoaded = true
        }
    }

    private suspend fun persistCache(context: Context) {
        diskMutex.withLock {
            try {
                val file = File(context.filesDir, "replaygain_cache.json")
                file.writeText(gson.toJson(memoryCache.values.toList()))
            } catch (_: Exception) {
            }
        }
    }

    fun extractReplayGainFromTag(tag: Tag?): ReplayGainMetadata {
        if (tag == null) return ReplayGainMetadata()
        fun firstCustom(keys: List<String>): String? {
            for (k in keys) {
                val v = runCatching { tag.getFirst(k) }.getOrNull()?.trim()
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        return ReplayGainEngine.fromRawTags(
            rgTrackGain = firstCustom(RG_TRACK_GAIN_KEYS),
            rgTrackPeak = firstCustom(RG_TRACK_PEAK_KEYS),
            rgAlbumGain = firstCustom(RG_ALBUM_GAIN_KEYS),
            rgAlbumPeak = firstCustom(RG_ALBUM_PEAK_KEYS),
            r128TrackGain = firstCustom(R128_TRACK_KEYS),
            r128AlbumGain = firstCustom(R128_ALBUM_KEYS),
            iTunNorm = firstCustom(ITUNNORM_KEYS)
        )
    }

    fun getCachedForSong(context: Context, song: Song): ReplayGainMetadata {
        ensureCacheLoaded(context)
        val base = song.toReplayGainMetadata()
        if (base.hasEmbeddedTags || base.computedLoudnessDb != null) return base
        val cached = memoryCache[song.path] ?: return base
        return ReplayGainMetadata(
            trackGainDb = cached.trackGainDb,
            trackPeak = cached.trackPeak,
            albumGainDb = cached.albumGainDb,
            albumPeak = cached.albumPeak,
            computedLoudnessDb = cached.computedLoudnessDb
        )
    }

    suspend fun resolveOrAnalyzeSong(
        context: Context,
        song: Song,
        allowFallbackDecode: Boolean
    ): ReplayGainMetadata = withContext(Dispatchers.IO) {
        ensureCacheLoaded(context)

        val existing = song.toReplayGainMetadata()
        if (existing.hasEmbeddedTags || existing.computedLoudnessDb != null) {
            return@withContext existing
        }

        val file = if (song.path.isNotBlank()) File(song.path) else null
        val lastMod = file?.takeIf { it.exists() }?.lastModified() ?: 0L
        val cached = memoryCache[song.path]
        if (cached != null && cached.lastModified == lastMod &&
            (cached.trackGainDb != null || cached.albumGainDb != null || cached.computedLoudnessDb != null || !allowFallbackDecode)
        ) {
            return@withContext ReplayGainMetadata(
                trackGainDb = cached.trackGainDb,
                trackPeak = cached.trackPeak,
                albumGainDb = cached.albumGainDb,
                albumPeak = cached.albumPeak,
                computedLoudnessDb = cached.computedLoudnessDb
            )
        }

        // Step 1: Fast tag check via Jaudiotagger (no audio decoding)
        var tagMeta = ReplayGainMetadata()
        if (file != null && file.exists() && file.canRead()) {
            tagMeta = runCatching {
                val af = AudioFileIO.read(file)
                extractReplayGainFromTag(af.tag)
            }.getOrDefault(ReplayGainMetadata())
        }

        if (tagMeta.hasEmbeddedTags || !allowFallbackDecode) {
            val entry = CachedLoudnessEntry(
                path = song.path,
                lastModified = lastMod,
                trackGainDb = tagMeta.trackGainDb,
                trackPeak = tagMeta.trackPeak,
                albumGainDb = tagMeta.albumGainDb,
                albumPeak = tagMeta.albumPeak
            )
            if (song.path.isNotBlank()) {
                memoryCache[song.path] = entry
                persistCache(context)
            }
            return@withContext tagMeta
        }

        // Step 2: Ultra-lightweight sparse 3-window PCM loudness analysis (< 450 ms decoded total)
        val analyzed = analyzeSparseLoudness(context, song)
        val finalMeta = ReplayGainMetadata(
            trackPeak = analyzed?.second,
            computedLoudnessDb = analyzed?.first
        )

        if (song.path.isNotBlank() && finalMeta.computedLoudnessDb != null) {
            memoryCache[song.path] = CachedLoudnessEntry(
                path = song.path,
                lastModified = lastMod,
                trackPeak = finalMeta.trackPeak,
                computedLoudnessDb = finalMeta.computedLoudnessDb
            )
            persistCache(context)
        }
        finalMeta
    }

    private fun analyzeSparseLoudness(context: Context, song: Song): Pair<Float, Float>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            val setOk = runCatching {
                extractor.setDataSource(context, song.uri, null)
                true
            }.getOrElse {
                if (song.path.isNotBlank() && File(song.path).exists()) {
                    extractor.setDataSource(song.path)
                    true
                } else false
            }
            if (!setOk) return null

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 2
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100
            val durationUs = when {
                song.duration > 0 -> song.duration * 1000L
                audioFormat.containsKey(MediaFormat.KEY_DURATION) -> audioFormat.getLong(MediaFormat.KEY_DURATION)
                else -> 120_000_000L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmCollector = ByteArrayOutputStream(65536)
            val windowBytesTarget = (sampleRate * channels * 2 * 14) / 100 // ~140 ms per window
            val seekPointsUs = if (durationUs > 15_000_000L) {
                longArrayOf((durationUs * 0.25).toLong(), (durationUs * 0.52).toLong(), (durationUs * 0.76).toLong())
            } else {
                longArrayOf((durationUs * 0.4).toLong())
            }

            val bufferInfo = MediaCodec.BufferInfo()
            for (seekUs in seekPointsUs) {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                runCatching { codec.flush() }
                var collectedForWindow = 0
                var iterations = 0
                var sawInputEos = false

                while (collectedForWindow < windowBytesTarget && iterations < 48) {
                    iterations++
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(4000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4000L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmCollector.write(chunk)
                            collectedForWindow += bufferInfo.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val pcmBytes = pcmCollector.toByteArray()
            ReplayGainEngine.estimateGainFromPcm16le(pcmBytes, pcmBytes.size, channels)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
