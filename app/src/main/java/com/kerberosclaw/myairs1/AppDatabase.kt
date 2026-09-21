package com.kerberosclaw.myairs1

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

data class SessionSummary(
    val eventId: String,
    val startedAt: Long,
    val endedAt: Long,
    val sampleCount: Int,
    val latest: Measurement,
    val averagePm25: Double,
    val averageTemperatureC: Double,
    val averageHumidityPercent: Double,
    val origin: MeasurementOrigin = MeasurementOrigin.MANUAL,
    val requestId: String? = null
)

data class OutboxItem(val id: Long, val payload: String, val attempts: Int)

data class ReportPoint(
    val startedAt: Long,
    val endedAt: Long,
    val sampleCount: Int,
    val latestPm25: Int,
    val batteryPercent: Int,
    val averagePm25: Double,
    val averageTemperatureC: Double,
    val averageHumidityPercent: Double
)

object SessionCalculator {
    fun summarize(
        samples: List<Measurement>,
        eventId: String = UUID.randomUUID().toString(),
        origin: MeasurementOrigin = MeasurementOrigin.MANUAL,
        requestId: String? = null
    ): SessionSummary? {
        if (samples.isEmpty()) return null
        val ordered = samples.sortedBy { it.receivedAt }
        val latest = ordered.last()
        return SessionSummary(
            eventId = eventId,
            startedAt = ordered.first().receivedAt,
            endedAt = latest.receivedAt,
            sampleCount = ordered.size,
            latest = latest,
            averagePm25 = ordered.map { it.pm25 }.average(),
            averageTemperatureC = ordered.map { it.temperatureC }.average(),
            averageHumidityPercent = ordered.map { it.humidityPercent }.average(),
            origin = origin,
            requestId = requestId
        )
    }
}

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "myair-s1.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE measurements(id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL, device_epoch INTEGER, pm25 INTEGER, temperature REAL, humidity REAL, battery INTEGER, trigger TEXT, raw_hex TEXT NOT NULL, event_fingerprint TEXT UNIQUE)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL)")
        createV2(db)
        createV3(db)
        createV4(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) createV5(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS measurement_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, sample_count INTEGER NOT NULL, latest_pm25 INTEGER NOT NULL, latest_temperature REAL NOT NULL, latest_humidity REAL NOT NULL, latest_battery INTEGER NOT NULL, average_pm25 REAL NOT NULL, average_temperature REAL NOT NULL, average_humidity REAL NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, delivered_at INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_measurements_received_at ON measurements(received_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(delivered_at,next_attempt_at)")
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE measurement_sessions ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual'")
        db.execSQL("ALTER TABLE measurement_sessions ADD COLUMN request_id TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_request_id ON measurement_sessions(request_id) WHERE request_id IS NOT NULL")
    }

    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_schedule(slot INTEGER PRIMARY KEY CHECK(slot=1), schedule_id TEXT UNIQUE NOT NULL, mode TEXT NOT NULL, interval_minutes INTEGER NOT NULL, local_minute_of_day INTEGER, next_run_at INTEGER NOT NULL, grace_minutes INTEGER NOT NULL DEFAULT 10, updated_at INTEGER NOT NULL)")
    }

    /** Removes the legacy raw_hex-only uniqueness rule and adds an opaque device event key. */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE measurements RENAME TO measurements_legacy")
        db.execSQL("CREATE TABLE measurements(id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL, device_epoch INTEGER, pm25 INTEGER, temperature REAL, humidity REAL, battery INTEGER, trigger TEXT, raw_hex TEXT NOT NULL, event_fingerprint TEXT UNIQUE)")
        db.execSQL("INSERT INTO measurements(id,received_at,device_epoch,pm25,temperature,humidity,battery,trigger,raw_hex) SELECT id,received_at,device_epoch,pm25,temperature,humidity,battery,trigger,raw_hex FROM measurements_legacy")
        db.execSQL("DROP TABLE measurements_legacy")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_measurements_received_at ON measurements(received_at)")
    }

    @Synchronized fun localSchedule(): LocalSchedule? = readableDatabase.rawQuery(
        "SELECT schedule_id,mode,interval_minutes,next_run_at,local_minute_of_day,grace_minutes FROM local_schedule WHERE slot=1", null
    ).use { c ->
        if (!c.moveToFirst()) null else LocalSchedule(
            id = c.getString(0), mode = LocalScheduleMode.valueOf(c.getString(1)), intervalMinutes = c.getInt(2),
            nextRunAt = c.getLong(3), localMinuteOfDay = if (c.isNull(4)) null else c.getInt(4), graceMinutes = c.getInt(5)
        )
    }

    @Synchronized fun saveOnceSchedule(delayMinutes: Int, graceMinutes: Int = 10): LocalSchedule {
        require(delayMinutes >= 1)
        return saveSchedule(LocalSchedule(UUID.randomUUID().toString(), LocalScheduleMode.ONCE, delayMinutes,
            System.currentTimeMillis() + delayMinutes * 60_000L, graceMinutes = graceMinutes))
    }

    @Synchronized fun saveIntervalSchedule(intervalMinutes: Int, graceMinutes: Int = 10): LocalSchedule {
        require(intervalMinutes >= 1)
        return saveSchedule(LocalSchedule(UUID.randomUUID().toString(), LocalScheduleMode.INTERVAL, intervalMinutes,
            System.currentTimeMillis() + intervalMinutes * 60_000L, graceMinutes = graceMinutes))
    }

    @Synchronized fun saveDailySchedule(localMinuteOfDay: Int, graceMinutes: Int = 10): LocalSchedule = saveSchedule(
        LocalSchedule(UUID.randomUUID().toString(), LocalScheduleMode.DAILY, 24 * 60,
            ScheduleTimes.nextDaily(localMinuteOfDay, System.currentTimeMillis()), localMinuteOfDay, graceMinutes)
    )

    private fun saveSchedule(schedule: LocalSchedule): LocalSchedule {
        writableDatabase.insertWithOnConflict("local_schedule", null, ContentValues().apply {
            put("slot", 1); put("schedule_id", schedule.id); put("mode", schedule.mode.name)
            put("interval_minutes", schedule.intervalMinutes); schedule.localMinuteOfDay?.let { put("local_minute_of_day", it) }
            put("next_run_at", schedule.nextRunAt); put("grace_minutes", schedule.graceMinutes); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return schedule
    }

    @Synchronized fun cancelSchedule() { writableDatabase.delete("local_schedule", "slot=1", null) }

    /** Advances only after a due task was accepted; a full queue therefore never overwrites it. */
    @Synchronized fun markScheduleDispatched(schedule: LocalSchedule, dispatchedAt: Long = System.currentTimeMillis()) {
        val current = localSchedule() ?: return
        if (current.id != schedule.id || current.nextRunAt != schedule.nextRunAt) return
        when (current.mode) {
            LocalScheduleMode.ONCE -> cancelSchedule()
            LocalScheduleMode.INTERVAL -> {
                var next = current.nextRunAt + current.intervalMinutes * 60_000L
                while (next <= dispatchedAt) next += current.intervalMinutes * 60_000L
                writableDatabase.update("local_schedule", ContentValues().apply { put("next_run_at", next); put("updated_at", dispatchedAt) }, "slot=1", null)
            }
            LocalScheduleMode.DAILY -> {
                val next = ScheduleTimes.nextDaily(requireNotNull(current.localMinuteOfDay), dispatchedAt)
                writableDatabase.update("local_schedule", ContentValues().apply { put("next_run_at", next); put("updated_at", dispatchedAt) }, "slot=1", null)
            }
        }
    }

    @Synchronized fun markScheduleMissed(schedule: LocalSchedule, reason: String, now: Long = System.currentTimeMillis()) {
        addEvent("schedule_missed", "${schedule.mode.name}:${schedule.nextRunAt}:$reason")
        markScheduleDispatched(schedule, now)
    }

    fun addMeasurement(m: Measurement, eventFingerprint: String? = null): Long = writableDatabase.insertWithOnConflict("measurements", null, ContentValues().apply {
        put("received_at", m.receivedAt); put("device_epoch", m.deviceEpochSeconds); put("pm25", m.pm25)
        put("temperature", m.temperatureC); put("humidity", m.humidityPercent); put("battery", m.batteryPercent)
        put("trigger", m.trigger); put("raw_hex", m.rawHex); eventFingerprint?.let { put("event_fingerprint", it) }
    }, SQLiteDatabase.CONFLICT_IGNORE)

    fun addEvent(kind: String, detail: String) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("occurred_at", System.currentTimeMillis()); put("kind", kind); put("detail", detail.take(2000))
        })
    }

    fun completeSession(
        samples: List<Measurement>,
        origin: MeasurementOrigin = MeasurementOrigin.MANUAL,
        requestId: String? = null
    ): SessionSummary? {
        val summary = SessionCalculator.summarize(samples, origin = origin, requestId = requestId) ?: return null
        return persistSession(summary)
    }

    /** Imports one device-stored observation exactly once without pretending it is a live session. */
    fun importHistoryMeasurement(measurement: Measurement, deviceIdHash: String): Boolean {
        val fingerprint = HistoryFingerprint.create(deviceIdHash, measurement)
        if (insertMeasurement(writableDatabase, measurement, fingerprint) == -1L) return false
        pruneOlderThan30Days()
        return true
    }

    private fun persistSession(summary: SessionSummary): SessionSummary {
        val latest = summary.latest
        val payload = summary.toJson().toString()
        writableDatabase.beginTransaction()
        try {
            insertSessionAndOutbox(writableDatabase, summary, payload)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        pruneOlderThan30Days()
        return summary
    }

    private fun insertMeasurement(database: SQLiteDatabase, m: Measurement, eventFingerprint: String?): Long =
        database.insertWithOnConflict("measurements", null, ContentValues().apply {
            put("received_at", m.receivedAt); put("device_epoch", m.deviceEpochSeconds); put("pm25", m.pm25)
            put("temperature", m.temperatureC); put("humidity", m.humidityPercent); put("battery", m.batteryPercent)
            put("trigger", m.trigger); put("raw_hex", m.rawHex); eventFingerprint?.let { put("event_fingerprint", it) }
        }, SQLiteDatabase.CONFLICT_IGNORE)

    private fun insertSessionAndOutbox(database: SQLiteDatabase, summary: SessionSummary, payload: String = summary.toJson().toString()) {
        val latest = summary.latest
        database.insertOrThrow("measurement_sessions", null, ContentValues().apply {
                put("event_id", summary.eventId); put("started_at", summary.startedAt); put("ended_at", summary.endedAt)
                put("sample_count", summary.sampleCount); put("latest_pm25", latest.pm25)
                put("latest_temperature", latest.temperatureC); put("latest_humidity", latest.humidityPercent)
                put("latest_battery", latest.batteryPercent); put("average_pm25", summary.averagePm25)
                put("average_temperature", summary.averageTemperatureC); put("average_humidity", summary.averageHumidityPercent)
                put("origin", summary.origin.wireName); summary.requestId?.let { put("request_id", it) }
            })
        database.insertOrThrow("outbox", null, ContentValues().apply {
                put("event_id", summary.eventId); put("payload", payload); put("created_at", System.currentTimeMillis())
            })
    }

    fun nextOutbox(now: Long = System.currentTimeMillis()): OutboxItem? = readableDatabase.rawQuery(
        "SELECT id,payload,attempts FROM outbox WHERE delivered_at IS NULL AND next_attempt_at<=? ORDER BY id LIMIT 1",
        arrayOf(now.toString())
    ).use { c -> if (c.moveToFirst()) OutboxItem(c.getLong(0), c.getString(1), c.getInt(2)) else null }

    fun markDelivered(id: Long) {
        writableDatabase.update("outbox", ContentValues().apply { put("delivered_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
    }

    fun markFailed(id: Long, previousAttempts: Int) {
        val attempts = previousAttempts + 1
        val delay = minOf(6 * 60 * 60 * 1000L, 30_000L * (1L shl minOf(attempts - 1, 9)))
        writableDatabase.update("outbox", ContentValues().apply {
            put("attempts", attempts); put("next_attempt_at", System.currentTimeMillis() + delay)
        }, "id=?", arrayOf(id.toString()))
    }

    fun pendingOutboxCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM outbox WHERE delivered_at IS NULL", null)
        .use { c -> c.moveToFirst(); c.getInt(0) }

    fun reportPoints(startInclusive: Long, endExclusive: Long): List<ReportPoint> = readableDatabase.rawQuery(
        "SELECT started_at,ended_at,sample_count,latest_pm25,latest_battery,average_pm25,average_temperature,average_humidity FROM measurement_sessions WHERE ended_at>=? AND ended_at<? ORDER BY ended_at",
        arrayOf(startInclusive.toString(), endExclusive.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ReportPoint(
                startedAt = cursor.getLong(0), endedAt = cursor.getLong(1), sampleCount = cursor.getInt(2),
                latestPm25 = cursor.getInt(3), batteryPercent = cursor.getInt(4), averagePm25 = cursor.getDouble(5),
                averageTemperatureC = cursor.getDouble(6), averageHumidityPercent = cursor.getDouble(7)
            ))
        }
    }

    fun latestReportPoint(): ReportPoint? = readableDatabase.rawQuery(
        "SELECT started_at,ended_at,sample_count,latest_pm25,latest_battery,average_pm25,average_temperature,average_humidity FROM measurement_sessions ORDER BY ended_at DESC LIMIT 1", null
    ).use { c -> if (!c.moveToFirst()) null else ReportPoint(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getDouble(5), c.getDouble(6), c.getDouble(7)) }

    fun pruneOlderThan30Days(now: Long = System.currentTimeMillis()) {
        val cutoff = now - 30L * 24 * 60 * 60 * 1000
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("measurements", "received_at<?", arrayOf(cutoff.toString()))
            writableDatabase.delete("events", "occurred_at<?", arrayOf(cutoff.toString()))
            writableDatabase.delete("measurement_sessions", "ended_at<?", arrayOf(cutoff.toString()))
            writableDatabase.delete("outbox", "created_at<?", arrayOf(cutoff.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun exportJson(): String {
        val out = StringBuilder("{\"schema_version\":2,\"measurements\":[")
        readableDatabase.rawQuery("SELECT received_at,device_epoch,pm25,temperature,humidity,battery,trigger,raw_hex FROM measurements ORDER BY id", null).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) out.append(','); first = false
                out.append(JSONObject().apply {
                    put("received_at", c.getLong(0)); put("device_epoch_seconds", c.getLong(1)); put("pm25_ug_m3", c.getInt(2))
                    put("temperature_c", c.getDouble(3)); put("humidity_pct", c.getDouble(4)); put("battery_pct", c.getInt(5))
                    put("trigger", c.getString(6)); put("raw_hex", c.getString(7))
                })
            }
        }
        out.append("],\"events\":[")
        readableDatabase.rawQuery("SELECT occurred_at,kind,detail FROM events ORDER BY id", null).use { c ->
            var first = true
            while (c.moveToNext()) {
                if (!first) out.append(','); first = false
                out.append(JSONObject().apply { put("occurred_at", c.getLong(0)); put("kind", c.getString(1)); put("detail", c.getString(2)) })
            }
        }
        return out.append("]}").toString()
    }
}

object HistoryFingerprint {
    fun create(deviceIdHash: String, measurement: Measurement): String {
        val contentChecksum = sha256(measurement.rawHex)
        return sha256(listOf(deviceIdHash, measurement.sequence, measurement.deviceEpochSeconds, measurement.triggerCode, contentChecksum).joinToString("|"))
    }

    fun opaqueDeviceId(address: String?): String = sha256(address?.uppercase() ?: "unbound-device")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun SessionSummary.toJson(): JSONObject = JSONObject().apply {
    put("schema_version", 1); put("event_id", eventId); put("device_id", "myair-s1")
    put("started_at", startedAt); put("ended_at", endedAt); put("sample_count", sampleCount)
    put("origin", origin.wireName); requestId?.let { put("request_id", it) }
    put("latest", JSONObject().apply {
        put("received_at", latest.receivedAt); put("timestamp_utc", latest.timestampUtc); put("pm25_ug_m3", latest.pm25)
        put("temperature_c", latest.temperatureC); put("humidity_pct", latest.humidityPercent)
        put("battery_pct", latest.batteryPercent); put("trigger", latest.trigger)
    })
    put("average", JSONObject().apply {
        put("pm25_ug_m3", averagePm25); put("temperature_c", averageTemperatureC); put("humidity_pct", averageHumidityPercent)
    })
}
