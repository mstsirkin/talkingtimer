package com.vibe.talkingtimer.core

import kotlin.math.max

class TimerEngine {
    private var mode: TimerMode = TimerMode.IDLE
    private var config: TimerConfig = TimerConfig()
    private var scheduledStartWallClockMs: Long? = null
    private var runningAnchorRealtimeMs: Long = 0L
    private var elapsedAtRunStartMs: Long = 0L
    private var lastTickElapsedMs: Long? = null
    private var stoppedElapsedMs: Long = 0L

    fun snapshot(nowRealtimeMs: Long, nowWallClockMs: Long): TimerSnapshot {
        val elapsed = when (mode) {
            TimerMode.RUNNING -> currentElapsedMs(nowRealtimeMs)
            TimerMode.WAITING_FOR_SCHEDULE -> config.startOffsetMs
            TimerMode.IDLE -> stoppedElapsedMs
        }
        return TimerSnapshot(
            mode = mode,
            elapsedMs = elapsed,
            cadence = config.cadence,
            scheduledStartWallClockMs = if (mode == TimerMode.WAITING_FOR_SCHEDULE) scheduledStartWallClockMs else null,
            startOffsetMs = config.startOffsetMs,
        )
    }

    fun startNow(nowRealtimeMs: Long, cfg: TimerConfig, source: StartSource = StartSource.MANUAL): List<TimerEvent> {
        config = cfg
        scheduledStartWallClockMs = null
        mode = TimerMode.RUNNING
        runningAnchorRealtimeMs = nowRealtimeMs
        elapsedAtRunStartMs = cfg.startOffsetMs
        lastTickElapsedMs = cfg.startOffsetMs - 1
        return listOf(TimerEvent.Started(source))
    }

    fun scheduleAt(
        nowRealtimeMs: Long,
        nowWallClockMs: Long,
        targetWallClockMs: Long,
        cfg: TimerConfig,
    ): List<TimerEvent> {
        return if (targetWallClockMs <= nowWallClockMs) {
            startNow(nowRealtimeMs, cfg, StartSource.SCHEDULED)
        } else {
            config = cfg
            mode = TimerMode.WAITING_FOR_SCHEDULE
            scheduledStartWallClockMs = targetWallClockMs
            lastTickElapsedMs = null
            emptyList()
        }
    }

    fun stop(nowRealtimeMs: Long = 0L): List<TimerEvent> {
        val wasRunning = mode == TimerMode.RUNNING || mode == TimerMode.WAITING_FOR_SCHEDULE
        stoppedElapsedMs = if (mode == TimerMode.RUNNING && nowRealtimeMs > 0L) {
            currentElapsedMs(nowRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        mode = TimerMode.IDLE
        scheduledStartWallClockMs = null
        lastTickElapsedMs = null
        return listOf(TimerEvent.Stopped(wasRunning))
    }

    fun tick(nowRealtimeMs: Long, nowWallClockMs: Long): List<TimerEvent> {
        val events = mutableListOf<TimerEvent>()
        if (mode == TimerMode.WAITING_FOR_SCHEDULE) {
            val target = scheduledStartWallClockMs
            if (target != null && nowWallClockMs >= target) {
                mode = TimerMode.RUNNING
                runningAnchorRealtimeMs = nowRealtimeMs
                elapsedAtRunStartMs = config.startOffsetMs
                lastTickElapsedMs = config.startOffsetMs
                scheduledStartWallClockMs = null
                events += TimerEvent.Started(StartSource.SCHEDULED)
            } else {
                return events
            }
        }
        if (mode != TimerMode.RUNNING) {
            return events
        }

        val previous = lastTickElapsedMs ?: currentElapsedMs(nowRealtimeMs)
        val current = currentElapsedMs(nowRealtimeMs)
        if (current == previous) {
            return events
        }

        emitCountdownEvents(previous, current, events)
        emitCadenceCallouts(previous, current, events)
        lastTickElapsedMs = current
        return events
    }

    private fun currentElapsedMs(nowRealtimeMs: Long): Long {
        return elapsedAtRunStartMs + (nowRealtimeMs - runningAnchorRealtimeMs)
    }

    private fun emitCountdownEvents(previousMs: Long, currentMs: Long, sink: MutableList<TimerEvent>) {
        if (currentMs <= previousMs) {
            return
        }
        val thresholds = listOf(
            -5_000L to TimerEvent.Countdown(5),
            -4_000L to TimerEvent.Countdown(4),
            -3_000L to TimerEvent.Countdown(3),
            -2_000L to TimerEvent.Countdown(2),
            -1_000L to TimerEvent.Countdown(1),
            0L to TimerEvent.Go,
        )
        for ((threshold, event) in thresholds) {
            if (previousMs < threshold && currentMs >= threshold) {
                sink += event
            }
        }
    }

    private fun emitCadenceCallouts(previousMs: Long, currentMs: Long, sink: MutableList<TimerEvent>) {
        val interval = config.cadence.intervalMs
        if (interval <= 0L || currentMs <= 0L) {
            return
        }
        val start = max(1L, previousMs + 1L)
        val firstBoundary = ceilDiv(start, interval) * interval
        var boundary = firstBoundary
        while (boundary in 1..currentMs) {
            sink += TimerEvent.PeriodicCallout(boundary)
            boundary += interval
        }
    }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        return if (value <= 0L) 0L else (value + divisor - 1L) / divisor
    }
}
