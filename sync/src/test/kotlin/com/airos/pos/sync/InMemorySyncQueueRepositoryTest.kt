package com.airos.pos.sync

import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InMemorySyncQueueRepositoryTest {
    @Test
    fun enqueue_andNextPending_returnQueuedItemsInOrder() = runBlocking {
        val repository = InMemorySyncQueueRepository()
        repository.enqueue(
            SyncItem(
                id = "1",
                aggregateType = "ticket",
                aggregateId = "t-1",
                action = "add_item",
                payloadJson = "{}",
                state = SyncState.QUEUED,
                attemptCount = 0,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
            ),
        )
        repository.enqueue(
            SyncItem(
                id = "2",
                aggregateType = "ticket",
                aggregateId = "t-2",
                action = "send_to_kitchen",
                payloadJson = "{}",
                state = SyncState.FAILED,
                attemptCount = 1,
                createdAtEpochMillis = 20L,
                updatedAtEpochMillis = 20L,
            ),
        )

        val pending = repository.nextPending(limit = 10)

        assertThat(pending.map { it.id }).containsExactly("1", "2").inOrder()
    }
}
