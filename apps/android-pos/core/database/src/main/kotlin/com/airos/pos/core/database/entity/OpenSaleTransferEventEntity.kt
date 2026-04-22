package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "open_sale_transfer_events",
    indices = [
        Index(value = ["saleId"]),
        Index(value = ["occurredAtEpochMillis"]),
    ],
)
data class OpenSaleTransferEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: String,
    val fromServiceSpotId: String?,
    val fromServiceSpotLabel: String?,
    val toServiceSpotId: String?,
    val toServiceSpotLabel: String?,
    val actedByStaffId: String,
    val actedByDisplayName: String,
    val occurredAtEpochMillis: Long,
)
