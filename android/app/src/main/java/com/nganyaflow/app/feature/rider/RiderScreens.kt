package com.nganyaflow.app.feature.rider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.core.model.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RiderOnboarding(state: RiderState, complete: (Boolean) -> Unit) {
    var choices by rememberSaveable { mutableStateOf(false) }
    var research by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RouteBadge("N")
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(if (choices) R.string.rider_choices else R.string.rider_welcome), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(if (choices) R.string.rider_choices_detail else R.string.rider_welcome_detail), style = MaterialTheme.typography.bodyLarge)
        if (state.error != null) NoticeCard(stringResource(R.string.connection_error), stringResource(R.string.rider_refresh_needed), kind = NoticeKind.Error)
        if (!choices) {
            NoticeCard(stringResource(R.string.privacy_summary), stringResource(R.string.rider_board_subtitle))
            PrimaryButton(stringResource(R.string.rider_get_started), { choices = true })
            SecondaryButton(stringResource(R.string.rider_explore), { complete(false) })
        } else {
            NoticeCard(stringResource(R.string.rider_location_choice), stringResource(R.string.rider_location_choice_detail))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().toggleable(research, enabled = state.meta != null, role = Role.Switch, onValueChange = { research = it }).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.rider_research), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.rider_research_detail), style = MaterialTheme.typography.bodyMedium)
                        if (state.meta == null) Text(stringResource(R.string.rider_policy_connect), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(research, null, enabled = state.meta != null)
                }
            }
            SecondaryButton(stringResource(R.string.rider_privacy), { privacy = true })
            PrimaryButton(stringResource(R.string.rider_continue), { complete(research) }, enabled = !state.refreshing)
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text(stringResource(R.string.rider_privacy)) },
        text = { Text(stringResource(R.string.rider_privacy_detail)) }, confirmButton = { TextButton({ privacy = false }) { Text(stringResource(R.string.rider_close)) } })
}

