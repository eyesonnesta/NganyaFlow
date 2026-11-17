package com.nganyaflow.app.core.sync

import android.content.Context
import androidx.work.*
import com.nganyaflow.app.NganyaFlowApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as NganyaFlowApp).container.repository
        return if(repo.uploadPending()) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("nganyaflow-outbox", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
