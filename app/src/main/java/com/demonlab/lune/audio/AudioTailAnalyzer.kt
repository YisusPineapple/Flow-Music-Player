package com.demonlab.lune.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.demonlab.lune.core.dsp.CrossfadeCurveEngine
import com.demonlab.lune.core.model.TailAnalysisResult
import com.demonlab.lune.tools.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Lightweight, zero-MediaCodec audio tail & silence analyzer.
 * Inspects the final 10 seconds of a track using MediaExtractor sample frames (or PCM RMS for WAV)
 * in < 3 ms on IO thread with a single 32 KB Direct/Heap ByteBuffer, caching up to 256 results.
 */
object AudioTailAnalyzer {

    private const val TAIL_WINDOW_MS = 10_000L
    private const val BUCKET_COUNT = 20
    private const val MAX_CACHE_ENTRIES = 256

    private val cache = ConcurrentHashMap<Long, TailAnalysisResult>()

    fun getCached(songId: Long): TailAnalysisResult? = cache[songId]

    suspend fun analyzeSongTail(context: Context, song: Song): TailAnalysisResult = withContext(Dispatchers.IO) {
        cache[song.id]?.let { return@withContext it }

        if (song.duration < 25_000L) {
            return@withContext TailAnalysisResult(song.id, 0L, 0L, 1f).also { putCache(song.id, it) }
        }

        val extractor = MediaExtractor()
        try {
            val file = File(song.path)
            if (song.path.isNotBlank() && file.exists()) {
                extractor.setDataSource(song.path)
            } else {
                extractor.setDataSource(context, song.uri, null)
            }

            var audioTrackIndex = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0) {
                return@withContext TailAnalysisResult(song.id, 0L, 0L, 1f).also { putCache(song.id, it) }
            }

            extractor.selectTrack(audioTrackIndex)
            val windowStartMs = (song.duration - TAIL_WINDOW_MS).coerceAtLeast(0L)
            extractor.seekTo(windowStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buckets = FloatArray(BUCKET_COUNT)
            val bucketCounts = IntArray(BUCKET_COUNT)
            val buffer = ByteBuffer.allocate(32 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val isRawPcm = mime.contains("raw", ignoreCase = true) || song.path.endsWith(".wav", ignoreCase = true)

            var framesRead = 0
            while (framesRead < 1200) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeMs = extractor.sampleTime / 1000L
                if (sampleTimeMs >= windowStartMs) {
                    val relMs = (sampleTimeMs - windowStartMs).coerceIn(0L, TAIL_WINDOW_MS - 1L)
                    val bucketIdx = ((relMs * BUCKET_COUNT) / TAIL_WINDOW_MS).toInt().coerceIn(0, BUCKET_COUNT - 1)

                    val frameEnergy = if (isRawPcm && sampleSize >= 4) {
                        computePcm16Rms(buffer, sampleSize)
                    } else {
                        // Compressed audio (FLAC, OGG, Opus, AAC, VBR MP3): frame payload density + byte variance
                        computeCompressedFrameEnergy(buffer, sampleSize)
                    }

                    buckets[bucketIdx] += frameEnergy
                    bucketCounts[bucketIdx]++
                }

                framesRead++
                if (!extractor.advance()) break
            }

            for (i in 0 until BUCKET_COUNT) {
                if (bucketCounts[i] > 0) {
                    buckets[i] /= bucketCounts[i].toFloat()
                }
            }

            val earlyFadeOffsetMs = CrossfadeCurveEngine.detectNaturalFadeOutOffsetMs(buckets, TAIL_WINDOW_MS)
            val peak = buckets.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
            val tailAvg = buckets.takeLast(4).average().toFloat() / peak
            val result = TailAnalysisResult(
                songId = song.id,
                earlyFadeTriggerOffsetMs = earlyFadeOffsetMs,
                trailingSilenceMs = earlyFadeOffsetMs,
                tailRmsRatio = tailAvg.coerceIn(0f, 1f)
            )
            putCache(song.id, result)
            result
        } catch (_: Throwable) {
            TailAnalysisResult(song.id, 0L, 0L, 1f).also { putCache(song.id, it) }
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun computePcm16Rms(buffer: ByteBuffer, byteCount: Int): Float {
        val samples = (byteCount / 2).coerceAtMost(2048)
        if (samples <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        val step = (samples / 256).coerceAtLeast(1)
        var counted = 0
        while (i < samples) {
            val s = buffer.getShort(i * 2).toInt() / 32768.0
            sumSq += s * s
            counted++
            i += step
        }
        return if (counted > 0) sqrt(sumSq / counted).toFloat() else 0f
    }

    private fun computeCompressedFrameEnergy(buffer: ByteBuffer, byteCount: Int): Float {
        if (byteCount <= 8) return 0f
        // Combine frame size proxy with byte-value variance (CBR digital silence frames are repetitive 0x00/0xFF/0x55)
        val step = (byteCount / 64).coerceAtLeast(1)
        var prev = buffer.get(0).toInt() and 0xFF
        var diffSum = 0L
        var samples = 0
        var idx = step
        while (idx < byteCount) {
            val curr = buffer.get(idx).toInt() and 0xFF
            diffSum += kotlin.math.abs(curr - prev)
            prev = curr
            samples++
            idx += step
        }
        val avgDiff = if (samples > 0) (diffSum.toFloat() / samples) / 128f else 0f
        val sizeFactor = (byteCount.toFloat() / 600f).coerceIn(0.05f, 1.5f)
        return (avgDiff * sizeFactor).coerceIn(0f, 1f)
    }

    private fun putCache(songId: Long, result: TailAnalysisResult) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[songId] = result
    }
}
