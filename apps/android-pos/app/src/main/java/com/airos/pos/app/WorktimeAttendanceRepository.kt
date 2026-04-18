package com.airos.pos.app

import androidx.room.withTransaction
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.database.entity.AttendanceActiveSessionLocalEntity
import com.airos.pos.core.database.entity.AttendanceEventLocalEntity
import com.airos.pos.core.database.entity.AttendanceSyncMetadataLocalEntity
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorktimeEffectiveAttendanceState(
    val activeSession: WorktimeActiveSession? = null,
    val syncMetadata: WorktimeAttendanceSyncMetadata = WorktimeAttendanceSyncMetadata(),
    val unresolvedEventCount: Int = 0,
)

data class WorktimeAttendanceSyncMetadata(
    val lastSuccessfulSyncAtEpochMillis: Long? = null,
    val lastSeenTerminalSequence: Long = 0,
    val lastSyncBatchId: String? = null,
    val lastError: String? = null,
    val syncState: String = AttendanceSyncStateIdle,
)

private data class AttendanceScope(
    val ownerAccountId: String?,
    val restaurantKey: String,
    val terminalId: String,
) {
    val metadataKey: String = listOf(ownerAccountId ?: "owner:none", restaurantKey, terminalId).joinToString("|")

    fun sessionKey(staffId: String): String {
        return listOf(ownerAccountId ?: "owner:none", restaurantKey, staffId).joinToString("|")
    }
}

private const val AttendanceActionClockIn = "clock_in"
private const val AttendanceActionClockOut = "clock_out"
private const val AttendanceSourceAndroidPos = "android-pos"
private const val AttendanceSyncStatusQueued = "queued"
private const val AttendanceSyncStateIdle = "idle"
private const val AttendanceSyncStateQueued = "queued"
private const val AttendanceSyncStateSyncing = "syncing"
private const val AttendanceSyncStateOffline = "offline"
// Upload succeeded but authoritative active-session refresh for at least one touched
// staff failed — we have not yet confirmed the backend view matches the local effective
// state. Distinct from idle so observers can see that reconciliation is outstanding.
private const val AttendanceSyncStateReconciling = "reconciling"
// One or more events were rejected by the backend with a non-retriable contract/schema
// failure and have been parked (syncStatus = 'blocked'). They will NOT be retried until
// intervention — keeping them in a retry loop would be noise, not progress.
private const val AttendanceSyncStateContractBlocked = "contract_blocked"
private const val OfflineSyncNotice = "Offline, syncing later"
private const val DefaultRestaurantKey = "ravintola_default"
private const val DefaultTerminalId = "android-pos-terminal"

