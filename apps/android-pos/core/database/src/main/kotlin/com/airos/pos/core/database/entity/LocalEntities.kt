package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
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

@Entity(tableName = "cash_drawers")
data class CashDrawerLocalEntity(
    @PrimaryKey val id: String,
    val label: String,
    val status: String,
    val openedAtEpochMillis: Long?,
    val closedAtEpochMillis: Long?,
    val latestExplicitCashCents: Int?,
    val latestExplicitCashEventId: String?,
    val latestExplicitCashAtEpochMillis: Long?,
    val latestCountedCashCents: Int?,
    val latestCountedAtEpochMillis: Long?,
    val latestCountedByStaffId: String?,
    val latestCountedByStaffName: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "cash_events",
    indices = [
        Index(value = ["drawerId", "occurredAtEpochMillis"]),
        Index(value = ["type", "occurredAtEpochMillis"]),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["sourceType", "sourceId"]),
    ],
)
data class CashEventLocalEntity(
    @PrimaryKey val id: String,
    val drawerId: String,
    val type: String,
    val amountCents: Int?,
    val deltaCents: Int,
    val staffId: String?,
    val staffName: String?,
    val sourceType: String?,
    val sourceId: String?,
    val idempotencyKey: String?,
    val note: String?,
    val occurredAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val expectedCashCents: Int? = null,
    val varianceCents: Int? = null,
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
    tableName = "local_finalized_sales",
    indices = [
        Index(value = ["sourcePosEventId"], unique = true),
        Index(value = ["finalizedAtEpochMillis"]),
        Index(value = ["sellerStaffId", "finalizedAtEpochMillis"]),
        Index(value = ["terminalId", "finalizedAtEpochMillis"]),
        Index(value = ["saleKind", "finalizedAtEpochMillis"]),
        Index(value = ["correctionOriginalSaleId"]),
    ],
)
data class LocalFinalizedSaleEntity(
    @PrimaryKey val id: String,
    val sourcePosEventId: String,
    val ticketId: String?,
    val openSaleId: String?,
    val receiptNumber: String?,
    val receiptSnapshotId: String? = null,
    val publicReceiptUrl: String? = null,
    val publicUrlPath: String? = null,
    val tableId: String?,
    val tableLabel: String?,
    val finalizedAtEpochMillis: Long,
    val totalCents: Int,
    val sellerStaffId: String?,
    val sellerDisplayName: String?,
    val terminalId: String?,
    val restaurantId: String?,
    val saleKind: String = "NORMAL_SALE",
    val correctionOriginalSaleId: String? = null,
    val correctionOriginalReceiptNumber: String? = null,
    val correctionReason: String? = null,
    val correctionAmountCents: Int? = null,
    val serverSaleId: String? = null,
    val status: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "local_finalized_sale_payments",
    foreignKeys = [
        ForeignKey(
            entity = LocalFinalizedSaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["finalizedSaleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["finalizedSaleId"]),
        Index(value = ["method"]),
        Index(value = ["createdAtEpochMillis"]),
    ],
)
data class LocalFinalizedSalePaymentEntity(
    @PrimaryKey val id: String,
    val finalizedSaleId: String,
    val method: String,
    val amountCents: Int,
    val cashTenderedCents: Int?,
    val cashChangeCents: Int?,
    val cashRetainedCents: Int?,
    val createdAtEpochMillis: Long,
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