@Composable
fun DirectionControl(direction: String, choices: List<String>, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.rider_direction), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.distinct().forEach { value -> FilterChip(selected = direction == value, onClick = { select(value) }, label = { Text(directionLabel(value)) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}

@Composable
fun directionLabel(direction: String): String = stringResource(if (direction == "townbound") R.string.rider_townbound else R.string.rider_outbound)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageBoard(
    state: RiderState, board: StageEstimates?, refresh: () -> Unit, refreshCatalogue: () -> Unit,
    direction: (String) -> Unit, selectStage: (Stage) -> Unit, favourite: (String) -> Unit,
    routeDetail: (Route) -> Unit, now: () -> Instant,
) {
    var picker by rememberSaveable { mutableStateOf(false) }
    val stage = state.stages.find { it.id == state.settings.selectedStageId }
    val directions = state.routes.filter { stage?.id in it.stageIds }.flatMap { it.servedDirections(stage?.id.orEmpty()) }.distinct()
    val boardRoutes = state.routes.filter { stage?.id in it.stageIds && state.settings.direction in it.servedDirections(stage?.id.orEmpty()) }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ picker = true }, Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Place, null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(stringResource(R.string.rider_change_stage), style = MaterialTheme.typography.labelMedium)
                                    Text(stage?.name ?: stringResource(R.string.choose_stage), style = MaterialTheme.typography.titleLarge)
                                }
                                Icon(Icons.Outlined.ExpandMore, null)
                            }
                            if (stage != null) IconButton({ favourite(stage.id) }) {
                                Icon(if (stage.id in state.settings.favourites) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    stringResource(if (stage.id in state.settings.favourites) R.string.rider_remove_favourite else R.string.rider_add_favourite))
                            }
                        }
                    }
                }
            }
            item { DirectionControl(state.settings.direction, directions.ifEmpty { listOf("townbound", "outbound") }, direction) }
            if (!state.online) item { NoticeCard(stringResource(R.string.rider_offline), stringResource(R.string.rider_offline_detail), kind = NoticeKind.Warning) }
            if (state.error != null) item { ErrorState(stringResource(R.string.connection_error), stringResource(R.string.rider_refresh_needed), refresh) }
            if (state.stages.isEmpty()) {
                if (state.refreshing) item { LoadingState() }
                else item { ErrorState(stringResource(R.string.no_stages), stringResource(R.string.no_stages_detail), refreshCatalogue) }
            } else if (stage == null) item { SecondaryButton(stringResource(R.string.choose_stage), { picker = true }) }
            else if (boardRoutes.isEmpty()) item { EmptyState(stringResource(R.string.rider_no_boardable), stringResource(R.string.rider_no_boardable_detail)) }
            else {
                if (board == null && state.refreshing) item { SkeletonCard() }
                items(boardRoutes, key = { it.id }) { route ->
                    val estimate = board?.routes?.find { it.routeId == route.id && it.direction == state.settings.direction }?.estimate
                    RiderEstimateCard(route, estimate, now(), state.meta?.demoMode == true) { routeDetail(route) }
                }
            }
            item { SecondaryButton(stringResource(R.string.rider_refresh), refresh) }
            item { Text(stringResource(R.string.rider_board_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (picker) StagePicker(state, { picker = false }) { stage -> selectStage(stage); picker = false }
}

@Composable
fun RiderEstimateCard(route: Route, estimate: Estimate?, now: Instant, demoMode: Boolean, onClick: (() -> Unit)? = null) {
    val current = estimate?.current(now)
    val available = current?.status == "available"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteCard(route.shortName ?: route.id, route.name, route.corridor.orEmpty(),
            lower = current?.lowerMinutes.takeIf { available }, median = current?.medianMinutes.takeIf { available }, upper = current?.upperMinutes.takeIf { available },
            unavailableReason = if (available) null else unavailableTitle(current?.reason),
            freshness = freshness(current?.lastObservedAt, now), occupancy = current?.occupancy ?: "unknown",
            demo = demoMode || current?.dataKind?.let { it != "field" } == true, onClick = onClick)
        if (!available) Text(unavailableDetail(current?.reason), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current != null) FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip(stringResource(if (current.evidenceCount >= 3) R.string.evidence_recent else R.string.evidence_limited))
            current.occupancyObservedAt?.let { Text(stringResource(R.string.rider_crowding_age, freshness(it, now)), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StagePicker(state: RiderState, dismiss: () -> Unit, select: (Stage) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locationStatus by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val once = remember(context) { OneShotLocation(context) }
    val permissionMemory = remember(context) { context.getSharedPreferences("rider-location-choice", Context.MODE_PRIVATE) }
    DisposableEffect(once) { onDispose { once.cancel() } }
    fun findLocation() {
        locationStatus = 1
        once.request { result -> location = result?.let { it.latitude to it.longitude }; locationStatus = if (result == null) 3 else 0 }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) findLocation() else locationStatus = 2
    }
    val filtered = state.stages.filter { it.name.contains(query, true) }
    val sorted = filtered.sortedWith(compareBy<Stage> { it.id !in state.settings.favourites }
        .thenBy { stage -> location?.let { distanceKm(it.first, it.second, stage) } ?: Double.MAX_VALUE }.thenBy { it.name })
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.choose_stage), style = MaterialTheme.typography.headlineSmall) }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.rider_search_stage)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
            item {
                SecondaryButton(stringResource(if (locationStatus == 1) R.string.rider_location_loading else R.string.rider_use_location), {
                    if (locationStatus != 1) {
                        when {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> findLocation()
                            permissionMemory.getBoolean("asked", false) -> locationStatus = 2
                            else -> { permissionMemory.edit().putBoolean("asked", true).apply(); permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        }
                    }
                })
            }
            if (locationStatus in 2..3) item { NoticeCard(stringResource(R.string.rider_location_choice), stringResource(if (locationStatus == 2) R.string.rider_location_denied else R.string.rider_location_unavailable)) }
            if (sorted.isEmpty()) item { EmptyState(stringResource(R.string.rider_no_search), stringResource(R.string.rider_keep_search)) }
            if (location == null) {
                val grouped = sorted.groupBy { stage ->
                    if (stage.id in state.settings.favourites) "★" else state.routes.filter { stage.id in it.stageIds }.mapNotNull { it.corridor }.distinct().sorted().firstOrNull().orEmpty()
                }
                grouped.forEach { (group, stages) ->
                    item(key = "group:$group") { Text(if (group == "★") stringResource(R.string.rider_favorite_label) else group.ifEmpty { stringResource(R.string.rider_other_stages) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                    items(stages, key = { it.id }) { StagePickerRow(it, state, null, select) }
                }
            } else items(sorted, key = { it.id }) { stage -> StagePickerRow(stage, state, location?.let { distanceKm(it.first, it.second, stage) }, select) }
        }
    }
}

@Composable
private fun StagePickerRow(stage: Stage, state: RiderState, distance: Double?, select: (Stage) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Surface(onClick = { select(stage) }, shape = MaterialTheme.shapes.medium, color = if (stage.id == state.settings.selectedStageId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (stage.id in state.settings.favourites) Icons.Outlined.Star else Icons.Outlined.Place, null)
            Column(Modifier.weight(1f)) {
                Text(stage.name, style = MaterialTheme.typography.titleMedium)
                if (distance != null) Text(stringResource(R.string.rider_distance, String.format(locale, "%.1f", distance)), style = MaterialTheme.typography.bodyMedium)
            }
            if (stage.id == state.settings.selectedStageId) Icon(Icons.Outlined.Check, stringResource(R.string.rider_you_are_here))
        }
    }
}

@Composable
fun RiderRoutes(state: RiderState, retry: () -> Unit, detail: (Route) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = state.routes.filter { routeMatches(it, query, state.stages) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.route_catalogue), style = MaterialTheme.typography.headlineLarge) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_routes)) }, supportingText = { Text(stringResource(R.string.rider_route_hint)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true) }
        if (!state.online) item { NoticeCard(stringResource(R.string.rider_offline), stringResource(R.string.rider_offline_detail), kind = NoticeKind.Warning) }
        if (state.refreshing && state.routes.isEmpty()) item { LoadingState() }
        if (state.error != null && state.routes.isEmpty()) item { ErrorState(stringResource(R.string.connection_error), stringResource(R.string.rider_refresh_needed), retry) }
        else if (results.isEmpty()) item { EmptyState(stringResource(R.string.rider_no_search), stringResource(R.string.rider_keep_search)) }
        else items(results, key = { it.id }) { route -> RouteCard(route.shortName ?: route.id, route.name, route.corridor.orEmpty(), onClick = { detail(route) }) }
    }
}

