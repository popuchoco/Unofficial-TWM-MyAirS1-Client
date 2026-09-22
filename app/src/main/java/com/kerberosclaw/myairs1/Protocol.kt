package com.kerberosclaw.myairs1

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

object S1Protocol {
    val MEASUREMENT_SERVICE: UUID = UUID.fromString("46494854-4443-5365-7276-696365030000")
    val CONTROL_POINT: UUID = UUID.fromString("46494854-4443-5365-7276-696365030001")
    val SENSOR_MEASUREMENT: UUID = UUID.fromString("46494854-4443-5365-7276-696365030002")
    val SYNC_MEASUREMENT: UUID = UUID.fromString("46494854-4443-5365-7276-696365030003")
    val DEVICE_INFORMATION_SERVICE: UUID = UUID.fromString("46494854-4443-5365-7276-696365010000")
    val VERSION_CHARACTERISTIC: UUID = UUID.fromString("46494854-4443-5365-7276-696365010001")
    val STANDARD_DEVICE_INFORMATION_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val STANDARD_FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val MEASURE_COMMAND: ByteArray = hex("001503000000011001")
    val HISTORY_SYNC_START_COMMAND: ByteArray = hex("0021020000000101")
    const val MAX_HISTORY_RECORDS = 1_000

    data class HistoryBatch(val declaredBytes: Int, val checksumStatus: Int, val records: List<ByteArray>)

