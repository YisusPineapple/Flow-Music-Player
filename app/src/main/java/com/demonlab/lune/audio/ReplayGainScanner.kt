package com.demonlab.lune.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.demonlab.lune.core.dsp.ReplayGainEngine
import com.demonlab.lune.core.dsp.ReplayGainTagService
import com.demonlab.lune.core.format.NativePcmContainerDecoder
import com.demonlab.lune.core.format.SeekableByteReader
import com.demonlab.lune.core.model.ReplayGainMetadata
import com.demonlab.lune.tools.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jaudiotagger.tag.Tag
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
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

data class LibraryAnalysisStatus(
    val isRunning: Boolean = false,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentTrackTitle: String = ""
)

/**
 * Lightweight, HDD- & battery-friendly ReplayGain / EBU R128 tag reader + ITU-R BS.1770-4 K-weighted
 * LUFS / RMS analyzer. Decodes < 450 ms of sparse audio across 3 seek points (or records real-time
 * streaming LUFS during playback) and caches results permanently on disk.
 */
object ReplayGainScanner {

    private val memoryCache = ConcurrentHashMap<String, CachedLoudnessEntry>()
    private val diskMutex = Mutex()
    private val batchScanMutex = Mutex()
    private val gson = Gson()
    @Volatile private var cacheLoaded = false

    private val _analysisStatus = MutableStateFlow(LibraryAnalysisStatus())
    val analysisStatus: StateFlow<LibraryAnalysisStatus> = _analysisStatus.asStateFlow()

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

