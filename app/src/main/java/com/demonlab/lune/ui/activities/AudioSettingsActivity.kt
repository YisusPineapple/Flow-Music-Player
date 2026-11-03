package com.demonlab.lune.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.BouncySwitch
import androidx.compose.ui.graphics.Color
import com.demonlab.lune.tools.PlaybackManager
import com.demonlab.lune.ui.components.AppBlurBackdrop
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.utils.bounceClick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.Alignment
import com.demonlab.lune.audio.ReplayGainScanner
import com.demonlab.lune.tools.MusicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AudioSettingsActivity : ComponentActivity() {
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

            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = useCustomColors,
                customColorPalette = customColorPalette,
                useAmoledPitchBlack = useAmoledPitchBlack
            ) {
                AudioSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
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

    var enableHiFi by remember { mutableStateOf(settingsManager.enableHiFi) }
    var is32BitFloatEnabled by remember { mutableStateOf(settingsManager.is32BitFloatEnabled) }
    var isExclusiveModeEnabled by remember { mutableStateOf(settingsManager.isExclusiveModeEnabled) }
    var stopOnTaskRemoved by remember { mutableStateOf(settingsManager.stopOnTaskRemoved) }
    var isCrossfadeAdaptive by remember { mutableStateOf(settingsManager.isCrossfadeAdaptive) }
    var isCrossfadeSilenceDetection by remember { mutableStateOf(settingsManager.isCrossfadeSilenceDetection) }

    // ReplayGain & Volume Normalization states
    var replayGainMode by remember { mutableIntStateOf(settingsManager.replayGainMode) }
    var replayGainPreampDb by remember { mutableFloatStateOf(settingsManager.replayGainPreampDb) }
    var replayGainUntaggedPreampDb by remember { mutableFloatStateOf(settingsManager.replayGainUntaggedPreampDb) }
    var replayGainPreventClipping by remember { mutableStateOf(settingsManager.replayGainPreventClipping) }
    var replayGainRmsFallback by remember { mutableStateOf(settingsManager.replayGainRmsFallback) }
    val lufsAnalysisStatus by ReplayGainScanner.analysisStatus.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Gapless Audio states
    var isGaplessEnabled by remember { mutableStateOf(settingsManager.isGaplessEnabled) }
    var isGaplessSmartAlbum by remember { mutableStateOf(settingsManager.isGaplessSmartAlbum) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val subCardBg = if (hasBlurBackground) {
        if (isDarkTheme) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val textColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (hasBlurBackground) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

    AppBlurBackdrop(
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
                            stringResource(R.string.audio_settings),
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
                                Box(contentAlignment = Alignment.Center) {
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
                // 1. ReplayGain & Volume Normalization Section
                SettingsSection(title = stringResource(R.string.replaygain_section)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                        color = subCardBg
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.replaygain_mode_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = textColor
                                    )
                                    Text(
                                        text = stringResource(R.string.replaygain_mode_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subTextColor
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            val rgModes = listOf(
                                0 to stringResource(R.string.replaygain_mode_off),
                                1 to stringResource(R.string.replaygain_mode_track),
                                2 to stringResource(R.string.replaygain_mode_album),
                                3 to stringResource(R.string.replaygain_mode_smart)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rgModes.forEach { (modeCode, label) ->
                                    FilterChip(
                                        selected = replayGainMode == modeCode,
                                        onClick = {
                                            replayGainMode = modeCode
                                            settingsManager.replayGainMode = modeCode
                                            playbackManager.refreshReplayGainState()
                                        },
                                        label = { Text(label, fontWeight = FontWeight.SemiBold) }
                                    )
                                }
                            }

                            if (replayGainMode != 0) {
                                Spacer(modifier = Modifier.height(14.dp))
                                val sign1 = if (replayGainPreampDb >= 0f) "+" else ""
                                Text(
                                    text = "${stringResource(R.string.replaygain_preamp_tagged)}: $sign1${String.format(java.util.Locale.US, "%.1f", replayGainPreampDb)} dB",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = textColor
                                )
                                Slider(
                                    value = replayGainPreampDb,
                                    onValueChange = {
                                        val snapped = (it * 2f).roundToInt() / 2f
                                        replayGainPreampDb = snapped
                                        settingsManager.replayGainPreampDb = snapped
                                        playbackManager.refreshReplayGainState()
                                    },
                                    valueRange = -12f..12f,
                                    steps = 47
                                )
                            }
                        }
                    }

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.replaygain_rms_fallback),
                        supportingText = stringResource(R.string.replaygain_rms_fallback_desc),
                        icon = Icons.Default.Speed,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = replayGainRmsFallback,
                                onCheckedChange = {
                                    replayGainRmsFallback = it
                                    settingsManager.replayGainRmsFallback = it
                                    playbackManager.refreshReplayGainState()
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (replayGainRmsFallback) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    if (replayGainMode != 0 && !replayGainRmsFallback) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = subCardBg
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                                val sign2 = if (replayGainUntaggedPreampDb >= 0f) "+" else ""
                                Text(
                                    text = "${stringResource(R.string.replaygain_preamp_untagged)}: $sign2${String.format(java.util.Locale.US, "%.1f", replayGainUntaggedPreampDb)} dB",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = textColor
                                )
                                Slider(
                                    value = replayGainUntaggedPreampDb,
                                    onValueChange = {
                                        val snapped = (it * 2f).roundToInt() / 2f
                                        replayGainUntaggedPreampDb = snapped
                                        settingsManager.replayGainUntaggedPreampDb = snapped
                                        playbackManager.refreshReplayGainState()
                                    },
                                    valueRange = -12f..12f,
                                    steps = 47
                                )
                            }
                        }
                    }

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.replaygain_prevent_clipping),
                        supportingText = stringResource(R.string.replaygain_prevent_clipping_desc),
                        icon = Icons.Default.Tune,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = replayGainPreventClipping,
                                onCheckedChange = {
                                    replayGainPreventClipping = it
                                    settingsManager.replayGainPreventClipping = it
                                    playbackManager.refreshReplayGainState()
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (replayGainPreventClipping) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.analyze_library_lufs),
                        supportingText = if (lufsAnalysisStatus.isRunning) {
                            stringResource(
                                R.string.analyzing_library_progress,
                                lufsAnalysisStatus.processedCount,
                                lufsAnalysisStatus.totalCount
                            )
                        } else {
                            stringResource(R.string.analyze_library_lufs_desc)
                        },
                        icon = Icons.Default.GraphicEq,
                        position = SectionPosition.LAST,
                        onClick = {
                            if (!lufsAnalysisStatus.isRunning) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val cachedSongs = MusicProvider(context).getCachedSongs()
                                    ReplayGainScanner.analyzeLibraryUntaggedInBackground(
                                        context = context,
                                        songs = cachedSongs,
                                        maxTracksPerPass = 500
                                    )
                                }
                            }
                        },
                        trailingContent = if (lufsAnalysisStatus.isRunning) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                        } else null
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Gapless Audio & Extended Formats Section
                SettingsSection(title = stringResource(R.string.gapless_section)) {
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.gapless_playback),
                        supportingText = stringResource(R.string.gapless_playback_desc),
                        icon = Icons.Default.AllInclusive,
                        position = SectionPosition.FIRST,
                        trailingContent = {
                            BouncySwitch(
                                checked = isGaplessEnabled,
                                onCheckedChange = {
                                    isGaplessEnabled = it
                                    settingsManager.isGaplessEnabled = it
                                    playbackManager.refreshGaplessState()
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isGaplessEnabled) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.gapless_smart_album),
                        supportingText = stringResource(R.string.gapless_smart_album_desc),
                        icon = Icons.Default.AutoAwesome,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = isGaplessSmartAlbum,
                                onCheckedChange = {
                                    isGaplessSmartAlbum = it
                                    settingsManager.isGaplessSmartAlbum = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isGaplessSmartAlbum) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.extended_formats_title),
                        supportingText = stringResource(R.string.extended_formats_desc),
                        icon = Icons.Default.AudioFile,
                        position = SectionPosition.LAST
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Engine & Output Settings Section
                SettingsSection(title = stringResource(R.string.audio_settings)) {
                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.hifi_badge),
                        supportingText = stringResource(R.string.hifi_badge_desc),
                        icon = Icons.Default.MusicNote,
                        position = SectionPosition.FIRST,
                        trailingContent = {
                            BouncySwitch(
                                checked = enableHiFi,
                                onCheckedChange = {
                                    enableHiFi = it
                                    settingsManager.enableHiFi = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (enableHiFi) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.audio_32bit_float),
                        supportingText = stringResource(R.string.audio_32bit_float_desc),
                        icon = Icons.Default.Equalizer,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = is32BitFloatEnabled,
                                onCheckedChange = {
                                    is32BitFloatEnabled = it
                                    settingsManager.is32BitFloatEnabled = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (is32BitFloatEnabled) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.audio_exclusive_mode),
                        supportingText = stringResource(R.string.audio_exclusive_mode_desc),
                        icon = Icons.Default.HighQuality,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = isExclusiveModeEnabled,
                                onCheckedChange = {
                                    isExclusiveModeEnabled = it
                                    settingsManager.isExclusiveModeEnabled = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isExclusiveModeEnabled) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.adaptive_crossfade),
                        supportingText = stringResource(R.string.adaptive_crossfade_desc),
                        icon = Icons.Default.AutoAwesome,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = isCrossfadeAdaptive,
                                onCheckedChange = {
                                    isCrossfadeAdaptive = it
                                    settingsManager.isCrossfadeAdaptive = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isCrossfadeAdaptive) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.crossfade_silence_detection),
                        supportingText = stringResource(R.string.crossfade_silence_detection_desc),
                        icon = Icons.Default.GraphicEq,
                        position = SectionPosition.MIDDLE,
                        trailingContent = {
                            BouncySwitch(
                                checked = isCrossfadeSilenceDetection,
                                onCheckedChange = {
                                    isCrossfadeSilenceDetection = it
                                    settingsManager.isCrossfadeSilenceDetection = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (isCrossfadeSilenceDetection) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )

                    SettingsPreferenceItem(
                        headlineText = stringResource(R.string.stop_on_task_removed),
                        supportingText = stringResource(R.string.stop_on_task_removed_desc),
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        position = SectionPosition.LAST,
                        trailingContent = {
                            BouncySwitch(
                                checked = stopOnTaskRemoved,
                                onCheckedChange = {
                                    stopOnTaskRemoved = it
                                    settingsManager.stopOnTaskRemoved = it
                                },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (stopOnTaskRemoved) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

