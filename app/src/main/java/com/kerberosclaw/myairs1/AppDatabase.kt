package com.kerberosclaw.myairs1

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

data class SessionSummary(
    val eventId: String,
    val startedAt: Long,
    val endedAt: Long,
    val sampleCount: Int,
    val latest: Measurement,
    val averagePm25: Double,
    val averageTemperatureC: Double,
    val averageHumidityPercent: Double
)

data class OutboxItem(val id: Long, val payload: String, val attempts: Int)

object SessionCalculator {
    fun summarize(samples: List<Measurement>, eventId: String = UUID.randomUUID().toString()): SessionSummary? {
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
            averageHumidityPercent = ordered.map { it.humidityPercent }.average()
        )
    }
}

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "myair-s1.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE measurements(id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL, device_epoch INTEGER, pm25 INTEGER, temperature REAL, humidity REAL, battery INTEGER, trigger TEXT, raw_hex TEXT UNIQUE NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL)")
        createV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS measurement_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, sample_count INTEGER NOT NULL, latest_pm25 INTEGER NOT NULL, latest_temperature REAL NOT NULL, latest_humidity REAL NOT NULL, latest_battery INTEGER NOT NULL, average_pm25 REAL NOT NULL, average_temperature REAL NOT NULL, average_humidity REAL NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, delivered_at INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_measurements_received_at ON measurements(received_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(delivered_at,next_attempt_at)")
    }

    fun addMeasurement(m: Measurement): Long = writableDatabase.insertWithOnConflict("measurements", null, ContentValues().apply {
        put("received_at", m.receivedAt); put("device_epoch", m.deviceEpochSeconds); put("pm25", m.pm25)
        put("temperature", m.temperatureC); put("humidity", m.humidityPercent); put("battery", m.batteryPercent)
        put("trigger", m.trigger); put("raw_hex", m.rawHex)
    }, SQLiteDatabase.CONFLICT_IGNORE)

    fun addEvent(kind: String, detail: String) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("occurred_at", System.currentTimeMillis()); put("kind", kind); put("detail", detail.take(2000))
        })
    }

    fun completeSession(samples: List<Measurement>): SessionSummary? {
        val summary = SessionCalculator.summarize(samples) ?: return null
        val latest = summary.latest
        val payload = summary.toJson().toString()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("measurement_sessions", null, ContentValues().apply {
                put("event_id", summary.eventId); put("started_at", summary.startedAt); put("ended_at", summary.endedAt)
                put("sample_count", summary.sampleCount); put("latest_pm25", latest.pm25)
                put("latest_temperature", latest.temperatureC); put("latest_humidity", latest.humidityPercent)
                put("latest_battery", latest.batteryPercent); put("average_pm25", summary.averagePm25)
                put("average_temperature", summary.averageTemperatureC); put("average_humidity", summary.averageHumidityPercent)
            })
            writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
                put("event_id", summary.eventId); put("payload", payload); put("created_at", System.currentTimeMillis())
            })
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        pruneOlderThan30Days()
        return summary
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

fun SessionSummary.toJson(): JSONObject = JSONObject().apply {
    put("schema_version", 1); put("event_id", eventId); put("device_id", "myair-s1")
    put("started_at", startedAt); put("ended_at", endedAt); put("sample_count", sampleCount)
    put("latest", JSONObject().apply {
        put("received_at", latest.receivedAt); put("timestamp_utc", latest.timestampUtc); put("pm25_ug_m3", latest.pm25)
        put("temperature_c", latest.temperatureC); put("humidity_pct", latest.humidityPercent)
        put("battery_pct", latest.batteryPercent); put("trigger", latest.trigger)
    })
    put("average", JSONObject().apply {
        put("pm25_ug_m3", averagePm25); put("temperature_c", averageTemperatureC); put("humidity_pct", averageHumidityPercent)
    })
}
