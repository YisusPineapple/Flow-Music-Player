package com.demonlab.lune.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Manages custom playlist covers with strict 1:1 square center-crop validation and bounded 600x600
 * downsampling so covers never render stretched, clipped awkwardly, or bloat heap memory (Issue #178).
 */
object PlaylistCoverManager {

    private const val TARGET_SIZE_PX = 600
    private const val DIR_NAME = "playlist_covers"

    private val _coverVersion = MutableStateFlow(0L)
    val coverVersion: StateFlow<Long> = _coverVersion.asStateFlow()

    private fun getCoverDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCustomCoverFile(context: Context, playlistId: Long): File {
        return File(getCoverDir(context), "playlist_${playlistId}.jpg")
    }

    fun getCustomCoverUriString(context: Context, playlistId: Long): String? {
        val file = getCustomCoverFile(context, playlistId)
        return if (file.exists() && file.length() > 0L) {
            "${Uri.fromFile(file)}?v=${file.lastModified()}"
        } else {
            null
        }
    }

    fun hasCustomCover(context: Context, playlistId: Long): Boolean {
        val file = getCustomCoverFile(context, playlistId)
        return file.exists() && file.length() > 0L
    }

    suspend fun saveCustomPlaylistCover(
        context: Context,
        playlistId: Long,
        sourceUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Pass 1: Read image dimensions without allocating pixel memory
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val rawW = boundsOpts.outWidth
            val rawH = boundsOpts.outHeight
            if (rawW <= 0 || rawH <= 0) return@withContext false

            // Calculate power-of-two subsampling for minimal RAM footprint
            var sampleSize = 1
            val minDim = min(rawW, rawH)
            while (minDim / (sampleSize * 2) >= TARGET_SIZE_PX) {
                sampleSize *= 2
            }

            // Pass 2: Decode downsampled bitmap
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@withContext false

            // Validate & normalize to exact 1:1 center-cropped square
            val w = decoded.width
            val h = decoded.height
            val squareEdge = min(w, h)
            val cropX = ((w - squareEdge) / 2).coerceAtLeast(0)
            val cropY = ((h - squareEdge) / 2).coerceAtLeast(0)

            val cropped = if (w == h) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, cropX, cropY, squareEdge, squareEdge)
            }

            val finalBitmap = if (cropped.width != TARGET_SIZE_PX) {
                Bitmap.createScaledBitmap(cropped, TARGET_SIZE_PX, TARGET_SIZE_PX, true)
            } else {
                cropped
            }

            val outFile = getCustomCoverFile(context, playlistId)
            outFile.outputStream().use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }

            if (finalBitmap !== cropped) finalBitmap.recycle()
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()

            _coverVersion.value = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("PlaylistCoverManager", "Failed to save 1:1 playlist cover", e)
            false
        }
    }

    suspend fun removeCustomPlaylistCover(context: Context, playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = getCustomCoverFile(context, playlistId)
        val deleted = if (file.exists()) file.delete() else true
        _coverVersion.value = System.currentTimeMillis()
        deleted
    }
}
