package com.nganyaflow.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.nganyaflow.app.BuildConfig
import com.nganyaflow.app.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("nganyaflow-settings")
class AppPreferences(context: Context) {
    private val store = context.applicationContext.preferences
    val flow: Flow<AppSettings> = store.data.map(::read)
    private fun read(p: Preferences) = AppSettings(
        serverUrl = p[stringPreferencesKey("server")] ?: BuildConfig.DEFAULT_SERVER_URL,
        installId = p[stringPreferencesKey("install")] ?: "",
        consentVersion = p[stringPreferencesKey("consent")], consentAt = p[stringPreferencesKey("consentAt")],
        onboardingComplete = p[booleanPreferencesKey("onboarded")] ?: false,
        selectedStageId = p[stringPreferencesKey("stage")] ?: "muthaiga",
        direction = p[stringPreferencesKey("direction")] ?: "townbound",
        theme = p[stringPreferencesKey("theme")] ?: "system", dynamicColor = p[booleanPreferencesKey("dynamic")] ?: false,
        language = p[stringPreferencesKey("language")] ?: "en", favourites = p[stringSetPreferencesKey("favourites")] ?: emptySet(),
        clockOffsetMillis = p[longPreferencesKey("clock")] ?: 0L, lastSyncAt = p[stringPreferencesKey("lastSync")],
    )
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(read(p))
            p[stringPreferencesKey("server")] = s.serverUrl
            p[stringPreferencesKey("install")] = s.installId
            fun optional(key: String, value: String?) { if(value == null) p.remove(stringPreferencesKey(key)) else p[stringPreferencesKey(key)] = value }
            optional("consent", s.consentVersion); optional("consentAt", s.consentAt); optional("lastSync", s.lastSyncAt)
            p[booleanPreferencesKey("onboarded")] = s.onboardingComplete
            p[stringPreferencesKey("stage")] = s.selectedStageId; p[stringPreferencesKey("direction")] = s.direction
            p[stringPreferencesKey("theme")] = s.theme; p[booleanPreferencesKey("dynamic")] = s.dynamicColor
            p[stringPreferencesKey("language")] = s.language; p[stringSetPreferencesKey("favourites")] = s.favourites
            p[longPreferencesKey("clock")] = s.clockOffsetMillis
        }
    }
}
