package com.vibe.talkingtimer.wear

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.talkingtimer.core.AnnouncementCadence
import com.vibe.talkingtimer.core.TimerMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = WearOledColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) { WearTimerScreen() }
            }
        }
    }
}

val WearOledColorScheme = darkColorScheme(
    primary = Color(0xFF7AD7FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0B2A36),
    onPrimaryContainer = Color(0xFFE4F7FF),
    secondary = Color(0xFFB8E0FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF102633),
    onSecondaryContainer = Color(0xFFDDEFFF),
    tertiary = Color(0xFFA7F3D0),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF0F2A24),
    onTertiaryContainer = Color(0xFFD7FFF2),
    background = Color.Black,
    onBackground = Color(0xFFF5F7FA),
    surface = Color.Black,
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF0D0F12),
    onSurfaceVariant = Color(0xFFC8CDD4),
    outline = Color(0xFF434A54),
    outlineVariant = Color(0xFF252A31),
    surfaceTint = Color(0xFF7AD7FF),
    scrim = Color.Black,
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WearTimerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by WearTimerStateBus.state.collectAsStateWithLifecycle()
    val exactAlarmsAllowed = rememberExactAlarmAccessState()

    var cadence by rememberSaveable { mutableStateOf(AnnouncementCadence.EVERY_30S) }
    var offsetSecondsText by rememberSaveable { mutableStateOf("-5") }
    var scheduledTargetWallMs by rememberSaveable { mutableLongStateOf(0L) }
    val offsetMs = offsetSecondsText.toLongOrNull()?.times(1000L) ?: 0L
    val pagerState = rememberPagerState(pageCount = { 3 })

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize(),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (page == 0) 10.dp else 12.dp, vertical = if (page == 0) 8.dp else 10.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(if (page == 0) 6.dp else 8.dp),
            ) {
                if (page == 0) {
                    RunTimeHeader(timeLabel = state.timeLabel)
                } else {
                    CompactStatusHeader(
                        page = page,
                        timeLabel = state.timeLabel,
                        statusMessage = state.statusMessage,
                    )
                }
                when (page) {
                    0 -> {
                        if (!exactAlarmsAllowed) {
                            ExactAlarmWarningCard(onFix = { openExactAlarmSettings(context) })
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = oledCardColors(),
                            border = oledCardBorder(),
                        ) {
                            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val iconPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
                                    Button(
                                        enabled = exactAlarmsAllowed,
                                        onClick = { startService(context, WearTimerForegroundService.startNowIntent(context, cadence, offsetMs)) },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                        contentPadding = iconPadding,
                                    ) {
                                        PlayTriangle(
                                            size = 32.dp,
                                            color = Color.Red,
                                            outlined = state.mode == TimerMode.RUNNING,
                                        )
                                    }
                                    Button(
                                        onClick = { startService(context, WearTimerForegroundService.stopTimerIntent(context)) },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                        contentPadding = iconPadding,
                                    ) {
                                        StopSquare(
                                            size = 32.dp,
                                            color = Color.Red,
                                            outlined = state.mode == TimerMode.IDLE && !state.listening && state.elapsedMs > 0,
                                        )
                                    }
                                    Button(
                                        enabled = exactAlarmsAllowed && state.speechAvailable,
                                        onClick = {
                                            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                                micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                            } else if (state.listening) {
                                                startService(context, WearTimerForegroundService.stopListeningIntent(context))
                                            } else {
                                                startService(context, WearTimerForegroundService.startListeningIntent(context, cadence, 0L))
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                        contentPadding = iconPadding,
                                    ) {
                                        MicIcon(
                                            size = 32.dp,
                                            color = if (state.listening) Color.Green else Color.Red,
                                        )
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    CadenceChip(
                                        option = AnnouncementCadence.EVERY_10S,
                                        selected = cadence == AnnouncementCadence.EVERY_10S,
                                        contentAlignment = Alignment.CenterEnd,
                                    ) { cadence = AnnouncementCadence.EVERY_10S }
                                    CadenceChip(
                                        option = AnnouncementCadence.EVERY_30S,
                                        selected = cadence == AnnouncementCadence.EVERY_30S,
                                        contentAlignment = Alignment.CenterStart,
                                    ) { cadence = AnnouncementCadence.EVERY_30S }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    CadenceChip(
                                        option = AnnouncementCadence.EVERY_1M,
                                        selected = cadence == AnnouncementCadence.EVERY_1M,
                                        contentAlignment = Alignment.CenterEnd,
                                    ) { cadence = AnnouncementCadence.EVERY_1M }
                                    CadenceChip(
                                        option = AnnouncementCadence.EVERY_5M,
                                        selected = cadence == AnnouncementCadence.EVERY_5M,
                                        contentAlignment = Alignment.CenterStart,
                                    ) { cadence = AnnouncementCadence.EVERY_5M }
                                }
                            }
                        }
                    }

                    1 -> {
                        if (!exactAlarmsAllowed) {
                            ExactAlarmWarningCard(onFix = { openExactAlarmSettings(context) })
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = oledCardColors(),
                            border = oledCardBorder(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = offsetSecondsText,
                                    onValueChange = { newValue ->
                                        offsetSecondsText = newValue.filter { ch -> ch.isDigit() || ch == '-' }.take(6)
                                    },
                                    label = { Text("Offset s") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { scheduledTargetWallMs = nextMinuteBoundary() },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                    ) { Text("+1m") }
                                    Button(
                                        enabled = exactAlarmsAllowed,
                                        onClick = {
                                            val target = if (scheduledTargetWallMs > 0L) scheduledTargetWallMs else nextMinuteBoundary()
                                            startService(context, WearTimerForegroundService.scheduleIntent(context, cadence, offsetMs, target))
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                    ) { Text("Go At") }
                                }
                                TextButton(
                                    onClick = { showTimePicker(context) { scheduledTargetWallMs = it } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (scheduledTargetWallMs > 0L) "At ${scheduledTargetWallMs.formatClockTime()}" else "Pick exact time")
                                }
                            }
                        }
                    }

                    else -> {
                        if (!exactAlarmsAllowed) {
                            ExactAlarmWarningCard(onFix = { openExactAlarmSettings(context) })
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = oledCardColors(),
                            border = oledCardBorder(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when {
                                        !exactAlarmsAllowed -> "Enable exact alarms first"
                                        state.speechAvailable -> "Say 'go'"
                                        else -> "Unavailable"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        enabled = exactAlarmsAllowed && state.speechAvailable,
                                        onClick = {
                                            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                                micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                startService(context, WearTimerForegroundService.startListeningIntent(context, cadence, offsetMs))
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                    ) { Text("Listen") }
                                    Button(
                                        onClick = { startService(context, WearTimerForegroundService.stopListeningIntent(context)) },
                                        modifier = Modifier.weight(1f),
                                        colors = oledButtonColors(),
                                        border = oledButtonBorder(),
                                    ) { Text("Stop") }
                                }
                                Text(
                                    "Notification stays on while active.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { context.startActivity(Intent(context, KeywordLabActivity::class.java)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Keyword Lab")
                                }
                            }
                        }
                    }
                }
            }
        }

        PageDots(
            selectedIndex = pagerState.currentPage,
            count = 3,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ExactAlarmWarningCard(onFix: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = oledCardColors(),
        border = BorderStroke(1.dp, Color(0xFF7A4B00)),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Enable Exact Alarms for reliable timing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
                colors = oledButtonColors(),
                border = oledButtonBorder(),
            ) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun rememberExactAlarmAccessState(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return allowed
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
    return alarmManager.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

@Composable
private fun RunTimeHeader(timeLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = oledCardColors(),
        border = oledCardBorder(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                timeLabel,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RowScope.CadenceChip(
    option: AnnouncementCadence,
    selected: Boolean,
    contentAlignment: Alignment,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = contentAlignment,
        ) {
            Text(
                option.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 20.sp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
            )
        }
    }
}

@Composable
private fun CompactStatusHeader(page: Int, timeLabel: String, statusMessage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = oledCardColors(),
        border = oledCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (page) {
                        0 -> "Run"
                        1 -> "Schedule"
                        else -> "Voice"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageDots(selectedIndex: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.88f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val color = if (index == selectedIndex) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            }
            Box(
                modifier = Modifier
                    .size(if (index == selectedIndex) 7.dp else 6.dp)
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}

@Composable
private fun PlayTriangle(size: Dp, color: Color, outlined: Boolean = false) {
    Canvas(modifier = Modifier.size(size)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
            lineTo(0f, this@Canvas.size.height)
            close()
        }
        if (outlined) {
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.width * 0.08f))
        } else {
            drawPath(path, color)
        }
    }
}

@Composable
private fun StopSquare(size: Dp, color: Color, outlined: Boolean = false) {
    Canvas(modifier = Modifier.size(size)) {
        if (outlined) {
            val strokeWidth = this.size.width * 0.08f
            drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
        } else {
            drawRect(color)
        }
    }
}

@Composable
private fun MicIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val micWidth = w * 0.36f
        val micHeight = h * 0.50f
        val micLeft = (w - micWidth) / 2f
        val micTop = h * 0.05f
        val cornerRadius = micWidth / 2f
        // Mic head
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(micLeft, micTop),
            size = androidx.compose.ui.geometry.Size(micWidth, micHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
        )
        // Arc holder
        val arcStroke = w * 0.08f
        val arcTop = micTop + micHeight * 0.35f
        val arcBottom = micTop + micHeight + h * 0.12f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(micLeft - w * 0.08f, arcTop),
            size = androidx.compose.ui.geometry.Size(micWidth + w * 0.16f, (arcBottom - arcTop) * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = arcStroke),
        )
        // Stem
        val stemTop = arcBottom
        val stemBottom = h * 0.95f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w / 2f, stemTop),
            end = androidx.compose.ui.geometry.Offset(w / 2f, stemBottom),
            strokeWidth = arcStroke,
        )
    }
}

@Composable
fun oledCardColors() = CardDefaults.cardColors(
    containerColor = Color.Black,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
fun oledCardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun oledButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Black,
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = Color(0xFF101214),
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun oledButtonBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f))

private fun hasPermission(context: Context, permission: String): Boolean {
    return if (Build.VERSION.SDK_INT < 23) true
    else ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun startService(context: Context, intent: android.content.Intent) {
    ContextCompat.startForegroundService(context, intent)
}

private fun nextMinuteBoundary(): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MINUTE, 1)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun showTimePicker(context: Context, onSelected: (Long) -> Unit) {
    val now = Calendar.getInstance()
    TimePickerDialog(
        context,
        { _, hour, minute ->
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            onSelected(target.timeInMillis)
        },
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        false,
    ).show()
}

private fun Long.formatClockTime(): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(this))
