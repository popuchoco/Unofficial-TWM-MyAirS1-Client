package com.kerberosclaw.myairs1

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "myair-s1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE measurements(id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL, device_epoch INTEGER, pm25 INTEGER, temperature REAL, humidity REAL, battery INTEGER, trigger TEXT, raw_hex TEXT UNIQUE NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    fun exportJson(): String {
        val out = StringBuilder("{\"schema_version\":1,\"measurements\":[")
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
