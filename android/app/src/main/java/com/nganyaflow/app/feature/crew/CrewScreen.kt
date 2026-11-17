package com.nganyaflow.app.feature.crew

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.core.model.Estimate
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CrewScreen(repository: NganyaRepository, onAccess: () -> Unit) {
    val auth by repository.auth.collectAsStateWithLifecycle()
    val routes by repository.routes.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val meta by repository.meta.collectAsStateWithLifecycle()
    var routeId by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf("townbound") }
    var window by rememberSaveable { mutableIntStateOf(60) }
    var expanded by remember { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var estimates by remember(routeId, direction) { mutableStateOf<Map<String, Estimate>>(emptyMap()) }
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }
    val route = routes.find { it.id == routeId } ?: routes.firstOrNull { settings.selectedStageId in it.stageIds } ?: routes.firstOrNull()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(repository.now()) { while (true) { value = repository.now(); delay(1000) } }
    val language = LocalConfiguration.current.locales[0].language
    val demand by remember(route?.id, direction, window) { repository.demandFlow(route?.id.orEmpty(), direction, window) }
        .collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(route?.id, direction, window, auth?.role, refresh) {
        if (route != null && auth?.role == "crew") lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true; error = null
                try {
                    val result = repository.demand(route.id, direction, window)
                    val next = mutableMapOf<String, Estimate>()
                    for (stage in result.stages) {
                        try { next[stage.stageId] = repository.estimate(stage.stageId, route.id, direction) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* The demand result remains useful without a wait estimate. */ }
                    }
                    estimates = next
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { loading = false }
                delay(30_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoticeCard(stringResource(R.string.crew_participation), stringResource(R.string.crew_disclaimer), kind = NoticeKind.Warning)
        if (auth?.role != "crew") {
            Text(stringResource(R.string.crew_access_required))
            PrimaryButton(stringResource(R.string.crew_sign_in), onAccess)
        } else {
            Box {
                OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(route?.let { "${it.shortName ?: it.id} · ${it.name}" } ?: stringResource(R.string.crew_choose_route)) }
                DropdownMenu(expanded, { expanded = false }) {
                    routes.forEach { option -> DropdownMenuItem(text = { Text("${option.shortName ?: option.id} · ${option.name}") }, onClick = {
                        routeId = option.id; direction = option.directions.firstOrNull() ?: "townbound"; expanded = false; selectedStage = null
                    }) }
                }
            }
            TextButton({ filters = !filters }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crew_controls, stringResource(if(direction == "townbound") R.string.crew_townbound else R.string.crew_outbound), window))
            }
            if(filters) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (route?.directions ?: emptyList()).forEach { value -> FilterChip(direction == value, { direction = value; selectedStage = null }, label = { Text(stringResource(if(value == "townbound") R.string.crew_townbound else R.string.crew_outbound)) }) }
                listOf(30, 60, 120).forEach { minutes -> FilterChip(window == minutes, { window = minutes }, label = { Text(stringResource(R.string.crew_window, minutes)) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if(meta?.demoMode != false) DemoDataChip()
                TextButton({ refresh++ }) { Text(stringResource(R.string.crew_refresh)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (demand == null && !loading && error == null) Text(stringResource(R.string.crew_no_data))
            demand?.let { result ->
                Text(stringResource(R.string.crew_as_of, relativeNairobiTime(result.asOf, now, language)), style = MaterialTheme.typography.labelMedium)
                val maximum = maxOf(1, result.stages.maxOfOrNull { it.boardingReports } ?: 1)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(result.stages, key = { it.stageId }) { stage ->
                        Card(onClick = { selectedStage = if(selectedStage == stage.stageId) null else stage.stageId }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stage.stageName, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.crew_reports, stage.boardingReports))
                                LinearProgressIndicator(progress = { stage.boardingReports.toFloat() / maximum }, modifier = Modifier.fillMaxWidth().height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    InfoChip(stringResource(when(stage.status) { "ok" -> R.string.crew_ok; "limited" -> R.string.crew_limited; else -> R.string.crew_no_data }), warning = stage.status != "ok")
                                    CrowdingChip(stage.latestOccupancy)
                                }
                                Text(stringResource(R.string.crew_arrivals, stage.observedArrivals), style = MaterialTheme.typography.bodyMedium)
                                val wait = estimates[stage.stageId]?.current(now)
                                Text(if(wait?.status == "available" && wait.lowerMinutes != null && wait.upperMinutes != null)
                                    formatWaitRange(wait.lowerMinutes, wait.upperMinutes, compact = true, language = LocalConfiguration.current.locales[0].language)
                                    else stringResource(R.string.crew_wait_unavailable), style = MaterialTheme.typography.bodySmall)
                                if(selectedStage == stage.stageId) {
                                    Text(stringResource(R.string.crew_crowding_time, relativeNairobiTime(stage.occupancyObservedAt, now, language)))
                                    Text(stringResource(R.string.crew_last_arrival, relativeNairobiTime(stage.latestArrivalAt, now, language)))
                                    Text(stringResource(R.string.crew_limits, result.minReportsForOk))
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.crew_no_advice), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun crewTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.of("Africa/Nairobi")).format(Instant.parse(value))
}.getOrDefault(value)
