package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.di.Graph

private const val PREF = "cache_settings_prefs"
private const val KEY_PRELOADING_ENABLED = "preloading_enabled"
private const val KEY_PRELOAD_MINUTES = "preload_minutes"
private const val KEY_CACHE_CAP_MB = "cache_cap_mb"

/** Preferences for proactive playback caching and queue preloading. */
object CacheSettingsPref {
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var preloadingEnabled by mutableStateOf(prefs(Graph.app).getBoolean(KEY_PRELOADING_ENABLED, true))
        private set

    /** How much listening ahead is kept on the phone, in minutes (default: 2 hours). */
    var preloadMinutes by mutableStateOf(prefs(Graph.app).getInt(KEY_PRELOAD_MINUTES, 120).coerceIn(15, 240))
        private set

    /** Cache cap in MB: 500, 1000, 2000, 5000, or 0 for Unlimited. Default: 5 GB (room for the 500 songs kept). */
    var cacheCapMb by mutableStateOf(prefs(Graph.app).getInt(KEY_CACHE_CAP_MB, 5000))
        private set

    fun setEnabled(c: Context, enabled: Boolean) {
        preloadingEnabled = enabled
        prefs(c).edit().putBoolean(KEY_PRELOADING_ENABLED, enabled).apply()
    }

    fun setMinutes(c: Context, minutes: Int) {
        val v = minutes.coerceIn(15, 240)
        preloadMinutes = v
        prefs(c).edit().putInt(KEY_PRELOAD_MINUTES, v).apply()
    }

    fun setCapMb(c: Context, capMb: Int) {
        cacheCapMb = capMb
        prefs(c).edit().putInt(KEY_CACHE_CAP_MB, capMb).apply()
    }
}
