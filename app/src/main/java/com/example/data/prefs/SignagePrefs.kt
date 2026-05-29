package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences

class SignagePrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("signage_player_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTOSTART = "autostart_enabled"
        private const val KEY_WATCHDOG = "watchdog_enabled"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SLEEP_HOUR = "sleep_hour"
        private const val KEY_SLEEP_MINUTE = "sleep_minute"
        private const val KEY_WAKE_HOUR = "wake_hour"
        private const val KEY_WAKE_MINUTE = "wake_minute"
        private const val DEFAULT_SERVER_URL = "https://app.slidetv.eu/player"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LAST_SLEEP_CMD = "last_sleep_command_at"
        private const val KEY_LAST_WAKE_CMD = "last_wake_command_at"
        private const val KEY_LAST_RELOAD_CMD = "last_reload_command_at"
        private const val KEY_LAST_CLEAR_CACHE_CMD = "last_clear_cache_command_at"
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var isAutostartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("is_first_launch", true)
        set(value) = prefs.edit().putBoolean("is_first_launch", value).apply()

    var isWatchdogEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCHDOG, true)
        set(value) = prefs.edit().putBoolean(KEY_WATCHDOG, value).apply()

    var isScheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()

    var sleepHour: Int
        get() = prefs.getInt(KEY_SLEEP_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_SLEEP_HOUR, value).apply()

    var sleepMinute: Int
        get() = prefs.getInt(KEY_SLEEP_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_SLEEP_MINUTE, value).apply()

    var wakeHour: Int
        get() = prefs.getInt(KEY_WAKE_HOUR, 8)
        set(value) = prefs.edit().putInt(KEY_WAKE_HOUR, value).apply()

    var wakeMinute: Int
        get() = prefs.getInt(KEY_WAKE_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_WAKE_MINUTE, value).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var lastSleepCommandAt: Long
        get() = prefs.getLong(KEY_LAST_SLEEP_CMD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SLEEP_CMD, value).apply()

    var lastWakeCommandAt: Long
        get() = prefs.getLong(KEY_LAST_WAKE_CMD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_WAKE_CMD, value).apply()

    var lastReloadCommandAt: Long
        get() = prefs.getLong(KEY_LAST_RELOAD_CMD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RELOAD_CMD, value).apply()

    var lastClearCacheCommandAt: Long
        get() = prefs.getLong(KEY_LAST_CLEAR_CACHE_CMD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CLEAR_CACHE_CMD, value).apply()

    // Derives API base URL from serverUrl (e.g. "https://app.slidetv.eu/player" → "https://app.slidetv.eu")
    val apiBaseUrl: String
        get() {
            val parsed = android.net.Uri.parse(serverUrl)
            return "${parsed.scheme}://${parsed.host}"
        }
}