@Composable
fun RiderRouteDetail(state: RiderState, route: Route?, board: StageEstimates?, direction: (String) -> Unit, selectStage: (Stage) -> Unit, refresh: () -> Unit, evidence: () -> Unit) {
    if (route == null) { Column(Modifier.padding(16.dp)) { EmptyState(stringResource(R.string.no_routes), stringResource(R.string.search_again)) }; return }
    val selected = state.settings.selectedStageId
    val selectedHere = selected in route.stageIds
    val directions = if (selectedHere) route.servedDirections(selected) else route.directions
    val stages = route.stageIds.mapNotNull { id -> state.stages.find { it.id == id } }.let { if (state.settings.direction == "townbound") it.reversed() else it }
    val estimate = board?.routes?.find { it.routeId == route.id && it.direction == state.settings.direction }?.estimate
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { RouteBadge(route.shortName ?: route.id); Spacer(Modifier.height(12.dp)); Text(route.name, style = MaterialTheme.typography.headlineLarge); Text(route.corridor.orEmpty(), style = MaterialTheme.typography.bodyLarge) }
        item { DirectionControl(state.settings.direction, directions, direction) }
        if (!state.online) item { NoticeCard(stringResource(R.string.rider_offline), stringResource(R.string.rider_offline_detail), kind = NoticeKind.Warning) }
        if (selectedHere && state.settings.direction in directions) {
            item { RiderEstimateCard(route, estimate, Instant.now().plusMillis(state.settings.clockOffsetMillis), state.meta?.demoMode == true) }
            item { SecondaryButton(stringResource(R.string.rider_evidence), evidence) }
            item { SecondaryButton(stringResource(R.string.rider_refresh), refresh) }
        } else item { NoticeCard(stringResource(R.string.choose_stage), stringResource(R.string.rider_choose_on_route)) }
        item { Text(stringResource(R.string.rider_route_stages), style = MaterialTheme.typography.titleLarge) }
        items(stages, key = { it.id }) { stage ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.width(28.dp).height(64.dp)) {
                    drawLine(color.copy(alpha = .4f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 3.dp.toPx(), StrokeCap.Round)
                    drawCircle(color, if (stage.id == selected) 8.dp.toPx() else 5.dp.toPx(), Offset(size.width / 2, size.height / 2))
                }
                TextButton({ selectStage(stage) }, Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stage.name, style = MaterialTheme.typography.titleMedium)
                        if (stage.id == selected) Text(stringResource(R.string.rider_you_are_here), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun RiderEvidence(state: RiderState, estimate: Estimate?, stageId: String, routeId: String, now: () -> Instant) {
    val at = now()
    val unknown = stringResource(R.string.rider_not_known)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.rider_evidence_title), style = MaterialTheme.typography.headlineLarge) }
        item { Text(listOfNotNull(state.stages.find { it.id == stageId }?.name, state.routes.find { it.id == routeId }?.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium) }
        item { NoticeCard(stringResource(R.string.rider_evidence), stringResource(R.string.rider_range_explain)) }
        if (state.meta?.demoMode == true || estimate?.dataKind?.let { it != "field" } == true) item { DemoDataChip() }
        item { Text(stringResource(R.string.rider_evidence_count, estimate?.evidenceCount ?: 0), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.rider_evidence_window, state.meta?.evidenceWindowMinutes ?: 30)) }
        item { Text(stringResource(R.string.rider_last_seen, estimate?.lastObservedAt?.let(::nairobiTime) ?: unknown)) }
        item { Text(stringResource(R.string.rider_model, estimate?.modelVersion ?: state.meta?.modelVersion ?: unknown)) }
        item { Text(stringResource(R.string.rider_data_kind, estimate?.dataKind ?: state.meta?.modelDataKind ?: unknown)) }
        if (estimate != null) item { Text(stringResource(R.string.rider_crowding_age, freshness(estimate.occupancyObservedAt, at))) }
        if (estimate?.status != "available") item { NoticeCard(unavailableTitle(estimate?.reason), unavailableDetail(estimate?.reason), kind = NoticeKind.Warning) }
    }
}

