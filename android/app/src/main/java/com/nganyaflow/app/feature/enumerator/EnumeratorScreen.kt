package com.nganyaflow.app.feature.enumerator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.data.NganyaRepository
import com.nganyaflow.app.feature.rider.DirectionControl
import com.nganyaflow.app.feature.rider.directionLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnumeratorScreen(repository: NganyaRepository, onBack: () -> Unit, onTitleChange: (String) -> Unit = {}) {
    val model = enumeratorModel(repository, "enumerator")
    val action by model.state.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val auth by repository.auth.collectAsStateWithLifecycle()
    val sessions by repository.sessions.collectAsStateWithLifecycle()
    val stages by repository.stages.collectAsStateWithLifecycle()
    val routes by repository.routes.collectAsStateWithLifecycle()
    val now = clock(repository)
    val authorized = auth?.role == "enumerator" && auth?.expiresAt?.let { runCatching { Instant.parse(it) > now }.getOrDefault(false) } == true
    if (!authorized) {
        AccessScreen(repository, onEnumerator = onBack, onCrew = onBack, onTitleChange = onTitleChange)
        return
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var closing by rememberSaveable { mutableStateOf<String?>(null) }
    var eventType by rememberSaveable { mutableStateOf<String?>(null) }
    var eventVehicle by rememberSaveable { mutableStateOf<String?>(null) }
    var unobserved by remember { mutableStateOf<LocalVehicle?>(null) }
    var voidId by rememberSaveable { mutableStateOf<String?>(null) }
    var log by rememberSaveable { mutableStateOf(false) }
    val active = sessions.firstOrNull { it.endedAt == null }
    LaunchedEffect(active?.session?.id) { active?.let { selectedId = it.session.id; history = false; closing = null } }
    val selected = sessions.find { it.session.id == selectedId }
    val observations by remember(repository, selectedId) { repository.observations(selectedId.orEmpty()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val vehicles by remember(repository, selectedId) { repository.vehicles(selectedId.orEmpty()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val openVehicles = vehicles.filter { it.departureId == null && !it.departureUnobserved }
    val live = selected != null && selected.endedAt == null && !history && closing == null
    val view = LocalView.current
    DisposableEffect(view, live) {
        val previous = view.keepScreenOn
        if (live) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, history, closing) { listState.scrollToItem(0) }
    val title = stringResource(when { history -> R.string.enum_history; selected == null -> R.string.enum_setup; selected.endedAt != null -> if (selected.paused) R.string.enum_paused else R.string.enum_closed; closing != null -> R.string.enum_summary; else -> R.string.enum_live })
    LaunchedEffect(title) { onTitleChange(title) }

    Scaffold(bottomBar = {
        if (live && selected != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ eventType = "arrival"; eventVehicle = null }, Modifier.weight(1f).heightIn(min = 72.dp), enabled = !action.busy) { Text(stringResource(R.string.enum_arrival)) }
                        FilledTonalButton({ eventType = "departure"; eventVehicle = null }, Modifier.weight(1f).heightIn(min = 72.dp), enabled = !action.busy && openVehicles.isNotEmpty()) { Text(stringResource(R.string.enum_departure)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ closing = "pause" }, enabled = !action.busy) { Text(stringResource(R.string.enum_pause)) }
                        TextButton({ closing = "end" }, enabled = !action.busy) { Text(stringResource(R.string.enum_summary)) }
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onBack) { Text(stringResource(R.string.enum_back)) }
                    TextButton({ history = !history; closing = null }) { Icon(Icons.Outlined.History, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.enum_view_history)) }
                }
                ActionError(action)
            }
            if (history) {
                if (sessions.none { it.endedAt != null }) item { Text(stringResource(R.string.enum_no_history)) }
                items(sessions.filter { it.endedAt != null }.sortedByDescending { it.session.startedAt }, key = { it.session.id }) { session ->
                    SessionHistoryCard(session, stages, routes, now) { selectedId = session.session.id; history = false; closing = null }
                }
                if (active != null) item { SecondaryButton(stringResource(R.string.enum_back_live), { selectedId = active.session.id; history = false; closing = null }) }
                item { OutlinedButton({ model.perform { repository.signOut() } }, Modifier.fillMaxWidth(), enabled = !action.busy) { Text(stringResource(R.string.enum_sign_out)) } }
            } else if (selected == null) {
                item { EnumeratorSetup(repository, model, action, stages, routes, settings) { selectedId = it; closing = null } }
            } else {
                item {
                    SessionIdentity(selected, stages, routes)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.enum_elapsed, elapsedLabel(Instant.parse(selected.session.startedAt), selected.endedAt?.let(Instant::parse) ?: now)), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.enum_counts,
                        observations.count { !it.voided && it.observation.eventType == "arrival" },
                        observations.count { !it.voided && it.observation.eventType == "departure" }, openVehicles.size), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    InfoChip(syncLabel(selected.state))
                }
                if (selected.endedAt != null) {
                    item { NoticeCard(title, stringResource(R.string.enum_closed_detail), kind = NoticeKind.Success) }
                    if (selected.paused && active == null) {
                        item { Text(stringResource(R.string.enum_resume_detail)) }
                        item { PrimaryButton(stringResource(R.string.enum_resume), {
                            model.perform { selectedId = repository.startSession(selected.session.stageId, selected.session.routeId, selected.session.direction, selected.windowId); closing = null }
                        }, enabled = !action.busy) }
                    }
                    item { SecondaryButton(stringResource(R.string.enum_new_session), { selectedId = null; closing = null }) }
                    item { SecondaryButton(stringResource(R.string.enum_event_log), { log = true }) }
                    item { SecondaryButton(stringResource(R.string.enum_sync_now), { model.perform { repository.syncNow() } }) }
                } else if (closing != null) {
                    item { NoticeCard(stringResource(R.string.enum_summary), stringResource(if (closing == "pause") R.string.enum_resolve_before_pause else R.string.enum_finish_detail)) }
                    items(openVehicles, key = { it.id }) { vehicle ->
                        OpenVehicleCard(vehicle, !action.busy, departure = { eventVehicle = vehicle.id; eventType = "departure" }, markUnobserved = { unobserved = vehicle })
                    }
                    if (openVehicles.isEmpty()) item { PrimaryButton(stringResource(if (closing == "pause") R.string.enum_pause else R.string.enum_finish), {
                        model.perform { repository.closeSession(selected.session.id, paused = closing == "pause"); closing = null }
                    }, enabled = !action.busy) }
                    item { SecondaryButton(stringResource(R.string.enum_back_live), { closing = null }) }
                    item { SecondaryButton(stringResource(R.string.enum_event_log), { log = true }) }
                } else {
                    item { NoticeCard(stringResource(if (selected.session.dataKind == "synthetic") R.string.enum_practice else R.string.enum_field), stringResource(R.string.enum_guidance)) }
                    item { Text(stringResource(R.string.enum_open_vehicles), style = MaterialTheme.typography.titleLarge) }
                    if (openVehicles.isEmpty()) item { Text(stringResource(R.string.enum_no_open_detail), style = MaterialTheme.typography.bodyMedium) }
                    items(openVehicles, key = { it.id }) { vehicle ->
                        OpenVehicleCard(vehicle, !action.busy, departure = { eventVehicle = vehicle.id; eventType = "departure" }, markUnobserved = { unobserved = vehicle })
                    }
                    item { SecondaryButton(stringResource(R.string.enum_event_log), { log = true }) }
                    item { SecondaryButton(stringResource(R.string.enum_sync_now), { model.perform { repository.syncNow() } }) }
                }
            }
        }
    }
    if (eventType != null && selected != null) {
        EventDialog(eventType!!, openVehicles, eventVehicle, action.busy, { eventType = null }, { occupancy, vehicleId ->
            val kind = eventType!!
            model.perform {
                repository.recordEvent(selected.session.id, kind, occupancy, vehicleId)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                eventType = null
            }
        })
    }
    if (unobserved != null) AlertDialog(onDismissRequest = { unobserved = null }, title = { Text(stringResource(R.string.enum_confirm_unobserved)) }, text = { Text(stringResource(R.string.enum_unobserved_detail)) },
        confirmButton = { TextButton({ val vehicle = unobserved!!; model.perform { repository.markDepartureUnobserved(vehicle.id); unobserved = null } }, enabled = !action.busy) { Text(stringResource(R.string.enum_confirm)) } },
        dismissButton = { TextButton({ unobserved = null }) { Text(stringResource(R.string.enum_cancel)) } })
    if (log) ModalBottomSheet(onDismissRequest = { log = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxHeight(.85f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.enum_event_log), style = MaterialTheme.typography.headlineSmall) }
            if (observations.isEmpty()) item { Text(stringResource(R.string.enum_no_events)) }
            items(observations.reversed(), key = { it.observation.id }) { observation ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(listOfNotNull(observation.observation.vehicleLabel, stringResource(if (observation.observation.eventType == "arrival") R.string.enum_arrival_short else R.string.enum_departure_short)).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        Text(relativeNairobi(observation.observation.observedAt, now), style = MaterialTheme.typography.bodyMedium)
                        CrowdingChip(observation.observation.occupancy)
                        Text(syncLabel(observation.state), style = MaterialTheme.typography.labelLarge)
                        if (observation.voided) InfoChip(stringResource(R.string.enum_voided), warning = true)
                        else TextButton({ voidId = observation.observation.id }, enabled = !action.busy) { Text(stringResource(R.string.enum_void)) }
                        observation.detail?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
    if (voidId != null) AlertDialog(onDismissRequest = { voidId = null }, title = { Text(stringResource(R.string.enum_void_title)) }, text = { Text(stringResource(R.string.enum_void_detail)) },
        confirmButton = { TextButton({ val id = voidId!!; model.perform { repository.voidObservation(id); voidId = null } }, enabled = !action.busy) { Text(stringResource(R.string.enum_confirm)) } },
        dismissButton = { TextButton({ voidId = null }) { Text(stringResource(R.string.enum_cancel)) } })
}

@Composable
private fun EnumeratorSetup(repository: NganyaRepository, model: EnumeratorViewModel, action: EnumeratorActionState, stages: List<Stage>, routes: List<Route>, settings: AppSettings, started: (String) -> Unit) {
    val meta by repository.meta.collectAsStateWithLifecycle()
    var stageId by rememberSaveable { mutableStateOf(settings.selectedStageId) }
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }
    var direction by rememberSaveable { mutableStateOf(settings.direction) }
    val stage = stages.find { it.id == stageId }
    val serving = routes.filter { stageId in it.stageIds }
    val route = serving.find { it.id == routeId }
    val directions = route?.servedDirections(stageId).orEmpty()
    LaunchedEffect(stageId, routeId, directions) {
        if (routeId != null && route == null) routeId = null
        if (direction !in directions && directions.isNotEmpty()) direction = directions.first()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.enum_setup), style = MaterialTheme.typography.headlineLarge)
        NoticeCard(stringResource(if (meta?.demoMode == true) R.string.enum_practice else R.string.enum_field), stringResource(R.string.enum_guidance))
        if (stages.isEmpty() || routes.isEmpty() || meta == null) {
            Text(stringResource(R.string.enum_catalogue_needed))
            SecondaryButton(stringResource(R.string.enum_refresh), { model.perform { repository.refreshCatalogue() } })
        }
        EnumSelector(stringResource(R.string.enum_select_stage), stage?.name.orEmpty(), stages.map { it.id to it.name }) { stageId = it; routeId = null }
        EnumSelector(stringResource(R.string.enum_select_route), route?.let { "${it.shortName ?: it.id} · ${it.name}" }.orEmpty(), serving.map { it.id to "${it.shortName ?: it.id} · ${it.name}" }) { routeId = it }
        if (directions.isNotEmpty()) DirectionControl(direction, directions) { direction = it }
        PrimaryButton(stringResource(if (action.busy) R.string.enum_working else R.string.enum_start), {
            model.perform { started(repository.startSession(stageId, requireNotNull(routeId), direction)) }
        }, enabled = !action.busy && stage != null && route != null && direction in directions && meta != null)
    }
}

