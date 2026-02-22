package com.vibe.talkingtimer.core

data class TimerConfig(
    val cadence: AnnouncementCadence = AnnouncementCadence.EVERY_30S,
    val startOffsetMs: Long = 0L,
)
