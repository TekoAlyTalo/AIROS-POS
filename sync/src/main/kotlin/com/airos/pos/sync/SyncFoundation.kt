package com.airos.pos.sync

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.domain.SyncQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class QueuedWriteRequest(
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
)

class InMemorySyncQueueRepository : SyncQueueRepository {
    private val queue = MutableStateFlow<List<SyncItem>>(emptyList())

    override fun observeQueue(): Flow<List<SyncItem>> = queue

    override suspend fun enqueue(item: SyncItem): PosResult<Unit> {
        queue.value = queue.value + item
        return PosResult.Success(Unit)
    }

    override suspend fun updateState(itemId: String, state: SyncState, lastError: String?): PosResult<Unit> {
        queue.value = queue.value.map { current ->
            if (current.id != itemId) {
                current
            } else {
                current.copy(
                    state = state,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    lastError = lastError,
                    attemptCount = if (state == SyncState.FAILED) current.attemptCount + 1 else current.attemptCount,
                )
            }
        }
        return PosResult.Success(Unit)
    }

    override suspend fun nextPending(limit: Int): List<SyncItem> {
        return queue.value
            .filter { it.state == SyncState.QUEUED || it.state == SyncState.FAILED }
            .sortedBy { it.createdAtEpochMillis }
            .take(limit)
    }
}

class SyncCoordinator(
    private val syncQueueRepository: SyncQueueRepository,
) {
    fun observeQueueDepth(): Flow<Int> = syncQueueRepository.observeQueue().map { it.size }

    suspend fun scheduleImmediateDrain(): PosResult<Unit> {
        // TODO-CONTRACT: Wire real backend sync dispatch once queue item contracts are finalized.
        return PosResult.Success(Unit)
    }
}