@Composable
internal fun EnumSelector(label: String, value: String, choices: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = choices.isNotEmpty()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value.ifBlank { label }, style = MaterialTheme.typography.titleMedium) }
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; select(id) }) }
        }
    }
}

@Composable
private fun SessionIdentity(session: LocalSession, stages: List<Stage>, routes: List<Route>) {
    val route = routes.find { it.id == session.session.routeId }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(route?.shortName ?: session.session.routeId)
        Column(Modifier.weight(1f)) {
            Text(stages.find { it.id == session.session.stageId }?.name ?: session.session.stageId, style = MaterialTheme.typography.titleLarge)
            Text(route?.name ?: session.session.routeId, style = MaterialTheme.typography.bodyMedium)
            Text(directionLabel(session.session.direction), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SessionHistoryCard(session: LocalSession, stages: List<Stage>, routes: List<Route>, now: Instant, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SessionIdentity(session, stages, routes)
            Text(relativeNairobi(session.session.startedAt, now), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(syncLabel(session.state))
                if (session.paused) InfoChip(stringResource(R.string.enum_gap))
                if (session.session.dataKind != "field") DemoDataChip()
            }
        }
    }
}

@Composable
private fun OpenVehicleCard(vehicle: LocalVehicle, enabled: Boolean, departure: () -> Unit, markUnobserved: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vehicle.label, style = MaterialTheme.typography.titleLarge)
            Button(departure, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) { Text(stringResource(R.string.enum_departure)) }
            TextButton(markUnobserved, enabled = enabled) { Text(stringResource(R.string.enum_unobserved)) }
        }
    }
}

