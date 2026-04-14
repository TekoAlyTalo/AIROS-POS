package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.TableAttentionFlag
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

private const val TABLE1_POS_SPOT_ID = "table-1"
private const val TABLE1_POS_SPOT_LABEL = "T1"
private const val TABLE1_BACKEND_TABLE_ID = 1

class BackendTruthTableRepository(
    private val delegate: TableRepository,
    private val openSaleRepository: OpenSaleRepository,
    backendBaseUrlProvider: () -> String,
    pollIntervalMillis: Long = 2_000L,
) : TableRepository {
    private val client = BackendTableTruthClient(
        backendBaseUrlProvider = backendBaseUrlProvider,
        pollIntervalMillis = pollIntervalMillis,
    )
    private var lastPublishedTable1ContextKey: String? = null

    override fun observeFloorMap(): Flow<FloorMap> {
        return combine(
            delegate.observeFloorMap(),
            client.observeBackendTableTruth(),
            openSaleRepository.observeOpenSales(),
        ) { floorMap, backendTruthByTableId, openSales ->
            publishTable1OpenBillContext(openSales)
            floorMap.copy(
                tables = floorMap.tables.map { table ->
                    if (table.id != TABLE1_POS_SPOT_ID) {
                        table
                    } else {
                        backendTruthByTableId[TABLE1_BACKEND_TABLE_ID]?.let(table::withBackendTruth) ?: run {
                            Log.w(
                                "AIROS",
                                "[BackendTruthTableRepository] Missing backend truth for $TABLE1_POS_SPOT_ID. POS will mark guest count unavailable instead of trusting fallback data.",
                            )
                            table
                        }
                    }
                },
            )
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

    private suspend fun publishTable1OpenBillContext(openSales: List<PersistedOpenSale>) {
        val table1Sales = openSales
            .filter { it.serviceSpotId == TABLE1_POS_SPOT_ID }
            .sortedBy { it.saleId }
        val contextKey = buildString {
            append(table1Sales.size)
            append('|')
            append(table1Sales.sumOf { it.totalCents() })
            append('|')
            append(table1Sales.joinToString(separator = ",") { it.saleId })
        }
        if (contextKey == lastPublishedTable1ContextKey) return

        val published = client.postOpenBillContext(
            backendTableId = TABLE1_BACKEND_TABLE_ID,
            serviceSpotId = TABLE1_POS_SPOT_ID,
            serviceSpotLabel = table1Sales.firstOrNull()?.serviceSpotLabel ?: TABLE1_POS_SPOT_LABEL,
            openBillCount = table1Sales.size,
            openSaleIds = table1Sales.map { it.saleId },
            openTotalCents = table1Sales.sumOf { it.totalCents() },
            oldestOpenSaleCreatedAtEpochMillis = table1Sales.minOfOrNull { it.createdAtEpochMillis },
        )
        if (published) {
            lastPublishedTable1ContextKey = contextKey
        }
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
                currentPersons = item.optInt("current_persons", 0).coerceAtLeast(0),
                state = item.optString("state", "READY"),
                attentionFlag = parseAttentionFlag(item.optString("attention_flag", "NONE")),
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
    val currentPersons: Int,
    val state: String,
    val attentionFlag: TableAttentionFlag,
    val reviewAnchorTime: String?,
    val reviewFrom: String?,
    val reviewTo: String?,
) {
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
        status = truth.tableStatus,
        guestCount = truth.currentPersons,
        attentionFlag = truth.attentionFlag,
        reviewAnchorTime = truth.reviewAnchorTime,
        reviewFrom = truth.reviewFrom,
        reviewTo = truth.reviewTo,
        truthSource = TableTruthSource.BACKEND,
    )
}

private fun parseAttentionFlag(raw: String): TableAttentionFlag {
    return when (raw.trim().uppercase()) {
        "CHECK_TABLE" -> TableAttentionFlag.CHECK_TABLE
        else -> TableAttentionFlag.NONE
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun PersistedOpenSale.totalCents(): Int {
    return lines.sumOf { line ->
        val gross = line.quantity * line.unitPriceCents
        val percentDiscount = line.discountPercent?.let { gross * it / 100 } ?: 0
        val amountDiscount = line.discountAmountCents ?: 0
        (gross - percentDiscount - amountDiscount).coerceAtLeast(0)
    }
}
