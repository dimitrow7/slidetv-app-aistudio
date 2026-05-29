# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

SlideTV Player is an Android digital signage app for TVs and tablets. It is a single-Activity kiosk app that displays a full-screen WebView pointing at `https://app.slidetv.eu/player`, with offline media caching, boot auto-start, and a hidden admin panel.

- **Min SDK**: 24 / **Target SDK**: 36 / **Compile SDK**: 36 (minorApiLevel 1)
- **Language**: Kotlin 2.2.10 + Jetpack Compose (no XML layouts; only `AndroidManifest.xml`)
- **Build**: Gradle Kotlin DSL, AGP 9.1.1

## Setup

1. Copy `.env.example` to `.env` and set your `GEMINI_API_KEY`.
2. In `app/build.gradle.kts`, **remove** the line `signingConfig = signingConfigs.getByName("debugConfig")` from the `debug` block before running a debug build locally (the `debugConfig` signing uses a pre-committed keystore only present in CI/AI Studio).
3. Open in Android Studio and run on an emulator or physical device.

## Commands

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease   # requires KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars

# Lint (abortOnError is false; MissingTvBanner is suppressed)
./gradlew lint

# Unit tests (Robolectric, runs on JVM)
./gradlew :app:test
./gradlew :app:test --tests "com.example.ExampleUnitTest"

# Screenshot tests (Roborazzi)
./gradlew :app:recordRoborazziDebug   # record new baselines
./gradlew :app:verifyRoborazziDebug   # compare against baselines

# Instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest
```

## Architecture

There is a single module (`:app`) and a single Activity. There are no navigation components, no ViewModels, and no Compose screen files beyond `MainActivity.kt`.

### Core files

**`MainActivity.kt`** — contains everything UI-related: the full-screen `WebView` inside a Compose `AndroidView`, the hidden `SettingsDialog` (AlertDialog with three tabs: System, Schedule, Info), and all `@Composable` helpers (`SettingsDialog`, `TimeAdjustmentRow`, `KioskDiagnosticsSection`, `DiagnosticRow`, `SlideTVBrandingHeader`). All runtime state lives here in `remember`/`mutableStateOf` — there is no ViewModel layer.

**`SignageWebViewClient.kt`** — custom `WebViewClient` that intercepts all GET requests to cache resources locally in `context.cacheDir/signage_media_cache/`. Two strategies:
- **Static media** (`media.slidetv.eu` or image/video/audio extensions): cache-first — serves from disk if present, downloads otherwise.
- **App resources** (`*.slidetv.eu` non-media, or JS/CSS/HTML/fonts): network-first with offline cache fallback.

Files are stored as `{urlHash}.{extension}` with an intermediate `.tmp` suffix during download. Also registers a `ServiceWorkerController` client so PWA service worker fetches are intercepted the same way (service worker requests bypass `shouldInterceptRequest` otherwise).

**`ScheduleManager.kt`** — singleton `object` that schedules/cancels `AlarmManager` exact alarms for daily sleep and wake events. Handles all API-level variations (pre-M, M–R, S+, including `canScheduleExactAlarms()` check). Call `updateAlarms(context)` any time schedule prefs change.

**`ScheduleReceiver.kt`** — `BroadcastReceiver` for alarm triggers. Acquires a `PARTIAL_WAKE_LOCK`, reschedules the next day's alarms, starts `MainActivity` directly (via `FLAG_ACTIVITY_NEW_TASK`), and also fires a high-priority `FullScreenIntent` notification as a fallback for locked-screen wake-up.

**`BootCompletedReceiver.kt`** — `BroadcastReceiver` for `BOOT_COMPLETED` / `QUICKBOOT_POWERON`. Launches `MainActivity` if `SignagePrefs.isAutostartEnabled` is true. Handles Lenovo, Samsung, Xiaomi, Huawei, Oppo, and Vivo manufacturer-specific autostart intents in `launchAutoStartSettings()`.

**`data/prefs/SignagePrefs.kt`** — thin `SharedPreferences` wrapper (file: `signage_player_prefs`). Exposes: `serverUrl`, `isAutostartEnabled`, `isFirstLaunch`, `isWatchdogEnabled`, `isScheduleEnabled`, `sleepHour`, `sleepMinute`, `wakeHour`, `wakeMinute`.

### Key runtime behaviors

**Watchdog**: A JS heartbeat interval (`window.SlideTVWatchdogInterval`) calls `SlideTVAndroid.pingWatchdog()` (JavascriptInterface) every 15 s. A Kotlin coroutine in `MainActivity` checks the last ping every 10 s and force-reloads if >75 s have elapsed without a ping. A second coroutine re-injects the heartbeat script every 30 s to survive page navigations.

**Sleep/wake dual mechanism**: `ScheduleManager` sets `AlarmManager` alarms for the next sleep/wake times (self-rescheduling daily). Inside `MainActivity`, a `LaunchedEffect` polls the schedule state every 10 s as a soft fallback. The `isSleepingState` drives: screen brightness (0.01 f when sleeping), `FLAG_KEEP_SCREEN_ON` toggle, and `WebView.onPause()`/`onResume()`. On wake, the WebView is reloaded.

**Hidden admin panel**: Opens on either 5 quick taps on the top-right 100 dp corner within 2.5 s, or 5 DPAD_CENTER/Enter key presses within 2.5 s, or a MENU/SETTINGS key. All settings have a local draft state inside the dialog; changes only commit to `SignagePrefs` on "Save" (pink button).
