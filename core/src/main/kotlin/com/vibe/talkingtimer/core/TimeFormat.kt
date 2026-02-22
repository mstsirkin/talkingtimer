package com.vibe.talkingtimer.core

import kotlin.math.abs

object TimeFormat {
    fun mmssWithSign(elapsedMs: Long): String {
        val totalSeconds = abs(elapsedMs) / 1000L
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
