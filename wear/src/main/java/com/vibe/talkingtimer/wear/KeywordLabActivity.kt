package com.vibe.talkingtimer.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KeywordLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = WearOledColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    KeywordLabScreen(onDone = { finish() })
                }
            }
        }
    }
}

@Composable
private fun KeywordLabScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { KeywordCalibrationRecorder(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { recorder.close() }
    }

    var calibration by remember { mutableStateOf(KeywordCalibrationStore.load(context)) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    var status by remember {
        mutableStateOf(
            if (calibration.usesDefaultThreshold) {
                "Default threshold active"
            } else {
                "Using ${calibration.recordingCount} recording(s)"
            },
        )
    }
    var lastCaptureSummary by remember { mutableStateOf<String?>(null) }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Mic permission required"
    }

    val isBusy = busyJob != null

    fun reloadCalibration(message: String? = null) {
        calibration = KeywordCalibrationStore.load(context)
        if (message != null) {
            status = message
        } else if (calibration.usesDefaultThreshold) {
            status = "Default threshold active"
        } else {
            status = "Using ${calibration.recordingCount} recording(s)"
        }
    }

    fun runCapture(saveResult: Boolean) {
        if (!hasMicPermission(context)) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        busyJob = scope.launch {
            try {
                status = if (saveResult) "Say 'go' once" else "Testing: say 'go' once"
                lastCaptureSummary = null
                if (WearTimerStateBus.state.value.listening) {
                    ContextCompat.startForegroundService(
                        context,
                        WearTimerForegroundService.stopListeningIntent(context),
                    )
                }
                val result = recorder.captureOneSample()
                lastCaptureSummary = "go=${"%.2f".format(java.util.Locale.US, result.maxGoScore)} " +
                    "top=${result.bestTopLabel.ifBlank { "-" }}:${"%.2f".format(java.util.Locale.US, result.bestTopScore)}"

                if (saveResult) {
                    if (result.maxGoScore >= 0.12f && result.peakRms >= 0.005f) {
                        val updated = KeywordCalibrationStore.addGoRecording(context, result.maxGoScore)
                        calibration = updated
                        status = "Saved recording #${updated.recordingCount}"
                    } else {
                        status = "No clear 'go' detected, try again"
                    }
                } else {
                    val threshold = calibration.effectiveGoThreshold
                    val passed = result.maxGoScore >= threshold && result.peakRms >= 0.005f
                    status = if (passed) {
                        "Test pass (${result.maxGoScore.format2()} >= ${threshold.format2()})"
                    } else {
                        "Test miss (${result.maxGoScore.format2()} < ${threshold.format2()})"
                    }
                }
            } catch (t: Throwable) {
                status = if (saveResult) "Record failed: ${t.javaClass.simpleName}" else "Test failed: ${t.javaClass.simpleName}"
            } finally {
                busyJob = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = oledCardColors(),
                border = oledCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Keyword Lab", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Record 'go' samples to tune detection. Reset returns to the default threshold.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = oledCardColors(),
                border = oledCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Recordings: ${calibration.recordingCount}")
                    Text(
                        "Go threshold: ${"%.2f".format(java.util.Locale.US, calibration.effectiveGoThreshold)}" +
                            if (calibration.usesDefaultThreshold) " (default)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    calibration.averageGoScore?.let {
                        Text(
                            "Avg saved go score: ${"%.2f".format(java.util.Locale.US, it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    calibration.bestGoScore?.let {
                        Text(
                            "Best saved go score: ${"%.2f".format(java.util.Locale.US, it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = oledCardColors(),
                border = oledCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !isBusy,
                            onClick = { runCapture(saveResult = true) },
                            modifier = Modifier.weight(1f),
                            colors = oledButtonColors(),
                            border = oledButtonBorder(),
                        ) {
                            Text(if (isBusy) "🎙️" else "🎙️+1")
                        }
                        Button(
                            enabled = !isBusy,
                            onClick = { runCapture(saveResult = false) },
                            modifier = Modifier.weight(1f),
                            colors = oledButtonColors(),
                            border = oledButtonBorder(),
                        ) {
                            Text("🧪")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !isBusy && !calibration.usesDefaultThreshold,
                            onClick = {
                                calibration = KeywordCalibrationStore.removeLast(context)
                                reloadCalibration(
                                    if (calibration.usesDefaultThreshold) "Removed last (default active)"
                                    else "Removed last (#${calibration.recordingCount} left)",
                                )
                                lastCaptureSummary = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = oledButtonColors(),
                            border = oledButtonBorder(),
                        ) {
                            Text("⌫")
                        }
                        Button(
                            enabled = !isBusy && !calibration.usesDefaultThreshold,
                            onClick = {
                                KeywordCalibrationStore.reset(context)
                                reloadCalibration("Reset to default")
                                lastCaptureSummary = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = oledButtonColors(),
                            border = oledButtonBorder(),
                        ) {
                            Text("🗑️")
                        }
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.contains("failed", ignoreCase = true)) {
                            Color(0xFFFFB4AB)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    lastCaptureSummary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Done")
            }
        }
    }
}

private fun hasMicPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT < 23) true
    else ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

private fun Float.format2(): String = "%.2f".format(java.util.Locale.US, this)
