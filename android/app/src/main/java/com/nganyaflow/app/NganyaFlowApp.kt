package com.nganyaflow.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.nganyaflow.app.core.database.NganyaDatabase
import com.nganyaflow.app.core.datastore.AppPreferences
import com.nganyaflow.app.core.datastore.TokenVault
import com.nganyaflow.app.core.sync.SyncWorker
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.*

class NganyaFlowApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        container.scope.launch { container.repository.initialize() }
    }
}
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(context.applicationContext, NganyaDatabase::class.java, "nganyaflow.db").build()
    val repository = NganyaRepository(database, AppPreferences(context), TokenVault(context), scope) { SyncWorker.enqueue(context) }
}
