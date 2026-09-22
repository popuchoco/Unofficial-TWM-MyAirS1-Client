package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRetryPolicyTest {
    @Test fun incompleteBatchRetriesUntilThirdAttempt() {
        assertEquals(25, HistoryRetryPolicy.expectedPackets(received = 23, remaining = 2))
        assertEquals(HistoryTimeoutAction.RETRY, HistoryRetryPolicy.onNoProgress(attempt = 1, connected = true))
        assertEquals(HistoryTimeoutAction.RETRY, HistoryRetryPolicy.onNoProgress(attempt = 2, connected = true))
        assertEquals(HistoryTimeoutAction.FAIL, HistoryRetryPolicy.onNoProgress(attempt = 3, connected = true))
    }

    @Test fun disconnectedBatchDoesNotRetry() {
        assertEquals(HistoryTimeoutAction.FAIL, HistoryRetryPolicy.onNoProgress(attempt = 1, connected = false))
    }

    @Test fun packetSequenceMustRestartAndRemainContiguousForEachAttempt() {
        assertEquals(true, HistoryRetryPolicy.isExpectedSequence(0, byteArrayOf(0x00, 0x20)))
        assertEquals(true, HistoryRetryPolicy.isExpectedSequence(23, byteArrayOf(0x17, 0x20)))
        assertEquals(false, HistoryRetryPolicy.isExpectedSequence(0, byteArrayOf(0x17, 0x20)))
        assertEquals(false, HistoryRetryPolicy.isExpectedSequence(2, byteArrayOf(0x03, 0x20)))
        assertEquals(false, HistoryRetryPolicy.isExpectedSequence(0, byteArrayOf()))
    }
}
