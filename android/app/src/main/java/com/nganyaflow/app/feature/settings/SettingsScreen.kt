package com.nganyaflow.app.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nganyaflow.app.BuildConfig
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(repository: NganyaRepository, onAccess: () -> Unit, onAlerts: () -> Unit, onTitleChange: (String) -> Unit = {}) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val meta by repository.meta.collectAsStateWithLifecycle()
    val outbox by repository.outbox.collectAsStateWithLifecycle()
    val deletions by repository.deletions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.settings_failed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("home") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val locationGranted = remember(permissionRevision, context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(page != "home") { page = "home"; error = null; status = null }
    fun perform(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; status = null
        scope.launch {
            try { block(); status = success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failedMessage }
            finally { busy = false }
        }
    }
    fun open(next: String) { page = next; error = null; status = null }
    val title = when(page) {
        "language" -> R.string.settings_language
        "appearance" -> R.string.settings_appearance
        "privacy" -> R.string.settings_privacy
        "sync" -> R.string.settings_sync
        "server" -> R.string.settings_server
        "about" -> R.string.settings_about
        else -> R.string.settings_title
    }
    val resolvedTitle = stringResource(title)
    LaunchedEffect(resolvedTitle) { onTitleChange(resolvedTitle) }
    val saved = stringResource(R.string.settings_saved)
    val synced = stringResource(R.string.settings_sync_finished)
    val cacheCleared = stringResource(R.string.settings_cache_cleared)
    val deletionQueued = stringResource(R.string.settings_deletion_queued)
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if(!busy) confirmDelete = false },
        title = { Text(stringResource(R.string.settings_delete_title)) },
        text = { Text(stringResource(R.string.settings_delete_explanation)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            confirmDelete = false
            perform(deletionQueued) { repository.deleteMyData() }
        }) { Text(stringResource(R.string.settings_delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.settings_cancel)) } },
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            if(page != "home") TextButton({ open("home") }) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        }
        if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { NoticeCard(stringResource(R.string.settings_failed), it, kind = NoticeKind.Error) } }
        status?.let { item { NoticeCard(stringResource(R.string.settings_done), it, kind = NoticeKind.Success) } }
        when(page) {
            "home" -> {
                item { SettingsRow(stringResource(R.string.settings_language), if(settings.language == "sw") "Kiswahili" else "English") { open("language") } }
                item { SettingsRow(stringResource(R.string.settings_appearance), stringResource(when(settings.theme) { "light" -> R.string.settings_light; "dark" -> R.string.settings_dark; else -> R.string.settings_system })) { open("appearance") } }
                item { SettingsRow(stringResource(R.string.settings_wait_alerts), stringResource(R.string.settings_wait_alerts_detail), onAlerts) }
                item { SettingsRow(stringResource(R.string.settings_privacy), stringResource(if(settings.consentVersion == null) R.string.settings_consent_off else R.string.settings_consent_on)) { open("privacy") } }
                item { SettingsRow(stringResource(R.string.settings_sync), stringResource(R.string.settings_queue_count, outbox.size)) { open("sync") } }
                item { SettingsRow(stringResource(R.string.settings_access), stringResource(R.string.settings_access_detail), onAccess) }
                item { SettingsRow(stringResource(R.string.settings_server), settings.serverUrl) { open("server") } }
                item { SettingsRow(stringResource(R.string.settings_about), BuildConfig.VERSION_NAME) { open("about") } }
            }
            "language" -> {
                item { Text(stringResource(R.string.settings_language_detail)) }
                item { SettingsChoice("English", settings.language == "en", !busy) { perform { repository.updateSettings { it.copy(language = "en") } } } }
                item { SettingsChoice("Kiswahili", settings.language == "sw", !busy) { perform { repository.updateSettings { it.copy(language = "sw") } } } }
                item { NoticeCard(stringResource(R.string.settings_review_title), stringResource(R.string.settings_review_detail), kind = NoticeKind.Warning) }
            }
            "appearance" -> {
                listOf("system" to R.string.settings_system, "light" to R.string.settings_light, "dark" to R.string.settings_dark).forEach { (value, label) ->
                    item { SettingsChoice(stringResource(label), settings.theme == value, !busy) { perform { repository.updateSettings { it.copy(theme = value) } } } }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_dynamic), Modifier.weight(1f))
                    Switch(settings.dynamicColor, enabled = !busy, onCheckedChange = { enabled -> perform { repository.updateSettings { it.copy(dynamicColor = enabled) } } })
                } }
                item { Text(stringResource(R.string.settings_dynamic_detail), style = MaterialTheme.typography.bodyMedium) }
            }
            "privacy" -> {
                item { NoticeCard(stringResource(R.string.settings_your_choices), stringResource(R.string.settings_privacy_detail)) }
                item { Text(stringResource(if(settings.consentVersion == null) R.string.settings_consent_off else R.string.settings_consent_on), style = MaterialTheme.typography.titleMedium) }
                settings.consentVersion?.let { version -> item { Text(stringResource(R.string.settings_consent_record, version, settingsTime(settings.consentAt))) } }
                if(settings.consentVersion != null) item { Button(enabled = !busy, onClick = { perform(saved) { repository.setConsent(false) } }) { Text(stringResource(R.string.settings_withdraw)) } }
                item { Text(stringResource(if(locationGranted) R.string.settings_location_on else R.string.settings_location_off)) }
                item { SecondaryButton(stringResource(R.string.settings_device_permissions), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) }
                item { Text(stringResource(R.string.settings_collected), style = MaterialTheme.typography.bodyMedium) }
                item { OutlinedButton(enabled = !busy, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_delete)) } }
                deletions.forEach { local -> item(key = local.request.id) {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if(local.state == "Deleted") R.string.settings_deletion_complete else R.string.settings_deletion_pending), style = MaterialTheme.typography.titleMedium)
                        local.status?.let { Text(stringResource(R.string.settings_reports_deleted, it.reportsDeleted)) }
                        local.detail?.let { Text(it) }
                        if(local.state != "Deleted") TextButton(enabled = !busy, onClick = { perform(synced) { repository.syncNow() } }) { Text(stringResource(R.string.settings_sync_now)) }
                    } }
                } }
            }
            "sync" -> {
                item { Text(stringResource(R.string.settings_last_sync, settingsTime(settings.lastSyncAt))) }
                if(outbox.isEmpty()) item { NoticeCard(stringResource(R.string.settings_queue_empty), stringResource(R.string.settings_queue_empty_detail), kind = NoticeKind.Success) }
                outbox.groupingBy { it.state }.eachCount().forEach { (state, count) -> item { Text("${settingsState(state)}: $count") } }
                item { Button(enabled = !busy, onClick = { perform(synced) { repository.syncNow() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_sync_now)) } }
                item { Button(enabled = !busy && outbox.any { it.state == "Needs attention" || it.state == "Sign in required" }, onClick = { perform { outbox.map { it.entityId }.distinct().forEach { repository.retry(it) }; repository.syncNow() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_retry_queue)) } }
                item { OutlinedButton(enabled = !busy, onClick = { perform(cacheCleared) { repository.clearEstimates() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_clear_cache)) } }
                item { Text(stringResource(R.string.settings_cache_detail), style = MaterialTheme.typography.bodyMedium) }
                outbox.filter { it.detail != null }.forEach { row -> item(key = row.id) { NoticeCard(settingsState(row.state), row.detail.orEmpty(), kind = NoticeKind.Warning) } }
            }
            "server" -> {
                item { Text(stringResource(R.string.settings_server_detail)) }
                item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_server)) }, singleLine = true, enabled = !busy) }
                item { Button(enabled = !busy && server.isNotBlank(), onClick = { perform(saved) { repository.changeServer(server) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_check_save)) } }
            }
            "about" -> {
                item { Text("NganyaFlow ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge) }
                item { Text(stringResource(R.string.settings_about_detail)) }
                meta?.let { value ->
                    item { if(value.demoMode || value.modelDataKind != "field") DemoDataChip() }
                    item { Text(stringResource(R.string.settings_model, value.modelVersion ?: stringResource(R.string.settings_none))) }
                    value.catalogAttribution?.let { attribution -> item { Text(attribution, style = MaterialTheme.typography.bodyMedium) } }
                }
                item { NoticeCard(stringResource(R.string.settings_review_title), stringResource(R.string.settings_review_detail), kind = NoticeKind.Warning) }
                item { Text(stringResource(R.string.settings_licences), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp)) {
        RadioButton(selected, onClick, enabled = enabled)
        Text(label, Modifier.padding(top = 12.dp, start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun settingsTime(value: String?): String = value?.let { runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.of("Africa/Nairobi")).format(Instant.parse(it))
}.getOrDefault(it) } ?: "—"

@Composable
private fun settingsState(value: String): String = stringResource(when(value) {
    "Queued" -> R.string.settings_state_queued
    "Uploading" -> R.string.settings_state_uploading
    "Synced" -> R.string.settings_state_synced
    "Will retry" -> R.string.settings_state_retry
    "Needs attention" -> R.string.settings_state_attention
    "Sign in required" -> R.string.settings_state_signin
    "Deletion pending" -> R.string.settings_deletion_pending
    "Deleted" -> R.string.settings_deletion_complete
    else -> R.string.settings_state_attention
})
