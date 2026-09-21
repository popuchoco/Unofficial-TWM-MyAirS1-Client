package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleTimesTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test fun dailyScheduleUsesTodayWhenTimeIsStillAhead() {
        val now = Instant.parse("2026-09-21T01:00:00Z").toEpochMilli() // 09:00
        val next = ScheduleTimes.nextDaily(10 * 60, now, taipei)
        assertEquals(Instant.parse("2026-09-21T02:00:00Z").toEpochMilli(), next)
    }

    @Test fun dailyScheduleRollsToTomorrowAfterTimePassed() {
        val now = Instant.parse("2026-09-21T03:00:00Z").toEpochMilli() // 11:00
        val next = ScheduleTimes.nextDaily(10 * 60, now, taipei)
        assertEquals(Instant.parse("2026-09-22T02:00:00Z").toEpochMilli(), next)
    }
}
