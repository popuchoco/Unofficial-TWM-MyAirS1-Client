package com.kerberosclaw.myairs1

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

data class UiState(
    val phase: String = "尚未掃描", val deviceName: String? = null, val address: String? = null,
    val scanning: Boolean = false, val connected: Boolean = false, val busy: Boolean = false,
    val backgroundEnabled: Boolean = false, val latest: Measurement? = null,
    val latestSession: SessionSummary? = null, val logs: List<String> = emptyList()
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val db: AppDatabase) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("ble_connection", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable
    private val handler = Handler(context.mainLooper)
    private val opQueue = ArrayDeque<() -> Unit>()
    private val sessionSamples = mutableListOf<Measurement>()
    private var gatt: BluetoothGatt? = null
    private var operationRunning = false
    private var autoReconnect = false
    private var reconnectAttempt = 0
    private val finishMeasurement = Runnable { finishSession() }
    private val reconnect = Runnable { if (autoReconnect && !mutable.value.connected) connectPreferredOrScan() }
    private val scanTimeout: Runnable = Runnable {
        if (mutable.value.scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            mutable.value = mutable.value.copy(phase = "掃描逾時，等待自動重試", scanning = false)
            log("BLE 掃描 15 秒未找到裝置")
            scheduleReconnect()
        }
    }

    private fun log(message: String) {
        db.addEvent("ble", message)
        mutable.value = mutable.value.copy(logs = (listOf("${java.time.LocalTime.now().withNano(0)}  $message") + mutable.value.logs).take(100))
    }

    fun startAutoReconnect() { autoReconnect = true; mutable.value = mutable.value.copy(backgroundEnabled = true); connectPreferredOrScan() }
    fun stopAutoReconnect() { autoReconnect = false; handler.removeCallbacks(reconnect); mutable.value = mutable.value.copy(backgroundEnabled = false) }
    fun scan() { reconnectAttempt = 0; scanInternal() }

    private fun connectPreferredOrScan() {
        if (mutable.value.connected || mutable.value.scanning) return
        val address = prefs.getString("preferred_address", null)
        val device = address?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }
        if (device != null) { log("嘗試重新連線已綁定裝置"); connect(device) } else scanInternal()
    }

    private fun scanInternal() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            mutable.value = mutable.value.copy(phase = "手機藍牙不可用或尚未開啟", scanning = false)
            scheduleReconnect(); return
        }
        if (mutable.value.scanning) return
        handler.removeCallbacks(scanTimeout)
        mutable.value = mutable.value.copy(phase = "正在掃描 myAir S1…", scanning = true)
        log("開始 BLE 掃描")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed(scanTimeout, 15_000)
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
            val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            if (!name.contains("myair", true) && !name.contains("S1", true) && S1Protocol.MEASUREMENT_SERVICE !in services) return
            adapter?.bluetoothLeScanner?.stopScan(this); handler.removeCallbacks(scanTimeout)
            prefs.edit().putString("preferred_address", result.device.address).apply()
            mutable.value = mutable.value.copy(scanning = false, deviceName = name.ifBlank { "myAir S1" }, address = result.device.address)
            log("找到 ${mutable.value.deviceName}，RSSI ${result.rssi} dBm"); connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(scanTimeout)
            mutable.value = mutable.value.copy(phase = "掃描失敗（$errorCode）", scanning = false)
            log("BLE 掃描失敗：$errorCode"); scheduleReconnect()
        }
    }

    private fun connect(device: BluetoothDevice) {
        handler.removeCallbacks(reconnect)
        mutable.value = mutable.value.copy(phase = "正在連線…", address = device.address)
        gatt?.close(); gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0; prefs.edit().putString("preferred_address", g.device.address).apply()
                mutable.value = mutable.value.copy(phase = "已連線，正在讀取服務…", connected = true, address = g.device.address)
                log("GATT 已連線（status=$status），開始讀取服務"); g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finishSession(); mutable.value = mutable.value.copy(phase = "已斷線，等待重新連線", connected = false, busy = false)
                operationRunning = false; opQueue.clear(); log("GATT 已斷線（status=$status）"); g.close()
                if (gatt === g) gatt = null
                scheduleReconnect()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(S1Protocol.MEASUREMENT_SERVICE)
            log("服務探索 status=$status；共 ${g.services.size} 個服務")
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) { mutable.value = mutable.value.copy(phase = "找不到 myAir S1 量測服務"); g.disconnect(); return }
            mutable.value = mutable.value.copy(phase = "已連線，可開始量測")
            enableNotify(g, service.getCharacteristic(S1Protocol.SENSOR_MEASUREMENT)); enableNotify(g, service.getCharacteristic(S1Protocol.CONTROL_POINT))
        }
        @Deprecated("Deprecated in API 33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = receive(c.uuid, c.value)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receive(c.uuid, value)
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { log("通知設定 ${descriptor.characteristic.uuid} status=$status"); operationDone() }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { log("寫入 ${characteristic.uuid} status=$status"); operationDone() }
    }

    private fun receive(uuid: java.util.UUID, value: ByteArray) {
        val raw = with(S1Protocol) { value.hexString() }; log("notify $uuid (${value.size} bytes): $raw")
        if (uuid != S1Protocol.SENSOR_MEASUREMENT || value.size < 18) return
        runCatching { S1Protocol.parse(value) }.onSuccess { measurement ->
            db.addMeasurement(measurement); sessionSamples += measurement
            mutable.value = mutable.value.copy(phase = "正在接收量測串流…", latest = measurement, busy = true)
            handler.removeCallbacks(finishMeasurement); handler.postDelayed(finishMeasurement, 2_500)
        }.onFailure { error -> mutable.value = mutable.value.copy(phase = "封包解析失敗：${error.message}", busy = false) }
    }

    private fun finishSession() {
        handler.removeCallbacks(finishMeasurement)
        val samples = sessionSamples.toList(); sessionSamples.clear()
        if (samples.isEmpty()) return
        val summary = db.completeSession(samples) ?: return
        mutable.value = mutable.value.copy(phase = "量測完成", busy = false, latest = summary.latest, latestSession = summary)
        log("量測串流完成：${summary.sampleCount} 筆，平均 PM2.5 ${"%.1f".format(summary.averagePm25)} µg/m³")
        OutboxScheduler.enqueue(context)
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        handler.removeCallbacks(reconnect)
        val delay = minOf(5 * 60_000L, 3_000L * (1L shl minOf(reconnectAttempt, 6))); reconnectAttempt++
        mutable.value = mutable.value.copy(phase = "已斷線，${delay / 1000} 秒後重試"); handler.postDelayed(reconnect, delay)
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null) { log("通知 characteristic 不存在"); return }
        enqueue {
            g.setCharacteristicNotification(c, true); val d = c.getDescriptor(S1Protocol.CCCD)
            if (d == null) { log("通知 CCCD 不存在"); operationDone() }
            else if (android.os.Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }
    }

    fun measure() {
        val g = gatt ?: run { log("尚未連線"); return }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT) ?: run { log("Control point 不存在"); return }
        finishSession(); mutable.value = mutable.value.copy(phase = "量測中…", busy = true)
        enqueue { write(g, c, S1Protocol.MEASURE_COMMAND, "量測") }
    }
    fun syncTime() {
        val g = gatt ?: run { log("尚未連線"); return }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT) ?: run { log("Control point 不存在"); return }
        mutable.value = mutable.value.copy(phase = "正在同步裝置時間…")
        enqueue { write(g, c, S1Protocol.timeSyncCommand(), "時間同步") }
    }
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, label: String) {
        val ok = if (android.os.Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { @Suppress("DEPRECATION") c.value = bytes; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        if (ok) log("已送出${label}命令") else { log("$label 命令無法排入 GATT"); operationDone() }
    }
    private fun enqueue(op: () -> Unit) { opQueue.add(op); runNext() }
    private fun runNext() { if (!operationRunning && opQueue.isNotEmpty()) { operationRunning = true; opQueue.removeFirst().invoke() } }
    private fun operationDone() { operationRunning = false; runNext() }
    fun disconnect() { stopAutoReconnect(); handler.removeCallbacks(scanTimeout); handler.removeCallbacks(finishMeasurement); adapter?.bluetoothLeScanner?.stopScan(scanCallback); gatt?.disconnect() }
}
