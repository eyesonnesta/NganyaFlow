package com.nganyaflow.app.core.sync

import android.content.Context
import androidx.work.*
import com.nganyaflow.app.NganyaFlowApp
import com.nganyaflow.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as NganyaFlowApp).container.repository
        return if(repo.uploadPending()) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .setConstraints(serverConstraints((context.applicationContext as NganyaFlowApp).container.repository.settings.value.serverUrl))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // New local work must not sit behind an old offline retry's backoff.
            // Replacing an in-flight upload is safe: every payload retains its UUID.
            WorkManager.getInstance(context).enqueueUniqueWork("nganyaflow-outbox", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Emulator host HTTP works even when Android cannot validate public Internet access. */
fun serverConstraints(server: String): Constraints {
    val localDebug = BuildConfig.DEBUG && server.toHttpUrlOrNull()?.let { it.scheme == "http" && it.host in setOf("10.0.2.2", "localhost") } == true
    return Constraints.Builder().setRequiredNetworkType(if(localDebug) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build()
}
