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

    // Thread-safe atomic tracker for JS watchdog pings (crucial for JavaBridge background thread safety)
    private val lastWatchdogPingTime = AtomicLong(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SignagePrefs(this)
        
        // Migrate old default URL to player page
        if (prefs.serverUrl == "https://app.slidetv.eu") {
            prefs.serverUrl = "https://app.slidetv.eu/player"
        }

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

                                webViewClient = SignageWebViewClient(ctx, settings.userAgentString) { view, url ->
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
                                
                                if (isSleeping != targetSleepState) {
                                    isSleeping = targetSleepState
                                    Log.d("SlideTVSchedule", "Schedule state updated. Is Sleeping: $targetSleepState")
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

                    // Remote device polling — init device token then poll every 30 seconds
                    LaunchedEffect(Unit) {
                        val api = DeviceApiClient.getService(prefs.apiBaseUrl)

                        // Init: register new device or confirm existing token
                        try {
                            val initResp = withContext(Dispatchers.IO) {
                                api.init(prefs.deviceToken.ifEmpty { null })
                            }
                            if (!initResp.deviceToken.isNullOrEmpty()) {
                                prefs.deviceToken = initResp.deviceToken
                                Log.d("SlideTVPolling", "Device token saved: ${initResp.deviceToken}")
                            }
                        } catch (e: Exception) {
                            Log.e("SlideTVPolling", "Init failed: ${e.message}")
                        }

                        // Polling loop
                        while (true) {
                            kotlinx.coroutines.delay(30_000)
                            val token = prefs.deviceToken
                            if (token.isEmpty()) continue

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

                                // Device was deleted from SaaS — clear token and re-init next cycle
                                if (pollResp.status == "unpaired") {
                                    Log.w("SlideTVPolling", "Screen unpaired from SaaS. Clearing token.")
                                    prefs.deviceToken = ""
                                    continue
                                }

                                // Remote sleep command
                                if (pollResp.commandSleepAt > prefs.lastSleepCommandAt) {
                                    isSleepingState.value = true
                                    prefs.lastSleepCommandAt = pollResp.commandSleepAt
                                    Log.d("SlideTVPolling", "Remote sleep command executed.")
                                }

                                // Remote wake command
                                if (pollResp.commandWakeAt > prefs.lastWakeCommandAt) {
                                    isSleepingState.value = false
                                    wakeHardwareScreen()
                                    prefs.lastWakeCommandAt = pollResp.commandWakeAt
                                    Log.d("SlideTVPolling", "Remote wake command executed.")
                                }

                                // Remote reload command
                                if (pollResp.commandReloadAt > prefs.lastReloadCommandAt) {
                                    webView?.reload()
                                    lastWatchdogPingTime.set(System.currentTimeMillis())
                                    prefs.lastReloadCommandAt = pollResp.commandReloadAt
                                    Log.d("SlideTVPolling", "Remote reload command executed.")
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
                                    }
                                }

                            } catch (e: Exception) {
                                Log.e("SlideTVPolling", "Poll failed: ${e.message}")
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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
            Log.d("MainActivity", "Hardware screen wake flags and WakeLock applied.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to wake up hardware screen: ${e.message}", e)
        }
    }
}

private fun getCacheSizeInfo(context: android.content.Context): String {
    val cacheDir = File(context.cacheDir, "signage_media_cache")
    if (!cacheDir.exists() || !cacheDir.isDirectory) return "0 B (0 файла)"
    
    var size = 0L
    var count = 0
    cacheDir.listFiles()?.forEach { file ->
        if (file.isFile) {
            size += file.length()
            count++
        }
    }
    
    val sizeStr = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size.toDouble() / 1024)
        else -> String.format("%.2f MB", size.toDouble() / (1024 * 1024))
    }
    return "$sizeStr ($count файла)"
}

private fun getAppVersionInfo(context: android.content.Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pInfo.versionName} (${pInfo.versionCode})"
    } catch (e: Exception) {
        "1.0.0 (1)"
    }
}

