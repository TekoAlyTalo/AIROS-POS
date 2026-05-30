package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.CashDrawer
import com.airos.pos.core.model.LocalFinalizedSalePaymentRecord
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptHandoffPayload
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.ReceiptPaymentRecord
import com.airos.pos.core.model.ReceiptTotals
import com.airos.pos.core.model.ReceiptVatRow
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.Ticket
import com.airos.pos.core.model.TicketStatus
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.AirosPosLedgerMapper
import com.airos.pos.domain.CashLedgerRepository
import com.airos.pos.domain.PaymentRepository
import com.airos.pos.domain.SalesDayReportRepository
import com.airos.pos.domain.SyncQueueRepository
import com.airos.pos.domain.toJsonString
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Runtime payment finalization for the Android POS.
 *
 * This class owns the local-first sale close path: it materializes the receipt
 * document, queues the durable sales-ledger outbox event, posts a sync attempt
 * to the backend, updates the in-memory ticket/floor-map mirrors, enqueues a
 * generic sync item, and writes the cash drawer event when there is a cash leg.
 *
 * Previously this class lived inside FakeRepositories.kt as `FakePaymentRepository`,
 * which obscured the fact that it is the real production payment path. It has
 * been extracted (without behavioural change beyond the cash-delta correction)
 * so the file name reflects responsibility.
 */
