package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.airos.pos.core.database.entity.AttendanceActiveSessionLocalEntity
import com.airos.pos.core.database.entity.AttendanceEventLocalEntity
import com.airos.pos.core.database.entity.AttendanceSyncMetadataLocalEntity
import com.airos.pos.core.database.entity.MenuItemLocalEntity
import com.airos.pos.core.database.entity.OpenSaleEntity
import com.airos.pos.core.database.entity.OpenSaleLineEntity
import com.airos.pos.core.database.entity.RestaurantTableLocalEntity
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
    @Query("SELECT * FROM shifts WHERE status = 'OPEN' LIMIT 1")
    fun observeOpenShift(): Flow<ShiftLocalEntity?>

    @Upsert
    suspend fun upsert(item: ShiftLocalEntity)
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAtEpochMillis")
    fun observeAll(): Flow<List<SyncQueueLocalEntity>>

    @Upsert
    suspend fun upsert(item: SyncQueueLocalEntity)
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

    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' AND serviceSpotId = :serviceSpotId LIMIT 1")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSale(entity: OpenSaleEntity)

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
}
