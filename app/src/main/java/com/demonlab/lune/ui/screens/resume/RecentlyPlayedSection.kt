package com.demonlab.lune.ui.screens.resume

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demonlab.lune.R
import com.demonlab.lune.tools.Song
import com.demonlab.lune.ui.components.SongCoverImage
import com.demonlab.lune.ui.utils.bounceClick
import com.demonlab.lune.ui.utils.expressiveItemInteraction
import com.demonlab.lune.ui.utils.formatDuration

@Composable
fun RecentlyPlayedSection(
    recentSongs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    hasBlurBackground: Boolean = false,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Expressive Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasBlurBackground) Color.White.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.resume_recents_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    if (recentSongs.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (hasBlurBackground) Color.White.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            contentColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                text = "${recentSongs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.resume_recents_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasBlurBackground) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (recentSongs.isNotEmpty()) {
                FilledTonalButton(
                    onClick = onPlayAll,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (hasBlurBackground) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .height(34.dp)
                        .expressiveItemInteraction(scaleDown = 0.94f, alphaDown = 0.85f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.resume_recents_play_all),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (recentSongs.isEmpty()) {
            // Vibrant Expressive Empty / Starter Card
            EmptyRecentsCard(
                hasBlurBackground = hasBlurBackground,
                onStartListening = onPlayAll
            )
        } else {
            // Vibrant Expressive Carousel
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(recentSongs, key = { _, song -> "recent_${song.id}" }) { index, song ->
                    val isCurrentlyPlaying = currentSong?.id == song.id
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            initialOffsetX = { (it / 2).coerceAtMost(160) }
                        ) + scaleIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            initialScale = 0.92f
                        )
                    ) {
                        ExpressiveRecentCard(
                            song = song,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isPlaying = isPlaying,
                            hasBlurBackground = hasBlurBackground,
                            onClick = { onSongClick(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveRecentCard(
    song: Song,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    hasBlurBackground: Boolean,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val cardBorder = if (isCurrentlyPlaying) {
        BorderStroke(2.dp, primaryColor)
    } else {
        BorderStroke(
            1.dp,
            (if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.35f)
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (isCurrentlyPlaying) 8.dp else 4.dp,
        shadowElevation = if (isCurrentlyPlaying) 6.dp else 3.dp,
        border = cardBorder,
        modifier = Modifier
            .width(160.dp)
            .height(215.dp)
            .expressiveItemInteraction(scaleDown = 0.95f, alphaDown = 0.88f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full background artwork
            SongCoverImage(
                coverUrl = song.coverUrl ?: song.albumArtUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp)
            )

            // Vibrant Multi-Tone Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = if (isCurrentlyPlaying) {
                                listOf(
                                    primaryColor.copy(alpha = 0.35f),
                                    Color.Black.copy(alpha = 0.40f),
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Black.copy(alpha = 0.95f)
                                )
                            } else {
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.20f),
                                    Color.Black.copy(alpha = 0.70f),
                                    Color.Black.copy(alpha = 0.92f)
                                )
                            },
                            startY = 0f
                        )
                    )
            )

            // Top Status Badge & Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCurrentlyPlaying) {
                    Surface(
                        shape = CircleShape,
                        color = primaryColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            AnimatedEqualizerBars(
                                isPlaying = isPlaying,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "En vivo",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        contentColor = Color.White
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = formatDuration(song.duration),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.90f)
                            )
                        }
                    }
                }

                // Quick Play / Pause Button
                Surface(
                    onClick = onClick,
                    shape = CircleShape,
                    color = if (isCurrentlyPlaying) primaryColor else Color.White.copy(alpha = 0.90f),
                    contentColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.onPrimary else Color.Black,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(36.dp)
                        .bounceClick()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isCurrentlyPlaying && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom metadata container
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.82f)
                )

                if (song.album.isNotBlank() && song.album != "<unknown>") {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = primaryColor.copy(alpha = 0.90f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedEqualizerBars(
    isPlaying: Boolean,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(460, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .height(12.dp)
            .width(10.dp)
    ) {
        val scale1 = if (isPlaying) bar1 else 0.4f
        val scale2 = if (isPlaying) bar2 else 0.7f
        val scale3 = if (isPlaying) bar3 else 0.3f

        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight(scale1)
                .background(color, CircleShape)
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight(scale2)
                .background(color, CircleShape)
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight(scale3)
                .background(color, CircleShape)
        )
    }
}

@Composable
private fun EmptyRecentsCard(
    hasBlurBackground: Boolean,
    onStartListening: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (hasBlurBackground) Color.White.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (hasBlurBackground) Color.White.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.resume_recents_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.resume_recents_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasBlurBackground) Color.White.copy(alpha = 0.78f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledIconButton(
                    onClick = onStartListening,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .size(46.dp)
                        .expressiveItemInteraction(scaleDown = 0.92f, alphaDown = 0.85f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.resume_recents_play_all),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