class WorktimeAttendanceRepository(
    private val database: AirosPosDatabase,
    private val client: WorktimeAttendanceClient,
    private val ownerAccountIdProvider: () -> String? = { null },
    private val restaurantKeyProvider: () -> String? = { DefaultRestaurantKey },
    private val terminalIdProvider: () -> String? = { DefaultTerminalId },
    private val pollIntervalMillis: Long = 15_000L,
) {
    private val attendanceDao = database.attendanceDao()
    private val syncMutex = Mutex()

    fun observeAttendance(): Flow<WorktimeAttendanceSnapshot> = flow {
        emit(WorktimeAttendanceSnapshot())
        while (true) {
            val scope = currentScope()
            syncPendingNow()
            when (val snapshot = client.fetchAttendanceSnapshot(scope.restaurantKey)) {
                is PosResult.Success -> {
                    markSyncIdleIfSettled(scope)
                    emit(snapshot.value)
                }
                is PosResult.Failure -> markSyncUnavailable(scope, OfflineSyncNotice)
            }
            delay(pollIntervalMillis)
        }
    }

    fun observeCurrentUserState(staffId: String): Flow<WorktimeEffectiveAttendanceState> {
        val scope = currentScope()
        return combine(
            attendanceDao.observeActiveSession(scope.sessionKey(staffId)),
            attendanceDao.observeSyncMetadata(scope.metadataKey),
            attendanceDao.observeUnresolvedEventCount(scope.metadataKey),
        ) { activeSession, metadata, unresolvedCount ->
            WorktimeEffectiveAttendanceState(
                activeSession = activeSession?.toModel(),
                syncMetadata = metadata?.toModel() ?: WorktimeAttendanceSyncMetadata(),
                unresolvedEventCount = unresolvedCount,
            )
        }
    }

    suspend fun clockIn(staffId: String, staffName: String): PosResult<Unit> {
        val scope = currentScope()
        val sessionKey = scope.sessionKey(staffId)
        val active = attendanceDao.loadActiveSession(sessionKey)
        if (active == null) {
            appendLocalEvent(scope, staffId, staffName, AttendanceActionClockIn)
        }
        syncPendingNow()
        return PosResult.Success(Unit)
    }

    suspend fun clockOut(staffId: String, staffName: String): PosResult<Unit> {
        val scope = currentScope()
        val sessionKey = scope.sessionKey(staffId)
        val active = attendanceDao.loadActiveSession(sessionKey)
        if (active != null) {
            appendLocalEvent(scope, staffId, staffName, AttendanceActionClockOut)
        }
        syncPendingNow()
        return PosResult.Success(Unit)
    }

    suspend fun syncAndRefreshCurrentUser(staffId: String, staffName: String): PosResult<Unit> {
        val scope = currentScope()
        syncPendingNow()
        return when (val result = refreshActiveSessionFromBackend(scope, staffId, staffName)) {
            is PosResult.Success -> {
                // If we were stuck in reconciling (previous batch failed to confirm server
                // state), a successful backend refresh clears it to idle.
                val metadata = attendanceDao.loadSyncMetadata(scope.metadataKey)
                if (metadata?.syncState == AttendanceSyncStateReconciling) {
                    markSyncState(scope, AttendanceSyncStateIdle, metadata.lastSyncBatchId, lastError = null)
                } else {
                    markSyncIdleIfSettled(scope)
                }
                PosResult.Success(Unit)
            }
            is PosResult.Failure -> {
                markSyncUnavailable(scope, OfflineSyncNotice)
                PosResult.Failure(result.message)
            }
        }
    }

    suspend fun syncPendingNow(): PosResult<Unit> = syncMutex.withLock {
        val scope = currentScope()
        val batchId = UUID.randomUUID().toString()
        val batchStartedAt = System.currentTimeMillis()
        val touchedStaff = linkedMapOf<String, String>()
        var syncedAnyEvent = false
        var pendingEvents = attendanceDao.pendingEvents(scope.metadataKey, limit = 25)

        if (pendingEvents.isEmpty()) {
            markSyncIdleIfSettled(scope)
            return@withLock PosResult.Success(Unit)
        }
        markSyncState(scope, AttendanceSyncStateSyncing, batchId, lastError = null)

        while (pendingEvents.isNotEmpty()) {
            for (event in pendingEvents) {
                val startedAt = System.currentTimeMillis()
                attendanceDao.markEventSyncing(event.eventId, batchId, startedAt)
                when (val outcome = client.syncAttendanceEvent(event.toSyncEvent())) {
                    AttendanceSyncOutcome.Delivered,
                    AttendanceSyncOutcome.ConflictReconciled -> {
                        attendanceDao.markEventSynced(event.eventId, batchId, System.currentTimeMillis())
                        touchedStaff[event.staffId] = event.staffName
                        syncedAnyEvent = true
                    }
                    is AttendanceSyncOutcome.TransientFailure -> {
                        attendanceDao.markEventFailed(event.eventId, batchId, System.currentTimeMillis(), outcome.message)
                        markSyncUnavailable(scope, OfflineSyncNotice, batchId)
                        return@withLock PosResult.Failure(OfflineSyncNotice)
                    }
                    is AttendanceSyncOutcome.RetriableServerFailure -> {
                        attendanceDao.markEventFailed(event.eventId, batchId, System.currentTimeMillis(), outcome.message)
                        markSyncUnavailable(scope, OfflineSyncNotice, batchId)
                        return@withLock PosResult.Failure(OfflineSyncNotice)
                    }
                    is AttendanceSyncOutcome.NonRetriableFailure -> {
                        // Park this event out of the retry pool so it will not be resubmitted
                        // forever. Surface the fact via contract_blocked so the metadata state
                        // reflects that there is parked data needing attention.
                        attendanceDao.markEventBlocked(event.eventId, batchId, System.currentTimeMillis(), outcome.message)
                        markSyncState(scope, AttendanceSyncStateContractBlocked, batchId, outcome.message)
                        return@withLock PosResult.Failure(outcome.message)
                    }
                }
            }
            pendingEvents = attendanceDao.pendingEvents(scope.metadataKey, limit = 25)
        }

        if (!syncedAnyEvent) {
            markSyncIdleIfSettled(scope)
            return@withLock PosResult.Success(Unit)
        }

        // Upload phase finished; enter reconciliation. We only mark the sync fully
        // settled once the authoritative active-session view has been refreshed for
        // every touched staff. A refresh failure keeps us in 'reconciling' so observers
        // can see that server truth has not yet been confirmed.
        markSyncState(scope, AttendanceSyncStateReconciling, batchId, lastError = null)
        var reconciliationError: String? = null
        touchedStaff.forEach { (staffId, staffName) ->
            when (val result = refreshActiveSessionFromBackend(scope, staffId, staffName)) {
                is PosResult.Success -> Unit
                is PosResult.Failure -> if (reconciliationError == null) reconciliationError = result.message
            }
        }
        if (reconciliationError == null) {
            markSyncSuccess(scope, batchStartedAt, batchId)
            PosResult.Success(Unit)
        } else {
            markSyncState(scope, AttendanceSyncStateReconciling, batchId, reconciliationError)
            PosResult.Failure(reconciliationError ?: OfflineSyncNotice)
        }
    }

    private suspend fun appendLocalEvent(
        scope: AttendanceScope,
        staffId: String,
        staffName: String,
        action: String,
    ): AttendanceEventLocalEntity {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val currentMetadata = attendanceDao.loadSyncMetadata(scope.metadataKey)
            val nextSequence = maxOf(
                attendanceDao.maxTerminalSequence(scope.metadataKey),
                currentMetadata?.lastSeenTerminalSequence ?: 0,
            ) + 1
            val event = AttendanceEventLocalEntity(
                eventId = UUID.randomUUID().toString(),
                metadataKey = scope.metadataKey,
                ownerAccountId = scope.ownerAccountId,
                restaurantKey = scope.restaurantKey,
                terminalId = scope.terminalId,
                staffId = staffId,
                staffName = staffName,
                action = action,
                occurredAtEpochMillis = now,
                source = AttendanceSourceAndroidPos,
                syncStatus = AttendanceSyncStatusQueued,
                terminalSequenceNumber = nextSequence,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                syncBatchId = null,
                lastError = null,
            )

            attendanceDao.insertEvent(event)
            if (action == AttendanceActionClockIn) {
                attendanceDao.upsertActiveSession(
                    AttendanceActiveSessionLocalEntity(
                        sessionKey = scope.sessionKey(staffId),
                        ownerAccountId = scope.ownerAccountId,
                        restaurantKey = scope.restaurantKey,
                        staffId = staffId,
                        staffName = staffName,
                        status = "active",
                        startedAtIso = Instant.ofEpochMilli(now).toString(),
                        startedAtEpochMillis = now,
                        serverSessionId = null,
                        updatedAtEpochMillis = now,
                    ),
                )
            } else {
                attendanceDao.deleteActiveSession(scope.sessionKey(staffId))
            }

            attendanceDao.upsertSyncMetadata(
                (currentMetadata ?: scope.emptyMetadata(now)).copy(
                    lastSeenTerminalSequence = nextSequence,
                    lastError = null,
                    syncState = AttendanceSyncStateQueued,
                    updatedAtEpochMillis = now,
                ),
            )
            event
        }
    }

    // Fetch the authoritative active-session snapshot for one staff and mirror it into
    // local state. Intentionally does NOT flip the sync metadata state — the caller is
    // responsible for deciding what "refresh failed" or "refresh succeeded" means in
    // the context of the wider sync lifecycle (upload vs. idle refresh vs. reconciling).
    private suspend fun refreshActiveSessionFromBackend(
        scope: AttendanceScope,
        staffId: String,
        staffName: String,
    ): PosResult<Unit> {
        return when (val result = client.fetchActiveSessionForStaff(staffId, scope.restaurantKey)) {
            is PosResult.Success -> {
                val now = System.currentTimeMillis()
                val activeSession = result.value
                if (activeSession == null) {
                    attendanceDao.deleteActiveSession(scope.sessionKey(staffId))
                } else {
                    attendanceDao.upsertActiveSession(
                        AttendanceActiveSessionLocalEntity(
                            sessionKey = scope.sessionKey(staffId),
                            ownerAccountId = scope.ownerAccountId,
                            restaurantKey = activeSession.restaurantKey ?: scope.restaurantKey,
                            staffId = activeSession.staffId,
                            staffName = activeSession.staffName.ifBlank { staffName },
                            status = activeSession.status,
                            startedAtIso = activeSession.startedAt,
                            startedAtEpochMillis = activeSession.startedAtEpochMillis ?: now,
                            serverSessionId = activeSession.sessionId,
                            updatedAtEpochMillis = now,
                        ),
                    )
                }
                PosResult.Success(Unit)
            }
            is PosResult.Failure -> PosResult.Failure(result.message)
        }
    }

    private suspend fun markSyncSuccess(scope: AttendanceScope, syncedAt: Long, batchId: String) {
        val metadata = attendanceDao.loadSyncMetadata(scope.metadataKey) ?: scope.emptyMetadata(syncedAt)
        attendanceDao.upsertSyncMetadata(
            metadata.copy(
                lastSuccessfulSyncAtEpochMillis = syncedAt,
                lastSeenTerminalSequence = maxOf(metadata.lastSeenTerminalSequence, attendanceDao.maxTerminalSequence(scope.metadataKey)),
                lastSyncBatchId = batchId,
                lastError = null,
                syncState = AttendanceSyncStateIdle,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun markSyncState(
        scope: AttendanceScope,
        syncState: String,
        batchId: String?,
        lastError: String?,
    ) {
        val now = System.currentTimeMillis()
        val metadata = attendanceDao.loadSyncMetadata(scope.metadataKey) ?: scope.emptyMetadata(now)
        attendanceDao.upsertSyncMetadata(
            metadata.copy(
                lastSyncBatchId = batchId ?: metadata.lastSyncBatchId,
                lastError = lastError,
                syncState = syncState,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private suspend fun markSyncUnavailable(
        scope: AttendanceScope,
        lastError: String,
        batchId: String? = null,
    ) {
        markSyncState(scope, AttendanceSyncStateOffline, batchId, lastError)
    }

    private suspend fun markSyncIdleIfSettled(scope: AttendanceScope) {
        val metadata = attendanceDao.loadSyncMetadata(scope.metadataKey) ?: return
        // contract_blocked is sticky — parked events remain parked until explicit
        // intervention, so we must not silently clear that state just because the
        // retry queue happens to be empty (blocked events are excluded from the
        // retry pool by design).
        if (metadata.syncState == AttendanceSyncStateContractBlocked) return
        // reconciling is sticky via this path — clearing it requires a confirmed backend
        // refresh (see syncAndRefreshCurrentUser), not just an empty retry queue.
        if (metadata.syncState == AttendanceSyncStateReconciling) return
        val pending = attendanceDao.pendingEvents(scope.metadataKey, limit = 1)
        if (pending.isEmpty() && metadata.syncState != AttendanceSyncStateIdle) {
            attendanceDao.upsertSyncMetadata(
                metadata.copy(
                    lastError = null,
                    syncState = AttendanceSyncStateIdle,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun currentScope(): AttendanceScope {
        return AttendanceScope(
            ownerAccountId = ownerAccountIdProvider()?.trim()?.ifBlank { null },
            restaurantKey = restaurantKeyProvider()?.trim()?.ifBlank { null } ?: DefaultRestaurantKey,
            terminalId = terminalIdProvider()?.trim()?.ifBlank { null } ?: DefaultTerminalId,
        )
    }

    private fun AttendanceScope.emptyMetadata(now: Long): AttendanceSyncMetadataLocalEntity {
        return AttendanceSyncMetadataLocalEntity(
            metadataKey = metadataKey,
            ownerAccountId = ownerAccountId,
            restaurantKey = restaurantKey,
            terminalId = terminalId,
            lastSuccessfulSyncAtEpochMillis = null,
            lastSeenTerminalSequence = 0,
            lastSyncBatchId = null,
            lastError = null,
            syncState = AttendanceSyncStateIdle,
            updatedAtEpochMillis = now,
        )
    }

    private fun AttendanceEventLocalEntity.toSyncEvent(): WorktimeAttendanceSyncEvent {
        return WorktimeAttendanceSyncEvent(
            eventId = eventId,
            ownerAccountId = ownerAccountId,
            restaurantKey = restaurantKey,
            terminalId = terminalId,
            staffId = staffId,
            staffName = staffName,
            action = action,
            occurredAtEpochMillis = occurredAtEpochMillis,
            source = source,
            terminalSequenceNumber = terminalSequenceNumber,
        )
    }

    private fun AttendanceActiveSessionLocalEntity.toModel(): WorktimeActiveSession {
        return WorktimeActiveSession(
            sessionId = serverSessionId ?: -1,
            staffId = staffId,
            staffName = staffName,
            restaurantKey = restaurantKey,
            status = status,
            startedAt = startedAtIso,
            startedAtEpochMillis = startedAtEpochMillis,
        )
    }

    private fun AttendanceSyncMetadataLocalEntity.toModel(): WorktimeAttendanceSyncMetadata {
        return WorktimeAttendanceSyncMetadata(
            lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            lastSeenTerminalSequence = lastSeenTerminalSequence,
            lastSyncBatchId = lastSyncBatchId,
            lastError = lastError,
            syncState = syncState,
        )
    }
}
