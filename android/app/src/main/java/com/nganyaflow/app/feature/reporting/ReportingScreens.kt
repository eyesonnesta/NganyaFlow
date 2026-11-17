package com.nganyaflow.app.feature.reporting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.data.NganyaRepository
import com.nganyaflow.app.feature.rider.directionLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BoardingScreen(repository: NganyaRepository, stageId: String, direction: String, onSaved: () -> Unit, onCancel: () -> Unit, onTitleChange: (String) -> Unit = {}) {
    val factory = remember(repository, stageId, direction) { viewModelFactory { initializer { ReportingViewModel(repository, createSavedStateHandle(), stageId, direction) } } }
    val model: ReportingViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val content = state.content
    val draft = state.draft
    val stage = content.stages.find { it.id == stageId }
    val choices = eligibleBoardingRoutes(content.routes, stageId, direction)
    val selected = choices.find { it.id == draft.routeId }
    val saved = content.reports.find { it.report.id == draft.reportId }
    val consent = content.settings.consentVersion != null && content.settings.consentVersion == content.meta?.consentVersion
    val changed = draft.server?.let { it != content.settings.serverUrl } == true || draft.installId?.let { it != content.settings.installId } == true
    val title = stringResource(when { saved != null -> R.string.reporting_saved_phone; !consent -> R.string.reporting_consent_title; draft.phase == "review" -> R.string.reporting_review_title; else -> R.string.reporting_choose_route })
    LaunchedEffect(title) { onTitleChange(title) }
    var policy by rememberSaveable { mutableStateOf(false) }
    BackHandler(draft.phase == "review" && !draft.busy && saved == null) { model.chooseAgain() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.reporting_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }) }
        if(draft.error != null) item { NoticeCard(stringResource(R.string.reporting_error), draft.error, kind = NoticeKind.Error) }
        when {
            saved != null -> {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(40.dp))
                            Text(stringResource(R.string.reporting_saved_phone), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.reporting_saved_detail))
                            InfoChip(reportStatus(saved.state))
                        }
                    }
                }
                item { ReportFacts(saved.report, content.stages, content.routes) }
                if(saved.state == "Synced") item { NoticeCard(stringResource(R.string.reporting_synced), stringResource(R.string.reporting_synced_detail), kind = NoticeKind.Success) }
                else if(saved.state == "Deleted") item { NoticeCard(stringResource(R.string.reporting_deleted), stringResource(R.string.reporting_deleted_detail)) }
                else item { NoticeCard(reportStatus(saved.state), stringResource(R.string.reporting_queue_detail)) }
                item { PrimaryButton(stringResource(R.string.reporting_view_activity), onSaved) }
                item { SecondaryButton(stringResource(R.string.reporting_keep_browsing), onCancel) }
            }
            draft.phase == "saved" -> {
                item { NoticeCard(stringResource(R.string.reporting_no_local), stringResource(R.string.reporting_no_local_detail)) }
                item { PrimaryButton(stringResource(R.string.reporting_view_activity), onSaved) }
            }
            !consent -> {
                item { Text(stringResource(R.string.reporting_consent_title), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.reporting_consent_intro)) }
                item { NoticeCard(stringResource(R.string.reporting_your_choice), stringResource(R.string.reporting_consent_data)) }
                item { SecondaryButton(stringResource(R.string.reporting_read_policy), { policy = true }) }
                if(content.meta == null) {
                    item { NoticeCard(stringResource(R.string.reporting_policy_needed), stringResource(R.string.reporting_policy_needed_detail)) }
                    item { PrimaryButton(stringResource(R.string.reporting_connect), model::refresh, enabled = !draft.busy) }
                } else {
                    item { Text(stringResource(R.string.reporting_policy_version, content.meta.consentVersion), style = MaterialTheme.typography.labelLarge) }
                    item { PrimaryButton(stringResource(R.string.reporting_agree), model::agree, enabled = !draft.busy) }
                }
                item { SecondaryButton(stringResource(R.string.reporting_decline), onCancel) }
            }
            changed -> {
                item { NoticeCard(stringResource(R.string.reporting_choices_changed), stringResource(R.string.reporting_choices_changed_detail), kind = NoticeKind.Warning) }
                item { PrimaryButton(stringResource(R.string.reporting_choose_again), model::chooseAgain) }
            }
            draft.phase == "review" && selected != null -> {
                item { Text(stringResource(R.string.reporting_review_title), style = MaterialTheme.typography.headlineSmall) }
                item { NoticeCard(stringResource(R.string.reporting_check_details), stringResource(R.string.reporting_review_detail)) }
                item { ReviewFacts(stage?.name ?: stageId, selected, direction, draft.boardedAt.orEmpty()) }
                item { PrimaryButton(stringResource(if(draft.busy) R.string.reporting_saving else R.string.reporting_confirm), model::save, enabled = !draft.busy) }
                item { SecondaryButton(stringResource(R.string.reporting_choose_again), model::chooseAgain, enabled = !draft.busy) }
                item { TextButton(onCancel, enabled = !draft.busy) { Text(stringResource(R.string.reporting_cancel)) } }
            }
            else -> {
                item { Text(stringResource(R.string.reporting_choose_route), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.reporting_no_preselection)) }
                item { NoticeCard(stage?.name ?: stageId, directionLabel(direction)) }
                if(choices.isEmpty()) {
                    item { EmptyState(stringResource(R.string.reporting_no_routes), stringResource(R.string.reporting_no_routes_detail)) }
                    item { SecondaryButton(stringResource(R.string.reporting_connect), model::refresh, enabled = !draft.busy) }
                }
                items(choices, key = { it.id }) { route ->
                    Surface(color = if(selected?.id == route.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().selectable(selected?.id == route.id, role = Role.RadioButton, enabled = !draft.busy, onClick = { model.selectRoute(route.id) }).heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected?.id == route.id, null)
                            RouteBadge(route.shortName ?: route.id)
                            Column(Modifier.weight(1f)) { Text(route.name, style = MaterialTheme.typography.titleMedium); route.corridor?.let { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                        }
                    }
                }
                item { PrimaryButton(stringResource(R.string.reporting_review), model::review, enabled = selected != null && !draft.busy) }
                item { SecondaryButton(stringResource(R.string.reporting_cancel), onCancel) }
            }
        }
        if(draft.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    if(policy) AlertDialog(onDismissRequest = { policy = false }, title = { Text(stringResource(R.string.reporting_policy_title)) },
        text = { Text(stringResource(R.string.reporting_policy_text)) }, confirmButton = { TextButton({ policy = false }) { Text(stringResource(R.string.reporting_close)) } })
}

