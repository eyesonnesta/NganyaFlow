package com.nganyaflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nganyaflow.app.core.designsystem.NganyaFlowTheme

class MainActivity : ComponentActivity() {
    private val alertNavigation = mutableIntStateOf(0)
    private fun consumeAlert(intent: Intent?) {
        val stage = intent?.getStringExtra("alert_stage_id") ?: return
        val direction = intent.getStringExtra("alert_direction") ?: return
        val server = intent.getStringExtra("alert_server") ?: return
        lifecycleScope.launch {
            val repository = (application as NganyaFlowApp).container.repository
            repository.initialize()
            if(repository.settings.value.serverUrl == server) {
                repository.updateSettings { it.copy(selectedStageId = stage, direction = direction) }
                alertNavigation.intValue++
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeAlert(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeAlert(intent)
        setContent {
            val settings by (application as NganyaFlowApp).container.repository.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val configuration = LocalConfiguration.current
            val localized = remember(settings.language, configuration) {
                createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.forLanguageTag(settings.language)) })
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
            NganyaFlowTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) { AppShell(alertNavigation.intValue) }
            }
        }
    }
}
