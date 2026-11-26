package com.nganyaflow.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class Stage(val id: String, val name: String, val latitude: Double? = null, val longitude: Double? = null)
@Serializable data class Route(
    val id: String, val name: String,
    @SerialName("short_name") val shortName: String? = null,
    val corridor: String? = null,
    @SerialName("stage_ids") val stageIds: List<String>,
    val directions: List<String>, val source: String? = null,
) {
    fun servedDirections(stageId: String): List<String> = directions.filter { direction ->
        source != "digital-matatus-2019" || stageIds.size < 2 ||
            !((direction == "townbound" && stageIds.first() == stageId) ||
                (direction == "outbound" && stageIds.last() == stageId))
    }
}
@Serializable data class Meta(
    @SerialName("api_version") val apiVersion: String,
    @SerialName("server_time") val serverTime: String,
    @SerialName("catalog_version") val catalogVersion: String? = null,
    @SerialName("catalog_attribution") val catalogAttribution: String? = null,
    @SerialName("consent_version") val consentVersion: String,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("model_data_kind") val modelDataKind: String? = null,
    @SerialName("demo_mode") val demoMode: Boolean = false,
    @SerialName("evidence_window_minutes") val evidenceWindowMinutes: Int = 30,
)
@Serializable data class Estimate(
    val status: String, val reason: String? = null,
    @SerialName("median_minutes") val medianMinutes: Double? = null,
    @SerialName("lower_minutes") val lowerMinutes: Double? = null,
    @SerialName("upper_minutes") val upperMinutes: Double? = null,
    @SerialName("nominal_coverage") val nominalCoverage: Double = .8,
    @SerialName("evidence_count") val evidenceCount: Int = 0,
    @SerialName("last_observed_at") val lastObservedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("data_kind") val dataKind: String? = null,
    val occupancy: String = "unknown",
    @SerialName("occupancy_observed_at") val occupancyObservedAt: String? = null,
) {
    fun current(now: Instant): Estimate = if (status == "available" &&
        (expiresAt == null || runCatching { Instant.parse(expiresAt) <= now }.getOrDefault(true))) {
        copy(status = "unavailable", reason = "stale_evidence", medianMinutes = null, lowerMinutes = null, upperMinutes = null)
    } else this
}
@Serializable data class RouteEstimate(
    @SerialName("route_id") val routeId: String,
    @SerialName("route_name") val routeName: String,
    @SerialName("short_name") val shortName: String? = null,
    val corridor: String? = null, val direction: String, val estimate: Estimate,
)
@Serializable data class StageEstimates(@SerialName("stage_id") val stageId: String, @SerialName("as_of") val asOf: String, val routes: List<RouteEstimate>)
@Serializable data class BoardingReport(
    val id: String, @SerialName("install_id") val installId: String,
    @SerialName("stage_id") val stageId: String, @SerialName("route_id") val routeId: String,
    val direction: String, @SerialName("boarded_at") val boardedAt: String,
    @SerialName("consent_version") val consentVersion: String,
)
@Serializable data class Session(
    val id: String, @SerialName("stage_id") val stageId: String,
    @SerialName("route_id") val routeId: String, val direction: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("data_kind") val dataKind: String,
    @SerialName("dataset_id") val datasetId: String,
)
@Serializable data class Observation(
    val id: String, @SerialName("session_id") val sessionId: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("event_type") val eventType: String,
    val occupancy: String = "unknown", @SerialName("vehicle_label") val vehicleLabel: String? = null,
)
@Serializable data class DeletionRequest(val id: String, @SerialName("install_id") val installId: String)
@Serializable data class DeletionStatus(
    val id: String, val status: String,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("reports_deleted") val reportsDeleted: Int = 0, val note: String? = null,
)
@Serializable data class AuthSession(val token: String, val role: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class DemandStage(
    @SerialName("stage_id") val stageId: String, @SerialName("stage_name") val stageName: String,
    @SerialName("boarding_reports") val boardingReports: Int,
    @SerialName("observed_arrivals") val observedArrivals: Int,
    @SerialName("latest_arrival_at") val latestArrivalAt: String? = null,
    @SerialName("latest_occupancy") val latestOccupancy: String = "unknown",
    @SerialName("occupancy_observed_at") val occupancyObservedAt: String? = null,
    val status: String,
)
@Serializable data class Demand(
    @SerialName("route_id") val routeId: String, @SerialName("route_name") val routeName: String,
    val direction: String, @SerialName("window_minutes") val windowMinutes: Int,
    @SerialName("as_of") val asOf: String, val stages: List<DemandStage>, val disclaimer: String,
    @SerialName("min_reports_for_ok") val minReportsForOk: Int = 5,
)
@Serializable data class LocalReport(val report: BoardingReport, val state: String = "Queued", val detail: String? = null)
@Serializable data class LocalSession(val session: Session, val windowId: String, val endedAt: String? = null, val paused: Boolean = false, val state: String = "Queued", val detail: String? = null)
@Serializable data class LocalObservation(val observation: Observation, val voided: Boolean = false, val state: String = "Queued", val detail: String? = null)
@Serializable data class LocalVehicle(val id: String, val sessionId: String, val windowId: String, val label: String, val arrivalId: String, val departureId: String? = null, val departureUnobserved: Boolean = false)
@Serializable data class LocalDeletion(val request: DeletionRequest, val status: DeletionStatus? = null, val state: String = "Deletion pending", val detail: String? = null)
data class AppSettings(
    val serverUrl: String, val installId: String = "", val consentVersion: String? = null,
    val consentAt: String? = null, val onboardingComplete: Boolean = false,
    val selectedStageId: String = "muthaiga", val direction: String = "townbound",
    val theme: String = "system", val dynamicColor: Boolean = false, val language: String = "en",
    val favourites: Set<String> = emptySet(), val clockOffsetMillis: Long = 0L, val lastSyncAt: String? = null,
)
