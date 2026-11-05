package com.demonlab.lune.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.demonlab.lune.core.metadata.MultiTagParser
import com.demonlab.lune.core.model.PerformanceProfile
import com.demonlab.lune.core.model.ReplayGainMode
import com.demonlab.lune.core.model.UiDensityMode
import com.demonlab.lune.core.search.PhoneticSearchEngine
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import kotlin.math.roundToInt

private enum class DesktopTab {
    TRACKS,
    ALBUMS,
    ARTISTS,
    GENRES,
    SETTINGS
}

fun main() = application {
    val windowState = rememberWindowState(width = 1120.dp, height = 740.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Flow Music Player"
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            FlowDesktopApp()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowDesktopApp() {
    val scope = rememberCoroutineScope()
    val engine = remember { DesktopAudioEngine(scope) }

    var settings by remember { mutableStateOf(engine.loadSettings()) }
    val currentSong by engine.currentSong.collectAsState()
    val activeRg by engine.activeReplayGain.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val progress by engine.progress.collectAsState()
    val isScanning by engine.isScanning.collectAsState()
    val scanStatus by engine.scanStatus.collectAsState()

    var songs by remember { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var activeTab by remember { mutableStateOf(DesktopTab.TRACKS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterArtist by remember { mutableStateOf<String?>(null) }
    var selectedFilterAlbum by remember { mutableStateOf<String?>(null) }
    var selectedFilterGenre by remember { mutableStateOf<String?>(null) }
    var editingSong by remember { mutableStateOf<DesktopSong?>(null) }
    var newWhitelistInput by remember { mutableStateOf("") }

    val outputDevices = remember { engine.listAudioOutputDevices() }

    fun updateSettings(newSettings: DesktopSettings, reparseTags: Boolean = false) {
        settings = newSettings
        engine.saveSettings(newSettings)
        if (reparseTags && songs.isNotEmpty()) {
            songs = engine.reparseSongsWithMultiTagConfig(songs, newSettings.multiTagConfig)
        }
    }

    DisposableEffect(Unit) {
        val initialFolder = File(settings.lastMusicFolder)
        if (initialFolder.exists()) {
            scope.launch {
                songs = engine.scanFolder(initialFolder, settings.multiTagConfig, forceRescan = false)
            }
        }
        onDispose { engine.stopInternal() }
    }

    val visibleSongs = remember(
        songs,
        searchQuery,
        settings.blacklistPaths,
        selectedFilterArtist,
        selectedFilterAlbum,
        selectedFilterGenre
    ) {
        songs.asSequence()
            .filter { it.file.absolutePath !in settings.blacklistPaths }
            .filter { s ->
                selectedFilterArtist == null || s.core.artists.any { it.equals(selectedFilterArtist, ignoreCase = true) }
            }
            .filter { s ->
                selectedFilterAlbum == null || s.core.album.equals(selectedFilterAlbum, ignoreCase = true)
            }
            .filter { s ->
                selectedFilterGenre == null || s.core.genres.any { it.equals(selectedFilterGenre, ignoreCase = true) }
            }
            .filter { song ->
                searchQuery.isBlank() ||
                    PhoneticSearchEngine.matches(song.core.displayTitle, searchQuery) ||
                    PhoneticSearchEngine.matches(song.core.artist, searchQuery) ||
                    PhoneticSearchEngine.matches(song.core.album, searchQuery) ||
                    song.core.artists.any { PhoneticSearchEngine.matches(it, searchQuery) } ||
                    song.core.genres.any { PhoneticSearchEngine.matches(it, searchQuery) }
            }
            .toList()
    }

    LaunchedEffect(visibleSongs) {
        engine.nextTrackProvider = { finishedSong, _ ->
            val idx = visibleSongs.indexOfFirst { it.core.id == finishedSong.core.id }
            if (idx >= 0 && visibleSongs.size > 1) {
                visibleSongs[(idx + 1) % visibleSongs.size]
            } else {
                null
            }
        }
    }

    val artistCounts = remember(songs, settings.blacklistPaths) {
        val map = LinkedHashMap<String, Pair<String, Int>>()
        for (s in songs) {
            if (s.file.absolutePath in settings.blacklistPaths) continue
            for (artistName in s.core.artists) {
                val key = artistName.lowercase(Locale.ROOT)
                val prev = map[key]
                map[key] = (prev?.first ?: artistName) to ((prev?.second ?: 0) + 1)
            }
        }
        map.values.sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    val genreCounts = remember(songs, settings.blacklistPaths) {
        val map = LinkedHashMap<String, Pair<String, Int>>()
        for (s in songs) {
            if (s.file.absolutePath in settings.blacklistPaths) continue
            for (g in s.core.genres) {
                val key = g.lowercase(Locale.ROOT)
                val prev = map[key]
                map[key] = (prev?.first ?: g) to ((prev?.second ?: 0) + 1)
            }
        }
        map.values.sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    val albumGroups = remember(songs, settings.blacklistPaths) {
        songs.asSequence()
            .filter { it.file.absolutePath !in settings.blacklistPaths }
            .groupBy { it.core.album.ifBlank { "Álbum Desconocido" } }
            .entries
            .map { Triple(it.key, it.value.first().core.artists.firstOrNull().orEmpty(), it.value.size) }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, settings.uiFontScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * settings.uiFontScale.coerceIn(0.85f, 1.25f)
        )
    }

    val itemVerticalPad: Dp = when (settings.uiDensityMode) {
        UiDensityMode.COMPACT -> 6.dp
        UiDensityMode.STANDARD -> 10.dp
        UiDensityMode.COMFORTABLE -> 15.dp
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompactWidth = maxWidth < 760.dp
                val isShortHeight = maxHeight < 560.dp
                val autoCols = when {
                    maxWidth < 620.dp -> 2
                    maxWidth < 920.dp -> 3
                    maxWidth < 1260.dp -> 4
                    else -> 5
                }
                val effectiveGridColumns = if (settings.gridColumns in 2..6) settings.gridColumns else autoCols

                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                        header = {
                            FilledTonalIconButton(
                                onClick = {
                                    val chooser = JFileChooser(settings.lastMusicFolder).apply {
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val selected = chooser.selectedFile
                                        updateSettings(settings.copy(lastMusicFolder = selected.absolutePath))
                                        scope.launch {
                                            songs = engine.scanFolder(selected, settings.multiTagConfig, forceRescan = false)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Seleccionar carpeta de música")
                            }
                        }
                    ) {
                        NavigationRailItem(
                            selected = activeTab == DesktopTab.TRACKS,
                            onClick = { activeTab = DesktopTab.TRACKS },
                            icon = { Icon(Icons.Default.MusicNote, contentDescription = "Pistas") },
                            label = if (isCompactWidth) null else ({ Text("Pistas") })
                        )
                        NavigationRailItem(
                            selected = activeTab == DesktopTab.ALBUMS,
                            onClick = { activeTab = DesktopTab.ALBUMS },
                            icon = { Icon(Icons.Default.Album, contentDescription = "Álbumes") },
                            label = if (isCompactWidth) null else ({ Text("Álbumes") })
                        )
                        NavigationRailItem(
                            selected = activeTab == DesktopTab.ARTISTS,
                            onClick = { activeTab = DesktopTab.ARTISTS },
                            icon = { Icon(Icons.Default.People, contentDescription = "Artistas") },
                            label = if (isCompactWidth) null else ({ Text("Artistas") })
                        )
                        NavigationRailItem(
                            selected = activeTab == DesktopTab.GENRES,
                            onClick = { activeTab = DesktopTab.GENRES },
                            icon = { Icon(Icons.Default.Category, contentDescription = "Géneros") },
                            label = if (isCompactWidth) null else ({ Text("Géneros") })
                        )
                        NavigationRailItem(
                            selected = activeTab == DesktopTab.SETTINGS,
                            onClick = { activeTab = DesktopTab.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                            label = if (isCompactWidth) null else ({ Text("Ajustes") })
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = if (isCompactWidth) 12.dp else 18.dp, vertical = if (isShortHeight) 8.dp else 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Flow",
                                style = if (isCompactWidth) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Buscar pistas, artistas, géneros o álbumes...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                                        }
                                    }
                                } else null,
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalIconButton(
                                onClick = {
                                    val dir = File(settings.lastMusicFolder)
                                    if (dir.exists()) {
                                        scope.launch {
                                            songs = engine.scanFolder(dir, settings.multiTagConfig, forceRescan = true)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reescanear carpeta")
                            }
                        }

                        if (isScanning || scanStatus.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = scanStatus.ifBlank { "Escaneando biblioteca con caché incremental..." },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (selectedFilterArtist != null || selectedFilterAlbum != null || selectedFilterGenre != null) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                selectedFilterArtist?.let { art ->
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedFilterArtist = null },
                                        label = { Text("Artista: $art") },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                                selectedFilterAlbum?.let { alb ->
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedFilterAlbum = null },
                                        label = { Text("Álbum: $alb") },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                                selectedFilterGenre?.let { gen ->
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedFilterGenre = null },
                                        label = { Text("Género: $gen") },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Crossfade(
                                targetState = activeTab,
                                animationSpec = tween(durationMillis = if (engine.isLowResourceActive) 0 else 140),
                                label = "flow_desktop_tab_fade"
                            ) { tab ->
                            when (tab) {
                                DesktopTab.TRACKS -> {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        items(visibleSongs, key = { it.core.id }) { item ->
                                            val active = currentSong?.core?.id == item.core.id
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { engine.play(song = item) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = itemVerticalPad),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (active && isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Text(
                                                                text = item.core.displayTitle,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f, fill = false)
                                                            )
                                                            if (item.core.formatCode.isNotEmpty()) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = MaterialTheme.colorScheme.secondaryContainer
                                                                ) {
                                                                    Text(
                                                                        text = item.core.formatCode,
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        FlowRow(
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalArrangement = Arrangement.Center
                                                        ) {
                                                            item.core.artists.forEachIndexed { idx, collaborator ->
                                                                Text(
                                                                    text = if (idx < item.core.artists.lastIndex) "$collaborator •" else collaborator,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.clickable {
                                                                        selectedFilterArtist = collaborator
                                                                    }
                                                                )
                                                            }
                                                            if (item.core.album.isNotBlank()) {
                                                                Text(
                                                                    text = "— ${item.core.album}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                    IconButton(onClick = { editingSong = item }) {
                                                        Icon(Icons.Default.Edit, contentDescription = "Editar metadatos")
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            updateSettings(settings.copy(blacklistPaths = settings.blacklistPaths + item.file.absolutePath))
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.VisibilityOff, contentDescription = "Ocultar pista")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                DesktopTab.ALBUMS -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(effectiveGridColumns),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(albumGroups, key = { it.first }) { (albumName, primaryArtist, count) ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedFilterAlbum = albumName
                                                        activeTab = DesktopTab.TRACKS
                                                    }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Icon(
                                                        Icons.Default.Album,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(albumName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        "$primaryArtist • $count pistas",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                DesktopTab.ARTISTS -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(effectiveGridColumns),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(artistCounts, key = { it.first }) { (artistName, count) ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedFilterArtist = artistName
                                                        activeTab = DesktopTab.TRACKS
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(artistName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text("$count canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                DesktopTab.GENRES -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(effectiveGridColumns),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(genreCounts, key = { it.first }) { (genreName, count) ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedFilterGenre = genreName
                                                        activeTab = DesktopTab.TRACKS
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(Icons.Default.Category, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(genreName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text("$count pistas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                DesktopTab.SETTINGS -> {
                                    DesktopSettingsPanel(
                                        settings = settings,
                                        engine = engine,
                                        songs = songs,
                                        outputDevices = outputDevices,
                                        newWhitelistInput = newWhitelistInput,
                                        onNewWhitelistInputChange = { newWhitelistInput = it },
                                        onUpdateSettings = ::updateSettings
                                    )
                                }
                            }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = if (isShortHeight) 8.dp else 12.dp)) {
                                Slider(
                                    value = progress,
                                    onValueChange = { engine.seekToFraction(it) },
                                    modifier = Modifier.fillMaxWidth().height(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = currentSong?.core?.displayTitle ?: "Selecciona una pista o carpeta",
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (settings.showReplayGainBadge && activeRg != null && activeRg!!.sourceLabel != "OFF") {
                                                val sign = if (activeRg!!.appliedGainDb >= 0f) "+" else ""
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                                ) {
                                                    Text(
                                                        text = String.format(Locale.US, "RG %s%.1f dB", sign, activeRg!!.appliedGainDb),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            val songArtists = currentSong?.core?.artists
                                            if (!songArtists.isNullOrEmpty()) {
                                                songArtists.forEachIndexed { idx, name ->
                                                    Text(
                                                        text = if (idx < songArtists.lastIndex) "$name •" else name,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.clickable {
                                                            selectedFilterArtist = name
                                                            activeTab = DesktopTab.TRACKS
                                                        }
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = if (engine.hasFfmpegAvailable) "Motor Gapless + FFmpeg SSE2 Activo" else "Motor Gapless JavaSound Activo",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Slider(
                                            value = settings.masterVolume,
                                            onValueChange = { vol ->
                                                settings = settings.copy(masterVolume = vol)
                                                engine.setMasterVolume(vol)
                                            },
                                            onValueChangeFinished = { updateSettings(settings) },
                                            modifier = Modifier.width(if (isCompactWidth) 80.dp else 120.dp)
                                        )
                                        FilledTonalIconButton(
                                            onClick = {
                                                val cur = currentSong ?: return@FilledTonalIconButton
                                                val idx = visibleSongs.indexOfFirst { it.core.id == cur.core.id }
                                                if (idx > 0) engine.play(visibleSongs[idx - 1])
                                            }
                                        ) {
                                            Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior")
                                        }
                                        FilledIconButton(onClick = { engine.togglePlayPause() }) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Reproducir / Pausar"
                                            )
                                        }
                                        FilledTonalIconButton(
                                            onClick = {
                                                val cur = currentSong ?: visibleSongs.firstOrNull() ?: return@FilledTonalIconButton
                                                val idx = visibleSongs.indexOfFirst { it.core.id == cur.core.id }
                                                if (idx >= 0 && visibleSongs.isNotEmpty()) {
                                                    engine.play(visibleSongs[(idx + 1) % visibleSongs.size])
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        editingSong?.let { songToEdit ->
            var editTitle by remember(songToEdit) { mutableStateOf(songToEdit.core.title) }
            var editArtist by remember(songToEdit) { mutableStateOf(songToEdit.core.artist) }
            var editAlbum by remember(songToEdit) { mutableStateOf(songToEdit.core.album) }
            var editGenre by remember(songToEdit) { mutableStateOf(songToEdit.core.genre.orEmpty()) }
            val livePreview = remember(editTitle, editArtist, editGenre, settings.multiTagConfig) {
                MultiTagParser.parseTrackTags(editTitle, editArtist, editGenre, settings.multiTagConfig)
            }

            AlertDialog(
                onDismissRequest = { editingSong = null },
                title = { Text("Editar Metadatos Reales") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Título") }, singleLine = true)
                        OutlinedTextField(value = editArtist, onValueChange = { editArtist = it }, label = { Text("Artista(s)") }, singleLine = true)
                        OutlinedTextField(value = editAlbum, onValueChange = { editAlbum = it }, label = { Text("Álbum") }, singleLine = true)
                        OutlinedTextField(value = editGenre, onValueChange = { editGenre = it }, label = { Text("Género(s)") }, singleLine = true)
                        Text(
                            text = "Artistas detectados: ${livePreview.artists.joinToString(" | ")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (livePreview.artists.size > 1 && editArtist.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    val updatedWl = settings.multiTagConfig.customWhitelist + editArtist.trim()
                                    updateSettings(
                                        settings.copy(multiTagConfig = settings.multiTagConfig.copy(customWhitelist = updatedWl)),
                                        reparseTags = true
                                    )
                                }
                            ) {
                                Text("Proteger \"$editArtist\" en Lista Blanca (no separar)")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val target = songToEdit
                            editingSong = null
                            scope.launch {
                                val updated = engine.writeRealMetadata(
                                    song = target,
                                    title = editTitle,
                                    artist = editArtist,
                                    album = editAlbum,
                                    genre = editGenre.ifBlank { null },
                                    multiTagConfig = settings.multiTagConfig
                                )
                                if (updated != null) {
                                    songs = songs.map { if (it.file == target.file) updated else it }
                                }
                            }
                        }
                    ) {
                        Text("Guardar en Archivo")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingSong = null }) { Text("Cancelar") }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSettingsPanel(
    settings: DesktopSettings,
    engine: DesktopAudioEngine,
    songs: List<DesktopSong>,
    outputDevices: List<AudioOutputDevice>,
    newWhitelistInput: String,
    onNewWhitelistInputChange: (String) -> Unit,
    onUpdateSettings: (DesktopSettings, Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ReplayGain y Normalización de Volumen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReplayGainMode.OFF to "Desactivado",
                        ReplayGainMode.TRACK to "Por Pista",
                        ReplayGainMode.ALBUM to "Por Álbum",
                        ReplayGainMode.SMART_ALBUM to "Álbum Inteligente"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.replayGainConfig.mode == mode,
                            onClick = {
                                onUpdateSettings(
                                    settings.copy(replayGainConfig = settings.replayGainConfig.copy(mode = mode)),
                                    false
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text("Pre-Amp con etiquetas ReplayGain/R128: ${String.format(Locale.US, "%+.1f dB", settings.replayGainConfig.preAmpWithTagsDb)}")
                Slider(
                    value = settings.replayGainConfig.preAmpWithTagsDb,
                    onValueChange = {
                        onUpdateSettings(settings.copy(replayGainConfig = settings.replayGainConfig.copy(preAmpWithTagsDb = (it * 2f).roundToInt() / 2f)), false)
                    },
                    valueRange = -12f..12f
                )
                Text("Pre-Amp sin etiquetas: ${String.format(Locale.US, "%+.1f dB", settings.replayGainConfig.preAmpUntaggedDb)}")
                Slider(
                    value = settings.replayGainConfig.preAmpUntaggedDb,
                    onValueChange = {
                        onUpdateSettings(settings.copy(replayGainConfig = settings.replayGainConfig.copy(preAmpUntaggedDb = (it * 2f).roundToInt() / 2f)), false)
                    },
                    valueRange = -12f..12f
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = settings.replayGainConfig.enableFallbackAnalysis,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(replayGainConfig = settings.replayGainConfig.copy(enableFallbackAnalysis = it)), false)
                            }
                        )
                        Text("Análisis ligero RMS/LUFS en pistas sin etiquetas")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = settings.replayGainConfig.preventClipping,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(replayGainConfig = settings.replayGainConfig.copy(preventClipping = it)), false)
                            }
                        )
                        Text("Limitador Anti-Clipping")
                    }
                }
                OutlinedButton(
                    onClick = { engine.analyzeUntaggedTracksInBackground(songs) }
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Analizar sonoridad LUFS K-Weighted en pistas sin etiquetas")
                }
            }
        }

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Audio Gapless, Formatos Extendidos y Salida de Audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (engine.hasFfmpegAvailable) {
                        "Decodificadores FFmpeg SSE2 + Nativo PCM/DSD activos: reproducción verificada de ALAC, AIFF/AIF, APE, WavPack (WV), DSD (DSF/DFF), M4A, MP4, MKV/MKA, WebM, Opus (estéreo y 5.1/7.1), E-AC3, AC-3, AC-4, DTS, WMA, CAF, FLAC, WAV, OGG, AAC y MP3."
                    } else {
                        "Decodificador Nativo PCM/DSD + JavaSound activo (WAV, AIFF/AIF, CAF, DSF/DFF DSD, AU). Instala 'ffmpeg' en tu sistema para habilitar ALAC, APE, WavPack, MKV/MKA, WebM, Opus 5.1/7.1, E-AC3, AC-3, AC-4, DTS y WMA."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.gaplessEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(gaplessEnabled = it), false) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Audio Gapless sin silencios")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.respectAlbumGapless,
                            onCheckedChange = { onUpdateSettings(settings.copy(respectAlbumGapless = it), false) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Respetar Gapless en Álbumes Continuos")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.crossfadeEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(crossfadeEnabled = it), false) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Crossfade (${settings.crossfadeSeconds.toInt()}s)")
                    }
                }
                Text("Dispositivo de Salida (PipeWire / ALSA / USB Audio DAC):", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    outputDevices.forEach { dev ->
                        FilterChip(
                            selected = settings.selectedMixerName == dev.id,
                            onClick = { onUpdateSettings(settings.copy(selectedMixerName = dev.id), false) },
                            label = { Text(dev.displayName) }
                        )
                    }
                }
            }
        }

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Multi-Artistas, Multi-Géneros y Lista Blanca", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = settings.multiTagConfig.enableMultiArtist,
                        onClick = { onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(enableMultiArtist = !settings.multiTagConfig.enableMultiArtist)), true) },
                        label = { Text("Multi-Artista") }
                    )
                    FilterChip(
                        selected = settings.multiTagConfig.enableMultiGenre,
                        onClick = { onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(enableMultiGenre = !settings.multiTagConfig.enableMultiGenre)), true) },
                        label = { Text("Multi-Género") }
                    )
                    FilterChip(
                        selected = settings.multiTagConfig.extractArtistsFromTitle,
                        onClick = { onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(extractArtistsFromTitle = !settings.multiTagConfig.extractArtistsFromTitle)), true) },
                        label = { Text("Extraer (ft. / feat.) del Título") }
                    )
                    FilterChip(
                        selected = settings.multiTagConfig.cleanExtractedFeatFromTitle,
                        onClick = { onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(cleanExtractedFeatFromTitle = !settings.multiTagConfig.cleanExtractedFeatFromTitle)), true) },
                        label = { Text("Limpiar (ft. ...) del Título mostrado") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newWhitelistInput,
                        onValueChange = onNewWhitelistInputChange,
                        label = { Text("Agregar excepción a Lista Blanca (ej. AC/DC, Wisin & Yandel)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = newWhitelistInput.trim()
                            if (trimmed.isNotEmpty()) {
                                val updated = settings.multiTagConfig.customWhitelist + trimmed
                                onNewWhitelistInputChange("")
                                onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(customWhitelist = updated)), true)
                            }
                        }
                    ) {
                        Text("Proteger")
                    }
                }
                if (settings.multiTagConfig.customWhitelist.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.multiTagConfig.customWhitelist.forEach { entry ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    val updated = settings.multiTagConfig.customWhitelist - entry
                                    onUpdateSettings(settings.copy(multiTagConfig = settings.multiTagConfig.copy(customWhitelist = updated)), true)
                                },
                                label = { Text(entry) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Quitar", modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }
        }

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rendimiento Extremo y Adaptación de Pantalla", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (engine.isLowSpecHardwareDetected) {
                        "Hardware modesto detectado (≤ 2 núcleos): Modo Ahorro / Ligero automático activado para 0% uso de CPU/GPU en reposo."
                    } else {
                        "Selecciona el perfil de recursos y la densidad visual ideal para tu monitor."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        PerformanceProfile.AUTO to "Automático",
                        PerformanceProfile.ULTRA_SAVER to "Ultra Ahorro (PC Modesta)",
                        PerformanceProfile.LIGHT to "Ligero",
                        PerformanceProfile.FULL to "Completo"
                    ).forEach { (prof, label) ->
                        FilterChip(
                            selected = settings.performanceProfile == prof,
                            onClick = { onUpdateSettings(settings.copy(performanceProfile = prof), false) },
                            label = { Text(label) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        UiDensityMode.COMPACT to "Densidad Compacta",
                        UiDensityMode.STANDARD to "Densidad Estándar",
                        UiDensityMode.COMFORTABLE to "Densidad Amplia"
                    ).forEach { (dens, label) ->
                        FilterChip(
                            selected = settings.uiDensityMode == dens,
                            onClick = { onUpdateSettings(settings.copy(uiDensityMode = dens), false) },
                            label = { Text(label) }
                        )
                    }
                }
                Text("Escala de Tipografía: ${(settings.uiFontScale * 100).roundToInt()}%")
                Slider(
                    value = settings.uiFontScale,
                    onValueChange = { onUpdateSettings(settings.copy(uiFontScale = (it * 20f).roundToInt() / 20f), false) },
                    valueRange = 0.85f..1.25f
                )
            }
        }

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Acerca de Flow (Fork de Lune)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Flow v1.0 — Reproductor audiófilo multiplataforma ultraligero (< 180 MB RAM en PC) para Android, Windows y Linux.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Flow es un fork de código abierto (GPLv3) basado en Lune por MrDemonc (https://github.com/MrDemonc/Lune), rediseñado con normalización LUFS K-Weighted en tiempo real, motor Gapless, soporte extendido de 23+ formatos y optimización extrema para hardware modesto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            if (java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/MrDemonc/Lune"))
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Proyecto original Lune en GitHub (MrDemonc/Lune)")
                }
            }
        }
    }
}
