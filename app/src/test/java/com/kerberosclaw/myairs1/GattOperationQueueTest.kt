package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test

class GattOperationQueueTest {
    @Test fun resetDiscardsRetiredConnectionWorkAndAllowsNewConnectionImmediately() {
        val queue = GattOperationQueue()
        val started = mutableListOf<String>()

        queue.enqueue { started += "old-running" }
        queue.enqueue { started += "old-pending" }
        assertEquals(listOf("old-running"), started)

        queue.reset()
        queue.enqueue { started += "new-running" }

        assertEquals(listOf("old-running", "new-running"), started)
        queue.complete()
        assertEquals(listOf("old-running", "new-running"), started)
    }
}
