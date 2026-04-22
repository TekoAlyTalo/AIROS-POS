package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable snapshot of the backend-authoritative floor map so the POS can render the
 * last honest layout after a cold start without backend reachability.
 *
 * Rows are written only by the write-through sink that observes
 * BackendTruthTableRepository. Nothing outside that sink is allowed to populate this
 * table — any manual seeding would reintroduce the fake-data problem this cache
 * exists to eliminate.
 */
@Entity(tableName = "cached_floor_map_tables")
data class CachedFloorMapTableEntity(
    @PrimaryKey val id: String,
    val backendTableId: Int?,
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
    val cameraId: String?,
    val cameraLabel: String?,
    val attentionFlag: String,
    val reviewAnchorTime: String?,
    val reviewFrom: String?,
    val reviewTo: String?,
    val spotType: String,
    val sortOrder: Int,
    val cachedAtEpochMillis: Long,
)
