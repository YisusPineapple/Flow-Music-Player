package com.demonlab.lune.tools

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lune_settings", Context.MODE_PRIVATE)

    private val _customTitleFlow = MutableStateFlow(prefs.getString("custom_title", "") ?: "")
    val customTitleFlow: StateFlow<String> = _customTitleFlow.asStateFlow()


    var isFirstRun: Boolean
        get() = prefs.getBoolean("is_first_run", true)
        set(value) = prefs.edit().putBoolean("is_first_run", value).apply()

    var optionsOrder: String
        get() = prefs.getString("options_order", "like,shuffle,repeat,crossfade,automix,timer,eq") ?: "like,shuffle,repeat,crossfade,automix,timer,eq"
        set(value) = prefs.edit().putString("options_order", value).apply()

    private val _blacklistedSongsFlow = MutableStateFlow<Set<Long>>(
        (prefs.getStringSet("blacklisted_song_ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    )
    val blacklistedSongsFlow: StateFlow<Set<Long>> = _blacklistedSongsFlow.asStateFlow()

    var blacklistedSongIds: Set<Long>
        get() = _blacklistedSongsFlow.value
        set(value) {
            val stringSet = value.map { it.toString() }.toSet()
            prefs.edit().putStringSet("blacklisted_song_ids", stringSet).apply()
            _blacklistedSongsFlow.value = value
        }

    fun addSongToBlacklist(songId: Long) {
        blacklistedSongIds = blacklistedSongIds + songId
    }

    fun removeSongFromBlacklist(songId: Long) {
        blacklistedSongIds = blacklistedSongIds - songId
    }

    var hiddenFolders: Set<String>
        get() = prefs.getStringSet("hidden_folders", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_folders", value).apply()

    // Deprecated: used for backward compatibility if needed, but we use hiddenFolders now
    var showWhatsappAudio: Boolean
        get() = !hiddenFolders.contains("WhatsApp Audio")
        set(value) {
            val folders = hiddenFolders.toMutableSet()
            if (value) folders.remove("WhatsApp Audio") else folders.add("WhatsApp Audio")
            hiddenFolders = folders
        }

    var enableHiFi: Boolean
        get() = prefs.getBoolean("enable_hifi", true)
        set(value) = prefs.edit().putBoolean("enable_hifi", value).apply()

    var is32BitFloatEnabled: Boolean
        get() = prefs.getBoolean("is_32bit_float_enabled", true)
        set(value) = prefs.edit().putBoolean("is_32bit_float_enabled", value).apply()

    var isExclusiveModeEnabled: Boolean
        get() = prefs.getBoolean("is_exclusive_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("is_exclusive_mode_enabled", value).apply()

    var stopOnTaskRemoved: Boolean
        get() = prefs.getBoolean("stop_on_task_removed", false)
        set(value) = prefs.edit().putBoolean("stop_on_task_removed", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    private val _themeMode = mutableStateOf(prefs.getInt("theme_mode", 0))
    var themeMode: Int
        get() = _themeMode.value
        set(value) {
            _themeMode.value = value
            prefs.edit().putInt("theme_mode", value).apply()
        }

    var forceDarkMode: Boolean
        get() = prefs.getBoolean("force_dark_mode", false)
        set(value) = prefs.edit().putBoolean("force_dark_mode", value).apply()

    var sortOption: String
        get() = prefs.getString("sort_option", "ALPHABETICAL") ?: "ALPHABETICAL"
        set(value) = prefs.edit().putString("sort_option", value).apply()

    fun getSortOption(key: String): String {
        val defaultSort = if (key.startsWith("album_")) "TRACK_NUMBER" else sortOption
        return prefs.getString("sort_option_$key", defaultSort) ?: defaultSort
    }

    fun setSortOption(key: String, option: String) {
        prefs.edit().putString("sort_option_$key", option).apply()
    }

    var tracksViewStyle: Int
        get() = prefs.getInt("tracks_view_style", 0) // 0 = List, 1 = Grid
        set(value) = prefs.edit().putInt("tracks_view_style", value).apply()

    var albumViewStyle: Int
        get() = prefs.getInt("album_view_style", 0) // 0 = Grid, 1 = Carousel
        set(value) = prefs.edit().putInt("album_view_style", value).apply()

    var albumBrowseMode: Boolean
        get() = prefs.getBoolean("album_browse_mode", false) // false = Artists, true = Albums
        set(value) = prefs.edit().putBoolean("album_browse_mode", value).apply()

    var isSectionCustomizationEnabled: Boolean
        get() = prefs.getBoolean("section_customization_enabled", false)
        set(value) = prefs.edit().putBoolean("section_customization_enabled", value).apply()

    var isHeaderWaveEffectEnabled: Boolean
        get() = prefs.getBoolean("header_wave_effect_enabled", true)
        set(value) = prefs.edit().putBoolean("header_wave_effect_enabled", value).apply()

    var isScrollToTopEnabled: Boolean
        get() = prefs.getBoolean("scroll_to_top_enabled", true)
        set(value) = prefs.edit().putBoolean("scroll_to_top_enabled", value).apply()

    var widgetUseSolidBackground: Boolean
        get() = prefs.getBoolean("widget_use_solid_background", false)
        set(value) = prefs.edit().putBoolean("widget_use_solid_background", value).apply()

    var widgetLightBackgroundColor: Int
        get() = prefs.getInt("widget_light_background_color", 0xFFFFFFFF.toInt())
        set(value) = prefs.edit().putInt("widget_light_background_color", value).apply()

    var widgetDarkBackgroundColor: Int
        get() = prefs.getInt("widget_dark_background_color", 0xFF1E1E24.toInt())
        set(value) = prefs.edit().putInt("widget_dark_background_color", value).apply()

    var widgetBackgroundDarkness: Float
        get() = prefs.getFloat("widget_background_darkness", 0.50f)
        set(value) = prefs.edit().putFloat("widget_background_darkness", value).apply()

    var widgetBackgroundBlur: Int
        get() = prefs.getInt("widget_background_blur", 50)
        set(value) = prefs.edit().putInt("widget_background_blur", value).apply()

    var widgetCircularCover: Boolean
        get() = prefs.getBoolean("widget_circular_cover", false)
        set(value) = prefs.edit().putBoolean("widget_circular_cover", value).apply()

    var widgetVinylCover: Boolean
        get() = prefs.getBoolean("widget_vinyl_cover", false)
        set(value) = prefs.edit().putBoolean("widget_vinyl_cover", value).apply()

    var hiddenSectionTabs: Set<String>
        get() = prefs.getStringSet("hidden_section_tabs", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_section_tabs", value).apply()

    var defaultSectionTab: String
        get() = prefs.getString("default_section_tab", "") ?: ""
        set(value) = prefs.edit().putString("default_section_tab", value).apply()

    var isSortAscending: Boolean
        get() = prefs.getBoolean("is_sort_ascending", true)
        set(value) = prefs.edit().putBoolean("is_sort_ascending", value).apply()

    fun getIsSortAscending(key: String): Boolean {
        return prefs.getBoolean("is_sort_ascending_$key", isSortAscending)
    }

    fun setIsSortAscending(key: String, ascending: Boolean) {
        prefs.edit().putBoolean("is_sort_ascending_$key", ascending).apply()
    }

    var isCaseSensitiveSort: Boolean
        get() = prefs.getBoolean("is_case_sensitive_sort", false)
        set(value) = prefs.edit().putBoolean("is_case_sensitive_sort", value).apply()

    fun getIsCaseSensitiveSort(key: String): Boolean {
        return prefs.getBoolean("is_case_sensitive_sort_$key", isCaseSensitiveSort)
    }

    fun setIsCaseSensitiveSort(key: String, caseSensitive: Boolean) {
        prefs.edit().putBoolean("is_case_sensitive_sort_$key", caseSensitive).apply()
    }

    var isShuffle: Boolean
        get() = prefs.getBoolean("is_shuffle", false)
        set(value) = prefs.edit().putBoolean("is_shuffle", value).apply()

    var isCrossfade: Boolean
        get() = prefs.getBoolean("is_crossfade", false)
        set(value) = prefs.edit().putBoolean("is_crossfade", value).apply()

    var isCrossfadeCustomDuration: Boolean
        get() = prefs.getBoolean("is_crossfade_custom_duration", false)
        set(value) = prefs.edit().putBoolean("is_crossfade_custom_duration", value).apply()

    var crossfadeDurationSeconds: Int
        get() = prefs.getInt("crossfade_duration_seconds", 12)
        set(value) = prefs.edit().putInt("crossfade_duration_seconds", value).apply()

    var isCrossfadeAdaptive: Boolean
        get() = prefs.getBoolean("is_crossfade_adaptive", true)
        set(value) = prefs.edit().putBoolean("is_crossfade_adaptive", value).apply()

    var isCrossfadeSilenceDetection: Boolean
        get() = prefs.getBoolean("is_crossfade_silence_detection", true)
        set(value) = prefs.edit().putBoolean("is_crossfade_silence_detection", value).apply()

    var isAutomix: Boolean
        get() = prefs.getBoolean("is_automix", false)
        set(value) = prefs.edit().putBoolean("is_automix", value).apply()

    var repeatMode: Int
        get() = prefs.getInt("repeat_mode", 0) // 0: Off, 1: One, 2: All
        set(value) = prefs.edit().putInt("repeat_mode", value).apply()

    var seamlessLooping: Boolean
        get() = prefs.getBoolean("seamless_looping", true)
        set(value) = prefs.edit().putBoolean("seamless_looping", value).apply()

    var isEqEnabled: Boolean
        get() = prefs.getBoolean("is_eq_enabled", false)
        set(value) = prefs.edit().putBoolean("is_eq_enabled", value).apply()

    var eqBandLevels: String
        get() = prefs.getString("eq_band_levels", "") ?: ""
        set(value) = prefs.edit().putString("eq_band_levels", value).apply()

    var activeEqPresetName: String
        get() = prefs.getString("active_eq_preset_name", "") ?: ""
        set(value) = prefs.edit().putString("active_eq_preset_name", value).apply()

    var lastEqPresetName: String
        get() = prefs.getString("last_eq_preset_name", "") ?: ""
        set(value) = prefs.edit().putString("last_eq_preset_name", value).apply()

    var lastEqBandLevels: String
        get() = prefs.getString("last_eq_band_levels", "") ?: ""
        set(value) = prefs.edit().putString("last_eq_band_levels", value).apply()

    var customEqPresetsJson: String
        get() = prefs.getString("custom_eq_presets", "[]") ?: "[]"
        set(value) = prefs.edit().putString("custom_eq_presets", value).apply()

    var isBassBoostEnabled: Boolean
        get() = prefs.getBoolean("is_bass_enabled", false)
        set(value) = prefs.edit().putBoolean("is_bass_enabled", value).apply()

    var bassBoostLevel: Int
        get() = prefs.getInt("bass_boost_level", 900)
        set(value) = prefs.edit().putInt("bass_boost_level", value).apply()

    var isSpatialAudioEnabled: Boolean
        get() = prefs.getBoolean("is_spatial_audio_enabled", false)
        set(value) {
            prefs.edit().putBoolean("is_spatial_audio_enabled", value).apply()
        }

    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(value) = prefs.edit().putString("language", value).apply()

    var customTitle: String
        get() = prefs.getString("custom_title", "") ?: ""
        set(value) {
            prefs.edit().putString("custom_title", value).apply()
            _customTitleFlow.value = value
        }

    var lastPlayedSongId: Long
        get() = prefs.getLong("last_played_song_id", -1L)
        set(value) = prefs.edit().putLong("last_played_song_id", value).apply()

    var lastCategory: String
        get() = prefs.getString("last_category", "") ?: ""
        set(value) = prefs.edit().putString("last_category", value).apply()



    var activeCategory: String
        get() = prefs.getString("active_category", "") ?: ""
        set(value) = prefs.edit().putString("active_category", value).apply()

    var isFullPlayerVisualizerEnabled: Boolean
        get() = prefs.getBoolean("is_full_player_visualizer_enabled", false)
        set(value) = prefs.edit().putBoolean("is_full_player_visualizer_enabled", value).apply()

    var isMiniPlayerVisualizerEnabled: Boolean
        get() = prefs.getBoolean("is_mini_player_visualizer_enabled", false)
        set(value) = prefs.edit().putBoolean("is_mini_player_visualizer_enabled", value).apply()

    var isCinematicPlayerEnabled: Boolean
        get() = prefs.getBoolean("is_cinematic_player_enabled", false)
        set(value) = prefs.edit().putBoolean("is_cinematic_player_enabled", value).apply()
        
    var isHapticVibrationEnabled: Boolean
        get() = prefs.getBoolean("is_haptic_vibration_enabled", true)
        set(value) = prefs.edit().putBoolean("is_haptic_vibration_enabled", value).apply()

    var isSongInfoEnabled: Boolean
        get() = prefs.getBoolean("is_song_info_enabled", false)
        set(value) = prefs.edit().putBoolean("is_song_info_enabled", value).apply()

    private val _isBitrateOnList = mutableStateOf(prefs.getBoolean("is_bitrate_on_list", true))
    var isBitrateOnList: Boolean
        get() = _isBitrateOnList.value
        set(value) {
            _isBitrateOnList.value = value
            prefs.edit().putBoolean("is_bitrate_on_list", value).apply()
        }

    private val _isBitrateOnPlayer = mutableStateOf(prefs.getBoolean("is_bitrate_on_player", true))
    var isBitrateOnPlayer: Boolean
        get() = _isBitrateOnPlayer.value
        set(value) {
            _isBitrateOnPlayer.value = value
            prefs.edit().putBoolean("is_bitrate_on_player", value).apply()
        }

    private val _isOptionsBarVisible = mutableStateOf(prefs.getBoolean("is_options_bar_visible", false))
    var isOptionsBarVisible: Boolean
        get() = _isOptionsBarVisible.value
        set(value) {
            _isOptionsBarVisible.value = value
            prefs.edit().putBoolean("is_options_bar_visible", value).apply()
        }

    private val _isMiniPlayerMinimized = mutableStateOf(prefs.getBoolean("is_mini_player_minimized", false))
    var isMiniPlayerMinimized: Boolean
        get() = _isMiniPlayerMinimized.value
        set(value) {
            _isMiniPlayerMinimized.value = value
            prefs.edit().putBoolean("is_mini_player_minimized", value).apply()
        }

    private val _isBlurEnabled = mutableStateOf(prefs.getBoolean("is_blur_enabled", true))
    var isBlurEnabled: Boolean
        get() = _isBlurEnabled.value
        set(value) {
            _isBlurEnabled.value = value
            prefs.edit().putBoolean("is_blur_enabled", value).apply()
        }

    private val _isBlurDarkMode = mutableStateOf(prefs.getBoolean("is_blur_dark_mode", true))
    var isBlurDarkMode: Boolean
        get() = _isBlurDarkMode.value
        set(value) {
            _isBlurDarkMode.value = value
            prefs.edit().putBoolean("is_blur_dark_mode", value).apply()
        }

    private val _isBlurLightMode = mutableStateOf(prefs.getBoolean("is_blur_light_mode", false))
    var isBlurLightMode: Boolean
        get() = _isBlurLightMode.value
        set(value) {
            _isBlurLightMode.value = value
            prefs.edit().putBoolean("is_blur_light_mode", value).apply()
        }

    private val _isBlurCinematicMode = mutableStateOf(prefs.getBoolean("is_blur_cinematic_mode", false))
    var isBlurCinematicMode: Boolean
        get() = _isBlurCinematicMode.value
        set(value) {
            _isBlurCinematicMode.value = value
            prefs.edit().putBoolean("is_blur_cinematic_mode", value).apply()
        }

    private val _isBlurControlsEnabled = mutableStateOf(prefs.getBoolean("is_blur_controls_enabled", false))
    var isBlurControlsEnabled: Boolean
        get() = _isBlurControlsEnabled.value
        set(value) {
            _isBlurControlsEnabled.value = value
            prefs.edit().putBoolean("is_blur_controls_enabled", value).apply()
        }

    var lyricsTextAlignment: Int
        get() = prefs.getInt("lyrics_text_alignment", 0)
        set(value) = prefs.edit().putInt("lyrics_text_alignment", value).apply()

    var lyricsSpeedIndex: Int
        get() = prefs.getInt("lyrics_speed_index", 0)
        set(value) = prefs.edit().putInt("lyrics_speed_index", value).apply()

    private val _isGesturesEnabled = mutableStateOf(prefs.getBoolean("is_gestures_enabled", false))
    var isGesturesEnabled: Boolean
        get() = _isGesturesEnabled.value
        set(value) {
            _isGesturesEnabled.value = value
            prefs.edit().putBoolean("is_gestures_enabled", value).apply()
        }

    var swipeUpAction: Int
        get() = prefs.getInt("swipe_up_action", 0)
        set(value) = prefs.edit().putInt("swipe_up_action", value).apply()

    var dailyListeningTime: Long
        get() = prefs.getLong("daily_listening_time", 0L)
        set(value) = prefs.edit().putLong("daily_listening_time", value).apply()

    var lastStatsResetTimestamp: Long
        get() = prefs.getLong("last_stats_reset_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_stats_reset_timestamp", value).apply()

    var musicFolderUri: String?
        get() = prefs.getString("music_folder_uri", null)
        set(value) = prefs.edit().putString("music_folder_uri", value).apply()
        
    var isInitialFolderScanPending: Boolean
        get() = prefs.getBoolean("is_initial_folder_scan_pending", false)
        set(value) = prefs.edit().putBoolean("is_initial_folder_scan_pending", value).apply()

    var showAllFoldersOnStart: Boolean
        get() = prefs.getBoolean("show_all_folders_on_start", false)
        set(value) = prefs.edit().putBoolean("show_all_folders_on_start", value).apply()

    var folderHierarchyMode: Boolean
        get() = prefs.getBoolean("folder_hierarchy_mode", false)
        set(value) = prefs.edit().putBoolean("folder_hierarchy_mode", value).apply()

    var useCustomColors: Boolean
        get() = prefs.getBoolean("use_custom_colors", false)
        set(value) = prefs.edit().putBoolean("use_custom_colors", value).apply()

    var customColorPalette: Int
        get() = prefs.getInt("custom_color_palette", 0)
        set(value) = prefs.edit().putInt("custom_color_palette", value).apply()

    var showBackupWarning: Boolean
        get() = prefs.getBoolean("show_backup_warning", true)
        set(value) = prefs.edit().putBoolean("show_backup_warning", value).apply()

    var isAutoSyncBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_backup_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_sync_backup_enabled", value).apply()

    var autoSyncBackupUri: String?
        get() = prefs.getString("auto_sync_backup_uri", null)
        set(value) = prefs.edit().putString("auto_sync_backup_uri", value).apply()

    var useAmoledPitchBlack: Boolean
        get() = prefs.getBoolean("use_amoled_pitch_black", false)
        set(value) = prefs.edit().putBoolean("use_amoled_pitch_black", value).apply()

    var showAiSection: Boolean
        get() = prefs.getBoolean("show_ai_section", true)
        set(value) = prefs.edit().putBoolean("show_ai_section", value).apply()

    var showHeroSection: Boolean
        get() = prefs.getBoolean("show_hero_section", true)
        set(value) = prefs.edit().putBoolean("show_hero_section", value).apply()

    private val _coverShape = mutableStateOf(prefs.getInt("cover_shape", 0))
    var coverShape: Int
        get() = _coverShape.value
        set(value) {
            _coverShape.value = value
            prefs.edit().putInt("cover_shape", value).apply()
        }

    private val _coverScale = mutableStateOf(prefs.getFloat("cover_scale", 1.0f))
    var coverScale: Float
        get() = _coverScale.value
        set(value) {
            _coverScale.value = value
            prefs.edit().putFloat("cover_scale", value).apply()
        }

    private val _coverSpin = mutableStateOf(prefs.getBoolean("cover_spin", true))
    var coverSpin: Boolean
        get() = _coverSpin.value
        set(value) {
            _coverSpin.value = value
            prefs.edit().putBoolean("cover_spin", value).apply()
        }

    private val _coverVinylEffect = mutableStateOf(prefs.getBoolean("cover_vinyl_effect", false))
    var coverVinylEffect: Boolean
        get() = _coverVinylEffect.value
        set(value) {
            _coverVinylEffect.value = value
            prefs.edit().putBoolean("cover_vinyl_effect", value).apply()
        }

    private val _controlsIconStyle = mutableStateOf(prefs.getInt("controls_icon_style", 0))
    var controlsIconStyle: Int
        get() = _controlsIconStyle.value
        set(value) {
            _controlsIconStyle.value = value
            prefs.edit().putInt("controls_icon_style", value).apply()
        }

    private val _isControlsFilled = mutableStateOf(prefs.getBoolean("is_controls_filled", false))
    var isControlsFilled: Boolean
        get() = _isControlsFilled.value
        set(value) {
            _isControlsFilled.value = value
            prefs.edit().putBoolean("is_controls_filled", value).apply()
        }

    private val _useCustomControlsColor = mutableStateOf(prefs.getBoolean("use_custom_controls_color", false))
    var useCustomControlsColor: Boolean
        get() = _useCustomControlsColor.value
        set(value) {
            _useCustomControlsColor.value = value
            prefs.edit().putBoolean("use_custom_controls_color", value).apply()
        }

    private val _controlsColorPalette = mutableStateOf(prefs.getInt("controls_color_palette", 0))
    var controlsColorPalette: Int
        get() = _controlsColorPalette.value
        set(value) {
            _controlsColorPalette.value = value
            prefs.edit().putInt("controls_color_palette", value).apply()
        }

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(value) = prefs.edit().putFloat("playback_speed", value).apply()

    var playbackPitch: Float
        get() = prefs.getFloat("playback_pitch", 1.0f)
        set(value) = prefs.edit().putFloat("playback_pitch", value).apply()

    var isTuning432: Boolean
        get() = prefs.getBoolean("tuning_432", false)
        set(value) = prefs.edit().putBoolean("tuning_432", value).apply()

    var reverbPreset: Int
        get() = prefs.getInt("reverb_preset", 0)
        set(value) = prefs.edit().putInt("reverb_preset", value).apply()

    var balance: Float
        get() = prefs.getFloat("balance", 0.5f)
        set(value) = prefs.edit().putFloat("balance", value).apply()

    var dynamicsPreset: Int
        get() = prefs.getInt("dynamics_preset", 0)
        set(value) = prefs.edit().putInt("dynamics_preset", value).apply()

    var isLoudnessEnabled: Boolean
        get() = prefs.getBoolean("is_loudness_enabled", false)
        set(value) = prefs.edit().putBoolean("is_loudness_enabled", value).apply()

    var loudnessGain: Int
        get() = prefs.getInt("loudness_gain", 0)
        set(value) = prefs.edit().putInt("loudness_gain", value).apply()

    fun getPlaylistShuffle(playlistId: Long): Boolean {
        return prefs.getBoolean("shuffle_playlist_$playlistId", false)
    }

    fun setPlaylistShuffle(playlistId: Long, value: Boolean) {
        prefs.edit().putBoolean("shuffle_playlist_$playlistId", value).apply()
    }

    // --- ReplayGain & Loudness Normalization ---
    private val _replayGainConfigVersion = MutableStateFlow(0)
    val replayGainConfigVersion: StateFlow<Int> = _replayGainConfigVersion.asStateFlow()

    private fun bumpReplayGainVersion() {
        _replayGainConfigVersion.value = _replayGainConfigVersion.value + 1
    }

    var replayGainMode: Int
        get() = prefs.getInt("replaygain_mode", 3) // 0=OFF, 1=TRACK, 2=ALBUM, 3=SMART_ALBUM
        set(value) {
            prefs.edit().putInt("replaygain_mode", value.coerceIn(0, 3)).apply()
            bumpReplayGainVersion()
        }

    var replayGainPreAmpDb: Float
        get() = prefs.getFloat("replaygain_preamp_db", 0.0f)
        set(value) {
            prefs.edit().putFloat("replaygain_preamp_db", value.coerceIn(-12f, 12f)).apply()
            bumpReplayGainVersion()
        }

    var replayGainUntaggedPreAmpDb: Float
        get() = prefs.getFloat("replaygain_untagged_preamp_db", 0.0f)
        set(value) {
            prefs.edit().putFloat("replaygain_untagged_preamp_db", value.coerceIn(-12f, 12f)).apply()
            bumpReplayGainVersion()
        }

    var replayGainPreventClipping: Boolean
        get() = prefs.getBoolean("replaygain_prevent_clipping", true)
        set(value) {
            prefs.edit().putBoolean("replaygain_prevent_clipping", value).apply()
            bumpReplayGainVersion()
        }

    var replayGainFallbackAnalysis: Boolean
        get() = prefs.getBoolean("replaygain_fallback_analysis", true)
        set(value) {
            prefs.edit().putBoolean("replaygain_fallback_analysis", value).apply()
            bumpReplayGainVersion()
        }

    var showReplayGainBadge: Boolean
        get() = prefs.getBoolean("show_replaygain_badge", true)
        set(value) = prefs.edit().putBoolean("show_replaygain_badge", value).apply()

    fun toReplayGainConfig(): com.demonlab.lune.core.model.ReplayGainConfig {
        val modeEnum = when (replayGainMode) {
            0 -> com.demonlab.lune.core.model.ReplayGainMode.OFF
            1 -> com.demonlab.lune.core.model.ReplayGainMode.TRACK
            2 -> com.demonlab.lune.core.model.ReplayGainMode.ALBUM
            else -> com.demonlab.lune.core.model.ReplayGainMode.SMART_ALBUM
        }
        return com.demonlab.lune.core.model.ReplayGainConfig(
            mode = modeEnum,
            preAmpWithTagsDb = replayGainPreAmpDb,
            preAmpUntaggedDb = replayGainUntaggedPreAmpDb,
            preventClipping = replayGainPreventClipping,
            enableFallbackAnalysis = replayGainFallbackAnalysis,
            targetLoudnessLufs = -18.0f
        )
    }

    // --- True Gapless Audio ---
    var isGaplessEnabled: Boolean
        get() = prefs.getBoolean("is_gapless_enabled", true)
        set(value) = prefs.edit().putBoolean("is_gapless_enabled", value).apply()

    var isGaplessAlbumPriority: Boolean
        get() = prefs.getBoolean("is_gapless_album_priority", true)
        set(value) = prefs.edit().putBoolean("is_gapless_album_priority", value).apply()

    // --- Multi-Artist, Multi-Genre & Protected Whitelist ---
    private val _multiTagVersion = MutableStateFlow(0)
    val multiTagVersion: StateFlow<Int> = _multiTagVersion.asStateFlow()

    private fun bumpMultiTagVersion() {
        _multiTagVersion.value = _multiTagVersion.value + 1
    }

    var isMultiArtistEnabled: Boolean
        get() = prefs.getBoolean("multi_artist_enabled", true)
        set(value) {
            prefs.edit().putBoolean("multi_artist_enabled", value).apply()
            bumpMultiTagVersion()
        }

    var isMultiGenreEnabled: Boolean
        get() = prefs.getBoolean("multi_genre_enabled", true)
        set(value) {
            prefs.edit().putBoolean("multi_genre_enabled", value).apply()
            bumpMultiTagVersion()
        }

    var splitBySemicolon: Boolean
        get() = prefs.getBoolean("split_semicolon", true)
        set(value) {
            prefs.edit().putBoolean("split_semicolon", value).apply()
            bumpMultiTagVersion()
        }

    var splitByComma: Boolean
        get() = prefs.getBoolean("split_comma", true)
        set(value) {
            prefs.edit().putBoolean("split_comma", value).apply()
            bumpMultiTagVersion()
        }

    var splitBySlash: Boolean
        get() = prefs.getBoolean("split_slash", true)
        set(value) {
            prefs.edit().putBoolean("split_slash", value).apply()
            bumpMultiTagVersion()
        }

    var splitByAmpersand: Boolean
        get() = prefs.getBoolean("split_ampersand", true)
        set(value) {
            prefs.edit().putBoolean("split_ampersand", value).apply()
            bumpMultiTagVersion()
        }

    var splitByFeatKeywords: Boolean
        get() = prefs.getBoolean("split_feat_keywords", true)
        set(value) {
            prefs.edit().putBoolean("split_feat_keywords", value).apply()
            bumpMultiTagVersion()
        }

    var extractArtistsFromTitle: Boolean
        get() = prefs.getBoolean("extract_artists_from_title", true)
        set(value) {
            prefs.edit().putBoolean("extract_artists_from_title", value).apply()
            bumpMultiTagVersion()
        }

    var cleanExtractedFeatFromTitle: Boolean
        get() = prefs.getBoolean("clean_extracted_feat_title", false)
        set(value) {
            prefs.edit().putBoolean("clean_extracted_feat_title", value).apply()
            bumpMultiTagVersion()
        }

    var customArtistWhitelist: Set<String>
        get() = prefs.getStringSet("custom_artist_whitelist", emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("custom_artist_whitelist", value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
            bumpMultiTagVersion()
        }

    fun addArtistToWhitelist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            customArtistWhitelist = customArtistWhitelist + trimmed
        }
    }

    fun removeArtistFromWhitelist(name: String) {
        customArtistWhitelist = customArtistWhitelist.filterNot { it.equals(name.trim(), ignoreCase = true) }.toSet()
    }

    fun toMultiTagConfig(): com.demonlab.lune.core.model.MultiTagConfig {
        return com.demonlab.lune.core.model.MultiTagConfig(
            enableMultiArtist = isMultiArtistEnabled,
            enableMultiGenre = isMultiGenreEnabled,
            splitSemicolon = splitBySemicolon,
            splitComma = splitByComma,
            splitSlash = splitBySlash,
            splitAmpersand = splitByAmpersand,
            splitFeatKeywords = splitByFeatKeywords,
            extractArtistsFromTitle = extractArtistsFromTitle,
            cleanExtractedFeatFromTitle = cleanExtractedFeatFromTitle,
            customWhitelist = customArtistWhitelist
        )
    }

    // --- Modest / Legacy Hardware Performance Profile ---
    private val _performanceProfileState = mutableStateOf(prefs.getInt("performance_profile", 0))
    var performanceProfile: Int
        get() = _performanceProfileState.value // 0=AUTO, 1=ULTRA_SAVER, 2=LIGHT, 3=FULL
        set(value) {
            val coerced = value.coerceIn(0, 3)
            _performanceProfileState.value = coerced
            prefs.edit().putInt("performance_profile", coerced).apply()
        }

    fun resolveEffectivePerformanceProfile(context: Context): com.demonlab.lune.core.model.PerformanceProfile {
        return when (performanceProfile) {
            1 -> com.demonlab.lune.core.model.PerformanceProfile.ULTRA_SAVER
            2 -> com.demonlab.lune.core.model.PerformanceProfile.LIGHT
            3 -> com.demonlab.lune.core.model.PerformanceProfile.FULL
            else -> {
                val cores = Runtime.getRuntime().availableProcessors()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                val totalRamMb = if (memInfo.totalMem > 0) memInfo.totalMem / (1024L * 1024L) else 4096L
                val isLowRam = am?.isLowRamDevice == true
                when {
                    cores <= 1 || totalRamMb <= 1600L -> com.demonlab.lune.core.model.PerformanceProfile.ULTRA_SAVER
                    isLowRam || cores <= 2 || totalRamMb <= 2800L || android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1 ->
                        com.demonlab.lune.core.model.PerformanceProfile.LIGHT
                    else -> com.demonlab.lune.core.model.PerformanceProfile.FULL
                }
            }
        }
    }

    fun isLowResourceVisualsActive(context: Context): Boolean {
        val p = resolveEffectivePerformanceProfile(context)
        return p == com.demonlab.lune.core.model.PerformanceProfile.ULTRA_SAVER ||
            p == com.demonlab.lune.core.model.PerformanceProfile.LIGHT
    }

    fun isUltraSaverActive(context: Context): Boolean {
        return resolveEffectivePerformanceProfile(context) == com.demonlab.lune.core.model.PerformanceProfile.ULTRA_SAVER
    }

    // --- Universal Adaptive UI & Density Customization ---
    private val _uiDensityModeState = mutableStateOf(prefs.getInt("ui_density_mode", 1))
    var uiDensityMode: Int
        get() = _uiDensityModeState.value // 0=COMPACT, 1=STANDARD, 2=COMFORTABLE
        set(value) {
            val coerced = value.coerceIn(0, 2)
            _uiDensityModeState.value = coerced
            prefs.edit().putInt("ui_density_mode", coerced).apply()
        }

    private val _uiFontScaleState = mutableStateOf(prefs.getFloat("ui_font_scale", 1.0f))
    var uiFontScale: Float
        get() = _uiFontScaleState.value
        set(value) {
            val coerced = value.coerceIn(0.85f, 1.25f)
            _uiFontScaleState.value = coerced
            prefs.edit().putFloat("ui_font_scale", coerced).apply()
        }

    private val _gridColumnsCountState = mutableStateOf(prefs.getInt("grid_columns_count", 0))
    var gridColumnsCount: Int
        get() = _gridColumnsCountState.value // 0=AUTO_ADAPTIVE, 2..6=Fixed
        set(value) {
            val coerced = if (value == 0) 0 else value.coerceIn(2, 6)
            _gridColumnsCountState.value = coerced
            prefs.edit().putInt("grid_columns_count", coerced).apply()
        }

    // --- Ergonomic UI Aliases ---
    var replayGainPreampDb: Float
        get() = replayGainPreAmpDb
        set(value) { replayGainPreAmpDb = value }

    var replayGainUntaggedPreampDb: Float
        get() = replayGainUntaggedPreAmpDb
        set(value) { replayGainUntaggedPreAmpDb = value }

    var replayGainRmsFallback: Boolean
        get() = replayGainFallbackAnalysis
        set(value) { replayGainFallbackAnalysis = value }

    var isGaplessSmartAlbum: Boolean
        get() = isGaplessAlbumPriority
        set(value) { isGaplessAlbumPriority = value }

    var enableMultiArtist: Boolean
        get() = isMultiArtistEnabled
        set(value) { isMultiArtistEnabled = value }

    var enableMultiGenre: Boolean
        get() = isMultiGenreEnabled
        set(value) { isMultiGenreEnabled = value }

    var cleanFeatFromDisplayTitle: Boolean
        get() = cleanExtractedFeatFromTitle
        set(value) { cleanExtractedFeatFromTitle = value }

    var tagWhitelist: Set<String>
        get() = customArtistWhitelist
        set(value) { customArtistWhitelist = value }

    fun addTagToWhitelist(name: String) = addArtistToWhitelist(name)

    fun removeTagFromWhitelist(name: String) = removeArtistFromWhitelist(name)

    var performanceMode: Int
        get() = performanceProfile
        set(value) { performanceProfile = value }

    fun resolvePerformanceProfile(context: Context): com.demonlab.lune.core.model.PerformanceProfile =
        resolveEffectivePerformanceProfile(context)

    fun notifyReplayGainUpdated() = bumpReplayGainVersion()

    companion object {
        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}