package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.airos.pos.core.database.entity.AttendanceActiveSessionLocalEntity
import com.airos.pos.core.database.entity.AttendanceEventLocalEntity
import com.airos.pos.core.database.entity.AttendanceSyncMetadataLocalEntity
import com.airos.pos.core.database.entity.CashDrawerLocalEntity
import com.airos.pos.core.database.entity.CashEventLocalEntity
import com.airos.pos.core.database.entity.LocalFinalizedSaleEntity
import com.airos.pos.core.database.entity.LocalFinalizedSalePaymentEntity
import com.airos.pos.core.database.entity.MenuItemLocalEntity
import com.airos.pos.core.database.entity.OpenSaleEntity
import com.airos.pos.core.database.entity.OpenSaleLineEntity
import com.airos.pos.core.database.entity.OpenSaleTransferEventEntity
import com.airos.pos.core.database.entity.RestaurantTableLocalEntity
import com.airos.pos.core.database.entity.SalesLedgerOutboxLocalEntity
import com.airos.pos.core.database.entity.ShiftLocalEntity
import com.airos.pos.core.database.entity.StaffLocalEntity
import com.airos.pos.core.database.entity.SyncQueueLocalEntity
import com.airos.pos.core.database.entity.TicketLineLocalEntity
import com.airos.pos.core.database.entity.TicketLocalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff_members ORDER BY displayName")
    fun observeAll(): Flow<List<StaffLocalEntity>>

    @Upsert
    suspend fun upsertAll(items: List<StaffLocalEntity>)
}

@Dao
interface TableDao {
    @Query("SELECT * FROM restaurant_tables ORDER BY areaName, label")
    fun observeAll(): Flow<List<RestaurantTableLocalEntity>>

    @Query("SELECT * FROM restaurant_tables WHERE id = :tableId LIMIT 1")
    fun observeById(tableId: String): Flow<RestaurantTableLocalEntity?>

    @Upsert
    suspend fun upsertAll(items: List<RestaurantTableLocalEntity>)
}

@Dao
interface MenuItemDao {
    @Query("SELECT * FROM menu_items ORDER BY category, name")
    fun observeAll(): Flow<List<MenuItemLocalEntity>>

    @Upsert
    suspend fun upsertAll(items: List<MenuItemLocalEntity>)
}

@Dao
interface TicketDao {
    @Query("SELECT * FROM tickets WHERE tableId = :tableId AND status != 'CLOSED' LIMIT 1")
    fun observeOpenTicketForTable(tableId: String): Flow<TicketLocalEntity?>

    @Query("SELECT * FROM ticket_lines WHERE ticketId = :ticketId")
    fun observeLines(ticketId: String): Flow<List<TicketLineLocalEntity>>

    @Upsert
    suspend fun upsertTicket(item: TicketLocalEntity)

    @Upsert
    suspend fun upsertLines(items: List<TicketLineLocalEntity>)
}

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shifts WHERE status = 'OPEN' ORDER BY openedAtEpochMillis DESC LIMIT 1")
    fun observeOpenShift(): Flow<ShiftLocalEntity?>

    @Query("SELECT * FROM shifts WHERE status = 'OPEN' ORDER BY openedAtEpochMillis DESC LIMIT 1")
    suspend fun getOpenShiftOnce(): ShiftLocalEntity?

    @Query("SELECT * FROM shifts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ShiftLocalEntity?

    @Upsert
    suspend fun upsert(item: ShiftLocalEntity)
}

@Dao
interface CashLedgerDao {
    @Query("SELECT * FROM cash_drawers WHERE id = :drawerId LIMIT 1")
    fun observeDrawer(drawerId: String): Flow<CashDrawerLocalEntity?>

    @Query("SELECT * FROM cash_drawers WHERE id = :drawerId LIMIT 1")
    suspend fun loadDrawer(drawerId: String): CashDrawerLocalEntity?

    @Upsert
    suspend fun upsertDrawer(entity: CashDrawerLocalEntity)

