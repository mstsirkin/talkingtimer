package com.vibe.talkingtimer.core

enum class StartSource {
    MANUAL,
    VOICE,
    SCHEDULED,
}

enum class TimerMode {
    IDLE,
    WAITING_FOR_SCHEDULE,
    RUNNING,
}

data class TimerSnapshot(
    val mode: TimerMode = TimerMode.IDLE,
    val elapsedMs: Long = 0L,
    val cadence: AnnouncementCadence = AnnouncementCadence.EVERY_30S,
    val scheduledStartWallClockMs: Long? = null,
    val startOffsetMs: Long = 0L,
)

sealed interface TimerEvent {
    data class Started(val source: StartSource) : TimerEvent
    data class Countdown(val value: Int) : TimerEvent
    data object Go : TimerEvent
    data class PeriodicCallout(val elapsedMs: Long) : TimerEvent
    data class Stopped(val wasRunning: Boolean) : TimerEvent
}
