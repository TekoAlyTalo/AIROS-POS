package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
interface OpenSaleDao {
    @Query("SELECT * FROM open_sales WHERE status = 'OPEN' LIMIT 1")
    suspend fun loadOpenSale(): OpenSaleEntity?

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
