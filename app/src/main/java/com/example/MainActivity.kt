package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import com.example.data.prefs.SignagePrefs
import com.example.log.DeviceLogger
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogProperties
import com.example.api.DeviceApiClient
import com.example.api.PollRequestBody
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SignagePrefs
    private var webView: WebView? = null
    private val showSettingsState = mutableStateOf(false)
    private val isSleepingState = mutableStateOf(false)

    // Remote OK key tracking for hidden menu (5 clicks within 2.5 seconds)
    private var okClickCount = 0
    private var lastOkClickTime = 0L

    // Throttle for forwarding native touches to the web player's kiosk-activity hook.
    private var lastKioskPingTime = 0L

    // Thread-safe atomic tracker for JS watchdog pings (crucial for JavaBridge background thread safety)
    private val lastWatchdogPingTime = AtomicLong(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SignagePrefs(this)
        
        // Migrate old default URL to player page
        if (prefs.serverUrl == "https://app.slidetv.eu") {
            prefs.serverUrl = "https://app.slidetv.eu/player"
        }

        // Start the log shipper. It stays disabled until a poll reports
        // logs_enabled=true, so this only spins the flush loop.
        DeviceLogger.start(prefs.apiBaseUrl)

        // Initialize isSleepingState based on current time schedule
        isSleepingState.value = ScheduleManager.isCurrentlyInSleepSchedule(this)

        // Initialize / sync alarms on creation or reboot
        ScheduleManager.updateAlarms(this)
        handleScheduleIntent(intent)

        // Show hidden admin console automatically on first launch
        if (prefs.isFirstLaunch) {
            showSettingsState.value = true
        }
        
        // Prevent screen dimming and keep device screen on permanently
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                var currentUrl by remember { mutableStateOf(prefs.serverUrl) }
                var showSettings by showSettingsState
                
                // Track watchdog state updates dynamically to reset or activate checking loop
                var isWatchdogRunning by remember { mutableStateOf(prefs.isWatchdogEnabled) }

                // Track sleep/wake schedule state dynamically
                var isScheduleEnabled by remember { mutableStateOf(prefs.isScheduleEnabled) }
                var sleepHour by remember { mutableStateOf(prefs.sleepHour) }
                var sleepMinute by remember { mutableStateOf(prefs.sleepMinute) }
                var wakeHour by remember { mutableStateOf(prefs.wakeHour) }
                var wakeMinute by remember { mutableStateOf(prefs.wakeMinute) }

                var isSleeping by isSleepingState
                var wasSleeping by remember { mutableStateOf(false) }

                // Manual Sleep/Wake command override (true = sleep, false = wake, null = none).
                // While set, the schedule monitor will NOT auto-change the sleep state until the
                // schedule naturally reaches the same value. Without this, a manual Wake Up during
                // the sleep window gets reverted to sleep by the monitor within ~10 seconds.
                var scheduleOverrideState by remember { mutableStateOf<Boolean?>(null) }

                // Baseline guard: on the first poll of a session we adopt the server's current
                // command timestamps without acting on them, so historical commands aren't
                // replayed all at once right after pairing or an app restart.
                var commandsBaselined by remember { mutableStateOf(false) }

                // Intercept Android Back button to show settings or let WebView go back
                BackHandler {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        showSettings = true
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                configureSettings()
                                
                                // Setup Javascript Watchdog Interface (runs on JavaBridge thread)
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun pingWatchdog() {
                                        lastWatchdogPingTime.set(System.currentTimeMillis())
                                    }
                                }, "SlideTVAndroid")

                                // Read through the lambda so a limit changed in Settings
                                // applies to the next sweep without recreating the client.
                                webViewClient = SignageWebViewClient(
                                    ctx,
                                    settings.userAgentString,
                                    { prefs.cacheLimitBytes }
                                ) { view, url ->
                                    lastWatchdogPingTime.set(System.currentTimeMillis())
                                    // Inject JavaScript Heartbeat loop once loaded
                                    injectWatchdogHeartbeatScript(view)
                                }
                                loadUrl(currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isSleeping) {
                        // Pitch black visual shield covering the entire screen to protect pixels/backlight
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }

                    // Re-injection timer to ensure JS Heartbeat is running (runs only when watchdog is enabled)
                    LaunchedEffect(isWatchdogRunning) {
                        if (isWatchdogRunning) {
                            while (true) {
                                kotlinx.coroutines.delay(30000)
                                webView?.post {
                                    injectWatchdogHeartbeatScript(webView)
                                }
                            }
                        }
                    }

                    // Watchdog Monitoring Loop checks if the tab crashed, froze or isn't responsive (runs only when watchdog is enabled)
                    LaunchedEffect(isWatchdogRunning) {
                        if (isWatchdogRunning) {
                            while (true) {
                                kotlinx.coroutines.delay(10000)
                                val now = System.currentTimeMillis()
                                val lastPing = lastWatchdogPingTime.get()
                                val timeDiff = now - lastPing
                                
                                // 75 seconds threshold
                                if (timeDiff > 75000) {
                                    Log.w("SlideTVWatchdog", "Watchdog triggered. No ping for $timeDiff ms. Force refreshing...")
                                    DeviceLogger.log("watchdog: no ping for ${timeDiff}ms, reloading")
                                    webView?.post {
                                        webView?.reload()
                                        injectWatchdogHeartbeatScript(webView)
                                    }
                                    lastWatchdogPingTime.set(System.currentTimeMillis()) // Reset clock to avoid loop
                                }
                            }
                        }
                    }

                    // Schedule Monitoring Loop (computes state dynamically based on system time)
                    LaunchedEffect(isScheduleEnabled, sleepHour, sleepMinute, wakeHour, wakeMinute) {
                        ScheduleManager.updateAlarms(context)
                        if (isScheduleEnabled) {
                            while (true) {
                                val calendar = java.util.Calendar.getInstance()
                                val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                                val currentMinute = calendar.get(java.util.Calendar.MINUTE)
                                
                                val sleepTimeStart = sleepHour * 60 + sleepMinute
                                val wakeTimeStart = wakeHour * 60 + wakeMinute
                                val currentTimeVal = currentHour * 60 + currentMinute
                                
                                val targetSleepState = if (sleepTimeStart < wakeTimeStart) {
                                    currentTimeVal in sleepTimeStart until wakeTimeStart
                                } else if (sleepTimeStart > wakeTimeStart) {
                                    currentTimeVal >= sleepTimeStart || currentTimeVal < wakeTimeStart
                                } else {
                                    false
                                }
                                
                                // Release a manual override once the schedule naturally agrees with it.
                                if (scheduleOverrideState != null && targetSleepState == scheduleOverrideState) {
                                    scheduleOverrideState = null
                                }

                                // Only auto-apply the scheduled state when no manual override is active.
                                if (scheduleOverrideState == null && isSleeping != targetSleepState) {
                                    isSleeping = targetSleepState
                                    Log.d("SlideTVSchedule", "Schedule state updated. Is Sleeping: $targetSleepState")
                                    DeviceLogger.log(if (targetSleepState) "scheduled sleep" else "scheduled wake")
                                }
                                kotlinx.coroutines.delay(10000) // check every 10 seconds
                            }
                        } else {
                            if (isSleeping) {
                                isSleeping = false
                                Log.d("SlideTVSchedule", "Schedule disabled. Reset isSleeping to false.")
                            }
                        }
                    }

                    // Active Sleep / Wake effect on WebView and Screen Brightness
                    LaunchedEffect(isSleeping, showSettings) {
                        val targetSleepScreen = isSleeping && !showSettings
                        val activity = context as? ComponentActivity ?: return@LaunchedEffect
                        activity.runOnUiThread {
                            val layoutParams = activity.window.attributes
                            if (targetSleepScreen) {
                                layoutParams.screenBrightness = 0.01f
                                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                webView?.onPause()
                                Log.d("SlideTVSchedule", "Applied sleep mode screen brightness 0.01 and paused WebView")
                            } else {
                                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                webView?.onResume()
                                Log.d("SlideTVSchedule", "Restored full awake brightness, enabled KEEP_SCREEN_ON, and resumed WebView")
                            }
                            activity.window.attributes = layoutParams
                        }
                    }

                    // WebView refresh trigger upon wakes
                    LaunchedEffect(isSleeping) {
                        if (wasSleeping && !isSleeping) {
                            webView?.reload()
                            lastWatchdogPingTime.set(System.currentTimeMillis())
                            Log.d("SlideTVSchedule", "Woke up from scheduled sleep. Reloading WebView.")
                        }
                        wasSleeping = isSleeping
                    }

                    // Touch monitor (clickable box): 5 quick taps inside 2.5 seconds in the top-right corner to open hidden settings
                    var topRightTapCount by remember { mutableStateOf(0) }
                    var lastTopRightTapTime by remember { mutableStateOf(0L) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(100.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val now = System.currentTimeMillis()
                                if (now - lastTopRightTapTime < 2500) {
                                    topRightTapCount++
                                } else {
                                    topRightTapCount = 1
                                }
                                lastTopRightTapTime = now
                                Log.d("SlideTV_Taps", "Top right corner tap registered: $topRightTapCount")
                                if (topRightTapCount >= 5) {
                                    topRightTapCount = 0
                                    showSettings = true
                                }
                            }
                    )

                    // Remote device polling — reuse the embedded web player's identity.
                    // The web player (loaded in the WebView) pairs the screen and stores its
                    // device token as the `slidetv_device_token` cookie. We reuse THAT token so
                    // the native shell polls the SAME screen the player is paired to. Calling
                    // /api/device/init here instead would create a SEPARATE, unpaired screen
                    // that never receives the sleep/wake schedule or remote commands.
                    LaunchedEffect(Unit) {
                        val api = DeviceApiClient.getService(prefs.apiBaseUrl)

                        // Log network trouble on state change only: one line on the
                        // first failure, then silence, then one on recovery. Logging
                        // every failed poll would flood the ~500-line buffer during a
                        // long outage — exactly when the earlier history matters most.
                        var networkDown = false

                        // Same state-change pattern for token resolution: log once when
                        // the loop goes idle for want of a token, and once when it starts
                        // running on the stored token because the cookie has vanished.
                        var tokenMissingLogged = false
                        var usingStoredTokenLogged = false

                        // Polling loop
                        while (true) {
                            kotlinx.coroutines.delay(30_000)

                            // Resolve the device token once. The web player's cookie is the
                            // source of truth, but it lives in memory and Android writes cookies
                            // to disk only on flush() — a force stop wipes it while localStorage
                            // (and our persisted copy) survive. Fall back to the last token we
                            // stored so the poll loop keeps running instead of idling silently.
                            val cookieToken = readCookieDeviceToken()
                            val token = cookieToken ?: prefs.deviceToken.takeIf { it.isNotBlank() }

                            if (token.isNullOrEmpty()) {
                                // No token by any path — the loop can't poll. Log once on the
                                // state change (not every 30s) then idle until one appears.
                                if (!tokenMissingLogged) {
                                    tokenMissingLogged = true
                                    Log.w("SlideTVPolling", "No device token from cookie or prefs — poll loop idle")
                                    DeviceLogger.log("no device token — poll loop idle")
                                }
                                continue
                            }
                            tokenMissingLogged = false

                            if (cookieToken != null) {
                                usingStoredTokenLogged = false
                                if (prefs.deviceToken != cookieToken) {
                                    // New token from the web player — persist it AND flush so it
                                    // survives a force stop, not just this process. "paired" is
                                    // logged inside DeviceLogger.updateConfig once logging is
                                    // actually enabled — see the comment there.
                                    prefs.deviceToken = cookieToken
                                    CookieManager.getInstance().flush()
                                    Log.d("SlideTVPolling", "Using web player device token: $cookieToken")
                                }
                            } else if (!usingStoredTokenLogged) {
                                // Cookie gone (e.g. force stop cleared it) — running on the
                                // stored token. Log once so the missing cookie is visible even
                                // while everything still works.
                                usingStoredTokenLogged = true
                                Log.w("SlideTVPolling", "token cookie missing, using stored token")
                                DeviceLogger.log("token cookie missing, using stored token")
                            }

                            try {
                                val pollResp = withContext(Dispatchers.IO) {
                                    api.poll(
                                        token,
                                        PollRequestBody(
                                            appVersion = BuildConfig.VERSION_NAME,
                                            versionCode = BuildConfig.VERSION_CODE
                                        )
                                    )
                                }

                                // Poll succeeded — note recovery if we were offline.
                                if (networkDown) {
                                    networkDown = false
                                    DeviceLogger.log("network recovered")
                                }

                                // Device was deleted from SaaS — clear token and re-init next cycle
                                if (pollResp.status == "unpaired") {
                                    Log.w("SlideTVPolling", "Screen unpaired from SaaS. Clearing token.")
                                    DeviceLogger.log("unpaired from SaaS")
                                    prefs.deviceToken = ""
                                    continue
                                }

                                // Server decides whether this screen ships logs.
                                DeviceLogger.updateConfig(pollResp.logsEnabled, token)

                                // First poll of this session: adopt the server's current command
                                // timestamps as the baseline so historical commands don't all fire
                                // at once on (re)pair or restart (which would, e.g., set a spurious
                                // wake override that blocks the schedule from ever sleeping).
                                if (!commandsBaselined) {
                                    prefs.lastSleepCommandAt = pollResp.commandSleepAt
                                    prefs.lastWakeCommandAt = pollResp.commandWakeAt
                                    prefs.lastReloadCommandAt = pollResp.commandReloadAt
                                    prefs.lastClearCacheCommandAt = pollResp.commandClearCacheAt
                                    commandsBaselined = true
                                }

                                // Remote sleep command — manual override: stay asleep until the
                                // schedule itself reaches the sleep state.
                                if (pollResp.commandSleepAt > prefs.lastSleepCommandAt) {
                                    isSleepingState.value = true
                                    scheduleOverrideState = true
                                    prefs.lastSleepCommandAt = pollResp.commandSleepAt
                                    Log.d("SlideTVPolling", "Remote sleep command executed.")
                                    DeviceLogger.log("remote sleep command")
                                }

                                // Remote wake command — manual override: stay awake until the
                                // schedule itself reaches the wake state (otherwise the monitor
                                // re-sleeps the screen within ~10s during the sleep window).
                                if (pollResp.commandWakeAt > prefs.lastWakeCommandAt) {
                                    isSleepingState.value = false
                                    scheduleOverrideState = false
                                    wakeHardwareScreen()
                                    prefs.lastWakeCommandAt = pollResp.commandWakeAt
                                    Log.d("SlideTVPolling", "Remote wake command executed.")
                                    DeviceLogger.log("remote wake command")
                                }

                                // Remote reload command
                                if (pollResp.commandReloadAt > prefs.lastReloadCommandAt) {
                                    webView?.reload()
                                    lastWatchdogPingTime.set(System.currentTimeMillis())
                                    prefs.lastReloadCommandAt = pollResp.commandReloadAt
                                    Log.d("SlideTVPolling", "Remote reload command executed.")
                                    DeviceLogger.log("remote reload (manifest refresh)")
                                }

                                // Remote clear cache command
                                if (pollResp.commandClearCacheAt > prefs.lastClearCacheCommandAt) {
                                    webView?.clearCache(true)
                                    try {
                                        val cacheDir = File(context.cacheDir, "signage_media_cache")
                                        if (cacheDir.exists()) cacheDir.deleteRecursively()
                                        cacheDir.mkdirs()
                                    } catch (e: Exception) { e.printStackTrace() }
                                    webView?.reload()
                                    lastWatchdogPingTime.set(System.currentTimeMillis())
                                    prefs.lastClearCacheCommandAt = pollResp.commandClearCacheAt
                                    Log.d("SlideTVPolling", "Remote clear-cache command executed.")
                                    DeviceLogger.log("remote clear-cache command")
                                }

                                // Sync schedule from SaaS operating_hours (overrides local settings)
                                pollResp.operatingHours?.let { oh ->
                                    if (prefs.isScheduleEnabled != oh.enabled ||
                                        prefs.sleepHour != oh.sleepHour ||
                                        prefs.sleepMinute != oh.sleepMinute ||
                                        prefs.wakeHour != oh.wakeHour ||
                                        prefs.wakeMinute != oh.wakeMinute
                                    ) {
                                        prefs.isScheduleEnabled = oh.enabled
                                        prefs.sleepHour = oh.sleepHour
                                        prefs.sleepMinute = oh.sleepMinute
                                        prefs.wakeHour = oh.wakeHour
                                        prefs.wakeMinute = oh.wakeMinute
                                        isScheduleEnabled = oh.enabled
                                        sleepHour = oh.sleepHour
                                        sleepMinute = oh.sleepMinute
                                        wakeHour = oh.wakeHour
                                        wakeMinute = oh.wakeMinute
                                        ScheduleManager.updateAlarms(context)
                                        Log.d("SlideTVPolling", "Schedule synced from SaaS: enabled=${oh.enabled} sleep=${oh.sleepHour}:${oh.sleepMinute} wake=${oh.wakeHour}:${oh.wakeMinute}")
                                        DeviceLogger.log("schedule synced from SaaS (enabled=${oh.enabled} sleep=${oh.sleepHour}:${oh.sleepMinute} wake=${oh.wakeHour}:${oh.wakeMinute})")
                                    }
                                }

                            } catch (e: Exception) {
                                Log.e("SlideTVPolling", "Poll failed: ${e.message}")
                                if (!networkDown) {
                                    networkDown = true
                                    DeviceLogger.log("network error: poll failed (${e.message})")
                                }
                            }
                        }
                    }

                    if (showSettings) {
                        SettingsDialog(
                            prefs = prefs,
                            isScheduleEnabled = isScheduleEnabled,
                            onScheduleEnabledChanged = { isScheduleEnabled = it },
                            sleepHour = sleepHour,
                            onSleepHourChanged = { sleepHour = it },
                            sleepMinute = sleepMinute,
                            onSleepMinuteChanged = { sleepMinute = it },
                            wakeHour = wakeHour,
                            onWakeHourChanged = { wakeHour = it },
                            wakeMinute = wakeMinute,
                            onWakeMinuteChanged = { wakeMinute = it },
                            onClearCache = {
                                DeviceLogger.log("cache cleared (admin panel)")
                                webView?.clearCache(true)
                                try {
                                    val cacheDir = File(context.cacheDir, "signage_media_cache")
                                    if (cacheDir.exists()) {
                                        cacheDir.deleteRecursively()
                                    }
                                    cacheDir.mkdirs()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                webView?.reload()
                                lastWatchdogPingTime.set(System.currentTimeMillis())
                                showSettings = false
                            },
                            onDisconnectDevice = {
                                DeviceLogger.log("device disconnected (admin panel)")
                                try {
                                    WebStorage.getInstance().deleteAllData()
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                    webView?.clearCache(true)
                                    webView?.clearHistory()
                                    
                                    val cacheDir = File(context.cacheDir, "signage_media_cache")
                                    if (cacheDir.exists()) {
                                        cacheDir.deleteRecursively()
                                    }
                                    cacheDir.mkdirs()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                
                                // Reset back to default player URL and reload
                                val defaultUrl = "https://app.slidetv.eu/player"
                                prefs.serverUrl = defaultUrl
                                currentUrl = defaultUrl
                                webView?.loadUrl(defaultUrl)
                                lastWatchdogPingTime.set(System.currentTimeMillis())
                                showSettings = false
                            },
                            onReload = {
                                webView?.reload()
                                lastWatchdogPingTime.set(System.currentTimeMillis())
                                showSettings = false
                            },
                            onWatchdogChanged = { enabled ->
                                isWatchdogRunning = enabled
                                lastWatchdogPingTime.set(System.currentTimeMillis())
                            },
                            onDismiss = { showSettings = false }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to hide system bars: ${e.message}")
        }
    }

    private fun injectWatchdogHeartbeatScript(view: WebView?) {
        val script = """
            (function() {
                if (window.SlideTVWatchdogInterval) {
                    clearInterval(window.SlideTVWatchdogInterval);
                }
                window.SlideTVWatchdogInterval = setInterval(function() {
                    try {
                        if (window.SlideTVAndroid && typeof window.SlideTVAndroid.pingWatchdog === 'function') {
                            window.SlideTVAndroid.pingWatchdog();
                        }
                    } catch(e) {}
                }, 15000);
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun isNetworkAvailable(context: android.content.Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Allow offline loading of Web content by checking network state
            cacheMode = if (isNetworkAvailable(context)) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
        }
        scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
    }

    /**
     * Forward every native touch/key to the web player's kiosk-activity hook. Touches
     * that land INSIDE a cross-origin kiosk iframe never surface to the page's JS
     * (browser security), so the WebView shell is the only place that reliably sees
     * them. The player exposes `window.__slidetvKioskActivity()`; calling it on each
     * interaction keeps an interacted-with kiosk slide from auto-advancing. Throttled
     * so ACTION_MOVE streams don't flood evaluateJavascript.
     */
    private fun pingKioskActivity() {
        val now = System.currentTimeMillis()
        if (now - lastKioskPingTime < 400) return
        lastKioskPingTime = now
        val view = webView ?: return
        view.post {
            view.evaluateJavascript(
                "window.__slidetvKioskActivity && window.__slidetvKioskActivity();",
                null
            )
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
            pingKioskActivity()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            pingKioskActivity()
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                keyCode == KeyEvent.KEYCODE_ENTER || 
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastOkClickTime < 2500) {
                    okClickCount++
                } else {
                    okClickCount = 1
                }
                lastOkClickTime = currentTime
                
                Log.d("SlideTV_OKClicks", "OK / Select pressed: count $okClickCount")
                
                if (okClickCount >= 5) {
                    okClickCount = 0
                    showSettingsState.value = true
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSettingsState.value = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScheduleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        // Only records the event — it does NOT stop the logger. DeviceLogger is
        // process-lived on purpose (start() is idempotent; tearing it down on
        // every config change would kill logging across a rotation). Delivery is
        // best-effort: the in-memory buffer usually dies with the process before
        // the next 5s flush.
        DeviceLogger.log("player stopping")
        super.onDestroy()
    }

    private fun handleScheduleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra("schedule_action")
        Log.d("MainActivity", "Handling schedule intent action: $action")
        if (action == "wake") {
            isSleepingState.value = false
            wakeHardwareScreen()
        } else if (action == "sleep") {
            isSleepingState.value = true
        }
    }

    /**
     * Reads the device token the embedded web player obtained after pairing, from
     * the `slidetv_device_token` cookie ONLY. Returns null when the cookie is absent
     * (e.g. a force stop wiped it before it was flushed to disk); the poll loop then
     * falls back to the persisted `prefs.deviceToken`. Reusing this token makes the
     * native shell and the web player a SINGLE screen identity on the SaaS, so remote
     * schedule/commands target the screen the user actually sees.
     */
    private fun readCookieDeviceToken(): String? {
        return try {
            val cookies = CookieManager.getInstance().getCookie(prefs.serverUrl) ?: return null
            cookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("slidetv_device_token=") }
                ?.substringAfter("=")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("SlideTVPolling", "Failed to read player token: ${e.message}")
            null
        }
    }

    private fun wakeHardwareScreen() {
        try {
            Log.d("MainActivity", "Waking up hardware screen...")
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "SlideTVApp:ForceWakeLock"
            )
            wakeLock.acquire(10000) // Keep screen on for 10 seconds to guarantee TV screen wakeup triggers
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            // CRITICAL: bring MainActivity to the foreground. setTurnScreenOn() only powers the
            // display as the activity is (re)resumed — so if the player had lost focus (settings,
            // launcher, screen-off), the wake command would otherwise do nothing. launchMode is
            // singleTask, so REORDER_TO_FRONT reuses the existing instance and fires onNewIntent.
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w("MainActivity", "Wake bring-to-front failed: ${e.message}")
            }

            // Dismiss a non-secure keyguard so the player is actually visible after wake.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    km.requestDismissKeyguard(this, null)
                } catch (e: Exception) {
                    Log.w("MainActivity", "requestDismissKeyguard failed: ${e.message}")
                }
            }

            Log.d("MainActivity", "Hardware screen wake: flags, foreground bring-to-front + keyguard dismiss applied.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to wake up hardware screen: ${e.message}", e)
        }
    }
}
