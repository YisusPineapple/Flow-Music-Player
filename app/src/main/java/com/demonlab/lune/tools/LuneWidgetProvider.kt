package com.demonlab.lune.tools

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.demonlab.lune.R
import com.demonlab.lune.ui.activities.Lune
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LuneWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val appCtx = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull()
        widgetScope.launch {
            try {
                pushUpdateToWidgets(appCtx, appWidgetManager, appWidgetIds)
            } catch (t: Throwable) {
                Log.w(TAG, "onUpdate error: ${t.message}")
            } finally {
                runCatching { pendingResult?.finish() }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val appCtx = context.applicationContext

        when (action) {
            ACTION_WIDGET_UPDATE -> {
                val pendingResult = runCatching { goAsync() }.getOrNull()
                widgetScope.launch {
                    try {
                        invalidateArtCache()
                        pushUpdateToAllWidgets(appCtx)
                    } catch (t: Throwable) {
                        Log.w(TAG, "WIDGET_UPDATE error: ${t.message}")
                    } finally {
                        runCatching { pendingResult?.finish() }
                    }
                }
            }
            ACTION_WIDGET_PLAY_PAUSE,
            ACTION_WIDGET_PREVIOUS,
            ACTION_WIDGET_NEXT -> {
                handleTransportBroadcast(appCtx, action)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val appCtx = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull()
        widgetScope.launch {
            try {
                pushUpdateToWidgets(appCtx, appWidgetManager, intArrayOf(appWidgetId))
            } catch (t: Throwable) {
                Log.w(TAG, "onAppWidgetOptionsChanged error: ${t.message}")
            } finally {
                runCatching { pendingResult?.finish() }
            }
        }
    }

    private fun handleTransportBroadcast(context: Context, widgetAction: String) {
        val pm = PlaybackManager.getInstance(context)
        if (pm.currentSong == null && !pm.stateRestored) {
            runCatching { pm.restorePlaybackState() }
        }

        // If no song or queue exists yet, open Flow Music Player cleanly instead of starting
        // an empty foreground service (avoids ForegroundServiceDidNotStartInTimeException).
        if (pm.currentSong == null && pm.activePlaylist.isEmpty()) {
            runCatching {
                val launchIntent = Intent(context, Lune::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(launchIntent)
            }
            return
        }

        when (widgetAction) {
            ACTION_WIDGET_PLAY_PAUSE -> {
                if (pm.isPlaying) {
                    val pauseIntent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_PAUSE
                    }
                    runCatching { context.startService(pauseIntent) }
                        .onFailure { pm.pause() }
                } else {
                    val playIntent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_PLAY
                    }
                    runCatching { ContextCompat.startForegroundService(context, playIntent) }
                        .onFailure {
                            val launchIntent = Intent(context, Lune::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            runCatching { context.startActivity(launchIntent) }
                        }
                }
            }
            ACTION_WIDGET_PREVIOUS -> {
                val prevIntent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_PREVIOUS
                }
                runCatching { ContextCompat.startForegroundService(context, prevIntent) }
            }
            ACTION_WIDGET_NEXT -> {
                val nextIntent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_NEXT
                }
                runCatching { ContextCompat.startForegroundService(context, nextIntent) }
            }
        }
    }

    companion object {
        private const val TAG = "FlowWidget"
        const val ACTION_WIDGET_UPDATE = "com.demonlab.lune.WIDGET_UPDATE"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.demonlab.lune.WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_PREVIOUS = "com.demonlab.lune.WIDGET_PREVIOUS"
        const val ACTION_WIDGET_NEXT = "com.demonlab.lune.WIDGET_NEXT"

        private const val MAX_WIDGET_COVER_DIM = 192
        private const val BLUR_BG_WIDTH = 240
        private const val BLUR_BG_HEIGHT = 120

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val renderMutex = Mutex()

        private data class CachedWidgetArt(
            val songId: Long,
            val songPath: String,
            val circular: Boolean,
            val vinyl: Boolean,
            val solidBg: Boolean,
            val blurRadius: Int,
            val darknessBits: Int,
            val coverBitmap: Bitmap?,
            val blurBitmap: Bitmap?
        )

        @Volatile
        private var cachedArt: CachedWidgetArt? = null

        fun invalidateArtCache() {
            cachedArt = null
        }

        fun applyWidgetStyling(
            context: Context,
            views: RemoteViews,
            settingsManager: SettingsManager
        ) {
            val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val isSolid = settingsManager.widgetUseSolidBackground
            val solidColor = if (isNight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val isLightBg = isSolid && isColorLight(solidColor)

            if (isLightBg) {
                val darkText = Color.parseColor("#151515")
                val darkSubtext = Color.parseColor("#555555")
                views.setTextColor(R.id.widget_title, darkText)
                views.setTextColor(R.id.widget_artist, darkSubtext)
                views.setTextColor(R.id.widget_output_text, darkText)
                views.setInt(R.id.widget_output_icon, "setColorFilter", darkText)
                views.setInt(R.id.widget_prev, "setColorFilter", darkText)
                views.setInt(R.id.widget_play_pause, "setColorFilter", darkText)
                views.setInt(R.id.widget_next, "setColorFilter", darkText)
            } else {
                val white = Color.WHITE
                val subtext = Color.parseColor("#D8D8DF")
                views.setTextColor(R.id.widget_title, white)
                views.setTextColor(R.id.widget_artist, subtext)
                views.setTextColor(R.id.widget_output_text, white)
                views.setInt(R.id.widget_output_icon, "setColorFilter", white)
                views.setInt(R.id.widget_prev, "setColorFilter", white)
                views.setInt(R.id.widget_play_pause, "setColorFilter", white)
                views.setInt(R.id.widget_next, "setColorFilter", white)
            }

            if (settingsManager.widgetCircularCover) {
                views.setInt(R.id.cover_container, "setBackgroundResource", R.drawable.widget_cover_circle_shape)
            } else {
                views.setInt(R.id.cover_container, "setBackgroundResource", R.drawable.widget_cover_shape)
            }

            if (isSolid) {
                views.setImageViewBitmap(R.id.widget_blur_bg, getSolidColorBitmap(solidColor))
                views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
            }
        }

        fun getWidgetLayoutResId(options: Bundle): Int {
            val colSpan = options.getInt("semAppWidgetColumnSpan", -1)
            val rowSpan = options.getInt("semAppWidgetRowSpan", -1)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)

            val isSingleRow = if (rowSpan > 0) rowSpan == 1 else minHeight in 1..125
            val isTwoColumns = if (colSpan > 0) colSpan <= 2 else minWidth in 1..179
            val isThreeColumns = if (colSpan > 0) colSpan == 3 else minWidth in 180..270

            return when {
                !isSingleRow && isTwoColumns -> R.layout.lune_widget_layout_2x2
                !isSingleRow && isThreeColumns -> R.layout.lune_widget_layout_2x3
                else -> R.layout.lune_widget_layout
            }
        }

        fun applyWidgetResponsiveLayout(views: RemoteViews, options: Bundle) {
            val colSpan = options.getInt("semAppWidgetColumnSpan", -1)
            val rowSpan = options.getInt("semAppWidgetRowSpan", -1)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)

            val isSingleRow = if (rowSpan > 0) rowSpan == 1 else minHeight in 1..125
            val isTwoColumns = if (colSpan > 0) colSpan <= 2 else minWidth in 1..179
            val isThreeColumns = if (colSpan > 0) colSpan == 3 else minWidth in 180..270

            if (isTwoColumns || isThreeColumns) {
                views.setViewVisibility(R.id.widget_output_pill, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_output_pill, View.VISIBLE)
            }

            if (isSingleRow) {
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_artist, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_title, View.VISIBLE)
                views.setViewVisibility(R.id.widget_artist, View.VISIBLE)
            }

            if (isSingleRow && isTwoColumns) {
                views.setViewVisibility(R.id.widget_prev, View.GONE)
                views.setViewVisibility(R.id.widget_next, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_prev, View.VISIBLE)
                views.setViewVisibility(R.id.widget_next, View.VISIBLE)
            }
        }

        suspend fun pushUpdateToAllWidgets(
            context: Context,
            preloadedArt: Bitmap? = null,
            preloadedArtSongId: Long? = null
        ) {
            val appCtx = context.applicationContext
            val appWidgetManager = runCatching { AppWidgetManager.getInstance(appCtx) }.getOrNull() ?: return
            val componentName = ComponentName(appCtx, LuneWidgetProvider::class.java)
            val appWidgetIds = runCatching { appWidgetManager.getAppWidgetIds(componentName) }.getOrNull() ?: return
            if (appWidgetIds.isEmpty()) return
            pushUpdateToWidgets(appCtx, appWidgetManager, appWidgetIds, preloadedArt, preloadedArtSongId)
        }

        suspend fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            pushUpdateToWidgets(context.applicationContext, appWidgetManager, intArrayOf(appWidgetId))
        }

        private suspend fun pushUpdateToWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            preloadedArt: Bitmap? = null,
            preloadedArtSongId: Long? = null
        ) = renderMutex.withLock {
            if (appWidgetIds.isEmpty()) return@withLock

            val playbackManager = PlaybackManager.getInstance(context)
            if (playbackManager.currentSong == null && !playbackManager.stateRestored) {
                runCatching { playbackManager.restorePlaybackState() }
            }

            val currentSong = playbackManager.currentSong
            val isPlaying = playbackManager.isPlaying
            val settingsManager = SettingsManager.getInstance(context)
            val multiTagCfg = settingsManager.toMultiTagConfig()

            val circular = settingsManager.widgetCircularCover
            val vinyl = circular && settingsManager.widgetVinylCover
            val solidBg = settingsManager.widgetUseSolidBackground
            val blurRadius = (settingsManager.widgetBackgroundBlur * 0.5f).toInt().coerceIn(3, 45)
            val darkness = settingsManager.widgetBackgroundDarkness
            val darknessBits = darkness.toBits()

            var resolvedCover: Bitmap? = null
            var resolvedBlur: Bitmap? = null

            if (currentSong != null) {
                val existing = cachedArt
                val cacheValid = existing != null &&
                    existing.songId == currentSong.id &&
                    existing.songPath == currentSong.path &&
                    existing.circular == circular &&
                    existing.vinyl == vinyl &&
                    existing.solidBg == solidBg &&
                    existing.blurRadius == blurRadius &&
                    existing.darknessBits == darknessBits &&
                    (existing.coverBitmap == null || !existing.coverBitmap.isRecycled) &&
                    (existing.blurBitmap == null || !existing.blurBitmap.isRecycled)

                if (cacheValid && existing != null) {
                    resolvedCover = existing.coverBitmap
                    resolvedBlur = existing.blurBitmap
                } else {
                    val rawArt = withContext(Dispatchers.IO) {
                        if (preloadedArt != null && !preloadedArt.isRecycled && preloadedArtSongId == currentSong.id) {
                            preloadedArt
                        } else {
                            fetchAlbumArt(context, currentSong)
                        }
                    }
                    if (rawArt != null && !rawArt.isRecycled) {
                        withContext(Dispatchers.Default) {
                            resolvedCover = when {
                                vinyl -> getVinylRecordBitmap(rawArt, MAX_WIDGET_COVER_DIM)
                                circular -> getCircularBitmap(rawArt, MAX_WIDGET_COVER_DIM)
                                else -> getSquareScaledBitmap(rawArt, MAX_WIDGET_COVER_DIM)
                            }
                            if (!solidBg) {
                                resolvedBlur = getBlurredBitmap(
                                    context = context,
                                    bitmap = rawArt,
                                    radius = blurRadius,
                                    cornerRadius = 28,
                                    darkness = darkness
                                )
                            }
                        }
                    }
                    cachedArt = CachedWidgetArt(
                        songId = currentSong.id,
                        songPath = currentSong.path,
                        circular = circular,
                        vinyl = vinyl,
                        solidBg = solidBg,
                        blurRadius = blurRadius,
                        darknessBits = darknessBits,
                        coverBitmap = resolvedCover,
                        blurBitmap = resolvedBlur
                    )
                }
            } else {
                cachedArt = null
            }

            val openAppIntent = Intent(context, Lune::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val playPausePi = getWidgetBroadcastPendingIntent(context, ACTION_WIDGET_PLAY_PAUSE)
            val prevPi = getWidgetBroadcastPendingIntent(context, ACTION_WIDGET_PREVIOUS)
            val nextPi = getWidgetBroadcastPendingIntent(context, ACTION_WIDGET_NEXT)

            val outputIconRes = getOutputIconRes(context)
            val outputName = getOutputName(context)
            val displayTitle = currentSong?.effectiveDisplayTitle(multiTagCfg)
                ?: context.getString(R.string.no_song_playing)
            val displayArtist = currentSong?.let { song ->
                song.parsedArtists(multiTagCfg).joinToString(", ").ifBlank { song.artist }
            }.orEmpty()

            for (appWidgetId in appWidgetIds) {
                runCatching {
                    val options = appWidgetManager.getAppWidgetOptions(appWidgetId) ?: Bundle.EMPTY
                    val layoutResId = getWidgetLayoutResId(options)
                    val views = RemoteViews(context.packageName, layoutResId)

                    applyWidgetStyling(context, views, settingsManager)
                    applyWidgetResponsiveLayout(views, options)
                    views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

                    views.setTextViewText(R.id.widget_title, displayTitle)
                    views.setTextViewText(R.id.widget_artist, displayArtist)
                    views.setImageViewResource(
                        R.id.widget_play_pause,
                        if (currentSong != null && isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                    )
                    views.setImageViewResource(R.id.widget_output_icon, outputIconRes)
                    views.setTextViewText(R.id.widget_output_text, outputName)

                    if (currentSong != null && resolvedCover != null && !resolvedCover.isRecycled) {
                        views.setImageViewBitmap(R.id.widget_cover, resolvedCover)
                    } else {
                        views.setImageViewResource(R.id.widget_cover, R.drawable.ic_lune_placeholder)
                    }

                    if (!solidBg) {
                        if (currentSong != null && resolvedBlur != null && !resolvedBlur.isRecycled) {
                            views.setImageViewBitmap(R.id.widget_blur_bg, resolvedBlur)
                            views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                        }
                    }

                    views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePi)
                    views.setOnClickPendingIntent(R.id.widget_prev, prevPi)
                    views.setOnClickPendingIntent(R.id.widget_next, nextPi)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }.onFailure { err ->
                    Log.w(TAG, "Failed updating widget $appWidgetId: ${err.message}")
                }
            }
        }

        fun getWidgetBroadcastPendingIntent(context: Context, widgetAction: String): PendingIntent {
            val intent = Intent(context, LuneWidgetProvider::class.java).apply {
                action = widgetAction
            }
            return PendingIntent.getBroadcast(
                context,
                widgetAction.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun getServicePendingIntent(context: Context, action: String): PendingIntent {
            val mappedWidgetAction = when (action) {
                MusicService.ACTION_PLAY, MusicService.ACTION_PAUSE -> ACTION_WIDGET_PLAY_PAUSE
                MusicService.ACTION_PREVIOUS -> ACTION_WIDGET_PREVIOUS
                MusicService.ACTION_NEXT -> ACTION_WIDGET_NEXT
                else -> null
            }
            if (mappedWidgetAction != null) {
                return getWidgetBroadcastPendingIntent(context, mappedWidgetAction)
            }
            val intent = Intent(context, MusicService::class.java).apply {
                this.action = action
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == MusicService.ACTION_PLAY) {
                PendingIntent.getForegroundService(
                    context,
                    action.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    action.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        suspend fun fetchAlbumArt(context: Context, song: Song): Bitmap? {
            val loader = Coil.imageLoader(context)
            val artUri = when {
                song.coverUrl != null -> Uri.parse(song.coverUrl)
                song.albumArtUri != null -> song.albumArtUri
                song.albumId > 0 -> ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    song.albumId
                )
                else -> song.uri
            }
            val request = ImageRequest.Builder(context)
                .data(artUri)
                .size(256, 256)
                .allowHardware(false)
                .build()

            val result = runCatching { loader.execute(request) }.getOrNull()
            var bitmap = (result as? SuccessResult)?.drawable?.let { drawable ->
                val w = drawable.intrinsicWidth.coerceIn(1, 256)
                val h = drawable.intrinsicHeight.coerceIn(1, 256)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }

            if (bitmap == null) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (song.path.isNotBlank() && File(song.path).exists()) {
                        retriever.setDataSource(song.path)
                    } else {
                        retriever.setDataSource(context, song.uri)
                    }
                    val pic = retriever.embeddedPicture
                    if (pic != null && pic.isNotEmpty()) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(pic, 0, pic.size, bounds)
                        var inSample = 1
                        while (bounds.outWidth / (inSample * 2) >= 256 && bounds.outHeight / (inSample * 2) >= 256) {
                            inSample *= 2
                        }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = inSample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        bitmap = BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
            }

            return bitmap
        }

        private fun getOutputIconRes(context: Context): Int {
            return runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return R.drawable.ic_speaker
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> return R.drawable.ic_bluetooth
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE -> return R.drawable.ic_headphones
                        else -> Unit
                    }
                }
                R.drawable.ic_speaker
            }.getOrDefault(R.drawable.ic_speaker)
        }

        private fun getOutputName(context: Context): String {
            return runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return context.getString(R.string.output_speaker)
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> return context.getString(R.string.output_bluetooth)
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE -> return context.getString(R.string.output_headphones)
                        else -> Unit
                    }
                }
                context.getString(R.string.output_speaker)
            }.getOrDefault(context.getString(R.string.output_speaker))
        }

        fun isColorLight(color: Int): Boolean {
            val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
            return darkness < 0.45
        }

        fun getSolidColorBitmap(color: Int, width: Int = 32, height: Int = 32, cornerRadius: Int = 0): Bitmap {
            val safeW = width.coerceIn(8, 256)
            val safeH = height.coerceIn(8, 256)
            val output = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.RGB_565)
            val canvas = Canvas(output)
            if (cornerRadius <= 0) {
                canvas.drawColor(color)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                val rectF = RectF(0f, 0f, safeW.toFloat(), safeH.toFloat())
                canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), paint)
            }
            return output
        }

        fun getCircularBitmap(bitmap: Bitmap, maxDim: Int = MAX_WIDGET_COVER_DIM): Bitmap {
            val safeMax = maxDim.coerceIn(32, MAX_WIDGET_COVER_DIM)
            val srcW = bitmap.width.coerceAtLeast(1)
            val srcH = bitmap.height.coerceAtLeast(1)
            val squareEdge = min(srcW, srcH)
            val x = (srcW - squareEdge) / 2
            val y = (srcH - squareEdge) / 2
            val targetSize = min(squareEdge, safeMax)

            val cropped = Bitmap.createBitmap(bitmap, x, y, squareEdge, squareEdge)
            val scaledBitmap = if (cropped.width != targetSize) {
                val s = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
                if (cropped !== bitmap) cropped.recycle()
                s
            } else {
                cropped
            }

            val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = targetSize / 2f
            canvas.drawARGB(0, 0, 0, 0)
            paint.color = Color.BLACK
            canvas.drawCircle(radius, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            return output
        }

        fun getVinylRecordBitmap(bitmap: Bitmap, size: Int = MAX_WIDGET_COVER_DIM): Bitmap {
            val safeSize = size.coerceIn(64, MAX_WIDGET_COVER_DIM)
            val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val center = safeSize / 2f

            val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#101010")
            }
            canvas.drawCircle(center, center, center, discPaint)

            val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            val grooveFractions = floatArrayOf(0.92f, 0.85f, 0.78f, 0.70f, 0.63f)
            for (fraction in grooveFractions) {
                groovePaint.color = Color.argb(
                    if (fraction == 0.85f || fraction == 0.70f) 22 else 14,
                    255, 255, 255
                )
                canvas.drawCircle(center, center, center * fraction, groovePaint)
            }

            val artSize = (safeSize * 0.55f).toInt().coerceAtLeast(24)
            val circularArt = getCircularBitmap(bitmap, artSize)
            val scaledArt = if (circularArt.width != artSize) {
                Bitmap.createScaledBitmap(circularArt, artSize, artSize, true)
            } else {
                circularArt
            }

            val artOffset = (safeSize - artSize) / 2f
            canvas.drawBitmap(scaledArt, artOffset, artOffset, null)
            if (scaledArt !== circularArt) {
                scaledArt.recycle()
            }
            circularArt.recycle()

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = Color.parseColor("#252525")
            }
            canvas.drawCircle(center, center, artSize / 2f, ringPaint)

            val holeRadius = (safeSize * 0.026f).coerceAtLeast(4.5f)
            val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#101010")
            }
            val holeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = Color.argb(60, 255, 255, 255)
            }
            canvas.drawCircle(center, center, holeRadius, holePaint)
            canvas.drawCircle(center, center, holeRadius, holeBorderPaint)

            return output
        }

        fun getSquareScaledBitmap(bitmap: Bitmap, maxDim: Int = MAX_WIDGET_COVER_DIM): Bitmap {
            val safeMax = maxDim.coerceIn(32, MAX_WIDGET_COVER_DIM)
            val srcW = bitmap.width.coerceAtLeast(1)
            val srcH = bitmap.height.coerceAtLeast(1)
            val squareEdge = min(srcW, srcH)
            val x = (srcW - squareEdge) / 2
            val y = (srcH - squareEdge) / 2
            val targetSize = min(squareEdge, safeMax)

            val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
            val canvas = Canvas(output)
            val srcRect = Rect(x, y, x + squareEdge, y + squareEdge)
            val dstRect = Rect(0, 0, targetSize, targetSize)
            canvas.drawBitmap(bitmap, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }

        fun fastBlur(sentBitmap: Bitmap, scale: Float, radius: Int): Bitmap? {
            if (sentBitmap.isRecycled || sentBitmap.width <= 0 || sentBitmap.height <= 0 || radius < 1) {
                return null
            }
            val w = (sentBitmap.width * scale).toInt().coerceIn(4, 256)
            val h = (sentBitmap.height * scale).toInt().coerceIn(4, 256)
            val safeRadius = radius.coerceIn(1, min(min(w - 1, h - 1), 32)).coerceAtLeast(1)

            val scaled = Bitmap.createScaledBitmap(sentBitmap, w, h, true)
            val bitmap = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
                scaled
            } else {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled !== sentBitmap) scaled.recycle()
                copy ?: return null
            }

            val pix = IntArray(w * h)
            bitmap.getPixels(pix, 0, w, 0, 0, w, h)
            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = safeRadius + safeRadius + 1
            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            var rsum: Int
            var gsum: Int
            var bsum: Int
            var x: Int
            var y: Int
            var i: Int
            var p: Int
            var yp: Int
            var yi: Int
            var yw: Int
            val vmin = IntArray(max(w, h))
            var divsum = (div + 1) shr 1
            divsum *= divsum
            val dv = IntArray(256 * divsum)
            i = 0
            while (i < 256 * divsum) {
                dv[i] = i / divsum
                i++
            }
            yi = 0
            yw = 0
            val stack = Array(div) { IntArray(3) }
            var stackpointer: Int
            var stackstart: Int
            var sir: IntArray
            var rbs: Int
            val r1 = safeRadius + 1
            var routsum: Int
            var goutsum: Int
            var boutsum: Int
            var rinsum: Int
            var ginsum: Int
            var binsum: Int

            y = 0
            while (y < h) {
                bsum = 0; gsum = 0; rsum = 0
                boutsum = 0; goutsum = 0; routsum = 0
                binsum = 0; ginsum = 0; rinsum = 0
                i = -safeRadius
                while (i <= safeRadius) {
                    p = pix[yi + min(wm, max(i, 0))]
                    sir = stack[i + safeRadius]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = p and 0x0000ff
                    rbs = r1 - abs(i)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                    i++
                }
                stackpointer = safeRadius
                x = 0
                while (x < w) {
                    r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                    stackstart = stackpointer - safeRadius + div
                    sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                    if (y == 0) {
                        vmin[x] = min(x + safeRadius + 1, wm)
                    }
                    p = pix[yw + vmin[x]]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = p and 0x0000ff
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    rsum += rinsum; gsum += ginsum; bsum += binsum
                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer % div]
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                    yi++
                    x++
                }
                yw += w
                y++
            }

            x = 0
            while (x < w) {
                bsum = 0; gsum = 0; rsum = 0
                boutsum = 0; goutsum = 0; routsum = 0
                binsum = 0; ginsum = 0; rinsum = 0
                yp = -safeRadius * w
                i = -safeRadius
                while (i <= safeRadius) {
                    yi = max(0, yp) + x
                    sir = stack[i + safeRadius]
                    sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                    rbs = r1 - abs(i)
                    rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                    if (i < hm) {
                        yp += w
                    }
                    i++
                }
                yi = x
                stackpointer = safeRadius
                y = 0
                while (y < h) {
                    pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                    stackstart = stackpointer - safeRadius + div
                    sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                    if (x == 0) {
                        vmin[y] = min(y + r1, hm) * w
                    }
                    p = x + vmin[y]
                    sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    rsum += rinsum; gsum += ginsum; bsum += binsum
                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer]
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                    yi += w
                    y++
                }
                x++
            }
            bitmap.setPixels(pix, 0, w, 0, 0, w, h)
            return bitmap
        }

        @Suppress("UNUSED_PARAMETER")
        fun getBlurredBitmap(
            context: Context,
            bitmap: Bitmap,
            radius: Int,
            cornerRadius: Int,
            darkness: Float = 0.50f
        ): Bitmap {
            val safeRadius = radius.coerceIn(2, 40)
            return try {
                // RGB_565 240x120 = 57.6 KB total — zero EGL/Gralloc allocation, zero Binder bloat
                val output = Bitmap.createBitmap(BLUR_BG_WIDTH, BLUR_BG_HEIGHT, Bitmap.Config.RGB_565)
                val canvas = Canvas(output)
                canvas.drawColor(Color.parseColor("#141316"))

                val maxEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
                val scale = (96f / maxEdge).coerceIn(0.05f, 1f)
                val blurred = fastBlur(bitmap, scale, (safeRadius / 2).coerceIn(1, 20))

                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = ((1f - (darkness * 0.7f).coerceIn(0f, 0.9f)) * 255).toInt().coerceIn(25, 255)
                }

                if (blurred != null && !blurred.isRecycled) {
                    canvas.drawBitmap(blurred, null, Rect(0, 0, BLUR_BG_WIDTH, BLUR_BG_HEIGHT), paint)
                    if (blurred !== bitmap) blurred.recycle()
                } else if (!bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, null, Rect(0, 0, BLUR_BG_WIDTH, BLUR_BG_HEIGHT), paint)
                }

                if (darkness > 0.3f) {
                    val scrimPaint = Paint().apply {
                        color = Color.BLACK
                        alpha = (((darkness - 0.3f) / 0.7f) * 135).toInt().coerceIn(0, 215)
                    }
                    canvas.drawRect(0f, 0f, BLUR_BG_WIDTH.toFloat(), BLUR_BG_HEIGHT.toFloat(), scrimPaint)
                }

                output
            } catch (_: Throwable) {
                getSolidColorBitmap(Color.parseColor("#141316"))
            }
        }
    }
}
