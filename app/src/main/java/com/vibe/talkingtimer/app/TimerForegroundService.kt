package com.vibe.talkingtimer.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vibe.talkingtimer.core.AnnouncementCadence
import com.vibe.talkingtimer.core.CalloutPhraseBuilder
import com.vibe.talkingtimer.core.StartSource
import com.vibe.talkingtimer.core.TimerConfig
import com.vibe.talkingtimer.core.TimerEngine
import com.vibe.talkingtimer.core.TimerEvent
import com.vibe.talkingtimer.core.TimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimerForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = TimerEngine()
    private lateinit var audioPlayer: ClipAudioPlayer
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var alarmManager: AlarmManager? = null

    private var tickJob: Job? = null
    private var lastTickerLoopElapsedRealtimeMs: Long = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechAvailable: Boolean = true
    private var listening: Boolean = false
    private var restartingSpeech: Boolean = false

    private var currentCadence = AnnouncementCadence.EVERY_30S
    private var currentStartOffsetMs = 0L
    private var lastStatusMessage: String = "Idle"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initWakeLock()
        alarmManager = getSystemService(AlarmManager::class.java)
        audioPlayer = ClipAudioPlayer(this, serviceScope)
        speechAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        startTicker()
        publishState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        publishState()
        ensureForegroundState()
        updateNotification()
        syncNextTimingAlarm()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListeningInternal(updateMessage = false)
        tickJob?.cancel()
        serviceScope.launch {
            audioPlayer.shutdown()
        }
        cancelTimingAlarm()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                val nowElapsed = SystemClock.elapsedRealtime()
                if (lastTickerLoopElapsedRealtimeMs != 0L) {
                    val loopGapMs = nowElapsed - lastTickerLoopElapsedRealtimeMs
                    if (loopGapMs > TICK_STALL_LOG_THRESHOLD_MS) {
                        Log.w("TalkingTimerApp", "Ticker loop gap ${loopGapMs}ms")
                    }
                }
                lastTickerLoopElapsedRealtimeMs = nowElapsed
                if (PhoneTimerStateBus.state.value.isActive) {
                    publishState()
                    ensureForegroundState()
                    updateNotification()
                }
                delay(UI_TICK_INTERVAL_MS)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_TIMING_ALARM -> {
                onTimingAlarm()
            }

            ACTION_START_NOW -> {
                if (!requireExactAlarmsForTimer("Enable exact alarms")) return
                val cfg = readConfig(intent)
                currentCadence = cfg.cadence
                currentStartOffsetMs = cfg.startOffsetMs
                stopListeningInternal(updateMessage = false)
                val events = engine.startNow(
                    nowRealtimeMs = SystemClock.elapsedRealtime(),
                    cfg = cfg,
                    source = intent.getStringExtra(EXTRA_START_SOURCE)?.let { parseStartSource(it) } ?: StartSource.MANUAL,
                )
                processEvents(events)
            }

            ACTION_SCHEDULE_AT -> {
                if (!requireExactAlarmsForTimer("Enable exact alarms")) return
                val cfg = readConfig(intent)
                currentCadence = cfg.cadence
                currentStartOffsetMs = cfg.startOffsetMs
                val target = intent.getLongExtra(EXTRA_TARGET_WALL_MS, -1L)
                if (target <= 0L) {
                    lastStatusMessage = "Invalid schedule"
                    return
                }
                stopListeningInternal(updateMessage = false)
                val events = engine.scheduleAt(
                    nowRealtimeMs = SystemClock.elapsedRealtime(),
                    nowWallClockMs = System.currentTimeMillis(),
                    targetWallClockMs = target,
                    cfg = cfg,
                )
                if (events.isEmpty()) {
                    lastStatusMessage = "Scheduled ${formatClockTime(target)}"
                }
                processEvents(events)
            }

            ACTION_STOP_TIMER -> {
                processEvents(engine.stop())
                lastStatusMessage = if (listening) "Listening" else "Stopped"
                maybeStopServiceIfIdle()
            }

            ACTION_START_LISTENING -> {
                if (!requireExactAlarmsForTimer("Enable exact alarms")) return
                currentCadence = parseCadence(intent.getStringExtra(EXTRA_CADENCE)) ?: currentCadence
                currentStartOffsetMs = intent.getLongExtra(EXTRA_START_OFFSET_MS, currentStartOffsetMs)
                startListening()
            }

            ACTION_STOP_LISTENING -> {
                stopListeningInternal(updateMessage = true)
                maybeStopServiceIfIdle()
            }
        }
    }

    private fun onTimingAlarm() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val events = engine.tick(nowElapsed, nowWall)
        if (events.isNotEmpty()) {
            processEvents(events)
        }
    }

    private fun processEvents(events: List<TimerEvent>) {
        for (event in events) {
            when (event) {
                is TimerEvent.Started -> {
                    lastStatusMessage = when (event.source) {
                        StartSource.SCHEDULED -> "Timer started"
                        StartSource.VOICE -> "Started by voice"
                        StartSource.MANUAL -> "Started"
                    }
                    when (event.source) {
                        StartSource.SCHEDULED -> audioPlayer.playTokens(listOf("timer_started"))
                        else -> audioPlayer.playTokens(listOf("started"))
                    }
                }

                is TimerEvent.Countdown -> audioPlayer.playTokens(listOf("n_${event.value}"))
                TimerEvent.Go -> audioPlayer.playTokens(listOf("go"))
                is TimerEvent.PeriodicCallout -> audioPlayer.playTokens(CalloutPhraseBuilder.buildClipTokens(event.elapsedMs))
                is TimerEvent.Stopped -> {
                    if (!listening) lastStatusMessage = "Stopped"
                }
            }
        }
    }

    private fun publishState() {
        val snapshot = engine.snapshot(
            nowRealtimeMs = SystemClock.elapsedRealtime(),
            nowWallClockMs = System.currentTimeMillis(),
        )
        val message = when {
            listening && snapshot.mode == TimerMode.IDLE -> if (speechAvailable) "Listening for 'go'" else "Speech unavailable"
            snapshot.mode == TimerMode.WAITING_FOR_SCHEDULE -> {
                "Scheduled ${snapshot.scheduledStartWallClockMs?.let(::formatClockTime) ?: ""}".trim()
            }
            else -> lastStatusMessage
        }
        PhoneTimerStateBus.publish(
            PhoneTimerState(
                mode = snapshot.mode,
                elapsedMs = snapshot.elapsedMs,
                cadence = snapshot.cadence,
                startOffsetMs = snapshot.startOffsetMs,
                scheduledStartWallClockMs = snapshot.scheduledStartWallClockMs,
                listening = listening,
                speechAvailable = speechAvailable,
                statusMessage = message,
            ),
        )
        syncWakeLock(listening)
    }

    private fun ensureForegroundState() {
        val state = PhoneTimerStateBus.state.value
        if (state.isActive) {
            val notification = buildNotification(state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (listening) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun updateNotification() {
        val state = PhoneTimerStateBus.state.value
        if (!state.isActive) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PhoneTimerState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Talking Timer")
            .setContentText(buildNotificationText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.mode != TimerMode.IDLE) {
            val stopPending = PendingIntent.getService(
                this,
                101,
                intentFor(this, ACTION_STOP_TIMER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop", stopPending)
        }
        if (state.listening) {
            val stopListeningPending = PendingIntent.getService(
                this,
                102,
                intentFor(this, ACTION_STOP_LISTENING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop Listening", stopListeningPending)
        }
        return builder.build()
    }

    private fun buildNotificationText(state: PhoneTimerState): String {
        return when {
            state.mode == TimerMode.RUNNING -> "${state.timeLabel} · ${state.cadence.label}"
            state.mode == TimerMode.WAITING_FOR_SCHEDULE -> state.statusMessage
            state.listening -> state.statusMessage
            else -> state.statusMessage
        }
    }

    private fun maybeStopServiceIfIdle() {
        publishState()
        ensureForegroundState()
        cancelTimingAlarm()
        if (!PhoneTimerStateBus.state.value.isActive) {
            stopSelf()
        }
    }

    private fun startListening() {
        if (!speechAvailable) {
            lastStatusMessage = "Speech recognizer unavailable"
            listening = false
            return
        }
        if (listening) return

        listening = true
        lastStatusMessage = "Listening for 'go'"
        audioPlayer.playTokens(listOf("listening"))
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(GoRecognitionListener())
            }
        }
        beginListeningSession()
        publishState()
        ensureForegroundState()
        updateNotification()
    }

    private fun beginListeningSession() {
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer.cancel()
            recognizer.startListening(intent)
        } catch (_: SecurityException) {
            speechAvailable = false
            listening = false
            lastStatusMessage = "Mic permission required"
        } catch (_: Exception) {
            listening = false
            lastStatusMessage = "Speech start failed"
        }
    }

    private fun stopListeningInternal(updateMessage: Boolean) {
        listening = false
        restartingSpeech = false
        speechRecognizer?.let {
            try {
                it.stopListening()
            } catch (_: Exception) {
            }
            try {
                it.cancel()
            } catch (_: Exception) {
            }
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }
        speechRecognizer = null
        if (updateMessage) {
            lastStatusMessage = "Listening stopped"
            audioPlayer.playTokens(listOf("listening_stopped"))
        }
    }

    private fun restartListeningSoon() {
        if (!listening || restartingSpeech) return
        restartingSpeech = true
        serviceScope.launch {
            delay(400)
            restartingSpeech = false
            if (listening) {
                beginListeningSession()
                publishState()
                updateNotification()
            }
        }
    }

    private fun requireExactAlarmsForTimer(message: String): Boolean {
        if (canScheduleExactAlarmsCompat()) return true
        if (listening) {
            stopListeningInternal(updateMessage = false)
        }
        lastStatusMessage = message
        cancelTimingAlarm()
        return false
    }

    private fun canScheduleExactAlarmsCompat(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarmManager ?: getSystemService(AlarmManager::class.java) ?: return false
        alarmManager = am
        return am.canScheduleExactAlarms()
    }

    private fun syncNextTimingAlarm() {
        val snapshot = engine.snapshot(
            nowRealtimeMs = SystemClock.elapsedRealtime(),
            nowWallClockMs = System.currentTimeMillis(),
        )
        if (snapshot.mode == TimerMode.IDLE) {
            cancelTimingAlarm()
            return
        }
        if (!canScheduleExactAlarmsCompat()) {
            cancelTimingAlarm()
            return
        }
        when (snapshot.mode) {
            TimerMode.WAITING_FOR_SCHEDULE -> {
                val targetWall = snapshot.scheduledStartWallClockMs ?: run {
                    cancelTimingAlarm()
                    return
                }
                scheduleExactAlarm(
                    alarmType = AlarmManager.RTC_WAKEUP,
                    triggerAtMs = targetWall,
                    reason = "scheduled-start",
                )
            }

            TimerMode.RUNNING -> {
                val nextElapsedTarget = nextRunBoundaryElapsedMs(
                    elapsedMs = snapshot.elapsedMs,
                    cadence = snapshot.cadence,
                ) ?: run {
                    cancelTimingAlarm()
                    return
                }
                val delayMs = (nextElapsedTarget - snapshot.elapsedMs).coerceAtLeast(1L)
                scheduleExactAlarm(
                    alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs = SystemClock.elapsedRealtime() + delayMs,
                    reason = "run-boundary@$nextElapsedTarget",
                )
            }

            TimerMode.IDLE -> cancelTimingAlarm()
        }
    }

    private fun nextRunBoundaryElapsedMs(elapsedMs: Long, cadence: AnnouncementCadence): Long? {
        var next: Long? = null

        fun consider(candidate: Long) {
            if (candidate <= elapsedMs) return
            if (next == null || candidate < next!!) {
                next = candidate
            }
        }

        consider(-5_000L)
        consider(-4_000L)
        consider(-3_000L)
        consider(-2_000L)
        consider(-1_000L)
        consider(0L)

        val interval = cadence.intervalMs
        if (interval > 0L) {
            val start = (elapsedMs + 1L).coerceAtLeast(1L)
            val cadenceBoundary = ceilDiv(start, interval) * interval
            consider(cadenceBoundary)
        }

        return next
    }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        return if (value <= 0L) 0L else (value + divisor - 1L) / divisor
    }

    private fun scheduleExactAlarm(alarmType: Int, triggerAtMs: Long, reason: String) {
        val am = alarmManager ?: getSystemService(AlarmManager::class.java) ?: return
        alarmManager = am
        try {
            am.setExactAndAllowWhileIdle(alarmType, triggerAtMs, timingAlarmPendingIntent())
        } catch (e: SecurityException) {
            Log.w("TalkingTimerApp", "Exact alarm denied for $reason", e)
            cancelTimingAlarm()
            lastStatusMessage = "Enable exact alarms"
        } catch (t: Throwable) {
            Log.w("TalkingTimerApp", "Exact alarm failed for $reason", t)
        }
    }

    private fun cancelTimingAlarm() {
        val am = alarmManager ?: return
        try {
            am.cancel(timingAlarmPendingIntent())
        } catch (t: Throwable) {
            Log.w("TalkingTimerApp", "Cancel alarm failed", t)
        }
    }

    private fun timingAlarmPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this,
            TIMING_ALARM_REQUEST_CODE,
            intentFor(this, ACTION_TIMING_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun initWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    private fun syncWakeLock(shouldHold: Boolean) {
        val wakeLock = cpuWakeLock ?: return
        try {
            if (shouldHold) {
                if (!wakeLock.isHeld) wakeLock.acquire()
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (t: Throwable) {
            Log.w("TalkingTimerApp", "Wake lock update failed", t)
        }
    }

    private fun releaseWakeLock() {
        val wakeLock = cpuWakeLock
        cpuWakeLock = null
        if (wakeLock == null) return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (t: Throwable) {
            Log.w("TalkingTimerApp", "Wake lock release failed", t)
        }
    }

    private inner class GoRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (containsGo(partialResults)) {
                startTimerFromVoice()
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            if (containsGo(results)) {
                startTimerFromVoice()
            } else if (listening) {
                restartListeningSoon()
            }
        }

        override fun onError(error: Int) {
            if (!listening) return
            lastStatusMessage = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    speechAvailable = false
                    listening = false
                    "Mic permission required"
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> "Speech network error (offline preferred)"
                else -> "Listening..."
            }
            if (listening) restartListeningSoon() else maybeStopServiceIfIdle()
            publishState()
            updateNotification()
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun containsGo(bundle: android.os.Bundle?): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        return list.any { phrase ->
            val normalized = phrase.lowercase(Locale.US)
            normalized == "go" || normalized.contains(" go ") || normalized.startsWith("go ") || normalized.endsWith(" go")
        }
    }

    private fun startTimerFromVoice() {
        if (!listening) return
        if (!requireExactAlarmsForTimer("Enable exact alarms")) {
            stopListeningInternal(updateMessage = false)
            publishState()
            ensureForegroundState()
            updateNotification()
            maybeStopServiceIfIdle()
            return
        }
        stopListeningInternal(updateMessage = false)
        val events = engine.startNow(
            nowRealtimeMs = SystemClock.elapsedRealtime(),
            cfg = TimerConfig(cadence = currentCadence, startOffsetMs = currentStartOffsetMs),
            source = StartSource.VOICE,
        )
        processEvents(events)
        publishState()
        ensureForegroundState()
        updateNotification()
        syncNextTimingAlarm()
    }

    private fun readConfig(intent: Intent): TimerConfig {
        return TimerConfig(
            cadence = parseCadence(intent.getStringExtra(EXTRA_CADENCE)) ?: currentCadence,
            startOffsetMs = intent.getLongExtra(EXTRA_START_OFFSET_MS, currentStartOffsetMs),
        )
    }

    private fun parseCadence(label: String?): AnnouncementCadence? {
        return AnnouncementCadence.entries.firstOrNull { it.name == label }
    }

    private fun parseStartSource(raw: String): StartSource {
        return StartSource.entries.firstOrNull { it.name == raw } ?: StartSource.MANUAL
    }

    private fun formatClockTime(wallMs: Long): String {
        return SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(wallMs))
    }

    companion object {
        private const val CHANNEL_ID = "talking_timer"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "TalkingTimer:PhoneTimer"
        private const val TICK_STALL_LOG_THRESHOLD_MS = 2_000L

        private const val EXTRA_CADENCE = "cadence"
        private const val EXTRA_START_OFFSET_MS = "start_offset_ms"
        private const val EXTRA_TARGET_WALL_MS = "target_wall_ms"
        private const val EXTRA_START_SOURCE = "start_source"

        const val ACTION_START_NOW = "com.vibe.talkingtimer.app.action.START_NOW"
        const val ACTION_SCHEDULE_AT = "com.vibe.talkingtimer.app.action.SCHEDULE_AT"
        const val ACTION_STOP_TIMER = "com.vibe.talkingtimer.app.action.STOP_TIMER"
        const val ACTION_START_LISTENING = "com.vibe.talkingtimer.app.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.vibe.talkingtimer.app.action.STOP_LISTENING"
        private const val ACTION_TIMING_ALARM = "com.vibe.talkingtimer.app.action.TIMING_ALARM"
        private const val TIMING_ALARM_REQUEST_CODE = 301
        private const val UI_TICK_INTERVAL_MS = 1_000L

        fun startNowIntent(context: Context, cadence: AnnouncementCadence, startOffsetMs: Long, source: StartSource = StartSource.MANUAL): Intent {
            return intentFor(context, ACTION_START_NOW).apply {
                putExtra(EXTRA_CADENCE, cadence.name)
                putExtra(EXTRA_START_OFFSET_MS, startOffsetMs)
                putExtra(EXTRA_START_SOURCE, source.name)
            }
        }

        fun scheduleIntent(context: Context, cadence: AnnouncementCadence, startOffsetMs: Long, targetWallMs: Long): Intent {
            return intentFor(context, ACTION_SCHEDULE_AT).apply {
                putExtra(EXTRA_CADENCE, cadence.name)
                putExtra(EXTRA_START_OFFSET_MS, startOffsetMs)
                putExtra(EXTRA_TARGET_WALL_MS, targetWallMs)
            }
        }

        fun startListeningIntent(context: Context, cadence: AnnouncementCadence, startOffsetMs: Long): Intent {
            return intentFor(context, ACTION_START_LISTENING).apply {
                putExtra(EXTRA_CADENCE, cadence.name)
                putExtra(EXTRA_START_OFFSET_MS, startOffsetMs)
            }
        }

        fun stopListeningIntent(context: Context): Intent = intentFor(context, ACTION_STOP_LISTENING)
        fun stopTimerIntent(context: Context): Intent = intentFor(context, ACTION_STOP_TIMER)

        private fun intentFor(context: Context, action: String): Intent {
            return Intent(context, TimerForegroundService::class.java).setAction(action)
        }
    }
}
