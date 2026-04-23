package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.TableAttentionFlag
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
            openSaleRepository.observeOpenSales(),
        ) { floorMap, backendTruthByTableId, openSales ->
            updateKnownBackendTableMappings(floorMap)
            val effectiveFloorMap = if (backendTruthByTableId.isEmpty()) {
                floorMap
            } else {
                buildAuthoritativeBackendFloorMap(
                    currentFloorMap = floorMap,
                    backendTruthByTableId = backendTruthByTableId,
                )
            }
            updateKnownBackendTableMappings(effectiveFloorMap)
            publishOpenBillContexts(
                floorMap = effectiveFloorMap,
                openSales = openSales,
            )
            if (backendTruthByTableId.isEmpty()) {
                Log.i(
                    "AIROS",
                    "[BackendTruthTableRepository] backend table truth unavailable; keeping current delegate floor map tables=${floorMap.tables.size}",
                )
                return@combine floorMap
            }
            val authoritativeFloorMap = effectiveFloorMap
            floorMapSink?.replaceBackendAuthoritativeFloorMap(authoritativeFloorMap)
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
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl() ?: return@withContext false
        val urlString = "$baseUrl/tables/$backendTableId/acknowledge-check"
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
                log("acknowledge-check rejected status=$statusCode")
                return@withContext false
            }
            true
        } catch (t: Throwable) {
            log("acknowledge-check failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            false
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

private data class BackendTableTruth(
    val tableId: Int,
    val tableName: String,
    val capacity: Int,
    val currentPersons: Int,
    val cameraId: String?,
    val cameraLabel: String?,
    val state: String,
    val attentionFlag: TableAttentionFlag,
    val emptyAnchorTime: String?,
    val reviewAnchorTime: String?,
    val reviewFrom: String?,
    val reviewTo: String?,
) {
    val posTableId: String
        get() = "table-$tableId"

    val tableStatus: TableStatus
        get() = when (state.trim().uppercase()) {
            "FREE" -> TableStatus.AVAILABLE
            "OCCUPIED" -> TableStatus.OCCUPIED
            "NEEDS_CLEANING", "DIRTY" -> TableStatus.DIRTY
            "RESERVED" -> TableStatus.RESERVED
            else -> TableStatus.AVAILABLE
        }
}

private fun RestaurantTable.withBackendTruth(truth: BackendTableTruth): RestaurantTable {
    return copy(
        backendTableId = truth.tableId,
        status = truth.tableStatus,
        guestCount = truth.currentPersons,
        attentionFlag = truth.attentionFlag,
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
): FloorMap {
    val currentTablesById = currentFloorMap.tables.associateBy(RestaurantTable::id)
    val authoritativeTables = backendTruthByTableId.values
        .sortedBy(BackendTableTruth::tableId)
        .mapIndexed { index, backendTruth ->
            val currentTable = currentTablesById[backendTruth.posTableId]
            buildAuthoritativeBackendTable(
                backendTruth = backendTruth,
                currentTable = currentTable,
                index = index,
            )
        }

    return FloorMap(
        id = BACKEND_FLOOR_MAP_ID,
        name = BACKEND_FLOOR_MAP_NAME,
        tables = authoritativeTables,
    )
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

private fun Int.toPosTableId(): String = "table-$this"

private fun PersistedOpenSale.totalCents(): Int {
    return lines.sumOf { line ->
        val gross = line.quantity * line.unitPriceCents
        val percentDiscount = line.discountPercent?.let { gross * it / 100 } ?: 0
        val amountDiscount = line.discountAmountCents ?: 0
        (gross - percentDiscount - amountDiscount).coerceAtLeast(0)
    }
}
