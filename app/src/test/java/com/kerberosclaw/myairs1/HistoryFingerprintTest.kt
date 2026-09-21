package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HistoryFingerprintTest {
    @Test fun sameDeviceEventIsStableButDifferentDeviceIsDistinct() {
        val measurement = Measurement(
            sequence = "0100", deviceEpochSeconds = 1_700_000_000, timestampUtc = null,
            deviceTimeSynchronized = true, timezoneOffset = "+08:00", protocolVersion = 1,
            triggerCode = 2, trigger = "AUTO", batteryPercent = 80, pm25 = 12,
            coverClosed = true, temperatureC = 25.0, humidityPercent = 60.0,
            receivedAt = 1_700_000_000_000, rawHex = "01020304"
        )
        val first = HistoryFingerprint.create("device-a", measurement)
        assertEquals(first, HistoryFingerprint.create("device-a", measurement))
        assertNotEquals(first, HistoryFingerprint.create("device-b", measurement))
    }
}
