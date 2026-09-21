package com.kerberosclaw.myairs1

import android.app.Application

class MyAirApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase(this) }
    val bleManager: BleManager by lazy { BleManager(this, database) }

    override fun onCreate() {
        super.onCreate()
        database.pruneOlderThan30Days()
        OutboxScheduler.enqueue(this)
        OutboxScheduler.schedulePeriodic(this)
    }
}
