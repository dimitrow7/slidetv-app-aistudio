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
}

