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
        return refreshActiveSessionFromBackend(scope, staffId, staffName, markErrors = true)
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
                val now = System.currentTimeMillis()
                attendanceDao.markEventSyncing(event.eventId, batchId, now)
                when (val result = client.syncAttendanceEvent(event.toSyncEvent())) {
                    is PosResult.Success -> {
                        attendanceDao.markEventSynced(event.eventId, batchId, System.currentTimeMillis())
                        touchedStaff[event.staffId] = event.staffName
                        syncedAnyEvent = true
                    }
                    is PosResult.Failure -> {
                        attendanceDao.markEventFailed(event.eventId, batchId, System.currentTimeMillis(), result.message)
                        markSyncUnavailable(scope, OfflineSyncNotice, batchId)
                        return@withLock PosResult.Failure(OfflineSyncNotice)
                    }
                }
            }
            pendingEvents = attendanceDao.pendingEvents(scope.metadataKey, limit = 25)
        }

        if (syncedAnyEvent) {
            markSyncSuccess(scope, batchStartedAt, batchId)
        } else {
            markSyncIdleIfSettled(scope)
        }
        touchedStaff.forEach { (staffId, staffName) ->
            refreshActiveSessionFromBackend(scope, staffId, staffName, markErrors = false)
        }
        PosResult.Success(Unit)
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

    private suspend fun refreshActiveSessionFromBackend(
        scope: AttendanceScope,
        staffId: String,
        staffName: String,
        markErrors: Boolean,
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
                markSyncIdleIfSettled(scope)
                PosResult.Success(Unit)
            }
            is PosResult.Failure -> {
                if (markErrors) {
                    markSyncUnavailable(scope, OfflineSyncNotice)
                }
                PosResult.Failure(OfflineSyncNotice)
            }
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
