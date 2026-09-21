package com.kerberosclaw.myairs1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as MyAirApplication
        if (app.database.localSchedule() != null) {
            ContextCompat.startForegroundService(context, Intent(context, S1ForegroundService::class.java))
        }
    }
}
