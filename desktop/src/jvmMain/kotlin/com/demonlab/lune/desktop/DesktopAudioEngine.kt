package com.demonlab.lune.desktop

import com.demonlab.lune.core.dsp.CrossfadeCurveEngine
import com.demonlab.lune.core.dsp.ReplayGainEngine
import com.demonlab.lune.core.format.AudioFormatRegistry
import com.demonlab.lune.core.metadata.MultiTagParser
import com.demonlab.lune.core.model.CoreTrack
import com.demonlab.lune.core.model.CrossfadeCurveMode
import com.demonlab.lune.core.model.MultiTagConfig
import com.demonlab.lune.core.model.PerformanceProfile
import com.demonlab.lune.core.model.ReplayGainConfig
import com.demonlab.lune.core.model.ReplayGainMetadata
import com.demonlab.lune.core.model.ReplayGainResult
import com.demonlab.lune.core.model.UiDensityMode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.ln
import kotlin.math.pow

/**
 * Persistent Desktop preferences stored in ~/.config/lune/desktop_settings.json
 * (or %APPDATA%/Lune on Windows).
 */
data class DesktopSettings(
    val lastMusicFolder: String = File(System.getProperty("user.home"), "Music").absolutePath,
    val selectedMixerName: String = "",
    val masterVolume: Float = 0.85f,
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Float = 5f,
    val gaplessEnabled: Boolean = true,
    val respectAlbumGapless: Boolean = true,
    val replayGainConfig: ReplayGainConfig = ReplayGainConfig(),
    val showReplayGainBadge: Boolean = true,
    val multiTagConfig: MultiTagConfig = MultiTagConfig(),
    val performanceProfile: PerformanceProfile = PerformanceProfile.AUTO,
    val uiDensityMode: UiDensityMode = UiDensityMode.STANDARD,
    val uiFontScale: Float = 1.0f,
    val gridColumns: Int = 0, // 0 = Auto
    val blacklistPaths: Set<String> = emptySet()
)

data class CachedTrackEntry(
    val path: String,
    val lastModified: Long,
    val fileSize: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val durationMs: Long,
    val year: Int,
    val trackNumber: Int,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val bitrate: Int?,
    val channels: Int,
    val formatCode: String,
    val rgTrackGainDb: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGainDb: Float?,
    val rgAlbumPeak: Float?,
    val computedLoudnessDb: Float?
)

data class DesktopSong(
    val core: CoreTrack,
    val file: File
)

data class AudioOutputDevice(
    val id: String,
    val displayName: String,
    val description: String
)

private interface PcmFrameSource : AutoCloseable {
    val sampleRate: Float
    val channels: Int
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Ultra-lightweight Desktop Audio, Extended-Format & Gapless Engine for Windows and Linux.
 *
 * Optimizations for modest / legacy hardware (e.g. Pentium E5800 SSE2, 1-core CPUs, mechanical HDDs,
 * NVIDIA GT 610, PipeWire/ALSA, Generalplus USB Audio DAC):
 * - Incremental disk metadata cache (~/.config/lune/desktop_library_cache.json) skips unchanged files on HDDs.
 * - SSE2-safe `ffmpeg` s16le pipe decoder supports ALAC, AIFF, MKV, MKA, MP4, WEBM, Opus 5.1/7.1,
 *   EAC3, AC3, AC4, APE, WavPack, DSD, WMA, FLAC, MP3, OGG, WAV with zero JVM heap bloat.
 * - Automatic pure-JVM `AudioSystem` fallback + fixed-point Q10 ITU-R BS.775 stereo downmixer.
 * - True sample-accurate Gapless playback: keeps a single `SourceDataLine` open across consecutive tracks.
 * - In-place Q12 fixed-point ReplayGain + Anti-Clipping Peak Limiter.
 */
class DesktopAudioEngine(private val scope: CoroutineScope) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val configDir: File = resolveConfigDirectory()
    private val settingsFile = File(configDir, "desktop_settings.json")
    private val cacheFile = File(configDir, "desktop_library_cache.json")

    val isLowSpecHardwareDetected: Boolean = detectModestDesktopHardware()
    val hasFfmpegAvailable: Boolean by lazy { detectFfmpegBinary() != null }
    private val ffmpegBinaryPath: String? by lazy { detectFfmpegBinary() }

