package com.demonlab.lune.tools

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.demonlab.lune.ai.LuneAiEngine
import com.demonlab.lune.data.MusicDatabase
import com.demonlab.lune.data.SongOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class MetadataWriteResult {
    object WrittenToFile : MetadataWriteResult()
    data class NeedsWritePermission(
        val intentSender: IntentSender,
        val pendingEdit: PendingMetadataEdit
    ) : MetadataWriteResult()
    object SavedLocalFallback : MetadataWriteResult()
    object Failed : MetadataWriteResult()
}

data class PendingMetadataEdit(
    val song: Song,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val coverUri: String?
)

class MetadataManager(private val context: Context) {
    private val database = MusicDatabase.getDatabase(context)
    private val coversDir = File(context.filesDir, "covers")

    /**
     * Persists metadata both locally in Room (for instant zero-loss fallback) AND physically
     * into the audio file's ID3v2 / Vorbis / FLAC / MP4 tags via Jaudiotagger (Fixes Issue #59).
     * On Android 10/11+ scoped storage, if MediaStore requires user confirmation to modify the file,
     * returns [MetadataWriteResult.NeedsWritePermission] with the system [IntentSender].
     */
    suspend fun updateSongMetadataWithFileWrite(
        song: Song,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: String?
    ): MetadataWriteResult = withContext(Dispatchers.IO) {
        val savedCoverStr = saveOverrideToRoom(song.id, title, artist, album, genre, coverUri)
            ?: return@withContext MetadataWriteResult.Failed

        LuneAiEngine.getInstance(context).invalidateProfileCache(song.id)

        val effectiveCoverPath = savedCoverStr.takeIf { it.isNotBlank() }
        writePhysicalTags(
            song = song,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            coverUriString = effectiveCoverPath
        )
    }

