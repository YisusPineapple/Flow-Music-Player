package com.demonlab.lune.core.model

enum class CrossfadeCurveMode {
    EQUAL_POWER,
    LOGARITHMIC,
    HYBRID_AUDIOPHILE
}

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
    SMART_ALBUM
}

enum class PerformanceProfile {
    AUTO,
    ULTRA_SAVER,
    LIGHT,
    FULL
}

enum class UiDensityMode {
    COMPACT,
    STANDARD,
    COMFORTABLE
}

data class ReplayGainMetadata(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
    val computedLoudnessDb: Float? = null
) {
    val hasEmbeddedTags: Boolean
        get() = trackGainDb != null || albumGainDb != null
}

data class ReplayGainConfig(
    val mode: ReplayGainMode = ReplayGainMode.SMART_ALBUM,
    val preAmpWithTagsDb: Float = 0.0f,
    val preAmpUntaggedDb: Float = 0.0f,
    val preventClipping: Boolean = true,
    val enableFallbackAnalysis: Boolean = true,
    val targetLoudnessLufs: Float = -18.0f
)

data class ReplayGainResult(
    val appliedGainDb: Float,
    val linearVolumeScalar: Float,
    val postBoostMillibels: Int,
    val isPeakLimited: Boolean,
    val sourceLabel: String
)

data class MultiTagConfig(
    val enableMultiArtist: Boolean = true,
    val enableMultiGenre: Boolean = true,
    val splitSemicolon: Boolean = true,
    val splitComma: Boolean = true,
    val splitSlash: Boolean = true,
    val splitAmpersand: Boolean = true,
    val splitFeatKeywords: Boolean = true,
    val extractArtistsFromTitle: Boolean = true,
    val cleanExtractedFeatFromTitle: Boolean = false,
    val customWhitelist: Set<String> = emptySet()
)

data class ParsedTrackTags(
    val artists: List<String>,
    val genres: List<String>,
    val cleanTitle: String,
    val primaryArtist: String
)

data class CoreTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val durationMs: Long,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrate: Int? = null,
    val artists: List<String> = if (artist.isNotBlank()) listOf(artist) else emptyList(),
    val genres: List<String> = if (!genre.isNullOrBlank()) listOf(genre) else emptyList(),
    val displayTitle: String = title,
    val replayGain: ReplayGainMetadata = ReplayGainMetadata(),
    val formatCode: String = "",
    val channels: Int = 2
)

data class CoreAudioProfile(
    val trackId: Long,
    val energy: Float,
    val valence: Float,
    val danceability: Float,
    val acousticness: Float,
    val normalizedGenre: String,
    val isSpokenWord: Boolean = false
)

data class CrossfadeConfig(
    val enabled: Boolean,
    val automixEnabled: Boolean,
    val adaptiveDuration: Boolean,
    val silenceDetectionEnabled: Boolean,
    val baseDurationSeconds: Int,
    val curveMode: CrossfadeCurveMode = CrossfadeCurveMode.HYBRID_AUDIOPHILE
)

data class TailAnalysisResult(
    val songId: Long,
    val earlyFadeTriggerOffsetMs: Long,
    val trailingSilenceMs: Long,
    val tailRmsRatio: Float
)
