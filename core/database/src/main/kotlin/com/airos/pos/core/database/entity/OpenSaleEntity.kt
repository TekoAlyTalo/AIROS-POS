package com.airos.pos.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "open_sales")
data class OpenSaleEntity(
    @PrimaryKey
    @ColumnInfo(name = "saleId")
    val saleId: String,
    @ColumnInfo(name = "serviceSpotId")
    val serviceSpotId: String? = null,
    @ColumnInfo(name = "serviceSpotLabel")
    val serviceSpotLabel: String? = null,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "createdAtEpochMillis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updatedAtEpochMillis")
    val updatedAtEpochMillis: Long,
)
