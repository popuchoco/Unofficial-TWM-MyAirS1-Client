package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDiscoveryPolicyTest {
    @Test fun noCandidateKeepsScanning() {
        assertEquals(DeviceDiscoveryAction.KEEP_SCANNING, DeviceDiscoveryPolicy.action(0))
    }

    @Test fun oneCandidateConnectsAutomatically() {
        assertEquals(DeviceDiscoveryAction.AUTO_CONNECT, DeviceDiscoveryPolicy.action(1))
    }

    @Test fun multipleCandidatesRequireUserSelection() {
        assertEquals(DeviceDiscoveryAction.SHOW_SELECTION, DeviceDiscoveryPolicy.action(2))
        assertEquals(DeviceDiscoveryAction.SHOW_SELECTION, DeviceDiscoveryPolicy.action(5))
    }
}
