package com.airos.pos.app

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.LocalFinalizedSalePaymentRecord
import com.airos.pos.core.model.LocalSalesDayReport
import com.airos.pos.core.model.LocalSalesPaymentBreakdown
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.core.model.PersistedOpenSaleTransferEvent
import com.airos.pos.core.ui.PosPane
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.LedgerCreateCorrectionRequest
import com.airos.pos.domain.LedgerCorrectionOperationRequest
import com.airos.pos.domain.LedgerCorrectionSaleResponse
import com.airos.pos.domain.OpenSaleRepository
import com.airos.pos.domain.SalesDayReportRepository
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private enum class TransactionsScope {
    CURRENT,
    PAST,
}

private enum class TransactionsStatusFilter {
    ALL,
    OPEN,
    PAID,
    CORRECTION,
}

private enum class TransactionsPaymentFilter {
    ALL,
    CASH,
    CARD,
    VOUCHER,
    MIXED,
}

private enum class TransactionsSortOption {
    TIME_DESC,
    TIME_ASC,
    AMOUNT_DESC,
    AMOUNT_ASC,
}

private enum class CorrectionOperationType(val backendCode: String, val label: String) {
    REMOVE_LINE("REMOVE_LINE", "Poista tuote/rivi"),
    CHANGE_QUANTITY("CHANGE_QUANTITY", "Muuta määrää"),
    APPLY_DISCOUNT("APPLY_DISCOUNT", "Alennus"),
    MONETARY_REFUND("MONETARY_REFUND", "Rahallinen hyvitys"),
    NON_MONETARY_COMPENSATION("NON_MONETARY_COMPENSATION", "Ei-rahallinen hyvitys"),
    FULL_REVERSAL("FULL_REVERSAL", "Koko kuitin peruutus"),
    ADD_LINE("ADD_LINE", "Lisää tuote"),
    REPLACE_LINE("REPLACE_LINE", "Vaihda tuote"),
}

private enum class TransactionBusinessStatus {
    OPEN,
    PAID,
}

private enum class TransactionSyncStatus {
    QUEUED,
    SYNCING,
    SYNCED,
    FAILED,
    BLOCKED,
    UNKNOWN,
}

private data class TransactionPaymentSnapshot(
    val methodCode: String,
    val amountCents: Int,
    val displayLabel: String? = null,
)

private data class TransactionTimelineEntry(
    val id: String,
    val titleKey: CashierStringKey,
    val occurredAtEpochMillis: Long,
    val supportingText: String? = null,
)

private data class BackendSaleLine(
    val id: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val lineTotalCents: Int,
    val vatRateBasisPoints: Int?,
)

private data class BackendPaymentLine(
    val method: String,
    val amountCents: Int,
    val displayLabel: String? = null,
)

private data class BackendCorrectionSummary(
    val correctionId: String?,
    val correctionSaleId: String?,
    val correctionReceiptNumber: String?,
    val correctionKind: String?,
    val reason: String?,
    val financialEffectCents: Int?,
)

private data class BackendSaleDetail(
    val saleId: String,
    val receiptNumber: String?,
    val totalCents: Int,
    val tableLabel: String?,
    val saleKind: String,
    val correctionOriginalReceiptNumber: String?,
    val lineItems: List<BackendSaleLine>,
    val payments: List<BackendPaymentLine>,
    val corrections: List<BackendCorrectionSummary>,
)

private data class BusinessDayReportResult(
    val report: LocalSalesDayReport,
    val transactions: List<TransactionRecord>,
    val businessDate: String,
    val windowLabel: String,
    val truthMessage: String? = null,
)

private data class BusinessDayWindowTruth(
    val businessDate: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val truthAvailable: Boolean,
    val source: String?,
    val missingReason: String?,
) {
    val windowLabel: String
        get() = "${formatUiDateTime(startEpochMillis)} - ${formatUiDateTime(endEpochMillis)}"
}

private data class CorrectionSubmitDraft(
    val operationType: CorrectionOperationType,
    val selectedLineId: String?,
    val quantityDeltaText: String,
    val discountCentsText: String,
    val refundAmountCentsText: String,
    val refundMethod: String,
    val compensationType: String,
    val compensationDetails: String,
    val reason: String,
)

private data class TransactionRecord(
    val stableId: String,
    val source: TransactionsScope,
    val occurredAtEpochMillis: Long,
    val updatedAtEpochMillis: Long? = null,
    val receiptNumber: String? = null,
    val saleId: String? = null,
    val tableId: String? = null,
    val tableLabel: String? = null,
    val actorStaffId: String? = null,
    val actorDisplayName: String? = null,
    val amountCents: Int,
    val businessStatus: TransactionBusinessStatus,
    val syncStatus: TransactionSyncStatus? = null,
    val paymentMethods: List<TransactionPaymentSnapshot> = emptyList(),
    val openSale: PersistedOpenSale? = null,
    val transferEvents: List<PersistedOpenSaleTransferEvent> = emptyList(),
    val timeline: List<TransactionTimelineEntry> = emptyList(),
    val publicReceiptUrl: String? = null,
    val publicUrlPath: String? = null,
    val saleKind: String = "NORMAL_SALE",
    val correctionOriginalSaleId: String? = null,
    val correctionOriginalReceiptNumber: String? = null,
    val correctionReason: String? = null,
    val correctionAmountCents: Int? = null,
    val structuredLineNames: List<String> = emptyList(),
    // Backend-assigned sale id (from LedgerFinalizeSaleResponse.sale_id).
    // Required to call POST /api/pos/sales/{serverSaleId}/corrections.
    // Null for pre-existing sales where ledger sync was not available.
    val serverSaleId: String? = null,
)

private data class TransactionsUiState(
    val scope: TransactionsScope = TransactionsScope.CURRENT,
    val searchQuery: String = "",
    val statusFilter: TransactionsStatusFilter = TransactionsStatusFilter.ALL,
    val paymentFilter: TransactionsPaymentFilter = TransactionsPaymentFilter.ALL,
    val sortOption: TransactionsSortOption = TransactionsSortOption.TIME_DESC,
    val currentEvents: List<TransactionRecord> = emptyList(),
    val pastEvents: List<TransactionRecord> = emptyList(),
    val selectedEventId: String? = null,
    val localDayReport: LocalSalesDayReport? = null,
    val businessDayLoading: Boolean = false,
    val businessDayMessage: String? = null,
    val businessDayDate: String? = null,
    val businessDayWindowLabel: String? = null,
    val correctionDialogEventId: String? = null,
    val correctionOperationType: CorrectionOperationType = CorrectionOperationType.FULL_REVERSAL,
    val correctionSaleDetail: BackendSaleDetail? = null,
    val correctionDetailLoading: Boolean = false,
    val correctionSelectedLineId: String? = null,
    val correctionQuantityDelta: String = "-1",
    val correctionDiscountCents: String = "",
    val correctionRefundAmountCents: String = "",
    val correctionRefundMethod: String = "CARD",
    val correctionCompensationType: String = "FREE_MEAL",
    val correctionCompensationDetails: String = "",
    val correctionReason: String = "",
    val correctionBusy: Boolean = false,
    val correctionMessage: String? = null,
)

private data class PaymentMethodBucket(
    val methodCode: String,
    val label: String,
    val amountCents: Int,
    val totalCents: Int,
)

