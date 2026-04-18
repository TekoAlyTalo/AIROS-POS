package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "staff_members")
data class StaffLocalEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val role: String,
    val quickColorHex: String,
    val isActive: Boolean,
)

@Entity(tableName = "restaurant_tables")
data class RestaurantTableLocalEntity(
    @PrimaryKey val id: String,
    val label: String,
    val areaName: String,
    val seats: Int,
    val status: String,
    val guestCount: Int,
    val activeTicketId: String?,
    val positionX: Int,
    val positionY: Int,
    val positionWidth: Int,
    val positionHeight: Int,
)

@Entity(tableName = "menu_items")
data class MenuItemLocalEntity(
    @PrimaryKey val id: String,
    val sku: String,
    val name: String,
    val category: String,
    val priceCents: Int,
    val taxRatePercent: Int,
    val barcode: String?,
    val requiresManagerOverride: Boolean,
)

@Entity(tableName = "tickets")
data class TicketLocalEntity(
    @PrimaryKey val id: String,
    val tableId: String,
    val openedByStaffId: String,
    val openedAtEpochMillis: Long,
    val status: String,
    val subtotalCents: Int,
    val taxCents: Int,
    val totalCents: Int,
    val syncState: String,
)

@Entity(tableName = "ticket_lines")
data class TicketLineLocalEntity(
    @PrimaryKey val id: String,
    val ticketId: String,
    val menuItemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val totalPriceCents: Int,
    val note: String?,
)

@Entity(tableName = "shifts")
data class ShiftLocalEntity(
    @PrimaryKey val id: String,
    val openedByStaffId: String,
    val openedAtEpochMillis: Long,
    val status: String,
    val openingFloatCents: Int,
    val expectedCashCents: Int,
    val countedCashCents: Int?,
    val closedAtEpochMillis: Long?,
)

@Entity(tableName = "sync_queue")
data class SyncQueueLocalEntity(
    @PrimaryKey val id: String,
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val payloadJson: String,
    val state: String,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
)

@Entity(
    tableName = "sales_ledger_outbox",
    indices = [
        Index(value = ["receiptNumber"], unique = true),
        Index(value = ["syncStatus", "createdAtEpochMillis"]),
        Index(value = ["terminalId", "createdAtEpochMillis"]),
        Index(value = ["cashierStaffId", "createdAtEpochMillis"]),
    ],
)
data class SalesLedgerOutboxLocalEntity(
    @PrimaryKey val sourcePosEventId: String,
    val receiptNumber: String,
    val ticketId: String?,
    val tableId: String?,
    val terminalId: String?,
    val restaurantId: String?,
    val cashierStaffId: String?,
    val totalCents: Int,
    val requestJson: String,
    val syncStatus: String,
    val attemptCount: Int,
    val lastError: String?,
    val serverSaleId: String?,
    val receiptSnapshotId: String?,
    val publicUrlPath: String?,
    val deliveryTokenIdsCsv: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long?,
    val syncedAtEpochMillis: Long?,
)

@Entity(
    tableName = "attendance_events",
    indices = [
        Index(value = ["metadataKey", "terminalSequenceNumber"], unique = true),
        Index(value = ["metadataKey", "syncStatus", "terminalSequenceNumber"]),
        Index(value = ["restaurantKey", "staffId", "occurredAtEpochMillis"]),
    ],
)
data class AttendanceEventLocalEntity(
    @PrimaryKey val eventId: String,
    val metadataKey: String,
    val ownerAccountId: String?,
    val restaurantKey: String,
    val terminalId: String,
    val staffId: String,
    val staffName: String,
    val action: String,
    val occurredAtEpochMillis: Long,
    val source: String,
    val syncStatus: String,
    val terminalSequenceNumber: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val syncBatchId: String?,
    val lastError: String?,
)

@Entity(
    tableName = "attendance_active_sessions",
    indices = [
        Index(value = ["restaurantKey", "staffId"]),
    ],
)
data class AttendanceActiveSessionLocalEntity(
    @PrimaryKey val sessionKey: String,
    val ownerAccountId: String?,
    val restaurantKey: String,
    val staffId: String,
    val staffName: String,
    val status: String,
    val startedAtIso: String,
    val startedAtEpochMillis: Long,
    val serverSessionId: Int?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "attendance_sync_metadata")
data class AttendanceSyncMetadataLocalEntity(
    @PrimaryKey val metadataKey: String,
    val ownerAccountId: String?,
    val restaurantKey: String,
    val terminalId: String,
    val lastSuccessfulSyncAtEpochMillis: Long?,
    val lastSeenTerminalSequence: Long,
    val lastSyncBatchId: String?,
    val lastError: String?,
    val syncState: String,
    val updatedAtEpochMillis: Long,
)
