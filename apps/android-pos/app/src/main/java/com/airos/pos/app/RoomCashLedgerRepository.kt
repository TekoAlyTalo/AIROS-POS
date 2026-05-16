package com.airos.pos.app

import androidx.room.withTransaction
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.database.entity.CashDrawerLocalEntity
import com.airos.pos.core.database.entity.CashEventLocalEntity
import com.airos.pos.core.model.CashCountResult
import com.airos.pos.core.model.CashDrawer
import com.airos.pos.core.model.CashDrawerStatus
import com.airos.pos.core.model.CashEvent
import com.airos.pos.core.model.CashEventType
import com.airos.pos.core.model.CashExpectedState
import com.airos.pos.core.model.CashLedgerState
import com.airos.pos.domain.CashLedgerRepository
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomCashLedgerRepository(
    private val database: AirosPosDatabase,
) : CashLedgerRepository {
    private val dao = database.cashLedgerDao()

    override fun observeState(drawerId: String): Flow<CashLedgerState> {
        return combine(
            dao.observeDrawer(drawerId),
            dao.observeEvents(drawerId),
        ) { drawer, events ->
            buildState(
                drawer = drawer ?: defaultDrawerEntity(drawerId = drawerId, now = System.currentTimeMillis()),
                events = events,
            )
        }
    }

    override suspend fun recordCashOpened(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        source: String,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_OPENED,
            amountCents = amountCents,
            deltaCents = 0,
            staffId = staffId,
            staffName = staffName,
            sourceType = source,
            sourceId = null,
            idempotencyKey = null,
            note = "Opening cash truth: $source",
        )
    }

    override suspend fun recordCashCount(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        note: String?,
    ): PosResult<CashCountResult> {
        return when (
            val result = insertCashEvent(
                drawerId = drawerId,
                type = CashEventType.CASH_COUNT_RECORDED,
                amountCents = amountCents,
                deltaCents = 0,
                staffId = staffId,
                staffName = staffName,
                sourceType = "manual_cash_count",
                sourceId = null,
                idempotencyKey = null,
                note = note,
            )
        ) {
            is PosResult.Success -> PosResult.Success(CashCountResult(result.value, amountCents))
            is PosResult.Failure -> result
        }
    }

    override suspend fun recordCashSale(
        drawerId: String,
        amountCents: Int,
        sourceEventId: String,
        receiptNumber: String?,
        staffId: String?,
        staffName: String?,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_SALE_RECEIVED,
            amountCents = amountCents,
            deltaCents = amountCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = "sale",
            sourceId = sourceEventId,
            idempotencyKey = "cash-sale:$sourceEventId",
            note = receiptNumber?.let { "Receipt $it" },
        )
    }

    override suspend fun recordCashRefund(
        drawerId: String,
        amountCents: Int,
        sourceEventId: String,
        staffId: String?,
        staffName: String?,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_REFUND_PAID,
            amountCents = amountCents,
            deltaCents = -amountCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = "refund",
            sourceId = sourceEventId,
            idempotencyKey = "cash-refund:$sourceEventId",
            note = null,
        )
    }

    override suspend fun recordCashAdded(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        reason: String?,
        sourceEventId: String?,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_ADDED,
            amountCents = amountCents,
            deltaCents = amountCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = "manual_cash_added",
            sourceId = sourceEventId,
            idempotencyKey = sourceEventId?.let { "cash-added:$it" },
            note = reason,
        )
    }

    override suspend fun recordCashRemoved(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        reason: String?,
        sourceEventId: String?,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_REMOVED,
            amountCents = amountCents,
            deltaCents = -amountCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = "manual_cash_removed",
            sourceId = sourceEventId,
            idempotencyKey = sourceEventId?.let { "cash-removed:$it" },
            note = reason,
        )
    }

    override suspend fun recordCashClosed(
        drawerId: String,
        amountCents: Int?,
        staffId: String,
        staffName: String?,
        countedAtClose: Boolean,
        note: String?,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_CLOSED,
            amountCents = amountCents,
            deltaCents = 0,
            staffId = staffId,
            staffName = staffName,
            sourceType = if (countedAtClose) "close_with_count" else "close_without_new_count",
            sourceId = null,
            idempotencyKey = null,
            note = note,
        )
    }

    override suspend fun recordTruthMissing(
        drawerId: String,
        staffId: String?,
        staffName: String?,
        reason: String,
    ): PosResult<CashEvent> {
        return insertCashEvent(
            drawerId = drawerId,
            type = CashEventType.CASH_TRUTH_MISSING,
            amountCents = null,
            deltaCents = 0,
            staffId = staffId,
            staffName = staffName,
            sourceType = "pos_vuoro",
            sourceId = LocalDate.now().toString(),
            idempotencyKey = "cash-truth-missing:$drawerId:${LocalDate.now()}",
            note = reason,
        )
    }

    private suspend fun insertCashEvent(
        drawerId: String,
        type: CashEventType,
        amountCents: Int?,
        deltaCents: Int,
        staffId: String?,
        staffName: String?,
        sourceType: String?,
        sourceId: String?,
        idempotencyKey: String?,
        note: String?,
    ): PosResult<CashEvent> {
        return try {
            val now = System.currentTimeMillis()
            val event = CashEventLocalEntity(
                id = "cash-event-${UUID.randomUUID()}",
                drawerId = drawerId,
                type = type.name,
                amountCents = amountCents,
                deltaCents = deltaCents,
                staffId = staffId,
                staffName = staffName,
                sourceType = sourceType,
                sourceId = sourceId,
                idempotencyKey = idempotencyKey,
                note = note,
                occurredAtEpochMillis = now,
                createdAtEpochMillis = now,
            )

            val persisted = database.withTransaction {
                val existing = idempotencyKey?.let { dao.loadEventByIdempotencyKey(it) }
                if (existing != null) {
                    return@withTransaction existing
                }

                val inserted = dao.insertEvent(event)
                val persistedEvent = if (inserted == -1L) {
                    idempotencyKey?.let { dao.loadEventByIdempotencyKey(it) } ?: event
                } else {
                    event
                }
                val currentDrawer = dao.loadDrawer(drawerId) ?: defaultDrawerEntity(drawerId, now)
                dao.upsertDrawer(updateDrawerForEvent(currentDrawer, persistedEvent))
                persistedEvent
            }

            PosResult.Success(persisted.toModel())
        } catch (t: Throwable) {
            PosResult.Failure("Cash ledger write failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    private fun buildState(drawer: CashDrawerLocalEntity, events: List<CashEventLocalEntity>): CashLedgerState {
        val explicitAnchor = events
            .filter { it.amountCents != null && it.type in explicitTruthEventTypes }
            .maxWithOrNull(compareBy<CashEventLocalEntity> { it.occurredAtEpochMillis }.thenBy { it.createdAtEpochMillis })

        if (explicitAnchor == null) {
            return CashLedgerState(
                drawer = drawer.toModel(),
                expectedState = CashExpectedState.MISSING_TRUTH,
                expectedCashCents = null,
                latestExplicitCashCents = null,
                latestExplicitCashEventId = null,
                latestExplicitCashAtEpochMillis = null,
                latestCountedCashCents = drawer.latestCountedCashCents,
                latestCountedAtEpochMillis = drawer.latestCountedAtEpochMillis,
                latestCountedByStaffName = drawer.latestCountedByStaffName,
                lastEventAtEpochMillis = events.maxOfOrNull { it.occurredAtEpochMillis },
                warningMessage = "Kassassa pitäisi olla ei ole laskettavissa. Laske kassa.",
            )
        }

        val deltaAfterAnchor = events
            .asSequence()
            .filter { event ->
                event.occurredAtEpochMillis > explicitAnchor.occurredAtEpochMillis ||
                    (event.occurredAtEpochMillis == explicitAnchor.occurredAtEpochMillis &&
                        event.createdAtEpochMillis > explicitAnchor.createdAtEpochMillis)
            }
            .sumOf { it.deltaCents }

        return CashLedgerState(
            drawer = drawer.toModel(),
            expectedState = CashExpectedState.AVAILABLE,
            expectedCashCents = explicitAnchor.amountCents!! + deltaAfterAnchor,
            latestExplicitCashCents = explicitAnchor.amountCents,
            latestExplicitCashEventId = explicitAnchor.id,
            latestExplicitCashAtEpochMillis = explicitAnchor.occurredAtEpochMillis,
            latestCountedCashCents = drawer.latestCountedCashCents,
            latestCountedAtEpochMillis = drawer.latestCountedAtEpochMillis,
            latestCountedByStaffName = drawer.latestCountedByStaffName,
            lastEventAtEpochMillis = events.maxOfOrNull { it.occurredAtEpochMillis },
            warningMessage = null,
        )
    }

    private fun updateDrawerForEvent(
        drawer: CashDrawerLocalEntity,
        event: CashEventLocalEntity,
    ): CashDrawerLocalEntity {
        val type = CashEventType.valueOf(event.type)
        val explicit = event.amountCents != null && type.name in explicitTruthEventTypes
        val counted = type == CashEventType.CASH_COUNT_RECORDED
        return drawer.copy(
            status = when (type) {
                CashEventType.CASH_OPENED -> CashDrawerStatus.OPEN.name
                CashEventType.CASH_CLOSED -> CashDrawerStatus.CLOSED.name
                else -> drawer.status
            },
            openedAtEpochMillis = if (type == CashEventType.CASH_OPENED) event.occurredAtEpochMillis else drawer.openedAtEpochMillis,
            closedAtEpochMillis = if (type == CashEventType.CASH_CLOSED) event.occurredAtEpochMillis else drawer.closedAtEpochMillis,
            latestExplicitCashCents = if (explicit) event.amountCents else drawer.latestExplicitCashCents,
            latestExplicitCashEventId = if (explicit) event.id else drawer.latestExplicitCashEventId,
            latestExplicitCashAtEpochMillis = if (explicit) event.occurredAtEpochMillis else drawer.latestExplicitCashAtEpochMillis,
            latestCountedCashCents = if (counted) event.amountCents else drawer.latestCountedCashCents,
            latestCountedAtEpochMillis = if (counted) event.occurredAtEpochMillis else drawer.latestCountedAtEpochMillis,
            latestCountedByStaffId = if (counted) event.staffId else drawer.latestCountedByStaffId,
            latestCountedByStaffName = if (counted) event.staffName else drawer.latestCountedByStaffName,
            updatedAtEpochMillis = event.createdAtEpochMillis,
        )
    }

    private fun defaultDrawerEntity(drawerId: String, now: Long): CashDrawerLocalEntity {
        return CashDrawerLocalEntity(
            id = drawerId,
            label = CashDrawer.DEFAULT_DRAWER_LABEL,
            status = CashDrawerStatus.CLOSED.name,
            openedAtEpochMillis = null,
            closedAtEpochMillis = null,
            latestExplicitCashCents = null,
            latestExplicitCashEventId = null,
            latestExplicitCashAtEpochMillis = null,
            latestCountedCashCents = null,
            latestCountedAtEpochMillis = null,
            latestCountedByStaffId = null,
            latestCountedByStaffName = null,
            updatedAtEpochMillis = now,
        )
    }

    private fun CashDrawerLocalEntity.toModel(): CashDrawer {
        return CashDrawer(
            id = id,
            label = label,
            status = runCatching { CashDrawerStatus.valueOf(status) }.getOrDefault(CashDrawerStatus.CLOSED),
            openedAtEpochMillis = openedAtEpochMillis,
            closedAtEpochMillis = closedAtEpochMillis,
        )
    }

    private fun CashEventLocalEntity.toModel(): CashEvent {
        return CashEvent(
            id = id,
            drawerId = drawerId,
            type = CashEventType.valueOf(type),
            amountCents = amountCents,
            deltaCents = deltaCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = sourceType,
            sourceId = sourceId,
            idempotencyKey = idempotencyKey,
            note = note,
            occurredAtEpochMillis = occurredAtEpochMillis,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private companion object {
        val explicitTruthEventTypes = setOf(
            CashEventType.CASH_OPENED.name,
            CashEventType.CASH_COUNT_RECORDED.name,
        )
    }
}