@Composable
fun RiderBasicSettings(state: RiderState, theme: (String) -> Unit, refresh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.settings_heading), style = MaterialTheme.typography.headlineLarge) }
        item { NoticeCard(stringResource(R.string.server_connection), state.settings.serverUrl) }
        item { Text(stringResource(R.string.rider_appearance), style = MaterialTheme.typography.titleLarge) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to R.string.rider_system, "light" to R.string.rider_light, "dark" to R.string.rider_dark).forEach { (value, label) -> FilterChip(state.settings.theme == value, { theme(value) }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp)) } } }
        item { Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyLarge) }
        item { SecondaryButton(stringResource(R.string.refresh_catalogue), refresh) }
        item { Text(stringResource(R.string.stage_count, state.stages.size)); Text(stringResource(R.string.route_count, state.routes.size)) }
    }
}

@Composable
fun unavailableTitle(reason: String?): String = stringResource(when (reason) {
    "insufficient_evidence" -> R.string.rider_not_enough
    "stale_evidence" -> R.string.rider_stale
    "model_not_trained", "model_expired" -> R.string.rider_paused
    "unsupported_training_group", "outside_training_support" -> R.string.rider_unsupported
    "evidence_provenance_mismatch" -> R.string.rider_on_hold
    else -> R.string.estimate_unavailable
})

@Composable
fun unavailableDetail(reason: String?): String = stringResource(when (reason) {
    "insufficient_evidence" -> R.string.rider_not_enough_detail
    "stale_evidence" -> R.string.rider_stale_detail
    "model_not_trained", "model_expired" -> R.string.rider_paused_detail
    "unsupported_training_group", "outside_training_support" -> R.string.rider_unsupported_detail
    "evidence_provenance_mismatch" -> R.string.rider_on_hold_detail
    else -> R.string.rider_wait_for_data
})

@Composable
fun freshness(value: String?, now: Instant): String {
    val elapsed = value?.let { runCatching { Duration.between(Instant.parse(it), now).seconds.coerceAtLeast(0) }.getOrNull() }
    return when { elapsed == null -> stringResource(R.string.rider_not_known); elapsed < 60 -> stringResource(R.string.rider_fresh_seconds); else -> stringResource(R.string.rider_fresh_minutes, elapsed / 60) }
}

fun nairobiTime(value: String): String = runCatching { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.getDefault()).withZone(ZoneId.of("Africa/Nairobi")).format(Instant.parse(value)) }.getOrDefault(value)
