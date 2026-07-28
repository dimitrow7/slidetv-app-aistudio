package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.prefs.SignagePrefs
import java.io.File

// ─── Palette ─────────────────────────────────────────────────────────────────
private val PanelDark   = Color(0xFF0B0A16)
private val AccentCyan   = Color(0xFF00D2FF)
private val AccentPink   = Color(0xFFE300A2)
private val AccentPurple = Color(0xFF7C3AED)
private val Danger       = Color(0xFFFF4D6A)
private val AccentGradient = Brush.horizontalGradient(listOf(AccentPurple, AccentPink))

/** Selectable ceilings for the media cache, paired with their byte value. */
val CACHE_LIMIT_OPTIONS = listOf(
    "1 GB" to 1L * 1024 * 1024 * 1024,
    "2 GB" to 2L * 1024 * 1024 * 1024,
    "4 GB" to 4L * 1024 * 1024 * 1024,
    "8 GB" to 8L * 1024 * 1024 * 1024
)

// ─── Glass modifiers ─────────────────────────────────────────────────────────
// Dark, mostly-opaque frosted fill so text stays readable over the bright
// ambient glows behind the panel; a faint white sheen keeps the glass feel.
private val GlassFill      = Color(0xFF14122A).copy(alpha = 0.74f)
private val GlassFillFocus = Color(0xFF201B3D).copy(alpha = 0.82f)
private val GlassSheen     = Color.White.copy(alpha = 0.05f)

private fun Modifier.glass(focused: Boolean = false, shape: Shape = RoundedCornerShape(18.dp)): Modifier =
    this.clip(shape)
        .background(if (focused) GlassFillFocus else GlassFill)
        .background(GlassSheen)
        .border(
            BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AccentCyan else Color.White.copy(alpha = 0.10f)),
            shape
        )

/**
 * A soft, feathered colour glow used for the ambient background — a radial
 * gradient that fades to transparent (no hard circle edge), plus a real blur on
 * Android 12+ (a graceful no-op below, where the gradient alone stays soft).
 */
@Composable
private fun AmbientGlow(color: Color, size: Dp, offsetX: Dp, offsetY: Dp, alpha: Float) {
    Box(
        Modifier
            .size(size)
            .offset(x = offsetX, y = offsetY)
            .blur(90.dp)
            .background(
                Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent)),
                CircleShape
            )
    )
}

