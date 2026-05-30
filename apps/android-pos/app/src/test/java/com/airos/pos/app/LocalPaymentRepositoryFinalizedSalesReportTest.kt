package com.airos.pos.app

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.dao.LocalFinalizedSalesReportDao
import com.airos.pos.core.database.entity.LocalFinalizedSaleEntity
import com.airos.pos.core.database.entity.LocalFinalizedSalePaymentEntity
import com.airos.pos.core.model.CashCountResult
import com.airos.pos.core.model.CashCountVarianceResult
import com.airos.pos.core.model.CashDaySummary
import com.airos.pos.core.model.CashDrawer
import com.airos.pos.core.model.CashEvent
import com.airos.pos.core.model.CashEventType
import com.airos.pos.core.model.CashLedgerState
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.LocalSalesDayReport
import com.airos.pos.core.model.PaymentEntry
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TicketLine
import com.airos.pos.domain.CashLedgerRepository
import com.airos.pos.domain.SalesDayReportRepository
import com.airos.pos.domain.SyncQueueRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocalPaymentRepositoryFinalizedSalesReportTest {
    @Test
    fun finalizeCashExactWritesStructuredReportRowsAndCashLedgerEvent() = runBlocking {
        val reports = RecordingSalesDayReportRepository()
        val cashLedger = RecordingCashLedgerRepository()
        val repository = paymentRepository(reports = reports, cashLedger = cashLedger)

        val result = repository.finalizeTablePayment(
            paymentRequest(
                totalCents = 900,
                payments = listOf(PaymentEntry(PaymentMethod.CASH, amountCents = 900)),
                cashTenderedCents = 900,
            ),
        )

        assertThat(result).isInstanceOf(PosResult.Success::class.java)
        assertThat(reports.dao.sales).hasSize(1)
        assertThat(reports.dao.payments).hasSize(1)
        assertThat(reports.dao.sales.single().status).isEqualTo("COMPLETED")
        assertThat(reports.dao.sales.single().totalCents).isEqualTo(900)
        assertThat(reports.dao.sales.single().sellerStaffId).isEqualTo("staff-aino")
        assertThat(reports.dao.sales.single().sellerDisplayName).isEqualTo("Aino Korhonen")
        assertThat(reports.dao.sales.single().terminalId).isEqualTo("terminal-sunmi-1")

        val payment = reports.dao.payments.single()
        assertThat(payment.method).isEqualTo(PaymentMethod.CASH.name)
        assertThat(payment.amountCents).isEqualTo(900)
        assertThat(payment.cashTenderedCents).isEqualTo(900)
        assertThat(payment.cashChangeCents).isEqualTo(0)
        assertThat(payment.cashRetainedCents).isEqualTo(900)

        val report = reports.observeSalesReport(0, Long.MAX_VALUE).first()
        assertThat(report.saleCount).isEqualTo(1)
        assertThat(report.totalSalesCents).isEqualTo(900)
        assertThat(report.cashSalesCents).isEqualTo(900)
        assertThat(report.cardSalesCents).isEqualTo(0)

        val cashEvent = cashLedger.cashSales.single()
        assertThat(cashEvent.type).isEqualTo(CashEventType.CASH_SALE_RECEIVED)
        assertThat(cashEvent.amountCents).isEqualTo(900)
        assertThat(cashEvent.deltaCents).isEqualTo(900)
    }

    @Test
    fun finalizeCardOnlyWritesCardReportRowsWithoutCashLedgerEvent() = runBlocking {
        val reports = RecordingSalesDayReportRepository()
        val cashLedger = RecordingCashLedgerRepository()
        val repository = paymentRepository(reports = reports, cashLedger = cashLedger)

        val result = repository.finalizeTablePayment(
            paymentRequest(
                totalCents = 1_250,
                payments = listOf(PaymentEntry(PaymentMethod.CARD, amountCents = 1_250)),
                cashTenderedCents = null,
            ),
        )

        assertThat(result).isInstanceOf(PosResult.Success::class.java)
        assertThat(reports.dao.sales.single().totalCents).isEqualTo(1_250)
        assertThat(reports.dao.payments.single().method).isEqualTo(PaymentMethod.CARD.name)
        assertThat(reports.dao.payments.single().amountCents).isEqualTo(1_250)
        assertThat(cashLedger.cashSales).isEmpty()

        val report = reports.observeSalesReport(0, Long.MAX_VALUE).first()
        assertThat(report.saleCount).isEqualTo(1)
        assertThat(report.cardSalesCents).isEqualTo(1_250)
        assertThat(report.cashSalesCents).isEqualTo(0)
    }

    @Test
    fun finalizeCashWithChangeUsesSettledAmountAndRetainsOnlyDrawerCash() = runBlocking {
        val reports = RecordingSalesDayReportRepository()
        val cashLedger = RecordingCashLedgerRepository()
        val repository = paymentRepository(reports = reports, cashLedger = cashLedger)

        val result = repository.finalizeTablePayment(
            paymentRequest(
                totalCents = 900,
                payments = listOf(PaymentEntry(PaymentMethod.CASH, amountCents = 900)),
                cashTenderedCents = 1_000,
            ),
        )

        assertThat(result).isInstanceOf(PosResult.Success::class.java)
        val payment = reports.dao.payments.single()
        assertThat(payment.method).isEqualTo(PaymentMethod.CASH.name)
        assertThat(payment.amountCents).isEqualTo(900)
        assertThat(payment.cashTenderedCents).isEqualTo(1_000)
        assertThat(payment.cashChangeCents).isEqualTo(100)
        assertThat(payment.cashRetainedCents).isEqualTo(900)

        val report = reports.observeSalesReport(0, Long.MAX_VALUE).first()
        assertThat(report.saleCount).isEqualTo(1)
        assertThat(report.cashSalesCents).isEqualTo(900)
        assertThat(report.totalSalesCents).isEqualTo(900)

        val cashEvent = cashLedger.cashSales.single()
        assertThat(cashEvent.amountCents).isEqualTo(900)
        assertThat(cashEvent.deltaCents).isEqualTo(900)
    }
}

