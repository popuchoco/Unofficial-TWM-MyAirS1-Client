package com.kerberosclaw.myairs1

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

data class UiState(
    val phase: String = "尚未掃描",
    val deviceName: String? = null,
    val address: String? = null,
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val latest: Measurement? = null,
    val logs: List<String> = emptyList()
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val db: AppDatabase) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable
    private var gatt: BluetoothGatt? = null
    private val opQueue = ArrayDeque<() -> Unit>()
    private var operationRunning = false
    private val handler = android.os.Handler(context.mainLooper)
    private val finishMeasurement = Runnable {
        mutable.value = mutable.value.copy(phase = "量測完成", busy = false)
        log("量測串流結束")
    }

    private fun log(message: String) {
        db.addEvent("ble", message)
        mutable.value = mutable.value.copy(logs = (listOf("${java.time.LocalTime.now().withNano(0)}  $message") + mutable.value.logs).take(80))
    }

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { log("手機藍牙不可用或尚未開啟"); return }
        mutable.value = mutable.value.copy(phase = "正在掃描 myAir S1…", scanning = true)
        log("開始 BLE 掃描")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Some S1 firmware revisions do not include the 128-bit service UUID in
        // every advertisement, so filtering in Android would hide the device.
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({
            if (mutable.value.scanning) {
                scanner.stopScan(scanCallback)
                mutable.value = mutable.value.copy(phase = "掃描逾時，請靠近裝置後重試", scanning = false)
                log("掃描 15 秒未找到帶有量測 Service UUID 的裝置")
            }
        }, 15_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
            val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            if (!name.contains("myair", ignoreCase = true) &&
                !name.contains("S1", ignoreCase = true) &&
                S1Protocol.MEASUREMENT_SERVICE !in services) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            mutable.value = mutable.value.copy(scanning = false, deviceName = name.ifBlank { "myAir S1" }, address = result.device.address)
            log("找到 ${mutable.value.deviceName}，RSSI ${result.rssi} dBm")
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            mutable.value = mutable.value.copy(phase = "掃描失敗（$errorCode）", scanning = false)
            log("BLE 掃描失敗：$errorCode")
        }
    }

    private fun connect(device: BluetoothDevice) {
        mutable.value = mutable.value.copy(phase = "正在連線…")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mutable.value = mutable.value.copy(phase = "已連線，正在讀取服務…", connected = true)
                log("GATT 已連線（status=$status），開始 discoverServices")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mutable.value = mutable.value.copy(phase = "已斷線（status=$status）", connected = false, busy = false)
                operationRunning = false; opQueue.clear()
                log("GATT 已斷線（status=$status）")
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(S1Protocol.MEASUREMENT_SERVICE)
            val listed = g.services.joinToString { it.uuid.toString() }
            log("服務探索 status=$status；services=$listed")
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                mutable.value = mutable.value.copy(phase = "找不到 myAir S1 量測服務")
                return
            }
            mutable.value = mutable.value.copy(phase = "已連線，可開始量測")
            enableNotify(g, service.getCharacteristic(S1Protocol.SENSOR_MEASUREMENT))
            enableNotify(g, service.getCharacteristic(S1Protocol.CONTROL_POINT))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = receive(c.uuid, c.value)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receive(c.uuid, value)

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("通知設定 ${descriptor.characteristic.uuid} status=$status")
            operationDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("寫入 ${characteristic.uuid} status=$status")
            operationDone()
        }
    }

    private fun receive(uuid: java.util.UUID, value: ByteArray) {
        val raw = with(S1Protocol) { value.hexString() }
        log("notify $uuid (${value.size} bytes): $raw")
        if (uuid == S1Protocol.SENSOR_MEASUREMENT && value.size >= 18) {
            runCatching { S1Protocol.parse(value) }
                .onSuccess { m ->
                    db.addMeasurement(m)
                    mutable.value = mutable.value.copy(phase = "正在接收量測串流…", latest = m)
                    handler.removeCallbacks(finishMeasurement)
                    handler.postDelayed(finishMeasurement, 2_500)
                }
                .onFailure { e -> mutable.value = mutable.value.copy(phase = "封包解析失敗：${e.message}", busy = false) }
        }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null) { log("Sensor characteristic 不存在"); return }
        enqueue {
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(S1Protocol.CCCD)
            if (d == null) { log("Sensor CCCD 不存在"); operationDone() }
            else if (android.os.Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }
    }

    fun measure() {
        val g = gatt ?: run { log("尚未連線"); return }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT)
            ?: run { log("Control point 不存在"); return }
        mutable.value = mutable.value.copy(phase = "量測中，約需 32 秒…", busy = true)
        enqueue {
            val ok = if (android.os.Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(c, S1Protocol.MEASURE_COMMAND, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value = S1Protocol.MEASURE_COMMAND; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
            if (!ok) { log("量測命令無法排入 GATT"); mutable.value = mutable.value.copy(busy = false); operationDone() }
            else log("已送出量測命令 001503000000011001")
        }
    }

    fun syncTime() {
        val g = gatt ?: run { log("尚未連線"); return }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT)
            ?: run { log("Control point 不存在"); return }
        val command = S1Protocol.timeSyncCommand()
        mutable.value = mutable.value.copy(phase = "正在同步裝置時間…")
        enqueue {
            val ok = if (android.os.Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(c, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value = command; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
            if (!ok) { log("時間同步命令無法排入 GATT"); operationDone() }
            else log("已送出時間同步命令：${with(S1Protocol) { command.hexString() }}")
        }
    }

    private fun enqueue(op: () -> Unit) { opQueue.add(op); runNext() }
    private fun runNext() { if (!operationRunning && opQueue.isNotEmpty()) { operationRunning = true; opQueue.removeFirst().invoke() } }
    private fun operationDone() { operationRunning = false; runNext() }

    fun disconnect() { handler.removeCallbacks(finishMeasurement); adapter?.bluetoothLeScanner?.stopScan(scanCallback); gatt?.disconnect() }
}
