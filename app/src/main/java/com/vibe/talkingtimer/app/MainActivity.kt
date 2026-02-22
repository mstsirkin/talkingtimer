package com.vibe.talkingtimer.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
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
                Surface {
                    TalkingTimerScreen()
                }
            }
        }
    }
}

@Composable
private fun TalkingTimerScreen() {
    val context = LocalContext.current
    val state by PhoneTimerStateBus.state.collectAsStateWithLifecycle()

    var cadence by rememberSaveable { mutableStateOf(AnnouncementCadence.EVERY_30S) }
    var offsetSecondsText by rememberSaveable { mutableStateOf("0") }
    var scheduledTargetWallMs by rememberSaveable { mutableLongStateOf(0L) }

    val notificationPermissionLauncher = rememberPermissionLauncher(Manifest.permission.POST_NOTIFICATIONS)
    val micPermissionLauncher = rememberPermissionLauncher(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.tertiaryContainer,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Talking Timer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(state.statusMessage, style = MaterialTheme.typography.bodyLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.timeLabel, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("Mode: ${state.mode.name.lowercase()}${if (state.listening) " + listening" else ""}")
                Text("Cadence: ${state.cadence.label}")
                state.scheduledStartWallClockMs?.let { Text("Scheduled: ${it.formatClockTime()}") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Cadence", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnnouncementCadence.entries.forEach { option ->
                        val selected = option == cadence
                        TextButton(
                            onClick = { cadence = option },
                            modifier = Modifier.background(
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(20.dp),
                            ),
                        ) {
                            Text(option.label)
                        }
                    }
                }

                OutlinedTextField(
                    value = offsetSecondsText,
                    onValueChange = { offsetSecondsText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Start offset seconds (negative allowed)") },
                    supportingText = { Text("Examples: 0, -10, -300") },
                    singleLine = true,
                )

                val offsetMs = offsetSecondsText.toLongOrNull()?.times(1000L) ?: 0L

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            startService(context, TimerForegroundService.startNowIntent(context, cadence, offsetMs))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    Button(
                        onClick = {
                            startService(context, TimerForegroundService.stopTimerIntent(context))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Schedule Start", style = MaterialTheme.typography.titleMedium)
                val targetLabel = if (scheduledTargetWallMs > 0L) scheduledTargetWallMs.formatClockTimeWithDate() else "No time selected"
                Text(targetLabel)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { showTimePicker(context) { scheduledTargetWallMs = it } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pick Time") }
                    Button(
                        onClick = {
                            val target = if (scheduledTargetWallMs > 0L) scheduledTargetWallMs else nextMinuteBoundary()
                            startService(context, TimerForegroundService.scheduleIntent(context, cadence, offsetMs, target))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Schedule") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Voice Start (last step)", style = MaterialTheme.typography.titleMedium)
                Text(if (state.speechAvailable) "On-device speech preferred; say 'go'" else "Speech recognizer unavailable on this device")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        enabled = state.speechAvailable,
                        onClick = {
                            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                startService(context, TimerForegroundService.startListeningIntent(context, cadence, offsetMs))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Listen for Go") }
                    Button(
                        onClick = { startService(context, TimerForegroundService.stopListeningIntent(context)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop Listening") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Runtime is local-only for timing and playback. Audio clips are pre-rendered into assets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberPermissionLauncher(permission: String) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

private fun hasPermission(context: Context, permission: String): Boolean {
    return if (Build.VERSION.SDK_INT < 23) true
    else ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun startService(context: Context, intent: android.content.Intent) {
    ContextCompat.startForegroundService(context, intent)
}

private fun Long.formatClockTime(): String = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(this))
private fun Long.formatClockTimeWithDate(): String = SimpleDateFormat("EEE h:mm:ss a", Locale.US).format(Date(this))

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
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            onSelected(target.timeInMillis)
        },
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        false,
    ).show()
}
