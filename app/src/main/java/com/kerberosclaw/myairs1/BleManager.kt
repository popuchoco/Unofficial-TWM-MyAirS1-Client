package com.kerberosclaw.myairs1

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.Executors

private const val HISTORY_PROGRESS_TIMEOUT_MS = 10_000L
private const val HISTORY_RETRY_SETTLE_MS = 1_500L
private const val DEVICE_DISCOVERY_WINDOW_MS = 3_000L

data class BleDeviceCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val isPreferred: Boolean
)

data class UiState(
    val phase: String = "尚未掃描", val deviceName: String? = null, val address: String? = null,
    val scanning: Boolean = false, val connecting: Boolean = false, val connected: Boolean = false, val busy: Boolean = false,
    val backgroundEnabled: Boolean = false, val latest: Measurement? = null,
    val latestSession: SessionSummary? = null, val firmwareVersion: String? = null,
    val deviceModel: String? = null, val hardwareVersion: String? = null, val deviceProtocolVersion: String? = null,
    val historySyncing: Boolean = false, val historyStatus: String? = null,
    val deviceCandidates: List<BleDeviceCandidate> = emptyList(),
    val preferredDeviceName: String? = null,
    val logs: List<String> = emptyList()
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val db: AppDatabase) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("ble_connection", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable
    private val handler = Handler(context.mainLooper)
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val opQueue = ArrayDeque<() -> Unit>()
    private val sessionSamples = mutableListOf<Measurement>()
    private val sessionLock = Any()
    private var gatt: BluetoothGatt? = null
    private var operationRunning = false
    @Volatile private var autoReconnect = false
    private var reconnectAttempt = 0
    private var activeRequest: MeasurementRequest? = null
    private var measurementFinished: (() -> Unit)? = null
    private var timeSyncInProgress = false
    private val historyPackets = mutableListOf<ByteArray>()
    private var historyPacketsRemaining: Int? = null
    private var historyAttempt = 0
    private var awaitingHistoryAck = false
    private var historyRetrySettling = false
    private val scanCandidates = linkedMapOf<String, BleDeviceCandidate>()
    private var deviceSelectionInProgress = false
    private var queueReadyCallback: (() -> Unit)? = null
    private val finishMeasurement = Runnable { finishSession() }
    private val measurementTimeout = Runnable {
        if (activeRequest != null) {
            log("${activeRequest?.label ?: "量測"}逾時，派發下一項任務")
            synchronized(sessionLock) { sessionSamples.clear() }
            mutable.value = mutable.value.copy(phase = "量測逾時", busy = false)
            finishActiveRequest()
        }
    }
    private val historyTimeout = Runnable {
        if (mutable.value.historySyncing) {
            val expected = HistoryRetryPolicy.expectedPackets(historyPackets.size, historyPacketsRemaining)
            val progress = expected?.let { "${historyPackets.size}/$it 個封包" } ?: "尚未收到封包數 ACK"
            if (HistoryRetryPolicy.onNoProgress(historyAttempt, mutable.value.connected) == HistoryTimeoutAction.RETRY) {
                log("歷史同步無進度（$progress），準備第 ${historyAttempt + 1}/${HistoryRetryPolicy.MAX_ATTEMPTS} 次唯讀重試")
                prepareHistoryRetry()
            } else {
                failHistorySync("$progress，已達 ${HistoryRetryPolicy.MAX_ATTEMPTS} 次嘗試")
            }
        }
    }
    private val historyRetrySettle = Runnable { if (mutable.value.historySyncing) startHistoryAttempt() }
    private val discoveryWindow = Runnable { finishCandidateDiscovery() }
    private val reconnect = Runnable { if (autoReconnect && !mutable.value.connected) connectPreferredOrScan() }
    private val connectionTimeout = Runnable {
        if (mutable.value.connecting) {
            log("GATT 連線逾時")
            val timedOutGatt = gatt
            gatt = null
            mutable.value = mutable.value.copy(connecting = false, connected = false)
            timedOutGatt?.disconnect()
            timedOutGatt?.close()
            scheduleReconnect()
        }
    }
    private val scanTimeout: Runnable = Runnable {
        if (mutable.value.scanning) {
            handler.removeCallbacks(discoveryWindow)
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            val phase = if (autoReconnect) "掃描逾時，等待自動重試" else "掃描逾時，請靠近裝置後重試"
            mutable.value = mutable.value.copy(phase = phase, scanning = false)
            log("BLE 掃描 15 秒未找到裝置")
            scheduleReconnect()
        }
    }

    private fun log(message: String) {
        dbExecutor.execute { db.addEvent("ble", message) }
        mutable.value = mutable.value.copy(logs = (listOf("${java.time.LocalTime.now().withNano(0)}  $message") + mutable.value.logs).take(100))
    }

    fun recordSkipped(request: MeasurementRequest, reason: String) {
        dbExecutor.execute { db.addEvent("measurement_skipped", "${request.origin.wireName}:${request.requestId}:$reason") }
        log("${request.label}未執行：$reason")
    }

    fun setQueueReadyCallback(callback: () -> Unit) { queueReadyCallback = callback }

    fun startAutoReconnect() { autoReconnect = true; mutable.value = mutable.value.copy(backgroundEnabled = true); connectPreferredOrScan() }
    fun stopAutoReconnect() { autoReconnect = false; handler.removeCallbacks(reconnect); mutable.value = mutable.value.copy(backgroundEnabled = false) }
    fun scan() {
        if (mutable.value.connected || mutable.value.connecting) { log("裝置已連線或連線中，略過重複掃描"); return }
        reconnectAttempt = 0
        scanInternal()
    }

    fun selectDevice(address: String) {
        val candidate = scanCandidates[address] ?: return
        deviceSelectionInProgress = false
        scanCandidates.clear()
        mutable.value = mutable.value.copy(deviceCandidates = emptyList())
        rememberPreferred(candidate)
        adapter?.getRemoteDevice(address)?.let(::connect)
    }

    fun dismissDeviceSelection() {
        deviceSelectionInProgress = false
        scanCandidates.clear()
        mutable.value = mutable.value.copy(deviceCandidates = emptyList(), phase = "已取消選擇裝置")
        scheduleReconnect()
    }

    fun changeDevice() {
        deviceSelectionInProgress = true
        handler.removeCallbacks(reconnect)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        mutable.value = mutable.value.copy(connected = false, connecting = false, phase = "請選擇其他裝置")
        handler.postDelayed({ scanInternal() }, 300)
    }

    fun forgetPreferredDevice() {
        deviceSelectionInProgress = false
        autoReconnect = false
        handler.removeCallbacks(reconnect)
        prefs.edit().remove("preferred_address").remove("preferred_name").apply()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        scanCandidates.clear()
        mutable.value = mutable.value.copy(
            connected = false, connecting = false, deviceName = null, address = null,
            preferredDeviceName = null, deviceCandidates = emptyList(), backgroundEnabled = false,
            phase = "已忘記綁定裝置"
        )
    }

    private fun connectPreferredOrScan() {
        if (mutable.value.connected || mutable.value.connecting || mutable.value.scanning) return
        val address = prefs.getString("preferred_address", null)
        val device = address?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }
        if (device != null) { log("嘗試重新連線已綁定裝置"); connect(device) } else scanInternal()
    }

    private fun scanInternal() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            mutable.value = mutable.value.copy(phase = "手機藍牙不可用或尚未開啟", scanning = false)
            scheduleReconnect(); return
        }
        if (mutable.value.connected || mutable.value.connecting || mutable.value.scanning) return
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(discoveryWindow)
        scanCandidates.clear()
        mutable.value = mutable.value.copy(
            phase = "正在掃描 myAir S1…", scanning = true, deviceCandidates = emptyList(),
            preferredDeviceName = prefs.getString("preferred_name", null)
        )
        log("開始 BLE 掃描")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed(scanTimeout, 15_000)
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
            val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            if (S1Protocol.MEASUREMENT_SERVICE !in services && !name.contains("myair", true)) return
            val displayName = name.ifBlank { "myAir S1" }
            val firstCandidate = scanCandidates.isEmpty()
            scanCandidates[result.device.address] = BleDeviceCandidate(
                address = result.device.address,
                name = displayName,
                rssi = result.rssi,
                isPreferred = result.device.address == prefs.getString("preferred_address", null)
            )
            mutable.value = mutable.value.copy(phase = "找到 ${scanCandidates.size} 台裝置，短暫確認附近裝置…")
            if (firstCandidate) handler.postDelayed(discoveryWindow, DEVICE_DISCOVERY_WINDOW_MS)
        }
        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(scanTimeout)
            mutable.value = mutable.value.copy(phase = "掃描失敗（$errorCode）", scanning = false)
            log("BLE 掃描失敗：$errorCode"); scheduleReconnect()
        }
    }

    private fun finishCandidateDiscovery() {
        if (!mutable.value.scanning || scanCandidates.isEmpty()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        handler.removeCallbacks(scanTimeout)
        val candidates = scanCandidates.values.sortedWith(
            compareByDescending<BleDeviceCandidate> { it.isPreferred }.thenByDescending { it.rssi }
        )
        if (DeviceDiscoveryPolicy.action(candidates.size) == DeviceDiscoveryAction.AUTO_CONNECT) {
            val candidate = candidates.single()
            deviceSelectionInProgress = false
            rememberPreferred(candidate)
            mutable.value = mutable.value.copy(scanning = false, deviceCandidates = emptyList())
            adapter?.getRemoteDevice(candidate.address)?.let(::connect)
        } else {
            mutable.value = mutable.value.copy(
                scanning = false,
                phase = "找到 ${candidates.size} 台 myAir S1，請選擇要連線的裝置",
                deviceCandidates = candidates
            )
        }
    }

    private fun rememberPreferred(candidate: BleDeviceCandidate) {
        prefs.edit().putString("preferred_address", candidate.address).putString("preferred_name", candidate.name).apply()
        mutable.value = mutable.value.copy(
            deviceName = candidate.name, address = candidate.address, preferredDeviceName = candidate.name
        )
        log("選擇 ${candidate.name}，RSSI ${candidate.rssi} dBm")
    }

    private fun connect(device: BluetoothDevice) {
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(connectionTimeout)
        mutable.value = mutable.value.copy(phase = "正在連線…", connecting = true, connected = false, address = device.address)
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectionTimeout, 20_000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) { g.close(); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0; prefs.edit().putString("preferred_address", g.device.address).apply()
                mutable.value = mutable.value.copy(phase = "已連線，正在讀取服務…", address = g.device.address)
                log("GATT 已連線（status=$status），開始讀取服務"); g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(connectionTimeout)
                handler.removeCallbacks(finishMeasurement); handler.removeCallbacks(measurementTimeout); handler.removeCallbacks(historyTimeout)
                handler.removeCallbacks(historyRetrySettle)
                synchronized(sessionLock) { sessionSamples.clear() }
                timeSyncInProgress = false
                awaitingHistoryAck = false
                historyRetrySettling = false
                mutable.value = mutable.value.copy(phase = "已斷線，等待重新連線", connecting = false, connected = false, busy = false,
                    historySyncing = false, historyStatus = if (mutable.value.historySyncing) "歷史同步因斷線中止；裝置端資料未清除" else mutable.value.historyStatus)
                activeRequest?.let { recordSkipped(it, "BLE 連線中斷") }
                operationRunning = false; opQueue.clear(); log("GATT 已斷線（status=$status）"); g.close()
                finishActiveRequest()
                if (gatt === g) gatt = null
                scheduleReconnect()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(S1Protocol.MEASUREMENT_SERVICE)
            log("服務探索 status=$status；共 ${g.services.size} 個服務")
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) { mutable.value = mutable.value.copy(phase = "找不到 myAir S1 量測服務"); g.disconnect(); return }
            handler.removeCallbacks(connectionTimeout)
            mutable.value = mutable.value.copy(phase = "已連線，可開始量測", connecting = false, connected = true)
            enableNotify(g, service.getCharacteristic(S1Protocol.SENSOR_MEASUREMENT)); enableNotify(g, service.getCharacteristic(S1Protocol.CONTROL_POINT))
            readFirmwareVersion(g)
            queueReadyCallback?.invoke()
        }
        @Deprecated("Deprecated in API 33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = receive(c.uuid, c.value)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receive(c.uuid, value)
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { log("通知設定 ${descriptor.characteristic.uuid} status=$status"); operationDone() }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("寫入 ${characteristic.uuid} status=$status")
            if (timeSyncInProgress && characteristic.uuid == S1Protocol.CONTROL_POINT) finishTimeSync(status == BluetoothGatt.GATT_SUCCESS)
            operationDone()
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            receiveRead(characteristic.uuid, characteristic.value, status)
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            receiveRead(characteristic.uuid, value, status)
    }

    private fun receive(uuid: java.util.UUID, value: ByteArray) {
        val raw = with(S1Protocol) { value.hexString() }; log("notify $uuid (${value.size} bytes): $raw")
        if (uuid == S1Protocol.CONTROL_POINT) {
            receiveControlPoint(raw)
            return
        }
        if (uuid == S1Protocol.SYNC_MEASUREMENT) {
            receiveHistoryPacket(value)
            return
        }
        if (uuid != S1Protocol.SENSOR_MEASUREMENT || value.size < 18) return
        runCatching { S1Protocol.parse(value) }.onSuccess { measurement ->
            dbExecutor.execute { db.addMeasurement(measurement) }
            synchronized(sessionLock) { sessionSamples += measurement }
            mutable.value = mutable.value.copy(phase = "正在接收量測串流…", latest = measurement, busy = true)
            handler.removeCallbacks(finishMeasurement); handler.postDelayed(finishMeasurement, 2_500)
        }.onFailure { error -> mutable.value = mutable.value.copy(phase = "封包解析失敗：${error.message}", busy = false) }
    }

    private fun receiveRead(uuid: java.util.UUID, value: ByteArray, status: Int) {
        if (uuid == S1Protocol.VERSION_CHARACTERISTIC && status == BluetoothGatt.GATT_SUCCESS) {
            runCatching { S1Protocol.parseDeviceVersion(value) }.onSuccess { version ->
                mutable.value = mutable.value.copy(firmwareVersion = version.firmwareVersion, deviceModel = version.modelName,
                    hardwareVersion = version.hardwareVersion, deviceProtocolVersion = version.protocolVersion)
                log("已讀取裝置版本資訊")
            }.onFailure { log("裝置版本資料無法解析：${it.message}") }
        } else if (uuid == S1Protocol.STANDARD_FIRMWARE_REVISION && status == BluetoothGatt.GATT_SUCCESS) {
            val version = value.toString(Charsets.UTF_8).trim().trim('\u0000').ifBlank { null }
            mutable.value = mutable.value.copy(firmwareVersion = version)
            log(if (version == null) "裝置未提供韌體版本" else "已讀取韌體版本")
        }
        operationDone()
    }

    private fun readFirmwareVersion(g: BluetoothGatt) {
        val characteristic = g.getService(S1Protocol.DEVICE_INFORMATION_SERVICE)?.getCharacteristic(S1Protocol.VERSION_CHARACTERISTIC)
            ?: g.getService(S1Protocol.STANDARD_DEVICE_INFORMATION_SERVICE)?.getCharacteristic(S1Protocol.STANDARD_FIRMWARE_REVISION)
        if (characteristic == null) { mutable.value = mutable.value.copy(firmwareVersion = null); log("裝置未提供標準韌體版本欄位"); return }
        enqueue {
            if (!g.readCharacteristic(characteristic)) { log("無法讀取韌體版本"); operationDone() }
        }
    }

    private fun finishSession() {
        handler.removeCallbacks(finishMeasurement)
        handler.removeCallbacks(measurementTimeout)
        val samples = synchronized(sessionLock) {
            sessionSamples.toList().also { sessionSamples.clear() }
        }
        if (samples.isEmpty()) return
        val request = activeRequest
        dbExecutor.execute {
            val result = runCatching { db.completeSession(samples, request?.origin ?: MeasurementOrigin.MANUAL, request?.requestId) }
            handler.post {
                result.onSuccess { summary ->
                    if (summary != null) {
                        mutable.value = mutable.value.copy(phase = "量測完成", busy = false, latest = summary.latest, latestSession = summary)
                        log("量測串流完成：${summary.sampleCount} 筆，平均 PM2.5 ${"%.1f".format(summary.averagePm25)} µg/m³")
                        OutboxScheduler.enqueue(context)
                    } else {
                        mutable.value = mutable.value.copy(phase = "量測沒有可保存的樣本", busy = false)
                    }
                }.onFailure { error ->
                    mutable.value = mutable.value.copy(phase = "量測保存失敗：${error.message}", busy = false)
                    log("量測保存失敗：${error.message}")
                }
                finishActiveRequest()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || deviceSelectionInProgress) return
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

    fun measure(request: MeasurementRequest, onFinished: () -> Unit): Boolean {
        if (!mutable.value.connected) return false
        val g = gatt ?: run { log("${request.label}等待失敗：尚未連線"); return false }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT) ?: run { log("Control point 不存在"); return false }
        if (activeRequest != null || mutable.value.busy) return false
        finishSession()
        activeRequest = request
        measurementFinished = onFinished
        mutable.value = mutable.value.copy(phase = "量測中…", busy = true)
        enqueue { write(g, c, S1Protocol.MEASURE_COMMAND, request.label) }
        handler.postDelayed(measurementTimeout, 60_000)
        return true
    }

    private fun finishActiveRequest() {
        activeRequest = null
        measurementFinished?.also { measurementFinished = null }?.invoke()
    }

    fun syncHistoryReadOnly(): Boolean {
        val g = gatt ?: run { log("尚未連線，無法同步裝置紀錄"); return false }
        if (mutable.value.busy || mutable.value.historySyncing) { log("目前有任務執行中，稍後再同步裝置紀錄"); return false }
        val service = g.getService(S1Protocol.MEASUREMENT_SERVICE)
        val sync = service?.getCharacteristic(S1Protocol.SYNC_MEASUREMENT) ?: run { log("裝置沒有歷史同步 characteristic"); return false }
        val control = service.getCharacteristic(S1Protocol.CONTROL_POINT) ?: run { log("Control point 不存在"); return false }
        historyPackets.clear(); historyPacketsRemaining = null
        historyAttempt = 0
        awaitingHistoryAck = false
        mutable.value = mutable.value.copy(historySyncing = true)
        enableNotify(g, sync)
        startHistoryAttempt(g, control)
        return true
    }

    private fun startHistoryAttempt(
        g: BluetoothGatt? = gatt,
        control: BluetoothGattCharacteristic? = g?.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT)
    ) {
        handler.removeCallbacks(historyTimeout)
        handler.removeCallbacks(historyRetrySettle)
        historyPackets.clear(); historyPacketsRemaining = null
        historyAttempt++
        historyRetrySettling = false
        awaitingHistoryAck = true
        val activeGatt = g
        if (activeGatt == null || control == null) {
            mutable.value = mutable.value.copy(historySyncing = false, historyStatus = "歷史同步中止：BLE 已斷線；裝置端資料未清除")
            return
        }
        mutable.value = mutable.value.copy(historyStatus = "正在要求裝置歷史紀錄（第 $historyAttempt/${HistoryRetryPolicy.MAX_ATTEMPTS} 次）…")
        enqueue { write(activeGatt, control, S1Protocol.HISTORY_SYNC_START_COMMAND, "唯讀歷史同步") }
        handler.postDelayed(historyTimeout, HISTORY_PROGRESS_TIMEOUT_MS)
    }

    private fun receiveControlPoint(raw: String) {
        if (historyRetrySettling && raw.length >= 20 && raw.substring(12, 14) == "21" && raw.substring(4, 6) == "04") {
            handler.removeCallbacks(historyRetrySettle)
            handler.postDelayed(historyRetrySettle, HISTORY_RETRY_SETTLE_MS)
            return
        }
        if (!mutable.value.historySyncing || !awaitingHistoryAck || raw.length < 20) return
        if (raw.substring(12, 14) != "21" || raw.substring(4, 6) != "04") return
        val countHex = raw.substring(16, 20)
        val count = countHex.substring(2, 4).plus(countHex.substring(0, 2)).toIntOrNull(16) ?: return
        awaitingHistoryAck = false
        historyPacketsRemaining = count
        mutable.value = mutable.value.copy(historyStatus = if (count == 0) "裝置沒有待同步紀錄" else "正在接收歷史資料（$count 個封包）")
        log("裝置歷史同步預計接收 $count 個封包")
        handler.removeCallbacks(historyTimeout); handler.postDelayed(historyTimeout, HISTORY_PROGRESS_TIMEOUT_MS)
        if (count == 0) finishHistorySync(emptyList())
    }

    private fun receiveHistoryPacket(value: ByteArray) {
        if (historyRetrySettling) {
            handler.removeCallbacks(historyRetrySettle)
            handler.postDelayed(historyRetrySettle, HISTORY_RETRY_SETTLE_MS)
            return
        }
        val remaining = historyPacketsRemaining ?: return
        if (!mutable.value.historySyncing || remaining <= 0) return
        if (!HistoryRetryPolicy.isExpectedSequence(historyPackets.size, value)) {
            log("歷史同步封包序號不連續；捨棄本次批次以避免混入前次嘗試")
            handler.removeCallbacks(historyTimeout)
            if (HistoryRetryPolicy.onNoProgress(historyAttempt, mutable.value.connected) == HistoryTimeoutAction.RETRY) prepareHistoryRetry()
            else failHistorySync("封包序號不連續")
            return
        }
        historyPackets += value.copyOf()
        historyPacketsRemaining = remaining - 1
        mutable.value = mutable.value.copy(historyStatus = "正在接收歷史資料（${historyPackets.size}/${historyPackets.size + remaining - 1}）")
        handler.removeCallbacks(historyTimeout); handler.postDelayed(historyTimeout, HISTORY_PROGRESS_TIMEOUT_MS)
        if (remaining == 1) finishHistorySync(historyPackets.toList())
    }

    private fun prepareHistoryRetry() {
        handler.removeCallbacks(historyTimeout)
        historyPackets.clear()
        historyPacketsRemaining = null
        awaitingHistoryAck = false
        historyRetrySettling = true
        mutable.value = mutable.value.copy(historyStatus = "正在排空前次通知，準備第 ${historyAttempt + 1}/${HistoryRetryPolicy.MAX_ATTEMPTS} 次嘗試")
        handler.removeCallbacks(historyRetrySettle)
        handler.postDelayed(historyRetrySettle, HISTORY_RETRY_SETTLE_MS)
    }

    private fun failHistorySync(reason: String) {
        handler.removeCallbacks(historyTimeout)
        handler.removeCallbacks(historyRetrySettle)
        historyPackets.clear()
        historyPacketsRemaining = null
        awaitingHistoryAck = false
        historyRetrySettling = false
        val status = "歷史同步失敗：$reason；裝置端資料未清除"
        mutable.value = mutable.value.copy(historySyncing = false, historyStatus = status)
        log(status)
    }

    private fun finishHistorySync(packets: List<ByteArray>) {
        handler.removeCallbacks(historyTimeout)
        handler.removeCallbacks(historyRetrySettle)
        awaitingHistoryAck = false
        historyRetrySettling = false
        if (packets.isEmpty()) {
            mutable.value = mutable.value.copy(historySyncing = false)
            return
        }
        val packetSnapshot = packets.map { it.copyOf() }
        val deviceIdHash = HistoryFingerprint.opaqueDeviceId(mutable.value.address)
        historyPackets.clear(); historyPacketsRemaining = null
        mutable.value = mutable.value.copy(historyStatus = "歷史封包接收完成，正在寫入本機資料…")
        dbExecutor.execute {
            val result = runCatching {
                val batch = S1Protocol.validatedHistoryPackets(packetSnapshot).getOrThrow()
                val measurements = batch.records.map { record ->
                    val epoch = java.nio.ByteBuffer.wrap(record, 2, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                    S1Protocol.parse(record, epoch * 1000L)
                }
                batch.records.size to db.importHistoryMeasurements(measurements, deviceIdHash)
            }
            handler.post {
                result.onSuccess { (total, imported) ->
                    val duplicates = total - imported
                    val status = "歷史同步完成：新增 $imported 筆、略過 $duplicates 筆重複資料；裝置端資料未清除"
                    mutable.value = mutable.value.copy(historySyncing = false, historyStatus = status)
                    log(status)
                }.onFailure { error ->
                    val status = "歷史同步失敗：${error.message}；裝置端資料未清除"
                    mutable.value = mutable.value.copy(historySyncing = false, historyStatus = status)
                    log(status)
                }
            }
        }
    }
    fun syncTime() {
        val g = gatt ?: run { log("尚未連線"); return }
        if (mutable.value.busy || mutable.value.historySyncing) { log("目前有任務執行中，略過時間同步"); return }
        val c = g.getService(S1Protocol.MEASUREMENT_SERVICE)?.getCharacteristic(S1Protocol.CONTROL_POINT) ?: run { log("Control point 不存在"); return }
        timeSyncInProgress = true
        mutable.value = mutable.value.copy(phase = "正在同步裝置時間…", busy = true)
        enqueue { write(g, c, S1Protocol.timeSyncCommand(), "時間同步") }
    }
    private fun finishTimeSync(success: Boolean) {
        timeSyncInProgress = false
        mutable.value = mutable.value.copy(phase = if (success) "裝置時間同步命令已送出" else "裝置時間同步失敗", busy = false)
        queueReadyCallback?.invoke()
    }
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, label: String) {
        val ok = if (android.os.Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { @Suppress("DEPRECATION") c.value = bytes; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        if (ok) log("已送出${label}命令") else {
            log("$label 命令無法排入 GATT")
            if (label == "時間同步" && timeSyncInProgress) finishTimeSync(false)
            operationDone()
        }
    }
    @Synchronized private fun enqueue(op: () -> Unit) { opQueue.add(op); runNext() }
    @Synchronized private fun runNext() { if (!operationRunning && opQueue.isNotEmpty()) { operationRunning = true; opQueue.removeFirst().invoke() } }
    @Synchronized private fun operationDone() { operationRunning = false; runNext() }
    fun disconnect() {
        stopAutoReconnect()
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(finishMeasurement)
        handler.removeCallbacks(measurementTimeout)
        handler.removeCallbacks(historyTimeout)
        handler.removeCallbacks(historyRetrySettle)
        handler.removeCallbacks(discoveryWindow)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
    }
}
