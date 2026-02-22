package com.vibe.talkingtimer.core

enum class AnnouncementCadence(val intervalMs: Long, val label: String) {
    EVERY_10S(10_000L, "10s"),
    EVERY_30S(30_000L, "30s"),
    EVERY_1M(60_000L, "1m"),
    EVERY_5M(5 * 60_000L, "5m"),
}
