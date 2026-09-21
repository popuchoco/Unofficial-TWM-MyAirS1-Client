package com.kerberosclaw.myairs1

import java.util.UUID
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MeasurementOrigin(val wireName: String) {
    MANUAL("manual"),
    LOCAL_TIMER("local_timer"),
    REMOTE_COMMAND("remote_command"),
    DEVICE_HISTORY("device_history")
}

data class MeasurementRequest(
    val origin: MeasurementOrigin,
    val requestId: String = UUID.randomUUID().toString(),
    val label: String = origin.defaultLabel,
    val expiresAt: Long? = null
)

private val MeasurementOrigin.defaultLabel: String get() = when (this) {
    MeasurementOrigin.MANUAL -> "手動量測"
    MeasurementOrigin.LOCAL_TIMER -> "本地定時量測"
    MeasurementOrigin.REMOTE_COMMAND -> "遠端量測"
    MeasurementOrigin.DEVICE_HISTORY -> "裝置歷史同步"
}

data class MeasurementQueueState(
    val current: MeasurementRequest? = null,
    val pending: List<MeasurementRequest> = emptyList()
)

/**
 * Serializes every App-initiated measurement through one entry point. Remote commands can be
 * added later without introducing a second BLE execution path.
 */
class MeasurementCoordinator(private val bleManager: BleManager) {
    private val queue = ArrayDeque<MeasurementRequest>()
    private val mutable = MutableStateFlow(MeasurementQueueState())
    val state: StateFlow<MeasurementQueueState> = mutable

    init { bleManager.setQueueReadyCallback(::resume) }

    @Synchronized fun request(request: MeasurementRequest): Boolean {
        if (mutable.value.current?.requestId == request.requestId || queue.any { it.requestId == request.requestId }) return true
        if (queue.size >= MAX_PENDING) return false
        queue.addLast(request)
        publish()
        dispatchNext()
        return true
    }

    @Synchronized private fun dispatchNext() {
        if (mutable.value.current != null || queue.isEmpty()) return
        val next = queue.removeFirst()
        if (next.expiresAt?.let { System.currentTimeMillis() > it } == true) {
            bleManager.recordSkipped(next, "超過允許執行時間")
            publish()
            dispatchNext()
            return
        }
        mutable.value = MeasurementQueueState(next, queue.toList())
        if (!bleManager.measure(next, ::onFinished)) {
            queue.addFirst(next)
            mutable.value = MeasurementQueueState(null, queue.toList())
        }
    }

    @Synchronized private fun onFinished() {
        mutable.value = MeasurementQueueState(null, queue.toList())
        dispatchNext()
    }

    @Synchronized private fun publish() {
        mutable.value = mutable.value.copy(pending = queue.toList())
    }

    fun isRunning(): Boolean = state.value.current != null

    @Synchronized fun resume() = dispatchNext()

    companion object { const val MAX_PENDING = 8 }
}
