package com.demonlab.lune.core.dsp

import com.demonlab.lune.core.model.ReplayGainConfig
import com.demonlab.lune.core.model.ReplayGainMetadata
import com.demonlab.lune.core.model.ReplayGainMode
import com.demonlab.lune.core.model.ReplayGainResult
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.pow

/**
 * Shared Multiplatform (`commonMain`) service for reading ReplayGain 1.0/2.0, EBU R128,
 * Apple SoundCheck (`iTunNORM`), and direct integrated `LUFS` loudness tags from audio files
 * using `jaudiotagger`, and computing peak-safe gain adjustments for media playback.
 *
 * Used by both Android (`MusicService`, `ExtendedFormatPlayer`, `ReplayGainScanner`, `MusicProvider`)
 * and Desktop (`DesktopAudioEngine`).
 */
object ReplayGainTagService {

    private const val REFERENCE_RG2_LUFS = -18.0f
    private const val MAX_FILE_CACHE_ENTRIES = 2048

    private data class CachedFileTagEntry(
        val lastModified: Long,
        val length: Long,
        val metadata: ReplayGainMetadata
    )

    private val fileTagCache = ConcurrentHashMap<String, CachedFileTagEntry>()

    init {
        runCatching {
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
        }
    }

    private val RG_TRACK_GAIN_KEYS = listOf(
        "REPLAYGAIN_TRACK_GAIN",
        "replaygain_track_gain",
        "TXXX:REPLAYGAIN_TRACK_GAIN",
        "TXXX:replaygain_track_gain",
        "----:com.apple.iTunes:replaygain_track_gain",
        "----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN"
    )

    private val RG_TRACK_PEAK_KEYS = listOf(
        "REPLAYGAIN_TRACK_PEAK",
        "replaygain_track_peak",
        "TXXX:REPLAYGAIN_TRACK_PEAK",
        "TXXX:replaygain_track_peak",
        "----:com.apple.iTunes:replaygain_track_peak",
        "----:com.apple.iTunes:REPLAYGAIN_TRACK_PEAK"
    )

    private val RG_ALBUM_GAIN_KEYS = listOf(
        "REPLAYGAIN_ALBUM_GAIN",
        "replaygain_album_gain",
        "TXXX:REPLAYGAIN_ALBUM_GAIN",
        "TXXX:replaygain_album_gain",
        "----:com.apple.iTunes:replaygain_album_gain",
        "----:com.apple.iTunes:REPLAYGAIN_ALBUM_GAIN"
    )

    private val RG_ALBUM_PEAK_KEYS = listOf(
        "REPLAYGAIN_ALBUM_PEAK",
        "replaygain_album_peak",
        "TXXX:REPLAYGAIN_ALBUM_PEAK",
        "TXXX:replaygain_album_peak",
        "----:com.apple.iTunes:replaygain_album_peak",
        "----:com.apple.iTunes:REPLAYGAIN_ALBUM_PEAK"
    )

    private val R128_TRACK_GAIN_KEYS = listOf(
        "R128_TRACK_GAIN",
        "r128_track_gain",
        "TXXX:R128_TRACK_GAIN",
        "----:com.apple.iTunes:R128_TRACK_GAIN"
    )

    private val R128_ALBUM_GAIN_KEYS = listOf(
        "R128_ALBUM_GAIN",
        "r128_album_gain",
        "TXXX:R128_ALBUM_GAIN",
        "----:com.apple.iTunes:R128_ALBUM_GAIN"
    )

    private val TRACK_LUFS_KEYS = listOf(
        "LUFS",
        "TRACK_LUFS",
        "INTEGRATED_LOUDNESS",
        "LOUDNESS_LUFS",
        "EBU_R128_LOUDNESS",
        "REPLAYGAIN_TRACK_LOUDNESS",
        "----:com.apple.iTunes:LUFS",
        "----:com.apple.iTunes:INTEGRATED_LOUDNESS"
    )

    private val ALBUM_LUFS_KEYS = listOf(
        "ALBUM_LUFS",
        "REPLAYGAIN_ALBUM_LOUDNESS",
        "----:com.apple.iTunes:ALBUM_LUFS"
    )

    private val ITUNNORM_KEYS = listOf(
        "ITUNNORM",
        "iTunNORM",
        "COMMENT ITUNNORM",
        "----:com.apple.iTunes:iTunNORM"
    )

