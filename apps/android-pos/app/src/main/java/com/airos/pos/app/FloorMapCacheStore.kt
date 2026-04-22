package com.airos.pos.app

import com.airos.pos.core.database.dao.CachedFloorMapDao
import com.airos.pos.core.database.entity.CachedFloorMapTableEntity
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.TableAttentionFlag
import com.airos.pos.core.model.TablePosition
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.TableTruthSource

/**
 * Write-through persistence for the last backend-authoritative floor map.
 *
 * The only writer is [persist], called from AppContainer whenever
 * BackendTruthTableRepository publishes a fresh authoritative snapshot.
 * On cold start, [loadPersistedFloorMap] restores the last persisted snapshot
 * so the Tables view renders the last honest layout without backend reachability.
 *
 * Never persist POS-side fabricated data here. If the backend list is empty, we
 * skip persisting so the prior honest cache is preserved rather than overwritten.
 */
class FloorMapCacheStore(
    private val dao: CachedFloorMapDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadPersistedFloorMap(): FloorMap? {
        val rows = dao.loadAll()
        if (rows.isEmpty()) return null
        return FloorMap(
            id = PERSISTED_FLOOR_MAP_ID,
            name = PERSISTED_FLOOR_MAP_NAME,
            tables = rows.map { it.toRestaurantTable() },
        )
    }

    suspend fun persist(floorMap: FloorMap) {
        if (floorMap.tables.isEmpty()) return
        val now = clock()
        val rows = floorMap.tables.mapIndexed { index, table ->
            table.toCacheEntity(sortOrder = index, cachedAtEpochMillis = now)
        }
        dao.replaceAll(rows)
    }

    companion object {
        private const val PERSISTED_FLOOR_MAP_ID = "backend-authoritative-floor"
        private const val PERSISTED_FLOOR_MAP_NAME = "Dining room"
    }
}

private fun CachedFloorMapTableEntity.toRestaurantTable(): RestaurantTable {
    return RestaurantTable(
        id = id,
        backendTableId = backendTableId,
        label = label,
        areaName = areaName,
        seats = seats,
        status = runCatching { TableStatus.valueOf(status) }.getOrDefault(TableStatus.AVAILABLE),
        guestCount = guestCount,
        activeTicketId = activeTicketId,
        position = TablePosition(
            x = positionX,
            y = positionY,
            width = positionWidth,
            height = positionHeight,
        ),
        cameraId = cameraId,
        cameraLabel = cameraLabel,
        attentionFlag = runCatching { TableAttentionFlag.valueOf(attentionFlag) }.getOrDefault(TableAttentionFlag.NONE),
        reviewAnchorTime = reviewAnchorTime,
        reviewFrom = reviewFrom,
        reviewTo = reviewTo,
        // Truth source is cached backend data. When the live poll reconnects,
        // BackendTruthTableRepository overwrites this with TableTruthSource.BACKEND.
        truthSource = TableTruthSource.BACKEND,
        spotType = runCatching { ServiceSpotType.valueOf(spotType) }.getOrDefault(ServiceSpotType.TABLE),
    )
}

private fun RestaurantTable.toCacheEntity(sortOrder: Int, cachedAtEpochMillis: Long): CachedFloorMapTableEntity {
    return CachedFloorMapTableEntity(
        id = id,
        backendTableId = backendTableId,
        label = label,
        areaName = areaName,
        seats = seats,
        status = status.name,
        guestCount = guestCount,
        activeTicketId = activeTicketId,
        positionX = position.x,
        positionY = position.y,
        positionWidth = position.width,
        positionHeight = position.height,
        cameraId = cameraId,
        cameraLabel = cameraLabel,
        attentionFlag = attentionFlag.name,
        reviewAnchorTime = reviewAnchorTime,
        reviewFrom = reviewFrom,
        reviewTo = reviewTo,
        spotType = spotType.name,
        sortOrder = sortOrder,
        cachedAtEpochMillis = cachedAtEpochMillis,
    )
}