@Composable
private fun EventDialog(eventType: String, vehicles: List<LocalVehicle>, initialVehicle: String?, busy: Boolean, dismiss: () -> Unit, save: (String, String?) -> Unit) {
    var occupancy by rememberSaveable { mutableStateOf("unknown") }
    var vehicleId by rememberSaveable { mutableStateOf(initialVehicle) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(if (eventType == "arrival") R.string.enum_arrival else R.string.enum_departure)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (eventType == "departure") EnumSelector(stringResource(R.string.enum_choose_vehicle), vehicles.find { it.id == vehicleId }?.label.orEmpty(), vehicles.map { it.id to it.label }) { vehicleId = it }
            Text(stringResource(R.string.enum_crowding), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.enum_crowding_detail), style = MaterialTheme.typography.bodyMedium)
            listOf("empty" to R.string.empty, "seats_available" to R.string.seats_available, "standing" to R.string.standing, "full" to R.string.full, "unknown" to R.string.enum_unknown).forEach { (value, label) ->
                FilterChip(occupancy == value, { occupancy = value }, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
    }, confirmButton = { TextButton({ save(occupancy, vehicleId) }, enabled = !busy && (eventType == "arrival" || vehicles.any { it.id == vehicleId })) { Text(stringResource(if (busy) R.string.enum_working else R.string.enum_save_event)) } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.enum_cancel)) } })
}

