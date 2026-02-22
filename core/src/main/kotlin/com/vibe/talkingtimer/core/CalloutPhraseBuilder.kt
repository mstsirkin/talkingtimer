package com.vibe.talkingtimer.core

object CalloutPhraseBuilder {
    fun buildClipTokens(elapsedMs: Long): List<String> {
        val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        val tokens = mutableListOf<String>()
        if (hours > 0) {
            tokens += numberToken(hours)
            tokens += if (hours == 1L) "hour" else "hours"
        }
        if (minutes > 0 || (hours > 0 && seconds > 0)) {
            tokens += numberToken(minutes)
            tokens += if (minutes == 1L) "minute" else "minutes"
        }
        if (seconds > 0 || tokens.isEmpty()) {
            tokens += numberToken(seconds)
            tokens += if (seconds == 1L) "second" else "seconds"
        }
        return tokens
    }

    private fun numberToken(value: Long): String {
        return when {
            value < 0 -> "n_0"
            value <= 99 -> "n_$value"
            else -> "n_99_plus"
        }
    }
}
