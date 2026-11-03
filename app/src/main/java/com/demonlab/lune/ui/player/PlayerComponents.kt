package com.demonlab.lune.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.demonlab.lune.R
import com.demonlab.lune.tools.PlaybackManager
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.Song
import com.demonlab.lune.ui.data.Album
import com.demonlab.lune.ui.components.SongCoverImage
import com.demonlab.lune.ui.components.VinylRecordAsyncCover
import com.demonlab.lune.ui.components.WaveformVisualizer
import com.demonlab.lune.ui.activities.EqualizerActivity
import com.demonlab.lune.ui.sheets.AddToPlaylistDialog
import com.demonlab.lune.ui.sheets.AudioDetailsBottomSheet
import com.demonlab.lune.ui.sheets.CustomRepeatDialog
import com.demonlab.lune.ui.sheets.PlayerOptionsBottomSheet
import com.demonlab.lune.ui.sheets.QueueBottomSheet
import com.demonlab.lune.ui.sheets.VisualizerSettingsBottomSheet
import com.demonlab.lune.ui.theme.getControlsPrimaryColor
import com.demonlab.lune.ui.utils.MaterialExpressiveScallopShape
import com.demonlab.lune.ui.utils.bounceClick
import com.demonlab.lune.ui.utils.formatDuration
import com.demonlab.lune.ui.utils.getSafeVibrator
import com.demonlab.lune.ui.utils.songSwipeGestures
import com.demonlab.lune.ui.utils.triggerLightVibration
import com.demonlab.lune.ui.viewmodels.MusicViewModel
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumStackedCarousel(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    bottomPadding: Dp,
    hasBlurBackground: Boolean = false,
    activePlaylistId: Long? = null
) {
    val initialPage = remember(activePlaylistId, albums) {
        if (activePlaylistId != null) {
            val idx = albums.indexOfFirst { it.id == activePlaylistId }
            if (idx >= 0) idx else 0
        } else 0
    }
    val pagerState = rememberPagerState(pageCount = { albums.size })

    LaunchedEffect(activePlaylistId) {
        if (initialPage > 0) {
            pagerState.scrollToPage(initialPage)
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = bottomPadding + 32.dp),
        pageSpacing = (-240).dp
    ) { page ->
        val album = albums[page]
        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        val absPageOffset = abs(pageOffset)

        val scale = 1f - (absPageOffset * 0.1f).coerceIn(0f, 0.4f)
        val alpha = if (pageOffset > 3f || pageOffset < -1f) 0f else (1f - (absPageOffset * 0.3f)).coerceIn(0f, 1f)
        val translationY = if (pageOffset > 0) {
            (pageOffset * 60.dp.value)
        } else {
            -(pageOffset * 300.dp.value)
        }

        val zIndex = 100f - absPageOffset
        val isPlaying = album.id == activePlaylistId

        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    this.translationY = translationY
                    this.shadowElevation = if (pageOffset == 0f) 16f else 4f
                }
                .zIndex(zIndex)
                .fillMaxWidth(0.75f)
                .aspectRatio(0.85f)
                .clickable { onAlbumClick(album) },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxSize()
            ) {
                Box {
                    AsyncImage(
                        model = album.coverUrl ?: album.albumArtUri ?: R.drawable.ic_lune_placeholder,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (hasBlurBackground) Color.Black.copy(alpha = 0.60f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            contentColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color.White
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                )
                            ),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = album.artist,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioQualityBadges(
    song: Song,
    runtimeSampleRate: Int?,
    runtimeBitDepth: Int?,
    runtimeBitrate: Int?,
    useBlurControls: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    enableScroll: Boolean = false
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val format = song.format.uppercase().ifEmpty {
        song.path.substringAfterLast('.', "").uppercase().ifEmpty { "AUDIO" }
    }
    val effectiveSampleRate = runtimeSampleRate ?: song.sampleRate
    val effectiveBitDepth = runtimeBitDepth ?: song.bitDepth
    val effectiveBitrate = runtimeBitrate ?: song.bitrate

    val isHiRes = song.isHiRes ||
        ((effectiveSampleRate ?: 0) >= 48000 && (effectiveBitDepth ?: 0) >= 24) ||
        (effectiveSampleRate ?: 0) >= 88200 ||
        format in listOf("DSF", "DFF") ||
        ((effectiveBitrate ?: 0) >= 2304000)

    val isHiFi = !isHiRes && (song.isHiFi ||
        format in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "AIF", "AIFC", "WV", "CAF") ||
        ((effectiveBitrate ?: 0) > 320000))

    val isHq = !isHiRes && !isHiFi && ((effectiveBitrate ?: 0) >= 256000)

    val channelBadge = when (val ch = song.channels ?: 2) {
        6 -> "5.1ch"
        8 -> "7.1ch"
        else -> if (ch > 2) "${ch}ch" else null
    }

    val showRgBadge = settingsManager.showReplayGainBadge &&
        settingsManager.replayGainMode != 0 &&
        playbackManager.currentSong?.id == song.id &&
        playbackManager.activeReplayGainSource != "OFF"
    val rgBadgeText = if (showRgBadge) {
        val db = playbackManager.activeReplayGainDb
        val sign = if (db >= 0f) "+" else ""
        val prefix = when (playbackManager.activeReplayGainSource) {
            "LUFS", "RMS", "AUTO" -> "LUFS"
            "UNTAGGED" -> "PRE"
            else -> "RG"
        }
        val lim = if (playbackManager.activeReplayGainPeakLimited) "!" else ""
        "$prefix $sign${String.format(java.util.Locale.US, "%.1f", db)} dB$lim"
    } else null

    val tierName = when {
        isHiRes -> "HI-RES"
        isHiFi -> "HI-FI"
        isHq -> "HQ"
        else -> null
    }

    val tierBg = when {
        isHiRes -> if (useBlurControls) Color(0xFFFFB300).copy(alpha = 0.28f) else Color(0xFFFFB300).copy(alpha = 0.20f)
        isHiFi -> if (useBlurControls) Color(0xFF00E5FF).copy(alpha = 0.24f) else Color(0xFF00E5FF).copy(alpha = 0.16f)
        else -> if (useBlurControls) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }

    val tierBorder = when {
        isHiRes -> Color(0xFFFFB300).copy(alpha = 0.65f)
        isHiFi -> Color(0xFF00E5FF).copy(alpha = 0.55f)
        else -> if (useBlurControls) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    }

    val tierColor = when {
        isHiRes -> Color(0xFFFFC107)
        isHiFi -> Color(0xFF00E5FF)
        else -> if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary
    }

    val badgeBg = if (useBlurControls) {
        if (isDarkTheme) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    val badgeBorder = if (useBlurControls) {
        if (isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    }

    val badgeTextColor = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onSurface

    val sampleRateStr = effectiveSampleRate?.let {
        if (it % 1000 == 0) "${it / 1000} kHz" else "${String.format(java.util.Locale.US, "%.1f", it / 1000f)} kHz"
    }
    val bitDepthStr = effectiveBitDepth?.let { if (it > 0) "${it}-bit" else null }
    val bitrateStr = effectiveBitrate?.let { if (it > 0) "${it / 1000} kbps" else null }

    val specsText = when {
        bitDepthStr != null && sampleRateStr != null -> "$bitDepthStr / $sampleRateStr"
        sampleRateStr != null -> sampleRateStr
        bitrateStr != null -> bitrateStr
        else -> null
    }

    val badgeContent: @Composable () -> Unit = {
        if (tierName != null) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = tierBg,
                border = BorderStroke(1.dp, tierBorder)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    if (isHiRes) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = tierColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = tierName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = tierColor
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = badgeBg,
            border = BorderStroke(1.dp, badgeBorder)
        ) {
            Text(
                text = format,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = badgeTextColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        if (specsText != null) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = badgeBg,
                border = BorderStroke(1.dp, badgeBorder)
            ) {
                Text(
                    text = specsText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (useBlurControls) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        if (channelBadge != null) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = badgeBg,
                border = BorderStroke(1.dp, badgeBorder)
            ) {
                Text(
                    text = channelBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeTextColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        if (rgBadgeText != null) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = badgeBg,
                border = BorderStroke(1.dp, badgeBorder)
            ) {
                Text(
                    text = rgBadgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (useBlurControls) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    if (enableScroll) {
        Box(
            modifier = modifier,
            contentAlignment = if (horizontalAlignment == Alignment.CenterHorizontally) Alignment.Center else Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .bounceClick()
                    .clickable { onClick() }
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp, horizontalAlignment),
                verticalAlignment = Alignment.CenterVertically
            ) {
                badgeContent()
            }
        }
    } else {
        Row(
            modifier = modifier
                .bounceClick()
                .clickable { onClick() },
            horizontalArrangement = Arrangement.spacedBy(5.dp, horizontalAlignment),
            verticalAlignment = Alignment.CenterVertically
        ) {
            badgeContent()
        }
    }
}

@Composable
fun UnifiedMarqueeHeader(
    song: Song,
    useBlurControls: Boolean,
    blurContainerColor: Color,
    isAmoled: Boolean,
    isDarkTheme: Boolean,
    settingsManager: SettingsManager,
    runtimeSampleRate: Int?,
    runtimeBitDepth: Int?,
    runtimeBitrate: Int?,
    onArtistClick: ((String) -> Unit)?,
    onAudioBadgesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    key(song.id) {
        var marqueeOffset by remember(song.id) { mutableFloatStateOf(0f) }
        var isUserInteracting by remember(song.id) { mutableStateOf(false) }
        var containerWidth by remember { mutableIntStateOf(0) }
        var titleWidth by remember(song.id) { mutableIntStateOf(0) }
        var artistRowWidth by remember(song.id) { mutableIntStateOf(0) }

        val density = LocalDensity.current
        val speedPxPerSec = with(density) { 28.dp.toPx() }
        val spacingPx = with(density) { 40.dp.toPx() }

        val isTitleLong = containerWidth > 0 && titleWidth > containerWidth
        val isArtistLong = containerWidth > 0 && artistRowWidth > containerWidth
        val shouldScroll = isTitleLong || isArtistLong

        val cycle = when {
            isTitleLong && isArtistLong -> maxOf(titleWidth, artistRowWidth) + spacingPx
            isTitleLong -> titleWidth + spacingPx
            isArtistLong -> artistRowWidth + spacingPx
            else -> 0f
        }

        LaunchedEffect(song.id) {
            marqueeOffset = 0f
            isUserInteracting = false
        }

        val context = LocalContext.current
        val lowResourceVisuals = settingsManager.isLowResourceVisualsActive(context)
        val multiTagVersion by settingsManager.multiTagVersion.collectAsState()
        val multiTagConfig = remember(multiTagVersion) { settingsManager.toMultiTagConfig() }
        val displayTitle = remember(song.id, song.title, song.artist, multiTagConfig) {
            song.effectiveDisplayTitle(multiTagConfig)
        }
        val parsedArtists = remember(song.id, song.title, song.artist, multiTagConfig) {
            song.parsedArtists(multiTagConfig)
        }

        LaunchedEffect(song.id, shouldScroll, isUserInteracting, lowResourceVisuals) {
            if (isUserInteracting || !shouldScroll || lowResourceVisuals) return@LaunchedEffect
            delay(1500L)
            var lastFrameNanos = withFrameNanos { it }
            while (isActive && !isUserInteracting && shouldScroll && !lowResourceVisuals) {
                withFrameNanos { frameNanos ->
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    lastFrameNanos = frameNanos
                    marqueeOffset += speedPxPerSec * dtSec
                }
            }
        }

        val titleContent: @Composable (Boolean) -> Unit = { isMeasuring ->
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = if (lowResourceVisuals && !isMeasuring) TextOverflow.Ellipsis else TextOverflow.Clip,
                textAlign = TextAlign.Start,
                color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = if (isMeasuring) {
                    Modifier.onSizeChanged { titleWidth = it.width }
                } else {
                    Modifier
                }
            )
        }

        val artistRowContent: @Composable (Boolean) -> Unit = { isMeasuring ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = if (isMeasuring) {
                    Modifier.onSizeChanged { artistRowWidth = it.width }
                } else {
                    Modifier
                }
            ) {
                parsedArtists.forEach { collaborator ->
                    Surface(
                        color = if (useBlurControls) blurContainerColor else if (isAmoled) Color(0xFF222222) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(percent = 50),
                        modifier = Modifier
                            .bounceClick(0.95f)
                            .clickable { onArtistClick?.invoke(collaborator) }
                    ) {
                        Text(
                            text = collaborator,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                if (settingsManager.isBitrateOnPlayer) {
                    AudioQualityBadges(
                        song = song,
                        runtimeSampleRate = runtimeSampleRate,
                        runtimeBitDepth = runtimeBitDepth,
                        runtimeBitrate = runtimeBitrate,
                        useBlurControls = useBlurControls,
                        isDarkTheme = isDarkTheme,
                        onClick = onAudioBadgesClick,
                        horizontalAlignment = Alignment.Start,
                        enableScroll = false
                    )
                }
            }
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { containerWidth = it.width }
                .pointerInput(song.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val downChange = downEvent.changes.firstOrNull { it.pressed } ?: continue
                            val downId = downChange.id
                            isUserInteracting = true

                            var isDragging = false
                            var totalDx = 0f
                            val touchSlop = viewConfiguration.touchSlop

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == downId }
                                    if (change == null || !change.pressed) {
                                        break
                                    }

                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y

                                    if (!isDragging) {
                                        totalDx += dx
                                        if (abs(totalDx) > touchSlop) {
                                            if (abs(totalDx) > abs(dy)) {
                                                isDragging = true
                                                change.consume()
                                                marqueeOffset = (marqueeOffset - dx).coerceAtLeast(0f)
                                            } else {
                                                break
                                            }
                                        }
                                    } else {
                                        change.consume()
                                        marqueeOffset = (marqueeOffset - dx).coerceAtLeast(0f)
                                    }
                                }
                            } finally {
                                isUserInteracting = false
                            }
                        }
                    }
                },
            horizontalAlignment = Alignment.Start
        ) {
            // Line 1: Song Title
            if (!isTitleLong) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                ) {
                    titleContent(true)
                }
            } else {
                val offsetInCycle = if (cycle > 0f) ((marqueeOffset % cycle) + cycle) % cycle else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    // Copia 1
                    Box(
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative((-offsetInCycle).roundToInt(), 0)
                            }
                        }
                    ) {
                        titleContent(true)
                    }

                    // Copia 2
                    Box(
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative((-offsetInCycle + cycle).roundToInt(), 0)
                            }
                        }
                    ) {
                        titleContent(false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Line 2: Artist & Audio Badges Row
            if (!isArtistLong) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                ) {
                    artistRowContent(true)
                }
            } else {
                val offsetInCycle = if (cycle > 0f) ((marqueeOffset % cycle) + cycle) % cycle else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    // Copia 1
                    Box(
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative((-offsetInCycle).roundToInt(), 0)
                            }
                        }
                    ) {
                        artistRowContent(true)
                    }

                    // Copia 2
                    Box(
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                            layout(layoutWidth, placeable.height) {
                                placeable.placeRelative((-offsetInCycle + cycle).roundToInt(), 0)
                            }
                        }
                    ) {
                        artistRowContent(false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onMinimize: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRefreshSongs: (() -> Unit)? = null,
    onSyncFavorite: ((Long, Boolean) -> Unit)? = null,
    showWaveform: Boolean,
    onToggleWaveform: () -> Unit,
    visualizerData: FloatArray = remember { FloatArray(0) },
    coverShape: Int,
    coverScale: Float,
    coverSpin: Boolean,
    coverVinylEffect: Boolean,
    controlsIconStyle: Int,
    isControlsFilled: Boolean,
    useCustomControlsColor: Boolean,
    controlsColorPalette: Int,
    onShowLyrics: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }


    val activity = context as? Activity

    var isGesturesEnabled by remember { mutableStateOf(settingsManager.isGesturesEnabled) }
    var swipeUpAction by remember { mutableIntStateOf(settingsManager.swipeUpAction) }
    var useAmoledPitchBlack by remember { mutableStateOf(settingsManager.useAmoledPitchBlack) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isGesturesEnabled = settingsManager.isGesturesEnabled
                swipeUpAction = settingsManager.swipeUpAction
                useAmoledPitchBlack = settingsManager.useAmoledPitchBlack
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isCinematic = settingsManager.isCinematicPlayerEnabled

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(isCinematic, isLandscape) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isCinematic || isLandscape) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose {
            if (isCinematic || isLandscape) {
                val window = activity?.window
                if (window != null) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
    }

    val playbackManager = remember { PlaybackManager.getInstance(context) }
    LaunchedEffect(song.id) {
        playbackManager.preloadSurroundingArtwork()
    }
    val liveVisualizerData by playbackManager.visualizerData.collectAsState()
    val effectiveVisualizerData = if (visualizerData.isNotEmpty()) visualizerData else liveVisualizerData

    val lowResourceVisuals = settingsManager.isLowResourceVisualsActive(context)
    val ultraSaverActive = settingsManager.isUltraSaverActive(context)

    DisposableEffect(isPlaying, showWaveform, lowResourceVisuals) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasAudioPermission && isPlaying && !lowResourceVisuals) {
            playbackManager.startVisualizer()
        }
        onDispose {
            if (!playbackManager.isMiniPlayerVisualizerEnabled || lowResourceVisuals) {
                playbackManager.stopVisualizer()
            }
        }
    }

    val sheetPeekHeight = 0.dp
    val sheetFullHeight = 0.dp

    var showQueueSheet by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistInPlayer by remember { mutableStateOf(false) }
    var showVisualizerSettings by remember { mutableStateOf(false) }
    var showCustomRepeatDialog by remember { mutableStateOf(false) }


    val density = LocalDensity.current
    val peekHeightPx = with(density) { sheetPeekHeight.toPx() }
    val fullHeightPx = with(density) { sheetFullHeight.toPx() }


    val scale: Float
    val orbitX: Float
    val orbitY: Float
    if (isCinematic && isPlaying && !lowResourceVisuals) {
        val infiniteTransition = rememberInfiniteTransition(label = "CoverAnimation")
        val animScale by infiniteTransition.animateFloat(
            initialValue = 1.08f,
            targetValue = 1.24f,
            animationSpec = infiniteRepeatable(
                animation = tween(26000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "Scale"
        )
        val animOrbitX by infiniteTransition.animateFloat(
            initialValue = -0.04f,
            targetValue = 0.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(24000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbitX"
        )
        val animOrbitY by infiniteTransition.animateFloat(
            initialValue = -0.035f,
            targetValue = 0.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(30000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbitY"
        )
        scale = animScale
        orbitX = animOrbitX
        orbitY = animOrbitY
    } else {
        scale = 1.04f
        orbitX = 0f
        orbitY = 0f
    }

    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val isAmoled = isDarkTheme && useAmoledPitchBlack

    val hasBlurBackground = !lowResourceVisuals && settingsManager.isBlurEnabled &&
        (if (isCinematic) settingsManager.isBlurCinematicMode
        else if (isDarkTheme) settingsManager.isBlurDarkMode else settingsManager.isBlurLightMode)
    val useBlurControls = hasBlurBackground && settingsManager.isBlurControlsEnabled

    val blurContainerColor = if (isDarkTheme) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.35f)
    val blurPlayContainerColor = if (isDarkTheme) Color.Black.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.50f)

    val spinRotation: Float = if (coverShape == 2 && coverSpin && isPlaying && !lowResourceVisuals) {
        val infiniteSpinTransition = rememberInfiniteTransition(label = "PlayerCoverSpin")
        val rot by infiniteSpinTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinAnimation"
        )
        rot
    } else 0f

    // Animación de rebote (bounce) en la carátula al pasar o regresar de canción
    val coroutineScope = rememberCoroutineScope()
    val coverBounceScale = remember { Animatable(1f) }
    var lastSongId by remember { mutableStateOf<Long?>(null) }
    var lastBounceTime by remember { mutableLongStateOf(0L) }

    val triggerCoverBounce: () -> Unit = remember(coroutineScope, ultraSaverActive, lowResourceVisuals) {
        {
            if (!ultraSaverActive) {
                val now = System.currentTimeMillis()
                if (now - lastBounceTime > 150) {
                    lastBounceTime = now
                    coroutineScope.launch {
                        coverBounceScale.animateTo(
                            targetValue = 0.94f,
                            animationSpec = tween(durationMillis = if (lowResourceVisuals) 60 else 75, easing = FastOutSlowInEasing)
                        )
                        coverBounceScale.animateTo(
                            targetValue = 1f,
                            animationSpec = if (lowResourceVisuals) {
                                tween(durationMillis = 110, easing = FastOutSlowInEasing)
                            } else {
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(song.id) {
        if (lastSongId == null) {
            lastSongId = song.id
            return@LaunchedEffect
        }
        if (lastSongId != song.id) {
            lastSongId = song.id
            triggerCoverBounce()
        }
    }

    val handleNext = remember(onNext, triggerCoverBounce) {
        {
            triggerCoverBounce()
            onNext()
        }
    }

    val handlePrevious = remember(onPrevious, triggerCoverBounce) {
        {
            triggerCoverBounce()
            onPrevious()
        }
    }

    var showAudioDetailsSheet by remember { mutableStateOf(false) }
    var runtimeSampleRate by remember(song.id) { mutableStateOf(song.sampleRate) }
    var runtimeBitDepth by remember(song.id) { mutableStateOf(song.bitDepth) }
    var runtimeBitrate by remember(song.id) { mutableStateOf(song.bitrate) }

    val playingFromContext = remember(song.id, playbackManager.activePlaylistId, playbackManager.activePlaylistName, playbackManager.activeCategory) {
        when {
            playbackManager.activePlaylistId == -300L -> context.getString(R.string.playing_from_search)
            !playbackManager.activePlaylistName.isNullOrBlank() -> playbackManager.activePlaylistName!!
            playbackManager.activeCategory == "ALL" -> context.getString(R.string.tab_all)
            playbackManager.activeCategory == "FAVORITES" -> context.getString(R.string.tab_favorites)
            playbackManager.activeCategory == "RESUME" -> context.getString(R.string.tab_resume)
            playbackManager.activeCategory == "ALBUMS" -> song.album.ifBlank { context.getString(R.string.tab_albums_real) }
            playbackManager.activeCategory == "ARTISTS" -> song.artist.ifBlank { context.getString(R.string.tab_artists) }
            playbackManager.activeCategory == "FOLDERS" -> song.folderName.ifBlank { context.getString(R.string.tab_folders) }
            playbackManager.activeCategory == "PLAYLISTS" -> context.getString(R.string.playlists)
            playbackManager.activeCategory == "MIXES" -> context.getString(R.string.tab_mixes)
            playbackManager.activeCategory == "GENRES" -> song.genre ?: context.getString(R.string.tab_genres)
            song.album.isNotBlank() -> song.album
            else -> context.getString(R.string.tab_all)
        }
    }

    LaunchedEffect(song.id, song.path) {
        if ((runtimeSampleRate == null || runtimeBitDepth == null || runtimeBitrate == null) && song.path.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(song.path)
                    if (file.exists()) {
                        val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                        val header = audioFile.audioHeader
                        if (runtimeSampleRate == null) runtimeSampleRate = header.sampleRateAsNumber
                        if (runtimeBitDepth == null) runtimeBitDepth = header.bitsPerSample
                        if (runtimeBitrate == null) runtimeBitrate = (header.bitRateAsNumber * 1000).toInt()
                    }
                } catch (_: Exception) {
                    try {
                        val extractor = android.media.MediaExtractor()
                        extractor.setDataSource(song.path)
                        for (i in 0 until extractor.trackCount) {
                            val format = extractor.getTrackFormat(i)
                            val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                            if (mime?.startsWith("audio/") == true) {
                                if (runtimeSampleRate == null && format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                    runtimeSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (runtimeBitrate == null && format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
                                    runtimeBitrate = format.getInteger(android.media.MediaFormat.KEY_BIT_RATE)
                                }
                                break
                            }
                        }
                        extractor.release()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (!isCinematic && hasBlurBackground) {
            Crossfade(
                targetState = song,
                animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
                label = "FullPlayerBlurCrossfade"
            ) { targetSong ->
                val blurRequest = remember(targetSong.id, targetSong.coverUrl) {
                    ImageRequest.Builder(context)
                        .data(targetSong.coverUrl ?: targetSong.uri)
                        .size(200, 200)
                        .fallback(R.drawable.ic_artwork_fallback)
                        .error(R.drawable.ic_artwork_fallback)
                        .build()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = blurRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(40.dp)
                            .alpha(if (isDarkTheme) 0.2f else 0.35f),
                        contentScale = ContentScale.Crop
                    )
                    if (!isDarkTheme) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f))
                        )
                    }
                }
            }
        }

        val crossfadeMs = when {
            ultraSaverActive -> 0
            lowResourceVisuals -> 140
            else -> 320
        }

        if (isCinematic) {
            val cinematicTransform: @Composable (Modifier) -> Modifier = { mod ->
                mod.clipToBounds().graphicsLayer {
                    val dim = size.width.coerceAtMost(size.height)
                    translationX = orbitX * dim
                    translationY = orbitY * dim
                    scaleX = scale
                    scaleY = scale
                }
            }

            Crossfade(
                targetState = song,
                animationSpec = tween(crossfadeMs, easing = FastOutSlowInEasing),
                label = "CinematicArtCrossfade"
            ) { targetSong ->
                val request = remember(targetSong.id, targetSong.coverUrl, lowResourceVisuals) {
                    ImageRequest.Builder(context)
                        .data(targetSong.coverUrl ?: targetSong.uri)
                        .size(if (lowResourceVisuals) 420 else 768)
                        .crossfade(!lowResourceVisuals)
                        .fallback(R.drawable.ic_artwork_fallback)
                        .error(R.drawable.ic_artwork_fallback)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = cinematicTransform(Modifier.fillMaxSize()),
                    contentScale = ContentScale.Crop
                )
            }

            if (hasBlurBackground) {
                val blurGradientBrush = if (isLandscape) {
                    Brush.horizontalGradient(
                        0.00f to Color.Transparent,
                        0.32f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        0.58f to Color.Black.copy(alpha = 0.68f),
                        0.74f to Color.Black.copy(alpha = 0.88f),
                        1.00f to Color.Black.copy(alpha = 0.96f)
                    )
                } else {
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.25f),
                        0.14f to Color.Transparent,
                        0.36f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.36f),
                        0.62f to Color.Black.copy(alpha = 0.72f),
                        0.78f to Color.Black.copy(alpha = 0.90f),
                        1.00f to Color.Black.copy(alpha = 0.96f)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = blurGradientBrush, blendMode = BlendMode.DstIn)
                        }
                ) {
                    Crossfade(
                        targetState = song,
                        animationSpec = tween(crossfadeMs, easing = LinearOutSlowInEasing),
                        label = "CinematicBlurCrossfade"
                    ) { targetSong ->
                        val request = remember(targetSong.id, targetSong.coverUrl) {
                            ImageRequest.Builder(context)
                                .data(targetSong.coverUrl ?: targetSong.uri)
                                .size(256, 256)
                                .crossfade(true)
                                .fallback(R.drawable.ic_artwork_fallback)
                                .error(R.drawable.ic_artwork_fallback)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            modifier = cinematicTransform(
                                Modifier
                                    .fillMaxSize()
                                    .blur(64.dp)
                            ),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                val surf = MaterialTheme.colorScheme.surface
                val mAlpha = if (isDarkTheme) 0.72f else 0.52f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isLandscape) {
                                Brush.horizontalGradient(
                                    0.00f to surf.copy(alpha = 0.18f),
                                    0.28f to Color.Transparent,
                                    0.44f to surf.copy(alpha = mAlpha * 0.45f),
                                    0.54f to surf.copy(alpha = mAlpha * 0.88f),
                                    0.64f to surf,
                                    1.00f to surf
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.00f to surf.copy(alpha = 0.34f),
                                    0.12f to Color.Transparent,
                                    0.30f to surf.copy(alpha = mAlpha * 0.25f),
                                    0.42f to surf.copy(alpha = mAlpha * 0.65f),
                                    0.52f to surf.copy(alpha = mAlpha + (1f - mAlpha) * 0.60f),
                                    0.60f to surf,
                                    1.00f to surf
                                )
                            }
                        )
                )
            }
        }

        val dynamicPrimaryColor = if (useCustomControlsColor && controlsColorPalette != 0) {
            when (controlsColorPalette) {
                1 -> if (isDarkTheme) Color(0xFFFFB4AA) else Color(0xFFB04B38)
                2 -> if (isDarkTheme) Color(0xFF9FD3B1) else Color(0xFF386B52)
                3 -> if (isDarkTheme) Color(0xFF99CCEA) else Color(0xFF2E6580)
                4 -> if (isDarkTheme) Color(0xFFE8B5D9) else Color(0xFF854B75)
                5 -> if (isDarkTheme) Color(0xFFFCBC43) else Color(0xFF825500)
                else -> MaterialTheme.colorScheme.primary
            }
        } else {
            MaterialTheme.colorScheme.primary
        }

        val playingFromHeaderColor = if (hasBlurBackground) {
            Color.White.copy(alpha = 0.70f)
        } else {
            dynamicPrimaryColor.copy(alpha = 0.75f)
        }

        val playingFromTextColor = if (hasBlurBackground) {
            Color.White
        } else {
            dynamicPrimaryColor
        }

        val topBarBtnBg = if (useBlurControls || hasBlurBackground) {
            blurContainerColor
        } else if (useCustomControlsColor) {
            dynamicPrimaryColor.copy(alpha = 0.16f)
        } else if (isAmoled) {
            Color(0xFF222222)
        } else if (isDarkTheme) {
            Color.Black.copy(alpha = 0.40f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        }

        val topBarBtnTint = if (useBlurControls || hasBlurBackground) {
            Color.White
        } else if (useCustomControlsColor) {
            dynamicPrimaryColor
        } else {
            MaterialTheme.colorScheme.primary
        }

        val eqIconTint = if (playbackManager.isEqEnabled) {
            topBarBtnTint
        } else {
            if (useBlurControls || hasBlurBackground) {
                Color.White.copy(alpha = 0.5f)
            } else if (useCustomControlsColor) {
                dynamicPrimaryColor.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }
        }

        val coverSection: @Composable (Boolean) -> Unit = { useTwoPane ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = if (useTwoPane) Modifier.fillMaxHeight() else Modifier.fillMaxWidth(),
                verticalArrangement = if (useTwoPane) Arrangement.Center else Arrangement.Top
            ) {
                var playingFromStep by remember(song.id, playingFromContext) { mutableIntStateOf(if (ultraSaverActive) 1 else 0) }

                LaunchedEffect(song.id, playingFromContext, ultraSaverActive) {
                    if (ultraSaverActive) {
                        playingFromStep = 1
                        return@LaunchedEffect
                    }
                    while (isActive) {
                        playingFromStep = 0
                        delay(2600)
                        playingFromStep = 1
                        delay(5500)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (useTwoPane) 8.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minimize / Collapse Player button (48.dp M3 touch target)
                    Surface(
                        onClick = onMinimize,
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                        color = topBarBtnBg,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_minimize_button")
                            .bounceClick()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = topBarBtnTint,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Add to Playlist button: paired pill container (48.dp touch target)
                    Surface(
                        onClick = { showAddToPlaylistInPlayer = true },
                        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                        color = topBarBtnBg,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_add_playlist_button")
                            .bounceClick()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.add_to_playlist),
                                tint = topBarBtnTint,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Animated Playing From "x" text: aligned to the right of the back button, WITHOUT container/background
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        AnimatedContent(
                            targetState = playingFromStep,
                            transitionSpec = {
                                if (targetState == 1) {
                                    (slideInVertically { height -> height / 2 } + fadeIn(tween(260)))
                                        .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(220)))
                                } else {
                                    (slideInVertically { height -> -height / 2 } + fadeIn(tween(260)))
                                        .togetherWith(slideOutVertically { height -> height / 2 } + fadeOut(tween(220)))
                                }
                            },
                            label = "PlayingFromFlipTransition"
                        ) { step ->
                            if (step == 0) {
                                Text(
                                    text = stringResource(R.string.playing_from_header),
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = playingFromHeaderColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = playingFromContext,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = playingFromTextColor,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee()
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Equalizer Button
                        Surface(
                            onClick = {
                                val eqIntent = Intent(context, EqualizerActivity::class.java)
                                context.startActivity(eqIntent)
                            },
                            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 5.dp, bottomEnd = 5.dp),
                            color = topBarBtnBg,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_equalizer_button")
                                .bounceClick()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = stringResource(R.string.eq_title),
                                    tint = eqIconTint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // 2. Extra Options BottomSheet Button
                        Surface(
                            onClick = { showOptionsSheet = true },
                            shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                            color = topBarBtnBg,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_output_options_button")
                                .bounceClick()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = playbackManager.currentOutputIcon,
                                    contentDescription = playbackManager.currentOutputName,
                                    tint = topBarBtnTint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                val coverModifier = if (useTwoPane) {
                    Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .scale(coverScale)
                        .songSwipeGestures(
                            enabled = isGesturesEnabled,
                            onNext = handleNext,
                            onPrevious = handlePrevious
                        )
                } else {
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .scale(coverScale)
                        .songSwipeGestures(
                            enabled = isGesturesEnabled,
                            onNext = handleNext,
                            onPrevious = handlePrevious
                        )
                }

                if (isCinematic) {
                    Box(
                        modifier = coverModifier,
                    )
                } else {
                    val auraColor = dynamicPrimaryColor.copy(alpha = if (isDarkTheme) 0.18f else 0.12f)
                    Box(
                        modifier = coverModifier
                            .drawBehind {
                                if (!hasBlurBackground) {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(auraColor, Color.Transparent),
                                            radius = size.minDimension * 0.62f
                                        )
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = coverBounceScale.value
                                    scaleY = coverBounceScale.value
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = song,
                                animationSpec = tween(durationMillis = crossfadeMs, easing = LinearOutSlowInEasing),
                                label = "FullPlayerCoverCrossfade"
                            ) { targetSong ->
                                if (coverShape == 2 && coverVinylEffect) {
                                    VinylRecordAsyncCover(
                                        model = targetSong.coverUrl ?: targetSong.uri,
                                        rotation = if (coverSpin && isPlaying) spinRotation else 0f,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    val activeShape = when (coverShape) {
                                        1 -> RoundedCornerShape(8.dp)
                                        2 -> CircleShape
                                        else -> RoundedCornerShape(28.dp)
                                    }
                                    Surface(
                                        shape = activeShape,
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
                                        ),
                                        shadowElevation = if (ultraSaverActive) 0.dp else 12.dp,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .rotate(if (coverShape == 2 && coverSpin && isPlaying) spinRotation else 0f),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        tonalElevation = 8.dp
                                    ) {
                                        SongCoverImage(
                                            coverUrl = targetSong.coverUrl ?: targetSong.uri,
                                            contentDescription = targetSong.title,
                                            modifier = Modifier.fillMaxSize(),
                                            shape = activeShape,
                                            iconScale = 0.68f
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val controlsSection: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                key(song.id) {
                    UnifiedMarqueeHeader(
                        song = song,
                        useBlurControls = useBlurControls,
                        blurContainerColor = blurContainerColor,
                        isAmoled = isAmoled,
                        isDarkTheme = isDarkTheme,
                        settingsManager = settingsManager,
                        runtimeSampleRate = runtimeSampleRate,
                        runtimeBitDepth = runtimeBitDepth,
                        runtimeBitrate = runtimeBitrate,
                        onArtistClick = onArtistClick,
                        onAudioBadgesClick = { showAudioDetailsSheet = true },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    )
                }

                val pillBg = if (useBlurControls) blurContainerColor else if (isAmoled) Color(0xFF222222) else if (isDarkTheme) Color.Black.copy(alpha = 0.40f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                val hasLyrics = playbackManager.currentLyrics != null
                val lyricsIconColor = if (hasLyrics) {
                    if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary
                } else {
                    if (useBlurControls) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onShowLyrics() },
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                        color = pillBg,
                        modifier = Modifier.size(48.dp).bounceClick()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lyrics,
                                contentDescription = stringResource(R.string.option_lyrics),
                                tint = lyricsIconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            playbackManager.toggleFavorite { updatedSong ->
                                onSyncFavorite?.invoke(updatedSong.id, updatedSong.isFavorite)
                            }
                        },
                        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                        color = pillBg,
                        modifier = Modifier.size(48.dp).bounceClick()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.option_favorite),
                                tint = if (song.isFavorite) {
                                    if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary
                                } else {
                                    if (useBlurControls) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Column {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val targetAmplitude = if (isPlaying && !lowResourceVisuals) 1f else 0f
                    val amplitudeLambda: (Float) -> Float = remember(targetAmplitude) { { _: Float -> targetAmplitude } }

                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary,
                        trackColor = if (useBlurControls) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant,
                        amplitude = amplitudeLambda
                    )

                    val rotation = if (isPlaying && !lowResourceVisuals) {
                        val thumbInfinite = rememberInfiniteTransition(label = "thumbRotation")
                        val rot by thumbInfinite.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "thumbRotation"
                        )
                        rot
                    } else 0f

                    val progressSliderState = remember { SliderState(progress.coerceIn(0f, 1f)) }
                    LaunchedEffect(progress) {
                        progressSliderState.value = progress.coerceIn(0f, 1f)
                    }

                    Slider(
                        state = progressSliderState,
                        onValueChange = onProgressChange,
                        modifier = Modifier.fillMaxWidth(),
                        thumb = { _ ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .graphicsLayer {
                                        rotationZ = rotation
                                    }
                                    .background(color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(5.dp))
                            )
                        },
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .padding(top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (useBlurControls) blurContainerColor else if (isAmoled) Color(0xFF222222) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(percent = 50)
                    ) {
                        Text(
                            text = formatDuration((song.duration * progress).toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        color = if (useBlurControls) blurContainerColor else if (isAmoled) Color(0xFF222222) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(percent = 50)
                    ) {
                        Text(
                            text = formatDuration(song.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            val activePrimary = getControlsPrimaryColor(useCustomControlsColor, controlsColorPalette)
            val activeContainerColor = if (useBlurControls) {
                blurContainerColor
            } else if (useCustomControlsColor) {
                activePrimary.copy(alpha = 0.2f)
            } else if (isAmoled) {
                Color(0xFF222222)
            } else {
                if (isDarkTheme) Color.Black.copy(alpha = 0.40f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            }
            val activeIconTint = if (useBlurControls) {
                Color.White
            } else if (useCustomControlsColor) {
                activePrimary
            } else {
                MaterialTheme.colorScheme.primary
            }

            val playBgColor = if (useBlurControls) {
                blurPlayContainerColor
            } else if (useCustomControlsColor) {
                activePrimary
            } else {
                MaterialTheme.colorScheme.primary
            }

            val playIconTint = if (useBlurControls) {
                Color.White
            } else if (useCustomControlsColor) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onPrimary
            }

            var showPlayStateLabel by remember { mutableStateOf(false) }
            var isFirstPlayComposition by remember { mutableStateOf(true) }
            var labelTrigger by remember { mutableIntStateOf(0) }

            LaunchedEffect(isPlaying, labelTrigger) {
                if (isFirstPlayComposition) {
                    isFirstPlayComposition = false
                    return@LaunchedEffect
                }
                showPlayStateLabel = true
                delay(1300L)
                showPlayStateLabel = false
            }

            val playButtonWidth by animateDpAsState(
                targetValue = if (showPlayStateLabel) 156.dp else 96.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "PlayWidthAnim"
            )

            val playShape = RoundedCornerShape(30.dp)
            val skipShape = RoundedCornerShape(26.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = handlePrevious,
                    shape = skipShape,
                    color = activeContainerColor,
                    modifier = Modifier
                        .size(68.dp)
                        .offset {
                            val halfPlay = playButtonWidth.toPx() / 2f
                            val gap = 14.dp.toPx()
                            val halfSkip = 34.dp.toPx()
                            IntOffset(x = -(halfPlay + gap + halfSkip).roundToInt(), y = 0)
                        }
                        .bounceClick()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ReusableSkipIcon(
                            isNext = false,
                            controlsIconStyle = controlsIconStyle,
                            isControlsFilled = isControlsFilled,
                            tint = activeIconTint,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                @OptIn(ExperimentalAnimationGraphicsApi::class)
                Surface(
                    onClick = {
                        labelTrigger++
                        onTogglePlay()
                    },
                    shape = playShape,
                    color = playBgColor,
                    modifier = Modifier
                        .height(68.dp)
                        .width(playButtonWidth)
                        .bounceClick()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            val avd = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause_morph)
                            Icon(
                                painter = rememberAnimatedVectorPainter(avd, atEnd = isPlaying),
                                contentDescription = stringResource(R.string.cd_play_pause),
                                modifier = Modifier.size(38.dp),
                                tint = playIconTint
                            )

                            AnimatedVisibility(
                                visible = showPlayStateLabel,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                        expandHorizontally(
                                            expandFrom = Alignment.CenterHorizontally,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        ) +
                                        scaleIn(
                                            initialScale = 0.8f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        ),
                                exit = fadeOut(animationSpec = tween(150)) +
                                       shrinkHorizontally(
                                           shrinkTowards = Alignment.CenterHorizontally,
                                           animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                       )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(if (isPlaying) R.string.play_state_play else R.string.play_state_pause),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = playIconTint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    onClick = handleNext,
                    shape = skipShape,
                    color = activeContainerColor,
                    modifier = Modifier
                        .size(68.dp)
                        .offset {
                            val halfPlay = playButtonWidth.toPx() / 2f
                            val gap = 14.dp.toPx()
                            val halfSkip = 34.dp.toPx()
                            IntOffset(x = (halfPlay + gap + halfSkip).roundToInt(), y = 0)
                        }
                        .bounceClick()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ReusableSkipIcon(
                            isNext = true,
                            controlsIconStyle = controlsIconStyle,
                            isControlsFilled = isControlsFilled,
                            tint = activeIconTint,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 12.dp))

            // Options Row (Shuffle, Repeat, Speed, Queue, Add to Playlist)
            val activeAccent = if (useBlurControls) {
                Color.White
            } else if (useCustomControlsColor) {
                activePrimary
            } else {
                MaterialTheme.colorScheme.primary
            }

            val defaultIconTint = if (useBlurControls) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

            val isShuffling = playbackManager.isShuffle
            val shuffleTint by animateColorAsState(
                targetValue = if (isShuffling) activeAccent else defaultIconTint,
                label = "shuffleTint"
            )

            val isRepeatActive = playbackManager.repeatMode > 0
            val repeatIcon = when (playbackManager.repeatMode) {
                1 -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            }
            val repeatTint by animateColorAsState(
                targetValue = if (isRepeatActive) activeAccent else defaultIconTint,
                label = "repeatTint"
            )

            val dockPillBg = if (useBlurControls) {
                blurContainerColor
            } else if (isAmoled) {
                Color(0xFF1A1A1A)
            } else if (isDarkTheme) {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            }

            val activeUtilityChipBg = if (useBlurControls) {
                Color.White.copy(alpha = 0.22f)
            } else {
                activeAccent.copy(alpha = 0.16f)
            }

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = dockPillBg,
                border = BorderStroke(
                    width = 0.8.dp,
                    color = if (useBlurControls || isDarkTheme) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Shuffle (48.dp accessible target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_shuffle_button")
                            .bounceClick(0.92f)
                            .clip(CircleShape)
                            .background(if (isShuffling) activeUtilityChipBg else Color.Transparent)
                            .clickable { playbackManager.toggleShuffle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = stringResource(R.string.option_shuffle),
                            tint = shuffleTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // 2. Repeat (48.dp accessible target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_repeat_button")
                            .bounceClick(0.92f)
                            .clip(CircleShape)
                            .background(if (isRepeatActive) activeUtilityChipBg else Color.Transparent)
                            .combinedClickable(
                                onClick = { playbackManager.toggleRepeatMode() },
                                onLongClick = { showCustomRepeatDialog = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = stringResource(R.string.option_repeat),
                            tint = repeatTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // 3. Audio & LUFS Telemetry Sheet (48.dp accessible target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_audio_telemetry_button")
                            .bounceClick(0.92f)
                            .clip(CircleShape)
                            .clickable { showAudioDetailsSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.HighQuality,
                            contentDescription = stringResource(R.string.audio_quality_details),
                            tint = defaultIconTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // 4. Queue (48.dp accessible target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_queue_button")
                            .bounceClick(0.92f)
                            .clip(CircleShape)
                            .clickable { showQueueSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = stringResource(R.string.player_queue),
                            tint = defaultIconTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // 5. Speed / Sleep Timer / Options Sheet (48.dp accessible target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_more_options_button")
                            .bounceClick(0.92f)
                            .clip(CircleShape)
                            .clickable { showOptionsSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.player_options),
                            tint = defaultIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var totalDragY = 0f
                    var gestureConsumed = false
                    detectDragGestures(
                        onDragStart = {
                            totalDragY = 0f
                            gestureConsumed = false
                        },
                        onDrag = { _, dragAmount ->
                            if (!gestureConsumed) {
                                totalDragY += dragAmount.y
                                val absY = abs(totalDragY)
                                val absX = abs(dragAmount.x)
                                if (absY > 60 && absY > absX * 1.5f) {
                                    if (totalDragY > 0) {
                                        onMinimize()
                                    } else {
                                        when (swipeUpAction) {
                                            1 -> showQueueSheet = true
                                            2 -> {
                                                val eqIntent = android.content.Intent(context, com.demonlab.lune.ui.activities.EqualizerActivity::class.java)
                                                context.startActivity(eqIntent)
                                            }
                                            3 -> showAddToPlaylistInPlayer = true
                                            4 -> {
                                                try {
                                                    val file = File(song.path)
                                                    if (file.exists()) {
                                                        val contentUri = FileProvider.getUriForFile(
                                                            context,
                                                            "com.demonlab.lune.fileprovider",
                                                            file
                                                        )
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "audio/*"
                                                            putExtra(Intent.EXTRA_STREAM, contentUri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.option_share)))
                                                    }
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    }
                                    gestureConsumed = true
                                }
                            }
                        }
                    )
                }
        ) {
            val useTwoPaneLayout = maxWidth > maxHeight * 1.04f ||
                (maxWidth >= 640.dp && maxHeight < 680.dp) ||
                maxHeight < 480.dp
            val isCompactWidth = maxWidth < 360.dp
            val isCompactHeight = maxHeight < 640.dp

            if (showWaveform && !lowResourceVisuals) {
                WaveformVisualizer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(80.dp)
                        .fillMaxWidth()
                        .alpha(0.6f),
                    magnitudes = effectiveVisualizerData,
                    color = if (useBlurControls) Color.White else MaterialTheme.colorScheme.primary
                )
            }

            if (useTwoPaneLayout) {
                val containerWidth = maxWidth
                val sidePad = when {
                    containerWidth >= 900.dp -> 48.dp
                    containerWidth >= 600.dp -> 28.dp
                    else -> 16.dp
                }
                val vertPad = if (maxHeight < 440.dp) 8.dp else 16.dp
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = vertPad, bottom = vertPad, start = sidePad, end = sidePad),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = if (containerWidth >= 700.dp) 24.dp else 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        coverSection(true)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        controlsSection()
                    }
                }
            } else {
                val horizPad = if (isCompactWidth) 14.dp else 24.dp
                val topPad = if (isCompactHeight) 20.dp else 42.dp
                val botPad = if (isCompactHeight) 12.dp else 24.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .align(Alignment.Center)
                        .padding(top = topPad, bottom = botPad, start = horizPad, end = horizPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        coverSection(false)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        controlsSection()
                    }
                }
            }
        }

        if (showQueueSheet) {
            QueueBottomSheet(
                playbackManager = playbackManager,
                onDismiss = { showQueueSheet = false }
            )
        }

        if (showOptionsSheet) {
            PlayerOptionsBottomSheet(
                playbackManager = playbackManager,
                showWaveform = showWaveform,
                onToggleWaveform = onToggleWaveform,
                onRefreshSongs = onRefreshSongs,
                onSyncFavorite = onSyncFavorite,
                onDismiss = { showOptionsSheet = false },
                onAddToPlaylistClick = {
                    showOptionsSheet = false
                    showAddToPlaylistInPlayer = true
                },
                onShowVisualizerSettings = {
                    showOptionsSheet = false
                    showVisualizerSettings = true
                },
                onShowLyrics = {
                    showOptionsSheet = false
                    onShowLyrics()
                },
                onGoToAlbumClick = if (onAlbumClick != null) {
                    {
                        val albumName = (playbackManager.currentSong ?: song).album
                        onAlbumClick(albumName)
                    }
                } else null
            )
        }

        if (showAddToPlaylistInPlayer) {
            val currentSongState = playbackManager.currentSong
            if (currentSongState != null) {
                val musicViewModel: MusicViewModel = viewModel()
                LaunchedEffect(Unit) {
                    musicViewModel.loadPlaylists()
                }
                AddToPlaylistDialog(
                    song = currentSongState,
                    viewModel = musicViewModel,
                    playbackManager = playbackManager,
                    onDismiss = {
                        showAddToPlaylistInPlayer = false
                        playbackManager.checkPlaylistStatus()
                        onRefreshSongs?.invoke()
                    }
                )
            }
        }

        if (showVisualizerSettings) {
            VisualizerSettingsBottomSheet(
                playbackManager = playbackManager,
                onClose = { showVisualizerSettings = false },
                onRequestPermission = onRequestAudioPermission
            )
        }

        if (showAudioDetailsSheet) {
            AudioDetailsBottomSheet(
                song = song,
                sampleRate = runtimeSampleRate,
                bitDepth = runtimeBitDepth,
                bitrate = runtimeBitrate,
                onDismiss = { showAudioDetailsSheet = false }
            )
        }

        if (showCustomRepeatDialog) {
            CustomRepeatDialog(
                playbackManager = playbackManager,
                currentSong = song,
                onDismiss = { showCustomRepeatDialog = false }
            )
        }
    }
}

@Composable
fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    useBlurControls: Boolean = false,
    containerColor: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = modifier.size(36.dp).bounceClick()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (useBlurControls) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun ScallopPlayPauseButtonWithProgress(
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    hasBlurBackground: Boolean,
    useCustomControlsColor: Boolean,
    activePrimary: Color,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ScallopProgress"
    )

    val scallopShape = remember { MaterialExpressiveScallopShape(lobes = 8, amplitude = 0.07f) }

    val trackColor = if (useCustomControlsColor) {
        activePrimary.copy(alpha = 0.25f)
    } else if (hasBlurBackground) {
        Color.White.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    }

    val progressColor = if (useCustomControlsColor) {
        activePrimary
    } else if (hasBlurBackground) {
        Color.White
    } else {
        MaterialTheme.colorScheme.primary
    }

    val buttonBg = if (useCustomControlsColor) {
        activePrimary
    } else if (hasBlurBackground) {
        Color.White.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    val iconTint = if (useCustomControlsColor) {
        Color.White
    } else if (hasBlurBackground) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Box(
        modifier = modifier
            .size(52.dp)
            .bounceClick(),
        contentAlignment = Alignment.Center
    ) {
        // Concentric Scalloped Progress Ring Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lobes = 8
            val amplitude = 0.07f
            val strokeWidth = 3.5.dp.toPx()
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val baseRadius = (minOf(size.width, size.height) / 2f - strokeWidth) / (1f + amplitude)
            val steps = lobes * 16
            val angleStep = (2.0 * Math.PI / steps).toFloat()

            // 1. Background full track
            val trackPath = Path()
            for (i in 0..steps) {
                val theta = i * angleStep - (Math.PI / 2.0).toFloat()
                val r = baseRadius * (1f + amplitude * cos(lobes * theta))
                val x = centerX + r * cos(theta)
                val y = centerY + r * sin(theta)
                if (i == 0) trackPath.moveTo(x, y) else trackPath.lineTo(x, y)
            }
            trackPath.close()
            drawPath(
                path = trackPath,
                color = trackColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 2. Active progress path
            if (animatedProgress > 0.005f) {
                val progressPath = Path()
                val targetSteps = (steps * animatedProgress).toInt().coerceIn(1, steps)
                val targetFraction = steps * animatedProgress

                for (i in 0..targetSteps) {
                    val theta = i * angleStep - (Math.PI / 2.0).toFloat()
                    val r = baseRadius * (1f + amplitude * cos(lobes * theta))
                    val x = centerX + r * cos(theta)
                    val y = centerY + r * sin(theta)
                    if (i == 0) progressPath.moveTo(x, y) else progressPath.lineTo(x, y)
                }

                if (targetSteps < steps) {
                    val theta = targetFraction * angleStep - (Math.PI / 2.0).toFloat()
                    val r = baseRadius * (1f + amplitude * cos(lobes * theta))
                    val x = centerX + r * cos(theta)
                    val y = centerY + r * sin(theta)
                    progressPath.lineTo(x, y)
                }

                drawPath(
                    path = progressPath,
                    color = progressColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // Inner Scalloped Button
        Surface(
            onClick = onClick,
            shape = scallopShape,
            color = buttonBg,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isPlaying) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(16.dp)
                                .clip(CircleShape)
                                .background(iconTint)
                        )
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(16.dp)
                                .clip(CircleShape)
                                .background(iconTint)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float = 0f,
    showWaveform: Boolean,
    visualizerData: FloatArray = remember { FloatArray(0) },
    currentOutputIcon: ImageVector,
    coverShape: Int,
    coverScale: Float,
    coverSpin: Boolean,
    coverVinylEffect: Boolean,
    controlsIconStyle: Int,
    isControlsFilled: Boolean,
    useCustomControlsColor: Boolean,
    controlsColorPalette: Int,
    shape: Shape = CircleShape,
    hasBlurBackground: Boolean = false,
    isDarkTheme: Boolean = false,
    onTogglePlay: () -> Unit,
    onExpand: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSearchClick: (() -> Unit)? = null,
    onScrollToCurrent: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null
) {
    val miniContext = LocalContext.current
    val miniSettings = remember { SettingsManager.getInstance(miniContext) }
    val lowResourceMini = miniSettings.isLowResourceVisualsActive(miniContext)
    val multiTagVersion by miniSettings.multiTagVersion.collectAsState()
    val multiTagConfig = remember(multiTagVersion) { miniSettings.toMultiTagConfig() }
    val displayTitle = remember(song.id, song.title, song.artist, multiTagConfig) {
        song.effectiveDisplayTitle(multiTagConfig)
    }
    val displayArtist = remember(song.id, song.title, song.artist, multiTagConfig) {
        song.parsedArtists(multiTagConfig).joinToString(", ")
    }
    val effectiveBlurMini = hasBlurBackground && !lowResourceMini
    val spinRotation = if (coverShape == 2 && coverSpin && isPlaying && !lowResourceMini) {
        val infiniteSpinTransition = rememberInfiniteTransition(label = "MiniPlayerSpin")
        val rot by infiniteSpinTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinAnimation"
        )
        rot
    } else 0f

    val blurContainerColorMini = if (isDarkTheme) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.4f)
    val activePrimary = getControlsPrimaryColor(useCustomControlsColor, controlsColorPalette)

    val pillMiniColor = if (useCustomControlsColor) {
        activePrimary.copy(alpha = 0.25f)
    } else if (effectiveBlurMini) {
        blurContainerColorMini
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val pillMiniIconTint = if (useCustomControlsColor) {
        activePrimary
    } else if (effectiveBlurMini) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Cápsula / Píldora principal interactiva (A la izquierda)
        val pillShape = CircleShape
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(pillShape)
                .songSwipeGestures(
                    enabled = true,
                    onNext = onNext,
                    onPrevious = onPrevious
                )
                .clickable { onExpand() },
            shape = pillShape,
            color = if (effectiveBlurMini) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = if (effectiveBlurMini) 0.dp else 6.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (effectiveBlurMini) {
                    Crossfade(
                        targetState = song,
                        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
                        label = "MiniPlayerBlurCrossfade"
                    ) { targetSong ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(30.dp)
                                    .alpha(if (isDarkTheme) 0.2f else 0.35f)
                            ) {
                                val miniBlurRequest = remember(targetSong.id, targetSong.coverUrl) {
                                    ImageRequest.Builder(miniContext)
                                        .data(targetSong.coverUrl ?: targetSong.uri)
                                        .size(150, 150)
                                        .build()
                                }
                                AsyncImage(
                                    model = miniBlurRequest,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            if (!isDarkTheme) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.28f))
                                )
                            }
                        }
                    }
                }

                if (showWaveform && !lowResourceMini) {
                    MiniPlayerWaveform(
                        context = miniContext,
                        passedMagnitudes = visualizerData,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Título de la canción y debajo icono de dispositivo + artista
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = displayTitle,
                            modifier = if (lowResourceMini) Modifier else Modifier.basicMarquee(),
                            color = if (effectiveBlurMini) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = currentOutputIcon,
                                contentDescription = null,
                                tint = if (effectiveBlurMini) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = displayArtist,
                                modifier = if (lowResourceMini) Modifier else Modifier.basicMarquee(),
                                color = if (effectiveBlurMini) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Controles a la derecha de la cápsula
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botón Sonando Ahora (solo visible cuando se está dentro de la lista que está sonando)
                        AnimatedVisibility(
                            visible = onScrollToCurrent != null,
                            enter = fadeIn(tween(200)) + expandHorizontally(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
                            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150)) + scaleOut(tween(150), targetScale = 0.8f)
                        ) {
                            val pulseScale = if (!lowResourceMini) {
                                val infiniteTransition = rememberInfiniteTransition(label = "ScrollPulse")
                                val p by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 1.15f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "PulseAnim"
                                )
                                p
                            } else 1f
                            Surface(
                                onClick = { onScrollToCurrent?.invoke() },
                                shape = CircleShape,
                                color = pillMiniColor,
                                modifier = Modifier
                                    .size(38.dp)
                                    .bounceClick()
                                    .scale(pulseScale)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "Scroll to current",
                                        tint = pillMiniIconTint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Botón Play/Pause con barra de progreso ondulada concéntrica Material 3 Expressive
                        ScallopPlayPauseButtonWithProgress(
                            isPlaying = isPlaying,
                            progress = progress,
                            onClick = onTogglePlay,
                            hasBlurBackground = hasBlurBackground,
                            useCustomControlsColor = useCustomControlsColor,
                            activePrimary = activePrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 2. Cover a la derecha de la píldora (siempre circular - CircleShape, al tocarlo minimiza el miniplayer)
        Box(
            modifier = Modifier
                .size(64.dp)
                .bounceClick(),
            contentAlignment = Alignment.Center
        ) {
            val isVinylActive = coverShape == 2 && coverVinylEffect
            val isSpinActive = coverShape == 2 && coverSpin && isPlaying

            Surface(
                onClick = { onMinimize?.invoke() },
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(coverScale),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 4.dp,
                shadowElevation = 2.dp
            ) {
                Crossfade(
                    targetState = song,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "MiniCoverRightCrossfade"
                ) { targetSong ->
                    if (isVinylActive) {
                        VinylRecordAsyncCover(
                            model = targetSong.coverUrl ?: targetSong.uri,
                            rotation = if (isSpinActive) spinRotation else 0f,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .rotate(if (isSpinActive) spinRotation else 0f)
                        ) {
                            SongCoverImage(
                                coverUrl = targetSong.coverUrl ?: targetSong.uri,
                                contentDescription = "Minimize player",
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                iconScale = 0.68f
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayerMinimized(
    song: Song,
    coverShape: Int,
    coverScale: Float,
    coverSpin: Boolean,
    coverVinylEffect: Boolean,
    hasBlurBackground: Boolean = false,
    isDarkTheme: Boolean = false,
    isPlaying: Boolean = false,
    onRestore: () -> Unit,
    onExpandPlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vibrator = remember(context) { context.getSafeVibrator() }
    val coroutineScope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    var hasVibrated by remember { mutableStateOf(false) }

    // Visual double-bounce hint animation when miniplayer is minimized to indicate swipe up
    LaunchedEffect(Unit) {
        delay(200L)
        offsetY.animateTo(
            targetValue = -24f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        )
        offsetY.animateTo(
            targetValue = -6f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
        )
        offsetY.animateTo(
            targetValue = -20f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
        )
        offsetY.animateTo(
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f)
        )
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = {
                hasVibrated = false
            },
            onDragEnd = {
                coroutineScope.launch {
                    val reachedLimit = hasVibrated || offsetY.value <= -50f
                    if (reachedLimit) {
                        if (onExpandPlayer != null) {
                            onExpandPlayer()
                        } else {
                            onRestore()
                        }
                    }
                    offsetY.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 350f)
                    )
                    hasVibrated = false
                }
            },
            onDragCancel = {
                coroutineScope.launch {
                    offsetY.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 350f))
                    hasVibrated = false
                }
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val rawOffset = offsetY.value + dragAmount
                val clampedOffset = if (rawOffset < -55f) {
                    -55f + (rawOffset + 55f) * 0.35f
                } else {
                    rawOffset
                }.coerceIn(-85f, 0f)

                if (clampedOffset <= -50f && !hasVibrated) {
                    hasVibrated = true
                    vibrator.triggerLightVibration(28L)
                }

                coroutineScope.launch {
                    offsetY.snapTo(clampedOffset)
                }
            }
        )
    }

    val minSettings = remember { SettingsManager.getInstance(context) }
    val lowResMin = minSettings.isLowResourceVisualsActive(context)
    val effectiveBlurMin = hasBlurBackground && !lowResMin
    val spinRotation = if (coverShape == 2 && coverSpin && isPlaying && !lowResMin) {
        val infiniteSpinTransition = rememberInfiniteTransition(label = "MiniSpin")
        val rot by infiniteSpinTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinAnim"
        )
        rot
    } else 0f

    Box(
        modifier = modifier.padding(top = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            onClick = onRestore,
            shape = CircleShape,
            color = if (effectiveBlurMin) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = if (effectiveBlurMin) 0.dp else 8.dp,
            modifier = Modifier
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .then(dragModifier)
                .size(52.dp)
                .scale(coverScale)
                .shadow(6.dp, CircleShape)
        ) {
        Box(contentAlignment = Alignment.Center) {
            if (effectiveBlurMin) {
                Crossfade(
                    targetState = song,
                    animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
                    label = "MiniCoverBlurCrossfade"
                ) { targetSong ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(25.dp)
                                .alpha(if (isDarkTheme) 0.2f else 0.35f)
                        ) {
                            val miniCtx = LocalContext.current
                            val blurRequest = remember(targetSong.id, miniCtx) {
                                ImageRequest.Builder(miniCtx)
                                    .data(targetSong.coverUrl ?: targetSong.uri)
                                    .size(150, 150)
                                    .build()
                            }
                            AsyncImage(
                                model = blurRequest,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (!isDarkTheme) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.28f))
                            )
                        }
                    }
                }
            }
            Crossfade(
                targetState = song,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "MiniPlayerCoverCrossfade"
            ) { targetSong ->
                if (coverShape == 2 && coverVinylEffect) {
                    VinylRecordAsyncCover(
                        model = targetSong.coverUrl ?: targetSong.uri,
                        rotation = if (coverSpin && isPlaying) spinRotation else 0f,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val activeShape = when (coverShape) {
                        1 -> RoundedCornerShape(0.dp)
                        2 -> CircleShape
                        else -> RoundedCornerShape(8.dp)
                    }
                    Surface(
                        shape = activeShape,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(if (coverShape == 2 && coverSpin && isPlaying) spinRotation else 0f),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        SongCoverImage(
                            coverUrl = targetSong.coverUrl ?: targetSong.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            shape = activeShape,
                            iconScale = 0.68f
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun MiniPlayerWaveform(
    context: Context,
    passedMagnitudes: FloatArray,
    color: Color
) {
    val activeMagnitudes = if (passedMagnitudes.isNotEmpty()) {
        passedMagnitudes
    } else {
        val pm = remember(context) { PlaybackManager.getInstance(context) }
        val pmData by pm.visualizerData.collectAsState()
        pmData
    }
    WaveformVisualizer(
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.3f)
            .blur(16.dp),
        magnitudes = activeMagnitudes,
        color = color
    )
}

@Composable
fun ReusableSkipIcon(
    isNext: Boolean,
    controlsIconStyle: Int,
    isControlsFilled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val visualOffset = if (controlsIconStyle > 0) {
        if (isNext) 2.dp else (-2).dp
    } else 0.dp
    val flipModifier = if (!isNext && controlsIconStyle > 0) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier
    val combinedModifier = modifier.offset(x = visualOffset).then(flipModifier)

    when (controlsIconStyle) {
        1 -> {
            val res = if (isControlsFilled) R.drawable.play_2_filled else R.drawable.play_2
            Icon(
                painter = painterResource(res),
                contentDescription = null,
                tint = tint,
                modifier = combinedModifier
            )
        }
        2 -> {
            val res = if (isControlsFilled) R.drawable.play_3_filled else R.drawable.play_3
            Icon(
                painter = painterResource(res),
                contentDescription = null,
                tint = tint,
                modifier = combinedModifier
            )
        }
        else -> {
            val vector = if (isControlsFilled) {
                if (isNext) Icons.Default.SkipNext else Icons.Default.SkipPrevious
            } else {
                if (isNext) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious
            }
            Icon(
                imageVector = vector,
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
    }
}

@Composable
fun CavaThreeDots(
    isPlaying: Boolean,
    visualizerData: FloatArray,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CavaFallbackAnim")

    val fallbackH1 by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 430, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cavaBar1"
    )
    val fallbackH2 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 310, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cavaBar2"
    )
    val fallbackH3 by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 510, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cavaBar3"
    )

    val hasVisualizerSignal = isPlaying && visualizerData.isNotEmpty() && visualizerData.any { it > 0.15f }

    val rawTarget1 = when {
        !isPlaying -> 0f
        hasVisualizerSignal -> ((visualizerData.getOrElse(4) { 0.1f } - 0.1f) / 0.8f).coerceIn(0f, 1f)
        else -> fallbackH1
    }

    val rawTarget2 = when {
        !isPlaying -> 0f
        hasVisualizerSignal -> ((visualizerData.getOrElse(14) { 0.1f } - 0.1f) / 0.8f).coerceIn(0f, 1f)
        else -> fallbackH2
    }

    val rawTarget3 = when {
        !isPlaying -> 0f
        hasVisualizerSignal -> ((visualizerData.getOrElse(24) { 0.1f } - 0.1f) / 0.8f).coerceIn(0f, 1f)
        else -> fallbackH3
    }

    val animSpec = if (hasVisualizerSignal) {
        spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    } else {
        spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }

    val h1 by animateDpAsState(
        targetValue = 3.5.dp + (11.5.dp * rawTarget1),
        animationSpec = animSpec,
        label = "cavaH1"
    )
    val h2 by animateDpAsState(
        targetValue = 3.5.dp + (15.5.dp * rawTarget2),
        animationSpec = animSpec,
        label = "cavaH2"
    )
    val h3 by animateDpAsState(
        targetValue = 3.5.dp + (9.5.dp * rawTarget3),
        animationSpec = animSpec,
        label = "cavaH3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(h1)
                .background(tint, CircleShape)
        )
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(h2)
                .background(tint, CircleShape)
        )
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(h3)
                .background(tint, CircleShape)
        )
    }
}

