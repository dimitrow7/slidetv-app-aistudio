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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
    Row(
        modifier = modifier.wrapContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        SlideTVLogo()
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Slide",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "TV",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00D2FF)
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
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                                text = "Добре дошли в SlideTV! 🚀",
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
                // 1. Запази
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

                // 2. Отказ
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

                // 3. Изчисти кеша
                Button(
                    onClick = onClearCache,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x3300D2FF),
                        contentColor = Color(0xFF00D2FF)
                    )
                ) {
                    Text("Изчисти кеша")
                }

                // 4. Разкачи у-во
                Button(
                    onClick = onDisconnectDevice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33FF0033),
                        contentColor = Color(0xFFFF4D4D)
                    )
                ) {
                    Text("Разкачи у-во")
                }

                // 5. Презареди страницата
                Button(
                    onClick = onReload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D2FF),
                        contentColor = Color(0xFF0D0D19)
                    )
                ) {
                    Text("Презареди страницата")
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
