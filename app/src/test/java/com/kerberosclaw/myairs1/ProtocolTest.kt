package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProtocolTest {
    @Test fun parsesKnownLayout() {
        val packet = byteArrayOf(
            0x12, 0x34, 0x00, 0xF1.toByte(), 0x53, 0x65, 0x08, 0x00,
            0x01, 0x03, 0x58, 0x23, 0x01, 0x00, 0xFA.toByte(), 0x00, 0x2B, 0x02
        )
        val m = S1Protocol.parse(packet, 0)
        assertEquals(291, m.pm25)
        assertEquals(25.0, m.temperatureC, 0.001)
        assertEquals(55.5, m.humidityPercent, 0.001)
        assertEquals(88, m.batteryPercent)
        assertEquals("APP", m.trigger)
        assertEquals(false, m.deviceTimeSynchronized)
    }

    @Test fun createsOfficialTimeSyncLayout() {
        val command = S1Protocol.timeSyncCommand(
            Instant.parse("2026-09-21T02:03:04Z"), ZoneId.of("Asia/Taipei")
        )
        assertEquals("00100B00000001EA070915020304010800", with(S1Protocol) { command.hexString() })
    }
}