    private val _currentSong = MutableStateFlow<DesktopSong?>(null)
    val currentSong: StateFlow<DesktopSong?> = _currentSong.asStateFlow()

    private val _activeReplayGain = MutableStateFlow<ReplayGainResult?>(null)
    val activeReplayGain: StateFlow<ReplayGainResult?> = _activeReplayGain.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var primaryJob: Job? = null
    private var secondaryJob: Job? = null
    @Volatile private var primaryLine: SourceDataLine? = null
    @Volatile private var secondaryLine: SourceDataLine? = null
    @Volatile private var activeFfmpegProcess: Process? = null
    @Volatile private var secondaryFfmpegProcess: Process? = null

    @Volatile private var masterVolume: Float = 0.85f
    @Volatile private var balance: Float = 0.5f
    @Volatile private var selectedMixerName: String = ""
    @Volatile private var replayGainConfig: ReplayGainConfig = ReplayGainConfig()
    @Volatile private var gaplessEnabled: Boolean = true
    @Volatile private var respectAlbumGapless: Boolean = true
    @Volatile private var crossfadeEnabled: Boolean = false
    @Volatile private var crossfadeMs: Long = 0L
    @Volatile private var lowResourceUiThrottling: Boolean = isLowSpecHardwareDetected

    // Provider queried by the streaming loop right as a track ends for zero-gap splicing
    @Volatile var nextTrackProvider: ((current: DesktopSong, allowGaplessOnly: Boolean) -> DesktopSong?)? = null

    fun loadSettings(): DesktopSettings {
        return try {
            if (settingsFile.exists()) {
                val parsed = gson.fromJson(settingsFile.readText(), DesktopSettings::class.java)
                if (parsed != null) {
                    applyEngineSettings(parsed)
                    return parsed
                }
            }
            val defaults = DesktopSettings()
            applyEngineSettings(defaults)
            defaults
        } catch (_: Exception) {
            DesktopSettings()
        }
    }

    fun saveSettings(settings: DesktopSettings) {
        applyEngineSettings(settings)
        scope.launch(Dispatchers.IO) {
            try {
                if (!configDir.exists()) configDir.mkdirs()
                settingsFile.writeText(gson.toJson(settings))
            } catch (_: Exception) {
            }
        }
    }

    fun applyEngineSettings(settings: DesktopSettings) {
        masterVolume = settings.masterVolume.coerceIn(0f, 1f)
        selectedMixerName = settings.selectedMixerName
        replayGainConfig = settings.replayGainConfig
        gaplessEnabled = settings.gaplessEnabled
        respectAlbumGapless = settings.respectAlbumGapless
        crossfadeEnabled = settings.crossfadeEnabled
        crossfadeMs = if (settings.crossfadeEnabled) (settings.crossfadeSeconds * 1000f).toLong() else 0L
        lowResourceUiThrottling = when (settings.performanceProfile) {
            PerformanceProfile.AUTO -> isLowSpecHardwareDetected
            PerformanceProfile.ULTRA_SAVER, PerformanceProfile.LIGHT -> true
            PerformanceProfile.FULL -> false
        }
        applyLineGain(primaryLine, masterVolume)
        _currentSong.value?.let { current ->
            _activeReplayGain.value = ReplayGainEngine.computeLinearMultiplier(
                metadata = current.core.replayGain,
                config = replayGainConfig,
                isAlbumSequentialContext = true
            )
        }
    }

