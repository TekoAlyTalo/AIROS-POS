package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.FloorMapArea
import com.airos.pos.core.model.FloorMapObject
import com.airos.pos.core.model.FloorPlanMarkerAnchor
import com.airos.pos.core.model.FloorPlanSofaStyle
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
import java.net.URLEncoder
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
private val LEGACY_LOCAL_SERVICE_SPOT_ID_REGEX = Regex("""table-\d+$""")

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
    private val lastPublishedOpenBillContextKeys = linkedMapOf<String, String>()
    private val warnedLegacyLocalOpenSales = linkedSetOf<String>()

    override fun observeFloorMap(): Flow<FloorMap> {
        return combine(
            delegate.observeFloorMap(),
            client.observeBackendTableTruth(),
            client.observeInUseFloorPlan(),
            openSaleRepository.observeOpenSales(),
        ) { floorMap, backendTruthByServiceSpotId, inUseFloorPlan, openSales ->
            val authoritativeFloorMap = buildAuthoritativeBackendFloorMap(
                currentFloorMap = floorMap,
                backendTruthByServiceSpotId = backendTruthByServiceSpotId,
                floorPlan = inUseFloorPlan,
            )
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
        val serviceSpotId = tableId.trim()
        if (serviceSpotId.isBlank()) {
            Log.w(
                "AIROS",
                "[BackendTruthTableRepository] CHECK acknowledge rejected for blank service spot id",
            )
            return false
        }
        return client.postAcknowledgeCheck(
            serviceSpotId = serviceSpotId,
            actorStaffId = actorStaffId,
            actorDisplayName = actorDisplayName,
        )
    }

    suspend fun markCleanedTable(
        tableId: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean {
        val serviceSpotId = tableId.trim()
        if (serviceSpotId.isBlank()) {
            Log.w(
                "AIROS",
                "[BackendTruthTableRepository] mark-cleaned rejected for blank service spot id",
            )
            return false
        }
        return client.postMarkCleaned(
            serviceSpotId = serviceSpotId,
            actorStaffId = actorStaffId,
            actorDisplayName = actorDisplayName,
        )
    }

    private suspend fun publishOpenBillContexts(
        floorMap: FloorMap,
        openSales: List<PersistedOpenSale>,
    ) {
        val floorTablesByServiceSpotId = floorMap.tables.associateBy(RestaurantTable::id)
        val activeServiceSpotIds = floorTablesByServiceSpotId.keys
        val stalePublishedKeys = lastPublishedOpenBillContextKeys.keys
            .filterNot { it in activeServiceSpotIds }
        stalePublishedKeys.forEach(lastPublishedOpenBillContextKeys::remove)

        val openSalesByServiceSpotId = openSales
            .mapNotNull { sale ->
                val serviceSpotId = sale.serviceSpotId?.trim().orEmpty()
                if (serviceSpotId.isBlank()) {
                    reportUnmappedOpenSale(sale = sale, serviceSpotId = serviceSpotId)
                    null
                } else if (!floorTablesByServiceSpotId.containsKey(serviceSpotId)) {
                    reportUnmappedOpenSale(sale = sale, serviceSpotId = serviceSpotId)
                    null
                } else {
                    serviceSpotId to sale
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            )

        floorMap.tables.forEach { table ->
            val serviceSpotId = table.id
            val tableSales = openSalesByServiceSpotId[serviceSpotId]
                .orEmpty()
                .sortedBy { it.saleId }
            val serviceSpotLabel = table.label
            val contextKey = buildString {
                append(serviceSpotId)
                append('|')
                append(tableSales.size)
                append('|')
                append(tableSales.sumOf { it.totalCents() })
                append('|')
                append(tableSales.joinToString(separator = ",") { it.saleId })
            }
            if (contextKey == lastPublishedOpenBillContextKeys[serviceSpotId]) return@forEach

            val published = client.postOpenBillContext(
                serviceSpotId = serviceSpotId,
                serviceSpotLabel = tableSales.firstOrNull()?.serviceSpotLabel ?: serviceSpotLabel,
                openBillCount = tableSales.size,
                openSaleIds = tableSales.map { it.saleId },
                openTotalCents = tableSales.sumOf { it.totalCents() },
                oldestOpenSaleCreatedAtEpochMillis = tableSales.minOfOrNull { it.createdAtEpochMillis },
            )
            if (published) {
                lastPublishedOpenBillContextKeys[serviceSpotId] = contextKey
            }
        }
    }

    private fun reportUnmappedOpenSale(
        sale: PersistedOpenSale,
        serviceSpotId: String,
    ) {
        val warningKey = sale.saleId.ifBlank { serviceSpotId.ifBlank { "<blank-service-spot-id>" } }
        if (!warnedLegacyLocalOpenSales.add(warningKey)) return

        if (LEGACY_LOCAL_SERVICE_SPOT_ID_REGEX.matches(serviceSpotId)) {
            Log.e(
                "AIROS",
                "[BackendTruthTableRepository] legacy local open sale detected saleId=${sale.saleId} serviceSpotId=$serviceSpotId. This id is ambiguous after the authoritative service-spot cutover and will not be auto-migrated. Clear the POS local open-sales Room data on the dev device or emulator before BAR1/T1 validation.",
            )
            return
        }

        Log.w(
            "AIROS",
            "[BackendTruthTableRepository] open sale could not be mapped to an active authoritative service spot saleId=${sale.saleId} serviceSpotId=${sale.serviceSpotId} serviceSpotLabel=${sale.serviceSpotLabel}",
        )
    }
}

private class BackendTableTruthClient(
    private val backendBaseUrlProvider: () -> String,
    private val pollIntervalMillis: Long,
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 2_000,
) {
    fun observeBackendTableTruth(): Flow<Map<String, BackendTableTruth>> = flow {
        var latest = emptyMap<String, BackendTableTruth>()
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
        // Eager first fetch so the initial combine() emission already carries the
        // authoritative floor plan (and thus the camera binding) instead of null.
        var latest: BackendFloorPlanSnapshot? = fetchInUseFloorPlan()
        emit(latest)
        while (true) {
            delay(pollIntervalMillis * 2)
            val next = fetchInUseFloorPlan()
            if (next != null || latest != null) {
                latest = next
                emit(latest)
            }
        }
    }

    suspend fun postOpenBillContext(
        serviceSpotId: String,
        serviceSpotLabel: String,
        openBillCount: Int,
        openSaleIds: List<String>,
        openTotalCents: Int,
        oldestOpenSaleCreatedAtEpochMillis: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext false
        val encodedServiceSpotId = encodePathSegment(serviceSpotId)
        val urlString = "$baseUrl/tables/by-service-spot/$encodedServiceSpotId/open-bill-context"
        val payload = JSONObject().apply {
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
        serviceSpotId: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = postTableAction(
        serviceSpotId = serviceSpotId,
        pathSegment = "acknowledge-check",
        logLabel = "acknowledge-check",
        actorStaffId = actorStaffId,
        actorDisplayName = actorDisplayName,
    )

    suspend fun postMarkCleaned(
        serviceSpotId: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = postTableAction(
        serviceSpotId = serviceSpotId,
        pathSegment = "mark-cleaned",
        logLabel = "mark-cleaned",
        actorStaffId = actorStaffId,
        actorDisplayName = actorDisplayName,
    )

    private suspend fun postTableAction(
        serviceSpotId: String,
        pathSegment: String,
        logLabel: String,
        actorStaffId: String? = null,
        actorDisplayName: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext false
        val encodedServiceSpotId = encodePathSegment(serviceSpotId)
        val urlString = "$baseUrl/tables/by-service-spot/$encodedServiceSpotId/$pathSegment"
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

    private suspend fun fetchTableOverviewTruth(): Map<String, BackendTableTruth>? = withContext(Dispatchers.IO) {
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
                val objectColor = obj.optStringOrNull("color")?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) }
                val backrestDirection = (obj.optStringOrNull("backrestDirection")
                    ?: obj.optStringOrNull("backrest_direction"))
                    ?.takeIf { it in setOf("top", "right", "bottom", "left") }
                val backrestMode = (obj.optStringOrNull("backrestMode")
                    ?: obj.optStringOrNull("backrest_mode"))
                    ?.takeIf { it in setOf("none", "backrest") }
                val armrestMode = (obj.optStringOrNull("armrestMode")
                    ?: obj.optStringOrNull("armrest_mode"))
                    ?.takeIf { it in setOf("both", "none", "left-only", "right-only") }
                val sofaStyle = parseFloorPlanSofaStyle(
                    obj.optStringOrNull("sofaStyle") ?: obj.optStringOrNull("sofa_style"),
                )
                val statusChipAnchor = FloorPlanMarkerAnchor.fromRawValue(
                    obj.optStringOrNull("statusChipAnchor") ?: obj.optStringOrNull("status_chip_anchor"),
                )
                val seatMarkerAnchor = FloorPlanMarkerAnchor.fromRawValue(
                    obj.optStringOrNull("seatMarkerAnchor") ?: obj.optStringOrNull("seat_marker_anchor"),
                )
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
                        color = objectColor,
                        backrestDirection = backrestDirection,
                        backrestMode = backrestMode,
                        statusChipAnchor = statusChipAnchor,
                        seatMarkerAnchor = seatMarkerAnchor,
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
                        color = objectColor,
                        backrestDirection = backrestDirection,
                        backrestMode = backrestMode,
                        armrestMode = armrestMode,
                        sofaStyle = sofaStyle,
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
    private fun parseTableOverview(body: String): Map<String, BackendTableTruth> {
        if (body.isBlank()) return emptyMap()
        val root = JSONObject(body)
        val tables = root.optJSONArray("tables") ?: return emptyMap()
        val result = linkedMapOf<String, BackendTableTruth>()
        for (i in 0 until tables.length()) {
            val item = tables.optJSONObject(i) ?: continue
            val tableId = item.optInt("table_id", -1)
            if (tableId <= 0) continue
            val serviceSpotId = item.optStringOrNull("service_spot_id") ?: continue
            result[serviceSpotId] = BackendTableTruth(
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
                serviceSpotId = serviceSpotId,
                serviceSpotKind = item.optStringOrNull("service_spot_kind"),
                maxOpenBills = item.optIntOrNull("max_open_bills"),
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

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
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
    val color: String?,
    val backrestDirection: String?,
    val backrestMode: String?,
    val statusChipAnchor: FloorPlanMarkerAnchor?,
    val seatMarkerAnchor: FloorPlanMarkerAnchor?,
)
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
    /** Stable backend service-spot identity. Null only for legacy rows the
     *  backend has not reconciled to a floor plan yet. */
    val serviceSpotId: String?,
    /** "table" | "bar_stool". Null when backend has no kind asserted. POS
     *  must NOT default null to "table". */
    val serviceSpotKind: String?,
    /** Per-spot bill cap. Null = unbounded. */
    val maxOpenBills: Int?,
) {
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
    backendTruthByServiceSpotId: Map<String, BackendTableTruth>,
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

    // Authoritative camera binding lives in the active floor plan JSON. Build a
    // service-spot -> camera-object index from floorPlan.objects and let it win
    // over /tables/overview's camera_id field, so camera preview targets resolve
    // as soon as the floor plan is available and never depend on overview latency.
    val cameraByServiceSpotId: Map<String, FloorMapObject> = floorPlan.objects
        .asSequence()
        .filter { it.type.equals("camera", ignoreCase = true) }
        .filter { it.linkedTargetType?.equals("table", ignoreCase = true) == true }
        .filter { !it.linkedTargetId.isNullOrBlank() && !it.cameraId.isNullOrBlank() }
        .groupBy { it.linkedTargetId!! }
        .mapValues { (targetId, candidates) ->
            if (candidates.size > 1) {
                Log.w(
                    "AIROS",
                    "[BackendTruthTableRepository] floor plan has ${candidates.size} cameras pointing at " +
                        "linkedTargetId=$targetId; using first cameraId=${candidates.first().cameraId}",
                )
            }
            candidates.first()
        }

    val floorPlanTables = floorPlan.tableObjects.map { tableObject ->
        val serviceSpotId = tableObject.id
        val backendTruth = backendTruthByServiceSpotId[serviceSpotId]
        val currentTable = currentTablesById[serviceSpotId]
        val floorPlanCamera = cameraByServiceSpotId[serviceSpotId]
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
        val resolvedCameraId = floorPlanCamera?.cameraId
            ?: backendTruth?.cameraId
            ?: currentTable?.cameraId
        val resolvedCameraLabel = floorPlanCamera?.label?.takeIf { it.isNotBlank() }
            ?: floorPlanCamera?.cameraId
            ?: backendTruth?.cameraLabel
            ?: backendTruth?.cameraId
            ?: currentTable?.cameraLabel
        RestaurantTable(
            id = serviceSpotId,
            backendTableId = backendTruth?.tableId,
            label = tableObject.label.ifBlank {
                backendTruth?.tableName ?: currentTable?.label.orEmpty()
            },
            areaName = areaName,
            seats = tableObject.capacity?.takeIf { it > 0 } ?: backendTruth?.capacity ?: currentTable?.seats ?: 1,
            status = backendTruth?.tableStatus ?: currentTable?.status ?: TableStatus.AVAILABLE,
            guestCount = backendTruth?.currentPersons ?: currentTable?.guestCount ?: 0,
            activeTicketId = currentTable?.activeTicketId,
            position = position,
            cameraId = resolvedCameraId,
            cameraLabel = resolvedCameraLabel,
            attentionFlag = backendTruth?.attentionFlag ?: currentTable?.attentionFlag ?: TableAttentionFlag.NONE,
            operationalFlags = backendTruth?.operationalFlags ?: currentTable?.operationalFlags ?: emptySet(),
            emptyAnchorTime = backendTruth?.emptyAnchorTime ?: currentTable?.emptyAnchorTime,
            reviewAnchorTime = backendTruth?.reviewAnchorTime ?: currentTable?.reviewAnchorTime,
            reviewFrom = backendTruth?.reviewFrom ?: currentTable?.reviewFrom,
            reviewTo = backendTruth?.reviewTo ?: currentTable?.reviewTo,
            truthSource = if (backendTruth != null) TableTruthSource.BACKEND else currentTable?.truthSource ?: TableTruthSource.LOCAL,
            spotType = resolveServiceSpotType(
                backendKind = backendTruth?.serviceSpotKind,
                fallback = currentTable?.spotType,
            ),
            maxOpenBills = backendTruth?.maxOpenBills ?: currentTable?.maxOpenBills,
            floorPlanX = tableObject.x,
            floorPlanY = tableObject.y,
            floorPlanWidth = tableObject.width,
            floorPlanHeight = tableObject.height,
            floorPlanRotation = tableObject.rotation,
            floorPlanShape = tableObject.shape,
            chairLayout = tableObject.chairLayout,
            tableNumber = tableObject.tableNumber,
            color = tableObject.color,
            backrestDirection = tableObject.backrestDirection,
            backrestMode = tableObject.backrestMode,
            statusChipAnchor = tableObject.statusChipAnchor,
            seatMarkerAnchor = tableObject.seatMarkerAnchor,
        )
    }

    Log.i(
        "AIROS",
        "[BackendTruthTableRepository] floor map built tables=${floorPlanTables.size} " +
            "withFloorPlanCamera=${cameraByServiceSpotId.size} " +
            "withOverviewCamera=${backendTruthByServiceSpotId.values.count { !it.cameraId.isNullOrBlank() }} " +
            "withResolvedCamera=${floorPlanTables.count { !it.cameraId.isNullOrBlank() }} " +
            "withBackendTruth=${floorPlanTables.count { it.backendTableId != null }} " +
            "overviewTruthCount=${backendTruthByServiceSpotId.size}",
    )

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

private fun resolveServiceSpotType(
    backendKind: String?,
    fallback: ServiceSpotType?,
): ServiceSpotType {
    return when (backendKind?.trim()?.lowercase()) {
        "bar_stool" -> ServiceSpotType.BAR_SEAT
        "table" -> ServiceSpotType.TABLE
        else -> fallback ?: ServiceSpotType.TABLE
    }
}

private fun parseFloorPlanSofaStyle(raw: String?): FloorPlanSofaStyle? {
    return when (raw?.trim()?.lowercase()) {
        "premium_leather", "premium-leather", "leather", "premium" -> FloorPlanSofaStyle.PREMIUM_LEATHER
        "terrace_poly_rattan", "terrace-poly-rattan", "poly_rattan", "poly-rattan", "rattan" -> FloorPlanSofaStyle.TERRACE_POLY_RATTAN
        else -> null
    }
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
