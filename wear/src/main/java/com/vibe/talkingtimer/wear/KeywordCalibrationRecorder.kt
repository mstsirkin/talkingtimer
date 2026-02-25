package com.vibe.talkingtimer.wear

import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import kotlin.math.sqrt

class KeywordCalibrationRecorder(
    private val context: Context,
) {
    data class Frame(
        val goScore: Float,
        val topLabel: String,
        val topScore: Float,
        val rms: Float?,
    )

    data class CaptureResult(
        val maxGoScore: Float,
        val bestTopLabel: String,
        val bestTopScore: Float,
        val peakRms: Float,
        val framesSeen: Int,
    )

    private var classifier: AudioClassifier? = null
    private var tensorAudio: TensorAudio? = null
    private var recorder: AudioRecord? = null

    suspend fun captureOneSample(
        durationMs: Long = 2600L,
        onFrame: (Frame) -> Unit = {},
    ): CaptureResult = withContext(Dispatchers.Default) {
        ensureInitialized()
        val localClassifier = requireNotNull(classifier)
        val localTensorAudio = requireNotNull(tensorAudio)
        val localRecorder = requireNotNull(recorder)

        val windowMs = ((localClassifier.requiredInputBufferSize.toFloat() /
            localClassifier.requiredTensorAudioFormat.sampleRate.toFloat()) * 1000f).toLong()
            .coerceAtLeast(100L)
        val intervalMs = (windowMs / 2L).coerceIn(80L, 500L)

        var maxGoScore = 0f
        var bestTopLabel = ""
        var bestTopScore = 0f
        var peakRms = 0f
        var framesSeen = 0
        val startMs = SystemClock.elapsedRealtime()

        try {
            localRecorder.startRecording()
            while (SystemClock.elapsedRealtime() - startMs < durationMs) {
                localTensorAudio.load(localRecorder)
                val categories = localClassifier.classify(localTensorAudio)
                    .firstOrNull()?.categories.orEmpty()
                val frame = toFrame(categories, estimateRms(localTensorAudio))
                onFrame(frame)
                framesSeen += 1
                maxGoScore = maxOf(maxGoScore, frame.goScore)
                if (frame.topScore >= bestTopScore) {
                    bestTopScore = frame.topScore
                    bestTopLabel = frame.topLabel
                }
                peakRms = maxOf(peakRms, frame.rms ?: 0f)
                delay(intervalMs)
            }
        } finally {
            try {
                localRecorder.stop()
            } catch (_: Throwable) {
            }
        }

        CaptureResult(
            maxGoScore = maxGoScore,
            bestTopLabel = bestTopLabel,
            bestTopScore = bestTopScore,
            peakRms = peakRms,
            framesSeen = framesSeen,
        )
    }

    fun close() {
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

    private fun ensureInitialized() {
        if (classifier != null && tensorAudio != null && recorder != null) return
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(4)
            .build()
        val localClassifier = AudioClassifier.createFromFileAndOptions(context, "speech_commands.tflite", options)
        classifier = localClassifier
        tensorAudio = localClassifier.createInputTensorAudio()
        recorder = localClassifier.createAudioRecord()
    }

    private fun toFrame(categories: List<Category>, rms: Float?): Frame {
        val normalized = categories.map { it.label.trim().lowercase() to it.score }
        val goScore = normalized.firstOrNull { it.first == "go" }?.second ?: 0f
        val top = normalized.maxByOrNull { it.second }
        return Frame(
            goScore = goScore,
            topLabel = top?.first ?: "",
            topScore = top?.second ?: 0f,
            rms = rms,
        )
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
}
