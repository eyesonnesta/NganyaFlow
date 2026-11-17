package com.nganyaflow.app.feature.alerts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun AlertsScreen(repository: NganyaRepository) {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.alerts_failed)
    val stages by repository.stages.collectAsStateWithLifecycle()
    val routes by repository.routes.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val meta by repository.meta.collectAsStateWithLifecycle()
    val store = remember(context) { WaitAlertStore(context) }
    val scope = rememberCoroutineScope()
    var stageId by rememberSaveable { mutableStateOf(settings.selectedStageId) }
    var routeId by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(settings.direction) }
    var threshold by rememberSaveable { mutableStateOf("5") }
    var active by remember { mutableStateOf(store.expire()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val validRoutes = routes.filter { stageId in it.stageIds }
    val route = validRoutes.find { it.id == routeId }
    val validDirections = route?.servedDirections(stageId).orEmpty()
    LaunchedEffect(stageId, routes) { if(validRoutes.none { it.id == routeId }) routeId = validRoutes.firstOrNull()?.id.orEmpty() }
    LaunchedEffect(stageId, routeId, routes) { if(direction !in validDirections) direction = validDirections.firstOrNull().orEmpty() }
    LaunchedEffect(store) { while(true) { active = withContext(Dispatchers.IO) { store.expire() }; delay(1000) } }
    fun enable() {
        if(busy) return
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) { denied = true; return }
        val stage = stages.find { it.id == stageId } ?: return
        val selectedRoute = validRoutes.find { it.id == routeId } ?: return
        val minutes = threshold.toIntOrNull()?.takeIf { it in 1..60 } ?: return
        if(direction !in selectedRoute.servedDirections(stageId)) return
        busy = true; error = null; denied = false
        scope.launch {
            try {
                val config = WaitAlertConfig(UUID.randomUUID().toString(), settings.serverUrl, stageId, routeId, direction,
                    minutes, System.currentTimeMillis() + 2 * 60 * 60 * 1000L,
                    selectedRoute.shortName ?: selectedRoute.name, stage.name, settings.language)
                withContext(Dispatchers.IO) { store.save(config); WaitAlertScheduler.start(context, config.id) }
                active = config
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failedMessage }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) enable() else denied = true }
    val canEnable = !busy && route != null && direction in validDirections && threshold.toIntOrNull()?.let { it in 1..60 } == true
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineMedium) }
        item { NoticeCard(stringResource(R.string.alerts_how_title), stringResource(R.string.alerts_how_detail)) }
        if(meta?.demoMode == true) item { DemoDataChip() }
        error?.let { item { NoticeCard(stringResource(R.string.alerts_failed), it, kind = NoticeKind.Error) } }
        if(denied) item {
            NoticeCard(stringResource(R.string.alerts_permission_title), stringResource(R.string.alerts_permission_detail), kind = NoticeKind.Warning)
            TextButton({ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
                Text(stringResource(R.string.alerts_open_settings))
            }
        }
        if(stages.isEmpty() || routes.isEmpty()) item {
            EmptyState(stringResource(R.string.alerts_catalogue_needed), stringResource(R.string.alerts_catalogue_detail)) {
                SecondaryButton(stringResource(R.string.alerts_refresh), { scope.launch { try { repository.refreshCatalogue() } catch(e: Exception) { error = e.message } } })
            }
        } else {
            item { AlertChoice(stringResource(R.string.alerts_stage), stages.map { it.id to it.name }, stageId, !busy) { stageId = it } }
            item { AlertChoice(stringResource(R.string.alerts_route), validRoutes.map { it.id to "${it.shortName.orEmpty()} ${it.name}" }, routeId, !busy) { routeId = it } }
            item { AlertChoice(stringResource(R.string.alerts_direction), validDirections.map { it to stringResource(if(it == "townbound") R.string.alerts_townbound else R.string.alerts_outbound) }, direction, !busy) { direction = it } }
            item { OutlinedTextField(threshold, { threshold = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(stringResource(R.string.alerts_threshold)) }, supportingText = { Text(stringResource(R.string.alerts_threshold_detail)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
            item { Button(enabled = canEnable, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enable()
            }) { Text(stringResource(if(active?.active == true) R.string.alerts_replace else R.string.alerts_enable)) } }
        }
        active?.let { config -> item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(when { config.active -> R.string.alerts_active; config.result == "fired" -> R.string.alerts_fired; config.result == "expired" -> R.string.alerts_expired; config.result == "permission" -> R.string.alerts_permission_title; else -> R.string.alerts_cancelled }), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.alerts_summary, config.routeName, config.stageName, config.threshold))
                Text(stringResource(R.string.alerts_expires, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.of("Africa/Nairobi")).format(Instant.ofEpochMilli(config.expiresAt))))
                if(config.server != settings.serverUrl) Text(stringResource(R.string.alerts_original_server, config.server), style = MaterialTheme.typography.bodyMedium)
                if(config.active) OutlinedButton(onClick = { WaitAlertScheduler.cancel(context); active = store.read() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alerts_cancel)) }
            } }
        } }
    }
}

@Composable
private fun AlertChoice(label: String, options: List<Pair<String,String>>, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(options.find { it.first == selected }?.second ?: stringResource(R.string.alerts_choose))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (value,title) -> DropdownMenuItem(text = { Text(title) }, onClick = { choose(value); expanded = false }) }
        }
    }
}
