package com.demonlab.lune.desktop

import com.demonlab.lune.core.dsp.CrossfadeCurveEngine
import com.demonlab.lune.core.dsp.ReplayGainTagService
import com.demonlab.lune.core.dsp.StreamingLufsAnalyzer
import com.demonlab.lune.core.format.AudioFormatRegistry
import com.demonlab.lune.core.format.NativePcmContainerDecoder
import com.demonlab.lune.core.format.SeekableByteReader
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
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Persistent Desktop preferences stored in ~/.config/flow/desktop_settings.json
 * (or %APPDATA%/Flow on Windows), with automatic seamless migration from legacy Lune config.
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
 * Flow Ultra-Lightweight Desktop Audio, Real-Time K-Weighted LUFS, Extended-Format & Gapless Engine
 * for Windows and Linux.
 *
 * Compatible with all 23+ formats & containers:
 * ALAC, AIFF/AIF, APE, WavPack (WV), DSD (DSF/DFF), M4A, MP4, MKV/MKA, WebM,
 * Opus (stereo & multichannel 5.1/7.1), E-AC3, AC-3, AC-4, DTS, WMA, CAF,
 * FLAC, WAV, OGG, AAC, MP3, AU/SND.
 *
 * Designed for <180 MB total RSS on modest dual-core / single-core SSE2 PCs (e.g. Pentium E5800 + GT 610).
 */