@Composable
internal fun elapsedLabel(from: Instant, to: Instant): String {
    val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
    return if (seconds >= 3600) stringResource(R.string.enum_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
    else stringResource(R.string.enum_minutes_seconds, seconds / 60, seconds % 60)
}

@Composable
private fun relativeNairobi(timestamp: String, now: Instant): String {
    val instant = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return stringResource(R.string.enum_unknown)
    val elapsed = Duration.between(instant, now).seconds.coerceAtLeast(0)
    val age = if (elapsed < 60) stringResource(R.string.enum_just_now) else if (elapsed >= 86400) stringResource(R.string.enum_days, elapsed / 86400) else elapsedLabel(instant, now)
    val locale = LocalConfiguration.current.locales[0]
    val time = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(ZoneId.of("Africa/Nairobi")).format(instant)
    return if (elapsed < 60) "$age · $time Nairobi" else stringResource(R.string.enum_time_ago, age, time)
}

@Composable
internal fun syncLabel(state: String): String = stringResource(when (state) {
    "Synced" -> R.string.enum_status_synced
    "Uploading" -> R.string.enum_status_uploading
    "Will retry" -> R.string.enum_status_retry
    "Needs attention" -> R.string.enum_status_attention
    "Sign in required" -> R.string.enum_status_signin
    else -> R.string.enum_status_queued
})
