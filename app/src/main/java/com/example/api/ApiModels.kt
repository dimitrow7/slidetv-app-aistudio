package com.example.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InitResponse(
    val status: String = "",
    @Json(name = "device_token") val deviceToken: String? = null,
    @Json(name = "pairing_code") val pairingCode: String? = null,
    @Json(name = "expires_in") val expiresIn: Int = 0
)

@JsonClass(generateAdapter = true)
data class OperatingHours(
    val enabled: Boolean = false,
    @Json(name = "sleep_hour") val sleepHour: Int = 22,
    @Json(name = "sleep_minute") val sleepMinute: Int = 0,
    @Json(name = "wake_hour") val wakeHour: Int = 8,
    @Json(name = "wake_minute") val wakeMinute: Int = 0
)

@JsonClass(generateAdapter = true)
data class PollResponse(
    val status: String = "",
    @Json(name = "command_sleep_at") val commandSleepAt: Long = 0,
    @Json(name = "command_wake_at") val commandWakeAt: Long = 0,
    @Json(name = "command_reload_at") val commandReloadAt: Long = 0,
    @Json(name = "command_clear_cache_at") val commandClearCacheAt: Long = 0,
    @Json(name = "operating_hours") val operatingHours: OperatingHours? = null
)

@JsonClass(generateAdapter = true)
data class PollRequestBody(
    @Json(name = "app_version") val appVersion: String,
    @Json(name = "version_code") val versionCode: Int,
    @Json(name = "device_type") val deviceType: String = "android"
)
