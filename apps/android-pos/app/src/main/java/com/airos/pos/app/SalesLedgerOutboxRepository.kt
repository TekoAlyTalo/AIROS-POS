package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.dao.SalesLedgerOutboxDao
import com.airos.pos.core.database.entity.SalesLedgerOutboxLocalEntity
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.LedgerFinalizeSaleResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SalesOutboxTag = "AIROS_SALES_OUTBOX"
private const val SalesOutboxStatusQueued = "queued"
private const val SalesOutboxStatusSyncing = "syncing"
private const val SalesOutboxStatusSynced = "synced"
private const val SalesOutboxStatusFailed = "failed"
private const val SalesOutboxStatusBlocked = "blocked"

data class SalesLedgerOutboxDraft(
    val sourcePosEventId: String,
    val receiptNumber: String,
    val ticketId: String?,
    val tableId: String?,
    val terminalId: String?,
    val restaurantId: String?,
    val cashierStaffId: String?,
    val totalCents: Int,
    val requestJson: String,
    val createdAtEpochMillis: Long,
)

sealed class SalesLedgerOutboxSyncOutcome {
    data class Delivered(val response: LedgerFinalizeSaleResponse) : SalesLedgerOutboxSyncOutcome()
    data class RetryableFailure(val message: String) : SalesLedgerOutboxSyncOutcome()
    data class Blocked(val message: String) : SalesLedgerOutboxSyncOutcome()
    data object NotFound : SalesLedgerOutboxSyncOutcome()
}

class SalesLedgerOutboxRepository(
    private val dao: SalesLedgerOutboxDao,
    private val ledgerHttpClient: AirosPosLedgerHttpClient,
) {
    private val syncMutex = Mutex()

    fun observeUnresolvedCount(): Flow<Int> = dao.observeUnresolvedCount()

    suspend fun enqueue(draft: SalesLedgerOutboxDraft): PosResult<Unit> {
        return try {
            val inserted = dao.insert(draft.toEntity())
            if (inserted == -1L) {
                val message =
                    "Sales ledger outbox duplicate rejected: receipt=${draft.receiptNumber} sourcePosEventId=${draft.sourcePosEventId}"
                Log.e(SalesOutboxTag, message)
                return PosResult.Failure(message)
            } else {
                Log.i(
                    SalesOutboxTag,
                    "enqueue: durable sale sync queued receipt=${draft.receiptNumber} sourcePosEventId=${draft.sourcePosEventId}",
                )
            }
            PosResult.Success(Unit)
        } catch (t: Throwable) {
            val message = "Sales ledger outbox persist failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            Log.e(SalesOutboxTag, message, t)
            PosResult.Failure(message)
        }
    }

    suspend fun syncOne(sourcePosEventId: String): SalesLedgerOutboxSyncOutcome = syncMutex.withLock {
        val entity = dao.load(sourcePosEventId) ?: return@withLock SalesLedgerOutboxSyncOutcome.NotFound
        syncOneUnlocked(entity)
    }

    suspend fun syncPendingNow(limit: Int = 25): PosResult<Unit> = syncMutex.withLock {
        val pending = dao.pending(limit)
        if (pending.isEmpty()) {
            return@withLock PosResult.Success(Unit)
        }

        for (entity in pending) {
            syncOneUnlocked(entity)
        }
        PosResult.Success(Unit)
    }

    private suspend fun syncOneUnlocked(entity: SalesLedgerOutboxLocalEntity): SalesLedgerOutboxSyncOutcome {
        if (entity.syncStatus == SalesOutboxStatusSynced) {
            return SalesLedgerOutboxSyncOutcome.NotFound
        }

        val now = System.currentTimeMillis()
        dao.markSyncing(entity.sourcePosEventId, now)
        Log.i(
            SalesOutboxTag,
            "sync: sending receipt=${entity.receiptNumber} sourcePosEventId=${entity.sourcePosEventId} attempt=${entity.attemptCount + 1}",
        )

        return when (val result = ledgerHttpClient.finalizeSaleJson(entity.requestJson, entity.receiptNumber)) {
            is PosResult.Success -> {
                val response = result.value
                dao.markSynced(
                    sourcePosEventId = entity.sourcePosEventId,
                    serverSaleId = response.sale_id,
                    receiptSnapshotId = response.receipt_snapshot_id,
                    publicUrlPath = response.public_url_path,
                    deliveryTokenIdsCsv = response.delivery_token_ids.joinToString(","),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                Log.i(
                    SalesOutboxTag,
                    "sync: delivered receipt=${entity.receiptNumber} saleId=${response.sale_id} publicUrl=${response.public_url_path}",
                )
                SalesLedgerOutboxSyncOutcome.Delivered(response)
            }
            is PosResult.Failure -> {
                val message = result.message.take(1_000)
                if (isRetryableLedgerSyncFailure(message)) {
                    dao.markFailed(
                        sourcePosEventId = entity.sourcePosEventId,
                        lastError = message,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    )
                    Log.w(
                        SalesOutboxTag,
                        "sync: retryable failure receipt=${entity.receiptNumber} sourcePosEventId=${entity.sourcePosEventId} reason=$message",
                    )
                    SalesLedgerOutboxSyncOutcome.RetryableFailure(message)
                } else {
                    dao.markBlocked(
                        sourcePosEventId = entity.sourcePosEventId,
                        lastError = message,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    )
                    Log.e(
                        SalesOutboxTag,
                        "sync: blocked receipt=${entity.receiptNumber} sourcePosEventId=${entity.sourcePosEventId} reason=$message",
                    )
                    SalesLedgerOutboxSyncOutcome.Blocked(message)
                }
            }
        }
    }

    private fun SalesLedgerOutboxDraft.toEntity(): SalesLedgerOutboxLocalEntity =
        SalesLedgerOutboxLocalEntity(
            sourcePosEventId = sourcePosEventId,
            receiptNumber = receiptNumber,
            ticketId = ticketId,
            tableId = tableId,
            terminalId = terminalId,
            restaurantId = restaurantId,
            cashierStaffId = cashierStaffId,
            totalCents = totalCents,
            requestJson = requestJson,
            syncStatus = SalesOutboxStatusQueued,
            attemptCount = 0,
            lastError = null,
            serverSaleId = null,
            receiptSnapshotId = null,
            publicUrlPath = null,
            deliveryTokenIdsCsv = "",
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            lastAttemptAtEpochMillis = null,
            syncedAtEpochMillis = null,
        )
}

fun isRetryableLedgerSyncFailure(message: String): Boolean {
    if (message.contains("base URL is empty", ignoreCase = true)) return true
    if (message.contains("connection open failed", ignoreCase = true)) return true
    if (message.contains("request failed at", ignoreCase = true)) return true
    if (message.contains("response parse failed", ignoreCase = true)) return true

    val httpStatusMatch = Regex("""HTTP (\d{3})""").find(message)
    if (httpStatusMatch != null) {
        val code = httpStatusMatch.groupValues[1].toIntOrNull() ?: 0
        return code >= 500
    }
    return false
}
