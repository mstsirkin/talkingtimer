package com.vibe.talkingtimer.wear

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.talkingtimer.core.AnnouncementCadence
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { WearTimerScreen() }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WearTimerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by WearTimerStateBus.state.collectAsStateWithLifecycle()

    var cadence by rememberSaveable { mutableStateOf(AnnouncementCadence.EVERY_30S) }
    var offsetSecondsText by rememberSaveable { mutableStateOf("0") }
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

    val bg = Brush.radialGradient(
        colors = listOf(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surface,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
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
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    listOf(AnnouncementCadence.EVERY_10S, AnnouncementCadence.EVERY_30S).forEach { option ->
                                        CadenceChip(option, cadence == option) { cadence = option }
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    listOf(AnnouncementCadence.EVERY_1M, AnnouncementCadence.EVERY_5M).forEach { option ->
                                        CadenceChip(option, cadence == option) { cadence = option }
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        onClick = { startService(context, WearTimerForegroundService.startNowIntent(context, cadence, offsetMs)) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Start") }
                                    Button(
                                        onClick = { startService(context, WearTimerForegroundService.stopTimerIntent(context)) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Stop") }
                                }
                            }
                        }
                    }

                    1 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
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
                                    Button(onClick = { scheduledTargetWallMs = nextMinuteBoundary() }, modifier = Modifier.weight(1f)) { Text("+1m") }
                                    Button(
                                        onClick = {
                                            val target = if (scheduledTargetWallMs > 0L) scheduledTargetWallMs else nextMinuteBoundary()
                                            startService(context, WearTimerForegroundService.scheduleIntent(context, cadence, offsetMs, target))
                                        },
                                        modifier = Modifier.weight(1f),
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
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (state.speechAvailable) "Say 'go'" else "Unavailable", style = MaterialTheme.typography.bodySmall)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        enabled = state.speechAvailable,
                                        onClick = {
                                            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                                micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                startService(context, WearTimerForegroundService.startListeningIntent(context, cadence, offsetMs))
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Listen") }
                                    Button(
                                        onClick = { startService(context, WearTimerForegroundService.stopListeningIntent(context)) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Stop") }
                                }
                                Text(
                                    "Notification stays on while active.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
private fun RunTimeHeader(timeLabel: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun RowScope.CadenceChip(option: AnnouncementCadence, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 34.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                RoundedCornerShape(16.dp),
            ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(option.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CompactStatusHeader(page: Int, timeLabel: String, statusMessage: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
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