private class TransactionsRepository(
    private val openSaleRepository: OpenSaleRepository,
    private val salesDayReportRepository: SalesDayReportRepository,
    private val ledgerHttpClient: AirosPosLedgerHttpClient,
    private val backendBaseUrl: String?,
) {
    fun observeCurrentTransactions(): Flow<List<TransactionRecord>> {
        return combine(
            openSaleRepository.observeOpenSales(),
            openSaleRepository.observeOpenSaleTransferEvents(),
        ) { openSales, transferEvents ->
            val transferEventsBySaleId = transferEvents.groupBy { it.saleId }
            openSales
                .sortedByDescending { it.createdAtEpochMillis }
                .map { sale ->
                    val saleTransfers = transferEventsBySaleId[sale.saleId].orEmpty().sortedBy { it.occurredAtEpochMillis }
                    TransactionRecord(
                        stableId = "current:${sale.saleId}",
                        source = TransactionsScope.CURRENT,
                        occurredAtEpochMillis = sale.createdAtEpochMillis,
                        updatedAtEpochMillis = sale.updatedAtEpochMillis,
                        receiptNumber = null,
                        saleId = sale.saleId,
                        tableId = sale.serviceSpotId,
                        tableLabel = sale.serviceSpotLabel,
                        actorStaffId = null,
                        actorDisplayName = null,
                        amountCents = sale.lines.sumOf { it.totalCents() },
                        businessStatus = TransactionBusinessStatus.OPEN,
                        syncStatus = null,
                        paymentMethods = emptyList(),
                        openSale = sale,
                        transferEvents = saleTransfers,
                        timeline = buildCurrentTimeline(sale, saleTransfers),
                        publicReceiptUrl = null,
                        publicUrlPath = null,
                        structuredLineNames = sale.lines.map { it.name },
                    )
                }
        }
    }

    suspend fun loadBusinessDayReport(): PosResult<BusinessDayReportResult> {
        val baseUrl = backendBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (baseUrl.isBlank()) {
            return PosResult.Failure("Päivän myyntiä ei voida näyttää: backend-osoite puuttuu.")
        }

        val today = LocalDate.now()
        val todayWindow = when (val result = fetchBusinessDayWindow(baseUrl, today.toString())) {
            is PosResult.Failure -> return result
            is PosResult.Success -> result.value
        }
        if (!todayWindow.truthAvailable) {
            return PosResult.Failure(todayWindow.missingReason ?: "Päivän myynnin liiketoimintapäivän totuus puuttuu.")
        }

        val now = System.currentTimeMillis()
        val selectedDate = if (now < todayWindow.startEpochMillis) today.minusDays(1) else today
        val selectedWindow = if (selectedDate == today) {
            todayWindow
        } else {
            when (val result = fetchBusinessDayWindow(baseUrl, selectedDate.toString())) {
                is PosResult.Failure -> return result
                is PosResult.Success -> result.value
            }
        }
        if (!selectedWindow.truthAvailable) {
            return PosResult.Failure(
                selectedWindow.missingReason ?: "Päivän myynnin liiketoimintapäivän totuus puuttuu.",
            )
        }

        val dayJson = when (val result = fetchBackendJson(baseUrl, "/api/pos/reports/day?business_date=$selectedDate")) {
            is PosResult.Failure -> return result
            is PosResult.Success -> result.value
        }
        val selectedRange = dayJson.optJSONObject("selected_range")
        val startMs = selectedRange?.optLongOrNull("start_epoch_ms") ?: selectedWindow.startEpochMillis
        val endMs = selectedRange?.optLongOrNull("end_epoch_ms") ?: selectedWindow.endEpochMillis

        val transactionJson = when (
            val result = fetchBackendJson(
                baseUrl,
                "/api/pos/reports/transactions/search?start=$startMs&end=$endMs",
            )
        ) {
            is PosResult.Failure -> return result
            is PosResult.Success -> result.value
        }

        val rows = transactionJson.optJSONArray("results").orEmptyJsonArray()
            .mapObjects()
            .map { row -> row.toBackendTransactionRecord(baseUrl) }
        val paymentBreakdown = dayJson.optJSONArray("payments_summary").orEmptyJsonArray()
            .mapObjects()
            .map { payment ->
                LocalSalesPaymentBreakdown(
                    method = payment.optString("payment_method").ifBlank { "UNKNOWN" },
                    amountCents = payment.optInt("amount_total_cents", 0),
                    paymentCount = payment.optInt("transaction_count", 0),
                )
            }
        val cashCents = paymentBreakdown
            .filter { it.method.equals(PaymentMethod.CASH.name, ignoreCase = true) }
            .sumOf { it.amountCents }
        val cardCents = paymentBreakdown
            .filter { it.method.equals(PaymentMethod.CARD.name, ignoreCase = true) }
            .sumOf { it.amountCents }
        val voucherCents = paymentBreakdown
            .filter { it.method.equals(PaymentMethod.VOUCHER.name, ignoreCase = true) }
            .sumOf { it.amountCents }
        val knownMethods = setOf(PaymentMethod.CASH.name, PaymentMethod.CARD.name, PaymentMethod.VOUCHER.name)
        val otherCents = paymentBreakdown
            .filterNot { knownMethods.contains(it.method.uppercase(Locale.ROOT)) }
            .sumOf { it.amountCents }
        val report = LocalSalesDayReport(
            startEpochMillisInclusive = startMs,
            endEpochMillisExclusive = endMs,
            totalSalesCents = dayJson.optInt("gross_sales_total_cents", rows.sumOf { it.amountCents }),
            saleCount = dayJson.optInt("total_sales_count", rows.size),
            paymentBreakdown = paymentBreakdown,
            cashSalesCents = cashCents,
            cardSalesCents = cardCents,
            voucherSalesCents = voucherCents,
            otherSalesCents = otherCents,
            refundCount = rows.count { it.amountCents < 0 },
            refundCents = rows.filter { it.amountCents < 0 }.sumOf { it.amountCents },
            refundsSupported = true,
        )
        return PosResult.Success(
            BusinessDayReportResult(
                report = report,
                transactions = rows,
                businessDate = selectedDate.toString(),
                windowLabel = selectedWindow.windowLabel,
                // TODO: expose selectedWindow.source behind a proper owner/admin/debug setting.
                // Normal cashier UI must not show backend source diagnostics as an error.
                truthMessage = null,
            ),
        )
    }

    suspend fun fetchSaleDetail(event: TransactionRecord): PosResult<BackendSaleDetail> {
        val baseUrl = backendBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (baseUrl.isBlank()) {
            return PosResult.Failure("Myynnin rivitietoja ei voida hakea: backend-osoite puuttuu.")
        }
        val saleId = event.serverSaleId?.trim().orEmpty()
        if (saleId.isBlank()) {
            return PosResult.Failure("Korjausmyynti ei ole saatavilla: myynniltä puuttuu backendin myyntitunniste.")
        }
        return when (
            val result = fetchBackendJson(
                baseUrl,
                "/api/pos/sales/${urlPathSegment(saleId)}/receipt/preview/json",
            )
        ) {
            is PosResult.Failure -> result
            is PosResult.Success -> PosResult.Success(result.value.toBackendSaleDetail(event))
        }
    }

    suspend fun createCorrectionSale(
        event: TransactionRecord,
        detail: BackendSaleDetail?,
        draft: CorrectionSubmitDraft,
        actorStaffId: String?,
        actorName: String?,
    ): PosResult<LedgerCorrectionSaleResponse> {
        val originalSaleId = event.serverSaleId?.trim().orEmpty()
        if (originalSaleId.isBlank()) {
            return PosResult.Failure(
                "Korjausmyyntiä ei voida luoda: myynniltä puuttuu backendin myyntitunniste. " +
                    "Myynti on saatettu tallentaa offline-tilassa eikä sitä ole vielä synkronoitu palvelimelle."
            )
        }
        val resolvedReason = draft.reason.trim()
        if (resolvedReason.isBlank()) {
            return PosResult.Failure("Korjausmyynnin syy on pakollinen.")
        }
        val operations = buildCorrectionOperations(event, detail, draft)
        if (operations.isEmpty()) {
            return PosResult.Failure("Valitse tuettu korjaustoimenpide.")
        }
        val sourcePosEventId = "pos-correction-${UUID.randomUUID()}"
        val refundMethod = if (draft.operationType == CorrectionOperationType.MONETARY_REFUND) {
            draft.refundMethod.trim().uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
        } else {
            null
        }
        val compensationType = if (draft.operationType == CorrectionOperationType.NON_MONETARY_COMPENSATION) {
            draft.compensationType.trim().uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
        } else {
            null
        }
        val compensationDetails = if (draft.operationType == CorrectionOperationType.NON_MONETARY_COMPENSATION) {
            mapOf("details" to draft.compensationDetails.trim().takeIf { it.isNotBlank() })
        } else {
            null
        }
        return ledgerHttpClient.createCorrectionSale(
            originalSaleId = originalSaleId,
            request = LedgerCreateCorrectionRequest(
                reason = resolvedReason,
                actor_staff_id = actorStaffId?.takeIf { it.isNotBlank() },
                actor_name = actorName?.takeIf { it.isNotBlank() },
                source_pos_event_id = sourcePosEventId,
                idempotency_key = sourcePosEventId,
                target_sale_id = event.serverSaleId,
                operations = operations,
                refund_method = refundMethod,
                settlement_method = refundMethod,
                compensation_type = compensationType,
                compensation_details = compensationDetails,
                finalized_at_epoch_ms = System.currentTimeMillis(),
            ),
        )
    }

    private fun buildCorrectionOperations(
        event: TransactionRecord,
        detail: BackendSaleDetail?,
        draft: CorrectionSubmitDraft,
    ): List<LedgerCorrectionOperationRequest> {
        val selectedLine = detail?.lineItems?.firstOrNull { it.id == draft.selectedLineId }
        return when (draft.operationType) {
            CorrectionOperationType.REMOVE_LINE -> {
                if (selectedLine == null) return emptyList()
                listOf(
                    LedgerCorrectionOperationRequest(
                        operation_type = draft.operationType.backendCode,
                        original_line_id = selectedLine.id,
                    ),
                )
            }
            CorrectionOperationType.CHANGE_QUANTITY -> {
                if (selectedLine == null) return emptyList()
                val delta = draft.quantityDeltaText.trim().toIntOrNull() ?: return emptyList()
                if (delta == 0) return emptyList()
                listOf(
                    LedgerCorrectionOperationRequest(
                        operation_type = draft.operationType.backendCode,
                        original_line_id = selectedLine.id,
                        quantity_delta = delta,
                    ),
                )
            }
            CorrectionOperationType.APPLY_DISCOUNT -> {
                if (selectedLine == null) return emptyList()
                val discount = draft.discountCentsText.euroTextToCentsOrNull()?.takeIf { it > 0 } ?: return emptyList()
                listOf(
                    LedgerCorrectionOperationRequest(
                        operation_type = draft.operationType.backendCode,
                        original_line_id = selectedLine.id,
                        discount_cents = discount,
                    ),
                )
            }
            CorrectionOperationType.MONETARY_REFUND -> {
                val amount = draft.refundAmountCentsText.euroTextToCentsOrNull()?.takeIf { it > 0 }
                    ?: kotlin.math.abs(event.amountCents)
                listOf(
                    LedgerCorrectionOperationRequest(
                        operation_type = draft.operationType.backendCode,
                        financial_effect_cents = -kotlin.math.abs(amount),
                    ),
                )
            }
            CorrectionOperationType.NON_MONETARY_COMPENSATION -> listOf(
                LedgerCorrectionOperationRequest(
                    operation_type = draft.operationType.backendCode,
                    financial_effect_cents = 0,
                    metadata = mapOf("details" to draft.compensationDetails.trim().takeIf { it.isNotBlank() }),
                ),
            )
            CorrectionOperationType.FULL_REVERSAL -> listOf(
                LedgerCorrectionOperationRequest(operation_type = draft.operationType.backendCode),
            )
            CorrectionOperationType.ADD_LINE,
            CorrectionOperationType.REPLACE_LINE -> emptyList()
        }
    }

    private suspend fun fetchBusinessDayWindow(baseUrl: String, businessDate: String): PosResult<BusinessDayWindowTruth> {
        return when (
            val json = fetchBackendJson(
                baseUrl,
                "/api/pos/reports/business-day-window?business_date=$businessDate",
            )
        ) {
            is PosResult.Failure -> json
            is PosResult.Success -> PosResult.Success(json.value.toBusinessDayWindowTruth(businessDate))
        }
    }

    private suspend fun fetchBackendJson(baseUrl: String, path: String): PosResult<JSONObject> {
        return withContext(Dispatchers.IO) {
            val urlString = "$baseUrl/${path.trimStart('/')}"
            val connection = try {
                (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    doInput = true
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                }
            } catch (t: Throwable) {
                return@withContext PosResult.Failure("Backend-yhteys epäonnistui: ${t.message ?: t.javaClass.simpleName}")
            }

            try {
                val status = connection.responseCode
                val body = readResponseBody(if (status in 200..299) connection.inputStream else connection.errorStream)
                if (status !in 200..299) {
                    return@withContext PosResult.Failure(parseBackendErrorMessage(body, status))
                }
                PosResult.Success(JSONObject(body))
            } catch (t: Throwable) {
                PosResult.Failure("Backend-vastauksen käsittely epäonnistui: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun buildCurrentTimeline(
        sale: PersistedOpenSale,
        transferEvents: List<PersistedOpenSaleTransferEvent>,
    ): List<TransactionTimelineEntry> {
        val timeline = mutableListOf(
            TransactionTimelineEntry(
                id = "opened:${sale.saleId}",
                titleKey = CashierStringKey.TransactionsTimelineOpened,
                occurredAtEpochMillis = sale.createdAtEpochMillis,
                supportingText = sale.serviceSpotLabel ?: sale.serviceSpotId,
            ),
        )
        transferEvents.forEach { event ->
            timeline += TransactionTimelineEntry(
                id = "transfer:${event.id}",
                titleKey = CashierStringKey.TransactionsTimelineTransferred,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
                supportingText = buildString {
                    append(event.fromServiceSpotLabel ?: event.fromServiceSpotId ?: "Walk-in")
                    append(" -> ")
                    append(event.toServiceSpotLabel ?: event.toServiceSpotId ?: "Walk-in")
                    append(" | ")
                    append(event.actedByDisplayName)
                },
            )
        }
        return timeline.sortedBy { it.occurredAtEpochMillis }
    }

}

private fun LocalFinalizedSaleRecord.toTransactionRecord(backendBaseUrl: String?): TransactionRecord {
    val resolvedPublicReceiptUrl = resolvePublicReceiptUrl(
        publicReceiptUrl = publicReceiptUrl,
        publicUrlPath = publicUrlPath,
        backendBaseUrl = backendBaseUrl,
    )
    return TransactionRecord(
        stableId = "paid:$id",
        source = TransactionsScope.PAST,
        occurredAtEpochMillis = finalizedAtEpochMillis,
        updatedAtEpochMillis = finalizedAtEpochMillis,
        receiptNumber = receiptNumber,
        saleId = id,
        tableId = tableId,
        tableLabel = tableLabel,
        actorStaffId = sellerStaffId,
        actorDisplayName = sellerDisplayName,
        amountCents = if (saleKind == "CORRECTION") correctionAmountCents ?: -kotlin.math.abs(totalCents) else totalCents,
        businessStatus = TransactionBusinessStatus.PAID,
        syncStatus = TransactionSyncStatus.SYNCED,
        paymentMethods = payments.map { payment ->
            TransactionPaymentSnapshot(
                methodCode = payment.method.name,
                amountCents = payment.amountCents,
                displayLabel = payment.method.name,
            )
        },
        openSale = null,
        transferEvents = emptyList(),
        timeline = listOf(
            TransactionTimelineEntry(
                id = "finalized:$id",
                titleKey = CashierStringKey.TransactionsTimelineFinalized,
                occurredAtEpochMillis = finalizedAtEpochMillis,
                supportingText = receiptNumber,
            ),
        ),
        publicReceiptUrl = resolvedPublicReceiptUrl,
        publicUrlPath = publicUrlPath,
        saleKind = saleKind,
        correctionOriginalSaleId = correctionOriginalSaleId,
        correctionOriginalReceiptNumber = correctionOriginalReceiptNumber,
        correctionReason = correctionReason,
        correctionAmountCents = correctionAmountCents,
        serverSaleId = serverSaleId,
    )
}

private fun LedgerCorrectionSaleResponse.toLocalFinalizedSaleRecord(
    originalEvent: TransactionRecord,
    sourcePosEventId: String,
    backendBaseUrl: String?,
    actorStaffId: String?,
    actorName: String?,
): LocalFinalizedSaleRecord {
    val publicUrl = backendBaseUrl?.takeIf { it.isNotBlank() }?.let { absolutePublicReceiptUrl(it) }
    return LocalFinalizedSaleRecord(
        id = correction_sale_id,
        sourcePosEventId = sourcePosEventId,
        ticketId = null,
        openSaleId = null,
        receiptNumber = correction_receipt_number,
        receiptSnapshotId = receipt_snapshot_id,
        publicReceiptUrl = publicUrl,
        publicUrlPath = public_url_path,
        tableId = originalEvent.tableId,
        tableLabel = originalEvent.tableLabel,
        finalizedAtEpochMillis = finalized_at_epoch_ms,
        totalCents = correction_amount_cents,
        sellerStaffId = actorStaffId,
        sellerDisplayName = actorName,
        terminalId = null,
        restaurantId = null,
        saleKind = "CORRECTION",
        correctionOriginalSaleId = original_sale_id,
        correctionOriginalReceiptNumber = original_receipt_number,
        correctionReason = correction_reason,
        correctionAmountCents = correction_amount_cents,
        serverSaleId = correction_sale_id,
        payments = originalEvent.paymentMethods.map { payment ->
            LocalFinalizedSalePaymentRecord(
                method = requireNotNull(payment.methodCode.toPaymentMethodOrNull()),
                amountCents = -kotlin.math.abs(payment.amountCents),
            )
        },
    )
}

private fun String.toPaymentMethodOrNull(): PaymentMethod? {
    return when (trim().uppercase(Locale.ROOT)) {
        PaymentMethod.CASH.name -> PaymentMethod.CASH
        PaymentMethod.CARD.name -> PaymentMethod.CARD
        PaymentMethod.VOUCHER.name -> PaymentMethod.VOUCHER
        else -> null
    }
}

private fun resolvePublicReceiptUrl(
    publicReceiptUrl: String?,
    publicUrlPath: String?,
    backendBaseUrl: String?,
): String? {
    val directUrl = publicReceiptUrl?.trim().orEmpty()
    if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) return directUrl

    val rawPath = publicUrlPath?.trim().orEmpty()
    if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) return rawPath

    val baseUrl = backendBaseUrl?.trim()?.trimEnd('/').orEmpty()
    if (baseUrl.isBlank() || rawPath.isBlank()) return null
    return "$baseUrl/${rawPath.trimStart('/')}"
}

private fun readResponseBody(stream: InputStream?): String {
    if (stream == null) return ""
    return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
        buildString {
            while (true) {
                val line = reader.readLine() ?: break
                append(line)
                append('\n')
            }
        }.trim()
    }
}