@Composable
fun ActivityScreen(repository: NganyaRepository, onBrowse: () -> Unit, onTitleChange: (String) -> Unit = {}) {
    val reports by repository.reports.collectAsStateWithLifecycle()
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stages by repository.stages.collectAsStateWithLifecycle()
    val queue by repository.outbox.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val actions: ReportingActionsViewModel = viewModel(factory = remember(repository) { viewModelFactory { initializer { ReportingActionsViewModel(repository) } } })
    val actionState by actions.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = reports.find { it.report.id == selectedId }
    val title = stringResource(if(selectedId == null) R.string.reporting_activity_title else R.string.reporting_report_detail)
    LaunchedEffect(title) { onTitleChange(title) }
    BackHandler(selectedId != null) { selectedId = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(if(selected == null) R.string.reporting_activity_title else R.string.reporting_report_detail), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }) }
        if(actionState.error != null) item { NoticeCard(stringResource(R.string.reporting_error), actionState.error!!, kind = NoticeKind.Error) }
        if(actionState.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(selectedId != null && selected == null) {
            item { EmptyState(stringResource(R.string.reporting_no_local), stringResource(R.string.reporting_no_local_detail)) }
            item { SecondaryButton(stringResource(R.string.reporting_back_activity), { selectedId = null }) }
        } else if(selected != null) {
            item { InfoChip(reportStatus(selected.state), warning = selected.state in listOf("Will retry", "Needs attention", "Sign in required")) }
            item { ReportFacts(selected.report, stages, routes) }
            selected.detail?.let { detail -> item { NoticeCard(stringResource(R.string.reporting_sync_details), detail, kind = NoticeKind.Warning) } }
            val pending = queue.filter { it.entityId == selected.report.id }
            if(pending.isNotEmpty()) {
                item { Text(stringResource(R.string.reporting_sync_details), style = MaterialTheme.typography.titleLarge) }
                items(pending, key = { it.id }) { entry ->
                    NoticeCard(stringResource(if(entry.action == "retract") R.string.reporting_undo_upload else R.string.reporting_report_upload),
                        stringResource(R.string.reporting_upload_state, reportStatus(entry.state), stringResource(if(entry.attempted) R.string.reporting_yes else R.string.reporting_no)))
                }
            }
            if(selected.state != "Deleted") {
                item { PrimaryButton(stringResource(R.string.reporting_undo), { undoId = selected.report.id }, enabled = !actionState.busy && selected.state != "Deletion pending") }
                if(pending.isNotEmpty()) item { SecondaryButton(stringResource(R.string.reporting_retry), { actions.retry(selected.report.id) }, enabled = !actionState.busy) }
            } else item { NoticeCard(stringResource(R.string.reporting_deleted), stringResource(R.string.reporting_deleted_detail)) }
            item { SecondaryButton(stringResource(R.string.reporting_back_activity), { selectedId = null }) }
        } else {
            item { Text(stringResource(R.string.reporting_activity_intro)) }
            if(reports.isEmpty()) {
                item { EmptyState(stringResource(R.string.reporting_empty_title), stringResource(R.string.reporting_empty_detail)) }
                item { PrimaryButton(stringResource(R.string.reporting_browse), onBrowse) }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    InfoChip(stringResource(R.string.reporting_pending_count, queue.count { it.action in listOf("report", "retract") }))
                    TextButton(actions::sync, enabled = !actionState.busy) { Text(stringResource(R.string.reporting_sync_now)) }
                }
            }
            settings.lastSyncAt?.let { last -> item { Text(stringResource(R.string.reporting_last_sync, shortNairobiTime(last)), style = MaterialTheme.typography.bodyMedium) } }
            if(reports.isEmpty()) {
                item { EmptyState(stringResource(R.string.reporting_empty_title), stringResource(R.string.reporting_empty_detail)) { PrimaryButton(stringResource(R.string.reporting_browse), onBrowse) } }
            } else items(reports, key = { it.report.id }) { local ->
                val route = routes.find { it.id == local.report.routeId }
                val stage = stages.find { it.id == local.report.stageId }
                Surface(onClick = { selectedId = local.report.id }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RouteBadge(route?.shortName ?: local.report.routeId)
                            Column(Modifier.weight(1f)) { Text(stage?.name ?: local.report.stageId, style = MaterialTheme.typography.titleMedium); Text(directionLabel(local.report.direction), style = MaterialTheme.typography.bodyMedium) }
                            Icon(if(local.state == "Synced") Icons.Outlined.CloudDone else Icons.Outlined.DirectionsBus, null)
                        }
                        Text(reportActivityTime(local.report.boardedAt, repository.now()), style = MaterialTheme.typography.bodyMedium)
                        InfoChip(reportStatus(local.state), warning = local.state in listOf("Needs attention", "Will retry", "Sign in required"))
                    }
                }
            }
        }
    }
    if(undoId != null) AlertDialog(onDismissRequest = { undoId = null }, title = { Text(stringResource(R.string.reporting_undo_title)) }, text = { Text(stringResource(R.string.reporting_undo_detail)) },
        confirmButton = { TextButton({ undoId?.let(actions::undo); undoId = null }) { Text(stringResource(R.string.reporting_undo)) } },
        dismissButton = { TextButton({ undoId = null }) { Text(stringResource(R.string.reporting_cancel)) } })
}