// ─── Public entry point (same signature as before) ───────────────────────────
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

    // Local (draft) state — committed on "Запази".
    var autostart by remember { mutableStateOf(prefs.isAutostartEnabled) }
    var watchdog by remember { mutableStateOf(prefs.isWatchdogEnabled) }
    var cacheLimit by remember { mutableStateOf(prefs.cacheLimitBytes) }
    var scheduleOn by remember { mutableStateOf(isScheduleEnabled) }
    var sHour by remember { mutableStateOf(sleepHour) }
    var sMin by remember { mutableStateOf(sleepMinute) }
    var wHour by remember { mutableStateOf(wakeHour) }
    var wMin by remember { mutableStateOf(wakeMinute) }

    var section by remember { mutableStateOf(0) }

    // Live connection status shown in the top-right of the panel.
    var online by remember { mutableStateOf(true) }
    var connType by remember { mutableStateOf("Мрежа") }
    LaunchedEffect(Unit) {
        while (true) {
            val (o, t) = connectionStatus(context)
            online = o; connType = t
            kotlinx.coroutines.delay(3000)
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Full-screen ambient background with soft colour blobs behind the glass.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF120F22), PanelDark, Color(0xFF0A1622))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            AmbientGlow(AccentPurple, 720.dp, (-320).dp, (-240).dp, 0.30f)
            AmbientGlow(AccentCyan,   680.dp,   340.dp,   280.dp, 0.20f)
            AmbientGlow(AccentPink,   460.dp,    40.dp,   320.dp, 0.14f)

            // Clamped glass panel — same physical size on phones, tablets and 4K TVs.
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.9f)
                    .widthIn(max = 1080.dp)
                    .heightIn(max = 660.dp)
                    .glass(shape = RoundedCornerShape(28.dp))
                    .padding(2.dp)
            ) {
                // ── Sidebar ──
                Column(
                    modifier = Modifier
                        .width(258.dp)
                        .fillMaxHeight()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    ) {
                        // The launcher icon is an adaptive-icon (XML) which
                        // painterResource can't load, so rasterise the real app
                        // icon into a bitmap and show it bare (no frame). Falls
                        // back to the foreground vector if the icon can't be
                        // resolved (e.g. under Robolectric).
                        val brandIcon = remember {
                            runCatching {
                                val d = context.packageManager.getApplicationIcon(context.packageName)
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    96, 96, android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bmp)
                                d.setBounds(0, 0, canvas.width, canvas.height)
                                d.draw(canvas)
                                bmp.asImageBitmap()
                            }.getOrNull()
                        }
                        val iconModifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                        if (brandIcon != null) {
                            Image(bitmap = brandIcon, contentDescription = null, modifier = iconModifier)
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = iconModifier
                            )
                        }
                        Column {
                            Text("SlideTV", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text("АДМИНИСТРАЦИЯ", color = Color.White.copy(0.5f),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                    }

                    NavItem(Icons.Default.Settings, "Система", "SYSTEM", section == 0,
                        Modifier.focusRequester(firstFocus)) { section = 0 }
                    NavItem(Icons.Default.DateRange, "График", "ON / OFF", section == 1) { section = 1 }
                    NavItem(Icons.Default.Refresh, "Кеш", "STORAGE", section == 2) { section = 2 }
                    NavItem(Icons.Default.Info, "Информация", "SYSTEM", section == 3) { section = 3 }

                    Spacer(Modifier.weight(1f))

                    // Device card
                    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
                        Text("УСТРОЙСТВО", color = Color.White.copy(0.4f),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(android.os.Build.MODEL ?: "SlideTV Player",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("SlideTV Player", color = Color.White.copy(0.5f), fontSize = 11.sp)
                    }
                }

                // ── Main area ──
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 22.dp, top = 20.dp, bottom = 18.dp)
                ) {
                    // Top bar: title + connection status
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("НАСТРОЙКИ НА УСТРОЙСТВОТО", color = Color.White.copy(0.45f),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text(
                                when (section) {
                                    0 -> "Система"
                                    1 -> "График"
                                    2 -> "Кеш и съхранение"
                                    else -> "Информация"
                                },
                                color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp
                            )
                        }
                        Row(
                            Modifier.glass().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(8.dp).background(
                                if (online) Color(0xFF22DD88) else Danger, CircleShape))
                            Text(if (online) "ONLINE" else "OFFLINE",
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("·", color = Color.White.copy(0.4f))
                            Text(connType, color = Color.White.copy(0.7f), fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Scrollable content — always reaches the end (weight fills, scrolls inside).
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (section) {
                            0 -> SystemSection(prefs, autostart, { autostart = it }, watchdog, { watchdog = it })
                            1 -> ScheduleSection(scheduleOn, { scheduleOn = it },
                                sHour, { sHour = (it + 24) % 24 }, sMin, { sMin = (it + 60) % 60 },
                                wHour, { wHour = (it + 24) % 24 }, wMin, { wMin = (it + 60) % 60 })
                            2 -> CacheSection(cacheInfo, cacheLimit, { cacheLimit = it }, onClearCache, onReload)
                            else -> InfoSection(versionInfo, cacheInfo, prefs.serverUrl, onDisconnectDevice)
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    Spacer(Modifier.height(14.dp))

                    // Fixed bottom bar: Cancel / Save
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Spacer(Modifier.weight(1f))
                        ActionButton("Отказ") { prefs.isFirstLaunch = false; onDismiss() }
                        ActionButton("Запази", primary = true) {
                            prefs.isFirstLaunch = false
                            prefs.isAutostartEnabled = autostart
                            prefs.isWatchdogEnabled = watchdog
                            prefs.cacheLimitBytes = cacheLimit
                            prefs.isScheduleEnabled = scheduleOn
                            prefs.sleepHour = sHour
                            prefs.sleepMinute = sMin
                            prefs.wakeHour = wHour
                            prefs.wakeMinute = wMin
                            onScheduleEnabledChanged(scheduleOn)
                            onSleepHourChanged(sHour)
                            onSleepMinuteChanged(sMin)
                            onWakeHourChanged(wHour)
                            onWakeMinuteChanged(wMin)
                            onWatchdogChanged(watchdog)
                            ScheduleManager.updateAlarms(context)
                            val msg = if (scheduleOn)
                                String.format(
                                    "Настройките са запазени! Плеърът ще спира в %02d:%02d и ще се събужда в %02d:%02d.",
                                    sHour, sMin, wHour, wMin
                                )
                            else "Настройките са запазени успешно! Графикът за сън е изключен."
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

// ─── Sidebar nav item ────────────────────────────────────────────────────────
@Composable
private fun NavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (selected) Modifier.background(
                    Brush.horizontalGradient(listOf(AccentPurple.copy(0.55f), AccentPink.copy(0.35f)))
                ) else Modifier.background(Color.White.copy(0.04f))
            )
            .border(
                BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    when { focused -> AccentCyan; selected -> Color.White.copy(0.25f); else -> Color.White.copy(0.06f) }
                ), shape
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) Color.White.copy(0.18f) else Color.White.copy(0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title,
                tint = if (selected) Color.White else AccentCyan.copy(0.85f),
                modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Color.White.copy(0.45f),
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        if (selected) Box(Modifier.size(8.dp).background(AccentCyan, CircleShape))
    }
}

// ─── Reusable pieces ─────────────────────────────────────────────────────────
@Composable
private fun ToggleCard(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().glass(focused)
            .clickable(interactionSource = interaction, indication = null) { onToggle(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(desc, color = Color.White.copy(0.55f), fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = AccentPink,
                uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.DarkGray
            )
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val base = when {
        primary -> Modifier.background(AccentGradient)
        danger  -> Modifier.background(Danger.copy(0.18f))
        else    -> Modifier.background(Color.White.copy(0.06f))
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(base)
            .border(BorderStroke(2.dp, if (focused) Color.White else Color.Transparent), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (danger) Danger else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(text, color = Color.White.copy(0.4f), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

// ─── Sections ────────────────────────────────────────────────────────────────
@Composable
private fun SystemSection(
    prefs: SignagePrefs,
    autostart: Boolean, onAutostart: (Boolean) -> Unit,
    watchdog: Boolean, onWatchdog: (Boolean) -> Unit
) {
    if (prefs.isFirstLaunch) {
        Column(Modifier.fillMaxWidth().glass(shape = RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Добре дошли в SlideTV!", color = AccentCyan, fontWeight = FontWeight.Bold)
            Text("Това устройство се настройва за първи път. Изберете дали приложението да " +
                "стартира автоматично при включване. Накрая натиснете „Запази“.",
                color = Color.White.copy(0.85f), fontSize = 12.sp)
        }
    }
    ToggleCard("Автоматичен старт", "Пуска плеъра автоматично след зареждане на устройството.",
        autostart, onAutostart)
    ToggleCard("Watchdog против замръзване",
        "Следи дали картината е забила (чрез JS heartbeat) и автоматично я презарежда.",
        watchdog, onWatchdog)

    SectionCaption("ПРАВА ЗА СЪН И СЪБУЖДАНЕ")
    KioskDiagnosticsSection(LocalContext.current)
}

@Composable
private fun ScheduleSection(
    on: Boolean, onToggle: (Boolean) -> Unit,
    sHour: Int, onSHour: (Int) -> Unit, sMin: Int, onSMin: (Int) -> Unit,
    wHour: Int, onWHour: (Int) -> Unit, wMin: Int, onWMin: (Int) -> Unit
) {
    ToggleCard("График сън / събуждане",
        "Спира плеъра и затъмнява екрана в определен период за пестене на енергия.", on, onToggle)
    if (on) {
        Column(Modifier.fillMaxWidth().glass().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TimeAdjustmentRow("Заспиване в:", sHour, sMin, onSHour, onSMin)
            TimeAdjustmentRow("Събуждане в:", wHour, wMin, onWHour, onWMin)
        }
    } else {
        Box(Modifier.fillMaxWidth().glass(shape = RoundedCornerShape(14.dp)).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Text("Графикът е деактивиран. Включете суича по-горе, за да настроите часовете.",
                color = Color.White.copy(0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CacheSection(
    cacheInfo: String, cacheLimit: Long, onCacheLimit: (Long) -> Unit,
    onClearCache: () -> Unit, onReload: () -> Unit
) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCaption("ЗАЕТО")
        Text(cacheInfo, color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
    }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Лимит на кеша", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Най-отдавна показваното съдържание се трие само над този лимит.",
            color = Color.White.copy(0.55f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CACHE_LIMIT_OPTIONS.forEach { (label, bytes) ->
                ChipButton(label, cacheLimit == bytes) { onCacheLimit(bytes) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Изчисти кеша") { onClearCache() }
        ActionButton("Пресвали медия") { onReload() }
    }
}

@Composable
private fun InfoSection(version: String, cacheInfo: String, serverUrl: String, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoRow("Версия на приложението", version)
        InfoRow("Локален медиен кеш", cacheInfo)
        InfoRow("Адрес на плеъра", serverUrl, AccentCyan)
    }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Бързи инструкции", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• За да отворите този панел отново, натиснете бързо 5 пъти бутона OK на " +
            "дистанционното в рамките на 2.5 секунди.", color = Color.White.copy(0.75f), fontSize = 11.sp)
        Text("• Изчистването на кеша премахва временните файлове и ги сваля наново при следващо зареждане.",
            color = Color.White.copy(0.75f), fontSize = 11.sp)
        Text("• За най-добра стабилност се уверете, че системният часовник е сверен правилно.",
            color = Color.White.copy(0.75f), fontSize = 11.sp)
    }
    ActionButton("Разкачи устройство", danger = true) { onDisconnect() }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Column {
        Text(label, color = Color.White.copy(0.45f), fontSize = 11.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.clip(shape)
            .then(if (selected) Modifier.background(AccentGradient) else Modifier.background(Color.White.copy(0.06f)))
            .border(BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) AccentCyan else Color.White.copy(0.08f)), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Color.White.copy(0.75f),
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─── Time picker row ─────────────────────────────────────────────────────────
@Composable
fun TimeAdjustmentRow(label: String, hour: Int, minute: Int, onHourChange: (Int) -> Unit, onMinuteChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton("−") { onHourChange(hour - 1) }
            NumberBox(String.format("%02d", hour))
            StepButton("+") { onHourChange(hour + 1) }
            Text(":", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            StepButton("−") { onMinuteChange(minute - 5) }
            NumberBox(String.format("%02d", minute))
            StepButton("+") { onMinuteChange(minute + 5) }
        }
    }
}

@Composable
private fun StepButton(sign: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(0.08f))
            .border(BorderStroke(if (focused) 2.dp else 0.dp, if (focused) AccentCyan else Color.Transparent),
                RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(sign, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

@Composable
private fun NumberBox(value: String) {
    Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
}

// ─── Permission diagnostics (glass restyle) ──────────────────────────────────
@Composable
fun KioskDiagnosticsSection(context: android.content.Context) {
    var battery by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var overlay by remember { mutableStateOf(canDrawOverlays(context)) }
    var exactAlarm by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            battery = isIgnoringBatteryOptimizations(context)
            overlay = canDrawOverlays(context)
            exactAlarm = canScheduleExactAlarms(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("За безупречно събуждане и сън осигурете следните права:",
            color = Color.White.copy(0.7f), fontSize = 12.sp)
        DiagnosticRow("Без ограничение на батерията [Doze]",
            "Предпазва приложението от заспиване през нощта.", battery) {
            launchBatteryOptimizationSettings(context)
        }
        DiagnosticRow("Показване над други приложения [Overlay]",
            "Позволява на алармата да активира екрана от фон.", overlay) {
            launchDrawOverlaysSettings(context)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            DiagnosticRow("Точни системни аларми",
                "Задейства събитията точно в минутата.", exactAlarm) {
                launchExactAlarmSettings(context)
            }
        }
        DiagnosticRow("Фонова автономия и стартиране",
            "Изберете „Без ограничения“ и разрешете автостарт, ако има опция.", false, "Отвори") {
            launchAutoStartSettings(context)
        }
        Box(Modifier.fillMaxWidth().background(AccentPink.copy(0.14f), RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text("СЪВЕТ: За 100% стабилност дръжте устройството постоянно на зарядно.",
                color = Color(0xFFFF99D6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DiagnosticRow(title: String, description: String, isGranted: Boolean, buttonTextOverride: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(0.03f))
            .border(BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) AccentCyan else Color.White.copy(0.05f)), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(description, color = Color.White.copy(0.55f), fontSize = 11.sp)
        }
        val ok = buttonTextOverride == null && isGranted
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(when { buttonTextOverride != null -> AccentCyan; ok -> Color(0xFF2E7D32); else -> Color(0xFFC62828) })
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(buttonTextOverride ?: (if (isGranted) "Разрешено" else "Разреши тук"),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Plain helpers (moved verbatim from MainActivity) ─────────────────────────
fun getCacheSizeInfo(context: android.content.Context): String {
    val cacheDir = File(context.cacheDir, "signage_media_cache")
    if (!cacheDir.exists() || !cacheDir.isDirectory) return "0 B (0 файла)"
    var size = 0L
    var count = 0
    cacheDir.listFiles()?.forEach { file -> if (file.isFile) { size += file.length(); count++ } }
    val sizeStr = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size.toDouble() / 1024)
        size < 1024L * 1024 * 1024 -> String.format("%.2f MB", size.toDouble() / (1024 * 1024))
        else -> String.format("%.2f GB", size.toDouble() / (1024L * 1024 * 1024))
    }
    return "$sizeStr ($count файла)"
}

fun getAppVersionInfo(context: android.content.Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pInfo.versionName} (${pInfo.versionCode})"
    } catch (e: Exception) {
        "1.0.0 (1)"
    }
}

private fun connectionStatus(context: android.content.Context): Pair<Boolean, String> {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false to "Офлайн"
        val caps = cm.getNetworkCapabilities(net) ?: return false to "Офлайн"
        val online = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val type = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобилни данни"
            else -> "Мрежа"
        }
        online to type
    } catch (e: Exception) {
        false to "Офлайн"
    }
}

// Permission checkers / launchers (moved verbatim from MainActivity)
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true
}

private fun launchBatteryOptimizationSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        try {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            android.util.Log.e("AdminPanel", "Failed to open battery settings", e)
        }
    }
}

private fun canDrawOverlays(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
        android.provider.Settings.canDrawOverlays(context) else true
}

private fun launchDrawOverlaysSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        try {
            context.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            try {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (ex: Exception) {
                android.util.Log.e("AdminPanel", "Failed to open overlay settings", ex)
            }
        }
    }
}

private fun canScheduleExactAlarms(context: android.content.Context): Boolean {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else true
    } catch (e: Exception) { true }
}

private fun launchExactAlarmSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        try {
            context.startActivity(android.content.Intent(
                "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                android.net.Uri.parse("package:${context.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            try {
                context.startActivity(android.content.Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                    .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (ex: Exception) {
                android.util.Log.e("AdminPanel", "Failed to open exact alarm settings", ex)
            }
        }
    }
}

private fun launchAppInfoSettings(context: android.content.Context) {
    try {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        android.util.Log.e("AdminPanel", "Failed to open App Info screen", e)
    }
}

private fun launchAutoStartSettings(context: android.content.Context) {
    val intents = listOf(
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.powermanager", "com.lenovo.powermanager.PowerManagerActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.lenovo.security", "com.lenovo.security.MainActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.RamActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.usergoal.ActiveApplicationFilterActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.PresenterActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        android.content.Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
    )
    for (intent in intents) {
        try {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (e: Exception) { /* try next */ }
    }
    launchAppInfoSettings(context)
}
