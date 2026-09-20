package com.kerberosclaw.myairs1

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BackendUploader {
    private val client = OkHttpClient()
    fun upload(baseUrl: String, m: Measurement): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("device_id", "myair-s1"); put("received_at", m.receivedAt); put("timestamp_utc", m.timestampUtc)
            put("pm25_ug_m3", m.pm25); put("temperature_c", m.temperatureC); put("humidity_pct", m.humidityPercent)
            put("battery_pct", m.batteryPercent); put("trigger", m.trigger); put("raw_hex", m.rawHex)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/measurements").post(body).build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}: ${it.body?.string()}")
            it.body?.string().orEmpty()
        }
    }
}
