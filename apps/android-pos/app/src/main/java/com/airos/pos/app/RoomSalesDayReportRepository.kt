package com.airos.pos.app

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.dao.LocalFinalizedSalesReportDao
import com.airos.pos.core.database.entity.LocalFinalizedSaleEntity
import com.airos.pos.core.database.entity.LocalFinalizedSalePaymentEntity
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.LocalSalesDayReport
import com.airos.pos.core.model.LocalSalesPaymentBreakdown
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.domain.SalesDayReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val LocalFinalizedSaleStatusCompleted = "COMPLETED"

class RoomSalesDayReportRepository(
    private val dao: LocalFinalizedSalesReportDao,
) : SalesDayReportRepository {
    override fun observeSalesReport(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<LocalSalesDayReport> {
        return combine(
            dao.observeSalesInRange(startEpochMillisInclusive, endEpochMillisExclusive),
            dao.observePaymentsInRange(startEpochMillisInclusive, endEpochMillisExclusive),
        ) { sales, payments ->
            buildLocalSalesDayReport(
                startEpochMillisInclusive = startEpochMillisInclusive,
                endEpochMillisExclusive = endEpochMillisExclusive,
                sales = sales,
                payments = payments,
            )
        }
    }

    override suspend fun recordFinalizedSale(record: LocalFinalizedSaleRecord): PosResult<Unit> {
        if (record.payments.isEmpty()) {
            return PosResult.Failure("Finalized sale report row requires at least one payment.")
        }
        if (record.totalCents < 0 || record.payments.any { it.amountCents < 0 }) {
            return PosResult.Failure("Finalized sale report amounts cannot be negative.")
        }

        return try {
            val sale = record.toEntity()
            val payments = record.payments.mapIndexed { index, payment ->
                LocalFinalizedSalePaymentEntity(
                    id = "${record.id}-payment-$index-${payment.method.name}",
                    finalizedSaleId = record.id,
                    method = payment.method.name,
                    amountCents = payment.amountCents,
                    cashTenderedCents = payment.cashTenderedCents,
                    cashChangeCents = payment.cashChangeCents,
                    cashRetainedCents = payment.cashRetainedCents,
                    createdAtEpochMillis = record.finalizedAtEpochMillis,
                )
            }
            dao.insertSaleWithPayments(sale, payments)
            PosResult.Success(Unit)
        } catch (t: Throwable) {
            PosResult.Failure("Local sales report write failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }
}

internal fun buildLocalSalesDayReport(
    startEpochMillisInclusive: Long,
    endEpochMillisExclusive: Long,
    sales: List<LocalFinalizedSaleEntity>,
    payments: List<LocalFinalizedSalePaymentEntity>,
): LocalSalesDayReport {
    val completedSaleIds = sales
        .filter { it.status == LocalFinalizedSaleStatusCompleted }
        .map { it.id }
        .toSet()
    val completedPayments = payments.filter { it.finalizedSaleId in completedSaleIds }
    val breakdown = completedPayments
        .groupBy { it.method }
        .map { (method, rows) ->
            LocalSalesPaymentBreakdown(
                method = method,
                amountCents = rows.sumOf { it.amountCents },
                paymentCount = rows.size,
            )
        }
        .sortedBy { it.method }

    fun amountFor(method: PaymentMethod): Int =
        breakdown.firstOrNull { it.method == method.name }?.amountCents ?: 0

    val knownMethods = PaymentMethod.values().map { it.name }.toSet()
    val otherSalesCents = breakdown
        .filterNot { it.method in knownMethods }
        .sumOf { it.amountCents }

    return LocalSalesDayReport(
        startEpochMillisInclusive = startEpochMillisInclusive,
        endEpochMillisExclusive = endEpochMillisExclusive,
        totalSalesCents = sales
            .filter { it.id in completedSaleIds }
            .sumOf { it.totalCents },
        saleCount = completedSaleIds.size,
        paymentBreakdown = breakdown,
        cashSalesCents = amountFor(PaymentMethod.CASH),
        cardSalesCents = amountFor(PaymentMethod.CARD),
        voucherSalesCents = amountFor(PaymentMethod.VOUCHER),
        otherSalesCents = otherSalesCents,
        refundCount = 0,
        refundCents = 0,
        refundsSupported = false,
    )
}

private fun LocalFinalizedSaleRecord.toEntity(): LocalFinalizedSaleEntity =
    LocalFinalizedSaleEntity(
        id = id,
        sourcePosEventId = sourcePosEventId,
        ticketId = ticketId,
        openSaleId = openSaleId,
        receiptNumber = receiptNumber,
        tableId = tableId,
        tableLabel = tableLabel,
        finalizedAtEpochMillis = finalizedAtEpochMillis,
        totalCents = totalCents,
        sellerStaffId = sellerStaffId,
        sellerDisplayName = sellerDisplayName,
        terminalId = terminalId,
        restaurantId = restaurantId,
        status = LocalFinalizedSaleStatusCompleted,
        createdAtEpochMillis = finalizedAtEpochMillis,
    )