private fun paymentRepository(
    reports: RecordingSalesDayReportRepository,
    cashLedger: RecordingCashLedgerRepository,
): LocalPaymentRepository =
    LocalPaymentRepository(
        store = FakePosStore(),
        syncQueueRepository = RecordingSyncQueueRepository(),
        terminalIdProvider = { "terminal-sunmi-1" },
        terminalNameProvider = { "Sunmi 1" },
        restaurantIdProvider = { "ravintola-default" },
        cashierStaffIdProvider = { "staff-aino" },
        cashierNameProvider = { "Aino Korhonen" },
        cashierSessionIdProvider = { "session-1" },
        cashierAuthMethodSnapshotProvider = { "PIN" },
        saleSyncOutboxRepository = null,
        cashLedgerRepository = cashLedger,
        salesDayReportRepository = reports,
    )

private fun paymentRequest(
    totalCents: Int,
    payments: List<PaymentEntry>,
    cashTenderedCents: Int?,
): TablePaymentRequest =
    TablePaymentRequest(
        tableId = null,
        tableLabel = null,
        lines = listOf(
            TicketLine(
                id = "line-1",
                menuItemId = "item-1",
                name = "Lounas",
                quantity = 1,
                unitPriceCents = totalCents,
                totalPriceCents = totalCents,
                taxRatePercent = 14.0,
            ),
        ),
        payments = payments,
        cashTenderedCents = cashTenderedCents,
    )

private class RecordingSalesDayReportRepository : SalesDayReportRepository {
    val dao = FinalizationFakeLocalFinalizedSalesReportDao()
    private val delegate = RoomSalesDayReportRepository(dao)

    override fun observeSalesReport(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<LocalSalesDayReport> =
        delegate.observeSalesReport(startEpochMillisInclusive, endEpochMillisExclusive)

    override suspend fun recordFinalizedSale(record: LocalFinalizedSaleRecord): PosResult<Unit> =
        delegate.recordFinalizedSale(record)
}

private class FinalizationFakeLocalFinalizedSalesReportDao : LocalFinalizedSalesReportDao {
    val sales = mutableListOf<LocalFinalizedSaleEntity>()
    val payments = mutableListOf<LocalFinalizedSalePaymentEntity>()

