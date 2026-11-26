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
import com.nganyaflow.app.feature.reporting.BoardingScreen
import com.nganyaflow.app.feature.reporting.ActivityScreen
import com.nganyaflow.app.feature.settings.SettingsScreen
import com.nganyaflow.app.feature.enumerator.AccessScreen
import com.nganyaflow.app.feature.enumerator.EnumeratorScreen
import com.nganyaflow.app.feature.crew.CrewScreen
import com.nganyaflow.app.feature.alerts.AlertsScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data object StageDestination
@Serializable data object RoutesDestination
@Serializable data object ActivityDestination
@Serializable data object SettingsDestination
@Serializable data class RouteDetailDestination(val routeId: String)
@Serializable data class EvidenceDestination(val routeId: String, val stageId: String, val direction: String)
@Serializable data class BoardingDestination(val stageId: String, val direction: String)
@Serializable data object AccessDestination
@Serializable data object EnumeratorDestination
@Serializable data object CrewDestination
@Serializable data object AlertsDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(alertNavigation: Int = 0) {
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
    var settingsTitle by remember { mutableStateOf("") }
    var activityTitle by remember { mutableStateOf("") }
    var boardingTitle by remember { mutableStateOf("") }
    var accessTitle by remember { mutableStateOf("") }
    var enumeratorTitle by remember { mutableStateOf("") }
    val pageTitle = when {
        destination?.hasRoute<RouteDetailDestination>() == true -> {
            val id = backStack?.toRoute<RouteDetailDestination>()?.routeId
            state.routes.find { it.id == id }?.let { "${it.shortName ?: it.id} · ${it.name}" } ?: stringResource(R.string.routes)
        }
        destination?.hasRoute<EvidenceDestination>() == true -> stringResource(R.string.integration_about)
        destination?.hasRoute<RoutesDestination>() == true -> stringResource(R.string.routes)
        destination?.hasRoute<ActivityDestination>() == true -> activityTitle.ifBlank { stringResource(R.string.activity) }
        destination?.hasRoute<SettingsDestination>() == true -> settingsTitle.ifBlank { stringResource(R.string.settings) }
        destination?.hasRoute<BoardingDestination>() == true -> boardingTitle.ifBlank { stringResource(R.string.integration_board) }
        destination?.hasRoute<AccessDestination>() == true -> accessTitle.ifBlank { stringResource(R.string.integration_access) }
        destination?.hasRoute<EnumeratorDestination>() == true -> enumeratorTitle.ifBlank { stringResource(R.string.integration_enumerator) }
        destination?.hasRoute<CrewDestination>() == true -> stringResource(R.string.crew_title)
        destination?.hasRoute<AlertsDestination>() == true -> stringResource(R.string.integration_alerts)
        else -> stringResource(R.string.app_name)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stageId = state.settings.selectedStageId
    val direction = state.settings.direction
    val board by remember(stageId, direction, repository) { repository.estimates(stageId, direction) }
        .collectAsStateWithLifecycle(initialValue = null)
    val detail = destination?.hasRoute<RouteDetailDestination>() == true || destination?.hasRoute<EvidenceDestination>() == true
    val nested = detail || destination?.hasRoute<BoardingDestination>() == true || destination?.hasRoute<AccessDestination>() == true || destination?.hasRoute<EnumeratorDestination>() == true || destination?.hasRoute<CrewDestination>() == true || destination?.hasRoute<AlertsDestination>() == true
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
    LaunchedEffect(alertNavigation) { if(alertNavigation > 0) nav.popBackStack<StageDestination>(inclusive = false) }
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
            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(pageTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { if (nested) IconButton({ nav.navigateUp() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.rider_back)) } })
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
                    { model.selectStage(it) }, model::favourite, { nav.navigate(RouteDetailDestination(it.id)) }, repository::now,
                    boarding = { nav.navigate(BoardingDestination(stageId, direction)) })
            }
            composable<RoutesDestination> {
                RiderRoutes(state, model::refreshCatalogue) { nav.navigate(RouteDetailDestination(it.id)) }
            }
            composable<RouteDetailDestination> { entry ->
                val route = state.routes.find { it.id == entry.toRoute<RouteDetailDestination>().routeId }
                RiderRouteDetail(state, route, board, model::direction, { stage -> model.selectStage(stage, route) }, model::refreshNow, evidence = {
                    if (route != null) nav.navigate(EvidenceDestination(route.id, stageId, direction))
                }, boarding = { nav.navigate(BoardingDestination(stageId, direction)) })
            }
            composable<EvidenceDestination> { entry ->
                val args = entry.toRoute<EvidenceDestination>()
                val evidence by remember(args) { repository.estimates(args.stageId, args.direction) }.collectAsStateWithLifecycle(initialValue = null)
                RiderEvidence(state, evidence?.routes?.find { it.routeId == args.routeId }?.estimate, args.stageId, args.routeId, repository::now)
            }
            composable<ActivityDestination> {
                ActivityScreen(repository, onBrowse = { navigate(1) }, onTitleChange = { activityTitle = it })
            }
            composable<SettingsDestination> { SettingsScreen(repository, onAccess = { nav.navigate(AccessDestination) }, onAlerts = { nav.navigate(AlertsDestination) }, onTitleChange = { settingsTitle = it }) }
            composable<BoardingDestination> { entry ->
                val args = entry.toRoute<BoardingDestination>()
                BoardingScreen(repository, args.stageId, args.direction, onSaved = { navigate(2) }, onCancel = { nav.navigateUp() }, onTitleChange = { boardingTitle = it })
            }
            composable<AccessDestination> { AccessScreen(repository, onEnumerator = { nav.navigate(EnumeratorDestination) }, onCrew = { nav.navigate(CrewDestination) }, onTitleChange = { accessTitle = it }) }
            composable<EnumeratorDestination> { EnumeratorScreen(repository, onBack = { nav.navigateUp() }, onTitleChange = { enumeratorTitle = it }) }
            composable<CrewDestination> { CrewScreen(repository, onAccess = { nav.navigate(AccessDestination) }) }
            composable<AlertsDestination> { AlertsScreen(repository) }
        }
    }
}
