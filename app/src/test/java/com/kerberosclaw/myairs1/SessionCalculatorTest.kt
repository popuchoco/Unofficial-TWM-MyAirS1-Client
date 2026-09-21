package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCalculatorTest {
    @Test fun emptySessionHasNoSummary() {
        assertNull(SessionCalculator.summarize(emptyList()))
    }

    @Test fun summarySortsSamplesAndUsesWholeInterval() {
        val samples = listOf(
            measurement(at = 3_000, pm25 = 30, temperature = 27.0, humidity = 63.0),
            measurement(at = 1_000, pm25 = 10, temperature = 25.0, humidity = 61.0),
            measurement(at = 2_000, pm25 = 20, temperature = 26.0, humidity = 62.0)
        )
        val result = SessionCalculator.summarize(samples, "test-event")!!
        assertEquals("test-event", result.eventId)
        assertEquals(1_000, result.startedAt)
        assertEquals(3_000, result.endedAt)
        assertEquals(3, result.sampleCount)
        assertEquals(30, result.latest.pm25)
        assertEquals(20.0, result.averagePm25, 0.0001)
        assertEquals(26.0, result.averageTemperatureC, 0.0001)
        assertEquals(62.0, result.averageHumidityPercent, 0.0001)
    }

    private fun measurement(at: Long, pm25: Int, temperature: Double, humidity: Double) = Measurement(
        sequence = "0000", deviceEpochSeconds = 0, timestampUtc = null,
        deviceTimeSynchronized = false, timezoneOffset = "+00:00", protocolVersion = 3,
        triggerCode = 3, trigger = "APP", batteryPercent = 90, pm25 = pm25,
        coverClosed = false, temperatureC = temperature, humidityPercent = humidity,
        receivedAt = at, rawHex = "${at}-${pm25}"
    )
}
