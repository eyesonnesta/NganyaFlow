package com.nganyaflow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nganyaflow.app.core.designsystem.EmptyState
import com.nganyaflow.app.core.designsystem.ErrorState
import com.nganyaflow.app.core.designsystem.InfoChip
import com.nganyaflow.app.core.designsystem.LoadingState
import com.nganyaflow.app.core.designsystem.NoticeCard
import com.nganyaflow.app.core.designsystem.PrimaryButton
import com.nganyaflow.app.core.designsystem.RouteCard
import com.nganyaflow.app.core.designsystem.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable data object StageDestination
@Serializable data object RoutesDestination
@Serializable data object ActivityDestination
@Serializable data object SettingsDestination

data class BootstrapStage(val id: String, val name: String)
data class BootstrapRoute(val id: String, val name: String, val number: String, val corridor: String, val stages: List<String>)
data class BootstrapState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val stages: List<BootstrapStage> = emptyList(),
    val routes: List<BootstrapRoute> = emptyList(),
    val demo: Boolean = false,
)

/** Real startup network state until the durable repositories are installed in WP3. */
class BootstrapViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(BootstrapState())
    val state = mutableState.asStateFlow()
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, failed = false)
            try {
                val result = withContext(Dispatchers.IO) {
                    fun fetch(path: String): String {
                        val request = Request.Builder().url(BuildConfig.DEFAULT_SERVER_URL + path).build()
                        return client.newCall(request).execute().use { response ->
                            check(response.isSuccessful) { "Server returned ${response.code}" }
                            response.body.string()
                        }
                    }
                    val meta = JSONObject(fetch("api/v1/meta"))
                    val stages = JSONArray(fetch("api/v1/stages"))
                    val routes = JSONArray(fetch("api/v1/routes"))
                    BootstrapState(
                        loading = false,
                        stages = (0 until stages.length()).map { i ->
                            stages.getJSONObject(i).let { BootstrapStage(it.getString("id"), it.getString("name")) }
                        },
                        routes = (0 until routes.length()).map { i ->
                            routes.getJSONObject(i).let { route ->
                                val stageIds = route.getJSONArray("stage_ids")
                                BootstrapRoute(route.getString("id"), route.getString("name"),
                                    route.optString("short_name", "").takeUnless { it == "null" || it.isBlank() } ?: route.getString("id"),
                                    route.optString("corridor", "").takeUnless { it == "null" } ?: "",
                                    (0 until stageIds.length()).map { stageIds.getString(it) })
                            }
                        },
                        demo = meta.optBoolean("demo_mode"),
                    )
                }
                mutableState.value = result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(model: BootstrapViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val labels = listOf(R.string.stage, R.string.routes, R.string.activity, R.string.settings)
    val icons = listOf(Icons.Outlined.DirectionsBus, Icons.Outlined.Route, Icons.Outlined.History, Icons.Outlined.Settings)
    val selected = when {
        destination?.hasRoute<RoutesDestination>() == true -> 1
        destination?.hasRoute<ActivityDestination>() == true -> 2
        destination?.hasRoute<SettingsDestination>() == true -> 3
        else -> 0
    }
    fun navigate(index: Int) {
        val route = when (index) { 1 -> RoutesDestination; 2 -> ActivityDestination; 3 -> SettingsDestination; else -> StageDestination }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) }) },
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected == index, { navigate(index) },
                        icon = { Icon(icons[index], null) }, label = { Text(stringResource(label)) })
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = StageDestination, modifier = Modifier.padding(padding)) {
            composable<StageDestination> { BootstrapStages(state, model::refresh) }
            composable<RoutesDestination> { BootstrapRoutes(state, model::refresh) }
            composable<ActivityDestination> {
                Column(Modifier.padding(16.dp)) {
                    EmptyState(stringResource(R.string.no_reports), stringResource(R.string.no_reports_detail)) {
                        PrimaryButton(stringResource(R.string.browse_routes), { navigate(1) })
                    }
                }
            }
            composable<SettingsDestination> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(stringResource(R.string.settings_heading), style = MaterialTheme.typography.headlineLarge) }
                    item { NoticeCard(stringResource(R.string.server_connection), BuildConfig.DEFAULT_SERVER_URL) }
                    item { Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyLarge) }
                    item { SecondaryButton(stringResource(R.string.refresh_catalogue), model::refresh) }
                    item { Text(stringResource(R.string.stage_count, state.stages.size)) }
                    item { Text(stringResource(R.string.route_count, state.routes.size)) }
                }
            }
        }
    }
}

@Composable
private fun BootstrapStages(state: BootstrapState, retry: () -> Unit) {
    var chosen by remember { mutableStateOf<BootstrapStage?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.stage_intro), style = MaterialTheme.typography.headlineLarge) }
        if (state.loading) item { LoadingState() }
        else if (state.failed) item { ErrorState(stringResource(R.string.connection_error), stringResource(R.string.connection_detail), retry) }
        else if (state.stages.isEmpty()) item { EmptyState(stringResource(R.string.no_stages), stringResource(R.string.no_stages_detail)) }
        else {
            val stage = chosen
            if (stage == null) {
                item { Text(stringResource(R.string.choose_stage), style = MaterialTheme.typography.titleLarge) }
                items(state.stages, key = { it.id }) { row ->
                    TextButton({ chosen = row }, Modifier.fillMaxWidth()) { Text(row.name, Modifier.fillMaxWidth()) }
                }
            } else {
                item { Text(stage.name, style = MaterialTheme.typography.headlineSmall) }
                item { SecondaryButton(stringResource(R.string.choose_stage), { chosen = null }) }
                items(state.routes.filter { stage.id in it.stages }, key = { it.id }) { route ->
                    RouteCard(route.number, route.name, route.corridor, demo = state.demo)
                }
            }
        }
    }
}

@Composable
private fun BootstrapRoutes(state: BootstrapState, retry: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val routes = state.routes.filter { (it.number + " " + it.name + " " + it.corridor).contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.route_catalogue), style = MaterialTheme.typography.headlineLarge) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_routes)) }, singleLine = true) }
        if (state.loading) item { LoadingState() }
        else if (state.failed) item { ErrorState(stringResource(R.string.connection_error), stringResource(R.string.connection_detail), retry) }
        else if (routes.isEmpty()) item { EmptyState(stringResource(R.string.no_routes), stringResource(R.string.search_again)) }
        else items(routes, key = { it.id }) { route -> RouteCard(route.number, route.name, route.corridor) }
    }
}