class DesktopAudioEngine(private val scope: CoroutineScope) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val configDir: File = resolveConfigDirectory()
    private val settingsFile = File(configDir, "desktop_settings.json")
    private val cacheFile = File(configDir, "desktop_library_cache.json")

    val isLowSpecHardwareDetected: Boolean = detectModestDesktopHardware()
    val hasFfmpegAvailable: Boolean by lazy { detectFfmpegBinary() != null }
    private val ffmpegBinaryPath: String? by lazy { detectFfmpegBinary() }
    private val ffprobeBinaryPath: String? by lazy { detectFfprobeBinary() }

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

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    // Lightweight in-memory map of real-time computed LUFS gains persisted periodically to disk
    private val computedLoudnessOverrides = ConcurrentHashMap<String, Pair<Float, Float>>()

    private var primaryJob: Job? = null
    private var secondaryJob: Job? = null
    private var backgroundLufsJob: Job? = null
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
    @Volatile var isLowResourceActive: Boolean = isLowSpecHardwareDetected
        private set

    // Provider queried by the streaming loop right as a track ends for zero-gap splicing
    @Volatile var nextTrackProvider: ((current: DesktopSong, allowGaplessOnly: Boolean) -> DesktopSong?)? = null

    fun loadSettings(): DesktopSettings {
        return try {
            if (settingsFile.exists()) {
                val parsed = settingsFile.bufferedReader(Charsets.UTF_8).use {
                    gson.fromJson(it, DesktopSettings::class.java)
                }
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
                settingsFile.bufferedWriter(Charsets.UTF_8).use { gson.toJson(settings, it) }
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
        isLowResourceActive = when (settings.performanceProfile) {
            PerformanceProfile.AUTO -> isLowSpecHardwareDetected
            PerformanceProfile.ULTRA_SAVER, PerformanceProfile.LIGHT -> true
            PerformanceProfile.FULL -> false
        }
        applyLineGain(primaryLine, masterVolume)
        _currentSong.value?.let { current ->
            val effectiveMeta = mergeWithComputedOverride(current)
            _activeReplayGain.value = ReplayGainTagService.computePlaybackAdjustment(
                metadata = effectiveMeta,
                config = replayGainConfig,
                isAlbumSequentialContext = true
            )
        }
    }

    private fun mergeWithComputedOverride(song: DesktopSong): ReplayGainMetadata {
        val base = song.core.replayGain
        if (base.hasEmbeddedTags) return base
        val ov = computedLoudnessOverrides[song.file.absolutePath] ?: return base
        return base.copy(computedLoudnessDb = ov.first, trackPeak = ov.second)
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
        _scanStatus.value = "Iniciando escaneo incremental..."
        try {
            val cachedMap: MutableMap<String, CachedTrackEntry> = if (forceRescan) {
                HashMap()
            } else {
                loadTrackCache()
            }
            var cacheDirty = false
            var reused = 0
            var parsedNew = 0
            val results = ArrayList<DesktopSong>(512)

            folder.walkTopDown()
                .maxDepth(10)
                .onEnter { dir -> !dir.name.startsWith(".") }
                .filter { it.isFile && it.length() > 4096L && AudioFormatRegistry.isSupportedAudioExtension(it.extension) }
                .forEach { file ->
                    val absPath = file.absolutePath
                    val lastMod = file.lastModified()
                    val length = file.length()
                    val cached = cachedMap[absPath]

                    val entry = if (cached != null && cached.lastModified == lastMod && cached.fileSize == length) {
                        reused++
                        cached
                    } else {
                        parsedNew++
                        val parsed = parseAudioFileToCacheEntry(file, cached?.computedLoudnessDb)
                        cachedMap[absPath] = parsed
                        cacheDirty = true
                        parsed
                    }
                    entry.computedLoudnessDb?.let { comp ->
                        computedLoudnessOverrides[absPath] = comp to (entry.rgTrackPeak ?: 0.98f)
                    }
                    results.add(buildDesktopSong(entry, file, multiTagConfig))
                    if (results.size % 50 == 0) {
                        _scanStatus.value = "Escaneadas ${results.size} pistas ($reused en caché, $parsedNew nuevas)..."
                    }
                }

            if (cacheDirty) {
                saveTrackCache(cachedMap)
            }

            val sorted = results.sortedWith(
                compareBy(
                    { it.core.album.lowercase(Locale.ROOT) },
                    { if (it.core.trackNumber > 0) it.core.trackNumber else Int.MAX_VALUE },
                    { it.core.title.lowercase(Locale.ROOT) }
                )
            )
            _scanStatus.value = "Biblioteca lista: ${sorted.size} pistas ($reused instantáneas desde caché)"
            sorted
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Low-priority background ITU-R BS.1770 K-weighted LUFS analyzer for untagged Desktop tracks.
     */
    fun analyzeUntaggedTracksInBackground(songs: List<DesktopSong>) {
        if (backgroundLufsJob?.isActive == true) return
        val candidates = songs.filter {
            !it.core.replayGain.hasEmbeddedTags &&
                it.core.replayGain.computedLoudnessDb == null &&
                !computedLoudnessOverrides.containsKey(it.file.absolutePath)
        }
        if (candidates.isEmpty()) {
            _scanStatus.value = "Todas las pistas ya cuentan con ganancia ReplayGain / LUFS."
            return
        }

        backgroundLufsJob = scope.launch(Dispatchers.IO) {
            val cachedMap = loadTrackCache()
            var dirtyCount = 0
            val readBuf = ByteArray(16384)
            val stereoBuf = ByteArray(16384)

            candidates.forEachIndexed { idx, song ->
                if (!isActive) return@launch
                // Pause background analysis while crossfading on modest 2-core CPUs
                if (isLowResourceActive && _isPlaying.value) {
                    delay(120L)
                }
                _scanStatus.value = "Analizando LUFS (${idx + 1}/${candidates.size}): ${song.core.displayTitle}"

                val startOffset = if (song.core.durationMs > 25_000L) (song.core.durationMs * 0.25).toLong() else 0L
                val source = openDecodedPcmSource(song, startOffset, isSecondary = true)
                if (source != null) {
                    try {
                        val analyzer = StreamingLufsAnalyzer(
                            sampleRateHz = source.sampleRate.toInt().coerceIn(8000, 192000),
                            channels = 2
                        )
                        val maxStereoBytes = (source.sampleRate.toInt() * 4 * 8).coerceAtLeast(65536)
                        var processedBytes = 0
                        while (processedBytes < maxStereoBytes && isActive) {
                            val n = source.read(readBuf, 0, readBuf.size)
                            if (n <= 0) break
                            val stValid = if (source.channels == 2) {
                                readBuf.copyInto(stereoBuf, 0, 0, n)
                                n
                            } else {
                                AudioFormatRegistry.downmixPcm16leToStereo(readBuf, n, source.channels, stereoBuf)
                            }
                            analyzer.processPcm16le(stereoBuf, 0, stValid)
                            processedBytes += stValid
                        }
                        val measured = analyzer.currentReplayGainDbAndPeak()
                        if (measured != null) {
                            persistComputedLoudness(cachedMap, song.file.absolutePath, measured.first, measured.second, saveNow = false)
                            dirtyCount++
                            if (dirtyCount % 12 == 0) {
                                saveTrackCache(cachedMap)
                            }
                        }
                    } finally {
                        runCatching { source.close() }
                    }
                }
                delay(if (isLowResourceActive) 90L else 20L)
            }
            if (dirtyCount > 0) {
                saveTrackCache(cachedMap)
            }
            _scanStatus.value = "Análisis LUFS completado ($dirtyCount pistas normalizadas)"
        }
    }

    private fun persistComputedLoudness(
        cacheMap: MutableMap<String, CachedTrackEntry>?,
        absPath: String,
        gainDb: Float,
        peak: Float,
        saveNow: Boolean
    ) {
        computedLoudnessOverrides[absPath] = gainDb to peak
        val targetMap = cacheMap ?: loadTrackCache()
        val existing = targetMap[absPath]
        if (existing != null) {
            targetMap[absPath] = existing.copy(
                computedLoudnessDb = gainDb,
                rgTrackPeak = existing.rgTrackPeak ?: peak
            )
            if (saveNow) {
                scope.launch(Dispatchers.IO) { saveTrackCache(targetMap) }
            }
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

    private fun parseAudioFileToCacheEntry(file: File, existingComputedDb: Float? = null): CachedTrackEntry {
        val ext = file.extension.lowercase(Locale.ROOT)
        var title = file.nameWithoutExtension
        var artist = "Unknown Artist"
        var album = file.parentFile?.name.orEmpty()
        var genre: String? = null
        var trackNo = 0
        var year = 0
        var durationMs = 0L
        var sampleRate: Int? = null
        var bitDepth: Int? = null
        var bitrate: Int? = null
        var channels = 2
        var codecHint: String? = null
        var rgMeta = ReplayGainMetadata()

        // 1. Jaudiotagger tag & audioHeader inspection (MP3, FLAC, OGG, OPUS, M4A, MP4, WAV, AIFF, DSF, WMA)
        runCatching {
            val af = AudioFileIO.read(file)
            val tag = af.tag
            val header = af.audioHeader
            codecHint = header?.format
            val allArtists = runCatching {
                tag?.getAll(FieldKey.ARTIST)?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            }.getOrDefault(emptyList())
            val joinedArtist = if (allArtists.size > 1) allArtists.joinToString("; ") else allArtists.firstOrNull()
            val allGenres = runCatching {
                tag?.getAll(FieldKey.GENRE)?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            }.getOrDefault(emptyList())
            val joinedGenre = if (allGenres.size > 1) allGenres.joinToString("; ") else allGenres.firstOrNull()

            tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            joinedArtist?.takeIf { it.isNotBlank() }?.let { artist = it }
            tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
            joinedGenre?.takeIf { it.isNotBlank() }?.let { genre = it }
            trackNo = tag?.getFirst(FieldKey.TRACK)?.substringBefore("/")?.trim()?.toIntOrNull() ?: 0
            year = tag?.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0
            durationMs = (header?.trackLength ?: 0) * 1000L
            sampleRate = header?.sampleRateAsNumber?.takeIf { it > 0 }
            bitDepth = header?.bitsPerSample?.takeIf { it > 0 }
            bitrate = header?.bitRateAsNumber?.let { (it * 1000).toInt() }
            channels = parseChannelCount(header?.channels)

            rgMeta = ReplayGainTagService.extractFromTag(tag)
        }

        // 2. Native pure-Kotlin header inspection for AIFF/AIFC, CAF, DSF, DFF, AU
        if ((durationMs <= 0L || sampleRate == null) && NativePcmContainerDecoder.isNativelyDecodableExtension(ext)) {
            runCatching {
                val raf = RandomAccessFile(file, "r")
                val reader = rafToSeekableReader(raf)
                val stream = NativePcmContainerDecoder.open(reader)
                if (stream != null) {
                    if (durationMs <= 0L) durationMs = stream.durationMs
                    if (sampleRate == null) sampleRate = stream.sourceSampleRate
                    if (bitDepth == null) bitDepth = if (ext == "dsf" || ext == "dff") 1 else 16
                    channels = stream.sourceChannels.coerceIn(1, 16)
                    stream.close()
                } else {
                    raf.close()
                }
            }
        }

        // 3. Lightweight ffprobe fallback for containers (MKV, MKA, WebM, EAC3, AC3, AC4, DTS, APE, WV)
        if (durationMs <= 0L && ffprobeBinaryPath != null) {
            probeViaFfprobe(file)?.let { (dur, sr, ch) ->
                if (dur > 0L) durationMs = dur
                if (sampleRate == null && sr > 0) sampleRate = sr
                if (ch > 0) channels = ch
            }
        }

        val formatBadge = AudioFormatRegistry.formatBadgeLabel(ext, codecHint, channels)
        return CachedTrackEntry(
            path = file.absolutePath,
            lastModified = file.lastModified(),
            fileSize = file.length(),
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            durationMs = durationMs,
            year = year,
            trackNumber = trackNo,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            channels = channels,
            formatCode = formatBadge,
            rgTrackGainDb = rgMeta.trackGainDb,
            rgTrackPeak = rgMeta.trackPeak,
            rgAlbumGainDb = rgMeta.albumGainDb,
            rgAlbumPeak = rgMeta.albumPeak,
            computedLoudnessDb = existingComputedDb
        )
    }

    private fun probeViaFfprobe(file: File): Triple<Long, Int, Int>? {
        val bin = ffprobeBinaryPath ?: return null
        return try {
            val proc = ProcessBuilder(
                bin, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels:format=duration",
                "-of", "default=noprint_wrappers=1:nokey=0",
                file.absolutePath
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            var sr = 0
            var ch = 2
            var durMs = 0L
            for (line in out.lineSequence()) {
                when {
                    line.startsWith("sample_rate=") -> sr = line.substringAfter("=").trim().toIntOrNull() ?: 0
                    line.startsWith("channels=") -> ch = line.substringAfter("=").trim().toIntOrNull()?.coerceIn(1, 16) ?: 2
                    line.startsWith("duration=") -> {
                        val sec = line.substringAfter("=").trim().toDoubleOrNull()
                        if (sec != null && sec > 0.0) durMs = (sec * 1000.0).toLong()
                    }
                }
            }
            Triple(durMs, sr, ch)
        } catch (_: Exception) {
            null
        }
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
            val updatedEntry = parseAudioFileToCacheEntry(song.file, song.core.replayGain.computedLoudnessDb)
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
            val stepMs = if (isLowResourceActive) 80L else 40L
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
     * Streams [initialSong] with zero-gap splicing across consecutive tracks on the SAME [SourceDataLine]
     * and performs real-time ITU-R BS.1770 K-weighted LUFS normalization on untagged tracks.
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

                var rgMeta = mergeWithComputedOverride(track)
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

                    if (activeLine == null || !activeLine.isOpen || abs(activeRate - source.sampleRate) > 1f) {
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
                    val needsLiveLufs = !rgMeta.hasEmbeddedTags &&
                        rgMeta.computedLoudnessDb == null &&
                        replayGainConfig.enableFallbackAnalysis
                    val lufsAnalyzer = if (needsLiveLufs) {
                        StreamingLufsAnalyzer(sampleRateHz = source.sampleRate.toInt().coerceIn(8000, 192000), channels = 2)
                    } else null
                    var earlyLocked = false
                    var refinedLocked = false

                    var stereoBytesWritten = ((startMs / 1000.0) * source.sampleRate * 4.0).toLong()
                    val bytesPerMs = ((source.sampleRate * 4f) / 1000f).coerceAtLeast(1f)
                    var lastProgressUpdateMs = 0L
                    val progressIntervalMs = if (isLowResourceActive) 500L else 130L

                    while (currentCoroutineContext().isActive) {
                        if (!_isPlaying.value) {
                            delay(80)
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

                        if (lufsAnalyzer != null && !refinedLocked && stereoValidBytes >= 4) {
                            lufsAnalyzer.processPcm16le(stereoBuf, 0, stereoValidBytes)
                            val sr = source.sampleRate.toInt().coerceAtLeast(8000)
                            val framesSeen = lufsAnalyzer.processedFrames
                            if (!earlyLocked && framesSeen >= sr * 2L) {
                                earlyLocked = true
                                lufsAnalyzer.currentReplayGainDbAndPeak()?.let { (gainDb, peak) ->
                                    rgMeta = rgMeta.copy(computedLoudnessDb = gainDb, trackPeak = peak)
                                    rgLinearScalar = computeTrackPcmGainScalar(rgMeta, isSecondary)
                                    persistComputedLoudness(null, track.file.absolutePath, gainDb, peak, saveNow = false)
                                }
                            } else if (earlyLocked && framesSeen >= sr * 10L) {
                                refinedLocked = true
                                lufsAnalyzer.currentReplayGainDbAndPeak()?.let { (gainDb, peak) ->
                                    rgMeta = rgMeta.copy(computedLoudnessDb = gainDb, trackPeak = peak)
                                    rgLinearScalar = computeTrackPcmGainScalar(rgMeta, isSecondary)
                                    persistComputedLoudness(null, track.file.absolutePath, gainDb, peak, saveNow = true)
                                }
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

                    // If short track ended before 10s lock, still save computed LUFS
                    if (lufsAnalyzer != null && !refinedLocked && lufsAnalyzer.processedFrames >= 64L) {
                        lufsAnalyzer.currentReplayGainDbAndPeak()?.let { (gainDb, peak) ->
                            persistComputedLoudness(null, track.file.absolutePath, gainDb, peak, saveNow = true)
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
        val res = ReplayGainTagService.computePlaybackAdjustment(
            metadata = meta,
            config = replayGainConfig,
            isAlbumSequentialContext = true
        )
        if (!isSecondary) {
            _activeReplayGain.value = res
        }
        return ReplayGainTagService.computeTotalLinearGain(
            metadata = meta,
            config = replayGainConfig,
            isAlbumSequentialContext = true
        )
    }

    private fun openDecodedPcmSource(
        song: DesktopSong,
        startOffsetMs: Long,
        isSecondary: Boolean
    ): PcmFrameSource? {
        val ext = song.file.extension.lowercase(Locale.ROOT)
        val ffmpeg = ffmpegBinaryPath

        // 1. Pure-Kotlin zero-dependency NativePcmContainerDecoder for AIFF/AIFC, CAF, DSF, DFF, AU/SND
        // Works on all Windows/Linux setups even without FFmpeg installed, with instant random seek!
        if (NativePcmContainerDecoder.isNativelyDecodableExtension(ext)) {
            openNativeContainerPcmSource(song.file, startOffsetMs)?.let { return it }
        }

        // 2. SSE2-safe FFmpeg pipe for ALAC, APE, WV, M4A, MP4, MKV/MKA, WebM, Opus 5.1/7.1,
        // EAC3, AC3, AC4, DTS, WMA, FLAC, OGG, AAC, MP3, etc.
        val isBasicWav = ext == "wav" || ext == "wave"
        if (ffmpeg != null && (!isBasicWav || startOffsetMs > 0L || song.core.channels > 2)) {
            openFfmpegPcmSource(ffmpeg, song, ext, startOffsetMs, isSecondary)?.let { return it }
        }

        // 3. JavaSound fallback (WAV, standard PCM, or SPI plugins)
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
            if (ffmpeg != null) openFfmpegPcmSource(ffmpeg, song, ext, startOffsetMs, isSecondary) else null
        }
    }

    private fun openNativeContainerPcmSource(
        file: File,
        startOffsetMs: Long
    ): PcmFrameSource? {
        return try {
            val raf = RandomAccessFile(file, "r")
            val reader = rafToSeekableReader(raf)
            val decoded = NativePcmContainerDecoder.open(reader) ?: run {
                raf.close()
                return null
            }
            if (startOffsetMs > 0L) {
                decoded.seekToMs(startOffsetMs)
            }
            object : PcmFrameSource {
                override val sampleRate: Float = decoded.outputSampleRate.toFloat()
                override val channels: Int = 2
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = decoded.readStereoS16le(buffer, offset, length)
                    return if (n <= 0) -1 else n
                }
                override fun close() {
                    runCatching { decoded.close() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rafToSeekableReader(raf: RandomAccessFile): SeekableByteReader = object : SeekableByteReader {
        override val length: Long get() = raf.length()
        override var position: Long
            get() = raf.filePointer
            set(value) { raf.seek(value.coerceAtLeast(0L)) }
        override fun read(buffer: ByteArray, offset: Int, count: Int): Int = raf.read(buffer, offset, count)
        override fun close() = raf.close()
    }

    private fun openFfmpegPcmSource(
        ffmpegBin: String,
        song: DesktopSong,
        ext: String,
        startOffsetMs: Long,
        isSecondary: Boolean
    ): PcmFrameSource? {
        return try {
            val rawSr = song.core.sampleRate ?: 48000
            // Exact 88.2 kHz integer decimation for 2.8224 MHz DSD (DSF/DFF) or ultra-high rates
            val outSampleRate = when {
                ext == "dsf" || ext == "dff" || rawSr > 192_000 -> 88_200
                isLowResourceActive -> rawSr.coerceIn(22_050, 48_000)
                else -> rawSr.coerceIn(22_050, 96_000)
            }
            val cmd = ArrayList<String>(18)
            cmd.add(ffmpegBin)
            cmd.add("-nostdin")
            cmd.add("-threads")
            cmd.add(if (isLowResourceActive) "1" else "2")
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
            val stdout = BufferedInputStream(proc.inputStream, 16384)

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
        line.open(format, 32768)
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
        backgroundLufsJob?.cancel()
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
            val list: List<CachedTrackEntry>? = cacheFile.bufferedReader(Charsets.UTF_8, 16384).use {
                gson.fromJson(it, listType)
            }
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
            val tmpFile = File(configDir, "desktop_library_cache.json.tmp")
            tmpFile.bufferedWriter(Charsets.UTF_8, 16384).use { writer ->
                gson.toJson(map.values, writer)
            }
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun resolveConfigDirectory(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val (flowDir, legacyLuneDir) = if (os.contains("win")) {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) {
                    File(appData, "Flow") to File(appData, "Lune")
                } else {
                    File(System.getProperty("user.home"), ".flow") to File(System.getProperty("user.home"), ".lune")
                }
            } else {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) {
                    File(xdg, "flow") to File(xdg, "lune")
                } else {
                    val cfg = File(System.getProperty("user.home"), ".config")
                    File(cfg, "flow") to File(cfg, "lune")
                }
            }
            // Seamless one-time migration from legacy Lune config directory if present
            if (!flowDir.exists() && legacyLuneDir.exists()) {
                runCatching {
                    flowDir.mkdirs()
                    legacyLuneDir.listFiles()?.forEach { f ->
                        if (f.isFile) f.copyTo(File(flowDir, f.name), overwrite = false)
                    }
                }
            }
            return flowDir
        }

        private fun detectModestDesktopHardware(): Boolean {
            val cores = Runtime.getRuntime().availableProcessors()
            val maxMemMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            return cores <= 2 || maxMemMb <= 256L
        }

        private fun detectFfmpegBinary(): String? =
            probeExecutable(listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg.exe"))

        private fun detectFfprobeBinary(): String? =
            probeExecutable(listOf("ffprobe", "/usr/bin/ffprobe", "/usr/local/bin/ffprobe", "ffprobe.exe"))

        private fun probeExecutable(candidates: List<String>): String? {
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
