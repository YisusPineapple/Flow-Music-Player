package com.demonlab.lune.ui.activities

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Restore
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.material3.*
import com.demonlab.lune.ui.components.BouncySwitch
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import kotlinx.coroutines.launch
import com.demonlab.lune.tools.PlaylistBackupManager
import com.demonlab.lune.tools.MusicProvider
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.BuildConfig
import com.demonlab.lune.tools.PlaybackManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import com.demonlab.lune.core.metadata.MultiTagParser
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.utils.bounceClick

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val themeMode = settingsManager.themeMode
            val systemInDarkTheme = isSystemInDarkTheme()
            val targetDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme
            }
            
            var useCustomColors by remember { mutableStateOf(settingsManager.useCustomColors) }
            var customColorPalette by remember { mutableIntStateOf(settingsManager.customColorPalette) }
            var useAmoledPitchBlack by remember { mutableStateOf(settingsManager.useAmoledPitchBlack) }
            
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        useCustomColors = settingsManager.useCustomColors
                        customColorPalette = settingsManager.customColorPalette
                        useAmoledPitchBlack = settingsManager.useAmoledPitchBlack
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = useCustomColors,
                customColorPalette = customColorPalette,
                useAmoledPitchBlack = useAmoledPitchBlack
            ) {
                SettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    var showWhatsapp by remember { mutableStateOf(settingsManager.showWhatsappAudio) }
    var keepScreenOn by remember { mutableStateOf(settingsManager.keepScreenOn) }

    var isCinematicEnabled by remember { mutableStateOf(settingsManager.isCinematicPlayerEnabled) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showMultiTagDialog by remember { mutableStateOf(false) }
    var showPerfModeDialog by remember { mutableStateOf(false) }
    var performanceMode by remember { mutableIntStateOf(settingsManager.performanceMode) }
    var showBackupWarning by remember { mutableStateOf(settingsManager.showBackupWarning) }
    var isScanningLibrary by remember { mutableStateOf(false) }
    val currentLanguage = settingsManager.language
    val scope = rememberCoroutineScope()
    val backupManager = remember { PlaylistBackupManager(context) }
    val musicProvider = remember { MusicProvider(context) }
    var autoSyncBackupUri by remember { mutableStateOf(settingsManager.autoSyncBackupUri) }
    var isAutoSyncEnabled by remember { mutableStateOf(settingsManager.isAutoSyncBackupEnabled) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            settingsManager.autoSyncBackupUri = it.toString()
            autoSyncBackupUri = it.toString()
            scope.launch {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    val success = backupManager.exportPlaylists(outputStream)
                    Toast.makeText(
                        context,
                        if (success) context.getString(R.string.export_success) else context.getString(R.string.export_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val success = backupManager.importPlaylists(inputStream)
                    Toast.makeText(
                        context,
                        if (success) context.getString(R.string.import_success) else context.getString(R.string.import_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val currentSong = playbackManager.currentSong
    val themeMode = settingsManager.themeMode
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = !settingsManager.isLowResourceVisualsActive(context) &&
        settingsManager.isBlurEnabled &&
        ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            confirmButton = {},
            containerColor = if (hasBlurBackground) (if (isDarkTheme) Color(0xFF1E1E1E).copy(alpha = 0.95f) else Color(0xFFF5F5F5).copy(alpha = 0.95f)) else MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.select_language),
                    color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    val languages = listOf(
                        "system" to stringResource(R.string.lang_system),
                        "en" to stringResource(R.string.lang_english),
                        "es" to stringResource(R.string.lang_spanish),
                        "pt-BR" to stringResource(R.string.lang_portuguese),
                        "fr" to stringResource(R.string.lang_french),
                        "zh" to stringResource(R.string.lang_chinese),
                        "de" to stringResource(R.string.lang_german),
                        "ru" to stringResource(R.string.lang_russian),
                        "fa" to stringResource(R.string.lang_persian),
                        "ar" to stringResource(R.string.lang_arabic)
                    )
                    languages.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsManager.language = code
                                    val appLocales: LocaleListCompat = if (code == "system") {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(code)
                                    }
                                    AppCompatDelegate.setApplicationLocales(appLocales)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == code,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary,
                                    unselectedColor = if (hasBlurBackground) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                label,
                                color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showBlacklistDialog) {
        val blacklistedIds by settingsManager.blacklistedSongsFlow.collectAsState()
        var hiddenFoldersState by remember { mutableStateOf(settingsManager.hiddenFolders) }
        val cachedSongs = remember(showBlacklistDialog) { musicProvider.getCachedSongs() }
        val blacklistedSongs = remember(blacklistedIds, cachedSongs) {
            cachedSongs.filter { it.id in blacklistedIds }
        }

        AlertDialog(
            onDismissRequest = { showBlacklistDialog = false },
            confirmButton = {
                TextButton(onClick = { showBlacklistDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            containerColor = if (hasBlurBackground) (if (isDarkTheme) Color(0xFF1E1E1E).copy(alpha = 0.96f) else Color(0xFFF5F5F5).copy(alpha = 0.96f)) else MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.manage_blacklist),
                    color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (blacklistedSongs.isEmpty() && hiddenFoldersState.isEmpty()) {
                        Text(
                            text = stringResource(R.string.blacklist_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasBlurBackground) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        if (blacklistedSongs.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.blacklisted_songs),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                            )
                            blacklistedSongs.forEach { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = song.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            color = if (hasBlurBackground) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { settingsManager.removeSongFromBlacklist(song.id) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = stringResource(R.string.restore_item),
                                            tint = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        if (hiddenFoldersState.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.blacklisted_folders),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                            )
                            hiddenFoldersState.sorted().forEach { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (hasBlurBackground) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                    IconButton(
                                        onClick = {
                                            val updated = hiddenFoldersState - folder
                                            hiddenFoldersState = updated
                                            settingsManager.hiddenFolders = updated
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = stringResource(R.string.restore_item),
                                            tint = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showMultiTagDialog) {
        var enableMultiArtist by remember { mutableStateOf(settingsManager.enableMultiArtist) }
        var enableMultiGenre by remember { mutableStateOf(settingsManager.enableMultiGenre) }
        var splitSemicolon by remember { mutableStateOf(settingsManager.splitBySemicolon) }
        var splitComma by remember { mutableStateOf(settingsManager.splitByComma) }
        var splitSlash by remember { mutableStateOf(settingsManager.splitBySlash) }
        var splitAmpersand by remember { mutableStateOf(settingsManager.splitByAmpersand) }
        var splitFeat by remember { mutableStateOf(settingsManager.splitByFeatKeywords) }
        var extractFromTitle by remember { mutableStateOf(settingsManager.extractArtistsFromTitle) }
        var cleanFeatTitle by remember { mutableStateOf(settingsManager.cleanFeatFromDisplayTitle) }
        var customWhitelist by remember { mutableStateOf(settingsManager.tagWhitelist) }
        var newWhitelistEntry by remember { mutableStateOf("") }
        val dlgText = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
        val dlgSubText = if (hasBlurBackground) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant

        AlertDialog(
            onDismissRequest = { showMultiTagDialog = false },
            confirmButton = {
                TextButton(onClick = { showMultiTagDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            containerColor = if (hasBlurBackground) (if (isDarkTheme) Color(0xFF1E1E1E).copy(alpha = 0.96f) else Color(0xFFF5F5F5).copy(alpha = 0.96f)) else MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.multitag_section),
                    color = dlgText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.enable_multi_artist), fontWeight = FontWeight.SemiBold, color = dlgText)
                            Text(stringResource(R.string.enable_multi_artist_desc), style = MaterialTheme.typography.bodySmall, color = dlgSubText)
                        }
                        Switch(
                            checked = enableMultiArtist,
                            onCheckedChange = {
                                enableMultiArtist = it
                                settingsManager.enableMultiArtist = it
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.enable_multi_genre), fontWeight = FontWeight.SemiBold, color = dlgText)
                            Text(stringResource(R.string.enable_multi_genre_desc), style = MaterialTheme.typography.bodySmall, color = dlgSubText)
                        }
                        Switch(
                            checked = enableMultiGenre,
                            onCheckedChange = {
                                enableMultiGenre = it
                                settingsManager.enableMultiGenre = it
                            }
                        )
                    }

                    HorizontalDivider()
                    Text(stringResource(R.string.multitag_separators_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    val sepItems = listOf(
                        Triple(stringResource(R.string.split_semicolon), splitSemicolon) { v: Boolean -> splitSemicolon = v; settingsManager.splitBySemicolon = v },
                        Triple(stringResource(R.string.split_comma), splitComma) { v: Boolean -> splitComma = v; settingsManager.splitByComma = v },
                        Triple(stringResource(R.string.split_slash), splitSlash) { v: Boolean -> splitSlash = v; settingsManager.splitBySlash = v },
                        Triple(stringResource(R.string.split_ampersand), splitAmpersand) { v: Boolean -> splitAmpersand = v; settingsManager.splitByAmpersand = v },
                        Triple(stringResource(R.string.split_feat_keywords), splitFeat) { v: Boolean -> splitFeat = v; settingsManager.splitByFeatKeywords = v }
                    )
                    sepItems.forEach { (label, checked, onToggle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(!checked) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = onToggle)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = dlgText)
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.extract_artists_from_title), fontWeight = FontWeight.SemiBold, color = dlgText)
                            Text(stringResource(R.string.extract_artists_from_title_desc), style = MaterialTheme.typography.bodySmall, color = dlgSubText)
                        }
                        Switch(
                            checked = extractFromTitle,
                            onCheckedChange = {
                                extractFromTitle = it
                                settingsManager.extractArtistsFromTitle = it
                            }
                        )
                    }

                    if (extractFromTitle) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.clean_feat_from_title), fontWeight = FontWeight.SemiBold, color = dlgText)
                                Text(stringResource(R.string.clean_feat_from_title_desc), style = MaterialTheme.typography.bodySmall, color = dlgSubText)
                            }
                            Switch(
                                checked = cleanFeatTitle,
                                onCheckedChange = {
                                    cleanFeatTitle = it
                                    settingsManager.cleanFeatFromDisplayTitle = it
                                }
                            )
                        }
                    }

                    HorizontalDivider()
                    Text(stringResource(R.string.whitelist_manage), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.whitelist_manage_desc), style = MaterialTheme.typography.bodySmall, color = dlgSubText)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newWhitelistEntry,
                            onValueChange = { newWhitelistEntry = it },
                            placeholder = { Text(stringResource(R.string.whitelist_add_hint), style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick = {
                                val clean = newWhitelistEntry.trim()
                                if (clean.isNotEmpty()) {
                                    settingsManager.addTagToWhitelist(clean)
                                    customWhitelist = settingsManager.tagWhitelist
                                    newWhitelistEntry = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.whitelist_add_button))
                        }
                    }

                    if (customWhitelist.isNotEmpty()) {
                        customWhitelist.sorted().forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(item, color = dlgText, fontWeight = FontWeight.Medium)
                                }
                                IconButton(onClick = {
                                    settingsManager.removeTagFromWhitelist(item)
                                    customWhitelist = settingsManager.tagWhitelist
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Text(
                        text = "${stringResource(R.string.whitelist_defaults_info)}: ${MultiTagParser.DEFAULT_PROTECTED_ENTRIES.take(10).joinToString(" • ")} …",
                        style = MaterialTheme.typography.labelSmall,
                        color = dlgSubText
                    )
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showPerfModeDialog) {
        val detectedProfile = settingsManager.resolvePerformanceProfile(context)
        val dlgText = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
        val dlgSubText = if (hasBlurBackground) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
        val modes = listOf(
            Triple(0, stringResource(R.string.perf_mode_auto), "${stringResource(R.string.performance_profile_desc)} (${detectedProfile.name})"),
            Triple(1, stringResource(R.string.perf_mode_ultra_saver), stringResource(R.string.perf_mode_ultra_saver_desc)),
            Triple(2, stringResource(R.string.perf_mode_light), stringResource(R.string.perf_mode_light_desc)),
            Triple(3, stringResource(R.string.perf_mode_full), stringResource(R.string.perf_mode_full_desc))
        )
        AlertDialog(
            onDismissRequest = { showPerfModeDialog = false },
            confirmButton = {
                TextButton(onClick = { showPerfModeDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            containerColor = if (hasBlurBackground) (if (isDarkTheme) Color(0xFF1E1E1E).copy(alpha = 0.96f) else Color(0xFFF5F5F5).copy(alpha = 0.96f)) else MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(stringResource(R.string.performance_profile_title), color = dlgText, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { (code, label, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    performanceMode = code
                                    settingsManager.performanceMode = code
                                    showPerfModeDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(selected = performanceMode == code, onClick = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(label, fontWeight = FontWeight.SemiBold, color = dlgText)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = dlgSubText)
                            }
                        }
                    }
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    com.demonlab.lune.ui.components.AppBlurBackdrop(
        hasBlurBackground = hasBlurBackground,
        isDarkTheme = isDarkTheme,
        currentSong = currentSong
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (hasBlurBackground && currentSong != null) Color.Transparent else MaterialTheme.colorScheme.surface,
            topBar = {
                LargeTopAppBar(
                    title = { 
                        Text(
                            stringResource(R.string.settings),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.primary
                        ) 
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.bounceClick()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (hasBlurBackground && currentSong != null) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack, 
                                        contentDescription = stringResource(R.string.cd_back),
                                        tint = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (hasBlurBackground && currentSong != null) Color.Transparent else MaterialTheme.colorScheme.surface,
                        titleContentColor = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // General Section
                SettingsSection(title = stringResource(R.string.general)) {
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.audio_settings),
                        supportingText = stringResource(R.string.audio_settings_desc),
                        icon = Icons.Default.MusicNote,
                        position = SectionPosition.FIRST,
                        onClick = { context.startActivity(Intent(context, AudioSettingsActivity::class.java)) }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.keep_screen_on),
                        supportingText = stringResource(R.string.keep_screen_on_desc),
                        icon = Icons.Default.LightMode,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = keepScreenOn,
                                onCheckedChange = {
                                    keepScreenOn = it
                                    settingsManager.keepScreenOn = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (keepScreenOn) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.rescan_library),
                        supportingText = stringResource(R.string.rescan_library_desc),
                        icon = Icons.Default.Sync,
                        position = SectionPosition.MIDDLE,
                        trailingContent = if (isScanningLibrary) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = {
                            if (!isScanningLibrary) {
                                scope.launch {
                                    isScanningLibrary = true
                                    val songs = musicProvider.refreshLibrary()
                                    isScanningLibrary = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.rescan_library_success, songs.size),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.manage_blacklist),
                        supportingText = stringResource(R.string.manage_blacklist_desc),
                        icon = Icons.Default.Block,
                        position = SectionPosition.MIDDLE,
                        onClick = { showBlacklistDialog = true }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.multitag_section),
                        supportingText = stringResource(R.string.whitelist_manage_desc),
                        icon = Icons.Default.Groups,
                        position = SectionPosition.MIDDLE,
                        onClick = { showMultiTagDialog = true }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.performance_profile_title),
                        supportingText = when (performanceMode) {
                            1 -> stringResource(R.string.perf_mode_ultra_saver)
                            2 -> stringResource(R.string.perf_mode_light)
                            3 -> stringResource(R.string.perf_mode_full)
                            else -> "${stringResource(R.string.perf_mode_auto)} (${settingsManager.resolvePerformanceProfile(context).name})"
                        },
                        icon = Icons.Default.Speed,
                        position = SectionPosition.MIDDLE,
                        onClick = { showPerfModeDialog = true }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.customization),
                        supportingText = stringResource(R.string.customization_desc),
                        icon = Icons.Default.Palette,
                        position = SectionPosition.MIDDLE,
                        onClick = { context.startActivity(Intent(context, CustomizationActivity::class.java)) }
                    )
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.language),
                        supportingText = when(currentLanguage) {
                            "en" -> stringResource(R.string.lang_english)
                            "es" -> stringResource(R.string.lang_spanish)
                            "pt-BR" -> stringResource(R.string.lang_portuguese)
                            "fr" -> stringResource(R.string.lang_french)
                            "zh" -> stringResource(R.string.lang_chinese)
                            "de" -> stringResource(R.string.lang_german)
                            "ru" -> stringResource(R.string.lang_russian)
                            "fa" -> stringResource(R.string.lang_persian)
                            "ar" -> stringResource(R.string.lang_arabic)
                            else -> stringResource(R.string.lang_system)
                        },
                        icon = Icons.Default.Language,
                        position = SectionPosition.LAST,
                        onClick = { showLanguageDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Backup Section
                SettingsSection(title = stringResource(R.string.backup)) {
                    if (showBackupWarning) {
                        BackupWarningCard(
                            onDismiss = {
                                showBackupWarning = false
                                settingsManager.showBackupWarning = false
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.export_playlists),
                        supportingText = stringResource(R.string.export_playlists_desc),
                        icon = Icons.Default.CloudDownload,
                        position = SectionPosition.FIRST,
                        onClick = { exportLauncher.launch("flow_playlists_backup.json") }
                    )
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.import_playlists),
                        supportingText = stringResource(R.string.import_playlists_desc),
                        icon = Icons.Default.Refresh,
                        position = SectionPosition.MIDDLE,
                        onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) }
                    )
                    val hasExportDestination = !autoSyncBackupUri.isNullOrBlank()
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.auto_sync_backup),
                        supportingText = if (hasExportDestination) {
                            stringResource(R.string.auto_sync_backup_desc)
                        } else {
                            stringResource(R.string.auto_sync_requires_export)
                        },
                        icon = Icons.Default.Sync,
                        position = SectionPosition.LAST,
                        trailingContent = {
                            BouncySwitch(
                                checked = isAutoSyncEnabled && hasExportDestination,
                                enabled = hasExportDestination,
                                onCheckedChange = { enabled ->
                                    isAutoSyncEnabled = enabled
                                    settingsManager.isAutoSyncBackupEnabled = enabled
                                    if (enabled) {
                                        backupManager.triggerAutoSync()
                                    }
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isAutoSyncEnabled && hasExportDestination) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Security Section
                SettingsSection(title = stringResource(R.string.security)) {
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.permissions),
                        supportingText = stringResource(R.string.permissions_desc),
                        icon = Icons.Default.Security,
                        position = SectionPosition.SINGLE,
                        onClick = { context.startActivity(Intent(context, PermissionsActivity::class.java)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // About Section
                SettingsSection(title = stringResource(R.string.about)) {
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.about),
                        icon = Icons.Default.Info,
                        position = SectionPosition.SINGLE,
                        onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) }
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

enum class SectionPosition {
    FIRST, MIDDLE, LAST, SINGLE
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = !settingsManager.isLowResourceVisualsActive(context) &&
        settingsManager.isBlurEnabled &&
        ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
fun SettingsPreferenceItem(
    headlineText: String,
    supportingText: String? = null,
    icon: ImageVector,
    position: SectionPosition,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = !settingsManager.isLowResourceVisualsActive(context) &&
        settingsManager.isBlurEnabled &&
        ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))

    val shape = when (position) {
        SectionPosition.FIRST -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        SectionPosition.MIDDLE -> RoundedCornerShape(4.dp)
        SectionPosition.LAST -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
        SectionPosition.SINGLE -> RoundedCornerShape(28.dp)
    }

    val cardBg = if (hasBlurBackground) {
        if (isDarkTheme) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val iconBg = if (hasBlurBackground) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    val iconTint = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
    val headlineColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
    val supportingColor = if (hasBlurBackground) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

    val clickModifier = if (onClick != null) {
        Modifier
            .bounceClick()
            .clip(shape)
            .clickable(onClick = onClick)
    } else Modifier

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .then(clickModifier),
        shape = shape,
        color = cardBg,
        tonalElevation = if (hasBlurBackground) 0.dp else 1.dp
    ) {
        ListItem(
            supportingContent = supportingText?.let { { Text(it, color = supportingColor) } },
            leadingContent = {
                Surface(
                    shape = CircleShape,
                    color = iconBg,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        ) {
            Text(headlineText, fontWeight = FontWeight.Bold, color = headlineColor)
        }
    }
}

@Composable
fun BackupWarningCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = settingsManager.isBlurEnabled && ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))

    val cardBg = if (hasBlurBackground) {
        Color.Black.copy(alpha = 0.35f)
    } else {
        Color(0xFFFDE8E8)
    }
    val borderColor = if (hasBlurBackground) Color.White.copy(alpha = 0.20f) else Color(0xFFF8B4B4)
    val titleColor = if (hasBlurBackground) Color.White else Color(0xFF9B1C1C)
    val descColor = if (hasBlurBackground) Color.White.copy(alpha = 0.80f) else Color(0xFF9B1C1C).copy(alpha = 0.85f)
    val iconTint = if (hasBlurBackground) Color(0xFFFF8A80) else Color(0xFF9B1C1C)
    val btnBg = if (hasBlurBackground) Color.White.copy(alpha = 0.15f) else Color(0xFFFBD5D5)
    val btnTint = if (hasBlurBackground) Color.White else Color(0xFF9B1C1C)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(com.demonlab.lune.R.string.backup_warning_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(com.demonlab.lune.R.string.backup_warning_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = descColor
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .bounceClick()
                    .background(btnBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = btnTint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