private fun parseBackendErrorMessage(body: String, status: Int): String {
    if (body.isBlank()) return "Backend palautti virheen (HTTP $status)."
    return runCatching {
        val json = JSONObject(body)
        when (val detail = json.opt("detail")) {
            is String -> detail
            is JSONObject -> {
                detail.optCleanString("missing_reason")
                    ?: detail.optCleanString("detail")
                    ?: "Liiketoimintapäivän totuus puuttuu tai on virheellinen."
            }
            else -> json.optCleanString("message") ?: "Backend palautti virheen (HTTP $status)."
        }
    }.getOrElse {
        "Backend palautti virheen (HTTP $status)."
    }
}

private fun urlPathSegment(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private fun JSONObject.toBusinessDayWindowTruth(requestedBusinessDate: String): BusinessDayWindowTruth {
    val startMs = optLongOrNull("start_epoch_ms")
    val endMs = optLongOrNull("end_exclusive_epoch_ms") ?: optLongOrNull("end_epoch_ms")
    val truth = optBoolean("truth_available", false) && startMs != null && endMs != null && endMs > startMs
    return BusinessDayWindowTruth(
        businessDate = optCleanString("business_date") ?: requestedBusinessDate,
        startEpochMillis = startMs ?: 0L,
        endEpochMillis = endMs ?: 0L,
        truthAvailable = truth,
        source = optCleanString("source"),
        missingReason = optCleanString("missing_reason")
            ?: if (!truth) "Liiketoimintapäivän asetukset puuttuvat tai ovat virheelliset." else null,
    )
}

private fun JSONObject.toBackendTransactionRecord(backendBaseUrl: String?): TransactionRecord {
    val saleId = requireCleanString("sale_id")
    val receiptNumber = optCleanString("receipt_number")
    val payments = optJSONArray("payment_summary").orEmptyJsonArray()
        .mapStrings()
        .mapNotNull { raw ->
            val method = raw.substringBefore(':', raw).trim().uppercase(Locale.ROOT)
            val amount = raw.substringAfter(':', "").trim().toIntOrNull()
            if (method.isBlank() || amount == null) null else {
                TransactionPaymentSnapshot(
                    methodCode = method,
                    amountCents = amount,
                    displayLabel = method,
                )
            }
        }
    val publicUrlPath = optCleanString("public_url_path")
    return TransactionRecord(
        stableId = "backend:$saleId",
        source = TransactionsScope.PAST,
        occurredAtEpochMillis = optLongOrNull("finalized_at_epoch_ms") ?: 0L,
        updatedAtEpochMillis = optLongOrNull("finalized_at_epoch_ms"),
        receiptNumber = receiptNumber,
        saleId = saleId,
        tableLabel = optCleanString("table_label"),
        actorStaffId = optCleanString("cashier_staff_id"),
        actorDisplayName = optCleanString("cashier_name"),
        amountCents = optInt("total_cents", 0),
        businessStatus = TransactionBusinessStatus.PAID,
        syncStatus = TransactionSyncStatus.SYNCED,
        paymentMethods = payments,
        publicReceiptUrl = resolvePublicReceiptUrl(null, publicUrlPath, backendBaseUrl),
        publicUrlPath = publicUrlPath,
        saleKind = optCleanString("sale_kind") ?: "NORMAL_SALE",
        correctionOriginalSaleId = optCleanString("correction_original_sale_id"),
        correctionOriginalReceiptNumber = optCleanString("correction_original_receipt_number"),
        correctionReason = optCleanString("correction_reason"),
        correctionAmountCents = optIntOrNull("correction_amount_cents"),
        serverSaleId = saleId,
    )
}

private fun JSONObject.toBackendSaleDetail(fallbackEvent: TransactionRecord): BackendSaleDetail {
    val sale = optJSONObject("sale") ?: JSONObject()
    val lineItems = optJSONArray("line_items").orEmptyJsonArray()
        .mapObjects()
        .mapIndexedNotNull { index, line ->
            val id = line.optCleanString("id")
                ?: line.optCleanString("line_id")
                ?: line.optCleanString("sale_line_item_id")
            if (id.isNullOrBlank()) return@mapIndexedNotNull null
            val vatBasisPoints = line.optIntOrNull("vat_rate_basis_points")
                ?: line.optIntOrNull("tax_rate_basis_points")
                ?: line.optDoubleOrNull("tax_rate_percent")?.let { (it * 100).toInt() }
            BackendSaleLine(
                id = id,
                productName = line.optCleanString("product_name")
                    ?: line.optCleanString("name")
                    ?: "Rivi ${index + 1}",
                quantity = line.optInt("quantity", 1),
                unitPriceCents = line.optInt("unit_price_cents", 0),
                lineTotalCents = line.optInt("line_total_cents", 0),
                vatRateBasisPoints = vatBasisPoints,
            )
        }
    val payments = optJSONArray("payments").orEmptyJsonArray()
        .mapObjects()
        .mapNotNull { payment ->
            val method = payment.optCleanString("method") ?: return@mapNotNull null
            BackendPaymentLine(
                method = method,
                amountCents = payment.optInt("amount_cents", 0),
                displayLabel = payment.optCleanString("display_label"),
            )
        }
    val corrections = optJSONArray("corrections").orEmptyJsonArray()
        .mapObjects()
        .map { correction ->
            BackendCorrectionSummary(
                correctionId = correction.optCleanString("correction_id"),
                correctionSaleId = correction.optCleanString("correction_sale_id"),
                correctionReceiptNumber = correction.optCleanString("correction_receipt_number"),
                correctionKind = correction.optCleanString("correction_kind") ?: correction.optCleanString("operation_type"),
                reason = correction.optCleanString("reason") ?: correction.optCleanString("correction_reason"),
                financialEffectCents = correction.optIntOrNull("financial_effect_cents")
                    ?: correction.optIntOrNull("correction_amount_cents"),
            )
        }
    return BackendSaleDetail(
        saleId = sale.optCleanString("id") ?: sale.optCleanString("sale_id") ?: fallbackEvent.serverSaleId.orEmpty(),
        receiptNumber = sale.optCleanString("receipt_number") ?: fallbackEvent.receiptNumber,
        totalCents = sale.optIntOrNull("total_cents") ?: fallbackEvent.amountCents,
        tableLabel = sale.optCleanString("table_label") ?: fallbackEvent.tableLabel,
        saleKind = sale.optCleanString("sale_kind") ?: fallbackEvent.saleKind,
        correctionOriginalReceiptNumber = sale.optCleanString("correction_original_receipt_number")
            ?: fallbackEvent.correctionOriginalReceiptNumber,
        lineItems = lineItems,
        payments = payments,
        corrections = corrections,
    )
}

private fun String.euroTextToCentsOrNull(): Int? {
    val normalized = trim().replace(',', '.')
    if (normalized.isBlank()) return null
    val euros = normalized.toDoubleOrNull() ?: return null
    return (euros * 100.0).let { kotlin.math.round(it).toInt() }
}

private fun JSONArray?.orEmptyJsonArray(): JSONArray = this ?: JSONArray()

private fun JSONArray.mapObjects(): List<JSONObject> {
    val items = mutableListOf<JSONObject>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { items += it }
    }
    return items
}