    /**
     * Reads ReplayGain / R128 / LUFS / SoundCheck tags directly from [file] via `jaudiotagger`,
     * using an internal `(path, lastModified, fileSize)` cache to avoid redundant disk I/O.
     */
    fun readFromFile(file: File?): ReplayGainMetadata {
        if (file == null || !file.exists() || !file.isFile || !file.canRead()) {
            return ReplayGainMetadata()
        }
        val absPath = file.absolutePath
        val lastMod = file.lastModified()
        val len = file.length()

        fileTagCache[absPath]?.let { hit ->
            if (hit.lastModified == lastMod && hit.length == len) {
                return hit.metadata
            }
        }

        val parsed = runCatching {
            val audioFile = AudioFileIO.read(file)
            extractFromAudioFile(audioFile)
        }.getOrDefault(ReplayGainMetadata())

        if (fileTagCache.size >= MAX_FILE_CACHE_ENTRIES) {
            val iter = fileTagCache.keys.iterator()
            var evicted = 0
            while (iter.hasNext() && evicted < 256) {
                iter.next()
                iter.remove()
                evicted++
            }
        }
        fileTagCache[absPath] = CachedFileTagEntry(lastMod, len, parsed)
        return parsed
    }

    /**
     * Extracts ReplayGain / R128 / LUFS metadata from an already-opened [AudioFile].
     */
    fun extractFromAudioFile(audioFile: AudioFile?): ReplayGainMetadata {
        if (audioFile == null) return ReplayGainMetadata()
        return extractFromTag(audioFile.tag)
    }

