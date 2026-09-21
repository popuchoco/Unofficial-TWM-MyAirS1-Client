package com.kerberosclaw.myairs1

import android.content.Context
import java.time.Instant
import java.time.ZoneId

enum class ThemeMode(val label: String) { SYSTEM("跟隨系統"), LIGHT("淺色"), DARK("深色") }
enum class LocalScheduleMode { ONCE, INTERVAL, DAILY }

data class LocalSchedule(
    val id: String,
    val mode: LocalScheduleMode,
    val intervalMinutes: Int,
    val nextRunAt: Long,
    val localMinuteOfDay: Int? = null,
    val graceMinutes: Int = 10
)

object ScheduleTimes {
    fun nextDaily(localMinuteOfDay: Int, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(localMinuteOfDay in 0..1439)
        val now = Instant.ofEpochMilli(after).atZone(zone)
        var candidate = now.toLocalDate().atTime(localMinuteOfDay / 60, localMinuteOfDay % 60).atZone(zone)
        if (!candidate.toInstant().isAfter(Instant.ofEpochMilli(after))) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }
}

object SettingsStore {
    private const val FILE = "app_settings"
    private const val THEME = "theme_mode"
    private const val DISCONNECT_ALERT = "disconnect_alert"

    fun theme(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(THEME, ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun setTheme(context: Context, value: ThemeMode) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(THEME, value.name).apply()

    fun disconnectAlert(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(DISCONNECT_ALERT, true)

    fun setDisconnectAlert(context: Context, enabled: Boolean) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(DISCONNECT_ALERT, enabled).apply()

}
