package com.vibe.talkingtimer.wear

import android.content.Context

data class KeywordCalibration(
    val goScores: List<Float> = emptyList(),
) {
    val recordingCount: Int get() = goScores.size
    val usesDefaultThreshold: Boolean get() = goScores.isEmpty()

    val effectiveGoThreshold: Float
        get() = if (goScores.isEmpty()) {
            KeywordCalibrationStore.DEFAULT_GO_THRESHOLD
        } else {
            KeywordCalibrationStore.computeGoThreshold(goScores)
        }

    val effectiveStrongGoThreshold: Float
        get() = if (goScores.isEmpty()) {
            KeywordCalibrationStore.DEFAULT_STRONG_GO_THRESHOLD
        } else {
            (effectiveGoThreshold + 0.15f).coerceIn(effectiveGoThreshold, 0.95f)
        }

    val averageGoScore: Float?
        get() = goScores.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    val bestGoScore: Float?
        get() = goScores.maxOrNull()
}

object KeywordCalibrationStore {
    const val DEFAULT_GO_THRESHOLD = 0.55f
    const val DEFAULT_STRONG_GO_THRESHOLD = 0.75f

    private const val PREFS_NAME = "keyword_calibration"
    private const val KEY_GO_SCORES = "go_scores_csv"

    fun load(context: Context): KeywordCalibration {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_GO_SCORES, "").orEmpty().trim()
        if (raw.isEmpty()) return KeywordCalibration()
        val scores = raw.split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .map { it.coerceIn(0f, 1f) }
        return KeywordCalibration(scores)
    }

    fun addGoRecording(context: Context, goScore: Float): KeywordCalibration {
        val current = load(context)
        val updated = current.goScores + goScore.coerceIn(0f, 1f)
        save(context, KeywordCalibration(updated))
        return KeywordCalibration(updated)
    }

    fun reset(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_GO_SCORES).apply()
    }

    fun removeLast(context: Context): KeywordCalibration {
        val current = load(context)
        if (current.goScores.isEmpty()) return current
        val updated = current.goScores.dropLast(1)
        if (updated.isEmpty()) {
            reset(context)
            return KeywordCalibration()
        }
        val calibration = KeywordCalibration(updated)
        save(context, calibration)
        return calibration
    }

    private fun save(context: Context, calibration: KeywordCalibration) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val csv = calibration.goScores.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
        prefs.edit().putString(KEY_GO_SCORES, csv).apply()
    }

    internal fun computeGoThreshold(scores: List<Float>): Float {
        if (scores.isEmpty()) return DEFAULT_GO_THRESHOLD
        val sorted = scores.sorted()
        val lowerIndex = ((sorted.size - 1) * 0.25f).toInt().coerceIn(0, sorted.lastIndex)
        val anchor = sorted[lowerIndex]
        val margin = if (sorted.size <= 1) 0.12f else 0.08f
        return (anchor - margin).coerceIn(0.20f, DEFAULT_GO_THRESHOLD)
    }
}
