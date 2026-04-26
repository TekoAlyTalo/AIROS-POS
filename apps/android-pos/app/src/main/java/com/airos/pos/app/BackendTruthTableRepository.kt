package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.FloorMapArea
import com.airos.pos.core.model.FloorMapObject
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.TableAttentionFlag
import com.airos.pos.core.model.TableOperationalFlag
import com.airos.pos.core.model.TablePosition
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.TableTruthSource
import com.airos.pos.domain.OpenSaleRepository
import com.airos.pos.domain.TableRepository
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private const val BACKEND_FLOOR_MAP_ID = "backend-authoritative-floor"
private const val BACKEND_FLOOR_MAP_NAME = "Dining room"
private const val BACKEND_DEFAULT_AREA_NAME = "Dining room"
private const val BACKEND_TABLE_GRID_COLUMNS = 4
private const val BACKEND_TABLE_GRID_LEFT = 96
private const val BACKEND_TABLE_GRID_TOP = 96
private const val BACKEND_TABLE_GRID_X_STEP = 220
private const val BACKEND_TABLE_GRID_Y_STEP = 190
private const val BACKEND_TABLE_WIDTH = 180
private const val BACKEND_TABLE_HEIGHT = 120

interface BackendAuthoritativeFloorMapSink {
    fun replaceBackendAuthoritativeFloorMap(floorMap: FloorMap)
}

