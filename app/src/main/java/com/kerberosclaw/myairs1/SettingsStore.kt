package com.kerberosclaw.myairs1

import android.content.Context

enum class ThemeMode(val label: String) { SYSTEM("跟隨系統"), LIGHT("淺色"), DARK("深色") }

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
