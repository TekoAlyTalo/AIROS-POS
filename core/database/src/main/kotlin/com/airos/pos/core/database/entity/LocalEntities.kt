package com.airos.pos.core.database.entity

import androidx.room.Entity
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