class BackendTruthTableRepository(
    private val delegate: TableRepository,
    private val floorMapSink: BackendAuthoritativeFloorMapSink? = null,
    private val openSaleRepository: OpenSaleRepository,
    backendBaseUrlProvider: () -> String,
    pollIntervalMillis: Long = 2_000L,
) : TableRepository {
    private val client = BackendTableTruthClient(
        backendBaseUrlProvider = backendBaseUrlProvider,
        pollIntervalMillis = pollIntervalMillis,
    )
    private val lastPublishedOpenBillContextKeys = linkedMapOf<Int, String>()
    @Volatile
    private var latestBackendTableIdByServiceSpotId: Map<String, Int> = emptyMap()

    override fun observeFloorMap(): Flow<FloorMap> {
        return combine(
            delegate.observeFloorMap(),
            client.observeBackendTableTruth(),
            client.observeInUseFloorPlan(),
            openSaleRepository.observeOpenSales(),
        ) { floorMap, backendTruthByTableId, inUseFloorPlan, openSales ->
            updateKnownBackendTableMappings(floorMap)
            val authoritativeFloorMap = buildAuthoritativeBackendFloorMap(
                currentFloorMap = floorMap,
                backendTruthByTableId = backendTruthByTableId,
                floorPlan = inUseFloorPlan,
            )
            updateKnownBackendTableMappings(authoritativeFloorMap)
            publishOpenBillContexts(
                floorMap = authoritativeFloorMap,
                openSales = openSales,
            )
            if (authoritativeFloorMap.isAuthoritativeFloorPlan) {
                floorMapSink?.replaceBackendAuthoritativeFloorMap(authoritativeFloorMap)
            } else {
                Log.i(
                    "AIROS",
                    "[BackendTruthTableRepository] authoritative in-use floor plan unavailable; surfacing explicit error state",
                )
            }
            authoritativeFloorMap
        }
    }

    override fun observeTable(tableId: String): Flow<RestaurantTable?> {
        return observeFloorMap().map { floorMap -> floorMap.tables.firstOrNull { it.id == tableId } }
    }

    override suspend fun openTable(tableId: String, guestCount: Int, openedByStaffId: String): PosResult<RestaurantTable> {
        return delegate.openTable(tableId, guestCount, openedByStaffId)
    }

    override suspend fun assignDraftToServiceSpot(
        fromSpotId: String?,
        toSpotId: String,
        openedByStaffId: String,
    ): PosResult<RestaurantTable> {
        return delegate.assignDraftToServiceSpot(fromSpotId, toSpotId, openedByStaffId)
    }

    suspend fun acknowledgeCheckTable(
        tableId: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean {
        val backendTableId = latestBackendTableIdByServiceSpotId[tableId]
        if (backendTableId == null) {
            Log.w(
                "AIROS",
                "[BackendTruthTableRepository] CHECK acknowledge rejected for unmapped service spot id=$tableId",
            )
            return false
        }
        return client.postAcknowledgeCheck(
            backendTableId = backendTableId,
            actorStaffId = actorStaffId,
            actorDisplayName = actorDisplayName,
        )
    }

    suspend fun markCleanedTable(
        tableId: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean {
        val backendTableId = latestBackendTableIdByServiceSpotId[tableId]
        if (backendTableId == null) {
            Log.w(
                "AIROS",
                "[BackendTruthTableRepository] mark-cleaned rejected for unmapped service spot id=$tableId",
            )
            return false
        }
        return client.postMarkCleaned(
            backendTableId = backendTableId,
            actorStaffId = actorStaffId,
            actorDisplayName = actorDisplayName,
        )
    }

    private suspend fun publishOpenBillContexts(
        floorMap: FloorMap,
        openSales: List<PersistedOpenSale>,
    ) {
        val backendTableIdByServiceSpotId = buildServiceSpotBackendTableIdMap(floorMap.tables)
        val floorTablesByBackendId = floorMap.tables
            .mapNotNull { table ->
                table.backendTableId?.let { backendTableId -> backendTableId to table }
            }
            .toMap(linkedMapOf())
        val openSalesByBackendTableId = openSales
            .mapNotNull { sale ->
                val backendTableId = resolveBackendTableIdForOpenSale(
                    sale = sale,
                    backendTableIdByServiceSpotId = backendTableIdByServiceSpotId,
                )
                if (backendTableId == null) {
                    Log.w(
                        "AIROS",
                        "[BackendTruthTableRepository] open sale could not be mapped to backend table saleId=${sale.saleId} serviceSpotId=${sale.serviceSpotId} serviceSpotLabel=${sale.serviceSpotLabel}",
                    )
                    null
                } else {
                    backendTableId to sale
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )
        val backendTableIds = linkedSetOf<Int>().apply {
            addAll(floorTablesByBackendId.keys)
            addAll(openSalesByBackendTableId.keys)
        }

        backendTableIds.forEach { backendTableId ->
            val table = floorTablesByBackendId[backendTableId]
            val tableSales = openSalesByBackendTableId[backendTableId]
                .orEmpty()
                .sortedBy { it.saleId }
            val serviceSpotId = table?.id ?: backendTableId.toPosTableId()
            val serviceSpotLabel = table?.label ?: "T$backendTableId"
            val contextKey = buildString {
                append(serviceSpotId)
                append('|')
                append(tableSales.size)
                append('|')
                append(tableSales.sumOf { it.totalCents() })
                append('|')
                append(tableSales.joinToString(separator = ",") { it.saleId })
            }
            if (contextKey == lastPublishedOpenBillContextKeys[backendTableId]) return@forEach

            val published = client.postOpenBillContext(
                backendTableId = backendTableId,
                serviceSpotId = serviceSpotId,
                serviceSpotLabel = tableSales.firstOrNull()?.serviceSpotLabel ?: serviceSpotLabel,
                openBillCount = tableSales.size,
                openSaleIds = tableSales.map { it.saleId },
                openTotalCents = tableSales.sumOf { it.totalCents() },
                oldestOpenSaleCreatedAtEpochMillis = tableSales.minOfOrNull { it.createdAtEpochMillis },
            )
            if (published) {
                lastPublishedOpenBillContextKeys[backendTableId] = contextKey
            }
        }
    }

    private fun updateKnownBackendTableMappings(floorMap: FloorMap) {
        latestBackendTableIdByServiceSpotId = buildServiceSpotBackendTableIdMap(floorMap.tables)
    }
}

private class BackendTableTruthClient(
    private val backendBaseUrlProvider: () -> String,
    private val pollIntervalMillis: Long,
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 2_000,
) {
    fun observeBackendTableTruth(): Flow<Map<Int, BackendTableTruth>> = flow {
        var latest = emptyMap<Int, BackendTableTruth>()
        emit(latest)
        while (true) {
            val next = fetchTableOverviewTruth()
            if (next != null) {
                latest = next
                emit(latest)
            }
            delay(pollIntervalMillis)
        }
    }

    fun observeInUseFloorPlan(): Flow<BackendFloorPlanSnapshot?> = flow {
        var latest: BackendFloorPlanSnapshot? = null
        emit(latest)
        while (true) {
            val next = fetchInUseFloorPlan()
            if (next != null || latest != null) {
                latest = next
                emit(latest)
            }
            delay(pollIntervalMillis * 2)
        }
    }

    suspend fun postOpenBillContext(
        backendTableId: Int,
        serviceSpotId: String,
        serviceSpotLabel: String,
        openBillCount: Int,
        openSaleIds: List<String>,
        openTotalCents: Int,
        oldestOpenSaleCreatedAtEpochMillis: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext false
        val urlString = "$baseUrl/tables/$backendTableId/open-bill-context"
        val payload = JSONObject().apply {
            put("table_id", backendTableId)
            put("service_spot_id", serviceSpotId)
            put("service_spot_label", serviceSpotLabel)
            put("open_bill_count", openBillCount)
            put("open_total_cents", openTotalCents)
            put("open_sale_ids", JSONArray().apply {
                openSaleIds.forEach { put(it) }
            })
            if (oldestOpenSaleCreatedAtEpochMillis != null) {
                put("oldest_open_sale_created_at_epoch_ms", oldestOpenSaleCreatedAtEpochMillis)
            }
            put("source", "android-pos")
        }

        var connection: HttpURLConnection? = null
        try {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Content-Length", bytes.size.toString())
            }
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                log("open-bill context rejected status=$statusCode")
                return@withContext false
            }
            true
        } catch (t: Throwable) {
            log("open-bill context post failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun postAcknowledgeCheck(
        backendTableId: Int,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = postTableAction(
        backendTableId = backendTableId,
        pathSegment = "acknowledge-check",
        logLabel = "acknowledge-check",
        actorStaffId = actorStaffId,
        actorDisplayName = actorDisplayName,
    )

    suspend fun postMarkCleaned(
        backendTableId: Int,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = postTableAction(
        backendTableId = backendTableId,
        pathSegment = "mark-cleaned",
        logLabel = "mark-cleaned",
        actorStaffId = actorStaffId,
        actorDisplayName = actorDisplayName,
    )

    private suspend fun postTableAction(
        backendTableId: Int,
        pathSegment: String,
        logLabel: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext false
        val urlString = "$baseUrl/tables/$backendTableId/$pathSegment"
        val payload = JSONObject().apply {
            if (actorStaffId != null) put("actor_staff_id", actorStaffId)
            if (actorDisplayName != null) put("actor_display_name", actorDisplayName)
            put("source", "android-pos")
        }
        var connection: HttpURLConnection? = null
        try {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Content-Length", bytes.size.toString())
            }
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                log("$logLabel rejected status=$statusCode")
                return@withContext false
            }
            true
        } catch (t: Throwable) {
            log("$logLabel failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun fetchInUseFloorPlan(): BackendFloorPlanSnapshot? = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext null
        val urlString = "$baseUrl/api/dashboard/floor-plans?restaurant_key=ravintola_default&in_use=true"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                log("in-use floor plan fetch failed status=$statusCode")
                return@withContext null
            }
            parseInUseFloorPlan(body)
        } catch (t: Throwable) {
            log("in-use floor plan fetch failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun fetchTableOverviewTruth(): Map<Int, BackendTableTruth>? = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext null
        val urlString = "$baseUrl/tables/overview"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                log("table overview fetch failed status=$statusCode")
                return@withContext null
            }
            parseTableOverview(body)
        } catch (t: Throwable) {
            log("table overview fetch failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseInUseFloorPlan(body: String): BackendFloorPlanSnapshot? {
        if (body.isBlank()) return null
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val item = items.optJSONObject(0) ?: return null
        val mapId = item.optStringOrNull("map_id") ?: item.optStringOrNull("mapId") ?: return null
        val name = item.optStringOrNull("name") ?: return null
        val mapWidth = item.optFloatOrNull("width") ?: return null
        val mapHeight = item.optFloatOrNull("height") ?: return null
        val pxPerMeter = item.optFloatOrNull("px_per_meter") ?: item.optFloatOrNull("pxPerMeter")
        val scaleStatus = item.optStringOrNull("scale_status") ?: item.optStringOrNull("scaleStatus")

        val floorTiles = item.optJSONArray("floor_tiles") ?: item.optJSONArray("floorTiles")
        val areas = mutableListOf<FloorMapArea>()
        if (floorTiles != null) {
            for (index in 0 until floorTiles.length()) {
                val tile = floorTiles.optJSONObject(index) ?: continue
                val x = tile.optFloatOrNull("x") ?: continue
                val y = tile.optFloatOrNull("y") ?: continue
                val width = tile.optFloatOrNull("width") ?: continue
                val height = tile.optFloatOrNull("height") ?: continue
                areas += FloorMapArea(
                    id = tile.optStringOrNull("id") ?: return null,
                    label = tile.optStringOrNull("label").orEmpty(),
                    x = x.roundToInt(),
                    y = y.roundToInt(),
                    width = width.roundToInt().coerceAtLeast(1),
                    height = height.roundToInt().coerceAtLeast(1),
                    xPx = x,
                    yPx = y,
                    widthPx = width.coerceAtLeast(0.01f),
                    heightPx = height.coerceAtLeast(0.01f),
                    shape = tile.optStringOrNull("shape") ?: "rectangle",
                    rotation = tile.optFloatOrNull("rotation") ?: 0f,
                    locked = tile.optBoolean("locked", false),
                    hidden = tile.optBoolean("hidden", false),
                    areaType = tile.optStringOrNull("areaType") ?: tile.optStringOrNull("area_type"),
                    surfaceMaterial = tile.optStringOrNull("surfaceMaterial") ?: tile.optStringOrNull("surface_material"),
                    p1XPercent = tile.optFloatOrNull("p1XPercent") ?: tile.optFloatOrNull("p1_x_percent"),
                    p1YPercent = tile.optFloatOrNull("p1YPercent") ?: tile.optFloatOrNull("p1_y_percent"),
                    p2XPercent = tile.optFloatOrNull("p2XPercent") ?: tile.optFloatOrNull("p2_x_percent"),
                    p2YPercent = tile.optFloatOrNull("p2YPercent") ?: tile.optFloatOrNull("p2_y_percent"),
                    p3XPercent = tile.optFloatOrNull("p3XPercent") ?: tile.optFloatOrNull("p3_x_percent"),
                    p3YPercent = tile.optFloatOrNull("p3YPercent") ?: tile.optFloatOrNull("p3_y_percent"),
                    apexXPercent = tile.optFloatOrNull("apexXPercent") ?: tile.optFloatOrNull("apex_x_percent"),
                )
            }
        }

        val objects = item.optJSONArray("objects")
        val floorPlanObjects = mutableListOf<FloorMapObject>()
        val tableObjects = mutableListOf<BackendFloorPlanTableObject>()
        if (objects != null) {
            for (index in 0 until objects.length()) {
                val obj = objects.optJSONObject(index) ?: continue
                val type = obj.optStringOrNull("type")?.trim()?.lowercase() ?: continue
                val objectId = obj.optStringOrNull("id") ?: return null
                val label = obj.optStringOrNull("label").orEmpty()
                val x = obj.optFloatOrNull("x") ?: continue
                val y = obj.optFloatOrNull("y") ?: continue
                val width = obj.optFloatOrNull("width") ?: continue
                val height = obj.optFloatOrNull("height") ?: continue
                val rotation = obj.optFloatOrNull("rotation") ?: 0f
                val tableNumber = obj.optIntOrNull("tableNumber") ?: obj.optIntOrNull("table_number")
                val capacity = obj.optIntOrNull("capacity")
                val shape = obj.optStringOrNull("shape")
                val chairLayout = obj.optStringOrNull("chairLayout") ?: obj.optStringOrNull("chair_layout")
                if (type == "table") {
                    tableObjects += BackendFloorPlanTableObject(
                        id = objectId,
                        label = label,
                        tableNumber = tableNumber,
                        capacity = capacity,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        rotation = rotation,
                        shape = shape,
                        chairLayout = chairLayout,
                    )
                } else {
                    floorPlanObjects += FloorMapObject(
                        id = objectId,
                        type = type,
                        label = label,
                        x = x.roundToInt(),
                        y = y.roundToInt(),
                        width = width.roundToInt().coerceAtLeast(1),
                        height = height.roundToInt().coerceAtLeast(1),
                        xPx = x,
                        yPx = y,
                        widthPx = width.coerceAtLeast(0.01f),
                        heightPx = height.coerceAtLeast(0.01f),
                        rotation = rotation,
                        locked = obj.optBoolean("locked", false),
                        hidden = obj.optBoolean("hidden", false),
                        shape = shape,
                        chairLayout = chairLayout,
                        capacity = capacity,
                        tableNumber = tableNumber,
                        cameraId = obj.optStringOrNull("cameraId") ?: obj.optStringOrNull("camera_id"),
                        coverageType = obj.optStringOrNull("coverageType") ?: obj.optStringOrNull("coverage_type"),
                        linkedTargetType = obj.optStringOrNull("linkedTargetType") ?: obj.optStringOrNull("linked_target_type"),
                        linkedTargetId = obj.optStringOrNull("linkedTargetId") ?: obj.optStringOrNull("linked_target_id"),
                        doorHingeSide = obj.optStringOrNull("doorHingeSide") ?: obj.optStringOrNull("door_hinge_side"),
                        doorSwingDirection = obj.optStringOrNull("doorSwingDirection") ?: obj.optStringOrNull("door_swing_direction"),
                    )
                }
            }
        }
        return BackendFloorPlanSnapshot(
            mapId = mapId,
            name = name,
            width = mapWidth,
            height = mapHeight,
            pxPerMeter = pxPerMeter,
            scaleStatus = scaleStatus,
            areas = areas,
            objects = floorPlanObjects,
            tableObjects = tableObjects,
        )
    }
    private fun parseTableOverview(body: String): Map<Int, BackendTableTruth> {
        if (body.isBlank()) return emptyMap()
        val root = JSONObject(body)
        val tables = root.optJSONArray("tables") ?: return emptyMap()
        val result = linkedMapOf<Int, BackendTableTruth>()
        for (i in 0 until tables.length()) {
            val item = tables.optJSONObject(i) ?: continue
            val tableId = item.optInt("table_id", -1)
            if (tableId <= 0) continue
            result[tableId] = BackendTableTruth(
                tableId = tableId,
                tableName = item.optStringOrNull("table_name") ?: "T$tableId",
                capacity = item.optInt("capacity", 1).coerceAtLeast(1),
                currentPersons = item.optInt("current_persons", 0).coerceAtLeast(0),
                cameraId = item.optStringOrNull("camera_id"),
                cameraLabel = item.optStringOrNull("camera_label"),
                state = item.optString("state", "READY"),
                attentionFlag = parseAttentionFlag(item.optString("attention_flag", "NONE")),
                operationalFlags = parseOperationalFlags(item.optJSONArray("operational_flags")),
                emptyAnchorTime = item.optStringOrNull("empty_anchor_time"),
                reviewAnchorTime = item.optStringOrNull("review_anchor_time"),
                reviewFrom = item.optStringOrNull("review_from"),
                reviewTo = item.optStringOrNull("review_to"),
            )
        }
        return result
    }

    private fun normalizedBaseUrl(): String? {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        return baseUrl.ifBlank { null }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        append(line)
                    }
                }
            }
        }
    }

    private fun log(message: String) {
        Log.d("AIROS", "[BackendTruthTableRepository] $message")
    }
}

private data class BackendFloorPlanSnapshot(
    val mapId: String,
    val name: String,
    val width: Float,
    val height: Float,
    val pxPerMeter: Float?,
    val scaleStatus: String?,
    val areas: List<FloorMapArea>,
    val objects: List<FloorMapObject>,
    val tableObjects: List<BackendFloorPlanTableObject>,
)

private data class BackendFloorPlanTableObject(
    val id: String,
    val label: String,
    val tableNumber: Int?,
    val capacity: Int?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val shape: String?,
    val chairLayout: String?,
) {
    val inferredBackendTableId: Int?
        get() = tableNumber ?: extractTrailingInteger(label)
}
private data class BackendTableTruth(
    val tableId: Int,
    val tableName: String,
    val capacity: Int,
    val currentPersons: Int,
    val cameraId: String?,
    val cameraLabel: String?,
    val state: String,
    val attentionFlag: TableAttentionFlag,
    val operationalFlags: Set<TableOperationalFlag>,
    val emptyAnchorTime: String?,
    val reviewAnchorTime: String?,
    val reviewFrom: String?,
    val reviewTo: String?,
) {
    val posTableId: String
        get() = "table-$tableId"

    val tableStatus: TableStatus
        get() = when (state.trim().uppercase()) {
            "FREE" -> if (TableOperationalFlag.NEEDS_CLEANING in operationalFlags) {
                TableStatus.DIRTY
            } else {
                TableStatus.AVAILABLE
            }
            "OCCUPIED" -> TableStatus.OCCUPIED
            "NEEDS_CLEANING", "DIRTY" -> TableStatus.DIRTY
            "RESERVED" -> TableStatus.RESERVED
            else -> if (TableOperationalFlag.NEEDS_CLEANING in operationalFlags) {
                TableStatus.DIRTY
            } else {
                TableStatus.AVAILABLE
            }
        }
}

private fun RestaurantTable.withBackendTruth(truth: BackendTableTruth): RestaurantTable {
    return copy(
        backendTableId = truth.tableId,
        status = truth.tableStatus,
        guestCount = truth.currentPersons,
        attentionFlag = truth.attentionFlag,
        operationalFlags = truth.operationalFlags,
        emptyAnchorTime = truth.emptyAnchorTime,
        reviewAnchorTime = truth.reviewAnchorTime,
        reviewFrom = truth.reviewFrom,
        reviewTo = truth.reviewTo,
        truthSource = TableTruthSource.BACKEND,
    )
}

private fun buildAuthoritativeBackendFloorMap(
    currentFloorMap: FloorMap,
    backendTruthByTableId: Map<Int, BackendTableTruth>,
    floorPlan: BackendFloorPlanSnapshot?,
): FloorMap {
    if (floorPlan == null) {
        return FloorMap(
            id = BACKEND_FLOOR_MAP_ID,
            name = BACKEND_FLOOR_MAP_NAME,
            tables = emptyList(),
            isAuthoritativeFloorPlan = false,
            floorPlanError = "Authoritative in-use floor plan is unavailable.",
        )
    }

    val currentTablesById = currentFloorMap.tables.associateBy(RestaurantTable::id)
    val floorPlanTables = floorPlan.tableObjects.map { tableObject ->
        val backendTableId = tableObject.inferredBackendTableId
        val backendTruth = backendTableId?.let { backendTruthByTableId[it] }
        val truthTableId = backendTruth?.posTableId
        val serviceSpotId = truthTableId ?: tableObject.id
        val currentTable = currentTablesById[serviceSpotId]
        val position = TablePosition(
            x = tableObject.x.roundToInt(),
            y = tableObject.y.roundToInt(),
            width = tableObject.width.roundToInt().coerceAtLeast(1),
            height = tableObject.height.roundToInt().coerceAtLeast(1),
        )
        val areaName = resolveAreaNameForPosition(
            areas = floorPlan.areas,
            centerX = tableObject.x + (tableObject.width / 2f),
            centerY = tableObject.y + (tableObject.height / 2f),
        ) ?: currentTable?.areaName ?: BACKEND_DEFAULT_AREA_NAME
        RestaurantTable(
            id = serviceSpotId,
            backendTableId = backendTruth?.tableId,
            label = tableObject.label.ifBlank { backendTruth?.tableName.orEmpty() },
            areaName = areaName,
            seats = tableObject.capacity?.takeIf { it > 0 } ?: backendTruth?.capacity ?: currentTable?.seats ?: 1,
            status = backendTruth?.tableStatus ?: currentTable?.status ?: TableStatus.AVAILABLE,
            guestCount = backendTruth?.currentPersons ?: currentTable?.guestCount ?: 0,
            activeTicketId = currentTable?.activeTicketId,
            position = position,
            cameraId = backendTruth?.cameraId ?: currentTable?.cameraId,
            cameraLabel = backendTruth?.cameraLabel ?: backendTruth?.cameraId ?: currentTable?.cameraLabel,
            attentionFlag = backendTruth?.attentionFlag ?: currentTable?.attentionFlag ?: TableAttentionFlag.NONE,
            operationalFlags = backendTruth?.operationalFlags ?: currentTable?.operationalFlags ?: emptySet(),
            emptyAnchorTime = backendTruth?.emptyAnchorTime ?: currentTable?.emptyAnchorTime,
            reviewAnchorTime = backendTruth?.reviewAnchorTime ?: currentTable?.reviewAnchorTime,
            reviewFrom = backendTruth?.reviewFrom ?: currentTable?.reviewFrom,
            reviewTo = backendTruth?.reviewTo ?: currentTable?.reviewTo,
            truthSource = if (backendTruth != null) TableTruthSource.BACKEND else currentTable?.truthSource ?: TableTruthSource.LOCAL,
            spotType = currentTable?.spotType ?: ServiceSpotType.TABLE,
            floorPlanX = tableObject.x,
            floorPlanY = tableObject.y,
            floorPlanWidth = tableObject.width,
            floorPlanHeight = tableObject.height,
            floorPlanRotation = tableObject.rotation,
            floorPlanShape = tableObject.shape,
            chairLayout = tableObject.chairLayout,
            tableNumber = tableObject.tableNumber,
        )
    }

    return FloorMap(
        id = floorPlan.mapId,
        name = floorPlan.name,
        tables = floorPlanTables,
        areas = floorPlan.areas,
        objects = floorPlan.objects,
        width = floorPlan.width.roundToInt().takeIf { it > 0 },
        height = floorPlan.height.roundToInt().takeIf { it > 0 },
        widthPx = floorPlan.width.takeIf { it > 0f },
        heightPx = floorPlan.height.takeIf { it > 0f },
        pxPerMeter = floorPlan.pxPerMeter,
        scaleStatus = floorPlan.scaleStatus,
        isAuthoritativeFloorPlan = true,
        floorPlanError = null,
    )
}
private fun resolveAreaNameForPosition(
    areas: List<FloorMapArea>,
    centerX: Float,
    centerY: Float,
): String? {
    if (areas.isEmpty()) return null
    return areas.firstOrNull { area ->
        centerX >= area.xPx && centerX <= area.xPx + area.widthPx &&
            centerY >= area.yPx && centerY <= area.yPx + area.heightPx
    }?.label
}
private fun buildAuthoritativeBackendTable(
    backendTruth: BackendTableTruth,
    currentTable: RestaurantTable?,
    index: Int,
): RestaurantTable {
    val baseTable = RestaurantTable(
        id = backendTruth.posTableId,
        backendTableId = backendTruth.tableId,
        label = backendTruth.tableName,
        areaName = currentTable?.areaName ?: BACKEND_DEFAULT_AREA_NAME,
        seats = backendTruth.capacity,
        status = backendTruth.tableStatus,
        guestCount = backendTruth.currentPersons,
        activeTicketId = currentTable?.activeTicketId,
        position = currentTable?.position ?: generatedBackendTablePosition(index),
        cameraId = backendTruth.cameraId,
        cameraLabel = backendTruth.cameraLabel ?: backendTruth.cameraId,
        attentionFlag = backendTruth.attentionFlag,
        operationalFlags = backendTruth.operationalFlags,
        emptyAnchorTime = backendTruth.emptyAnchorTime,
        reviewAnchorTime = backendTruth.reviewAnchorTime,
        reviewFrom = backendTruth.reviewFrom,
        reviewTo = backendTruth.reviewTo,
        truthSource = TableTruthSource.BACKEND,
        spotType = currentTable?.spotType ?: ServiceSpotType.TABLE,
    )
    return baseTable.withBackendTruth(backendTruth)
}

private fun generatedBackendTablePosition(index: Int): TablePosition {
    val row = index / BACKEND_TABLE_GRID_COLUMNS
    val column = index % BACKEND_TABLE_GRID_COLUMNS
    return TablePosition(
        x = BACKEND_TABLE_GRID_LEFT + column * BACKEND_TABLE_GRID_X_STEP,
        y = BACKEND_TABLE_GRID_TOP + row * BACKEND_TABLE_GRID_Y_STEP,
        width = BACKEND_TABLE_WIDTH,
        height = BACKEND_TABLE_HEIGHT,
    )
}

private fun parseAttentionFlag(raw: String): TableAttentionFlag {
    return when (raw.trim().uppercase()) {
        "CHECK_TABLE" -> TableAttentionFlag.CHECK_TABLE
        else -> TableAttentionFlag.NONE
    }
}

private fun parseOperationalFlags(raw: JSONArray?): Set<TableOperationalFlag> {
    if (raw == null || raw.length() == 0) return emptySet()
    val flags = linkedSetOf<TableOperationalFlag>()
    for (index in 0 until raw.length()) {
        when (raw.optString(index).trim().uppercase()) {
            "CHECK" -> flags += TableOperationalFlag.CHECK
            "NEEDS_CLEANING" -> flags += TableOperationalFlag.NEEDS_CLEANING
        }
    }
    return flags
}

private fun buildServiceSpotBackendTableIdMap(tables: List<RestaurantTable>): Map<String, Int> {
    return tables
        .mapNotNull { table ->
            val backendTableId = table.backendTableId ?: return@mapNotNull null
            table.id
                .takeIf { it.isNotBlank() }
                ?.let { serviceSpotId -> serviceSpotId to backendTableId }
        }
        .toMap(linkedMapOf())
}

private fun resolveBackendTableIdForOpenSale(
    sale: PersistedOpenSale,
    backendTableIdByServiceSpotId: Map<String, Int>,
): Int? {
    val serviceSpotId = sale.serviceSpotId?.takeIf { it.isNotBlank() } ?: return null
    return backendTableIdByServiceSpotId[serviceSpotId]
}

private fun serviceSpotAliases(
    serviceSpotId: String?,
    serviceSpotLabel: String?,
    backendTableId: Int?,
): List<String> {
    val aliases = linkedSetOf<String>()

    fun addAlias(raw: String?) {
        val normalized = normalizeServiceSpotAlias(raw) ?: return
        aliases += normalized
        extractTrailingInteger(normalized)?.let { numeric ->
            aliases += numeric.toPosTableId()
            aliases += "t$numeric"
            aliases += numeric.toString()
        }
    }

    addAlias(serviceSpotId)
    addAlias(serviceSpotLabel)
    backendTableId?.let { numeric ->
        aliases += numeric.toPosTableId()
        aliases += "t$numeric"
        aliases += numeric.toString()
    }
    return aliases.toList()
}

private fun normalizeServiceSpotAlias(raw: String?): String? {
    val value = raw
        ?.trim()
        ?.lowercase()
        ?.replace("ö", "o")
        ?.replace("ä", "a")
        ?.replace("å", "a")
        ?.replace(Regex("""\s+"""), "")
        ?.replace(Regex("""[^a-z0-9_-]"""), "")
        ?: return null
    return value.takeIf { it.isNotBlank() }
}

private fun extractTrailingInteger(raw: String): Int? {
    val match = Regex("""(\d+)$""").find(raw) ?: return null
    return match.groupValues[1].toIntOrNull()
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject.optFloatOrNull(name: String): Float? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    if (!value.isFinite()) return null
    return value.toFloat()
}
private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { optString(name).isNotBlank() }
}

private fun Int.toPosTableId(): String = "table-$this"

private fun PersistedOpenSale.totalCents(): Int {
    return lines.sumOf { line ->
        val gross = line.quantity * line.unitPriceCents
        val percentDiscount = line.discountPercent?.let { gross * it / 100 } ?: 0
        val amountDiscount = line.discountAmountCents ?: 0
        (gross - percentDiscount - amountDiscount).coerceAtLeast(0)
    }
}