private fun JSONArray.mapStrings(): List<String> {
    val items = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index).trim()
        if (value.isNotBlank()) items += value
    }
    return items
}

private fun JSONObject.requireCleanString(key: String): String {
    return optCleanString(key) ?: error("Missing JSON field '$key'")
}

private fun JSONObject.optCleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getLong(key) }.getOrNull()
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getInt(key) }.getOrNull()
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getDouble(key) }.getOrNull()
}

private class TransactionsViewModel private constructor(
    private val repository: TransactionsRepository,
    private val currentStaffId: String?,
    private val currentStaffName: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCurrentTransactions().collect { items ->
                mutableState.update { current -> current.copy(currentEvents = items) }
            }
        }
        refreshBusinessDay()
    }

    fun selectEvent(stableId: String) {
        mutableState.update { it.copy(selectedEventId = stableId) }
    }

    fun updateSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setStatusFilter(filter: TransactionsStatusFilter) {
        mutableState.update { it.copy(statusFilter = filter) }
    }

    fun toggleTimeSort() {
        mutableState.update {
            it.copy(
                sortOption = if (it.sortOption == TransactionsSortOption.TIME_DESC) {
                    TransactionsSortOption.TIME_ASC
                } else {
                    TransactionsSortOption.TIME_DESC
                },
            )
        }
    }

    fun toggleAmountSort() {
        mutableState.update {
            it.copy(
                sortOption = if (it.sortOption == TransactionsSortOption.AMOUNT_DESC) {
                    TransactionsSortOption.AMOUNT_ASC
                } else {
                    TransactionsSortOption.AMOUNT_DESC
                },
            )
        }
    }

    fun openCorrectionDialog(event: TransactionRecord) {
        mutableState.update {
            it.copy(
                correctionDialogEventId = event.stableId,
                correctionOperationType = CorrectionOperationType.FULL_REVERSAL,
                correctionSaleDetail = null,
                correctionDetailLoading = true,
                correctionSelectedLineId = null,
                correctionQuantityDelta = "-1",
                correctionDiscountCents = "",
                correctionRefundAmountCents = "",
                correctionRefundMethod = event.defaultRefundMethod(),
                correctionCompensationType = "FREE_MEAL",
                correctionCompensationDetails = "",
                correctionReason = "",
                correctionMessage = null,
            )
        }
        viewModelScope.launch {
            when (val detail = repository.fetchSaleDetail(event)) {
                is PosResult.Failure -> mutableState.update {
                    it.copy(correctionDetailLoading = false, correctionMessage = detail.message)
                }
                is PosResult.Success -> mutableState.update {
                    val defaultLineId = detail.value.lineItems.firstOrNull()?.id
                    it.copy(
                        correctionDetailLoading = false,
                        correctionSaleDetail = detail.value,
                        correctionSelectedLineId = defaultLineId,
                    )
                }
            }
        }
    }

    fun dismissCorrectionDialog() {
        mutableState.update {
            if (it.correctionBusy) {
                it
            } else {
                it.copy(
                    correctionDialogEventId = null,
                    correctionSaleDetail = null,
                    correctionDetailLoading = false,
                    correctionReason = "",
                    correctionMessage = null,
                )
            }
        }
    }

    fun selectCorrectionOperation(operationType: CorrectionOperationType) {
        mutableState.update {
            it.copy(
                correctionOperationType = operationType,
                correctionMessage = null,
                correctionSelectedLineId = it.correctionSaleDetail?.lineItems?.firstOrNull()?.id,
            )
        }
    }

    fun selectCorrectionLine(lineId: String) {
        mutableState.update { it.copy(correctionSelectedLineId = lineId, correctionMessage = null) }
    }

    fun updateCorrectionQuantityDelta(value: String) {
        mutableState.update { it.copy(correctionQuantityDelta = value, correctionMessage = null) }
    }

    fun updateCorrectionDiscountCents(value: String) {
        mutableState.update { it.copy(correctionDiscountCents = value, correctionMessage = null) }
    }

    fun updateCorrectionRefundAmount(value: String) {
        mutableState.update { it.copy(correctionRefundAmountCents = value, correctionMessage = null) }
    }

    fun updateCorrectionRefundMethod(value: String) {
        mutableState.update { it.copy(correctionRefundMethod = value, correctionMessage = null) }
    }

    fun updateCorrectionCompensationType(value: String) {
        mutableState.update { it.copy(correctionCompensationType = value, correctionMessage = null) }
    }

    fun updateCorrectionCompensationDetails(value: String) {
        mutableState.update { it.copy(correctionCompensationDetails = value, correctionMessage = null) }
    }

    fun updateCorrectionReason(reason: String) {
        mutableState.update { it.copy(correctionReason = reason, correctionMessage = null) }
    }

    fun confirmCorrection() {
        val snapshot = mutableState.value
        val eventId = snapshot.correctionDialogEventId ?: return
        val event = (snapshot.pastEvents + snapshot.currentEvents).firstOrNull { it.stableId == eventId }
        if (event == null) {
            mutableState.update { it.copy(correctionMessage = "Korjattavaa myyntiä ei löydy.") }
            return
        }
        if (snapshot.correctionReason.isBlank()) {
            mutableState.update { it.copy(correctionMessage = "Korjausmyynnin syy on pakollinen.") }
            return
        }
        mutableState.update { it.copy(correctionBusy = true, correctionMessage = null) }
        viewModelScope.launch {
            val draft = CorrectionSubmitDraft(
                operationType = snapshot.correctionOperationType,
                selectedLineId = snapshot.correctionSelectedLineId,
                quantityDeltaText = snapshot.correctionQuantityDelta,
                discountCentsText = snapshot.correctionDiscountCents,
                refundAmountCentsText = snapshot.correctionRefundAmountCents,
                refundMethod = snapshot.correctionRefundMethod,
                compensationType = snapshot.correctionCompensationType,
                compensationDetails = snapshot.correctionCompensationDetails,
                reason = snapshot.correctionReason,
            )
            when (
                val result = repository.createCorrectionSale(
                    event = event,
                    detail = snapshot.correctionSaleDetail,
                    draft = draft,
                    actorStaffId = currentStaffId,
                    actorName = currentStaffName,
                )
            ) {
                is PosResult.Failure -> mutableState.update {
                    it.copy(correctionBusy = false, correctionMessage = result.message)
                }
                is PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            correctionBusy = false,
                            correctionDialogEventId = null,
                            correctionSaleDetail = null,
                            correctionReason = "",
                            correctionMessage = null,
                            selectedEventId = "backend:${result.value.correction_sale_id}",
                        )
                    }
                    loadBusinessDay(selectSaleId = result.value.correction_sale_id)
                }
            }
        }
    }

    fun refreshBusinessDay(selectSaleId: String? = null) {
        viewModelScope.launch {
            loadBusinessDay(selectSaleId)
        }
    }

    private suspend fun loadBusinessDay(selectSaleId: String? = null) {
        mutableState.update {
            it.copy(
                businessDayLoading = true,
                businessDayMessage = null,
            )
        }
        when (val result = repository.loadBusinessDayReport()) {
            is PosResult.Failure -> mutableState.update {
                it.copy(
                    businessDayLoading = false,
                    businessDayMessage = "Päivän myynti ei ole käytettävissä backendin liiketoimintapäivän totuudella: ${result.message}",
                    localDayReport = null,
                    pastEvents = emptyList(),
                    businessDayDate = null,
                    businessDayWindowLabel = null,
                )
            }
            is PosResult.Success -> mutableState.update {
                val selectedId = selectSaleId?.let { saleId -> "backend:$saleId" }
                    ?: it.selectedEventId?.takeIf { selected -> result.value.transactions.any { row -> row.stableId == selected } }
                    ?: result.value.transactions.firstOrNull()?.stableId
                it.copy(
                    businessDayLoading = false,
                    businessDayMessage = null,
                    localDayReport = result.value.report,
                    pastEvents = result.value.transactions,
                    businessDayDate = result.value.businessDate,
                    businessDayWindowLabel = result.value.windowLabel,
                    selectedEventId = selectedId,
                )
            }
        }
    }

    companion object {
        fun factory(
            openSaleRepository: OpenSaleRepository,
            salesDayReportRepository: SalesDayReportRepository,
            ledgerHttpClient: AirosPosLedgerHttpClient,
            backendBaseUrl: String?,
            currentStaffId: String?,
            currentStaffName: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    repository = TransactionsRepository(
                        openSaleRepository = openSaleRepository,
                        salesDayReportRepository = salesDayReportRepository,
                        ledgerHttpClient = ledgerHttpClient,
                        backendBaseUrl = backendBaseUrl,
                    ),
                    currentStaffId = currentStaffId,
                    currentStaffName = currentStaffName,
                )
            }
        }
    }
}

