package com.kerberosclaw.myairs1

import android.content.Context
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object BackendUploader {
    private val client = OkHttpClient()
    fun configured() = BuildConfig.MYAIR_API_URL.isNotBlank() && BuildConfig.MYAIR_SUPABASE_ANON_KEY.isNotBlank() && BuildConfig.MYAIR_UPLOAD_KEY.isNotBlank()
    fun upload(payload: String): Boolean {
        if (!configured()) return false
        val request = Request.Builder().url(BuildConfig.MYAIR_API_URL)
            .header("Authorization", "Bearer ${BuildConfig.MYAIR_SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.MYAIR_SUPABASE_ANON_KEY)
            .header("X-MyAir-Key", BuildConfig.MYAIR_UPLOAD_KEY)
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        return runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!BackendUploader.configured()) return Result.success()
        val db = (applicationContext as MyAirApplication).database
        repeat(20) {
            val item = db.nextOutbox() ?: return Result.success()
            if (BackendUploader.upload(item.payload)) db.markDelivered(item.id)
            else { db.markFailed(item.id, item.attempts); return Result.retry() }
        }
        return if (db.pendingOutboxCount() > 0) Result.retry() else Result.success()
    }
}

object OutboxScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "myair-outbox-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        )
    }
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "myair-outbox-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        )
    }
}
