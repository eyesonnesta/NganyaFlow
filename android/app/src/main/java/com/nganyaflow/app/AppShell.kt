package com.nganyaflow.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import com.nganyaflow.app.core.designsystem.EmptyState
import com.nganyaflow.app.core.designsystem.LoadingState
import com.nganyaflow.app.core.designsystem.PrimaryButton
import com.nganyaflow.app.feature.rider.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data object StageDestination
@Serializable data object RoutesDestination
@Serializable data object ActivityDestination
@Serializable data object SettingsDestination
@Serializable data class RouteDetailDestination(val routeId: String)
@Serializable data class EvidenceDestination(val routeId: String, val stageId: String, val direction: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val repository = (LocalContext.current.applicationContext as NganyaFlowApp).container.repository
    val model: RiderViewModel = viewModel(factory = remember(repository) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = RiderViewModel(repository) as T
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stageId = state.settings.selectedStageId
    val direction = state.settings.direction
    val board by remember(stageId, direction, repository) { repository.estimates(stageId, direction) }
        .collectAsStateWithLifecycle(initialValue = null)
    val detail = destination?.hasRoute<RouteDetailDestination>() == true || destination?.hasRoute<EvidenceDestination>() == true
    val visible = destination?.hasRoute<StageDestination>() == true || detail
    LaunchedEffect(stageId, direction, visible, state.initialized) {
        if (visible && state.initialized) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.refreshBoard(stageId, direction); delay(30_000) }
        }
    }
    if (!state.initialized) {
        Column(Modifier.padding(24.dp)) { LoadingState() }
        return
    }
    if (!state.settings.onboardingComplete) {
        RiderOnboarding(state, model::onboard)
        return
    }
    val selected = when {
        destination?.hasRoute<RoutesDestination>() == true -> 1
        destination?.hasRoute<ActivityDestination>() == true -> 2
        destination?.hasRoute<SettingsDestination>() == true -> 3
        else -> 0
    }
    fun navigate(index: Int) {
        if (index == 0) {
            nav.popBackStack<StageDestination>(inclusive = false)
            return
        }
        val route = when (index) { 1 -> RoutesDestination; 2 -> ActivityDestination; 3 -> SettingsDestination; else -> StageDestination }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { if (detail) IconButton({ nav.navigateUp() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.rider_back)) } })
        },
        bottomBar = {
            NavigationBar {
                val labels = listOf(R.string.stage, R.string.routes, R.string.activity, R.string.settings)
                val icons = listOf(Icons.Outlined.DirectionsBus, Icons.Outlined.Route, Icons.Outlined.History, Icons.Outlined.Settings)
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected == index, { navigate(index) }, icon = { Icon(icons[index], null) }, label = { Text(stringResource(label)) })
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = StageDestination, modifier = Modifier.padding(padding)) {
            composable<StageDestination> {
                StageBoard(state, board, model::refreshNow, model::refreshCatalogue, model::direction,
                    { model.selectStage(it) }, model::favourite, { nav.navigate(RouteDetailDestination(it.id)) }, repository::now)
            }
            composable<RoutesDestination> {
                RiderRoutes(state, model::refreshCatalogue) { nav.navigate(RouteDetailDestination(it.id)) }
            }
            composable<RouteDetailDestination> { entry ->
                val route = state.routes.find { it.id == entry.toRoute<RouteDetailDestination>().routeId }
                RiderRouteDetail(state, route, board, model::direction, { stage -> model.selectStage(stage, route) }, model::refreshNow) {
                    if (route != null) nav.navigate(EvidenceDestination(route.id, stageId, direction))
                }
            }
            composable<EvidenceDestination> { entry ->
                val args = entry.toRoute<EvidenceDestination>()
                val evidence by remember(args) { repository.estimates(args.stageId, args.direction) }.collectAsStateWithLifecycle(initialValue = null)
                RiderEvidence(state, evidence?.routes?.find { it.routeId == args.routeId }?.estimate, args.stageId, args.routeId, repository::now)
            }
            composable<ActivityDestination> {
                val reports by repository.reports.collectAsStateWithLifecycle()
                Column(Modifier.padding(16.dp)) {
                    if (reports.isEmpty()) EmptyState(stringResource(R.string.no_reports), stringResource(R.string.no_reports_detail)) {
                        PrimaryButton(stringResource(R.string.browse_routes), { navigate(1) })
                    } else reports.forEach { Text("${it.report.boardedAt} · ${it.state}", Modifier.padding(vertical = 12.dp)) }
                }
            }
            composable<SettingsDestination> { RiderBasicSettings(state, model::theme, model::refreshCatalogue) }
        }
    }
}