    @Query("SELECT * FROM cash_events WHERE drawerId = :drawerId ORDER BY occurredAtEpochMillis ASC, createdAtEpochMillis ASC")
    fun observeEvents(drawerId: String): Flow<List<CashEventLocalEntity>>

    @Query("SELECT * FROM cash_events WHERE drawerId = :drawerId ORDER BY occurredAtEpochMillis DESC, createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecentEvents(drawerId: String, limit: Int = 100): Flow<List<CashEventLocalEntity>>

    @Query("SELECT * FROM cash_events WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun loadEventByIdempotencyKey(idempotencyKey: String): CashEventLocalEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: CashEventLocalEntity): Long

    @Query("SELECT * FROM cash_events WHERE drawerId = :drawerId AND occurredAtEpochMillis >= :dayStartEpochMillis ORDER BY occurredAtEpochMillis ASC, createdAtEpochMillis ASC")
    fun observeEventsFromDay(drawerId: String, dayStartEpochMillis: Long): Flow<List<CashEventLocalEntity>>
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAtEpochMillis")
    fun observeAll(): Flow<List<SyncQueueLocalEntity>>

    @Upsert
    suspend fun upsert(item: SyncQueueLocalEntity)
}

@Dao
interface SalesLedgerOutboxDao {
    @Query("SELECT * FROM sales_ledger_outbox ORDER BY createdAtEpochMillis")
    fun observeAll(): Flow<List<SalesLedgerOutboxLocalEntity>>

    @Query("SELECT COUNT(*) FROM sales_ledger_outbox WHERE syncStatus IN ('queued', 'syncing', 'failed')")
    fun observeUnresolvedCount(): Flow<Int>

    @Query("SELECT * FROM sales_ledger_outbox WHERE sourcePosEventId = :sourcePosEventId LIMIT 1")
    suspend fun load(sourcePosEventId: String): SalesLedgerOutboxLocalEntity?

    @Query("SELECT * FROM sales_ledger_outbox WHERE syncStatus IN ('queued', 'syncing', 'failed') ORDER BY createdAtEpochMillis ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SalesLedgerOutboxLocalEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SalesLedgerOutboxLocalEntity): Long

    @Query(
        """
        UPDATE sales_ledger_outbox
        SET syncStatus = 'syncing',
            attemptCount = attemptCount + 1,
            lastError = NULL,
            lastAttemptAtEpochMillis = :updatedAtEpochMillis,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE sourcePosEventId = :sourcePosEventId
        """
    )
    suspend fun markSyncing(sourcePosEventId: String, updatedAtEpochMillis: Long)

    @Query(
        """
        UPDATE sales_ledger_outbox
        SET syncStatus = 'synced',
            serverSaleId = :serverSaleId,
            receiptSnapshotId = :receiptSnapshotId,
            publicUrlPath = :publicUrlPath,
            deliveryTokenIdsCsv = :deliveryTokenIdsCsv,
            lastError = NULL,
            syncedAtEpochMillis = :updatedAtEpochMillis,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE sourcePosEventId = :sourcePosEventId
        """
    )
    suspend fun markSynced(
        sourcePosEventId: String,
        serverSaleId: String,
        receiptSnapshotId: String,
        publicUrlPath: String?,
        deliveryTokenIdsCsv: String,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE sales_ledger_outbox
        SET syncStatus = 'failed',
            lastError = :lastError,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE sourcePosEventId = :sourcePosEventId
        """
    )
    suspend fun markFailed(sourcePosEventId: String, lastError: String, updatedAtEpochMillis: Long)

    @Query(
        """
        UPDATE sales_ledger_outbox
        SET syncStatus = 'blocked',
            lastError = :lastError,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE sourcePosEventId = :sourcePosEventId
        """
    )
    suspend fun markBlocked(sourcePosEventId: String, lastError: String, updatedAtEpochMillis: Long)
}

@Dao
interface LocalFinalizedSalesReportDao {
    @Query(
        """
        SELECT * FROM local_finalized_sales
        WHERE finalizedAtEpochMillis >= :startEpochMillisInclusive
          AND finalizedAtEpochMillis < :endEpochMillisExclusive
          AND status = 'COMPLETED'
        ORDER BY finalizedAtEpochMillis ASC, id ASC
        """
    )
    fun observeSalesInRange(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<List<LocalFinalizedSaleEntity>>

    @Query(
        """
        SELECT p.* FROM local_finalized_sale_payments p
        INNER JOIN local_finalized_sales s ON s.id = p.finalizedSaleId
        WHERE s.finalizedAtEpochMillis >= :startEpochMillisInclusive
          AND s.finalizedAtEpochMillis < :endEpochMillisExclusive
          AND s.status = 'COMPLETED'
        ORDER BY p.createdAtEpochMillis ASC, p.id ASC
        """
    )
    fun observePaymentsInRange(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<List<LocalFinalizedSalePaymentEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSale(entity: LocalFinalizedSaleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayments(items: List<LocalFinalizedSalePaymentEntity>): List<Long>

    @Transaction
    suspend fun insertSaleWithPayments(
        sale: LocalFinalizedSaleEntity,
        payments: List<LocalFinalizedSalePaymentEntity>,
    ): Boolean {
        val inserted = insertSale(sale)
        if (inserted == -1L) return false
        insertPayments(payments)
        return true
    }
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_active_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    fun observeActiveSession(sessionKey: String): Flow<AttendanceActiveSessionLocalEntity?>

    @Query("SELECT * FROM attendance_active_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun loadActiveSession(sessionKey: String): AttendanceActiveSessionLocalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveSession(entity: AttendanceActiveSessionLocalEntity)

    @Query("DELETE FROM attendance_active_sessions WHERE sessionKey = :sessionKey")
    suspend fun deleteActiveSession(sessionKey: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: AttendanceEventLocalEntity)

    @Query("DELETE FROM attendance_events WHERE eventId = :eventId")
    suspend fun deleteEvent(eventId: String)

    @Query("SELECT COALESCE(MAX(terminalSequenceNumber), 0) FROM attendance_events WHERE metadataKey = :metadataKey")
    suspend fun maxTerminalSequence(metadataKey: String): Long

    @Query("SELECT * FROM attendance_events WHERE metadataKey = :metadataKey AND syncStatus IN ('queued', 'syncing', 'failed') ORDER BY terminalSequenceNumber ASC LIMIT :limit")
    suspend fun pendingEvents(metadataKey: String, limit: Int): List<AttendanceEventLocalEntity>

    @Query("SELECT COUNT(*) FROM attendance_events WHERE metadataKey = :metadataKey AND syncStatus IN ('queued', 'syncing', 'failed')")
    fun observeUnresolvedEventCount(metadataKey: String): Flow<Int>

    @Query("UPDATE attendance_events SET syncStatus = 'syncing', syncBatchId = :syncBatchId, updatedAtEpochMillis = :updatedAtEpochMillis, lastError = NULL WHERE eventId = :eventId")
    suspend fun markEventSyncing(eventId: String, syncBatchId: String, updatedAtEpochMillis: Long)

    @Query("UPDATE attendance_events SET syncStatus = 'synced', syncBatchId = :syncBatchId, updatedAtEpochMillis = :updatedAtEpochMillis, lastError = NULL WHERE eventId = :eventId")
    suspend fun markEventSynced(eventId: String, syncBatchId: String, updatedAtEpochMillis: Long)

    @Query("UPDATE attendance_events SET syncStatus = 'failed', syncBatchId = :syncBatchId, updatedAtEpochMillis = :updatedAtEpochMillis, lastError = :lastError WHERE eventId = :eventId")
    suspend fun markEventFailed(eventId: String, syncBatchId: String, updatedAtEpochMillis: Long, lastError: String)

    @Query("UPDATE attendance_events SET syncStatus = 'blocked', syncBatchId = :syncBatchId, updatedAtEpochMillis = :updatedAtEpochMillis, lastError = :lastError WHERE eventId = :eventId")
    suspend fun markEventBlocked(eventId: String, syncBatchId: String, updatedAtEpochMillis: Long, lastError: String)

    @Query("SELECT * FROM attendance_sync_metadata WHERE metadataKey = :metadataKey LIMIT 1")
    fun observeSyncMetadata(metadataKey: String): Flow<AttendanceSyncMetadataLocalEntity?>

    @Query("SELECT * FROM attendance_sync_metadata WHERE metadataKey = :metadataKey LIMIT 1")
    suspend fun loadSyncMetadata(metadataKey: String): AttendanceSyncMetadataLocalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncMetadata(entity: AttendanceSyncMetadataLocalEntity)
}

@Dao
interface OpenSaleDao {
    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' LIMIT 1")
    suspend fun loadOpenSale(): OpenSaleEntity?

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND saleId = :saleId LIMIT 1")
    suspend fun loadOpenSaleById(saleId: String): OpenSaleEntity?

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND serviceSpotId = :serviceSpotId ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun loadOpenSaleForSpot(serviceSpotId: String): OpenSaleEntity?

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND serviceSpotId IS NULL LIMIT 1")
    suspend fun loadOpenSaleForWalkIn(): OpenSaleEntity?

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND serviceSpotId = :serviceSpotId ORDER BY createdAtEpochMillis")
    suspend fun loadOpenSalesForSpot(serviceSpotId: String): List<OpenSaleEntity>

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND serviceSpotId IS NULL ORDER BY createdAtEpochMillis")
    suspend fun loadOpenSalesForWalkIn(): List<OpenSaleEntity>

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN'")
    fun observeAllOpen(): Flow<List<OpenSaleEntity>>

    @Query("SELECT * FROM open_sale_lines")
    fun observeAllLines(): Flow<List<OpenSaleLineEntity>>

    @Query("SELECT * FROM open_sale_transfer_events ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeAllTransferEvents(): Flow<List<OpenSaleTransferEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSale(entity: OpenSaleEntity)

    @Insert
    suspend fun insertTransferEvent(entity: OpenSaleTransferEventEntity)

    @Query("DELETE FROM open_sales WHERE saleId = :saleId")
    suspend fun deleteSale(saleId: String)

    @Query("UPDATE open_sales SET serviceSpotId = :serviceSpotId, serviceSpotLabel = :serviceSpotLabel, updatedAtEpochMillis = :updated WHERE saleId = :saleId")
    suspend fun updateServiceSpot(
        saleId: String,
        serviceSpotId: String?,
        serviceSpotLabel: String?,
        updated: Long,
    )

    @Query("UPDATE open_sales SET status = 'CLOSED', updatedAtEpochMillis = :updated WHERE saleId = :saleId")
    suspend fun closeSale(saleId: String, updated: Long)

    @Query("SELECT * FROM open_sale_lines WHERE saleId = :saleId")
    suspend fun loadLines(saleId: String): List<OpenSaleLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(items: List<OpenSaleLineEntity>)

    @Query("DELETE FROM open_sale_lines WHERE saleId = :saleId")
    suspend fun deleteLinesForSale(saleId: String)

    @Transaction
    suspend fun assignServiceSpotWithTransferAudit(
        saleId: String,
        serviceSpotId: String?,
        serviceSpotLabel: String?,
        actedByStaffId: String,
        actedByDisplayName: String,
        updated: Long,
    ) {
        val currentSale = loadOpenSaleById(saleId)
        if (currentSale != null && currentSale.serviceSpotId != serviceSpotId) {
            insertTransferEvent(
                OpenSaleTransferEventEntity(
                    saleId = saleId,
                    fromServiceSpotId = currentSale.serviceSpotId,
                    fromServiceSpotLabel = currentSale.serviceSpotLabel,
                    toServiceSpotId = serviceSpotId,
                    toServiceSpotLabel = serviceSpotLabel,
                    actedByStaffId = actedByStaffId,
                    actedByDisplayName = actedByDisplayName,
                    occurredAtEpochMillis = updated,
                ),
            )
        }
        updateServiceSpot(
            saleId = saleId,
            serviceSpotId = serviceSpotId,
            serviceSpotLabel = serviceSpotLabel,
            updated = updated,
        )
    }
}
