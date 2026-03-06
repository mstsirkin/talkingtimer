package com.vibe.talkingtimer.wear

import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import kotlin.math.sqrt

class LocalKeywordSpotter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onKeywordDetected(score: Float)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private var classifier: AudioClassifier? = null
    private var tensorAudio: TensorAudio? = null
    private var recorder: AudioRecord? = null
    private var loopJob: Job? = null

    @Volatile
    private var running = false

    private var initError: String? = null
    private var goThreshold: Float = KeywordCalibrationStore.DEFAULT_GO_THRESHOLD
    private var strongGoThreshold: Float = KeywordCalibrationStore.DEFAULT_STRONG_GO_THRESHOLD
    private var lastTriggerElapsedMs: Long = 0L
    private var lastGoHitElapsedMs: Long = 0L
    private var goHitStreak: Int = 0
    private var lastDebugLogElapsedMs: Long = 0L

    @Synchronized
    fun isAvailable(): Boolean {
        return ensureInitialized()
    }

    @Synchronized
    fun start(): Boolean {
        if (!ensureInitialized()) return false
        if (running) return true
        refreshThresholds()
        val localRecorder = recorder ?: return false
        try {
            localRecorder.startRecording()
        } catch (t: Throwable) {
            initError = "Mic start failed"
            listener.onError(initError ?: "Mic start failed", t)
            return false
        }
        running = true
        // Offset forward so the cooldown covers the prompt audio ("say go")
        // plus mic buffer clearing time: total ignore = this offset + TRIGGER_COOLDOWN_MS
        lastTriggerElapsedMs = SystemClock.elapsedRealtime() + PROMPT_SETTLE_MS
        goHitStreak = 0
        loopJob = scope.launch(Dispatchers.Default) {
            runLoop()
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun shutdown() {
        stop()
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        try {
            classifier?.close()
        } catch (_: Throwable) {
        }
        classifier = null
        tensorAudio = null
    }

    @Synchronized
    fun lastErrorMessage(): String? = initError

    @Synchronized
    private fun ensureInitialized(): Boolean {
        if (classifier != null && tensorAudio != null && recorder != null) {
            initError = null
            return true
        }

        return try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                .setMaxResults(4)
                .build()

            val localClassifier = AudioClassifier.createFromFileAndOptions(context, MODEL_ASSET, options)
            val localTensorAudio = localClassifier.createInputTensorAudio()
            val localRecorder = localClassifier.createAudioRecord()

            classifier = localClassifier
            tensorAudio = localTensorAudio
            recorder = localRecorder
            initError = null
            true
        } catch (t: Throwable) {
            initError = "Local voice init failed"
            listener.onError(initError ?: "Local voice init failed", t)
            false
        }
    }

    private suspend fun runLoop() {
        val localClassifier = classifier ?: return
        val localTensorAudio = tensorAudio ?: return
        val localRecorder = recorder ?: return

        val windowMs = ((localClassifier.requiredInputBufferSize.toFloat() /
            localClassifier.requiredTensorAudioFormat.sampleRate.toFloat()) * 1000f).toLong()
            .coerceAtLeast(100L)
        val intervalMs = (windowMs / 2L).coerceIn(80L, 500L)

        while (running && scope.isActive) {
            try {
                localTensorAudio.load(localRecorder)
                val output = localClassifier.classify(localTensorAudio)
                val categories = output.firstOrNull()?.categories.orEmpty()
                val rms = estimateRms(localTensorAudio)
                maybeTrigger(categories, rms)
            } catch (t: Throwable) {
                Log.w(TAG, "KWS loop failure", t)
                listener.onError("Local voice failed", t)
                running = false
                break
            }

            delay(intervalMs)
        }
    }

    private fun maybeTrigger(categories: List<org.tensorflow.lite.support.label.Category>, rms: Float?) {
        if (categories.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val normalized = categories.map { it.label.trim().lowercase() to it.score }
        val goScore = normalized.firstOrNull { it.first == "go" }?.second ?: 0f
        val silenceScore = normalized.firstOrNull { it.first == "silence" || it.first == "_silence_" }?.second ?: 0f
        val unknownScore = normalized.firstOrNull { it.first == "unknown" || it.first == "_unknown_" }?.second ?: 0f
        val top = normalized.maxByOrNull { it.second }

        val vadSpeech = when {
            rms != null -> rms >= RMS_VAD_THRESHOLD
            top == null -> false
            top.first == "silence" || top.first == "_silence_" -> false
            silenceScore >= 0.6f -> false
            unknownScore >= 0.9f && top.second < 0.7f -> false
            else -> true
        }

        if (now - lastDebugLogElapsedMs > 1200L) {
            lastDebugLogElapsedMs = now
            Log.i(
                TAG,
                "KWS top=${top?.first}:${top?.second ?: 0f} go=$goScore thresh=$goThreshold rms=${rms ?: -1f} vad=$vadSpeech",
            )
        }

        if (!vadSpeech || goScore < goThreshold) {
            if (now - lastGoHitElapsedMs > HIT_WINDOW_MS) {
                goHitStreak = 0
            }
            return
        }

        if (now - lastTriggerElapsedMs < TRIGGER_COOLDOWN_MS) {
            return
        }

        goHitStreak = if (now - lastGoHitElapsedMs <= HIT_WINDOW_MS) goHitStreak + 1 else 1
        lastGoHitElapsedMs = now

        val shouldTrigger = goScore >= strongGoThreshold || goHitStreak >= REQUIRED_HITS
        if (!shouldTrigger) return

        lastTriggerElapsedMs = now
        goHitStreak = 0
        listener.onKeywordDetected(goScore)
    }

    private fun estimateRms(tensorAudio: TensorAudio): Float? {
        return try {
            val tensorBuffer = tensorAudio.javaClass.methods
                .firstOrNull { it.name == "getTensorBuffer" && it.parameterCount == 0 }
                ?.invoke(tensorAudio)
                ?: return null
            val floatArray = tensorBuffer.javaClass.methods
                .firstOrNull { it.name == "getFloatArray" && it.parameterCount == 0 }
                ?.invoke(tensorBuffer) as? FloatArray
                ?: return null
            if (floatArray.isEmpty()) return null
            var sum = 0.0
            for (v in floatArray) {
                sum += (v * v).toDouble()
            }
            sqrt(sum / floatArray.size).toFloat()
        } catch (_: Throwable) {
            null
        }
    }

    private fun refreshThresholds() {
        val calibration = KeywordCalibrationStore.load(context)
        goThreshold = calibration.effectiveGoThreshold
        strongGoThreshold = calibration.effectiveStrongGoThreshold
    }

    companion object {
        private const val TAG = "TalkingTimerKws"
        private const val MODEL_ASSET = "speech_commands.tflite"
        private const val REQUIRED_HITS = 1
        private const val HIT_WINDOW_MS = 700L
        private const val TRIGGER_COOLDOWN_MS = 1500L
        private const val PROMPT_SETTLE_MS = 1000L

        // TensorAudio commonly provides normalized float PCM in [-1, 1].
        private const val RMS_VAD_THRESHOLD = 0.01f
    }
}
