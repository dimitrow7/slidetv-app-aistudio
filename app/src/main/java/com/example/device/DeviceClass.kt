package com.example.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * How the player treats the hardware it runs on. Drives which permission rows,
 * power guidance and launcher option the admin panel shows.
 *
 * A built-in TV panel and an Android box feeding a TV over HDMI cannot be told
 * apart reliably at runtime — both report leanback / UI_MODE_TYPE_TELEVISION —
 * so [suggest] only proposes a default and the user confirms it once at first
 * launch. The stored choice lives in `SignagePrefs.deviceClass`.
 */
enum class DeviceClass {
    /** Built-in Android TV panel. An app can't power the panel itself (no CEC). */
    TV,

    /** Android box driving a TV over HDMI — relies on the box's own HDMI-CEC. */
    BOX,

    /** Phone / tablet — battery-optimisation and OEM autostart rows apply here. */
    HANDHELD;

    /** Lowercase id persisted in prefs / used across the wire. */
    val id: String get() = name.lowercase()

    /** TV and BOX share the "10-foot" treatment (no battery row, launcher option). */
    val isTvMode: Boolean get() = this == TV || this == BOX

    companion object {
        /** Parse a stored id back to a class; null when unset/unknown. */
        fun fromId(id: String?): DeviceClass? {
            val key = id?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.id == key }
        }

        /**
         * Pure heuristic — no Android calls, so it is host-testable. A "10-foot"
         * device (leanback, television UI mode, or no touchscreen) defaults to TV
         * because TV is the more constrained class; the user can switch to BOX.
         */
        fun classify(isLeanback: Boolean, isTelevisionUiMode: Boolean, hasTouchscreen: Boolean): DeviceClass =
            if (isLeanback || isTelevisionUiMode || !hasTouchscreen) TV else HANDHELD

        /** Suggested default for this hardware, read from real system signals. */
        fun suggest(context: Context): DeviceClass {
            val pm = context.packageManager
            val isLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            val isTvUiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            return classify(isLeanback, isTvUiMode, hasTouch)
        }

        /** The class to use: the user's stored choice, else the heuristic suggestion. */
        fun resolve(context: Context, storedId: String?): DeviceClass =
            fromId(storedId) ?: suggest(context)
    }
}