@Composable
fun SlideTVLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D0D19))
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "SlideTV Logo",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun SlideTVBrandingHeader(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(55.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.slidetv_app_logo_white),
            contentDescription = "SlideTV Logo",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun SettingsDialog(
    prefs: SignagePrefs,
    isScheduleEnabled: Boolean,
    onScheduleEnabledChanged: (Boolean) -> Unit,
    sleepHour: Int,
    onSleepHourChanged: (Int) -> Unit,
    sleepMinute: Int,
    onSleepMinuteChanged: (Int) -> Unit,
    wakeHour: Int,
    onWakeHourChanged: (Int) -> Unit,
    wakeMinute: Int,
    onWakeMinuteChanged: (Int) -> Unit,
    onClearCache: () -> Unit,
    onDisconnectDevice: () -> Unit,
    onReload: () -> Unit,
    onWatchdogChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cacheInfo = remember { getCacheSizeInfo(context) }
    val versionInfo = remember { getAppVersionInfo(context) }

    // Use local state inside dialog to allow "Save" and "Cancel" flow
    var localIsScheduleEnabled by remember { mutableStateOf(isScheduleEnabled) }
    var localSleepHour by remember { mutableStateOf(sleepHour) }
    var localSleepMinute by remember { mutableStateOf(sleepMinute) }
    var localWakeHour by remember { mutableStateOf(wakeHour) }
    var localWakeMinute by remember { mutableStateOf(wakeMinute) }
    var localAutostartEnabled by remember { mutableStateOf(prefs.isAutostartEnabled) }
    var localWatchdogEnabled by remember { mutableStateOf(prefs.isWatchdogEnabled) }
    var selectedTabIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 850.dp)
            .fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SlideTVBrandingHeader()
                Text(
                    text = "Player Администраторски панел",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color(0xFF151525),
                    contentColor = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Системни",
                                tint = if (selectedTabIndex == 0) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                text = "Системни",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTabIndex == 0) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.6f)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "График",
                                tint = if (selectedTabIndex == 1) Color(0xFFE300A2) else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                text = "График",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTabIndex == 1) Color(0xFFE300A2) else Color.White.copy(alpha = 0.6f)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Информация",
                                tint = if (selectedTabIndex == 2) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                text = "Информация",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTabIndex == 2) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.6f)
                            )
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (selectedTabIndex) {
                        0 -> {
                            // First Run Hint / Helper Banner
                            if (prefs.isFirstLaunch) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF00D2FF).copy(alpha = 0.1f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00D2FF)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Добре дошли в SlideTV!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00D2FF)
                                        )
                                        Text(
                                            text = "Това устройство се настройва за първи път. Моля изберете дали желаете приложението да стартира автоматично при включване на уреда (Автоматичен старт) или ръчно. Когато приключите, натиснете розовия бутон 'Запази и затвори' долу.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }

                            // Autostart Option
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Автоматичен старт",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Пуска плеъра автоматично след зареждане на устройството.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = localAutostartEnabled,
                                    onCheckedChange = { localAutostartEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFE300A2),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    )
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Watchdog Option
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Watchdog против замръзване",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Следи дали картината е забила (чрез JS heartbeat) и автоматично я презарежда.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = localWatchdogEnabled,
                                    onCheckedChange = { localWatchdogEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFE300A2),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    )
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Show diagnostics inside Systems tab permanently
                            KioskDiagnosticsSection(context = context)
                        }
                        1 -> {
                            // Sleep / Wake Schedule Option
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "График сън / събуждане",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Спира плеъра и затъмнява екрана в определен период за пестене на енергия.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = localIsScheduleEnabled,
                                    onCheckedChange = { localIsScheduleEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFE300A2),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    )
                                )
                            }

                            if (localIsScheduleEnabled) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TimeAdjustmentRow(
                                        label = "Заспиване в:",
                                        hour = localSleepHour,
                                        minute = localSleepMinute,
                                        onHourChange = {
                                            localSleepHour = (it + 24) % 24
                                        },
                                        onMinuteChange = {
                                            localSleepMinute = (it + 60) % 60
                                        }
                                    )

                                    TimeAdjustmentRow(
                                        label = "Събуждане в:",
                                        hour = localWakeHour,
                                        minute = localWakeMinute,
                                        onHourChange = {
                                            localWakeHour = (it + 24) % 24
                                        },
                                        onMinuteChange = {
                                            localWakeMinute = (it + 60) % 60
                                        }
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Графикът е деактивиран. Включете суича по-горе, за да настроите часовете за сън и събуждане.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        2 -> {
                            // Info block
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Версия на приложението: $versionInfo",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Локален медиен кеш: $cacheInfo",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Адрес на плеъра: ${prefs.serverUrl}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00D2FF)
                                    )
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0x1100D2FF)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00D2FF).copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Бързи инструкции",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00D2FF)
                                    )
                                    Text(
                                        text = "• За да отворите този панел отново, натиснете бързо 5 пъти бутона OK на дистанционното управление в рамките на 2.5 секунди.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = "• Изчистването на кеша ще премахне всички временни файлове и ще принуди плеъра да ги свали отново при следващо зареждане.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = "• За най-добра стабилност се уверете, че системният часовник на Вашето устройство е сверен правилно.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Now ordered mirrorwise (right-most are active/primary, left-most are utility/destructive)

                // 1. Разкачи у-во
                Button(
                    onClick = onDisconnectDevice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33FF0033),
                        contentColor = Color(0xFFFF4D4D)
                    )
                ) {
                    Text("Разкачи у-во")
                }

                // 2. Изчисти кеша
                Button(
                    onClick = onClearCache,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x3300D2FF),
                        contentColor = Color(0xFF00D2FF)
                    )
                ) {
                    Text("Изчисти кеша")
                }

                // 3. Презареди страницата
                Button(
                    onClick = onReload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D2FF),
                        contentColor = Color(0xFF0D0D19)
                    )
                ) {
                    Text("Презареди страницата")
                }

                // 4. Отказ
                Button(
                    onClick = {
                        prefs.isFirstLaunch = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x22FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Text("Отказ")
                }

                // 5. Запази
                Button(
                    onClick = {
                        // Clear first-run layout now that user completed settings check
                        prefs.isFirstLaunch = false

                        // On "Save and Close" click, commit all local state values to SharedPreferences and states
                        prefs.isAutostartEnabled = localAutostartEnabled
                        prefs.isWatchdogEnabled = localWatchdogEnabled
                        prefs.isScheduleEnabled = localIsScheduleEnabled
                        prefs.sleepHour = localSleepHour
                        prefs.sleepMinute = localSleepMinute
                        prefs.wakeHour = localWakeHour
                        prefs.wakeMinute = localWakeMinute

                        onScheduleEnabledChanged(localIsScheduleEnabled)
                        onSleepHourChanged(localSleepHour)
                        onSleepMinuteChanged(localSleepMinute)
                        onWakeHourChanged(localWakeHour)
                        onWakeMinuteChanged(localWakeMinute)
                        onWatchdogChanged(localWatchdogEnabled)

                        // Force immediate update of alarms
                        ScheduleManager.updateAlarms(context)

                        val confirmMsg = if (localIsScheduleEnabled) {
                            String.format(
                                "Настройките са запазени за постоянно! Плеърът ще спира в %02d:%02d и ще се събужда в %02d:%02d.",
                                localSleepHour, localSleepMinute, localWakeHour, localWakeMinute
                            )
                        } else {
                            "Настройките са запазени успешно! Графикът за сън е изключен."
                        }
                        android.widget.Toast.makeText(context, confirmMsg, android.widget.Toast.LENGTH_LONG).show()
                        
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE300A2),
                        contentColor = Color.White
                    )
                ) {
                    Text("Запази", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
fun TimeAdjustmentRow(
    label: String,
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(110.dp)
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Hour adjust
            FilledTonalIconButton(
                onClick = { onHourChange(hour - 1) },
                modifier = Modifier.size(34.dp)
            ) {
                Text("-", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = String.format("%02d", hour),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(26.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalIconButton(
                onClick = { onHourChange(hour + 1) },
                modifier = Modifier.size(34.dp)
            ) {
                Text("+", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            
            Text(":", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            // Minute adjust (steps of 5 minutes is standard is extremely convenient for signage)
            FilledTonalIconButton(
                onClick = { onMinuteChange(minute - 5) },
                modifier = Modifier.size(34.dp)
            ) {
                Text("-", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = String.format("%02d", minute),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(26.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalIconButton(
                onClick = { onMinuteChange(minute + 5) },
                modifier = Modifier.size(34.dp)
            ) {
                Text("+", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// Checkers and Launchers for Android permissions that are critical for long-term tablet sleep/wake stability (Lenovo, Samsung, etc.)
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

private fun launchBatteryOptimizationSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open battery settings", e)
        }
    }
}

private fun canDrawOverlays(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        android.provider.Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun launchDrawOverlaysSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                android.util.Log.e("MainActivity", "Failed to open overlay settings", ex)
            }
        }
    }
}

private fun canScheduleExactAlarms(context: android.content.Context): Boolean {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }
    } catch (e: Exception) {
        true
    }
}

private fun launchExactAlarmSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        try {
            val intent = android.content.Intent(
                "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                android.util.Log.e("MainActivity", "Failed to open exact alarm settings", ex)
            }
        }
    }
}

private fun launchAppInfoSettings(context: android.content.Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to open App Info screen", e)
    }
}

private fun launchAutoStartSettings(context: android.content.Context) {
    val intents = listOf(
        // Lenovo Pure Background settings
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.powermanager", "com.lenovo.powermanager.PowerManagerActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.security", "com.lenovo.security.MainActivity")),
        // Samsung smart manager / battery restrictions
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.RamActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.usergoal.ActiveApplicationFilterActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.PresenterActivity")),
        // Xiaomi MIUI Autostart settings
        android.content.Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        // Huawei Protected apps settings
        android.content.Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        // Oppo Autostart settings
        android.content.Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        // Vivo Autostart settings
        android.content.Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
    )

    for (intent in intents) {
        try {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            android.util.Log.d("MainActivity", "Successfully launched manufacturer settings: ${intent.component?.className}")
            return
        } catch (e: Exception) {
            // Silence and try next component layout
        }
    }

    // Direct fallback to central App Details screen where all permissions & battery settings are stored
    launchAppInfoSettings(context)
}

@Composable
fun KioskDiagnosticsSection(context: android.content.Context) {
    var hasBatteryIgnore by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var hasOverlayPermission by remember { mutableStateOf(canDrawOverlays(context)) }
    var hasExactAlarmPermission by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    val manufacturerRaw = android.os.Build.MANUFACTURER ?: ""
    val manufacturer = manufacturerRaw.split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    val deviceBrand = if (manufacturer.equals("Unknown", ignoreCase = true) || manufacturer.isEmpty()) "" else " $manufacturer"

    LaunchedEffect(Unit) {
        while (true) {
            hasBatteryIgnore = isIgnoringBatteryOptimizations(context)
            hasOverlayPermission = canDrawOverlays(context)
            hasExactAlarmPermission = canScheduleExactAlarms(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E2E)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE300A2).copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Системна алармена диагностика за устройството",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00D2FF)
                )
            }
            Text(
                text = "За да задействате събуждането и съня безупречно на Вашето устройство$deviceBrand, моля осигурете следните права:",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            // Battery ignore optimization
            DiagnosticRow(
                title = "Работа без ограничение на батерията [Doze]",
                description = "Предпазва приложението от заспиване през нощта.",
                isGranted = hasBatteryIgnore,
                onClick = { launchBatteryOptimizationSettings(context) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            // Play over other apps overlay
            DiagnosticRow(
                title = "Показване над други приложения [Overlay]",
                description = "Позволява на алармата да активира екрана от фонов режим безпрепятствено.",
                isGranted = hasOverlayPermission,
                onClick = { launchDrawOverlaysSettings(context) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Exact alarm permission
                DiagnosticRow(
                    title = "Разрешение за точни системни аларми",
                    description = "Позволява на устройството да задейства събитията точно в минутата.",
                    isGranted = hasExactAlarmPermission,
                    onClick = { launchExactAlarmSettings(context) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            } else {
                Text(
                    text = "* Разрешението за точни системни аларми не се изисква и автоматично не се показва, защото Вашето устройство работи с по-стара версия Android (под Android 12).",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            }

            // Background Auto-Start optimization
            DiagnosticRow(
                title = "Фонова автономия и стартиране",
                description = "След натискане на бутона, в настройките изберете 'Батерия' -> 'Без ограничения' (Unrestricted). Разрешете също автостарт, ако има такава опция.",
                isGranted = false,
                buttonTextOverride = "Отвори",
                onClick = { launchAutoStartSettings(context) }
            )
            
            // Failsafe note
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE300A2).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "СЪВЕТ: За 100% стабилност е силно препоръчително устройството да остане постоянно свързано към зарядно устройство.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF99D6)
                )
            }
        }
    }
}

@Composable
fun DiagnosticRow(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonTextOverride: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (buttonTextOverride != null) Color(0xFF00D2FF) else (if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828)),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(
                text = buttonTextOverride ?: (if (isGranted) "Разрешено" else "Разреши тук"),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

