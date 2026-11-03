package com.demonlab.lune

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.demonlab.lune.audio.ReplayGainScanner
import com.demonlab.lune.tools.AudioThumbnailFetcher
import com.demonlab.lune.tools.SettingsManager

class LuneApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val isModestDevice = am?.isLowRamDevice == true ||
            (memInfo.totalMem in 1L..(3L * 1024L * 1024L * 1024L))
        val lowVisuals = runCatching {
            SettingsManager.getInstance(this).isLowResourceVisualsActive(this)
        }.getOrDefault(isModestDevice)

        // Strict RAM budget: 6 MB on modest/low-RAM devices, 12 MB on high-RAM devices
        // Keeps total Android process footprint well below 90-128 MB even on large libraries.
        val maxMemoryCacheBytes = if (isModestDevice || lowVisuals) {
            6 * 1024 * 1024
        } else {
            12 * 1024 * 1024
        }

        return ImageLoader.Builder(this)
            .components {
                add(AudioThumbnailFetcher.Factory(this@LuneApplication))
            }
            .allowRgb565(true)
            .bitmapConfig(if (isModestDevice || lowVisuals) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            .crossfade(!lowVisuals)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(maxMemoryCacheBytes)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            ReplayGainScanner.trimInMemoryIfNeeded()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        ReplayGainScanner.trimInMemoryIfNeeded()
    }
}
