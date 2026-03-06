package com.vibe.talkingtimer.wear

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

class WearTimerForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = TimerEngine()
    private lateinit var audioPlayer: ClipAudioPlayer
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var alarmManager: AlarmManager? = null

    private var tickJob: Job? = null
    private var lastTickerLoopElapsedRealtimeMs: Long = 0L
    private var localKeywordSpotter: LocalKeywordSpotter? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechForceStopJob: Job? = null
    private var preferredSpeechRecognizerComponent: ComponentName? = null
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
        refreshSpeechRecognizerAvailability()
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
        localKeywordSpotter?.shutdown()
        localKeywordSpotter = null
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
                        Log.w("TalkingTimerWear", "Ticker loop gap ${loopGapMs}ms")
                    }
                }
                lastTickerLoopElapsedRealtimeMs = nowElapsed
                if (WearTimerStateBus.state.value.isActive) {
                    // Tick engine from loop as fallback for rate-limited alarms.
                    // The engine's lastTickElapsedMs tracking prevents duplicate events
                    // if both the alarm and ticker fire near the same boundary.
                    val events = engine.tick(nowElapsed, System.currentTimeMillis())
                    if (events.isNotEmpty()) {
                        processEvents(events)
                        syncNextTimingAlarm()
                    }
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
        WearTimerStateBus.publish(
            WearTimerState(
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
        syncWakeLock(
            listening ||
                (snapshot.mode == TimerMode.RUNNING && snapshot.cadence.intervalMs < ALARM_CLOCK_CADENCE_THRESHOLD_MS),
        )
    }

    private fun ensureForegroundState() {
        val state = WearTimerStateBus.state.value
        if (state.isActive || listening) {
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
        val state = WearTimerStateBus.state.value
        if (!state.isActive) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: WearTimerState): Notification {
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

    private fun buildNotificationText(state: WearTimerState): String {
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
        if (!WearTimerStateBus.state.value.isActive) {
            stopSelf()
        }
    }

    private fun startListening() {
        refreshSpeechRecognizerAvailability()
        if (!speechAvailable) {
            lastStatusMessage = "Speech recognizer unavailable"
            listening = false
            return
        }
        if (listening) return

        listening = true
        lastStatusMessage = "Listening for 'go'"
        publishState()
        ensureForegroundState()
        audioPlayer.playTokens(listOf("listening"))

        if (hasLocalKeywordModelAsset()) {
            val spotter = getOrCreateLocalKeywordSpotter()
            if (!spotter.start()) {
                listening = false
                speechAvailable = false
                lastStatusMessage = spotter.lastErrorMessage() ?: "Local voice start failed"
                publishState()
                ensureForegroundState()
                maybeStopServiceIfIdle()
                return
            }
            publishState()
            ensureForegroundState()
            return
        }

        if (speechRecognizer == null) {
            val recognizer = createConfiguredSpeechRecognizer()
            if (recognizer == null) {
                speechAvailable = false
                listening = false
                lastStatusMessage = "Speech recognizer unavailable"
                maybeStopServiceIfIdle()
                return
            }
            speechRecognizer = recognizer.apply {
                setRecognitionListener(GoRecognitionListener())
            }
        }
        beginListeningSession()
        publishState()
        ensureForegroundState()
    }

    private fun refreshSpeechRecognizerAvailability() {
        if (hasLocalKeywordModelAsset()) {
            speechAvailable = true
            preferredSpeechRecognizerComponent = null
            return
        }
        val components = queryRecognitionServiceComponents()
        preferredSpeechRecognizerComponent = selectPreferredRecognitionService(components)
        speechAvailable = components.isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(this)
    }

    private fun hasLocalKeywordModelAsset(): Boolean {
        return try {
            assets.openFd("speech_commands.tflite").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateLocalKeywordSpotter(): LocalKeywordSpotter {
        return localKeywordSpotter ?: LocalKeywordSpotter(
            context = applicationContext,
            scope = serviceScope,
            listener = object : LocalKeywordSpotter.Listener {
                override fun onKeywordDetected(score: Float) {
                    Log.i("TalkingTimerWear", "Local KWS detected go score=$score")
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        if (!listening) return@launch
                        startTimerFromVoice()
                    }
                }

                override fun onError(message: String, throwable: Throwable?) {
                    Log.w("TalkingTimerWear", message, throwable)
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        if (!listening) return@launch
                        listening = false
                        lastStatusMessage = message
                        publishState()
                        ensureForegroundState()
                        maybeStopServiceIfIdle()
                    }
                }
            },
        ).also {
            localKeywordSpotter = it
        }
    }

    @Suppress("DEPRECATION")
    private fun queryRecognitionServiceComponents(): List<ComponentName> {
        val intent = Intent("android.speech.RecognitionService")
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentServices(intent, 0)
        }
        return matches.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }
    }

    private fun selectPreferredRecognitionService(components: List<ComponentName>): ComponentName? {
        if (components.isEmpty()) return null
        for (candidate in LOCAL_RECOGNIZER_CANDIDATES) {
            if (components.any { it.packageName == candidate.packageName && it.className == candidate.className }) {
                return candidate
            }
        }
        return components.first()
    }

    private fun createConfiguredSpeechRecognizer(): SpeechRecognizer? {
        return try {
            preferredSpeechRecognizerComponent?.let { component ->
                SpeechRecognizer.createSpeechRecognizer(this, component)
            } ?: SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun destroySpeechRecognizer() {
        speechForceStopJob?.cancel()
        speechForceStopJob = null
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
    }

    private fun beginListeningSession() {
        val recognizer = speechRecognizer ?: return
        speechForceStopJob?.cancel()
        speechForceStopJob = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer.cancel()
            recognizer.startListening(intent)
        } catch (e: SecurityException) {
            Log.w("TalkingTimerWear", "Speech start security failure", e)
            speechAvailable = false
            listening = false
            lastStatusMessage = "Mic permission required"
            destroySpeechRecognizer()
        } catch (e: Exception) {
            Log.w("TalkingTimerWear", "Speech start failure", e)
            listening = false
            val type = e.javaClass.simpleName.takeIf { it.isNotBlank() }
            lastStatusMessage = if (type != null) "Speech start failed ($type)" else "Speech start failed"
            destroySpeechRecognizer()
        }
    }

    private fun stopListeningInternal(updateMessage: Boolean) {
        listening = false
        restartingSpeech = false
        localKeywordSpotter?.stop()
        destroySpeechRecognizer()
        if (updateMessage) {
            lastStatusMessage = "Listening stopped"
            audioPlayer.playTokens(listOf("listening_stopped"))
        }
    }

    private fun restartListeningSoon() {
        if (!listening || restartingSpeech) return
        restartingSpeech = true
        serviceScope.launch(Dispatchers.Main.immediate) {
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
                scheduleAlarmClock(targetWall, "scheduled-start")
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
                if (snapshot.cadence.intervalMs >= ALARM_CLOCK_CADENCE_THRESHOLD_MS) {
                    scheduleAlarmClock(
                        System.currentTimeMillis() + delayMs,
                        "run-boundary@$nextElapsedTarget",
                    )
                } else {
                    // Short cadence: wake lock + ticker handle timing; alarm is backup
                    scheduleExactAlarmElapsed(
                        SystemClock.elapsedRealtime() + delayMs,
                        "run-boundary@$nextElapsedTarget",
                    )
                }
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

    private fun scheduleAlarmClock(triggerWallMs: Long, reason: String) {
        val am = alarmManager ?: getSystemService(AlarmManager::class.java) ?: return
        alarmManager = am
        val showIntent = PendingIntent.getActivity(
            this,
            TIMING_ALARM_SHOW_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val info = AlarmManager.AlarmClockInfo(triggerWallMs, showIntent)
        try {
            am.setAlarmClock(info, timingAlarmPendingIntent())
        } catch (e: SecurityException) {
            Log.w("TalkingTimerWear", "Alarm clock denied for $reason", e)
            cancelTimingAlarm()
            lastStatusMessage = "Enable exact alarms"
        } catch (t: Throwable) {
            Log.w("TalkingTimerWear", "Alarm clock failed for $reason", t)
        }
    }

    private fun scheduleExactAlarmElapsed(triggerElapsedMs: Long, reason: String) {
        val am = alarmManager ?: getSystemService(AlarmManager::class.java) ?: return
        alarmManager = am
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerElapsedMs,
                timingAlarmPendingIntent(),
            )
        } catch (e: SecurityException) {
            Log.w("TalkingTimerWear", "Exact alarm denied for $reason", e)
            cancelTimingAlarm()
            lastStatusMessage = "Enable exact alarms"
        } catch (t: Throwable) {
            Log.w("TalkingTimerWear", "Exact alarm failed for $reason", t)
        }
    }

    private fun cancelTimingAlarm() {
        val am = alarmManager ?: return
        try {
            am.cancel(timingAlarmPendingIntent())
        } catch (t: Throwable) {
            Log.w("TalkingTimerWear", "Cancel alarm failed", t)
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
            Log.w("TalkingTimerWear", "Wake lock update failed", t)
        }
    }

    private fun releaseWakeLock() {
        val wakeLock = cpuWakeLock
        cpuWakeLock = null
        if (wakeLock == null) return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (t: Throwable) {
            Log.w("TalkingTimerWear", "Wake lock release failed", t)
        }
    }

    private inner class GoRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            scheduleForceStopListening(delayMs = 2500L)
        }
        override fun onBeginningOfSpeech() {
            scheduleForceStopListening(delayMs = 1200L)
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            speechForceStopJob?.cancel()
            speechForceStopJob = null
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            logRecognitionPhrases("partial", partialResults)
            if (containsGo(partialResults)) {
                startTimerFromVoice()
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            speechForceStopJob?.cancel()
            speechForceStopJob = null
            logRecognitionPhrases("final", results)
            if (containsGo(results)) {
                startTimerFromVoice()
            } else if (listening) {
                recognitionPhrases(results).firstOrNull()?.let { heard ->
                    lastStatusMessage = "Heard: ${heard.take(24)}"
                }
                restartListeningSoon()
            }
        }

        override fun onError(error: Int) {
            if (!listening) return
            speechForceStopJob?.cancel()
            speechForceStopJob = null
            Log.i("TalkingTimerWear", "Speech onError=$error")
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

    private fun scheduleForceStopListening(delayMs: Long) {
        speechForceStopJob?.cancel()
        speechForceStopJob = serviceScope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            if (!listening) return@launch
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
        }
    }

    private fun recognitionPhrases(bundle: android.os.Bundle?): List<String> {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun logRecognitionPhrases(kind: String, bundle: android.os.Bundle?) {
        val phrases = recognitionPhrases(bundle)
        if (phrases.isEmpty()) return
        Log.i("TalkingTimerWear", "Speech $kind results=${phrases.joinToString(" | ")}")
    }

    private fun containsGo(bundle: android.os.Bundle?): Boolean {
        val list = recognitionPhrases(bundle)
        val goWord = Regex("\\bgo\\b")
        return list.any { phrase ->
            val normalized = phrase.lowercase(Locale.US)
            goWord.containsMatchIn(normalized)
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
        ensureForegroundState()
        publishState()
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
        private const val WAKE_LOCK_TAG = "TalkingTimer:WearTimer"
        private const val TICK_STALL_LOG_THRESHOLD_MS = 2_000L

        private const val EXTRA_CADENCE = "cadence"
        private const val EXTRA_START_OFFSET_MS = "start_offset_ms"
        private const val EXTRA_TARGET_WALL_MS = "target_wall_ms"
        private const val EXTRA_START_SOURCE = "start_source"

        const val ACTION_START_NOW = "com.vibe.talkingtimer.wear.action.START_NOW"
        const val ACTION_SCHEDULE_AT = "com.vibe.talkingtimer.wear.action.SCHEDULE_AT"
        const val ACTION_STOP_TIMER = "com.vibe.talkingtimer.wear.action.STOP_TIMER"
        const val ACTION_START_LISTENING = "com.vibe.talkingtimer.wear.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.vibe.talkingtimer.wear.action.STOP_LISTENING"
        private const val ACTION_TIMING_ALARM = "com.vibe.talkingtimer.wear.action.TIMING_ALARM"
        private const val TIMING_ALARM_REQUEST_CODE = 301
        private const val TIMING_ALARM_SHOW_REQUEST_CODE = 302
        private const val ALARM_CLOCK_CADENCE_THRESHOLD_MS = 30_000L
        private const val UI_TICK_INTERVAL_MS = 1_000L

        private val LOCAL_RECOGNIZER_CANDIDATES = listOf(
            ComponentName("com.alexvt.whisperinput", "com.alexvt.whisperinput.speak.service.OfflineRecognitionService"),
            ComponentName("com.elishaazaria.sayboard", "com.elishaazaria.sayboard.services.SayboardRecognitionService"),
        )

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
            return Intent(context, WearTimerForegroundService::class.java).setAction(action)
        }
    }
}
