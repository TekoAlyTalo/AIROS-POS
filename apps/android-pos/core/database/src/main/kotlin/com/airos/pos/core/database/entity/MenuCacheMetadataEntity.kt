package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "menu_cache_metadata")
data class MenuCacheMetadataEntity(
    @PrimaryKey val restaurantKey: String,
    val lastSyncedAt: Long,
    val itemCount: Int,
)