@Composable
private fun ReviewFacts(stage: String, route: Route, direction: String, time: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RouteBadge(route.shortName ?: route.id)
            Text(route.name, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reporting_stage_value, stage))
            Text(stringResource(R.string.reporting_direction_value, directionLabel(direction)))
            Text(stringResource(R.string.reporting_time_value, shortNairobiTime(time)))
        }
    }
}

@Composable
private fun ReportFacts(report: BoardingReport, stages: List<Stage>, routes: List<Route>) {
    val route = routes.find { it.id == report.routeId }
    if(route != null) ReviewFacts(stages.find { it.id == report.stageId }?.name ?: report.stageId, route, report.direction, report.boardedAt)
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(report.routeId, style = MaterialTheme.typography.titleLarge); Text(stages.find { it.id == report.stageId }?.name ?: report.stageId); Text(directionLabel(report.direction)); Text(shortNairobiTime(report.boardedAt)) }
}

@Composable
private fun shortNairobiTime(time: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching { DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).withZone(ZoneId.of("Africa/Nairobi")).format(Instant.parse(time)) }.getOrDefault(stringResource(R.string.reporting_time_unknown))
}

@Composable
private fun reportActivityTime(time: String, now: Instant): String {
    val minutes = runCatching { Duration.between(Instant.parse(time), now).toMinutes().coerceAtLeast(0) }.getOrNull()
    val relative = when { minutes == null -> stringResource(R.string.reporting_time_unknown); minutes < 1 -> stringResource(R.string.reporting_just_now); minutes < 60 -> stringResource(R.string.reporting_minutes_ago, minutes); minutes < 1440 -> stringResource(R.string.reporting_hours_ago, minutes / 60); else -> stringResource(R.string.reporting_days_ago, minutes / 1440) }
    return "$relative · ${shortNairobiTime(time)}"
}

@Composable
fun reportStatus(state: String): String = stringResource(when(state) {
    "Queued" -> R.string.reporting_queued
    "Uploading" -> R.string.reporting_uploading
    "Synced" -> R.string.reporting_synced
    "Will retry" -> R.string.reporting_will_retry
    "Needs attention" -> R.string.reporting_attention
    "Sign in required" -> R.string.reporting_sign_in
    "Deletion pending" -> R.string.reporting_deletion_pending
    "Deleted" -> R.string.reporting_deleted
    else -> R.string.reporting_queued
})