    /**
     * Backward-compatible Room-first updater.
     */
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        saveOverrideToRoom(songId, title, artist, album, genre, coverUri) != null
    }

    private suspend fun saveOverrideToRoom(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: String?
    ): String? {
        return try {
            val existing = database.songOverrideDao().getOverrideForSong(songId)
            val finalCoverUri = if (coverUri != null) {
                if (!coverUri.startsWith("file://${context.filesDir}")) {
                    saveCustomCover(songId, coverUri.toUri())?.toString() ?: coverUri
                } else {
                    coverUri
                }
            } else {
                existing?.coverUri
            }

            val override = SongOverride(
                songId = songId,
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                coverUri = finalCoverUri,
                isFavorite = existing?.isFavorite ?: false
            )
            database.songOverrideDao().insertOverride(override)
            finalCoverUri ?: ""
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error saving metadata to Room", e)
            null
        }
    }

    suspend fun retryPhysicalTagWrite(pending: PendingMetadataEdit): MetadataWriteResult =
        withContext(Dispatchers.IO) {
            val existing = database.songOverrideDao().getOverrideForSong(pending.song.id)
            writePhysicalTags(
                song = pending.song,
                title = pending.title,
                artist = pending.artist,
                album = pending.album,
                genre = pending.genre,
                coverUriString = existing?.coverUri ?: pending.coverUri
            )
        }

    private fun writePhysicalTags(
        song: Song,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUriString: String?
    ): MetadataWriteResult {
        val ext = song.path.substringAfterLast('.', "mp3").lowercase()
        val directFile = File(song.path)

        // 1. Direct filesystem write when permitted (Android <= 9 or app-owned/writable file)
        if (directFile.exists() && directFile.canWrite()) {
            try {
                applyJaudiotaggerEdits(directFile, title, artist, album, genre, coverUriString)
                MediaScannerConnection.scanFile(context, arrayOf(directFile.absolutePath), null, null)
                return MetadataWriteResult.WrittenToFile
            } catch (sec: SecurityException) {
                buildWritePermissionResult(sec, song, title, artist, album, genre, coverUriString)?.let { return it }
            } catch (e: Exception) {
                Log.w("MetadataManager", "Direct Jaudiotagger write failed, trying ContentResolver temp swap: ${e.message}")
            }
        }

        // 2. Scoped Storage ContentResolver temp-file edit + atomic descriptor write-back
        val tempFile = File(context.cacheDir, "tag_edit_${song.id}.$ext")
        try {
            // Probe write access first so Android 10/11+ throws SecurityException BEFORE expensive copy if unauthorized
            context.contentResolver.openFileDescriptor(song.uri, "rw")?.close()

            context.contentResolver.openInputStream(song.uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return MetadataWriteResult.SavedLocalFallback

            applyJaudiotaggerEdits(tempFile, title, artist, album, genre, coverUriString)

            context.contentResolver.openFileDescriptor(song.uri, "rwt")?.use { pfd ->
                FileInputStream(tempFile).use { editedInput ->
                    FileOutputStream(pfd.fileDescriptor).use { targetOutput ->
                        editedInput.copyTo(targetOutput)
                        targetOutput.fd.sync()
                    }
                }
            } ?: return MetadataWriteResult.SavedLocalFallback

            if (song.path.isNotBlank()) {
                MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
            }
            return MetadataWriteResult.WrittenToFile
        } catch (sec: SecurityException) {
            buildWritePermissionResult(sec, song, title, artist, album, genre, coverUriString)?.let {
                return it
            }
            return MetadataWriteResult.SavedLocalFallback
        } catch (e: Exception) {
            Log.w("MetadataManager", "Physical tag write unsupported or failed (${e.message}); Room override active")
            return MetadataWriteResult.SavedLocalFallback
        } finally {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    private fun applyJaudiotaggerEdits(
        targetFile: File,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUriString: String?
    ) {
        val audioFile = AudioFileIO.read(targetFile)
        val tag = audioFile.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)
        if (!genre.isNullOrBlank()) {
            tag.setField(FieldKey.GENRE, genre)
        } else {
            runCatching { tag.deleteField(FieldKey.GENRE) }
        }

        if (!coverUriString.isNullOrBlank()) {
            val coverFile = resolveLocalCoverFile(coverUriString)
            if (coverFile != null && coverFile.exists()) {
                runCatching {
                    val artwork = ArtworkFactory.createArtworkFromFile(coverFile)
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }
            }
        }
        audioFile.commit()
    }

    private fun resolveLocalCoverFile(coverUriString: String): File? {
        return try {
            val uri = coverUriString.toUri()
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                File(uri.path!!)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildWritePermissionResult(
        securityException: SecurityException,
        song: Song,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: String?
    ): MetadataWriteResult? {
        if (song.uri.scheme != "content") return null
        val pendingEdit = PendingMetadataEdit(song, title, artist, album, genre, coverUri)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(song.uri))
                MetadataWriteResult.NeedsWritePermission(pendingIntent.intentSender, pendingEdit)
            } catch (e: Exception) {
                Log.w("MetadataManager", "MediaStore.createWriteRequest failed: ${e.message}")
                null
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && securityException is RecoverableSecurityException) {
            return MetadataWriteResult.NeedsWritePermission(
                securityException.userAction.actionIntent.intentSender,
                pendingEdit
            )
        }
        return null
    }

    suspend fun updateFavoriteStatus(songId: Long, isFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = database.songOverrideDao().getOverrideForSong(songId)
            val override = existing?.copy(isFavorite = isFavorite)
                ?: SongOverride(songId = songId, isFavorite = isFavorite)

            database.songOverrideDao().insertOverride(override)
            true
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error updating favorite status", e)
            false
        }
    }

    suspend fun clearMetadataOverride(songId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = database.songOverrideDao().getOverrideForSong(songId)
            if (existing != null) {
                File(coversDir, "cover_$songId.jpg").takeIf { it.exists() }?.delete()
                val clearedOverride = SongOverride(
                    songId = songId,
                    isFavorite = existing.isFavorite
                )
                database.songOverrideDao().insertOverride(clearedOverride)
            }
            LuneAiEngine.getInstance(context).invalidateProfileCache(songId)
            true
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error clearing metadata override", e)
            false
        }
    }

    private fun saveCustomCover(songId: Long, imageUri: Uri): Uri? {
        return try {
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }
            val coverFile = File(coversDir, "cover_$songId.jpg")
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                coverFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(coverFile)
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error saving custom cover", e)
            null
        }
    }
}