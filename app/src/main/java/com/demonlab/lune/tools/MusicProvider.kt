package com.demonlab.lune.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.demonlab.lune.data.MusicDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.demonlab.lune.audio.ReplayGainScanner
import com.demonlab.lune.core.format.NativePcmContainerDecoder
import com.demonlab.lune.core.format.SeekableByteReader
import java.io.RandomAccessFile

data class LibraryScanProgress(
    val isScanning: Boolean = false,
    val discoveredCount: Int = 0,
    val cachedReusedCount: Int = 0,
    val newlyParsedCount: Int = 0,
    val phase: String = ""
)

class UriTypeAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(reader: JsonReader): Uri? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextString().toUri()
    }
}

class MusicProvider(private val context: Context) {
    companion object {
        private val _scanProgress = MutableStateFlow(LibraryScanProgress())
        val scanProgress: StateFlow<LibraryScanProgress> = _scanProgress.asStateFlow()
    }

    private val settingsManager = SettingsManager.getInstance(context)
    private val database = MusicDatabase.getDatabase(context)

    private val cacheFile = File(context.filesDir, "songs_cache.json")
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
        .disableHtmlEscaping()
        .create()

    fun getCachedSongs(): List<Song> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Song>>() {}.type
            cacheFile.bufferedReader(Charsets.UTF_8, 16 * 1024).use { reader ->
                gson.fromJson<List<Song>>(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveToCache(songs: List<Song>) {
        try {
            val tmpFile = File(context.filesDir, "songs_cache.json.tmp")
            tmpFile.bufferedWriter(Charsets.UTF_8, 16 * 1024).use { writer ->
                gson.toJson(songs, writer)
            }
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSongInCache(updatedSong: Song) {
        val songs = getCachedSongs().toMutableList()
        val idx = songs.indexOfFirst { it.id == updatedSong.id }
        if (idx >= 0) {
            songs[idx] = updatedSong
            saveToCache(songs)
        } else {
            songs.add(updatedSong)
            saveToCache(songs)
        }
    }

    private fun hasReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncSongs(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        var reusedCount = 0
        var newlyParsed = 0
        _scanProgress.value = LibraryScanProgress(isScanning = true, phase = "MediaStore")
        val songList = mutableListOf<Song>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.Audio.Media.GENRE
            } else {
                MediaStore.Audio.Media.ARTIST
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.BITRATE)
        }
        val projection = projectionList.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val overrides = database.songOverrideDao().getAllOverrides().associateBy { it.songId }
        val existingById = getCachedSongs().associateBy { it.id }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val genreColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1
            val bitrateColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                var title = cursor.getString(titleColumn)
                var artist = cursor.getString(artistColumn)
                var album = cursor.getString(albumColumn)
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val data = cursor.getString(dataColumn) ?: continue
                val dateAdded = cursor.getLong(dateAddedColumn)
                var trackNumber = if (trackColumn != -1) cursor.getInt(trackColumn) else 0
                if (trackNumber >= 1000) {
                    trackNumber %= 1000
                }
                var genre = if (genreColumn != -1) cursor.getString(genreColumn) else null

                val cachedSong = existingById[id]
                val override = overrides[id]
                var coverUrl: String? = null
                var isFavorite = false

                title = CharsetUtils.sanitizeText(title)
                artist = CharsetUtils.sanitizeText(artist)
                album = CharsetUtils.sanitizeText(album)

                val folderName = data.substringBeforeLast("/").substringAfterLast("/")
                val extension = data.substringAfterLast(".").lowercase()
                val isHiFiFile = com.demonlab.lune.core.format.AudioFormatRegistry.isLosslessOrHiFi(extension)
                val isHiFi = settingsManager.enableHiFi && isHiFiFile

                val mediaStoreBitrate = if (bitrateColumn != -1) {
                    val value = cursor.getInt(bitrateColumn)
                    if (value > 0) value else null
                } else null

                var sampleRate: Int? = null
                var bitDepth: Int? = null
                var isHiRes = false
                var bitrate: Int? = mediaStoreBitrate
                var channels: Int? = null
                var rgTrackDb: Float? = null
                var rgTrackPeak: Float? = null
                var rgAlbumDb: Float? = null
                var rgAlbumPeak: Float? = null

                val canReuseCachedHeader = cachedSong != null &&
                    cachedSong.dateAdded == dateAdded &&
                    cachedSong.path == data &&
                    (!isHiFiFile || cachedSong.sampleRate != null) &&
                    cachedSong.channels != null

                if (canReuseCachedHeader && cachedSong != null) {
                    reusedCount++
                    sampleRate = cachedSong.sampleRate
                    bitDepth = cachedSong.bitDepth
                    isHiRes = cachedSong.isHiRes
                    channels = cachedSong.channels
                    rgTrackDb = cachedSong.replayGainTrackDb
                    rgTrackPeak = cachedSong.replayGainTrackPeak
                    rgAlbumDb = cachedSong.replayGainAlbumDb
                    rgAlbumPeak = cachedSong.replayGainAlbumPeak
                    if (bitrate == null) bitrate = cachedSong.bitrate
                    if (override == null) {
                        if (cachedSong.artist.contains(";") && !artist.contains(";")) {
                            artist = cachedSong.artist
                        }
                        if (!cachedSong.genre.isNullOrBlank() && (genre.isNullOrBlank() || cachedSong.genre.contains(";"))) {
                            genre = cachedSong.genre
                        }
                        if (extension == "wav" || extension == "dsf" || extension == "dff") {
                            if (cachedSong.title.isNotBlank()) title = cachedSong.title
                            if (cachedSong.artist.isNotBlank()) artist = cachedSong.artist
                            if (cachedSong.album.isNotBlank()) album = cachedSong.album
                            if (!cachedSong.genre.isNullOrBlank()) genre = cachedSong.genre
                            if (cachedSong.trackNumber > 0) trackNumber = cachedSong.trackNumber
                        }
                    }
                } else {
                    newlyParsed++
                    val file = File(data)
                    if (bitrate == null && duration > 0 && file.exists()) {
                        bitrate = ((file.length() * 8) / (duration / 1000f)).toInt()
                    }
                    if (file.exists() && file.canRead()) {
                        try {
                            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                            val header = audioFile.audioHeader
                            sampleRate = header.sampleRateAsNumber.takeIf { it > 0 }
                            bitDepth = header.bitsPerSample.takeIf { it > 0 }
                            val chStr = header.channels?.trim().orEmpty()
                            channels = when {
                                chStr.equals("Mono", ignoreCase = true) -> 1
                                chStr.equals("Stereo", ignoreCase = true) || chStr.contains("Joint", ignoreCase = true) -> 2
                                else -> chStr.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 16) ?: 2
                            }
                            if (((sampleRate ?: 0) >= 48000 && (bitDepth ?: 16) >= 24) || (sampleRate ?: 0) >= 88200 || extension == "dsf" || extension == "dff") {
                                isHiRes = true
                            }

                            val tag = audioFile.tag
                            if (tag != null) {
                                val rgMeta = com.demonlab.lune.audio.ReplayGainScanner.extractReplayGainFromTag(tag)
                                rgTrackDb = rgMeta.trackGainDb
                                rgTrackPeak = rgMeta.trackPeak
                                rgAlbumDb = rgMeta.albumGainDb
                                rgAlbumPeak = rgMeta.albumPeak

                                if (override == null) {
                                    val tagTitle = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) }.getOrNull()?.trim()
                                    val allArtists = runCatching {
                                        tag.getAll(org.jaudiotagger.tag.FieldKey.ARTIST)
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .distinct()
                                    }.getOrDefault(emptyList())
                                    val tagArtist = if (allArtists.size > 1) {
                                        allArtists.joinToString("; ")
                                    } else {
                                        allArtists.firstOrNull() ?: runCatching {
                                            tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST)?.trim()
                                        }.getOrNull()
                                    }

                                    val allGenres = runCatching {
                                        tag.getAll(org.jaudiotagger.tag.FieldKey.GENRE)
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .distinct()
                                    }.getOrDefault(emptyList())
                                    val tagGenre = if (allGenres.size > 1) {
                                        allGenres.joinToString("; ")
                                    } else {
                                        allGenres.firstOrNull()
                                    }

                                    val tagAlbum = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM) }.getOrNull()?.trim()
                                    val tagTrack = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK) }.getOrNull()?.trim()

