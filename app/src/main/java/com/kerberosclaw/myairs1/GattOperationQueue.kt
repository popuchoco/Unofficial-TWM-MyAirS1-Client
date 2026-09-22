package com.kerberosclaw.myairs1

import java.util.ArrayDeque

/** Serializes Android GATT operations and can discard a retired connection's pending work. */
internal class GattOperationQueue {
    private val pending = ArrayDeque<() -> Unit>()
    private var running = false

    @Synchronized
    fun enqueue(operation: () -> Unit) {
        pending.add(operation)
        runNext()
    }

    @Synchronized
    fun complete() {
        running = false
        runNext()
    }

    @Synchronized
    fun reset() {
        pending.clear()
        running = false
    }

    private fun runNext() {
        if (!running && pending.isNotEmpty()) {
            running = true
            pending.removeFirst().invoke()
        }
    }
}
