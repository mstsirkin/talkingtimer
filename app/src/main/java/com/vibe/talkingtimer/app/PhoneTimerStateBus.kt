package com.vibe.talkingtimer.app

import com.vibe.talkingtimer.core.AnnouncementCadence
import com.vibe.talkingtimer.core.TimeFormat
import com.vibe.talkingtimer.core.TimerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhoneTimerState(
    val mode: TimerMode = TimerMode.IDLE,
    val elapsedMs: Long = 0L,
    val cadence: AnnouncementCadence = AnnouncementCadence.EVERY_30S,
    val startOffsetMs: Long = 0L,
    val scheduledStartWallClockMs: Long? = null,
    val listening: Boolean = false,
    val speechAvailable: Boolean = true,
    val statusMessage: String = "Idle",
) {
    val timeLabel: String get() = TimeFormat.mmssWithSign(elapsedMs)
    val isActive: Boolean get() = mode != TimerMode.IDLE || listening
}

object PhoneTimerStateBus {
    private val mutableState = MutableStateFlow(PhoneTimerState())
    val state: StateFlow<PhoneTimerState> = mutableState.asStateFlow()

    fun publish(state: PhoneTimerState) {
        mutableState.value = state
    }
}
