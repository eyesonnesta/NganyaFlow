package com.nganyaflow.app.feature.reporting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nganyaflow.app.BuildConfig
import com.nganyaflow.app.core.database.OutboxEntity
import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ReportingContent(
    val settings: AppSettings = AppSettings(BuildConfig.DEFAULT_SERVER_URL), val meta: Meta? = null,
    val stages: List<Stage> = emptyList(), val routes: List<Route> = emptyList(), val reports: List<LocalReport> = emptyList(),
)
data class BoardingDraft(
    val routeId: String? = null, val phase: String = "choose", val reportId: String? = null,
    val boardedAt: String? = null, val server: String? = null, val installId: String? = null,
    val busy: Boolean = false, val error: String? = null,
)
data class ReportingState(val content: ReportingContent = ReportingContent(), val draft: BoardingDraft = BoardingDraft(), val outbox: List<OutboxEntity> = emptyList())

fun eligibleBoardingRoutes(routes: List<Route>, stageId: String, direction: String): List<Route> =
    routes.filter { stageId in it.stageIds && direction in it.servedDirections(stageId) }

class ReportingViewModel(
    private val repository: NganyaRepository, private val handle: SavedStateHandle,
    private val stageId: String, private val direction: String,
) : ViewModel() {
    private val draft = MutableStateFlow(BoardingDraft(
        routeId = handle["boardingRoute"], phase = handle["boardingPhase"] ?: "choose",
        reportId = handle["boardingId"], boardedAt = handle["boardingTime"],
        server = handle["boardingServer"], installId = handle["boardingInstall"],
    ))
    private val content = combine(repository.settings, repository.meta, repository.stages, repository.routes, repository.reports) { settings, meta, stages, routes, reports -> ReportingContent(settings, meta, stages, routes, reports) }
    val state = combine(content, draft, repository.outbox) { content, draft, outbox -> ReportingState(content, draft, outbox) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReportingState(draft = draft.value))

    init { action { repository.initialize() } }
    private fun update(transform: (BoardingDraft) -> BoardingDraft) {
        val next = transform(draft.value)
        draft.value = next
        handle["boardingRoute"] = next.routeId; handle["boardingPhase"] = next.phase
        handle["boardingId"] = next.reportId; handle["boardingTime"] = next.boardedAt
        handle["boardingServer"] = next.server; handle["boardingInstall"] = next.installId
    }
    fun selectRoute(id: String) {
        if(draft.value.busy) return
        if(id !in eligibleBoardingRoutes(state.value.content.routes, stageId, direction).map { it.id }) return
        update { it.copy(routeId = id, phase = "choose", reportId = null, boardedAt = null, server = null, installId = null, error = null) }
    }
    fun agree() = action { repository.setConsent(true) }
    fun refresh() = action { repository.refreshCatalogue() }
    fun review() {
        val content = state.value.content
        if(draft.value.busy || draft.value.routeId == null) return
        if(content.settings.consentVersion == null || content.settings.consentVersion != content.meta?.consentVersion) return
        update { it.copy(phase = "review", reportId = it.reportId ?: UUID.randomUUID().toString(), boardedAt = it.boardedAt ?: repository.now().minusSeconds(2).toString(), server = content.settings.serverUrl, installId = content.settings.installId, error = null) }
    }
    fun chooseAgain() { if(!draft.value.busy) update { it.copy(phase = "choose", reportId = null, boardedAt = null, server = null, installId = null, error = null) } }
    fun save() {
        if(draft.value.busy) return
        val reviewed = draft.value
        if(reviewed.routeId == null || reviewed.reportId == null || reviewed.boardedAt == null || reviewed.server == null || reviewed.installId == null) return
        action {
            repository.saveReport(stageId, reviewed.routeId, direction, reviewed.reportId, reviewed.boardedAt, reviewed.server, reviewed.installId)
            update { it.copy(phase = "saved") }
        }
    }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        if(draft.value.busy) return@launch
        update { it.copy(busy = true, error = null) }
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { update { it.copy(error = e.message) } }
        finally { update { it.copy(busy = false) } }
    }
}

data class ReportingActionState(val busy: Boolean = false, val error: String? = null)
class ReportingActionsViewModel(private val repository: NganyaRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ReportingActionState())
    val state = mutable.asStateFlow()
    fun sync() = action { repository.syncNow() }
    fun undo(id: String) = action { repository.undoReport(id) }
    fun retry(id: String) = action { repository.retry(id); repository.syncNow() }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        if(mutable.value.busy) return@launch
        mutable.value = ReportingActionState(busy = true)
        try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { mutable.value = mutable.value.copy(error = e.message) }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
