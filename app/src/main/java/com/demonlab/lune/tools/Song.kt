package com.demonlab.lune.tools

import android.net.Uri
import com.demonlab.lune.core.metadata.MultiTagParser
import com.demonlab.lune.core.model.MultiTagConfig
import com.demonlab.lune.core.model.ReplayGainMetadata

data class Song(
    val id: Long,
    val albumId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val path: String,
    val dateAdded: Long = 0,
    val albumArtUri: Uri? = null,
    val genre: String? = null,
    val folderName: String,
    val isHiFi: Boolean = false,
    val coverUrl: String? = null,
    val isFavorite: Boolean = false,
    val lyrics: String? = null,
    val format: String = "",
    val bitrate: Int? = null,
    val trackNumber: Int = 0,
    val isHiRes: Boolean = false,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val artists: List<String>? = null,
    val genres: List<String>? = null,
    val cleanTitle: String? = null,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
    val computedLoudnessDb: Float? = null,
    val channels: Int? = null
) {
    fun parsedArtists(config: MultiTagConfig): List<String> {
        return MultiTagParser.parseTrackTags(
            rawTitle = title,
            rawArtist = artist,
            rawGenre = null,
            config = config
        ).artists
    }

    fun parsedGenres(config: MultiTagConfig): List<String> {
        val list = MultiTagParser.parseGenresOnly(genre, config)
        return if (list.isEmpty()) listOf("Desconocido") else list
    }

    fun effectiveDisplayTitle(config: MultiTagConfig): String {
        if (!config.cleanExtractedFeatFromTitle || !config.extractArtistsFromTitle) return title
        return MultiTagParser.parseTrackTags(
            rawTitle = title,
            rawArtist = artist,
            rawGenre = null,
            config = config
        ).cleanTitle
    }

    fun matchesArtist(targetArtist: String, config: MultiTagConfig): Boolean {
        val normTarget = targetArtist.trim()
        if (normTarget.isEmpty()) return false
        if (artist.trim().equals(normTarget, ignoreCase = true)) return true
        return parsedArtists(config).any { it.equals(normTarget, ignoreCase = true) }
    }

    fun matchesGenre(targetGenre: String, config: MultiTagConfig): Boolean {
        val normTarget = targetGenre.trim()
        if (normTarget.equals("Desconocido", ignoreCase = true) || normTarget.equals("Unknown", ignoreCase = true)) {
            val g = genre?.trim()
            return g.isNullOrEmpty() || g.equals("<unknown>", ignoreCase = true) || g.equals("unknown", ignoreCase = true)
        }
        return parsedGenres(config).any { it.equals(normTarget, ignoreCase = true) }
    }

    fun toReplayGainMetadata(overrideComputedDb: Float? = null): ReplayGainMetadata {
        return ReplayGainMetadata(
            trackGainDb = replayGainTrackDb,
            trackPeak = replayGainTrackPeak,
            albumGainDb = replayGainAlbumDb,
            albumPeak = replayGainAlbumPeak,
            computedLoudnessDb = overrideComputedDb ?: computedLoudnessDb
        )
    }

    fun toCoreTrack(): com.demonlab.lune.core.model.CoreTrack {
        return com.demonlab.lune.core.model.CoreTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            durationMs = duration,
            trackNumber = trackNumber,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            formatCode = format,
            channels = channels ?: 2
        )
    }
}
