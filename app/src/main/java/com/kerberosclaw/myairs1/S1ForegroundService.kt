package com.kerberosclaw.myairs1

import android.app.*
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class S1ForegroundService : LifecycleService() {
    private val app get() = application as MyAirApplication
    private var wasConnected = false
    private var suppressDisconnectAlert = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "myAir S1 背景連線", NotificationManager.IMPORTANCE_LOW)
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "myAir S1 斷線提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(NOTIFICATION_ID, notification("正在啟動背景連線…"))
        lifecycleScope.launch { app.bleManager.state.collect { state ->
            updateNotification(state.phase)
            if (wasConnected && !state.connected && !suppressDisconnectAlert && SettingsStore.disconnectAlert(this@S1ForegroundService)) notifyDisconnected()
            wasConnected = state.connected
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            suppressDisconnectAlert = true
            app.bleManager.disconnect(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        suppressDisconnectAlert = false
        app.bleManager.startAutoReconnect(); return START_STICKY
    }
    override fun onDestroy() { app.bleManager.stopAutoReconnect(); super.onDestroy() }
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    private fun notifyDisconnected() {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alert = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("myAir S1 已斷線")
            .setContentText("背景連線會自動嘗試重新連線").setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        getSystemService(NotificationManager::class.java).notify(DISCONNECT_NOTIFICATION_ID, alert)
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, S1ForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("myAir S1").setContentText(text).setContentIntent(open).setOngoing(true).addAction(0, "停止", stop).build()
    }
    companion object {
        const val ACTION_STOP = "com.kerberosclaw.myairs1.STOP"
        private const val CHANNEL_ID = "myair_ble"
        private const val ALERT_CHANNEL_ID = "myair_disconnect"
        private const val NOTIFICATION_ID = 1001
        private const val DISCONNECT_NOTIFICATION_ID = 1002
    }
}