    /** Reassembles the device history transport. It intentionally does not clear device data. */
    fun parseHistoryPackets(packets: List<ByteArray>): HistoryBatch {
        require(packets.isNotEmpty()) { "沒有歷史同步封包" }
        val first = packets.first()
        require(first.size >= 8) { "第一個歷史封包不足 8 bytes" }
        val declaredBytes = ByteBuffer.wrap(first, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(declaredBytes >= 0 && declaredBytes % 18 == 0 && declaredBytes <= MAX_HISTORY_RECORDS * 18) { "歷史資料長度不合法：$declaredBytes" }
        val payload = buildList<Byte> {
            packets.forEachIndexed { index, packet ->
                val header = if (index == 0) 8 else 3
                if (packet.size > header) addAll(packet.copyOfRange(header, packet.size).toList())
            }
        }.take(declaredBytes).toByteArray()
        require(payload.size == declaredBytes) { "歷史資料不完整：${payload.size}/$declaredBytes bytes" }
        val last = packets.last()
        val checksum = if (last.size > 4) {
            ((last[last.lastIndex - 1].toInt() and 0xff) shl 8) or (last.last().toInt() and 0xff)
        } else {
            require(packets.size >= 2 && packets[packets.lastIndex - 1].isNotEmpty() && last.isNotEmpty()) { "歷史 checksum 不完整" }
            ((packets[packets.lastIndex - 1].last().toInt() and 0xff) shl 8) or (last.last().toInt() and 0xff)
        }
        return HistoryBatch(declaredBytes, checksum, payload.asList().chunked(18).map { it.toByteArray() })
    }

    fun validatedHistoryPackets(packets: List<ByteArray>): Result<HistoryBatch> = runCatching {
        parseHistoryPackets(packets).also { require(it.checksumStatus == 0) { "checksum status=${it.checksumStatus}" } }
    }

    fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun ByteArray.hexString(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    /** Builds the device time-sync frame from UTC time and the local offset. */
    fun timeSyncCommand(now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): ByteArray {
        val utc = now.atZone(ZoneId.of("UTC"))
        val local = now.atZone(zoneId)
        val offsetMinutes = local.offset.totalSeconds / 60
        val signNibble = if (offsetMinutes < 0) 1 else 0
        val absMinutes = kotlin.math.abs(offsetMinutes)
        val timezone = byteArrayOf(((signNibble shl 4) or (absMinutes / 60)).toByte(), (absMinutes % 60).toByte())
        val year = utc.year
        return byteArrayOf(0x00, 0x10, 0x0B, 0x00, 0x00, 0x00, 0x01,
            (year and 0xff).toByte(), ((year shr 8) and 0xff).toByte(),
            utc.monthValue.toByte(), utc.dayOfMonth.toByte(), utc.hour.toByte(), utc.minute.toByte(), utc.second.toByte(),
            local.dayOfWeek.value.toByte()) + timezone
    }

    fun parse(packet: ByteArray, receivedAt: Long = System.currentTimeMillis()): Measurement {
        require(packet.size >= 18) { "量測封包不足 18 bytes：${packet.size}" }
        fun u16(offset: Int) = ByteBuffer.wrap(packet, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val epoch = ByteBuffer.wrap(packet, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val tzHex = packet.copyOfRange(6, 8).hexString()
        val sign = if (tzHex.startsWith("1")) "-" else "+"
        val hour = tzHex.substring(1, 2).toInt(16)
        val minute = tzHex.substring(2, 4).toInt(16)
        val triggerCode = packet[9].toInt() and 0xff
        val deviceTimeTrusted = kotlin.math.abs(receivedAt / 1000L - epoch) <= 7L * 24 * 60 * 60
        return Measurement(
            sequence = packet.copyOfRange(0, 2).hexString(),
            deviceEpochSeconds = epoch,
            timestampUtc = if (deviceTimeTrusted) runCatching { Instant.ofEpochSecond(epoch).toString() }.getOrNull() else null,
            deviceTimeSynchronized = deviceTimeTrusted,
            timezoneOffset = "%s%02d:%02d".format(sign, hour, minute),
            protocolVersion = packet[8].toInt() and 0xff,
            triggerCode = triggerCode,
            trigger = mapOf(1 to "USER", 2 to "AUTO", 3 to "APP")[triggerCode] ?: "UNKNOWN",
            batteryPercent = packet[10].toInt() and 0xff,
            pm25 = u16(11),
            coverClosed = packet[13].toInt() == 1,
            temperatureC = u16(14) / 10.0,
            humidityPercent = u16(16) / 10.0,
            receivedAt = receivedAt,
            rawHex = packet.copyOfRange(0, 18).hexString()
        )
    }

    fun parseDeviceVersion(value: ByteArray): DeviceVersion {
        require(value.size >= 14) { "版本資料不足 14 bytes：${value.size}" }
        fun dotted(bytes: ByteArray): String = bytes.hexString().let { "${it.first()}.${it.drop(1)}" }
        val hardwareRaw = value.copyOfRange(12, 14).joinToString("") { "%X".format(it.toInt() and 0xff) }
        val stage = when (hardwareRaw.firstOrNull()) { '1' -> "EVT"; '2' -> "DVT"; '3' -> "PVT"; else -> null }
        return DeviceVersion(
            protocolVersion = dotted(value.copyOfRange(0, 2)),
            modelName = value.copyOfRange(2, 10).toString(Charsets.UTF_8).trim('\u0000', ' '),
            firmwareVersion = dotted(value.copyOfRange(10, 12)),
            hardwareVersion = listOfNotNull(stage, hardwareRaw.getOrNull(1)?.toString()).joinToString(" ").ifBlank { hardwareRaw }
        )
    }
}

data class DeviceVersion(val protocolVersion: String, val modelName: String, val firmwareVersion: String, val hardwareVersion: String)

data class Measurement(
    val sequence: String,
    val deviceEpochSeconds: Long,
    val timestampUtc: String?,
    val deviceTimeSynchronized: Boolean,
    val timezoneOffset: String,
    val protocolVersion: Int,
    val triggerCode: Int,
    val trigger: String,
    val batteryPercent: Int,
    val pm25: Int,
    val coverClosed: Boolean,
    val temperatureC: Double,
    val humidityPercent: Double,
    val receivedAt: Long,
    val rawHex: String
)

enum class HistoryTimeoutAction { RETRY, FAIL }

object HistoryRetryPolicy {
    const val MAX_ATTEMPTS = 3
    fun onNoProgress(attempt: Int, connected: Boolean): HistoryTimeoutAction =
        if (connected && attempt < MAX_ATTEMPTS) HistoryTimeoutAction.RETRY else HistoryTimeoutAction.FAIL

    fun expectedPackets(received: Int, remaining: Int?): Int? = remaining?.let { received + it }
}
