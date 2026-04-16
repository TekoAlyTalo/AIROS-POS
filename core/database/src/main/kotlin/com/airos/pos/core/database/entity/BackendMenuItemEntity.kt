package com.airos.pos.core.database.entity

import androidx.room.Entity

/**
 * Room entity for backend menu items cached locally.
 *
 * Composite primary key (restaurantKey, id) allows a single DB to serve
 * multiple restaurant configurations without data bleed.
 */
@Entity(
    tableName = "backend_menu_items",
    primaryKeys = ["restaurantKey", "id"],
)
data class BackendMenuItemEntity(
    val restaurantKey: String,
    val id: String,
    val sku: String,
    val name: String,
    val category: String,
    val priceCents: Int,
    val taxRatePercent: Double,
    val subcategory: String?,
    val barcode: String?,
    val imageUrl: String?,
    /** Still-image used by the intermediate submenu/subcategory tile. */
    val subcategoryImageUrl: String?,
    /** Epoch millis when this row was written to the cache. */
    val cachedAt: Long,
    /** Absolute file path to a locally cached copy of the product image. */
    val cachedImagePath: String? = null,
    /** Absolute file path to a locally cached copy of the subcategory image. */
    val cachedSubcategoryImagePath: String? = null,
)
