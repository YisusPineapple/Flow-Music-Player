package com.demonlab.lune.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import com.demonlab.lune.core.format.AudioFormatRegistry
import java.io.File

class AudioThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val targetWidth = options.size.width.pxOrElse { 384 }.coerceIn(64, 512)
        val targetHeight = options.size.height.pxOrElse { 384 }.coerceIn(64, 512)

        // 1. Try modern ContentResolver.loadThumbnail (fast, system-cached, pre-downsampled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val thumbnail = runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(targetWidth, targetHeight), null)
            }.getOrNull()

            if (thumbnail != null) {
                return DrawableResult(
                    drawable = thumbnail.toDrawable(context.resources),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            }
        }

        // 2. Fallback to embedded picture via MediaMetadataRetriever with strict bounds subsampling
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null && File(path).exists()) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, uri)
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture?.let { picture ->
                val (bitmap, sampled) = decodeSampledBitmap(picture, targetWidth, targetHeight)
                if (bitmap != null) {
                    return DrawableResult(
                        drawable = bitmap.toDrawable(context.resources),
                        isSampled = sampled,
                        dataSource = DataSource.DISK
                    )
                }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            runCatching { retriever.release() }
        }

        // 3. Fallback to Jaudiotagger (works for WAV with ID3 chunks, AIFF, DSF, DFF, FLAC, APE, WV)
        try {
            var filePath: String? = if (uri.scheme == "file") uri.path else null
            if (filePath == null && uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx != -1) filePath = cursor.getString(idx)
                    }
                }
            }
            if (!filePath.isNullOrBlank()) {
                val file = File(filePath)
                if (file.exists() && file.canRead()) {
                    val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                    val artwork = audioFile.tag?.firstArtwork?.binaryData
                    if (artwork != null && artwork.isNotEmpty()) {
                        val (bitmap, sampled) = decodeSampledBitmap(artwork, targetWidth, targetHeight)
                        if (bitmap != null) {
                            return DrawableResult(
                                drawable = bitmap.toDrawable(context.resources),
                                isSampled = sampled,
                                dataSource = DataSource.DISK
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }

        return null
    }

    private fun decodeSampledBitmap(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int
    ): Pair<Bitmap?, Boolean> {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null to false

            var inSampleSize = 1
            while ((bounds.outWidth / (inSampleSize * 2)) >= reqWidth &&
                (bounds.outHeight / (inSampleSize * 2)) >= reqHeight
            ) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = if (options.allowRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            decoded to (inSampleSize > 1)
        } catch (_: OutOfMemoryError) {
            System.gc()
            null to false
        } catch (_: Exception) {
            null to false
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme == "content" || scheme == "file") {
                val str = data.toString().lowercase()
                val ext = str.substringAfterLast('.', "").substringBefore('?')
                val isAudio = str.contains("audio") ||
                    AudioFormatRegistry.isSupportedAudioExtension(ext) ||
                    runCatching {
                        val mime = context.contentResolver.getType(data)
                        mime?.startsWith("audio/") == true || mime == "video/x-matroska" || mime == "application/ogg"
                    }.getOrDefault(false)
                if (isAudio) {
                    return AudioThumbnailFetcher(data, options, context)
                }
            }
            return null
        }
    }
}