    fun trimInMemoryIfNeeded(maxEntries: Int = 4000) {
        if (memoryCache.size > maxEntries) {
            val iterator = memoryCache.keys.iterator()
            var removed = 0
            val targetRemove = memoryCache.size - maxEntries
            while (iterator.hasNext() && removed < targetRemove) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
    }

    suspend fun recordRealtimeComputedLoudness(
        context: Context,
        songPath: String,
        gainDb: Float,
        peak: Float
    ) {
        if (songPath.isBlank() || !gainDb.isFinite()) return
        ensureCacheLoaded(context)
        val lastMod = runCatching { File(songPath).takeIf { it.exists() }?.lastModified() ?: 0L }.getOrDefault(0L)
        val prev = memoryCache[songPath]
        if (prev != null && prev.hasAnyTag()) return
        memoryCache[songPath] = CachedLoudnessEntry(
            path = songPath,
            lastModified = lastMod,
            trackGainDb = prev?.trackGainDb,
            trackPeak = peak.coerceIn(0.01f, 1.0f),
            albumGainDb = prev?.albumGainDb,
            albumPeak = prev?.albumPeak,
            computedLoudnessDb = gainDb.coerceIn(-24f, 18f)
        )
        persistCache(context)
    }

    private fun CachedLoudnessEntry.hasAnyTag(): Boolean =
        trackGainDb != null || albumGainDb != null

    fun extractReplayGainFromTag(tag: Tag?): ReplayGainMetadata {
        return ReplayGainTagService.extractFromTag(tag)
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

    /**
     * Low-priority background batch analyzer for untagged tracks in the library.
     * Yields between tracks and batches disk writes to minimize battery and I/O overhead.
     */
    suspend fun analyzeLibraryUntaggedInBackground(
        context: Context,
        songs: List<Song>,
        maxTracksPerPass: Int = 60
    ) = withContext(Dispatchers.IO) {
        if (songs.isEmpty() || !batchScanMutex.tryLock()) return@withContext
        try {
            ensureCacheLoaded(context)
            val candidates = songs.filter { s ->
                s.path.isNotBlank() &&
                    s.replayGainTrackDb == null &&
                    s.replayGainAlbumDb == null &&
                    s.computedLoudnessDb == null &&
                    memoryCache[s.path]?.let { it.trackGainDb != null || it.albumGainDb != null || it.computedLoudnessDb != null } != true
            }.take(maxTracksPerPass)

            if (candidates.isEmpty()) return@withContext

            _analysisStatus.value = LibraryAnalysisStatus(
                isRunning = true,
                processedCount = 0,
                totalCount = candidates.size
            )
            var dirtyCount = 0
            candidates.forEachIndexed { idx, track ->
                yield()
                _analysisStatus.value = LibraryAnalysisStatus(
                    isRunning = true,
                    processedCount = idx,
                    totalCount = candidates.size,
                    currentTrackTitle = track.title
                )
                resolveOrAnalyzeSong(context, track, allowFallbackDecode = true, deferPersist = true)
                dirtyCount++
                if (dirtyCount >= 8) {
                    persistCache(context)
                    dirtyCount = 0
                }
            }
            if (dirtyCount > 0) {
                persistCache(context)
            }
        } finally {
            _analysisStatus.value = LibraryAnalysisStatus(isRunning = false)
            batchScanMutex.unlock()
        }
    }

    suspend fun resolveOrAnalyzeSong(
        context: Context,
        song: Song,
        allowFallbackDecode: Boolean,
        deferPersist: Boolean = false
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

        // Step 1: Fast shared commonMain tag check via ReplayGainTagService (jaudiotagger, no audio decoding)
        val tagMeta = ReplayGainTagService.readFromFile(file)

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
                if (!deferPersist) persistCache(context)
            }
            return@withContext tagMeta
        }

        // Step 2: Sparse 3-window K-weighted LUFS analysis (< 450 ms decoded total)
        val analyzed = analyzeSparseLoudness(context, song)
            ?: file?.takeIf { it.exists() && it.canRead() }?.let { analyzeViaNativeContainerDecoder(it) }

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
            if (!deferPersist) persistCache(context)
        }
        finalMeta
    }

    private fun analyzeViaNativeContainerDecoder(file: File): Pair<Float, Float>? {
        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(file, "r")
            val fileRef = raf
            val reader = object : SeekableByteReader {
                override val length: Long get() = fileRef.length()
                override var position: Long
                    get() = fileRef.filePointer
                    set(value) { fileRef.seek(value.coerceAtLeast(0L)) }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
                    fileRef.read(buffer, offset, count)
                override fun close() = runCatching { fileRef.close() }.let {}
            }
            val decoded = NativePcmContainerDecoder.open(reader) ?: return null
            try {
                val outRate = decoded.outputSampleRate
                val windowBytes = (outRate * 4 * 15) / 100 // ~150 ms stereo s16le per seek point
                val collector = ByteArrayOutputStream(windowBytes * 3)
                val scratch = ByteArray(8192)
                val dur = decoded.durationMs
                val seekPoints = if (dur > 12_000L) {
                    longArrayOf((dur * 0.25).toLong(), (dur * 0.52).toLong(), (dur * 0.76).toLong())
                } else {
                    longArrayOf((dur * 0.35).toLong())
                }
                for (pt in seekPoints) {
                    decoded.seekToMs(pt)
                    var gotWindow = 0
                    while (gotWindow < windowBytes) {
                        val n = decoded.readStereoS16le(scratch, 0, minOf(scratch.size, windowBytes - gotWindow))
                        if (n <= 0) break
                        collector.write(scratch, 0, n)
                        gotWindow += n
                    }
                }
                val pcm = collector.toByteArray()
                ReplayGainEngine.estimateGainFromPcm16le(pcm, pcm.size, channels = 2, sampleRateHz = outRate)
            } finally {
                decoded.close()
            }
        } catch (_: Exception) {
            runCatching { raf?.close() }
            null
        }
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

            val chunkReusable = ByteArray(16384)
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
                            var rem = bufferInfo.size
                            while (rem > 0) {
                                val step = minOf(rem, chunkReusable.size)
                                outBuf.get(chunkReusable, 0, step)
                                pcmCollector.write(chunkReusable, 0, step)
                                rem -= step
                            }
                            collectedForWindow += bufferInfo.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val pcmBytes = pcmCollector.toByteArray()
            ReplayGainEngine.estimateGainFromPcm16le(
                pcmBytes = pcmBytes,
                validLength = pcmBytes.size,
                channels = channels,
                sampleRateHz = sampleRate
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
