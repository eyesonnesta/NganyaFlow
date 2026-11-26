package com.nganyaflow.app.feature.rider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nganyaflow.app.BuildConfig
import com.nganyaflow.app.core.model.AppSettings
import com.nganyaflow.app.core.model.Meta
import com.nganyaflow.app.core.model.Route
import com.nganyaflow.app.core.model.Stage
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RiderState(
    val settings: AppSettings = AppSettings(BuildConfig.DEFAULT_SERVER_URL),
    val stages: List<Stage> = emptyList(),
    val routes: List<Route> = emptyList(),
    val meta: Meta? = null,
    val online: Boolean = true,
    val initialized: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

private data class RequestState(val ready: Boolean = false, val refreshing: Boolean = false, val error: String? = null)

class RiderViewModel(val repository: NganyaRepository) : ViewModel() {
    private val requests = MutableStateFlow(RequestState())
    private val content = combine(repository.settings, repository.stages, repository.routes, repository.meta, repository.online) { settings, stages, routes, meta, online ->
        RiderState(settings, stages, routes, meta, online)
    }
    val state = combine(content, requests) { content, request ->
        content.copy(initialized = request.ready, refreshing = request.refreshing, error = request.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RiderState())

    init { refreshCatalogue() }

    fun refreshCatalogue() = action(refreshing = true) {
        repository.initialize()
        requests.value = requests.value.copy(ready = true)
        repository.refreshCatalogue()
    }

    suspend fun refreshBoard(stageId: String, direction: String) {
        requests.value = requests.value.copy(refreshing = true, error = null)
        try {
            // Meta updates clock skew and catalogue version. A failed meta refresh
            // must not prevent a separate estimate request or hide valid cache.
            try { repository.refreshCatalogue() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Cached catalogue remains usable. */ }
            repository.refreshEstimates(stageId, direction)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { requests.value = requests.value.copy(error = e.message) }
        finally { requests.value = requests.value.copy(refreshing = false) }
    }

    fun refreshNow() = viewModelScope.launch { refreshBoard(state.value.settings.selectedStageId, state.value.settings.direction) }
    fun selectStage(stage: Stage, route: Route? = null) = action {
        repository.updateSettings { current ->
            val directions = (route?.let(::listOf) ?: state.value.routes).filter { stage.id in it.stageIds }.flatMap { it.servedDirections(stage.id) }
            current.copy(selectedStageId = stage.id, direction = current.direction.takeIf { it in directions } ?: directions.firstOrNull() ?: "townbound")
        }
    }
    fun direction(direction: String) = action { repository.updateSettings { it.copy(direction = direction) } }
    fun favourite(stageId: String) = action {
        repository.updateSettings { it.copy(favourites = if (stageId in it.favourites) it.favourites - stageId else it.favourites + stageId) }
    }
    fun onboard(consent: Boolean) = action {
        repository.setConsent(consent)
        repository.updateSettings { it.copy(onboardingComplete = true) }
    }
    fun theme(theme: String) = action { repository.updateSettings { it.copy(theme = theme) } }
    private fun action(refreshing: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        requests.value = requests.value.copy(refreshing = refreshing, error = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { requests.value = requests.value.copy(error = e.message, ready = true) }
        finally { requests.value = requests.value.copy(refreshing = false) }
    }
}

fun routeMatches(route: Route, query: String, stages: List<Stage>): Boolean {
    val text = query.trim()
    return text.isEmpty() || listOfNotNull(route.name, route.shortName, route.corridor)
        .plus(stages.filter { it.id in route.stageIds }.map { it.name }).any { it.contains(text, ignoreCase = true) }
}

/** Great-circle distance; the coordinate stays in the stage picker and never reaches a repository. */
fun distanceKm(latitude: Double, longitude: Double, stage: Stage): Double? {
    val lat = stage.latitude ?: return null
    val lon = stage.longitude ?: return null
    val dLat = Math.toRadians(lat - latitude)
    val dLon = Math.toRadians(lon - longitude)
    val a = (sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(lat)) * sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
    return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}
