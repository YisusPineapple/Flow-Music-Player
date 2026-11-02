package com.demonlab.lune.ai

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.demonlab.lune.R
import com.demonlab.lune.ai.model.AiMix
import com.demonlab.lune.ai.model.DayType
import com.demonlab.lune.ai.model.MixCategory
import com.demonlab.lune.ai.model.TimeOfDay
import com.demonlab.lune.ai.storage.AiTelemetryStorage
import com.demonlab.lune.core.ai.HarmonicAutomixScorer
import com.demonlab.lune.core.model.CoreAudioProfile
import com.demonlab.lune.tools.Song
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

class LuneAiEngine private constructor(private val context: Context) {
    private val storage = AiTelemetryStorage(context)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("LuneAiEngine", "Unhandled exception in AI engine coroutine", throwable)
    }
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    private val _aiMixes = MutableStateFlow<List<AiMix>>(emptyList())
    val aiMixes: StateFlow<List<AiMix>> = _aiMixes.asStateFlow()

    private val profileCache = ConcurrentHashMap<Long, AudioProfile>(512)
    private var refreshJob: Job? = null
    @Volatile private var lastRefreshTimestampMs: Long = 0L
    @Volatile private var lastRefreshSongCount: Int = 0

    private var lastStartedSongId: Long? = null
    private var lastStartedTimeMs: Long = 0L
    private var previousPlayedSongId: Long? = null

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 5_000L
        private const val MAX_SHUFFLE_CANDIDATE_WINDOW = 96

        @Volatile
        private var INSTANCE: LuneAiEngine? = null

        fun getInstance(context: Context): LuneAiEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LuneAiEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun invalidateProfileCache(songId: Long? = null) {
        if (songId != null) {
            profileCache.remove(songId)
        } else {
            profileCache.clear()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. TELEMETRY & HABIT LEARNING
    // ─────────────────────────────────────────────────────────────

    fun onSongStarted(song: Song) {
        try {
            val now = System.currentTimeMillis()
            val timeOfDay = TimeOfDay.current()
            val isWeekend = DayType.current() == DayType.WEEKEND

            previousPlayedSongId?.let { prevId ->
                if (prevId != song.id && (now - lastStartedTimeMs > 40_000L)) {
                    storage.recordInteraction(prevId) { prevInteraction ->
                        prevInteraction.recordTransitionTo(song.id)
                    }
                }
            }

            val isReplay = lastStartedSongId == song.id && (now - lastStartedTimeMs < 35_000L)
            previousPlayedSongId = lastStartedSongId
            lastStartedSongId = song.id
            lastStartedTimeMs = now

            storage.recordInteraction(song.id) { interaction ->
                interaction.playCount++
                interaction.lastPlayedTimestamp = now
                if (isReplay) interaction.repeatCount++
                if (isWeekend) interaction.weekendPlays++ else interaction.weekdayPlays++
                when (timeOfDay) {
                    TimeOfDay.MORNING -> interaction.morningPlays++
                    TimeOfDay.AFTERNOON -> interaction.afternoonPlays++
                    TimeOfDay.EVENING -> interaction.eveningPlays++
                    TimeOfDay.NIGHT -> interaction.nightPlays++
                }
                if (interaction.consecutiveSkips > 0) {
                    interaction.consecutiveSkips = 0
                }
            }
        } catch (e: Exception) {
            Log.e("LuneAiEngine", "Error onSongStarted: ${e.message}", e)
        }
    }

    fun onSongCompleted(song: Song) {
        try {
            storage.recordInteraction(song.id) { interaction ->
                interaction.fullCompletions++
                interaction.consecutiveSkips = 0
            }
        } catch (e: Exception) {
            Log.e("LuneAiEngine", "Error onSongCompleted: ${e.message}", e)
        }
    }

    fun onSongSkipped(song: Song, playedSeconds: Long, totalDurationSeconds: Long) {
        try {
            val isFastSkip = playedSeconds < 20 ||
                (totalDurationSeconds > 0 && (playedSeconds.toFloat() / totalDurationSeconds.toFloat()) < 0.15f)
            if (isFastSkip) {
                storage.recordInteraction(song.id) { interaction ->
                    interaction.fastSkips++
                    interaction.consecutiveSkips++
                }
            }
        } catch (e: Exception) {
            Log.e("LuneAiEngine", "Error onSongSkipped: ${e.message}", e)
        }
    }

    fun onSongFavoriteToggled(song: Song, isFavorite: Boolean) {
        try {
            storage.recordInteraction(song.id) { interaction ->
                interaction.isFavorite = isFavorite
            }
        } catch (e: Exception) {
            Log.e("LuneAiEngine", "Error onSongFavoriteToggled: ${e.message}", e)
        }
    }

    fun onSongAddedToPlaylist(songId: Long) {
        try {
            storage.recordInteraction(songId) { interaction ->
                interaction.playlistAddCount++
            }
        } catch (e: Exception) {
            Log.e("LuneAiEngine", "Error onSongAddedToPlaylist: ${e.message}", e)
        }
    }

    fun getSongAffinity(songId: Long): Float {
        return getSongAffinitySnapshot(
            songId = songId,
            now = System.currentTimeMillis(),
            currentTime = TimeOfDay.current(),
            isWeekend = DayType.current() == DayType.WEEKEND
        )
    }

    private fun getSongAffinitySnapshot(
        songId: Long,
        now: Long,
        currentTime: TimeOfDay,
        isWeekend: Boolean
    ): Float {
        return try {
            storage.getInteraction(songId).calculateDynamicScore(now, currentTime, isWeekend)
        } catch (_: Exception) {
            1.0f
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. MUSICAL HEURISTICS & ACOUSTIC PROFILING (CACHED)
    // ─────────────────────────────────────────────────────────────

    data class AudioProfile(
        val energyScore: Float,
        val acousticScore: Float,
        val valenceScore: Float,
        val isLiveOrAcoustic: Boolean,
        val isFocusFriendly: Boolean,
        val isSpokenWord: Boolean,
        val normalizedGenre: String
    )

    fun profileSong(song: Song): AudioProfile {
        profileCache[song.id]?.let { return it }
        val computed = computeProfileInternal(song)
        if (profileCache.size > 8192) {
            profileCache.clear()
        }
        profileCache[song.id] = computed
        return computed
    }

    fun toCoreAudioProfile(song: Song): CoreAudioProfile {
        val p = profileSong(song)
        return CoreAudioProfile(
            trackId = song.id,
            energy = p.energyScore,
            valence = p.valenceScore,
            danceability = (p.energyScore * 0.7f + (1f - p.acousticScore) * 0.3f).coerceIn(0f, 1f),
            acousticness = p.acousticScore,
            normalizedGenre = p.normalizedGenre,
            isSpokenWord = p.isSpokenWord
        )
    }

    private fun computeProfileInternal(song: Song): AudioProfile {
        val titleLower = song.title.lowercase(Locale.ROOT)
        val genreLower = (song.genre ?: "").trim().lowercase(Locale.ROOT)

        var energy = 0.50f
        var acoustic = 0.30f
        var valence = 0.52f
        var isLiveOrAcoustic = false

        val isSpoken = song.duration > 15 * 60_000L ||
            genreLower.contains("podcast") ||
            genreLower.contains("audiobook") ||
            genreLower.contains("spoken") ||
            titleLower.contains("podcast") ||
            titleLower.contains("audiolibro")

        if (titleLower.contains("acoustic") || titleLower.contains("acústic") ||
            titleLower.contains("unplugged") || titleLower.contains("piano version") ||
            genreLower.contains("acoustic") || genreLower.contains("folk")
        ) {
            acoustic += 0.50f
            energy -= 0.25f
            isLiveOrAcoustic = true
        }

        if (titleLower.contains("live") || titleLower.contains("en vivo") ||
            titleLower.contains("en directo") || titleLower.contains("session")
        ) {
            isLiveOrAcoustic = true
        }

        if (titleLower.contains("remix") || titleLower.contains("extended") ||
            titleLower.contains("club") || titleLower.contains("speed up") ||
            genreLower.contains("rock") || genreLower.contains("metal") ||
            genreLower.contains("dance") || genreLower.contains("edm") ||
            genreLower.contains("electronic") || genreLower.contains("trap") ||
            genreLower.contains("hardcore") || genreLower.contains("reggaeton")
        ) {
            energy += 0.35f
            acoustic -= 0.20f
            valence += 0.14f
        }

        if (titleLower.contains("lofi") || titleLower.contains("lo-fi") ||
            titleLower.contains("slowed") || titleLower.contains("ambient") ||
            titleLower.contains("instrumental") || genreLower.contains("ambient") ||
            genreLower.contains("classical") || genreLower.contains("jazz") ||
            genreLower.contains("chill")
        ) {
            energy -= 0.30f
            acoustic += 0.25f
            valence -= 0.10f
        }

        if (song.duration > 300_000L) {
            energy -= 0.08f
        } else if (song.duration in 120_000L..210_000L) {
            energy += 0.08f
        }

        energy = energy.coerceIn(0.05f, 0.98f)
        acoustic = acoustic.coerceIn(0.05f, 0.98f)
        valence = valence.coerceIn(0.08f, 0.95f)

        val isFocus = !isSpoken && (acoustic > 0.40f || energy in 0.20f..0.55f ||
            titleLower.contains("instrumental") || titleLower.contains("lo-fi"))

        return AudioProfile(
            energyScore = energy,
            acousticScore = acoustic,
            valenceScore = valence,
            isLiveOrAcoustic = isLiveOrAcoustic,
            isFocusFriendly = isFocus,
            isSpokenWord = isSpoken,
            normalizedGenre = genreLower
        )
    }

    fun computeSongSimilarity(a: Song, b: Song): Float {
        if (a.id == b.id) return 1.0f

        var similarity = 0.0f
        if (a.artist.equals(b.artist, ignoreCase = true)) {
            similarity += 0.42f
        }

        val profileA = profileSong(a)
        val profileB = profileSong(b)
        val genreA = profileA.normalizedGenre
        val genreB = profileB.normalizedGenre
        if (genreA.isNotEmpty() && genreB.isNotEmpty()) {
            if (genreA == genreB) {
                similarity += 0.35f
            } else if (isCompatibleGenre(genreA, genreB)) {
                similarity += 0.22f
            }
        }

        val energyDelta = abs(profileA.energyScore - profileB.energyScore)
        val acousticDelta = abs(profileA.acousticScore - profileB.acousticScore)
        val profileHarmony = 1.0f - ((energyDelta * 0.6f) + (acousticDelta * 0.4f))
        similarity += (profileHarmony * 0.23f).coerceIn(0f, 0.23f)

        val transitionWeight = storage.getInteraction(a.id).getTransitionProbabilityTo(b.id)
        if (transitionWeight > 0f) {
            similarity += (transitionWeight * 0.20f).coerceIn(0f, 0.20f)
        }

        return similarity.coerceIn(0f, 1f)
    }

    private fun isCompatibleGenre(g1: String, g2: String): Boolean {
        return (g1 in ROCK_FAMILY && g2 in ROCK_FAMILY) ||
            (g1 in POP_FAMILY && g2 in POP_FAMILY) ||
            (g1 in ELECTRONIC_FAMILY && g2 in ELECTRONIC_FAMILY) ||
            (g1 in URBAN_FAMILY && g2 in URBAN_FAMILY) ||
            (g1 in CHILL_FAMILY && g2 in CHILL_FAMILY)
    }

    // ─────────────────────────────────────────────────────────────
    // 3. SMART SHUFFLE & AUTOMIX (BOUNDED O(N) COMPLEXITY)
    // ─────────────────────────────────────────────────────────────

    fun generateSmartShuffle(
        songs: List<Song>,
        startingSong: Song? = null,
        affinityLookup: Map<Long, Float>? = null
    ): List<Song> {
        if (songs.size <= 2) return songs

        val now = System.currentTimeMillis()
        val currentTime = TimeOfDay.current()
        val isWeekend = DayType.current() == DayType.WEEKEND
        fun affinityOf(id: Long): Float =
            affinityLookup?.get(id) ?: getSongAffinitySnapshot(id, now, currentTime, isWeekend)

        val remaining = songs.sortedByDescending { affinityOf(it.id) }.toMutableList()
        val result = ArrayList<Song>(songs.size)

        val seedIdx = startingSong?.let { s -> remaining.indexOfFirst { it.id == s.id } } ?: -1
        val seed = if (seedIdx >= 0) remaining.removeAt(seedIdx) else remaining.removeAt(0)
        result.add(seed)

        var current = seed
        val random = Random.Default

        while (remaining.isNotEmpty()) {
            // Inspect bounded candidate window so 5,000+ track libraries stay O(N) instead of O(N^2)
            val windowSize = minOf(MAX_SHUFFLE_CANDIDATE_WINDOW, remaining.size)
            var bestIdx1 = 0
            var bestScore1 = -Float.MAX_VALUE
            var bestIdx2 = 0
            var bestScore2 = -Float.MAX_VALUE
            var bestIdx3 = 0
            var bestScore3 = -Float.MAX_VALUE

            for (i in 0 until windowSize) {
                val candidate = remaining[i]
                val similarity = computeSongSimilarity(current, candidate)
                val affinity = affinityOf(candidate.id)
                val sameArtistPenalty = if (candidate.artist.equals(current.artist, ignoreCase = true)) -0.35f else 0f
                val noise = random.nextFloat() * 0.08f
                val score = (similarity * 0.48f) + ((affinity / 45f).coerceIn(0f, 0.44f)) + sameArtistPenalty + noise

                if (score > bestScore1) {
                    bestScore3 = bestScore2; bestIdx3 = bestIdx2
                    bestScore2 = bestScore1; bestIdx2 = bestIdx1
                    bestScore1 = score; bestIdx1 = i
                } else if (score > bestScore2) {
                    bestScore3 = bestScore2; bestIdx3 = bestIdx2
                    bestScore2 = score; bestIdx2 = i
                } else if (score > bestScore3) {
                    bestScore3 = score; bestIdx3 = i
                }
            }

            val pickSlot = when {
                windowSize >= 3 -> when (random.nextInt(3)) {
                    0 -> bestIdx1
                    1 -> bestIdx2
                    else -> bestIdx3
                }
                windowSize == 2 -> if (random.nextBoolean()) bestIdx1 else bestIdx2
                else -> bestIdx1
            }

            val selected = remaining.removeAt(pickSlot)
            result.add(selected)
            current = selected
        }

        return result
    }

    /**
     * Selects the optimal next harmonic track for Smart Automix using shared KMP [HarmonicAutomixScorer].
     */
    fun selectNextAutomixSong(
        currentSong: Song,
        recentHistory: List<Song>,
        candidates: List<Song>
    ): Song? {
        if (candidates.isEmpty()) return null
        val eligible = candidates.filter { candidate ->
            candidate.id != currentSong.id && recentHistory.takeLast(8).none { it.id == candidate.id }
        }.ifEmpty {
            candidates.filter { it.id != currentSong.id }
        }
        if (eligible.isEmpty()) return null

        val now = System.currentTimeMillis()
        val currentTime = TimeOfDay.current()
        val isWeekend = DayType.current() == DayType.WEEKEND
        val currentCoreProfile = toCoreAudioProfile(currentSong)
        val currentArtistNorm = currentSong.artist.trim().lowercase(Locale.ROOT)
        val recentArtistsNorm = recentHistory.takeLast(3).map { it.artist.trim().lowercase(Locale.ROOT) }
        val currentInteraction = storage.getInteraction(currentSong.id)

        // Cap evaluation pool for instant real-time transition selection
        val pool = if (eligible.size > 140) eligible.shuffled().take(140) else eligible

        return pool.maxByOrNull { candidate ->
            val sim = computeSongSimilarity(currentSong, candidate)
            val markov = currentInteraction.getTransitionProbabilityTo(candidate.id)
            val affinityNorm = (getSongAffinitySnapshot(candidate.id, now, currentTime, isWeekend) / 40f).coerceIn(0f, 1f)
            HarmonicAutomixScorer.scoreCandidate(
                currentArtistNormalized = currentArtistNorm,
                currentProfile = currentCoreProfile,
                candidateArtistNormalized = candidate.artist.trim().lowercase(Locale.ROOT),
                candidateProfile = toCoreAudioProfile(candidate),
                recentArtistsNormalized = recentArtistsNorm,
                baseAcousticSimilarity = sim,
                markovTransitionProb = markov,
                userAffinityNormalized = affinityNorm
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. DISCOVERY MODE GENERATION (BOUNDED & SAFE)
    // ─────────────────────────────────────────────────────────────

    fun generateDiscoveryQueue(
        allSongs: List<Song>,
        seedSong: Song? = null,
        affinityLookup: Map<Long, Float>? = null
    ): List<Song> {
        if (allSongs.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val currentTime = TimeOfDay.current()
        val isWeekend = DayType.current() == DayType.WEEKEND
        fun affinityOf(id: Long): Float =
            affinityLookup?.get(id) ?: getSongAffinitySnapshot(id, now, currentTime, isWeekend)

        val familiar = allSongs.filter { affinityOf(it.id) >= 12f }
        val unexplored = allSongs.filter {
            val interaction = storage.getInteraction(it.id)
            interaction.playCount <= 1 && !interaction.isFavorite
        }

        if (unexplored.isEmpty()) {
            return generateSmartShuffle(allSongs.take(60), seedSong, affinityLookup)
        }

        val seed = seedSong ?: familiar.maxByOrNull { affinityOf(it.id) } ?: allSongs.first()
        val result = ArrayList<Song>(48)
        result.add(seed)

        val anchorFavorites = familiar.sortedByDescending { affinityOf(it.id) }.take(24)
        val candidateUnexplored = if (unexplored.size > 90) unexplored.shuffled().take(90) else unexplored

        val rankedUnexplored = candidateUnexplored.map { candidate ->
            val maxSim = anchorFavorites.maxOfOrNull { computeSongSimilarity(it, candidate) } ?: 0.3f
            candidate to maxSim
        }.sortedByDescending { it.second }

        val familiarPool = (familiar - seed).sortedByDescending { affinityOf(it.id) }.take(36).toMutableList()
        val unexploredPool = rankedUnexplored.take(36).map { it.first }.toMutableList()

        var flip = 0
        while ((familiarPool.isNotEmpty() || unexploredPool.isNotEmpty()) && result.size < 48) {
            if (flip % 3 == 2 && unexploredPool.isNotEmpty()) {
                result.add(unexploredPool.removeAt(0))
            } else if (familiarPool.isNotEmpty()) {
                val last = result.last()
                val nextFam = familiarPool.maxByOrNull { computeSongSimilarity(last, it) } ?: familiarPool.first()
                familiarPool.remove(nextFam)
                result.add(nextFam)
            } else if (unexploredPool.isNotEmpty()) {
                result.add(unexploredPool.removeAt(0))
            }
            flip++
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // 5. SMART NEXT PREDICTION (FOR AUTO-PLAY & INFINITE RADIO)
    // ─────────────────────────────────────────────────────────────

    fun predictNextSong(currentSong: Song, history: List<Song>, pool: List<Song>): Song? {
        return selectNextAutomixSong(currentSong, history, pool)
    }

    // ─────────────────────────────────────────────────────────────
    // 6. DEEP AI MIX GENERATION (DEBOUNCED, NON-BLOCKING, INCREMENTAL)
    // ─────────────────────────────────────────────────────────────

    fun refreshMixes(allSongs: List<Song>, force: Boolean = false) {
        if (allSongs.isEmpty()) return

        val nowCheck = System.currentTimeMillis()
        if (!force &&
            _aiMixes.value.isNotEmpty() &&
            lastRefreshSongCount == allSongs.size &&
            (nowCheck - lastRefreshTimestampMs) < REFRESH_DEBOUNCE_MS
        ) {
            return
        }

        lastRefreshTimestampMs = nowCheck
        lastRefreshSongCount = allSongs.size
        refreshJob?.cancel()

        val songsSnapshot = allSongs.toList()
        refreshJob = engineScope.launch {
            try {
                val generated = ArrayList<AiMix>(8)
                val now = System.currentTimeMillis()
                val currentTime = TimeOfDay.current()
                val isWeekend = DayType.current() == DayType.WEEKEND

                // Pre-compute affinities once in O(N) with zero Calendar allocations per comparison
                val affinityMap = HashMap<Long, Float>(songsSnapshot.size)
                for (song in songsSnapshot) {
                    affinityMap[song.id] = getSongAffinitySnapshot(song.id, now, currentTime, isWeekend)
                }

                suspend fun publishIncremental(mix: AiMix?) {
                    if (mix != null && mix.songs.size >= 4) {
                        generated.add(mix)
                        _aiMixes.value = generated.toList()
                        yield()
                    }
                }

                // 1. Daily AI Flow
                runCatching {
                    val dailySongs = songsSnapshot
                        .sortedByDescending { affinityMap[it.id] ?: 1f }
                        .take(32)
                    if (dailySongs.size >= 4) {
                        AiMix(
                            id = "ai_daily_flow",
                            title = context.getString(R.string.ai_mix_daily_title),
                            subtitle = context.getString(R.string.ai_mix_daily_sub),
                            category = MixCategory.DAILY_FLOW,
                            songs = generateSmartShuffle(dailySongs, affinityLookup = affinityMap),
                            gradientColors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899)),
                            iconName = "auto_awesome",
                            description = "Generado combinando afinidad en tiempo real, hábitos horarios y transiciones armónicas."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 2. Deep Discovery Mix
                runCatching {
                    val discoverySongs = generateDiscoveryQueue(songsSnapshot, affinityLookup = affinityMap).take(28)
                    if (discoverySongs.size >= 4) {
                        AiMix(
                            id = "ai_discovery_flow",
                            title = context.getString(R.string.ai_mix_discovery_title),
                            subtitle = context.getString(R.string.ai_mix_discovery_sub),
                            category = MixCategory.DISCOVERY,
                            songs = discoverySongs,
                            gradientColors = listOf(Color(0xFF0D9488), Color(0xFF059669), Color(0xFF10B981)),
                            iconName = "explore",
                            description = "Explora canciones de tu biblioteca con alta compatibilidad matemática con tus gustos."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 3. Energy Boost
                runCatching {
                    val filteredEnergy = songsSnapshot.filter { song ->
                        val profile = profileSong(song)
                        profile.energyScore > 0.62f || (song.duration < 230_000L && (affinityMap[song.id] ?: 1f) > 8f)
                    }.sortedByDescending { affinityMap[it.id] ?: 1f }.take(25)

                    val energySongs = if (filteredEnergy.size < 15 && songsSnapshot.size >= 4) {
                        val existingIds = filteredEnergy.map { it.id }.toHashSet()
                        val additional = songsSnapshot.asSequence()
                            .filter { it.id !in existingIds }
                            .sortedByDescending { profileSong(it).energyScore }
                            .take(25 - filteredEnergy.size)
                            .toList()
                        filteredEnergy + additional
                    } else {
                        filteredEnergy
                    }

                    if (energySongs.size >= 4) {
                        AiMix(
                            id = "ai_energy_boost",
                            title = context.getString(R.string.ai_mix_energy_title),
                            subtitle = context.getString(R.string.ai_mix_energy_sub),
                            category = MixCategory.ENERGY,
                            songs = generateSmartShuffle(energySongs, affinityLookup = affinityMap),
                            gradientColors = listOf(Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFEC4899)),
                            iconName = "bolt",
                            description = "Selección de canciones con pulsos vivos y tempos energéticos según tu biblioteca."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 4. Chill & Focus
                runCatching {
                    val filteredChill = songsSnapshot.filter { song ->
                        val profile = profileSong(song)
                        profile.energyScore < 0.45f || profile.isFocusFriendly
                    }.sortedByDescending { affinityMap[it.id] ?: 1f }.take(25)

                    val chillSongs = if (filteredChill.size < 15 && songsSnapshot.size >= 4) {
                        val existingIds = filteredChill.map { it.id }.toHashSet()
                        val additional = songsSnapshot.asSequence()
                            .filter { it.id !in existingIds }
                            .sortedBy { profileSong(it).energyScore }
                            .take(25 - filteredChill.size)
                            .toList()
                        filteredChill + additional
                    } else {
                        filteredChill
                    }

                    if (chillSongs.size >= 4) {
                        AiMix(
                            id = "ai_chill_flow",
                            title = context.getString(R.string.ai_mix_chill_title),
                            subtitle = context.getString(R.string.ai_mix_chill_sub),
                            category = MixCategory.CHILL,
                            songs = generateSmartShuffle(chillSongs, affinityLookup = affinityMap),
                            gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF6366F1)),
                            iconName = "spa",
                            description = "Sonidos acústicos y texturas pausadas para momentos de tranquilidad."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                delay(15L)

                // 5. Temporal Context & Weekend
                runCatching {
                    val timeName = when {
                        isWeekend -> context.getString(R.string.ai_mix_weekend_title)
                        currentTime == TimeOfDay.MORNING -> context.getString(R.string.ai_mix_morning_title)
                        currentTime == TimeOfDay.AFTERNOON -> context.getString(R.string.ai_mix_afternoon_title)
                        currentTime == TimeOfDay.EVENING -> context.getString(R.string.ai_mix_evening_title)
                        else -> context.getString(R.string.ai_mix_night_title)
                    }
                    val timeSub = when {
                        isWeekend -> context.getString(R.string.ai_mix_weekend_sub)
                        currentTime == TimeOfDay.MORNING -> context.getString(R.string.ai_mix_morning_sub)
                        currentTime == TimeOfDay.AFTERNOON -> context.getString(R.string.ai_mix_afternoon_sub)
                        currentTime == TimeOfDay.EVENING -> context.getString(R.string.ai_mix_evening_sub)
                        else -> context.getString(R.string.ai_mix_night_sub)
                    }
                    val timeGradients = when (currentTime) {
                        TimeOfDay.MORNING -> listOf(Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF06B6D4))
                        TimeOfDay.AFTERNOON -> listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFF06B6D4))
                        TimeOfDay.EVENING -> listOf(Color(0xFFF97316), Color(0xFF8B5CF6), Color(0xFF4F46E5))
                        TimeOfDay.NIGHT -> listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4C1D95))
                    }

                    val timeSongs = songsSnapshot
                        .sortedByDescending { affinityMap[it.id] ?: 1f }
                        .take(25)

                    if (timeSongs.size >= 4) {
                        AiMix(
                            id = "ai_time_mix",
                            title = timeName,
                            subtitle = timeSub,
                            category = MixCategory.TIME_OF_DAY,
                            songs = generateSmartShuffle(timeSongs, affinityLookup = affinityMap),
                            gradientColors = timeGradients,
                            iconName = if (currentTime == TimeOfDay.NIGHT) "bedtime" else "wb_sunny",
                            description = "Adaptado al contexto horario actual con base en tus hábitos pasados."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 6. Forgotten Gems
                runCatching {
                    val twoWeeksAgo = now - (14L * 24L * 60L * 60L * 1000L)
                    val forgottenSongs = songsSnapshot.filter { song ->
                        val interaction = storage.getInteraction(song.id)
                        (interaction.isFavorite || interaction.playCount >= 3) &&
                            (interaction.lastPlayedTimestamp in 1..twoWeeksAgo || interaction.lastPlayedTimestamp == 0L)
                    }.shuffled().take(22)

                    if (forgottenSongs.size >= 4) {
                        AiMix(
                            id = "ai_forgotten_gems",
                            title = context.getString(R.string.ai_mix_forgotten_title),
                            subtitle = context.getString(R.string.ai_mix_forgotten_sub),
                            category = MixCategory.FORGOTTEN_GEMS,
                            songs = forgottenSongs,
                            gradientColors = listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857)),
                            iconName = "history",
                            description = "Redescubre temas con alta puntuación en tu historial que han quedado atrás."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 7. Acoustic / Live Sessions
                runCatching {
                    val acousticLiveSongs = songsSnapshot
                        .asSequence()
                        .filter { profileSong(it).isLiveOrAcoustic }
                        .take(28)
                        .toList()
                    if (acousticLiveSongs.size >= 4) {
                        AiMix(
                            id = "ai_acoustic_live",
                            title = context.getString(R.string.ai_mix_acoustic_title),
                            subtitle = context.getString(R.string.ai_mix_acoustic_sub),
                            category = MixCategory.ACOUSTIC_LIVE,
                            songs = generateSmartShuffle(acousticLiveSongs, affinityLookup = affinityMap),
                            gradientColors = listOf(Color(0xFFD97706), Color(0xFFB45309), Color(0xFF78350F)),
                            iconName = "mic_external_on",
                            description = "Detección inteligente de pistas acústicas y grabaciones en vivo."
                        )
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                // 8. Top Artist Spotlight Radio
                runCatching {
                    val topArtist = songsSnapshot
                        .groupBy { it.artist }
                        .filter { it.key.isNotBlank() && it.value.size >= 2 }
                        .maxByOrNull { entry ->
                            entry.value.sumOf { (affinityMap[it.id] ?: 1f).toDouble() }
                        }

                    if (topArtist != null) {
                        val artistSongs = topArtist.value.take(16)
                        val anchor = artistSongs.first()
                        val kindredSongs = songsSnapshot.asSequence()
                            .filter { it.artist != topArtist.key && computeSongSimilarity(anchor, it) > 0.40f }
                            .take(15)
                            .toList()
                        val spotlightPool = (artistSongs + kindredSongs).distinctBy { it.id }

                        if (spotlightPool.size >= 4) {
                            AiMix(
                                id = "ai_artist_${topArtist.key.hashCode()}",
                                title = context.getString(R.string.ai_mix_artist_radio_title, topArtist.key),
                                subtitle = context.getString(R.string.ai_mix_artist_radio_sub, topArtist.key),
                                category = MixCategory.ARTIST_SPOTLIGHT,
                                songs = generateSmartShuffle(spotlightPool, affinityLookup = affinityMap),
                                gradientColors = listOf(Color(0xFFE11D48), Color(0xFFBE123C), Color(0xFF881337)),
                                iconName = "person",
                                description = "Sesión continua generada en torno a uno de tus artistas con mayor afinidad."
                            )
                        } else null
                    } else null
                }.getOrNull()?.let { publishIncremental(it) }

                _aiMixes.value = generated.toList()
            } catch (e: Exception) {
                Log.e("LuneAiEngine", "Error refreshing AI mixes: ${e.message}", e)
            }
        }
    }

    private val ROCK_FAMILY = setOf("rock", "alternative", "indie", "metal", "punk", "grunge", "hard rock")
    private val POP_FAMILY = setOf("pop", "dance", "electropop", "synthpop", "indie pop", "disco")
    private val ELECTRONIC_FAMILY = setOf("electronic", "edm", "house", "techno", "synthwave", "ambient", "trance")
    private val URBAN_FAMILY = setOf("hip hop", "rap", "trap", "r&b", "soul", "reggaeton", "urban")
    private val CHILL_FAMILY = setOf("acoustic", "folk", "classical", "instrumental", "ambient", "lo-fi", "chill", "jazz")
}