    /**
     * Extracts ReplayGain 1.0/2.0, EBU R128 (`Q7.8`), direct `LUFS` loudness tags, and Apple
     * SoundCheck (`iTunNORM`) across Vorbis/FLAC/Opus, ID3v2 (`TXXX`/`COMM` on MP3/WAV/AIFF/DSF),
     * MP4/M4A/ALAC reverse-DNS atoms, APEv2, and ASF/WMA tags.
     */
    fun extractFromTag(tag: Tag?): ReplayGainMetadata {
        if (tag == null) return ReplayGainMetadata()

        val id3CustomMap = HashMap<String, String>(12)
        val effectiveId3Tag: AbstractID3v2Tag? = when (tag) {
            is AbstractID3v2Tag -> tag
            is org.jaudiotagger.tag.wav.WavTag -> tag.iD3Tag
            else -> null
        }
        if (effectiveId3Tag != null) {
            collectId3CustomFrames(effectiveId3Tag, id3CustomMap)
        }

        val genericFieldMap = HashMap<String, String>(16)
        runCatching {
            val iterator = tag.fields
            while (iterator.hasNext()) {
                val field = iterator.next() ?: continue
                val rawId = field.id?.trim().orEmpty()
                if (rawId.isEmpty()) continue
                val normId = normalizeDescriptor(rawId)
                if (field is TagTextField) {
                    val content = field.content?.trim()
                    if (!content.isNullOrBlank()) {
                        genericFieldMap.putIfAbsent(normId, content)
                    }
                } else {
                    val rawStr = field.toString().trim()
                    if (rawStr.isNotBlank()) {
                        extractInlineDescriptionText(rawStr)?.let { (desc, value) ->
                            genericFieldMap.putIfAbsent(normalizeDescriptor(desc), value)
                        }
                    }
                }
            }
        }

        fun lookup(candidates: List<String>): String? {
            for (key in candidates) {
                val direct = runCatching { tag.getFirst(key) }.getOrNull()?.trim()
                if (!direct.isNullOrBlank()) return direct
            }
            for (key in candidates) {
                val norm = normalizeDescriptor(key)
                id3CustomMap[norm]?.takeIf { it.isNotBlank() }?.let { return it }
                genericFieldMap[norm]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }

        val rgTrackGainRaw = lookup(RG_TRACK_GAIN_KEYS)
        val rgTrackPeakRaw = lookup(RG_TRACK_PEAK_KEYS)
        val rgAlbumGainRaw = lookup(RG_ALBUM_GAIN_KEYS)
        val rgAlbumPeakRaw = lookup(RG_ALBUM_PEAK_KEYS)
        val r128TrackRaw = lookup(R128_TRACK_GAIN_KEYS)
        val r128AlbumRaw = lookup(R128_ALBUM_GAIN_KEYS)
        val iTunNormRaw = lookup(ITUNNORM_KEYS)
        val trackLufsRaw = lookup(TRACK_LUFS_KEYS)
        val albumLufsRaw = lookup(ALBUM_LUFS_KEYS)

        val base = ReplayGainEngine.fromRawTags(
            rgTrackGain = rgTrackGainRaw,
            rgTrackPeak = rgTrackPeakRaw,
            rgAlbumGain = rgAlbumGainRaw,
            rgAlbumPeak = rgAlbumPeakRaw,
            r128TrackGain = r128TrackRaw,
            r128AlbumGain = r128AlbumRaw,
            iTunNorm = iTunNormRaw
        )

        val lufsTrackGainDb = if (base.trackGainDb == null) parseLufsTagToReplayGainDb(trackLufsRaw) else null
        val lufsAlbumGainDb = if (base.albumGainDb == null) parseLufsTagToReplayGainDb(albumLufsRaw) else null

        return if (lufsTrackGainDb != null || lufsAlbumGainDb != null) {
            base.copy(
                trackGainDb = base.trackGainDb ?: lufsTrackGainDb,
                albumGainDb = base.albumGainDb ?: lufsAlbumGainDb
            )
        } else {
            base
        }
    }

    /**
     * Converts a direct loudness tag (e.g. `"-14.2 LUFS"`, `"-20.5 LKFS"`, or `"-16.0"`)
     * into a ReplayGain 2.0 gain delta relative to `-18.0 LUFS`.
     */
    fun parseLufsTagToReplayGainDb(rawLufs: String?): Float? {
        val parsedLufs = ReplayGainEngine.parseGainDb(rawLufs) ?: return null
        // Valid integrated loudness values lie between -70 LUFS and +5 LUFS
        if (parsedLufs !in -70.0f..5.0f) return null
        return (REFERENCE_RG2_LUFS - parsedLufs).coerceIn(-24.0f, 18.0f)
    }

    /**
     * Computes the full [ReplayGainResult] to apply during playback for [metadata] and [config].
     */
    fun computePlaybackAdjustment(
        metadata: ReplayGainMetadata?,
        config: ReplayGainConfig,
        isAlbumSequentialContext: Boolean = false
    ): ReplayGainResult {
        return ReplayGainEngine.computeLinearMultiplier(
            metadata = metadata,
            config = config,
            isAlbumSequentialContext = isAlbumSequentialContext
        )
    }

    /**
     * Computes the unified peak-limited linear PCM multiplier (`1.0f` = unity) for direct
     * sample-domain scaling in ExoPlayer's AudioProcessor, Native AudioTrack, or Desktop SourceDataLine.
     */
    fun computeTotalLinearGain(
        metadata: ReplayGainMetadata?,
        config: ReplayGainConfig,
        isAlbumSequentialContext: Boolean = false
    ): Float {
        if (config.mode == ReplayGainMode.OFF) return 1.0f
        val result = computePlaybackAdjustment(metadata, config, isAlbumSequentialContext)
        if (result.appliedGainDb == 0f) return 1.0f
        return 10.0.pow(result.appliedGainDb / 20.0).toFloat().coerceIn(0.03f, 3.98f)
    }

    private fun collectId3CustomFrames(id3Tag: AbstractID3v2Tag, out: MutableMap<String, String>) {
        fun handleFrame(obj: Any?) {
            when (obj) {
                is AbstractID3v2Frame -> {
                    when (val body = obj.body) {
                        is FrameBodyTXXX -> {
                            val desc = body.description?.trim().orEmpty()
                            val value = body.firstTextValue?.trim().takeIf { !it.isNullOrBlank() }
                                ?: body.text?.trim().orEmpty()
                            if (desc.isNotEmpty() && value.isNotEmpty()) {
                                out.putIfAbsent(normalizeDescriptor(desc), value)
                            }
                        }
                        is FrameBodyCOMM -> {
                            val desc = body.description?.trim().orEmpty()
                            val value = body.firstTextValue?.trim().takeIf { !it.isNullOrBlank() }
                                ?: body.text?.trim().orEmpty()
                            if (desc.isNotEmpty() && value.isNotEmpty()) {
                                out.putIfAbsent(normalizeDescriptor(desc), value)
                            }
                        }
                    }
                }
                is Iterable<*> -> obj.forEach { handleFrame(it) }
            }
        }

        for (frameId in listOf("TXXX", "TXX", "COMM", "COM")) {
            runCatching { handleFrame(id3Tag.getFrame(frameId)) }
        }
    }

    private fun normalizeDescriptor(raw: String): String {
        val stripped = raw
            .removePrefix("TXXX:")
            .removePrefix("txxx:")
            .removePrefix("----:com.apple.iTunes:")
            .removePrefix("COMMENT ")
            .trim()
        return stripped.uppercase(Locale.ROOT)
    }

    private fun extractInlineDescriptionText(frameString: String): Pair<String, String>? {
        // Jaudiotagger ID3 FrameBodyTXXX.toString() formats as: Description="..."; Text="..."
        val descIdx = frameString.indexOf("Description=\"", ignoreCase = true)
        val textIdx = frameString.indexOf("Text=\"", ignoreCase = true)
        if (descIdx >= 0 && textIdx > descIdx) {
            val dStart = descIdx + "Description=\"".length
            val dEnd = frameString.indexOf('"', dStart)
            val tStart = textIdx + "Text=\"".length
            val tEnd = frameString.indexOf('"', tStart)
            if (dEnd > dStart && tEnd > tStart) {
                return frameString.substring(dStart, dEnd) to frameString.substring(tStart, tEnd)
            }
        }
        return null
    }
}