                                    if (!tagTitle.isNullOrBlank() && (title.isBlank() || extension == "wav")) {
                                        title = CharsetUtils.sanitizeText(tagTitle)
                                    }
                                    if (!tagArtist.isNullOrBlank() && (allArtists.size > 1 || artist.isBlank() || artist.equals("<unknown>", ignoreCase = true) || extension == "wav")) {
                                        artist = CharsetUtils.sanitizeText(tagArtist)
                                    }
                                    if (!tagAlbum.isNullOrBlank() && (album.isBlank() || album.equals("<unknown>", ignoreCase = true) || extension == "wav")) {
                                        album = CharsetUtils.sanitizeText(tagAlbum)
                                    }
                                    if (!tagGenre.isNullOrBlank() && (allGenres.size > 1 || genre.isNullOrBlank() || extension == "wav")) {
                                        genre = CharsetUtils.sanitizeText(tagGenre)
                                    }
                                    if (!tagTrack.isNullOrBlank() && (trackNumber == 0 || extension == "wav")) {
                                        val parsedTrack = tagTrack.substringBefore("/").toIntOrNull()
                                        if (parsedTrack != null) trackNumber = parsedTrack
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            channels = cachedSong?.channels ?: 2
                            if (bitrate != null && bitrate >= 2304000) {
                                isHiRes = true
                            }
                        }
                    } else {
                        channels = 2
                    }
                }

                val format = com.demonlab.lune.core.format.AudioFormatRegistry.formatBadgeLabel(
                    extension = extension,
                    channels = channels
                )

                if (override != null) {
                    override.title?.let { if (it.isNotBlank()) title = it }
                    override.artist?.let { if (it.isNotBlank()) artist = it }
                    override.album?.let { if (it.isNotBlank()) album = it }
                    override.genre?.let { if (it.isNotBlank()) genre = it }
                    coverUrl = override.coverUri
                    isFavorite = override.isFavorite
                }

                val contentUri: Uri = ContentUris.withAppendedId(collection, id)
                val albumArtUri = contentUri

                songList.add(
                    Song(
                        id = id,
                        albumId = albumId,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        uri = contentUri,
                        path = data,
                        dateAdded = dateAdded,
                        albumArtUri = albumArtUri,
                        genre = genre,
                        folderName = folderName,
                        isHiFi = isHiFi,
                        coverUrl = coverUrl,
                        isFavorite = isFavorite,
                        lyrics = null,
                        format = format,
                        bitrate = bitrate,
                        trackNumber = trackNumber,
                        isHiRes = isHiRes,
                        sampleRate = sampleRate,
                        bitDepth = bitDepth,
                        replayGainTrackDb = rgTrackDb,
                        replayGainTrackPeak = rgTrackPeak,
                        replayGainAlbumDb = rgAlbumDb,
                        replayGainAlbumPeak = rgAlbumPeak,
                        computedLoudnessDb = cachedSong?.computedLoudnessDb,
                        channels = channels
                    )
                )
                if (songList.size % 60 == 0) {
                    _scanProgress.value = LibraryScanProgress(
                        isScanning = true,
                        discoveredCount = songList.size,
                        cachedReusedCount = reusedCount,
                        newlyParsedCount = newlyParsed,
                        phase = "MediaStore"
                    )
                }
            }
        }

        // Supplementary fast MediaStore.Files + folder scan for extended audio & containers
        // (ALAC, AIFF, AIF, APE, WV, DSF, DFF, MKV, MKA, WebM, EAC3, AC3, AC4, DTS, WMA, CAF)
        _scanProgress.value = LibraryScanProgress(
            isScanning = true,
            discoveredCount = songList.size,
            cachedReusedCount = reusedCount,
            newlyParsedCount = newlyParsed,
            phase = "ExtendedFormats"
        )
        val indexedPaths = songList.mapTo(HashSet((songList.size * 2).coerceAtLeast(32))) { it.path }
        val existingByPath = existingById.values.associateBy { it.path }

        // 1. Fast MediaStore.Files query for audiophile extensions across storage
        runCatching {
            val filesUri = MediaStore.Files.getContentUri("external")
            val extSuffixes = listOf(
                ".alac", ".aiff", ".aif", ".aifc", ".ape", ".wv",
                ".dsf", ".dff", ".mka", ".mkv", ".webm",
                ".eac3", ".ec3", ".ac3", ".ac4", ".dts", ".wma", ".caf"
            )
            val whereClause = extSuffixes.joinToString(" OR ") { "LOWER(${MediaStore.Files.FileColumns.DATA}) LIKE ?" }
            val whereArgs = extSuffixes.map { "%$it" }.toTypedArray()
            context.contentResolver.query(
                filesUri,
                arrayOf(MediaStore.Files.FileColumns.DATA),
                whereClause,
                whereArgs,
                null
            )?.use { fCursor ->
                val pCol = fCursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (fCursor.moveToNext()) {
                    val path = if (pCol != -1) fCursor.getString(pCol) else null
                    if (path.isNullOrBlank() || path in indexedPaths) continue
                    val file = File(path)
                    if (!file.exists() || !file.isFile || file.length() <= 16384L) continue
                    indexedPaths.add(path)
                    val syntheticId = -(path.hashCode().toLong() and 0x7FFFFFFFL) - 10_000L
                    val cachedExtra = existingByPath[path] ?: existingById[syntheticId]
                    val lastSec = file.lastModified() / 1000L
                    if (cachedExtra != null && cachedExtra.dateAdded == lastSec) {
                        reusedCount++
                        val ov = overrides[cachedExtra.id]
                        songList.add(applyOverrideToCached(cachedExtra, ov))
                    } else {
                        newlyParsed++
                        buildDirectFileSong(file, syntheticId, overrides[syntheticId])?.let { songList.add(it) }
                    }
                }
            }
        }

        // 2. Shallow folder walk for non-MediaStore files in Music / Downloads / Custom folder
        val candidateDirs = linkedSetOf<File>()
        runCatching {
            val mDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (mDir != null && mDir.exists()) candidateDirs.add(mDir)
            val dDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dDir != null && dDir.exists()) candidateDirs.add(dDir)
            val customUriStr = settingsManager.musicFolderUri
            if (!customUriStr.isNullOrBlank()) {
                val seg = Uri.parse(customUriStr).lastPathSegment?.substringAfterLast(":")
                if (!seg.isNullOrBlank()) {
                    val cDir = File(Environment.getExternalStorageDirectory(), seg)
                    if (cDir.exists()) candidateDirs.add(cDir)
                }
            }
        }

        for (rootDir in candidateDirs) {
            runCatching {
                rootDir.walkTopDown()
                    .maxDepth(5)
                    .onEnter { dir -> !dir.name.startsWith(".") && !dir.name.equals("Android", ignoreCase = true) }
                    .filter { f ->
                        f.isFile && f.length() > 16384L &&
                            f.absolutePath !in indexedPaths &&
                            com.demonlab.lune.core.format.AudioFormatRegistry.isSupportedAudioExtension(f.extension)
                    }
                    .forEach { file ->
                        val absPath = file.absolutePath
                        indexedPaths.add(absPath)
                        val syntheticId = -(absPath.hashCode().toLong() and 0x7FFFFFFFL) - 10_000L
                        val cachedExtra = existingByPath[absPath] ?: existingById[syntheticId]
                        val lastSec = file.lastModified() / 1000L
                        if (cachedExtra != null && cachedExtra.dateAdded == lastSec) {
                            reusedCount++
                            val ov = overrides[cachedExtra.id]
                            songList.add(applyOverrideToCached(cachedExtra, ov))
                        } else {
                            newlyParsed++
                            buildDirectFileSong(file, syntheticId, overrides[syntheticId])?.let { songList.add(it) }
                        }
                    }
            }
        }

        saveToCache(songList)
        _scanProgress.value = LibraryScanProgress(
            isScanning = false,
            discoveredCount = songList.size,
            cachedReusedCount = reusedCount,
            newlyParsedCount = newlyParsed,
            phase = "Completed"
        )

        if (settingsManager.replayGainMode != 0 && settingsManager.replayGainFallbackAnalysis) {
            ReplayGainScanner.analyzeLibraryUntaggedInBackground(
                context = context,
                songs = songList,
                maxTracksPerPass = if (settingsManager.isLowResourceVisualsActive(context)) 40 else 150
            )
        }
        songList
    }

    private fun applyOverrideToCached(
        cachedExtra: Song,
        ov: com.demonlab.lune.data.SongOverride?
    ): Song {
        if (ov == null) return cachedExtra
        return cachedExtra.copy(
            title = ov.title?.takeIf { it.isNotBlank() } ?: cachedExtra.title,
            artist = ov.artist?.takeIf { it.isNotBlank() } ?: cachedExtra.artist,
            album = ov.album?.takeIf { it.isNotBlank() } ?: cachedExtra.album,
            genre = ov.genre?.takeIf { it.isNotBlank() } ?: cachedExtra.genre,
            coverUrl = ov.coverUri,
            isFavorite = ov.isFavorite
        )
    }

    private fun buildDirectFileSong(
        file: File,
        syntheticId: Long,
        override: com.demonlab.lune.data.SongOverride?
    ): Song? {
        val ext = file.extension.lowercase()
        var title = file.nameWithoutExtension
        var artist = "<unknown>"
        var album = file.parentFile?.name ?: "Unknown Album"
        var genre: String? = null
        var durationMs = 0L
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var bitDepth: Int? = null
        var channels = 2
        var trackNumber = 0
        var rgMeta = com.demonlab.lune.core.model.ReplayGainMetadata()

        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val hasAudio = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                if (ext in setOf("mp4", "mkv", "webm", "avi", "mov", "ts") && hasAudio == null) {
                    return null
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = CharsetUtils.sanitizeText(it) }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = CharsetUtils.sanitizeText(it) }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = CharsetUtils.sanitizeText(it) }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }?.let { genre = CharsetUtils.sanitizeText(it) }
                durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                trackNumber = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore("/")?.trim()?.toIntOrNull() ?: 0
            } finally {
                runCatching { retriever.release() }
            }
        }

        runCatching {
            val af = org.jaudiotagger.audio.AudioFileIO.read(file)
            val hdr = af.audioHeader
            if (durationMs <= 0L) durationMs = hdr.trackLength * 1000L
            sampleRate = hdr.sampleRateAsNumber.takeIf { it > 0 }
            bitDepth = hdr.bitsPerSample.takeIf { it > 0 }
            val chStr = hdr.channels?.trim().orEmpty()
            channels = when {
                chStr.equals("Mono", ignoreCase = true) -> 1
                chStr.equals("Stereo", ignoreCase = true) -> 2
                else -> chStr.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 16) ?: 2
            }
            af.tag?.let { t ->
                rgMeta = com.demonlab.lune.audio.ReplayGainScanner.extractReplayGainFromTag(t)
                val artists = runCatching { t.getAll(org.jaudiotagger.tag.FieldKey.ARTIST).map { it.trim() }.filter { it.isNotEmpty() }.distinct() }.getOrDefault(emptyList())
                if (artists.isNotEmpty()) artist = CharsetUtils.sanitizeText(artists.joinToString("; "))
                val genres = runCatching { t.getAll(org.jaudiotagger.tag.FieldKey.GENRE).map { it.trim() }.filter { it.isNotEmpty() }.distinct() }.getOrDefault(emptyList())
                if (genres.isNotEmpty()) genre = CharsetUtils.sanitizeText(genres.joinToString("; "))
                t.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let { title = CharsetUtils.sanitizeText(it) }
                t.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)?.takeIf { it.isNotBlank() }?.let { album = CharsetUtils.sanitizeText(it) }
            }
        }

        // Native pure-Kotlin header fallback for AIFF, AIFC, CAF, DSF, DFF, AU
        if ((durationMs <= 0L || sampleRate == null) && NativePcmContainerDecoder.isNativelyDecodableExtension(ext)) {
            runCatching {
                val raf = RandomAccessFile(file, "r")
                val reader = object : SeekableByteReader {
                    override val length: Long get() = raf.length()
                    override var position: Long
                        get() = raf.filePointer
                        set(value) { raf.seek(value.coerceAtLeast(0L)) }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = raf.read(buffer, offset, count)
                    override fun close() = raf.close()
                }
                val stream = NativePcmContainerDecoder.open(reader)
                if (stream != null) {
                    if (durationMs <= 0L && stream.durationMs > 0L) durationMs = stream.durationMs
                    if (sampleRate == null) sampleRate = stream.sourceSampleRate
                    if (bitDepth == null) bitDepth = if (ext == "dsf" || ext == "dff") 1 else 16
                    channels = stream.sourceChannels.coerceIn(1, 16)
                    stream.close()
                } else {
                    raf.close()
                }
            }
        }

        if (durationMs <= 0L && ext in setOf("mp4", "mkv", "webm", "avi", "mov")) return null
        val isHiFiFile = com.demonlab.lune.core.format.AudioFormatRegistry.isLosslessOrHiFi(ext)
        val isHiRes = ((sampleRate ?: 0) >= 48000 && (bitDepth ?: 16) >= 24) || (sampleRate ?: 0) >= 88200 || ext == "dsf" || ext == "dff"
        val fileUri = Uri.fromFile(file)
        val folderName = file.parentFile?.name ?: "Music"

        if (override != null) {
            override.title?.takeIf { it.isNotBlank() }?.let { title = it }
            override.artist?.takeIf { it.isNotBlank() }?.let { artist = it }
            override.album?.takeIf { it.isNotBlank() }?.let { album = it }
            override.genre?.takeIf { it.isNotBlank() }?.let { genre = it }
        }

        return Song(
            id = syntheticId,
            albumId = -(album.lowercase().hashCode().toLong() and 0x7FFFFFFFL),
            title = title,
            artist = artist,
            album = album,
            duration = durationMs.coerceAtLeast(1000L),
            uri = fileUri,
            path = file.absolutePath,
            dateAdded = file.lastModified() / 1000L,
            albumArtUri = fileUri,
            genre = genre,
            folderName = folderName,
            isHiFi = settingsManager.enableHiFi && isHiFiFile,
            coverUrl = override?.coverUri,
            isFavorite = override?.isFavorite ?: false,
            format = com.demonlab.lune.core.format.AudioFormatRegistry.formatBadgeLabel(ext, channels = channels),
            bitrate = bitrate,
            trackNumber = trackNumber,
            isHiRes = isHiRes,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            replayGainTrackDb = rgMeta.trackGainDb,
            replayGainTrackPeak = rgMeta.trackPeak,
            replayGainAlbumDb = rgMeta.albumGainDb,
            replayGainAlbumPeak = rgMeta.albumPeak,
            channels = channels
        )
    }

    suspend fun refreshLibrary(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        // Collect paths: known songs from cache + common music directories
        val pathsToScan = mutableSetOf<String>()
        getCachedSongs().forEach { pathsToScan.add(it.path) }

        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (musicDir.exists()) pathsToScan.add(musicDir.absolutePath)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir.exists()) pathsToScan.add(downloadsDir.absolutePath)

        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir.exists()) pathsToScan.add(documentsDir.absolutePath)

        val audioFolderUriStr = settingsManager.musicFolderUri
        if (!audioFolderUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(audioFolderUriStr)
                val pathSegment = uri.lastPathSegment?.substringAfterLast(":")
                if (!pathSegment.isNullOrBlank()) {
                    val customFolder = File(Environment.getExternalStorageDirectory(), pathSegment)
                    if (customFolder.exists()) pathsToScan.add(customFolder.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (pathsToScan.isNotEmpty()) {
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(
                context,
                pathsToScan.toTypedArray(),
                null
            ) { _, _ -> latch.countDown() }
            latch.await(15, TimeUnit.SECONDS)
        }

        syncSongs()
    }
}