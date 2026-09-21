package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceVersionTest {
    @Test fun parsesCustomDeviceInformationLayout() {
        val value = byteArrayOf(0x01, 0x23) + "MYAIRS1".padEnd(8, '\u0000').toByteArray() + byteArrayOf(0x04, 0x56, 0x03, 0x02)
        val result = S1Protocol.parseDeviceVersion(value)
        assertEquals("0.123", result.protocolVersion)
        assertEquals("MYAIRS1", result.modelName)
        assertEquals("0.456", result.firmwareVersion)
        assertEquals("PVT 2", result.hardwareVersion)
    }
}