    fun listAudioOutputDevices(): List<AudioOutputDevice> {
        val result = ArrayList<AudioOutputDevice>()
        result.add(AudioOutputDevice("", "Predeterminado del Sistema (PipeWire / ALSA / WASAPI)", "Salida automática del SO"))
        try {
            val probeFormat = AudioFormat(48000f, 16, 2, true, false)
            val lineInfo = DataLine.Info(SourceDataLine::class.java, probeFormat)
            for (info in AudioSystem.getMixerInfo()) {
                val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
                if (mixer.isLineSupported(lineInfo)) {
                    result.add(
                        AudioOutputDevice(
                            id = info.name,
                            displayName = info.name,
                            description = info.description.orEmpty()
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    suspend fun scanFolder(
        folder: File,
        multiTagConfig: MultiTagConfig = MultiTagConfig(),
        forceRescan: Boolean = false
    ): List<DesktopSong> = withContext(Dispatchers.IO) {
        if (!folder.exists() || !folder.isDirectory) return@withContext emptyList()
        _isScanning.value = true
        try {
            val cachedMap: MutableMap<String, CachedTrackEntry> = if (forceRescan) {
                HashMap()
            } else {
                loadTrackCache()
            }
            var cacheDirty = false
            val results = ArrayList<DesktopSong>(512)

            folder.walkTopDown()
                .maxDepth(10)
                .filter { it.isFile && AudioFormatRegistry.isSupportedAudioExtension(it.extension) }
                .forEach { file ->
                    val absPath = file.absolutePath
                    val lastMod = file.lastModified()
                    val length = file.length()
                    val cached = cachedMap[absPath]

                    val entry = if (cached != null && cached.lastModified == lastMod && cached.fileSize == length) {
                        cached
                    } else {
                        val parsed = parseAudioFileToCacheEntry(file)
                        cachedMap[absPath] = parsed
                        cacheDirty = true
                        parsed
                    }
                    results.add(buildDesktopSong(entry, file, multiTagConfig))
                }

            if (cacheDirty) {
                saveTrackCache(cachedMap)
            }

            results.sortedWith(
                compareBy(
                    { it.core.album.lowercase(Locale.ROOT) },
                    { if (it.core.trackNumber > 0) it.core.trackNumber else Int.MAX_VALUE },
                    { it.core.title.lowercase(Locale.ROOT) }
                )
            )
        } finally {
            _isScanning.value = false
        }
    }

    fun reparseSongsWithMultiTagConfig(
        songs: List<DesktopSong>,
        multiTagConfig: MultiTagConfig
    ): List<DesktopSong> {
        return songs.map { existing ->
            val parsedTags = MultiTagParser.parseTrackTags(
                rawTitle = existing.core.title,
                rawArtist = existing.core.artist,
                rawGenre = existing.core.genre,
                config = multiTagConfig
            )
            existing.copy(
                core = existing.core.copy(
                    artists = parsedTags.artists,
                    genres = parsedTags.genres,
                    displayTitle = if (multiTagConfig.cleanExtractedFeatFromTitle) parsedTags.cleanTitle else existing.core.title
                )
            )
        }
    }

    private fun buildDesktopSong(
        entry: CachedTrackEntry,
        file: File,
        multiTagConfig: MultiTagConfig
    ): DesktopSong {
        val parsedTags = MultiTagParser.parseTrackTags(
            rawTitle = entry.title,
            rawArtist = entry.artist,
            rawGenre = entry.genre,
            config = multiTagConfig
        )
        return DesktopSong(
            core = CoreTrack(
                id = entry.path.hashCode().toLong(),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                genre = entry.genre,
                durationMs = entry.durationMs,
                year = entry.year,
                trackNumber = entry.trackNumber,
                sampleRate = entry.sampleRate,
                bitDepth = entry.bitDepth,
                bitrate = entry.bitrate,
                artists = parsedTags.artists,
                genres = parsedTags.genres,
                displayTitle = if (multiTagConfig.cleanExtractedFeatFromTitle) parsedTags.cleanTitle else entry.title,
                replayGain = ReplayGainMetadata(
                    trackGainDb = entry.rgTrackGainDb,
                    trackPeak = entry.rgTrackPeak,
                    albumGainDb = entry.rgAlbumGainDb,
                    albumPeak = entry.rgAlbumPeak,
                    computedLoudnessDb = entry.computedLoudnessDb
                ),
                formatCode = entry.formatCode,
                channels = entry.channels
            ),
            file = file
        )
    }

    private fun parseAudioFileToCacheEntry(file: File): CachedTrackEntry {
        val ext = file.extension.lowercase(Locale.ROOT)
        try {
            val af = AudioFileIO.read(file)
            val tag = af.tag
            val header = af.audioHeader
            val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() } ?: file.parentFile?.name.orEmpty()
            val genre = tag?.getFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
            val trackNo = tag?.getFirst(FieldKey.TRACK)?.substringBefore("/")?.trim()?.toIntOrNull() ?: 0
            val year = tag?.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0
            val channels = parseChannelCount(header?.channels)

            val rgMeta = ReplayGainEngine.fromRawTags(
                rgTrackGain = readCustomTag(tag, "REPLAYGAIN_TRACK_GAIN", "replaygain_track_gain"),
                rgTrackPeak = readCustomTag(tag, "REPLAYGAIN_TRACK_PEAK", "replaygain_track_peak"),
                rgAlbumGain = readCustomTag(tag, "REPLAYGAIN_ALBUM_GAIN", "replaygain_album_gain"),
                rgAlbumPeak = readCustomTag(tag, "REPLAYGAIN_ALBUM_PEAK", "replaygain_album_peak"),
                r128TrackGain = readCustomTag(tag, "R128_TRACK_GAIN", "r128_track_gain"),
                r128AlbumGain = readCustomTag(tag, "R128_ALBUM_GAIN", "r128_album_gain"),
                iTunNorm = readCustomTag(tag, "iTunNORM", "ITUNNORM")
            )

            val formatBadge = AudioFormatRegistry.formatBadgeLabel(ext, header?.format, channels)
            return CachedTrackEntry(
                path = file.absolutePath,
                lastModified = file.lastModified(),
                fileSize = file.length(),
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                durationMs = (header?.trackLength ?: 0) * 1000L,
                year = year,
                trackNumber = trackNo,
                sampleRate = header?.sampleRateAsNumber,
                bitDepth = header?.bitsPerSample?.takeIf { it > 0 },
                bitrate = header?.bitRateAsNumber?.let { (it * 1000).toInt() },
                channels = channels,
                formatCode = formatBadge,
                rgTrackGainDb = rgMeta.trackGainDb,
                rgTrackPeak = rgMeta.trackPeak,
                rgAlbumGainDb = rgMeta.albumGainDb,
                rgAlbumPeak = rgMeta.albumPeak,
                computedLoudnessDb = null
            )
        } catch (_: Exception) {
            return CachedTrackEntry(
                path = file.absolutePath,
                lastModified = file.lastModified(),
                fileSize = file.length(),
                title = file.nameWithoutExtension,
                artist = "Unknown Artist",
                album = file.parentFile?.name.orEmpty(),
                genre = null,
                durationMs = 0L,
                year = 0,
                trackNumber = 0,
                sampleRate = null,
                bitDepth = null,
                bitrate = null,
                channels = 2,
                formatCode = AudioFormatRegistry.formatBadgeLabel(ext, null, 2),
                rgTrackGainDb = null,
                rgTrackPeak = null,
                rgAlbumGainDb = null,
                rgAlbumPeak = null,
                computedLoudnessDb = null
            )
        }
    }

    private fun readCustomTag(tag: org.jaudiotagger.tag.Tag?, vararg keys: String): String? {
        if (tag == null) return null
        for (key in keys) {
            val v = runCatching { tag.getFirst(key) }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun parseChannelCount(rawChannels: String?): Int {
        if (rawChannels.isNullOrBlank()) return 2
        val lower = rawChannels.lowercase(Locale.ROOT).trim()
        lower.toIntOrNull()?.let { return it.coerceIn(1, 8) }
        return when {
            lower.contains("mono") -> 1
            lower.contains("stereo") || lower.contains("joint") || lower.contains("dual") -> 2
            lower.contains("5.1") -> 6
            lower.contains("7.1") -> 8
            else -> Regex("\\d+").find(lower)?.value?.toIntOrNull()?.coerceIn(1, 8) ?: 2
        }
    }

    suspend fun writeRealMetadata(
        song: DesktopSong,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        multiTagConfig: MultiTagConfig = MultiTagConfig()
    ): DesktopSong? = withContext(Dispatchers.IO) {
        try {
            val af = AudioFileIO.read(song.file)
            val tag = af.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, album)
            if (!genre.isNullOrBlank()) {
                tag.setField(FieldKey.GENRE, genre)
            }
            af.commit()
            val updatedEntry = parseAudioFileToCacheEntry(song.file)
            buildDesktopSong(updatedEntry, song.file, multiTagConfig)
        } catch (_: Exception) {
            null
        }
    }

    fun play(
        song: DesktopSong,
        startOffsetMs: Long = 0L,
        crossfadeOverrideMs: Long? = null,
        onTrackAdvanced: (DesktopSong) -> Unit = {}
    ) {
        val effectiveFadeMs = crossfadeOverrideMs ?: crossfadeMs
        val current = _currentSong.value
        val useGaplessInsteadOfFade = current != null && AudioFormatRegistry.shouldUseGaplessTransition(
            gaplessEnabled = gaplessEnabled,
            crossfadeEnabled = effectiveFadeMs > 0L,
            automixEnabled = false,
            respectAlbumGapless = respectAlbumGapless,
            isShuffleActive = false,
            currentAlbum = current.core.album,
            currentTrackNumber = current.core.trackNumber,
            nextAlbum = song.core.album,
            nextTrackNumber = song.core.trackNumber
        )

        if (startOffsetMs == 0L && effectiveFadeMs > 0L && !useGaplessInsteadOfFade && _isPlaying.value && primaryLine != null) {
            crossfadeTo(song, effectiveFadeMs, onTrackAdvanced)
            return
        }

        stopPlaybackStreamsOnly()
        _currentSong.value = song
        _isPlaying.value = true
        primaryJob = scope.launch(Dispatchers.IO) {
            streamChainToLine(
                initialSong = song,
                initialOffsetMs = startOffsetMs,
                isSecondary = false,
                initialScalar = 1f,
                onTrackAdvanced = onTrackAdvanced
            )
        }
    }

    fun seekToFraction(fraction: Float, onTrackAdvanced: (DesktopSong) -> Unit = {}) {
        val song = _currentSong.value ?: return
        val clamped = fraction.coerceIn(0f, 0.995f)
        val duration = song.core.durationMs
        if (duration <= 0L) return
        val targetMs = (duration * clamped).toLong()
        _progress.value = clamped
        play(song = song, startOffsetMs = targetMs, crossfadeOverrideMs = 0L, onTrackAdvanced = onTrackAdvanced)
    }

    private fun crossfadeTo(
        nextSong: DesktopSong,
        durationMs: Long,
        onTrackAdvanced: (DesktopSong) -> Unit
    ) {
        secondaryJob?.cancel()
        destroyProcessQuietly(secondaryFfmpegProcess)
        secondaryFfmpegProcess = null
        secondaryLine?.close()
        _currentSong.value = nextSong
        _isPlaying.value = true

        secondaryJob = scope.launch(Dispatchers.IO) {
            streamChainToLine(
                initialSong = nextSong,
                initialOffsetMs = 0L,
                isSecondary = true,
                initialScalar = 0f,
                onTrackAdvanced = onTrackAdvanced
            )
        }

        scope.launch(Dispatchers.Default) {
            val stepMs = if (lowResourceUiThrottling) 80L else 40L
            val steps = (durationMs / stepMs).coerceAtLeast(8L).toInt()
            val delayStep = durationMs / steps
            for (step in 1..steps) {
                val p = step.toFloat() / steps
                val gains = CrossfadeCurveEngine.computeStereoGains(
                    progress = p,
                    targetLeft = masterVolume * (if (balance <= 0.5f) 1f else (1f - balance) * 2f),
                    targetRight = masterVolume * (if (balance >= 0.5f) 1f else balance * 2f),
                    mode = CrossfadeCurveMode.HYBRID_AUDIOPHILE
                )
                applyLineGain(primaryLine, maxOf(gains.outgoingLeft, gains.outgoingRight))
                applyLineGain(secondaryLine, maxOf(gains.incomingLeft, gains.incomingRight))
                delay(delayStep)
            }
            primaryJob?.cancel()
            destroyProcessQuietly(activeFfmpegProcess)
            primaryLine?.stop()
            primaryLine?.close()
            primaryLine = secondaryLine
            primaryJob = secondaryJob
            activeFfmpegProcess = secondaryFfmpegProcess
            secondaryLine = null
            secondaryJob = null
            secondaryFfmpegProcess = null
        }
    }

    fun togglePlayPause() {
        val line = primaryLine ?: return
        if (_isPlaying.value) {
            line.stop()
            _isPlaying.value = false
        } else {
            line.start()
            _isPlaying.value = true
        }
    }

    fun setMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
        applyLineGain(primaryLine, masterVolume)
    }

    /**
     * Streams [initialSong] and seamlessly splices subsequent tracks onto the SAME [SourceDataLine]
     * when Gapless playback is active, completely eliminating ALSA / PipeWire / WASAPI close-reopen clicks!
     */
    private suspend fun streamChainToLine(
        initialSong: DesktopSong,
        initialOffsetMs: Long,
        isSecondary: Boolean,
        initialScalar: Float,
        onTrackAdvanced: (DesktopSong) -> Unit
    ) {
        var currentTrack: DesktopSong? = initialSong
        var startMs = initialOffsetMs
        var activeLine: SourceDataLine? = null
        var activeRate = 0f

        val rawReadBuf = ByteArray(16384)
        val stereoBuf = ByteArray(16384)

        try {
            while (currentTrack != null && currentCoroutineContext().isActive) {
                val track = currentTrack
                if (!isSecondary) {
                    _currentSong.value = track
                }

                var rgMeta = track.core.replayGain
                val source = openDecodedPcmSource(track, startMs, isSecondary) ?: break

                try {
                    val targetFormat = AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        source.sampleRate,
                        16,
                        2,
                        4,
                        source.sampleRate,
                        false
                    )

                    if (activeLine == null || !activeLine.isOpen || kotlin.math.abs(activeRate - source.sampleRate) > 1f) {
                        activeLine?.drain()
                        activeLine?.close()
                        val newLine = openHardwareLine(targetFormat)
                        activeLine = newLine
                        activeRate = source.sampleRate
                        if (isSecondary) secondaryLine = newLine else primaryLine = newLine
                        applyLineGain(newLine, initialScalar * masterVolume)
                        newLine.start()
                    }

                    var rgLinearScalar = computeTrackPcmGainScalar(rgMeta, isSecondary)
                    var hasProbedFallbackLoudness = rgMeta.hasEmbeddedTags || rgMeta.computedLoudnessDb != null || !replayGainConfig.enableFallbackAnalysis

                    var stereoBytesWritten = ((startMs / 1000.0) * source.sampleRate * 4.0).toLong()
                    val bytesPerMs = ((source.sampleRate * 4f) / 1000f).coerceAtLeast(1f)
                    var lastProgressUpdateMs = 0L
                    val progressIntervalMs = if (lowResourceUiThrottling) 450L else 120L

                    while (currentCoroutineContext().isActive) {
                        if (!_isPlaying.value) {
                            delay(60)
                            continue
                        }
                        val readBytes = source.read(rawReadBuf, 0, rawReadBuf.size)
                        if (readBytes == -1) break
                        if (readBytes <= 0) continue

                        val stereoValidBytes = if (source.channels == 2) {
                            rawReadBuf.copyInto(stereoBuf, 0, 0, readBytes)
                            readBytes
                        } else {
                            AudioFormatRegistry.downmixPcm16leToStereo(
                                inBuffer = rawReadBuf,
                                inValidBytes = readBytes,
                                inChannels = source.channels,
                                outStereoBuffer = stereoBuf
                            )
                        }

                        if (!hasProbedFallbackLoudness && stereoValidBytes >= 2048) {
                            hasProbedFallbackLoudness = true
                            val estimated = ReplayGainEngine.estimateGainFromPcm16le(stereoBuf, stereoValidBytes, 2)
                            if (estimated != null) {
                                rgMeta = rgMeta.copy(
                                    computedLoudnessDb = estimated.first,
                                    trackPeak = estimated.second
                                )
                                rgLinearScalar = computeTrackPcmGainScalar(rgMeta, isSecondary)
                            }
                        }

                        if (rgLinearScalar !in 0.995f..1.005f) {
                            AudioFormatRegistry.applyGainPcm16leInPlace(stereoBuf, stereoValidBytes, rgLinearScalar)
                        }

                        activeLine.write(stereoBuf, 0, stereoValidBytes)
                        stereoBytesWritten += stereoValidBytes

                        if (!isSecondary && track.core.durationMs > 0L) {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdateMs >= progressIntervalMs) {
                                lastProgressUpdateMs = now
                                val elapsedMs = stereoBytesWritten / bytesPerMs
                                _progress.value = (elapsedMs / track.core.durationMs).coerceIn(0f, 1f)
                            }
                        }
                    }
                } finally {
                    source.close()
                }

                if (!currentCoroutineContext().isActive) break

                val provider = nextTrackProvider
                val candidateNext = provider?.invoke(track, false)
                if (candidateNext != null) {
                    val useGapless = AudioFormatRegistry.shouldUseGaplessTransition(
                        gaplessEnabled = gaplessEnabled,
                        crossfadeEnabled = crossfadeEnabled && crossfadeMs > 0L,
                        automixEnabled = false,
                        respectAlbumGapless = respectAlbumGapless,
                        isShuffleActive = false,
                        currentAlbum = track.core.album,
                        currentTrackNumber = track.core.trackNumber,
                        nextAlbum = candidateNext.core.album,
                        nextTrackNumber = candidateNext.core.trackNumber
                    )
                    if (useGapless) {
                        currentTrack = candidateNext
                        startMs = 0L
                        withContext(Dispatchers.Main) { onTrackAdvanced(candidateNext) }
                        continue
                    } else {
                        activeLine?.drain()
                        activeLine?.close()
                        activeLine = null
                        withContext(Dispatchers.Main) {
                            onTrackAdvanced(candidateNext)
                            play(candidateNext, startOffsetMs = 0L, onTrackAdvanced = onTrackAdvanced)
                        }
                        return
                    }
                } else {
                    currentTrack = null
                }
            }

            activeLine?.drain()
            activeLine?.close()
            if (!isSecondary && currentCoroutineContext().isActive) {
                _isPlaying.value = false
                _progress.value = 0f
            }
        } catch (_: Exception) {
            activeLine?.close()
            if (!isSecondary) _isPlaying.value = false
        }
    }

    private fun computeTrackPcmGainScalar(meta: ReplayGainMetadata, isSecondary: Boolean): Float {
        val res = ReplayGainEngine.computeLinearMultiplier(
            metadata = meta,
            config = replayGainConfig,
            isAlbumSequentialContext = true
        )
        if (!isSecondary) {
            _activeReplayGain.value = res
        }
        return if (res.appliedGainDb == 0f) {
            1.0f
        } else {
            10.0.pow(res.appliedGainDb / 20.0).toFloat().coerceIn(0.05f, 3.2f)
        }
    }

    private fun openDecodedPcmSource(
        song: DesktopSong,
        startOffsetMs: Long,
        isSecondary: Boolean
    ): PcmFrameSource? {
        val ffmpeg = ffmpegBinaryPath
        val ext = song.file.extension.lowercase(Locale.ROOT)
        val isNativeJavaSoundFormat = ext == "wav" || ext == "wave" || ext == "au" || ext == "aiff" || ext == "aif"

        if (ffmpeg != null && (!isNativeJavaSoundFormat || startOffsetMs > 0L || song.core.channels > 2)) {
            openFfmpegPcmSource(ffmpeg, song, startOffsetMs, isSecondary)?.let { return it }
        }

        return try {
            val rawStream = AudioSystem.getAudioInputStream(song.file)
            val baseFormat = rawStream.format
            val channels = baseFormat.channels.coerceIn(1, 8)
            val sampleRate = baseFormat.sampleRate.takeIf { it > 0f } ?: 44100f
            val pcmFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
            )
            val pcmStream = AudioSystem.getAudioInputStream(pcmFormat, rawStream)
            if (startOffsetMs > 0L) {
                val bytesToSkip = ((startOffsetMs / 1000.0) * sampleRate * channels * 2).toLong()
                skipFullyBounded(pcmStream, bytesToSkip)
            }
            object : PcmFrameSource {
                override val sampleRate: Float = sampleRate
                override val channels: Int = channels
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    pcmStream.read(buffer, offset, length)
                override fun close() {
                    runCatching { pcmStream.close() }
                    runCatching { rawStream.close() }
                }
            }
        } catch (_: Exception) {
            if (ffmpeg != null) openFfmpegPcmSource(ffmpeg, song, startOffsetMs, isSecondary) else null
        }
    }

    private fun openFfmpegPcmSource(
        ffmpegBin: String,
        song: DesktopSong,
        startOffsetMs: Long,
        isSecondary: Boolean
    ): PcmFrameSource? {
        return try {
            val outSampleRate = (song.core.sampleRate ?: 48000).coerceIn(22050, 96000)
            val cmd = ArrayList<String>(16)
            cmd.add(ffmpegBin)
            cmd.add("-nostdin")
            cmd.add("-v")
            cmd.add("quiet")
            if (startOffsetMs > 0L) {
                cmd.add("-ss")
                cmd.add(String.format(Locale.US, "%.3f", startOffsetMs / 1000.0))
            }
            cmd.add("-i")
            cmd.add(song.file.absolutePath)
            cmd.add("-vn")
            cmd.add("-sn")
            cmd.add("-dn")
            cmd.add("-ac")
            cmd.add("2")
            cmd.add("-ar")
            cmd.add(outSampleRate.toString())
            cmd.add("-f")
            cmd.add("s16le")
            cmd.add("pipe:1")

            val proc = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            if (isSecondary) secondaryFfmpegProcess = proc else activeFfmpegProcess = proc
            val stdout = BufferedInputStream(proc.inputStream, 32768)

            object : PcmFrameSource {
                override val sampleRate: Float = outSampleRate.toFloat()
                override val channels: Int = 2
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val alignedLen = length and -4
                    if (alignedLen <= 0) return 0
                    var total = 0
                    while (total < alignedLen) {
                        val n = stdout.read(buffer, offset + total, alignedLen - total)
                        if (n == -1) return if (total == 0) -1 else (total and -4)
                        total += n
                        if (total >= 4 && stdout.available() == 0) break
                    }
                    return total and -4
                }

                override fun close() {
                    runCatching { stdout.close() }
                    destroyProcessQuietly(proc)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openHardwareLine(format: AudioFormat): SourceDataLine {
        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
        val preferredName = selectedMixerName
        val line: SourceDataLine = if (preferredName.isNotBlank()) {
            val matchedMixer: Mixer? = AudioSystem.getMixerInfo()
                .firstOrNull { it.name == preferredName }
                ?.let { runCatching { AudioSystem.getMixer(it) }.getOrNull() }
            if (matchedMixer != null && matchedMixer.isLineSupported(lineInfo)) {
                matchedMixer.getLine(lineInfo) as SourceDataLine
            } else {
                AudioSystem.getSourceDataLine(format)
            }
        } else {
            AudioSystem.getSourceDataLine(format)
        }
        line.open(format, 65536)
        return line
    }

    private fun skipFullyBounded(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip - (bytesToSkip % 4)
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun applyLineGain(line: SourceDataLine?, linearGain: Float) {
        if (line == null || !line.isOpen) return
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val ctrl = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val safeLinear = linearGain.coerceIn(0.0001f, 1f)
                val db = (20.0 * ln(safeLinear.toDouble()) / ln(10.0)).toFloat()
                    .coerceIn(ctrl.minimum, ctrl.maximum)
                ctrl.value = db
            }
        } catch (_: Exception) {
        }
    }

    private fun stopPlaybackStreamsOnly() {
        primaryJob?.cancel()
        secondaryJob?.cancel()
        destroyProcessQuietly(activeFfmpegProcess)
        destroyProcessQuietly(secondaryFfmpegProcess)
        activeFfmpegProcess = null
        secondaryFfmpegProcess = null
        runCatching { primaryLine?.stop() }
        runCatching { primaryLine?.close() }
        runCatching { secondaryLine?.stop() }
        runCatching { secondaryLine?.close() }
        primaryLine = null
        secondaryLine = null
    }

    fun stopInternal() {
        stopPlaybackStreamsOnly()
        _isPlaying.value = false
        _progress.value = 0f
    }

    private fun destroyProcessQuietly(process: Process?) {
        if (process == null) return
        runCatching {
            if (process.isAlive) {
                process.destroy()
            }
        }
    }

    private fun loadTrackCache(): MutableMap<String, CachedTrackEntry> {
        return try {
            if (!cacheFile.exists()) return HashMap()
            val listType = object : TypeToken<List<CachedTrackEntry>>() {}.type
            val list: List<CachedTrackEntry>? = gson.fromJson(cacheFile.readText(), listType)
            val map = HashMap<String, CachedTrackEntry>((list?.size ?: 16) * 2)
            list?.forEach { map[it.path] = it }
            map
        } catch (_: Exception) {
            HashMap()
        }
    }

    private fun saveTrackCache(map: Map<String, CachedTrackEntry>) {
        try {
            if (!configDir.exists()) configDir.mkdirs()
            cacheFile.writeText(gson.toJson(map.values.toList()))
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun resolveConfigDirectory(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return if (os.contains("win")) {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) File(appData, "Lune") else File(System.getProperty("user.home"), ".lune")
            } else {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, "lune") else File(File(System.getProperty("user.home"), ".config"), "lune")
            }
        }

        private fun detectModestDesktopHardware(): Boolean {
            val cores = Runtime.getRuntime().availableProcessors()
            val maxMemMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            return cores <= 2 || maxMemMb <= 256L
        }

        private fun detectFfmpegBinary(): String? {
            val candidates = listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg.exe")
            for (bin in candidates) {
                try {
                    val p = ProcessBuilder(bin, "-version")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    if (p.waitFor() == 0) return bin
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