@Composable
private fun TransactionsScreen(
    state: TransactionsUiState,
    onSelectEvent: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onStatusFilterChange: (TransactionsStatusFilter) -> Unit,
    onToggleTimeSort: () -> Unit,
    onToggleAmountSort: () -> Unit,
    onOpenCorrection: (TransactionRecord) -> Unit,
    onContinueOpenBill: (PersistedOpenSale) -> Unit,
    onCorrectionOperationChange: (CorrectionOperationType) -> Unit,
    onCorrectionLineChange: (String) -> Unit,
    onCorrectionQuantityDeltaChange: (String) -> Unit,
    onCorrectionDiscountChange: (String) -> Unit,
    onCorrectionRefundAmountChange: (String) -> Unit,
    onCorrectionRefundMethodChange: (String) -> Unit,
    onCorrectionCompensationTypeChange: (String) -> Unit,
    onCorrectionCompensationDetailsChange: (String) -> Unit,
    onCorrectionReasonChange: (String) -> Unit,
    onConfirmCorrection: () -> Unit,
    onDismissCorrection: () -> Unit,
    backendBaseUrl: String? = null,
) {
    val strings = rememberCashierStrings()

    val openBills = state.currentEvents
    val paidSales = state.pastEvents
    val dayEvents = remember(
        openBills,
        paidSales,
        state.searchQuery,
        state.statusFilter,
        state.sortOption,
    ) {
        (paidSales + openBills)
            .filter { it.matchesStatus(state.statusFilter) }
            .filter { it.matchesSearch(state.searchQuery) }
            .let { events ->
                when (state.sortOption) {
                    TransactionsSortOption.TIME_DESC -> events.sortedByDescending { it.occurredAtEpochMillis }
                    TransactionsSortOption.TIME_ASC -> events.sortedBy { it.occurredAtEpochMillis }
                    TransactionsSortOption.AMOUNT_DESC -> events.sortedByDescending { it.amountCents }
                    TransactionsSortOption.AMOUNT_ASC -> events.sortedBy { it.amountCents }
                }
            }
    }

    val dayReport = state.localDayReport
    val hasCorrections = paidSales.any { it.saleKind == "CORRECTION" }
    val paymentBuckets = remember(dayReport, strings) {
        dayReport
            ?.paymentBreakdown
            ?.sortedByDescending { it.amountCents }
            ?.map { pm ->
                PaymentMethodBucket(
                    methodCode = pm.method,
                    label = pm.method.toPaymentLabel(strings),
                    amountCents = pm.amountCents,
                    totalCents = dayReport.totalSalesCents,
                )
            }
            .orEmpty()
    }

    val selectedEvent = remember(state.selectedEventId, dayEvents) {
        dayEvents.firstOrNull { it.stableId == state.selectedEventId }
    }
    val correctionDialogEvent = remember(state.correctionDialogEventId, dayEvents) {
        dayEvents.firstOrNull { it.stableId == state.correctionDialogEventId }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosPane(
            title = "Päivän myynti",
            supportingText = buildString {
                append("Backendin liiketoimintapäivä")
                state.businessDayDate?.let { append(" $it") }
                state.businessDayWindowLabel?.let { append(" · $it") }
            },
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.businessDayMessage?.let { message ->
                    item {
                        ReceiptTruthError(message = message, modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SalesKpiCard(
                            label = "Myynti tänään",
                            value = when {
                                state.businessDayLoading -> "..."
                                dayReport != null -> CentsFormatter.format(dayReport.totalSalesCents)
                                else -> "Ei totuutta"
                            },
                            money = dayReport != null && !state.businessDayLoading,
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Avoinna",
                            value = openBills.size.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Myyntejä",
                            value = when {
                                state.businessDayLoading -> "..."
                                dayReport != null -> dayReport.saleCount.toString()
                                else -> "-"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Käteinen",
                            value = dayReport?.let { CentsFormatter.format(it.cashSalesCents) } ?: "-",
                            money = dayReport != null,
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Kortti",
                            value = dayReport?.let { CentsFormatter.format(it.cardSalesCents) } ?: "-",
                            money = dayReport != null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardPaymentMethods]) }
                if (paymentBuckets.isNotEmpty()) {
                    items(paymentBuckets, key = { it.methodCode }) { bucket ->
                        PaymentMethodRow(bucket = bucket)
                    }
                } else {
                    item {
                        Text(
                            text = "Ei maksettuja myyntejä tänään.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Hae kuittia, paikkaa, maksutapaa, tilaa tai summaa") },
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReportFilterChip(
                            label = "Kaikki",
                            selected = state.statusFilter == TransactionsStatusFilter.ALL,
                            onClick = { onStatusFilterChange(TransactionsStatusFilter.ALL) },
                        )
                        ReportFilterChip(
                            label = "Maksetut",
                            selected = state.statusFilter == TransactionsStatusFilter.PAID,
                            onClick = { onStatusFilterChange(TransactionsStatusFilter.PAID) },
                        )
                        ReportFilterChip(
                            label = "Avoimet",
                            selected = state.statusFilter == TransactionsStatusFilter.OPEN,
                            onClick = { onStatusFilterChange(TransactionsStatusFilter.OPEN) },
                        )
                        if (hasCorrections) {
                            ReportFilterChip(
                                label = "Korjaukset",
                                selected = state.statusFilter == TransactionsStatusFilter.CORRECTION,
                                onClick = { onStatusFilterChange(TransactionsStatusFilter.CORRECTION) },
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        ReportFilterChip(
                            label = if (state.sortOption == TransactionsSortOption.TIME_ASC) "Vanhin ensin" else "Uusin ensin",
                            selected = state.sortOption == TransactionsSortOption.TIME_DESC ||
                                state.sortOption == TransactionsSortOption.TIME_ASC,
                            onClick = onToggleTimeSort,
                        )
                        ReportFilterChip(
                            label = if (state.sortOption == TransactionsSortOption.AMOUNT_ASC) "Pienin summa" else "Suurin summa",
                            selected = state.sortOption == TransactionsSortOption.AMOUNT_DESC ||
                                state.sortOption == TransactionsSortOption.AMOUNT_ASC,
                            onClick = onToggleAmountSort,
                        )
                    }
                }

                item { DashboardSectionHeader("Päivän tapahtumat") }
                if (dayEvents.isEmpty()) {
                    item {
                        Text(
                            text = "Ei tapahtumia valituilla rajauksilla.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(dayEvents, key = { it.stableId }) { event ->
                        if (event.businessStatus == TransactionBusinessStatus.OPEN) {
                            OpenBillRow(
                                event = event,
                                selected = selectedEvent?.stableId == event.stableId,
                                strings = strings,
                                onClick = { onSelectEvent(event.stableId) },
                                onContinueOpenBill = onContinueOpenBill,
                            )
                        } else {
                            RecentSaleRow(
                                event = event,
                                selected = selectedEvent?.stableId == event.stableId,
                                strings = strings,
                                onClick = { onSelectEvent(event.stableId) },
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        SalesDetailPane(
            event = selectedEvent,
            strings = strings,
            onCreateCorrection = onOpenCorrection,
            onContinueOpenBill = onContinueOpenBill,
            backendBaseUrl = backendBaseUrl,
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight(),
        )
    }

    if (correctionDialogEvent != null) {
        CorrectionSaleDialog(
            event = correctionDialogEvent,
            detail = state.correctionSaleDetail,
            detailLoading = state.correctionDetailLoading,
            operationType = state.correctionOperationType,
            selectedLineId = state.correctionSelectedLineId,
            quantityDelta = state.correctionQuantityDelta,
            discountCents = state.correctionDiscountCents,
            refundAmount = state.correctionRefundAmountCents,
            refundMethod = state.correctionRefundMethod,
            compensationType = state.correctionCompensationType,
            compensationDetails = state.correctionCompensationDetails,
            reason = state.correctionReason,
            busy = state.correctionBusy,
            message = state.correctionMessage,
            onOperationChange = onCorrectionOperationChange,
            onLineChange = onCorrectionLineChange,
            onQuantityDeltaChange = onCorrectionQuantityDeltaChange,
            onDiscountCentsChange = onCorrectionDiscountChange,
            onRefundAmountChange = onCorrectionRefundAmountChange,
            onRefundMethodChange = onCorrectionRefundMethodChange,
            onCompensationTypeChange = onCorrectionCompensationTypeChange,
            onCompensationDetailsChange = onCorrectionCompensationDetailsChange,
            onReasonChange = onCorrectionReasonChange,
            onConfirm = onConfirmCorrection,
            onDismiss = onDismissCorrection,
        )
    }
}

@Composable
internal fun TransactionsRoute(
    openSaleRepository: OpenSaleRepository,
    salesDayReportRepository: SalesDayReportRepository,
    ledgerHttpClient: AirosPosLedgerHttpClient,
    backendBaseUrl: String?,
    currentStaffId: String?,
    currentStaffName: String?,
    onContinueOpenBill: (PersistedOpenSale) -> Unit,
) {
    val viewModel: TransactionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TransactionsViewModel.factory(
            openSaleRepository = openSaleRepository,
            salesDayReportRepository = salesDayReportRepository,
            ledgerHttpClient = ledgerHttpClient,
            backendBaseUrl = backendBaseUrl,
            currentStaffId = currentStaffId,
            currentStaffName = currentStaffName,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    TransactionsScreen(
        state = state,
        onSelectEvent = viewModel::selectEvent,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onStatusFilterChange = viewModel::setStatusFilter,
        onToggleTimeSort = viewModel::toggleTimeSort,
        onToggleAmountSort = viewModel::toggleAmountSort,
        onOpenCorrection = viewModel::openCorrectionDialog,
        onContinueOpenBill = onContinueOpenBill,
        onCorrectionOperationChange = viewModel::selectCorrectionOperation,
        onCorrectionLineChange = viewModel::selectCorrectionLine,
        onCorrectionQuantityDeltaChange = viewModel::updateCorrectionQuantityDelta,
        onCorrectionDiscountChange = viewModel::updateCorrectionDiscountCents,
        onCorrectionRefundAmountChange = viewModel::updateCorrectionRefundAmount,
        onCorrectionRefundMethodChange = viewModel::updateCorrectionRefundMethod,
        onCorrectionCompensationTypeChange = viewModel::updateCorrectionCompensationType,
        onCorrectionCompensationDetailsChange = viewModel::updateCorrectionCompensationDetails,
        onCorrectionReasonChange = viewModel::updateCorrectionReason,
        onConfirmCorrection = viewModel::confirmCorrection,
        onDismissCorrection = viewModel::dismissCorrectionDialog,
        backendBaseUrl = backendBaseUrl,
    )
}

@Composable
private fun SalesKpiCard(
    label: String,
    value: String,
    money: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = if (money) 8.dp else 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = if (money) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PaymentMethodRow(bucket: PaymentMethodBucket) {
    val pct = if (bucket.totalCents > 0) bucket.amountCents * 100 / bucket.totalCents else 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bucket.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                text = CentsFormatter.format(bucket.amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun UnsupportedReportSection(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun OpenBillRow(
    event: TransactionRecord,
    selected: Boolean,
    strings: CashierStrings,
    onClick: () -> Unit,
    onContinueOpenBill: (PersistedOpenSale) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.locationLabel(strings),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(label = event.statusLabel(strings))
                }
                Text(
                    text = formatUiDateTime(event.occurredAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = CentsFormatter.format(event.amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp),
            )
            event.openSale?.let { openSale ->
                TextButton(
                    onClick = { onContinueOpenBill(openSale) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Jatka")
                }
            }
        }
    }
}

@Composable
private fun RecentSaleRow(
    event: TransactionRecord,
    selected: Boolean,
    strings: CashierStrings,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = event.receiptNumber
                            ?: event.saleId?.takeLast(6)?.uppercase(Locale.ROOT)
                            ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(label = event.statusLabel(strings))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = event.locationLabel(strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = formatUiDateTime(event.occurredAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = event.paymentSummary(strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = CentsFormatter.format(event.amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun ReportFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SalesDetailPane(
    event: TransactionRecord?,
    strings: CashierStrings,
    onCreateCorrection: (TransactionRecord) -> Unit,
    onContinueOpenBill: (PersistedOpenSale) -> Unit,
    backendBaseUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    PosPane(
        title = strings[CashierStringKey.TransactionsReceiptPreview],
        supportingText = event?.detailSupportingText(strings)
            ?: "Kuittitiedot näytetään, kun myynti valitaan.",
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // POS/internal preview MUST NOT use publicReceiptUrl/publicUrlPath:
            // those resolve to /api/pos/receipts/public/<token> which consumes
            // one-time delivery tokens. The internal preview uses the durable
            // sale_finalizations + receipt_snapshots truth via
            // /api/pos/sales/{serverSaleId}/receipt/preview, which is safe to
            // call repeatedly and never touches receipt_delivery_tokens.
            val internalPreviewUrl = remember(event?.serverSaleId, backendBaseUrl) {
                buildInternalReceiptPreviewUrl(event?.serverSaleId, backendBaseUrl)
            }
            when {
                event == null -> {
                    Text(
                        text = "Kuittitiedot näytetään, kun myynti valitaan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                event.businessStatus == TransactionBusinessStatus.OPEN -> {
                    OpenBillDetailCard(
                        event = event,
                        strings = strings,
                        onContinueOpenBill = onContinueOpenBill,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                event.serverSaleId.isNullOrBlank() -> {
                    ReceiptTruthError(
                        message = "Kuittia ei voida esikatsella: myynniltä puuttuu backendin myyntitunniste. " +
                            "Myynti on tallennettu offline-tilassa tai synkronointi on kesken.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                internalPreviewUrl == null -> {
                    ReceiptTruthError(
                        message = "Kuittia ei voida esikatsella: taustajärjestelmän osoite puuttuu.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    InternalReceiptPreview(
                        previewUrl = internalPreviewUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (event != null && event.canCreateCorrection()) {
            Button(
                onClick = { onCreateCorrection(event) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Tee korjausmyynti")
            }
        }
        val correctionUnavailable = event?.correctionUnavailableReason()
        if (correctionUnavailable != null) {
            ReceiptTruthError(message = correctionUnavailable, modifier = Modifier.fillMaxWidth())
        }
        if (event != null && event.saleKind == "CORRECTION") {
            Text(
                text = buildString {
                    append("Korjaa kuittia ")
                    append(event.correctionOriginalReceiptNumber ?: event.correctionOriginalSaleId ?: "tuntematon")
                    event.correctionReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        append(" · ")
                        append(reason)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CorrectionSaleDialog(
    event: TransactionRecord,
    detail: BackendSaleDetail?,
    detailLoading: Boolean,
    operationType: CorrectionOperationType,
    selectedLineId: String?,
    quantityDelta: String,
    discountCents: String,
    refundAmount: String,
    refundMethod: String,
    compensationType: String,
    compensationDetails: String,
    reason: String,
    busy: Boolean,
    message: String?,
    onOperationChange: (CorrectionOperationType) -> Unit,
    onLineChange: (String) -> Unit,
    onQuantityDeltaChange: (String) -> Unit,
    onDiscountCentsChange: (String) -> Unit,
    onRefundAmountChange: (String) -> Unit,
    onRefundMethodChange: (String) -> Unit,
    onCompensationTypeChange: (String) -> Unit,
    onCompensationDetailsChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var reasonFocused by remember { mutableStateOf(false) }
    val strings = rememberCashierStrings()
    val scrollState = rememberScrollState()
    val disabledReason = operationType.disabledReason(detail, detailLoading)
    val lineRequired = operationType.requiresExistingLine()
    val confirmEnabled = !busy &&
        reason.isNotBlank() &&
        disabledReason == null &&
        (!lineRequired || !selectedLineId.isNullOrBlank())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = "Tee korjausmyynti",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailKvRow(label = "Kuitti", value = detail?.receiptNumber ?: event.receiptNumber ?: event.saleId.orEmpty())
                event.correctionOriginalReceiptNumber?.let { originalReceipt ->
                    DetailKvRow(label = "Alkuperäinen kuitti", value = originalReceipt)
                }
                DetailKvRow(label = "Paikka", value = detail?.tableLabel ?: event.tableLabel ?: "Pikamyynti")
                DetailKvRow(label = "Summa", value = CentsFormatter.format(event.amountCents))
                DetailKvRow(label = "Maksutapa", value = event.paymentSummary(strings))
                DetailKvRow(label = "Aika", value = formatUiDateTime(event.occurredAtEpochMillis))
                if (detailLoading) {
                    Text(
                        text = "Haetaan backendin rivitietoja...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!detailLoading && detail?.corrections?.isNotEmpty() == true) {
                    DashboardSectionHeader("Korjausketju")
                    detail.corrections.forEach { correction ->
                        Text(
                            text = buildString {
                                append(correction.correctionReceiptNumber ?: correction.correctionSaleId ?: correction.correctionId ?: "Korjaus")
                                correction.correctionKind?.let { append(" · $it") }
                                correction.financialEffectCents?.let { append(" · ${CentsFormatter.format(it)}") }
                                correction.reason?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DashboardSectionHeader("Toimenpide")
                CorrectionOperationType.values().forEach { operation ->
                    val reasonText = operation.disabledReason(detail, detailLoading)
                    CorrectionChoiceRow(
                        label = operation.label,
                        selected = operationType == operation,
                        disabledReason = reasonText,
                        enabled = !busy && reasonText == null,
                        onClick = { onOperationChange(operation) },
                    )
                }

                if (operationType.requiresExistingLine()) {
                    DashboardSectionHeader("Rivi")
                    val lines = detail?.lineItems.orEmpty()
                    if (lines.isEmpty()) {
                        Text(
                            text = "Backend ei palauttanut rakenteisia myyntirivejä tälle kuitille.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        lines.forEach { line ->
                            CorrectionChoiceRow(
                                label = "${line.quantity} x ${line.productName} · ${CentsFormatter.format(line.lineTotalCents)}",
                                selected = selectedLineId == line.id,
                                disabledReason = null,
                                enabled = !busy,
                                onClick = { onLineChange(line.id) },
                            )
                        }
                    }
                }

                when (operationType) {
                    CorrectionOperationType.CHANGE_QUANTITY -> {
                        OutlinedTextField(
                            value = quantityDelta,
                            onValueChange = onQuantityDeltaChange,
                            label = { Text("Määrän muutos, esim. -1") },
                            enabled = !busy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CorrectionOperationType.APPLY_DISCOUNT -> {
                        OutlinedTextField(
                            value = discountCents,
                            onValueChange = onDiscountCentsChange,
                            label = { Text("Alennus euroina, esim. 2,50") },
                            enabled = !busy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CorrectionOperationType.MONETARY_REFUND -> {
                        OutlinedTextField(
                            value = refundAmount,
                            onValueChange = onRefundAmountChange,
                            label = { Text("Hyvitys euroina, tyhjä = koko summa") },
                            enabled = !busy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportFilterChip(
                                label = "Kortti",
                                selected = refundMethod == "CARD",
                                onClick = { onRefundMethodChange("CARD") },
                            )
                            ReportFilterChip(
                                label = "Käteinen",
                                selected = refundMethod == "CASH",
                                onClick = { onRefundMethodChange("CASH") },
                            )
                        }
                    }
                    CorrectionOperationType.NON_MONETARY_COMPENSATION -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportFilterChip(
                                label = "Ilmainen ateria",
                                selected = compensationType == "FREE_MEAL",
                                onClick = { onCompensationTypeChange("FREE_MEAL") },
                            )
                            ReportFilterChip(
                                label = "Kahvi",
                                selected = compensationType == "COFFEE",
                                onClick = { onCompensationTypeChange("COFFEE") },
                            )
                            ReportFilterChip(
                                label = "Muu",
                                selected = compensationType == "OTHER",
                                onClick = { onCompensationTypeChange("OTHER") },
                            )
                        }
                        OutlinedTextField(
                            value = compensationDetails,
                            onValueChange = onCompensationDetailsChange,
                            label = { Text("Hyvityksen kuvaus") },
                            enabled = !busy,
                            singleLine = false,
                            minLines = 2,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Unit
                }

                disabledReason?.let {
                    ReceiptTruthError(message = it, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Korjauksen syy") },
                    enabled = !busy,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { reasonFocused = it.isFocused },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        enabled = !busy && reasonFocused,
                    ) {
                        Text("Piilota näppäimistö")
                    }
                }
                message?.let {
                    ReceiptTruthError(message = it, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text("Peruuta")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
            ) {
                Text(if (busy) "Luodaan..." else "Luo korjauskuitti")
            }
        },
    )
}

@Composable
private fun CorrectionChoiceRow(
    label: String,
    selected: Boolean,
    disabledReason: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            disabledReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OpenBillDetailCard(
    event: TransactionRecord,
    strings: CashierStrings,
    onContinueOpenBill: (PersistedOpenSale) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val openSale = event.openSale

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = strings[CashierStringKey.SalesDashboardOpenBillDetail],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            DetailKvRow(label = strings[CashierStringKey.TransactionsFieldStatus], value = event.statusLabel(strings))
            DetailKvRow(label = strings[CashierStringKey.TransactionsFieldTime], value = formatUiDateTime(event.occurredAtEpochMillis))
            DetailKvRow(label = strings[CashierStringKey.TransactionsFieldTable], value = event.locationLabel(strings))

            if (openSale == null || openSale.lines.isEmpty()) {
                Text(
                    text = strings[CashierStringKey.TransactionsNoLineItems],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    openSale.lines.forEach { line ->
                        OpenBillLineRow(line = line, strings = strings)
                    }
                }
            }

            DetailKvRow(
                label = strings[CashierStringKey.TransactionsTotal],
                value = CentsFormatter.format(event.amountCents),
                bold = true,
            )
            if (openSale != null) {
                Button(
                    onClick = { onContinueOpenBill(openSale) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Jatka myyntiä")
                }
            }
        }
    }
}

@Composable
private fun OpenBillLineRow(
    line: PersistedOpenSaleLine,
    strings: CashierStrings,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${line.quantity}x ${line.name}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = CentsFormatter.format(line.totalCents()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        line.discountSummary(strings)?.let { discount ->
            Text(
                text = discount,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailKvRow(
    label: String,
    value: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// Builds the non-consuming internal POS receipt preview URL:
//   {backendBaseUrl}/api/pos/sales/{serverSaleId}/receipt/preview
// This endpoint renders durable receipt_snapshot truth and MUST NOT
// touch receipt_delivery_tokens (no access_count, no revoke).
// Returns null if either input is missing/blank.
private fun buildInternalReceiptPreviewUrl(serverSaleId: String?, backendBaseUrl: String?): String? {
    val sid = serverSaleId?.trim().orEmpty()
    val base = backendBaseUrl?.trim()?.trimEnd('/').orEmpty()
    if (sid.isBlank() || base.isBlank()) return null
    val encoded = java.net.URLEncoder.encode(sid, java.nio.charset.StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    return "$base/api/pos/sales/$encoded/receipt/preview"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InternalReceiptPreview(
    previewUrl: String,
    modifier: Modifier = Modifier,
) {
    var loadError by remember(previewUrl) { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
            ),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    loadError = "Kuitin esikatselu epäonnistui: ${error.description}"
                                }
                            }
                        }
                        tag = previewUrl
                        loadUrl(previewUrl)
                    }
                },
                update = { webView ->
                    if (webView.tag != previewUrl) {
                        loadError = null
                        webView.tag = previewUrl
                        webView.loadUrl(previewUrl)
                    }
                },
            )
        }

        loadError?.let { error ->
            ReceiptTruthError(
                message = error,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun ReceiptTruthError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(14.dp),
        )
    }
}
private fun computePaymentBuckets(events: List<TransactionRecord>, strings: CashierStrings): List<PaymentMethodBucket> {
    val totalCents = events.sumOf { it.amountCents }
    val byMethod = mutableMapOf<String, Int>()
    events.forEach { event ->
        event.paymentMethods.forEach { pm ->
            byMethod[pm.methodCode] = (byMethod[pm.methodCode] ?: 0) + pm.amountCents
        }
    }
    return byMethod.entries
        .sortedByDescending { it.value }
        .map { (code, amount) ->
            PaymentMethodBucket(
                methodCode = code,
                label = code.toPaymentLabel(strings),
                amountCents = amount,
                totalCents = totalCents,
            )
        }
}

private fun TransactionRecord.matchesStatus(filter: TransactionsStatusFilter): Boolean {
    return when (filter) {
        TransactionsStatusFilter.ALL -> true
        TransactionsStatusFilter.OPEN -> businessStatus == TransactionBusinessStatus.OPEN
        TransactionsStatusFilter.PAID -> businessStatus == TransactionBusinessStatus.PAID && saleKind != "CORRECTION"
        TransactionsStatusFilter.CORRECTION -> saleKind == "CORRECTION"
    }
}

private fun TransactionRecord.matchesPayment(filter: TransactionsPaymentFilter): Boolean {
    return when (filter) {
        TransactionsPaymentFilter.ALL -> true
        TransactionsPaymentFilter.CASH -> paymentMethods.any { it.methodCode == PaymentMethod.CASH.name }
        TransactionsPaymentFilter.CARD -> paymentMethods.any { it.methodCode == PaymentMethod.CARD.name }
        TransactionsPaymentFilter.VOUCHER -> paymentMethods.any { it.methodCode == PaymentMethod.VOUCHER.name }
        TransactionsPaymentFilter.MIXED -> paymentMethods.map { it.methodCode }.distinct().size > 1
    }
}

private fun TransactionRecord.matchesSearch(query: String): Boolean {
    if (query.isBlank()) return true
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val haystack = buildString {
        append(receiptNumber.orEmpty())
        append(' ')
        append(saleId.orEmpty())
        append(' ')
        append(tableId.orEmpty())
        append(' ')
        append(tableLabel.orEmpty())
        append(' ')
        append(actorStaffId.orEmpty())
        append(' ')
        append(actorDisplayName.orEmpty())
        append(' ')
        append(paymentMethods.joinToString(" ") { it.methodCode + " " + (it.displayLabel ?: "") })
        append(' ')
        append(syncStatus?.name.orEmpty())
        append(' ')
        append(businessStatus.name)
        append(' ')
        append(saleKind)
        append(' ')
        append(correctionOriginalReceiptNumber.orEmpty())
        append(' ')
        append(correctionReason.orEmpty())
        append(' ')
        append(amountCents.toString())
        append(' ')
        append(CentsFormatter.format(amountCents))
        append(' ')
        append(structuredLineNames.joinToString(" "))
    }.lowercase(Locale.ROOT)
    return haystack.contains(normalizedQuery)
}

private fun TransactionRecord.locationLabel(strings: CashierStrings): String {
    return tableLabel ?: tableId ?: strings[CashierStringKey.TransactionsWalkIn]
}

private fun TransactionRecord.paymentSummary(strings: CashierStrings): String {
    if (paymentMethods.isEmpty()) return strings[CashierStringKey.TransactionsUnavailable]
    val distinctMethods = paymentMethods.map { it.methodCode }.distinct()
    return if (distinctMethods.size > 1) {
        strings[CashierStringKey.TransactionsPaymentMixed]
    } else {
        distinctMethods.first().toPaymentLabel(strings)
    }
}

private fun TransactionRecord.canCreateCorrection(): Boolean {
    // Correction goes through the backend ledger, so only serverSaleId is
    // structurally required. publicReceiptUrl is customer-facing delivery
    // truth and intentionally NOT a gate here — POS preview now uses the
    // internal non-consuming receipt preview endpoint.
    return businessStatus == TransactionBusinessStatus.PAID &&
        !serverSaleId.isNullOrBlank()
}

private fun TransactionRecord.correctionUnavailableReason(): String? {
    if (businessStatus != TransactionBusinessStatus.PAID) return null
    if (serverSaleId.isNullOrBlank()) {
        return "Korjausmyynti ei ole saatavilla: myynniltä puuttuu backendin myyntitunniste. " +
            "Myynti on tallennettu offline-tilassa tai synkronointi on kesken."
    }
    return null
}

private fun TransactionRecord.defaultRefundMethod(): String {
    val methods = paymentMethods.map { it.methodCode.uppercase(Locale.ROOT) }
    return when {
        PaymentMethod.CARD.name in methods -> PaymentMethod.CARD.name
        PaymentMethod.CASH.name in methods -> PaymentMethod.CASH.name
        else -> PaymentMethod.CARD.name
    }
}

private fun CorrectionOperationType.requiresExistingLine(): Boolean {
    return this == CorrectionOperationType.REMOVE_LINE ||
        this == CorrectionOperationType.CHANGE_QUANTITY ||
        this == CorrectionOperationType.APPLY_DISCOUNT
}

private fun CorrectionOperationType.disabledReason(
    detail: BackendSaleDetail?,
    detailLoading: Boolean,
): String? {
    return when (this) {
        CorrectionOperationType.ADD_LINE,
        CorrectionOperationType.REPLACE_LINE ->
            "Tuotevalintaa ei ole turvallisesti kytketty tähän näkymään."
        CorrectionOperationType.REMOVE_LINE,
        CorrectionOperationType.CHANGE_QUANTITY,
        CorrectionOperationType.APPLY_DISCOUNT -> when {
            detailLoading -> "Odotetaan backendin rivitietoja."
            detail?.lineItems.isNullOrEmpty() -> "Backend ei palauttanut rakenteisia myyntirivejä."
            else -> null
        }
        CorrectionOperationType.MONETARY_REFUND,
        CorrectionOperationType.NON_MONETARY_COMPENSATION,
        CorrectionOperationType.FULL_REVERSAL -> null
    }
}

private fun TransactionRecord.statusLabel(strings: CashierStrings): String {
    return when (businessStatus) {
        TransactionBusinessStatus.OPEN -> strings[CashierStringKey.TransactionsStatusOpen]
        TransactionBusinessStatus.PAID -> {
            if (saleKind == "CORRECTION") return "Korjaus"
            when (syncStatus) {
                TransactionSyncStatus.QUEUED,
                TransactionSyncStatus.SYNCING -> strings[CashierStringKey.TransactionsStatusPending]
                TransactionSyncStatus.SYNCED -> strings[CashierStringKey.TransactionsStatusPaid]
                TransactionSyncStatus.FAILED -> strings[CashierStringKey.TransactionsStatusFailed]
                TransactionSyncStatus.BLOCKED -> strings[CashierStringKey.TransactionsStatusBlocked]
                TransactionSyncStatus.UNKNOWN,
                null -> strings[CashierStringKey.TransactionsStatusPaid]
            }
        }
    }
}

private fun TransactionRecord.syncStatusLabel(strings: CashierStrings): String {
    return when (syncStatus) {
        TransactionSyncStatus.QUEUED,
        TransactionSyncStatus.SYNCING -> strings[CashierStringKey.TransactionsStatusPending]
        TransactionSyncStatus.SYNCED -> strings[CashierStringKey.TransactionsStatusSynced]
        TransactionSyncStatus.FAILED -> strings[CashierStringKey.TransactionsStatusFailed]
        TransactionSyncStatus.BLOCKED -> strings[CashierStringKey.TransactionsStatusBlocked]
        TransactionSyncStatus.UNKNOWN,
        null -> strings[CashierStringKey.TransactionsUnavailable]
    }
}

private fun TransactionRecord.detailSupportingText(strings: CashierStrings): String {
    return when (source) {
        TransactionsScope.CURRENT -> strings[CashierStringKey.TransactionsCurrentSummary]
        TransactionsScope.PAST -> strings[CashierStringKey.TransactionsReceiptPreview]
    }
}

private fun TransactionsStatusFilter.toStringKey(): CashierStringKey {
    return when (this) {
        TransactionsStatusFilter.ALL -> CashierStringKey.TransactionsFilterAll
        TransactionsStatusFilter.OPEN -> CashierStringKey.TransactionsStatusOpen
        TransactionsStatusFilter.PAID -> CashierStringKey.TransactionsStatusPaid
        TransactionsStatusFilter.CORRECTION -> CashierStringKey.TransactionsStatusPaid
    }
}

private fun TransactionsPaymentFilter.toStringKey(): CashierStringKey {
    return when (this) {
        TransactionsPaymentFilter.ALL -> CashierStringKey.TransactionsPaymentAll
        TransactionsPaymentFilter.CASH -> CashierStringKey.TransactionsPaymentCash
        TransactionsPaymentFilter.CARD -> CashierStringKey.TransactionsPaymentCard
        TransactionsPaymentFilter.VOUCHER -> CashierStringKey.TransactionsPaymentVoucher
        TransactionsPaymentFilter.MIXED -> CashierStringKey.TransactionsPaymentMixed
    }
}

private fun String?.toPaymentLabel(strings: CashierStrings): String {
    val normalized = this?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when (normalized) {
        PaymentMethod.CASH.name -> strings[CashierStringKey.TransactionsPaymentCash]
        PaymentMethod.CARD.name -> strings[CashierStringKey.TransactionsPaymentCard]
        PaymentMethod.VOUCHER.name -> strings[CashierStringKey.TransactionsPaymentVoucher]
        else -> normalized.ifBlank { strings[CashierStringKey.TransactionsUnavailable] }
    }
}

private fun PersistedOpenSaleLine.totalCents(): Int {
    val subtotal = quantity * unitPriceCents
    val discountPercentValue = discountPercent
    val discountAmountValue = discountAmountCents
    val discount = when {
        discountPercentValue != null -> ((subtotal * discountPercentValue.coerceIn(0, 100)) / 100).coerceIn(0, subtotal)
        discountAmountValue != null -> discountAmountValue.coerceIn(0, subtotal)
        else -> 0
    }
    return (subtotal - discount).coerceAtLeast(0)
}

private fun PersistedOpenSaleLine.discountSummary(strings: CashierStrings): String? {
    val discountPercentValue = discountPercent
    val discountAmountValue = discountAmountCents
    return when {
        discountPercentValue != null -> "${strings[CashierStringKey.TransactionsDiscount]} ${discountPercentValue}%"
        discountAmountValue != null && discountAmountValue > 0 ->
            "${strings[CashierStringKey.TransactionsDiscount]} ${CentsFormatter.format(discountAmountValue)}"
        else -> null
    }
}

private fun formatUiDateTime(epochMillis: Long): String {
    return DateFormat.format("dd.MM HH:mm", epochMillis).toString()
}
