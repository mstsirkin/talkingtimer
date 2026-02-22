package com.vibe.talkingtimer.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimerEngineTest {
    @Test
    fun basicCountUpEmitsCadenceEvents() {
        val engine = TimerEngine()
        engine.startNow(0L, TimerConfig(cadence = AnnouncementCadence.EVERY_10S))

        val noEvent = engine.tick(nowRealtimeMs = 9_900L, nowWallClockMs = 9_900L)
        assertTrue(noEvent.none { it is TimerEvent.PeriodicCallout })

        val event = engine.tick(nowRealtimeMs = 10_000L, nowWallClockMs = 10_000L)
        assertContains(event, TimerEvent.PeriodicCallout(10_000L))
    }

    @Test
    fun negativeCountdownEmits321Go() {
        val engine = TimerEngine()
        engine.startNow(0L, TimerConfig(startOffsetMs = -5_000L, cadence = AnnouncementCadence.EVERY_1M))

        val events = mutableListOf<TimerEvent>()
        events += engine.tick(2_100L, 2_100L)
        events += engine.tick(3_100L, 3_100L)
        events += engine.tick(4_100L, 4_100L)
        events += engine.tick(5_100L, 5_100L)

        assertContains(events, TimerEvent.Countdown(3))
        assertContains(events, TimerEvent.Countdown(2))
        assertContains(events, TimerEvent.Countdown(1))
        assertContains(events, TimerEvent.Go)
    }

    @Test
    fun scheduledStartTransitionsWhenTargetReached() {
        val engine = TimerEngine()
        engine.scheduleAt(
            nowRealtimeMs = 1_000L,
            nowWallClockMs = 1_000L,
            targetWallClockMs = 5_000L,
            cfg = TimerConfig(),
        )

        assertEquals(TimerMode.WAITING_FOR_SCHEDULE, engine.snapshot(2_000L, 2_000L).mode)
        val events = engine.tick(nowRealtimeMs = 6_000L, nowWallClockMs = 5_100L)
        assertContains(events, TimerEvent.Started(StartSource.SCHEDULED))
        assertEquals(TimerMode.RUNNING, engine.snapshot(6_000L, 5_100L).mode)
    }

    @Test
    fun largeTickEmitsAllBoundariesCrossed() {
        val engine = TimerEngine()
        engine.startNow(0L, TimerConfig(cadence = AnnouncementCadence.EVERY_10S))
        val events = engine.tick(31_000L, 31_000L)

        val callouts = events.filterIsInstance<TimerEvent.PeriodicCallout>().map { it.elapsedMs }
        assertEquals(listOf(10_000L, 20_000L, 30_000L), callouts)
    }
}
