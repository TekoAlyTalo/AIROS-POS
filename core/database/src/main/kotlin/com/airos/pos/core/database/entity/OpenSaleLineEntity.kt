package com.airos.pos.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "open_sale_lines",
    primaryKeys = ["saleId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = OpenSaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["saleId"])],
)
data class OpenSaleLineEntity(
    @ColumnInfo(name = "saleId")
    val saleId: String,
    @ColumnInfo(name = "itemId")
    val itemId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "quantity")
    val quantity: Int,
    @ColumnInfo(name = "unitPriceCents")
    val unitPriceCents: Int,
    @ColumnInfo(name = "taxRatePercent")
    val taxRatePercent: Double,
    @ColumnInfo(name = "discountPercent")
    val discountPercent: Int? = null,
    @ColumnInfo(name = "discountAmountCents")
    val discountAmountCents: Int? = null,
)
