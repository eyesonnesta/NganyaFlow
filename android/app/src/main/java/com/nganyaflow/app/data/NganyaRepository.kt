package com.nganyaflow.app.data

import androidx.room.withTransaction
import com.nganyaflow.app.BuildConfig
import com.nganyaflow.app.core.database.*
import com.nganyaflow.app.core.datastore.AppPreferences
import com.nganyaflow.app.core.datastore.TokenVault
import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.util.UUID

/** All writes are durable before return. Network work never runs on the UI thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class NganyaRepository(
    private val db: NganyaDatabase,
    private val preferences: AppPreferences,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
    private val scheduleSync: () -> Unit,
) {
    private val dao = db.dao()
    private val operations = Mutex()
    private val initialization = Mutex()
    private var initialized = false
    private var lastEventMillis = 0L
    private val mutableSettings = MutableStateFlow(AppSettings(BuildConfig.DEFAULT_SERVER_URL))
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()
    private val mutableAuth = MutableStateFlow<AuthSession?>(null)
    val auth: StateFlow<AuthSession?> = mutableAuth.asStateFlow()
    private val mutableOnline = MutableStateFlow(true)
    val online = mutableOnline.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val lastError = mutableError.asStateFlow()
    val stages = cacheFlow<List<Stage>>("stages").map { it ?: emptyList() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val routes = cacheFlow<List<Route>>("routes").map { it ?: emptyList() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val meta = cacheFlow<Meta>("meta").stateIn(scope, SharingStarted.Eagerly, null)
    val reports = recordsFlow("report").map { rows -> rows.map { LocalReport(WireJson.decodeFromString(it.payload), it.state, it.detail) } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sessions = combine(recordsFlow("session"), settings.map { it.serverUrl }.distinctUntilChanged().flatMapLatest(dao::outboxFlow)) { rows, pending ->
        rows.map { row ->
            val entries = pending.filter { it.groupId == "session:${row.id}" }
            val priority = listOf("Needs attention", "Sign in required", "Will retry", "Uploading", "Queued")
            val pendingState = priority.firstOrNull { state -> entries.any { it.state == state } }
            WireJson.decodeFromString<LocalSession>(row.payload).copy(state = pendingState ?: row.state, detail = entries.firstOrNull { it.detail != null }?.detail ?: row.detail)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val deletions = recordsFlow("deletion").map { rows -> rows.map { WireJson.decodeFromString<LocalDeletion>(it.payload).copy(state = it.state, detail = it.detail) } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val outbox = settings.map { it.serverUrl }.distinctUntilChanged().flatMapLatest(dao::outboxFlow).stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val ticks = flow { while (currentCoroutineContext().isActive) { emit(Instant.now()); delay(1000) } }

    private fun recordsFlow(kind: String) = settings.map { it.serverUrl }.distinctUntilChanged().flatMapLatest { dao.records(it, kind) }
    private inline fun <reified T> cacheFlow(key: String): Flow<T?> = settings.map { it.serverUrl }.distinctUntilChanged().flatMapLatest { server ->
        dao.cacheFlow(server, key).map { row -> row?.let { runCatching { WireJson.decodeFromString<T>(it.payload) }.getOrNull() } }
    }
    private suspend inline fun <reified T> cache(server: String, key: String, value: T) = dao.cache(CacheEntity(server, key, WireJson.encodeToString(value), System.currentTimeMillis()))
    private fun api(server: String = settings.value.serverUrl) = createApi(server, token = { vault.read(server)?.takeIf { runCatching { Instant.parse(it.expiresAt) > now() }.getOrDefault(false) }?.token })
    fun now(): Instant = Instant.now().plusMillis(settings.value.clockOffsetMillis)
    private fun eventTime(): String {
        // All event writes hold operations. Leave clock-skew margin and make rapid
        // button taps distinct even on a device with millisecond clock precision.
        val millis = maxOf(now().minusSeconds(2).toEpochMilli(), lastEventMillis + 1)
        lastEventMillis = millis
        return Instant.ofEpochMilli(millis).toString()
    }
    private fun uuid() = UUID.randomUUID().toString()

    suspend fun initialize(): Unit = withContext(Dispatchers.IO) { initialization.withLock {
        if (initialized) return@withLock
        mutableSettings.value = preferences.flow.first()
        val server = settings.value.serverUrl
        val identity = dao.identity(server) ?: IdentityEntity(server, settings.value.installId.ifBlank(::uuid)).also { dao.identity(it) }
        preferences.update {
            // Recover a deletion interrupted between the Room commit and preference update.
            val sameIdentity = it.installId == identity.installId
            it.copy(installId = identity.installId,
                consentVersion = it.consentVersion.takeIf { sameIdentity },
                consentAt = it.consentAt.takeIf { sameIdentity })
        }
        mutableSettings.value = preferences.flow.first()
        mutableAuth.value = vault.read(server)
        scope.launch { preferences.flow.collect { mutableSettings.value = it } }
        initialized = true
        scheduleSync()
    } }
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        initialize()
        preferences.update { old ->
            val changed = transform(old)
            require(changed.serverUrl == old.serverUrl) { "Use changeServer to validate a server address." }
            changed.copy(installId = old.installId, clockOffsetMillis = old.clockOffsetMillis)
        }
        mutableSettings.value = preferences.flow.first()
    }
    suspend fun setConsent(enabled: Boolean) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val current = meta.value ?: cacheNow<Meta>(server, "meta")
            if (enabled) requireNotNull(current) { "Connect to the server once to read the current consent policy." }
            preferences.update { it.copy(consentVersion = if(enabled) current?.consentVersion else null, consentAt = if(enabled) now().toString() else null) }
            mutableSettings.value = preferences.flow.first()
            if(!enabled) db.withTransaction {
                dao.outbox(server).filter { it.action == "report" }.forEach { entry ->
                    if(!entry.attempted) { dao.deleteOutbox(entry.sequence); dao.state(server, entry.entityId, "Deleted", "Research consent withdrawn before upload.") }
                    else { dao.outboxState(entry.sequence, "Needs attention", true, "Research consent withdrawn. Undo or delete this report."); dao.state(server, entry.entityId, "Needs attention", "Research consent withdrawn. Undo or delete this report.") }
                }
            }
        }
    }
    suspend fun refreshCatalogue() = withContext(Dispatchers.IO) {
        initialize()
        val server = settings.value.serverUrl
        val service = api(server)
        val before = System.currentTimeMillis()
        val status = request { service.meta() }
        val midpoint = (before + System.currentTimeMillis()) / 2
        val oldMeta = cacheNow<Meta>(server, "meta")
        val needCatalogue = oldMeta?.catalogVersion != status.catalogVersion || dao.cached(server, "stages") == null || dao.cached(server, "routes") == null
        val catalogue = if(needCatalogue) request { service.stages() to service.routes() } else null
        db.withTransaction { cache(server, "meta", status); catalogue?.let { cache(server, "stages", it.first); cache(server, "routes", it.second) } }
        if (settings.value.serverUrl == server) {
            preferences.update { it.copy(clockOffsetMillis = Instant.parse(status.serverTime).toEpochMilli() - midpoint) }
            mutableSettings.value = preferences.flow.first()
        }
    }
    private suspend inline fun <reified T> cacheNow(server: String, key: String): T? = dao.cached(server, key)?.let { WireJson.decodeFromString<T>(it.payload) }
    private suspend fun <T> request(block: suspend () -> T): T = try {
        apiCall(block).also { mutableOnline.value = true; mutableError.value = null }
    } catch (e: ApiFailure) {
        if(e.code == 401) { vault.write(settings.value.serverUrl, null); mutableAuth.value = null }
        mutableOnline.value = !e.retryable || e.code != null; mutableError.value = e.message; throw e
    }
    fun estimates(stageId: String, direction: String): Flow<StageEstimates?> = combine(cacheFlow<StageEstimates>("estimates:$stageId:$direction"), ticks, settings) { value, tick, s ->
        value?.copy(routes = value.routes.map { it.copy(estimate = it.estimate.current(tick.plusMillis(s.clockOffsetMillis))) })
    }
    suspend fun refreshEstimates(stageId: String, direction: String) = withContext(Dispatchers.IO) {
        initialize(); val server = settings.value.serverUrl
        val result = request { api(server).stageEstimates(stageId, direction) }
        cache(server, "estimates:$stageId:$direction", result)
    }
    fun estimateFlow(stageId: String, routeId: String, direction: String): Flow<Estimate?> = combine(cacheFlow<Estimate>("estimate:$stageId:$routeId:$direction"), ticks, settings) { value, tick, s -> value?.current(tick.plusMillis(s.clockOffsetMillis)) }
    suspend fun estimate(stageId: String, routeId: String, direction: String): Estimate = withContext(Dispatchers.IO) {
        initialize(); val server = settings.value.serverUrl
        val result = request { api(server).estimate(stageId, routeId, direction) }
        cache(server, "estimate:$stageId:$routeId:$direction", result)
        result.current(now())
    }
    suspend fun clearEstimates() { initialize(); dao.clearEstimates(settings.value.serverUrl) }
    private suspend fun validateScope(stageId: String, routeId: String, direction: String) {
        val route = cacheNow<List<Route>>(settings.value.serverUrl, "routes")?.find { it.id == routeId }
        require(route != null && stageId in route.stageIds && direction in route.servedDirections(stageId)) { "Choose a route and direction that can be boarded here." }
    }
    suspend fun saveReport(
        stageId: String, routeId: String, direction: String,
        reportId: String? = null, boardedAt: String? = null,
        expectedServer: String? = null, expectedInstallId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            require(expectedServer == null || expectedServer == server) { "The server changed. Review this report again." }
            val install = requireNotNull(dao.identity(server)).installId
            require(expectedInstallId == null || expectedInstallId == install) { "Your data choices changed. Start this report again." }
            val id = reportId ?: uuid()
            UUID.fromString(id)
            val existing = dao.record(server, id)
            if(existing != null) {
                require(existing.kind == "report") { "This identifier is already in use." }
                val saved = WireJson.decodeFromString<BoardingReport>(existing.payload)
                require(saved.installId == install && saved.stageId == stageId && saved.routeId == routeId && saved.direction == direction && (boardedAt == null || saved.boardedAt == boardedAt)) { "Report ID reused with different content." }
                return@withLock id
            }
            validateScope(stageId, routeId, direction)
            val current = requireNotNull(cacheNow<Meta>(server, "meta")) { "Connect once to read the research policy." }
            require(settings.value.consentVersion == current.consentVersion) { "Research consent is required before reporting." }
            val time = boardedAt ?: eventTime()
            val instant = Instant.parse(time)
            require(instant <= now() && instant >= now().minusSeconds(7 * 24 * 60 * 60)) { "Review the boarding time again." }
            val report = BoardingReport(id, install, stageId, routeId, direction, time, current.consentVersion)
            db.withTransaction {
                dao.record(RecordEntity(server, report.id, "report", WireJson.encodeToString(report), createdAt = System.currentTimeMillis()))
                queue(server, report.id, "report", "report:${report.id}", WireJson.encodeToString(report))
            }
            scheduleSync(); report.id
        }
    }
    suspend fun deleteMyData(): String = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val oldId = requireNotNull(dao.identity(server)).installId
            val newId = uuid()
            val deletion = DeletionRequest(uuid(), oldId)
            db.withTransaction {
                dao.deleteReportOutbox(server); dao.deleteReports(server)
                dao.identity(IdentityEntity(server, newId))
                dao.record(RecordEntity(server, deletion.id, "deletion", WireJson.encodeToString(LocalDeletion(deletion)), "Deletion pending", System.currentTimeMillis()))
                queue(server, deletion.id, "delete", "install:$oldId", WireJson.encodeToString(deletion))
            }
            preferences.update { it.copy(installId = newId, consentVersion = null, consentAt = null) }
            mutableSettings.value = preferences.flow.first()
            scheduleSync(); deletion.id
        }
    }
    suspend fun undoReport(id: String) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val row = requireNotNull(dao.record(server, id)) { "This report is no longer on this phone." }
            require(row.kind == "report")
            if(row.state == "Deleted") return@withLock
            val queued = dao.entityOutbox(server, id)
            if(queued.any { it.action == "retract" }) return@withLock
            db.withTransaction {
                if((row.state == "Queued" && queued.isNotEmpty() && queued.none { it.attempted }) || (row.state == "Needs attention" && queued.isEmpty())) {
                    dao.deleteEntityOutbox(server, id)
                    dao.state(server, id, "Deleted")
                } else {
                    // A timeout may conceal remote acceptance. Keep the original immutable
                    // upload ahead of this DELETE until the server acknowledges it.
                    queued.filter { it.action == "report" && it.state == "Needs attention" }.forEach {
                        dao.outboxState(it.sequence, "Queued", it.attempted)
                    }
                    dao.state(server, id, "Deletion pending")
                    queue(server, id, "retract", "report:$id", row.payload)
                }
            }
            scheduleSync()
        }
    }
    suspend fun refreshDeletion(id: String): DeletionStatus = withContext(Dispatchers.IO) {
        initialize(); val server = settings.value.serverUrl
        val result = request { api(server).deletion(id) }
        val row = dao.record(server, id)
        if(row != null) {
            val old = WireJson.decodeFromString<LocalDeletion>(row.payload)
            dao.record(row.copy(payload = WireJson.encodeToString(old.copy(status = result)), state = if(result.status == "completed") "Deleted" else "Deletion pending"))
        }
        result
    }
    suspend fun redeem(code: String): AuthSession = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val result = request { api(server).redeem(RedeemCode(code)) }
            vault.write(server, result); mutableAuth.value = result
            dao.outbox(server).filter { it.state == "Sign in required" }.forEach { dao.outboxState(it.sequence, "Queued", it.attempted) }
            scheduleSync(); result
        }
    }
    suspend fun signOut() = withContext(Dispatchers.IO) { initialize(); operations.withLock { vault.write(settings.value.serverUrl, null); mutableAuth.value = null } }
    suspend fun changeServer(address: String) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val base = validatedServer(address, BuildConfig.DEBUG)
            require(request { createApi(base).health() }.status == "ok") { "This server did not pass its health check." }
            val identity = dao.identity(base) ?: IdentityEntity(base, uuid()).also { dao.identity(it) }
            preferences.update { it.copy(serverUrl = base, installId = identity.installId, consentVersion = null, consentAt = null, clockOffsetMillis = 0, lastSyncAt = null) }
            mutableSettings.value = preferences.flow.first(); mutableAuth.value = vault.read(base)
            scheduleSync()
        }
        refreshCatalogue()
    }
    suspend fun startSession(stageId: String, routeId: String, direction: String, windowId: String? = null): String = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            validateScope(stageId, routeId, direction)
            require(auth.value?.role == "enumerator") { "Sign in with an enumerator access code." }
            val server = settings.value.serverUrl
            require(dao.recordsNow(server, "session").none { WireJson.decodeFromString<LocalSession>(it.payload).endedAt == null }) { "Finish or pause the current session first." }
            val demo = requireNotNull(cacheNow<Meta>(server, "meta")) { "Connect once before starting a session." }.demoMode
            val session = Session(uuid(), stageId, routeId, direction, eventTime(), if(demo) "synthetic" else "field", if(demo) "android-practice" else "android-field")
            val local = LocalSession(session, windowId ?: uuid())
            db.withTransaction {
                dao.record(RecordEntity(server, session.id, "session", WireJson.encodeToString(local), createdAt = System.currentTimeMillis(), sessionId = session.id))
                queue(server, session.id, "session", "session:${session.id}", WireJson.encodeToString(session))
            }
            scheduleSync(); session.id
        }
    }
    fun observations(sessionId: String): Flow<List<LocalObservation>> = recordsFlow("observation").map { rows -> rows.filter { it.sessionId == sessionId }.map { WireJson.decodeFromString<LocalObservation>(it.payload).copy(state = it.state, detail = it.detail) }.sortedBy { it.observation.observedAt } }
    fun vehicles(sessionId: String): Flow<List<LocalVehicle>> = recordsFlow("vehicle").map { rows -> rows.filter { it.sessionId == sessionId }.map { WireJson.decodeFromString<LocalVehicle>(it.payload) }.sortedBy { it.label } }
    suspend fun recordEvent(sessionId: String, eventType: String, occupancy: String, vehicleId: String? = null): String = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val session = requireNotNull(dao.record(server, sessionId)) { "Unknown session." }.let { WireJson.decodeFromString<LocalSession>(it.payload) }
            require(session.endedAt == null) { "This session is closed." }
            require(occupancy in listOf("empty", "seats_available", "standing", "full", "unknown"))
            require(eventType in listOf("arrival", "departure"))
            val vehicles = dao.recordsNow(server, "vehicle").filter { it.sessionId == sessionId }.map { WireJson.decodeFromString<LocalVehicle>(it.payload) }
            val existing = if(eventType == "departure") requireNotNull(vehicles.find { it.id == vehicleId && it.departureId == null && !it.departureUnobserved }) { "Choose an open vehicle." } else null
            val label = existing?.label ?: "Vehicle %02d".format(vehicles.size + 1)
            val event = Observation(uuid(), sessionId, eventTime(), eventType, occupancy, label)
            val vehicle = existing?.copy(departureId = event.id) ?: LocalVehicle(uuid(), sessionId, session.windowId, label, event.id)
            db.withTransaction {
                dao.record(RecordEntity(server, event.id, "observation", WireJson.encodeToString(LocalObservation(event)), createdAt = System.currentTimeMillis(), sessionId = sessionId))
                dao.record(RecordEntity(server, vehicle.id, "vehicle", WireJson.encodeToString(vehicle), createdAt = System.currentTimeMillis(), sessionId = sessionId))
                queue(server, event.id, "observation", "session:$sessionId", WireJson.encodeToString(event))
            }
            scheduleSync(); event.id
        }
    }
    suspend fun markDepartureUnobserved(vehicleId: String) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val row = requireNotNull(dao.record(server, vehicleId))
            val vehicle = WireJson.decodeFromString<LocalVehicle>(row.payload)
            require(vehicle.departureId == null) { "A departure is already recorded." }
            dao.record(row.copy(payload = WireJson.encodeToString(vehicle.copy(departureUnobserved = true))))
        }
    }
    suspend fun closeSession(id: String, paused: Boolean = false) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val row = requireNotNull(dao.record(server, id))
            val local = WireJson.decodeFromString<LocalSession>(row.payload)
            if(local.endedAt != null) return@withLock
            val open = dao.recordsNow(server, "vehicle").filter { it.sessionId == id }.map { WireJson.decodeFromString<LocalVehicle>(it.payload) }.any { it.departureId == null && !it.departureUnobserved }
            require(!open) { "Record each open departure or mark it unobserved first." }
            val end = eventTime()
            db.withTransaction {
                dao.record(row.copy(payload = WireJson.encodeToString(local.copy(endedAt = end, paused = paused)), state = "Queued"))
                queue(server, id, "end", "session:$id", WireJson.encodeToString(CloseSession(end)))
            }
            scheduleSync()
        }
    }
    suspend fun voidObservation(id: String) = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val row = requireNotNull(dao.record(server, id))
            val local = WireJson.decodeFromString<LocalObservation>(row.payload)
            if(local.voided) return@withLock
            db.withTransaction {
                dao.record(row.copy(payload = WireJson.encodeToString(local.copy(voided = true)), state = "Queued"))
                queue(server, id, "void", "session:${local.observation.sessionId}", "{}")
                dao.recordsNow(server, "vehicle").forEach { vehicleRow ->
                    val vehicle = WireJson.decodeFromString<LocalVehicle>(vehicleRow.payload)
                    when { vehicle.arrivalId == id -> dao.deleteRecord(server, vehicle.id)
                        vehicle.departureId == id -> dao.record(vehicleRow.copy(payload = WireJson.encodeToString(vehicle.copy(departureId = null, departureUnobserved = false)))) }
                }
            }
            scheduleSync()
        }
    }
    suspend fun demand(routeId: String, direction: String, windowMinutes: Int): Demand = withContext(Dispatchers.IO) {
        initialize(); val server = settings.value.serverUrl
        val result = request { api(server).demand(routeId, direction, windowMinutes) }
        cache(server, "demand:$routeId:$direction:$windowMinutes", result)
        result
    }
    fun demandFlow(routeId: String, direction: String, windowMinutes: Int): Flow<Demand?> = cacheFlow("demand:$routeId:$direction:$windowMinutes")
    private suspend fun queue(server: String, entityId: String, action: String, groupId: String, payload: String) = dao.enqueue(OutboxEntity(server = server, id = uuid(), entityId = entityId, action = action, groupId = groupId, payload = payload))
    suspend fun syncNow() { initialize(); val retry = uploadPending(); if(retry) scheduleSync() }
    suspend fun retry(id: String) { initialize(); operations.withLock { dao.entityOutbox(settings.value.serverUrl, id).forEach { dao.outboxState(it.sequence, "Queued", it.attempted) } }; scheduleSync() }
    /** True means a transient failure needs WorkManager backoff; auth/correction pauses stay local. */
    suspend fun uploadPending(): Boolean = withContext(Dispatchers.IO) {
        initialize(); operations.withLock {
            val server = settings.value.serverUrl
            val service = api(server)
            var retry = false
            val blocked = mutableSetOf<String>()
            for (entry in dao.outbox(server)) {
                if(entry.groupId in blocked) continue
                if(entry.state in listOf("Needs attention", "Sign in required")) { blocked += entry.groupId; continue }
                val isProtected = entry.action in listOf("session", "observation", "end", "void")
                if(isProtected && auth.value?.role != "enumerator") {
                    dao.outboxState(entry.sequence, "Sign in required", entry.attempted, "Sign in with an enumerator access code.")
                    dao.state(server, entry.entityId, "Sign in required", "Unsent work is kept on this phone."); blocked += entry.groupId; continue
                }
                dao.outboxState(entry.sequence, "Uploading", true)
                dao.state(server, entry.entityId, "Uploading")
                try {
                    var deletionStatus: DeletionStatus? = null
                    apiCall {
                        when(entry.action) {
                            "report" -> requireAccepted(service.reports(ReportBatch(listOf(WireJson.decodeFromString(entry.payload)))), entry.entityId)
                            "retract" -> service.retract(entry.entityId, WireJson.decodeFromString<BoardingReport>(entry.payload).installId)
                            "delete" -> { deletionStatus = service.delete(WireJson.decodeFromString(entry.payload)) }
                            "session" -> service.session(WireJson.decodeFromString(entry.payload))
                            "observation" -> requireAccepted(service.observations(ObservationBatch(listOf(WireJson.decodeFromString(entry.payload)))), entry.entityId)
                            "end" -> service.end(entry.entityId, WireJson.decodeFromString(entry.payload))
                            "void" -> service.void(entry.entityId)
                            else -> error("Unknown queued action.")
                        }
                    }
                    db.withTransaction {
                        dao.deleteOutbox(entry.sequence)
                        val remaining = dao.entityOutbox(server, entry.entityId)
                        val state = if(entry.action in listOf("retract", "delete")) "Deleted" else if(remaining.isNotEmpty()) "Queued" else "Synced"
                        if(entry.action == "delete") {
                            val row = dao.record(server, entry.entityId)
                            if(row != null) {
                                val old = WireJson.decodeFromString<LocalDeletion>(row.payload)
                                dao.record(row.copy(payload = WireJson.encodeToString(old.copy(status = deletionStatus)), state = state))
                            }
                        } else dao.state(server, entry.entityId, state)
                    }
                    preferences.update { it.copy(lastSyncAt = now().toString()) }
                    mutableOnline.value = true
                } catch (e: CancellationException) { throw e }
                catch (e: ApiFailure) {
                    if(e.code == 410) {
                        db.withTransaction {
                            dao.deleteOutbox(entry.sequence)
                            dao.state(server, entry.entityId, if(e.message == "daily_limit") "Needs attention" else "Deleted", e.message)
                        }
                        continue
                    }
                    val state = when { e.code == 401 -> "Sign in required"; e.retryable -> "Will retry"; else -> "Needs attention" }
                    dao.outboxState(entry.sequence, state, true, e.message)
                    dao.state(server, entry.entityId, state, e.message)
                    if(e.code == 401) { vault.write(server, null); mutableAuth.value = null }
                    if(e.retryable) retry = true
                    mutableOnline.value = e.code != null
                    blocked += entry.groupId
                }
            }
            retry
        }
    }
    private fun requireAccepted(result: BatchResult, id: String) {
        val rejection = result.rejected.find { it.id == id }
        if(rejection != null) throw ApiFailure(410, rejection.reason)
        if(id !in result.acceptedIds && id !in result.duplicateIds) throw ApiFailure(message = "Server did not acknowledge this record.", retryable = true)
    }
}

fun validatedServer(address: String, debug: Boolean): String {
    val url = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid server URL.")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "Use a server origin without credentials, path, query or fragment." }
    require(url.isHttps || (debug && url.scheme == "http" && url.host in setOf("10.0.2.2", "localhost"))) { "Use HTTPS. Debug HTTP is allowed only for 10.0.2.2 and localhost." }
    return url.toString()
}
