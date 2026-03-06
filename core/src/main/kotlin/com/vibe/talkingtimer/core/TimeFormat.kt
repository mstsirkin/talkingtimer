package com.vibe.talkingtimer.core

import kotlin.math.abs

object TimeFormat {
    fun mmssWithSign(elapsedMs: Long): String {
        // Ceil during countdown so display matches the spoken number
        val totalSeconds = if (elapsedMs < 0) (abs(elapsedMs) + 999L) / 1000L else abs(elapsedMs) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val sign = if (elapsedMs < 0) "-" else ""
        return if (hours > 0) {
            "%s%02d:%02d:%02d".format(sign, hours, minutes, seconds)
        } else {
            "%s%02d:%02d".format(sign, minutes, seconds)
        }
    }
}