class LocalPaymentRepository(
    private val store: FakePosStore,
    private val syncQueueRepository: SyncQueueRepository,
    private val ledgerHttpClient: AirosPosLedgerHttpClient? = null,
    private val ledgerBackendBaseUrlProvider: (() -> String?)? = null,
    private val terminalIdProvider: (() -> String?)? = null,
    private val terminalNameProvider: (() -> String?)? = null,
    private val restaurantIdProvider: (() -> String?)? = null,
    private val cashierStaffIdProvider: (() -> String?)? = null,
    private val cashierNameProvider: (() -> String?)? = null,
    private val cashierSessionIdProvider: (() -> String?)? = null,
    private val cashierAuthMethodSnapshotProvider: (() -> String?)? = null,
    private val restaurantReceiptSettingsClient: RestaurantReceiptSettingsClient? = null,
    private val saleSyncOutboxRepository: SalesLedgerOutboxRepository? = null,
    private val cashLedgerRepository: CashLedgerRepository? = null,
    private val salesDayReportRepository: SalesDayReportRepository? = null,
) : PaymentRepository {
    private companion object {
        const val TAG = "AIROS_LEDGER"
    }

    override fun observePaymentSummary(ticketId: String): Flow<PaymentSummary?> {
        return combine(store.tickets, store.paymentsByTicket) { tickets, payments ->
            val ticket = tickets[ticketId] ?: return@combine null
            val paid = payments[ticketId] ?: 0
            PaymentSummary(
                ticketId = ticketId,
                totalDueCents = ticket.totalCents,
                paidCents = paid,
                remainingCents = (ticket.totalCents - paid).coerceAtLeast(0),
                availableMethods = listOf(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.VOUCHER),
                refundEligible = paid > 0,
            )
        }
    }

    override suspend fun collectPayment(ticketId: String, method: PaymentMethod, amountCents: Int): PosResult<PaymentSummary> {
        val ticket = store.tickets.value[ticketId] ?: return PosResult.Failure("Ticket not found.")
        val nextPaid = (store.paymentsByTicket.value[ticketId] ?: 0) + amountCents
        store.paymentsByTicket.value = store.paymentsByTicket.value + (ticketId to nextPaid)
        val isSettled = nextPaid >= ticket.totalCents
        val updatedTicket = if (isSettled) ticket.copy(status = TicketStatus.PAID, syncState = SyncState.QUEUED) else ticket
        store.tickets.value = store.tickets.value + (ticketId to updatedTicket)
        if (isSettled) {
            store.floorMap.value = store.floorMap.value.copy(
                tables = store.floorMap.value.tables.map { table ->
                    if (table.activeTicketId == ticketId) {
                        table.copy(
                            status = TableStatus.AVAILABLE,
                            activeTicketId = null,
                            guestCount = if (table.requiresBackendGuestTruth()) table.guestCount else 0,
                        )
                    } else {
                        table
                    }
                },
            )
        }
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "payment",
            aggregateId = ticketId,
            action = "collect_payment",
            payloadJson = """{"method":"${method.name}","amountCents":$amountCents}""",
        )
        return PosResult.Success(
            PaymentSummary(
                ticketId = ticketId,
                totalDueCents = ticket.totalCents,
                paidCents = nextPaid,
                remainingCents = (ticket.totalCents - nextPaid).coerceAtLeast(0),
                availableMethods = listOf(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.VOUCHER),
                refundEligible = nextPaid > 0,
            ),
        )
    }

    override suspend fun finalizeTablePayment(request: TablePaymentRequest): PosResult<TablePaymentResult> {
        if (request.lines.isEmpty()) {
            return PosResult.Failure("Ticket is empty.")
        }

        val subtotalBeforeDiscountCents = request.lines.sumOf { it.totalPriceCents }
        val discountCents = request.discountAmountCents.coerceIn(0, subtotalBeforeDiscountCents)
        val totalDueCents = (subtotalBeforeDiscountCents - discountCents).coerceAtLeast(0)
        val totalPaidCents = request.payments.sumOf { it.amountCents.coerceAtLeast(0) }
        if (totalPaidCents < totalDueCents) {
            return PosResult.Failure("Payment total is smaller than the bill total.")
        }

        val cashierStaffId = cashierStaffIdProvider?.invoke()?.trim().orEmpty()
        val cashierName = cashierNameProvider?.invoke()?.trim().orEmpty()
        val cashierSessionId = cashierSessionIdProvider?.invoke()?.trim().takeUnless { it.isNullOrBlank() }
        val cashierAuthMethodSnapshot = cashierAuthMethodSnapshotProvider?.invoke()?.trim().takeUnless { it.isNullOrBlank() }
        if (cashierStaffId.isBlank() || cashierName.isBlank()) {
            return PosResult.Failure("Cannot finalize payment without signed-in staff attribution.")
        }

        val resolvedTableId = request.tableId
        val resolvedTableLabel = request.tableLabel?.ifBlank { null }
            ?: resolvedTableId?.let { tableId ->
                store.floorMap.value.tables.firstOrNull { it.id == tableId }?.label
            }

        val ticketId = store.floorMap.value.tables
            .firstOrNull { table -> table.id == resolvedTableId }
            ?.activeTicketId
            ?: store.nextId("ticket")

        val paymentRecords = request.payments
            .filter { it.amountCents > 0 }
            .map { entry ->
                ReceiptPaymentRecord(
                    method = entry.method,
                    amountCents = entry.amountCents,
                    reference = entry.reference,
                    displayLabel = entry.displayLabel,
                )
            }

        val changeCents = ((request.cashTenderedCents ?: totalPaidCents) - totalDueCents).coerceAtLeast(0)
        val vatBreakdownMap = mutableMapOf<Double, Pair<Int, Int>>()
        request.lines.forEach { line ->
            val taxRate = line.taxRatePercent
            if (taxRate > 0.0) {
                val lineTaxCents = ((line.totalPriceCents * taxRate) / (100.0 + taxRate)).roundToInt()
                val lineBaseCents = line.totalPriceCents - lineTaxCents
                val current = vatBreakdownMap.getOrDefault(taxRate, 0 to 0)
                vatBreakdownMap[taxRate] = (current.first + lineTaxCents) to (current.second + lineBaseCents)
            }
        }
        val vatBreakdown = vatBreakdownMap.entries
            .sortedBy { it.key }
            .map { (rate, amounts) -> ReceiptVatRow(ratePercent = rate, taxCents = amounts.first, baseCents = amounts.second) }
        val taxCents = vatBreakdown.sumOf { it.taxCents }
        val receiptNumber = "receipt-${ticketId}-${store.now()}"
        val receiptLines = request.lines.map { line ->
            ReceiptLine(
                label = line.name,
                quantity = "${line.quantity}x",
                totalPriceCents = line.totalPriceCents,
                note = line.note,
            )
        }

        val baseReceiptDocument = ReceiptDocument(
            title = "",
            lines = receiptLines,
            footer = "Thank you",
            payments = paymentRecords,
            totals = ReceiptTotals(
                subtotalCents = subtotalBeforeDiscountCents,
                discountCents = discountCents,
                taxCents = taxCents,
                totalCents = totalDueCents,
                vatBreakdown = vatBreakdown,
            ),
            receiptNumber = receiptNumber,
            orderNumber = ticketId,
            tableLabel = resolvedTableLabel,
            printedAtEpochMillis = store.now(),
            cashierName = cashierName,
        )

        val settingsAppliedReceiptDocument = when (val client = restaurantReceiptSettingsClient) {
            null -> {
                Log.w(TAG, "finalizeTablePayment: receipt settings client missing, using base receipt document receipt=$receiptNumber")
                baseReceiptDocument
            }
            else -> {
                when (val settingsResult = client.fetchCurrent()) {
                    is PosResult.Success -> {
                        Log.i(TAG, "finalizeTablePayment: receipt settings applied receipt=$receiptNumber logoEnabled=${settingsResult.value.logoEnabled}")
                        applyBackendReceiptSettings(baseReceiptDocument, settingsResult.value)
                    }
                    is PosResult.Failure -> {
                        Log.w(TAG, "finalizeTablePayment: receipt settings unavailable (no live or cached data), using base receipt document receipt=$receiptNumber reason=${settingsResult.message}")
                        baseReceiptDocument
                    }
                }
            }
        }

        var receiptHandoffPayload: ReceiptHandoffPayload? = null
        val sourcePosEventId = UUID.randomUUID().toString()
        val receiptDocument = when {
            ledgerHttpClient != null && saleSyncOutboxRepository != null -> {
                val ledgerBaseUrl = ledgerBackendBaseUrlProvider?.invoke().orEmpty()
                val ledgerPaymentResult = TablePaymentResult(
                    ticketId = ticketId,
                    tableId = resolvedTableId,
                    tableLabel = resolvedTableLabel,
                    totalDueCents = totalDueCents,
                    totalPaidCents = totalPaidCents,
                    changeCents = changeCents,
                    payments = paymentRecords,
                    receiptDocument = settingsAppliedReceiptDocument,
                )
                val ledgerRequest = AirosPosLedgerMapper.buildFinalizeSaleRequest(
                    paymentRequest = request,
                    paymentResult = ledgerPaymentResult,
                    terminalId = terminalIdProvider?.invoke(),
                    terminalName = terminalNameProvider?.invoke(),
                    restaurantId = restaurantIdProvider?.invoke(),
                    cashierStaffId = cashierStaffId,
                    cashierName = cashierName,
                    sessionId = cashierSessionId,
                    authMethodSnapshot = cashierAuthMethodSnapshot,
                    countryProfile = "FI",
                    languageCode = "fi",
                    currencyCode = settingsAppliedReceiptDocument.currencyCode,
                    saleChannel = if (resolvedTableId.isNullOrBlank()) "walk_in" else "table_service",
                    sourcePosEventId = sourcePosEventId,
                )
                val enqueueResult = saleSyncOutboxRepository.enqueue(
                    SalesLedgerOutboxDraft(
                        sourcePosEventId = sourcePosEventId,
                        receiptNumber = receiptNumber,
                        ticketId = ticketId,
                        tableId = resolvedTableId,
                        terminalId = terminalIdProvider?.invoke(),
                        restaurantId = restaurantIdProvider?.invoke(),
                        cashierStaffId = cashierStaffId,
                        totalCents = totalDueCents,
                        requestJson = ledgerRequest.toJsonString(),
                        createdAtEpochMillis = settingsAppliedReceiptDocument.printedAtEpochMillis ?: store.now(),
                    ),
                )
                if (enqueueResult is PosResult.Failure) {
                    return enqueueResult
                }

                Log.i(
                    TAG,
                    "finalizeTablePayment: durable ledger outbox queued receipt=$receiptNumber sourcePosEventId=$sourcePosEventId baseUrl=$ledgerBaseUrl tableId=$resolvedTableId totalDueCents=$totalDueCents totalPaidCents=$totalPaidCents",
                )

                when (val syncOutcome = saleSyncOutboxRepository.syncOne(sourcePosEventId)) {
                    is SalesLedgerOutboxSyncOutcome.Delivered -> {
                        val ledgerResponse = syncOutcome.response
                        receiptHandoffPayload = ReceiptHandoffPayload(
                            receiptNumber = receiptNumber,
                            ticketId = ticketId,
                            saleId = ledgerResponse.sale_id,
                            receiptSnapshotId = ledgerResponse.receipt_snapshot_id,
                            publicReceiptUrl = ledgerResponse.absolutePublicReceiptUrl(ledgerBaseUrl),
                            publicUrlPath = ledgerResponse.public_url_path,
                            rawPublicToken = ledgerResponse.raw_public_token,
                            deliveryTokenIds = ledgerResponse.delivery_token_ids,
                            createdAtEpochMillis = store.now(),
                        )
                        Log.i(TAG, "finalizeTablePayment: ledger finalize success receipt=$receiptNumber publicUrl=${ledgerResponse.public_url_path} token=${ledgerResponse.raw_public_token != null}")
                        AirosPosLedgerMapper.applyPublicReceiptQr(
                            document = settingsAppliedReceiptDocument,
                            backendBaseUrl = ledgerBaseUrl,
                            ledgerResponse = ledgerResponse,
                            label = "Sähköinen kuitti",
                        )
                    }
                    is SalesLedgerOutboxSyncOutcome.RetryableFailure -> {
                        Log.w(
                            TAG,
                            "finalizeTablePayment: ledger sync pending receipt=$receiptNumber baseUrl=$ledgerBaseUrl sourcePosEventId=$sourcePosEventId reason=${syncOutcome.message}",
                        )
                        settingsAppliedReceiptDocument
                    }
                    is SalesLedgerOutboxSyncOutcome.Blocked -> {
                        return PosResult.Failure("Sale was rejected by the backend ledger. ${syncOutcome.message}")
                    }
                    SalesLedgerOutboxSyncOutcome.NotFound -> {
                        return PosResult.Failure("Sale ledger outbox event was not found after queueing.")
                    }
                }
            }
            else -> {
                Log.w(TAG, "finalizeTablePayment: ledger finalize skipped receipt=$receiptNumber clientPresent=${ledgerHttpClient != null} baseUrl=${ledgerBackendBaseUrlProvider?.invoke()}")
                settingsAppliedReceiptDocument
            }
        }

        // Net drawer cash from this sale = what the customer put IN (cash tendered)
        // minus what came OUT (change returned). PaymentEntry.amountCents remains
        // the sale-settled amount used by reporting breakdowns.
        val retainedCashCents = computeRetainedCashCents(
            cashTenderedCents = request.cashTenderedCents,
            changeCents = changeCents,
        )
        val localFinalizedSaleRecord = LocalFinalizedSaleRecord(
            id = "local-finalized-sale-$sourcePosEventId",
            sourcePosEventId = sourcePosEventId,
            ticketId = ticketId,
            receiptNumber = receiptNumber,
            receiptSnapshotId = receiptHandoffPayload?.receiptSnapshotId,
            publicReceiptUrl = receiptHandoffPayload?.publicReceiptUrl,
            publicUrlPath = receiptHandoffPayload?.publicUrlPath,
            tableId = resolvedTableId,
            tableLabel = resolvedTableLabel,
            finalizedAtEpochMillis = settingsAppliedReceiptDocument.printedAtEpochMillis ?: store.now(),
            totalCents = totalDueCents,
            sellerStaffId = cashierStaffId,
            sellerDisplayName = cashierName,
            terminalId = terminalIdProvider?.invoke(),
            restaurantId = restaurantIdProvider?.invoke(),
            payments = paymentRecords.map { payment ->
                val isCash = payment.method == PaymentMethod.CASH
                LocalFinalizedSalePaymentRecord(
                    method = payment.method,
                    amountCents = payment.amountCents,
                    cashTenderedCents = if (isCash) request.cashTenderedCents else null,
                    cashChangeCents = if (isCash && request.cashTenderedCents != null) changeCents else null,
                    cashRetainedCents = if (isCash && request.cashTenderedCents != null) retainedCashCents else null,
                )
            },
        )
        when (val reportResult = salesDayReportRepository?.recordFinalizedSale(localFinalizedSaleRecord)) {
            is PosResult.Failure -> return PosResult.Failure(reportResult.message)
            is PosResult.Success -> Log.i(TAG, "finalizeTablePayment: local sales report row recorded receipt=$receiptNumber sourcePosEventId=$sourcePosEventId")
            null -> Log.w(TAG, "finalizeTablePayment: local sales report repository missing; report row not recorded receipt=$receiptNumber")
        }

        val finalizedTicketOpenedByStaffId = store.tickets.value[ticketId]
            ?.openedByStaffId
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "menu-checkout" }
            ?: cashierStaffId

        val closedTicket = Ticket(
            id = ticketId,
            tableId = resolvedTableId ?: "walk-in",
            openedByStaffId = finalizedTicketOpenedByStaffId,
            openedAtEpochMillis = store.now(),
            status = TicketStatus.CLOSED,
            lines = request.lines,
            subtotalCents = subtotalBeforeDiscountCents,
            taxCents = taxCents,
            totalCents = totalDueCents,
            syncState = SyncState.QUEUED,
        )

        store.tickets.value = store.tickets.value + (ticketId to closedTicket)
        store.paymentsByTicket.value = store.paymentsByTicket.value + (ticketId to totalPaidCents)

        if (resolvedTableId != null) {
            store.floorMap.value = store.floorMap.value.copy(
                tables = store.floorMap.value.tables.map { table ->
                    if (table.id == resolvedTableId || table.activeTicketId == ticketId) {
                        table.copy(
                            status = TableStatus.AVAILABLE,
                            activeTicketId = null,
                            guestCount = if (table.requiresBackendGuestTruth()) table.guestCount else 0,
                        )
                    } else {
                        table
                    }
                },
            )
        }

        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "payment",
            aggregateId = ticketId,
            action = "finalize_table_payment",
            payloadJson = """{"tableId":"${resolvedTableId ?: ""}","totalDueCents":$totalDueCents,"totalPaidCents":$totalPaidCents,"discountCents":$discountCents}""",
        )

        if (retainedCashCents > 0) {
            when (val cashResult = cashLedgerRepository?.recordCashSale(
                drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                amountCents = retainedCashCents,
                sourceEventId = sourcePosEventId,
                receiptNumber = receiptNumber,
                staffId = cashierStaffId,
                staffName = cashierName,
            )) {
                is PosResult.Failure -> Log.e(TAG, "finalizeTablePayment: cash ledger event failed receipt=$receiptNumber reason=${cashResult.message}")
                is PosResult.Success -> Log.i(TAG, "finalizeTablePayment: cash ledger event recorded receipt=$receiptNumber amountCents=$retainedCashCents")
                null -> Log.w(TAG, "finalizeTablePayment: cash ledger repository missing; cash event not recorded receipt=$receiptNumber")
            }
        }

        return PosResult.Success(
            TablePaymentResult(
                ticketId = ticketId,
                tableId = resolvedTableId,
                tableLabel = resolvedTableLabel,
                totalDueCents = totalDueCents,
                totalPaidCents = totalPaidCents,
                changeCents = changeCents,
                payments = paymentRecords,
                receiptDocument = receiptDocument,
                receiptHandoff = receiptHandoffPayload,
            ),
        )
    }

    override suspend fun refund(request: RefundRequest): PosResult<Unit> {
        val ticket = store.tickets.value[request.ticketId] ?: return PosResult.Failure("Ticket not found.")
        store.tickets.value = store.tickets.value + (request.ticketId to ticket.copy(status = TicketStatus.REFUND_PENDING))
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "payment",
            aggregateId = request.ticketId,
            action = "refund",
            payloadJson = """{"amountCents":${request.amountCents},"reason":"${request.reason}"}""",
        )
        return PosResult.Success(Unit)
    }
}

// Net cash drawer increase from a single sale.
//   tenderedCents = total cash the customer handed over (null when no cash leg)
//   changeCents   = cash returned to the customer
// Returns the amount that physically stays in the drawer, never negative.
//
// Card / voucher only:        tenderedCents = null      -> 0
// Exact cash:                  tendered = total, change=0 -> tendered
// Overpaid pure cash:          tendered > total, change > 0 -> tendered - change
// Split with cash leg:         tendered = cash portion, change = total tendered - total due
//                              -> tendered - change (correct when changeCents is correct)
internal fun computeRetainedCashCents(cashTenderedCents: Int?, changeCents: Int): Int {
    val tendered = (cashTenderedCents ?: 0).coerceAtLeast(0)
    val change = changeCents.coerceAtLeast(0)
    return (tendered - change).coerceAtLeast(0)
}

