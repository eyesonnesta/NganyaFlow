package com.nganyaflow.app.feature.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.nganyaflow.app.MainActivity
import com.nganyaflow.app.NganyaFlowApp
import com.nganyaflow.app.R
import com.nganyaflow.app.core.datastore.AppPreferences
import com.nganyaflow.app.core.designsystem.formatWaitRange
import com.nganyaflow.app.core.network.WireJson
import com.nganyaflow.app.core.network.createApi
import com.nganyaflow.app.core.sync.serverConstraints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class WaitAlertConfig(
    val id: String, val server: String, val stageId: String, val routeId: String,
    val direction: String, val threshold: Int, val expiresAt: Long,
    val routeName: String, val stageName: String, val language: String,
    val active: Boolean = true, val result: String = "active",
)

/** A new generation prevents an old worker from notifying after replacement or cancellation. */
class WaitAlertStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nganyaflow-wait-alert", Context.MODE_PRIVATE)
    fun read(): WaitAlertConfig? = synchronized(lock) {
        prefs.getString("config", null)?.let { runCatching { WireJson.decodeFromString<WaitAlertConfig>(it) }.getOrNull() }
    }
    fun save(config: WaitAlertConfig) = synchronized(lock) { check(prefs.edit().putString("config", WireJson.encodeToString(config)).commit()) }
    fun finish(id: String, result: String): Boolean = synchronized(lock) {
        val config = read() ?: return false
        if(config.id != id || !config.active) return false
        save(config.copy(active = false, result = result)); true
    }
    fun expire(): WaitAlertConfig? = synchronized(lock) {
        val config = read()
        if(config != null && config.active && config.expiresAt <= System.currentTimeMillis()) finish(config.id, "expired")
        read()
    }
    companion object { internal val lock = Any() }
}

object WaitAlertScheduler {
    const val WORK = "nganyaflow-wait-alert"
    fun start(context: Context, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(context, id, false))
    }
    fun again(context: Context, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(context, id, true))
    }
    private fun request(context: Context, id: String, delayed: Boolean): OneTimeWorkRequest = OneTimeWorkRequestBuilder<WaitAlertWorker>()
        .setInputData(workDataOf("alertId" to id))
        .setConstraints(serverConstraints(WaitAlertStore(context).read()?.server.orEmpty()))
        .setInitialDelay(if(delayed) 60 else 0, TimeUnit.SECONDS).build()
    fun cancel(context: Context) {
        val store = WaitAlertStore(context)
        store.read()?.let { store.finish(it.id, "cancelled") }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(context).cancel(4701)
    }
}

class WaitAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val store = WaitAlertStore(applicationContext)
        val config = store.expire() ?: return Result.success()
        val id = inputData.getString("alertId") ?: return Result.success()
        if(config.id != id || !config.active) return Result.success()
        try {
            val service = createApi(config.server)
            val meta = service.meta()
            val fetchedAt = android.os.SystemClock.elapsedRealtime()
            val response = service.estimate(config.stageId, config.routeId, config.direction)
            val now = Instant.parse(meta.serverTime).plusMillis(android.os.SystemClock.elapsedRealtime() - fetchedAt + 1000)
            val estimate = response.current(now)
            if(estimate.status == "available" && estimate.upperMinutes != null && estimate.lowerMinutes != null && estimate.upperMinutes <= config.threshold) {
                val repo = (applicationContext as NganyaFlowApp).container.repository
                repo.initialize()
                val language = runCatching { AppPreferences(applicationContext).flow.first().language }.getOrDefault(config.language)
                val localized = applicationContext.createConfigurationContext(android.content.res.Configuration(applicationContext.resources.configuration).apply { setLocale(Locale(language)) })
                val permitted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                synchronized(WaitAlertStore.lock) {
                    val latest = store.read()
                    if(latest?.id == id && latest.active && latest.expiresAt > System.currentTimeMillis() && permitted && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
                        val manager = applicationContext.getSystemService(NotificationManager::class.java)
                        manager.createNotificationChannel(NotificationChannel("wait-alerts", localized.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT))
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("alert_stage_id", config.stageId)
                            putExtra("alert_direction", config.direction)
                            putExtra("alert_server", config.server)
                        }
                        val pending = PendingIntent.getActivity(applicationContext, 4701, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val range = formatWaitRange(estimate.lowerMinutes, estimate.upperMinutes, language = language)
                        val demo = if(estimate.dataKind != "field") localized.getString(R.string.alerts_demo_suffix) else ""
                        val text = localized.getString(R.string.alerts_notification_text, config.routeName, config.stageName, range) + demo
                        val notification = NotificationCompat.Builder(applicationContext, "wait-alerts")
                            .setOnlyAlertOnce(true)
                            .setSmallIcon(R.drawable.ic_bus).setContentTitle(localized.getString(R.string.alerts_notification_title))
                            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                            .setContentIntent(pending).setAutoCancel(true).build()
                        try {
                            NotificationManagerCompat.from(applicationContext).notify(4701, notification)
                            store.finish(id, "fired")
                        } catch (_: SecurityException) { store.finish(id, "permission") }
                    } else if(!permitted) store.finish(id, "permission")
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* No current estimate: keep the alert until its expiry. */ }
        val latest = store.expire()
        if(latest?.id == id && latest.active) WaitAlertScheduler.again(applicationContext, id)
        return Result.success()
    }
}