    override fun observeSalesInRange(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<List<LocalFinalizedSaleEntity>> =
        flowOf(
            sales.filter {
                it.finalizedAtEpochMillis >= startEpochMillisInclusive &&
                    it.finalizedAtEpochMillis < endEpochMillisExclusive &&
                    it.status == "COMPLETED"
            },
        )

    override fun observePaymentsInRange(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<List<LocalFinalizedSalePaymentEntity>> {
        val saleIds = sales
            .filter {
                it.finalizedAtEpochMillis >= startEpochMillisInclusive &&
                    it.finalizedAtEpochMillis < endEpochMillisExclusive &&
                    it.status == "COMPLETED"
            }
            .map { it.id }
            .toSet()
        return flowOf(payments.filter { it.finalizedSaleId in saleIds })
    }

    override suspend fun insertSale(entity: LocalFinalizedSaleEntity): Long {
        if (sales.any { it.id == entity.id || it.sourcePosEventId == entity.sourcePosEventId }) {
            return -1L
        }
        sales += entity
        return 1L
    }

    override suspend fun insertPayments(items: List<LocalFinalizedSalePaymentEntity>): List<Long> {
        return items.map { item ->
            if (payments.any { it.id == item.id }) {
                -1L
            } else {
                payments += item
                1L
            }
        }
    }
}

private class RecordingSyncQueueRepository : SyncQueueRepository {
    val items = mutableListOf<SyncItem>()

    override fun observeQueue(): Flow<List<SyncItem>> = flowOf(items)

    override suspend fun enqueue(item: SyncItem): PosResult<Unit> {
        items += item
        return PosResult.Success(Unit)
    }

    override suspend fun updateState(itemId: String, state: SyncState, lastError: String?): PosResult<Unit> =
        PosResult.Success(Unit)

    override suspend fun nextPending(limit: Int): List<SyncItem> =
        items.filter { it.state == SyncState.QUEUED }.take(limit)
}

private class RecordingCashLedgerRepository : CashLedgerRepository {
    val cashSales = mutableListOf<CashEvent>()

    override fun observeState(drawerId: String): Flow<CashLedgerState> =
        flowOf(CashLedgerState())

    override suspend fun recordCashOpened(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        source: String,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashCount(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        note: String?,
    ): PosResult<CashCountResult> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashSale(
        drawerId: String,
        amountCents: Int,
        sourceEventId: String,
        receiptNumber: String?,
        staffId: String?,
        staffName: String?,
    ): PosResult<CashEvent> {
        val event = CashEvent(
            id = "cash-sale-${cashSales.size + 1}",
            drawerId = drawerId,
            type = CashEventType.CASH_SALE_RECEIVED,
            amountCents = amountCents,
            deltaCents = amountCents,
            staffId = staffId,
            staffName = staffName,
            sourceType = "sale",
            sourceId = sourceEventId,
            idempotencyKey = "cash-sale:$sourceEventId",
            note = receiptNumber?.let { "Receipt $it" },
            occurredAtEpochMillis = 1_000L + cashSales.size,
            createdAtEpochMillis = 1_000L + cashSales.size,
        )
        cashSales += event
        return PosResult.Success(event)
    }

    override suspend fun recordCashRefund(
        drawerId: String,
        amountCents: Int,
        sourceEventId: String,
        staffId: String?,
        staffName: String?,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashAdded(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        reason: String?,
        sourceEventId: String?,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashRemoved(
        drawerId: String,
        amountCents: Int,
        staffId: String,
        staffName: String?,
        reason: String?,
        sourceEventId: String?,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashClosed(
        drawerId: String,
        amountCents: Int?,
        staffId: String,
        staffName: String?,
        countedAtClose: Boolean,
        note: String?,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordTruthMissing(
        drawerId: String,
        staffId: String?,
        staffName: String?,
        reason: String,
    ): PosResult<CashEvent> =
        PosResult.Failure("Not used in this test.")

    override suspend fun recordCashCountWithVariance(
        drawerId: String,
        countedCashCents: Int,
        expectedCashCents: Int?,
        staffId: String,
        staffName: String?,
        note: String?,
    ): PosResult<CashCountVarianceResult> =
        PosResult.Failure("Not used in this test.")

    override fun observeDaySummary(
        drawerId: String,
        dayStartEpochMillis: Long,
        recentEventLimit: Int,
    ): Flow<CashDaySummary> =
        flowOf(
            CashDaySummary(
                drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                dayStartEpochMillis = dayStartEpochMillis,
                openingFloatCents = null,
                openingAtEpochMillis = null,
                cashSalesCents = cashSales.sumOf { it.amountCents ?: 0 },
                cashSalesCount = cashSales.size,
                cashRefundsCents = 0,
                cashRefundsCount = 0,
                cashAddedCents = 0,
                cashRemovedCents = 0,
                cashCountCount = 0,
                latestCountedCashCents = null,
                latestCountExpectedCashCents = null,
                latestCountVarianceCents = null,
                latestCountedAtEpochMillis = null,
                latestCountedByStaffName = null,
                expectedCashCents = null,
                recentCashEvents = cashSales.takeLast(recentEventLimit),
            ),
        )
}
