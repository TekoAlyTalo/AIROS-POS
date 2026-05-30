package com.airos.pos.app

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.database.dao.LocalFinalizedSalesReportDao
import com.airos.pos.core.database.entity.LocalFinalizedSaleEntity
import com.airos.pos.core.database.entity.LocalFinalizedSalePaymentEntity
import com.airos.pos.core.model.LocalFinalizedSalePaymentRecord
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.PaymentMethod
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RoomSalesDayReportRepositoryTest {
    @Test
    fun migration15To16CreatesFinalizedSalesTablesWithoutDestructiveSql() {
        val migration = migration15To16()
        val recordingDatabase = RecordingSqliteDatabase()

        migration.migrate(recordingDatabase.database)

        assertThat(migration.startVersion).isEqualTo(15)
        assertThat(migration.endVersion).isEqualTo(16)

        val sql = recordingDatabase.statements.joinToString("\n")
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `local_finalized_sales`")
        assertThat(sql).contains("`id` TEXT NOT NULL")
        assertThat(sql).contains("`sourcePosEventId` TEXT NOT NULL")
        assertThat(sql).contains("`ticketId` TEXT")
        assertThat(sql).contains("`openSaleId` TEXT")
        assertThat(sql).contains("`receiptNumber` TEXT")
        assertThat(sql).contains("`tableId` TEXT")
        assertThat(sql).contains("`tableLabel` TEXT")
        assertThat(sql).contains("`finalizedAtEpochMillis` INTEGER NOT NULL")
        assertThat(sql).contains("`totalCents` INTEGER NOT NULL")
        assertThat(sql).contains("`sellerStaffId` TEXT")
        assertThat(sql).contains("`sellerDisplayName` TEXT")
        assertThat(sql).contains("`terminalId` TEXT")
        assertThat(sql).contains("`restaurantId` TEXT")
        assertThat(sql).contains("`status` TEXT NOT NULL")
        assertThat(sql).contains("`createdAtEpochMillis` INTEGER NOT NULL")
        assertThat(sql).contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_finalized_sales_sourcePosEventId`")
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS `index_local_finalized_sales_finalizedAtEpochMillis`")

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `local_finalized_sale_payments`")
        assertThat(sql).contains("`finalizedSaleId` TEXT NOT NULL")
        assertThat(sql).contains("`method` TEXT NOT NULL")
        assertThat(sql).contains("`amountCents` INTEGER NOT NULL")
        assertThat(sql).contains("`cashTenderedCents` INTEGER")
        assertThat(sql).contains("`cashChangeCents` INTEGER")
        assertThat(sql).contains("`cashRetainedCents` INTEGER")
        assertThat(sql).contains("FOREIGN KEY(`finalizedSaleId`) REFERENCES `local_finalized_sales`(`id`) ON DELETE CASCADE")
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS `index_local_finalized_sale_payments_finalizedSaleId`")
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS `index_local_finalized_sale_payments_method`")

        val upperSql = sql.uppercase()
        assertThat(upperSql).doesNotContain("DROP TABLE")
        assertThat(upperSql).doesNotContain("DELETE FROM")
        assertThat(upperSql).doesNotContain("ALTER TABLE `CASH_EVENTS`")
        assertThat(upperSql).doesNotContain("ALTER TABLE `OPEN_SALES`")
    }

    @Test
    fun exactCashSaleAggregatesCashBreakdownAndSaleCount() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "sale-1", finalizedAt = 1_000, totalCents = 900)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.CASH, amountCents = 900)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(0, 2_000)
            .first()

        assertThat(report.totalSalesCents).isEqualTo(900)
        assertThat(report.saleCount).isEqualTo(1)
        assertThat(report.cashSalesCents).isEqualTo(900)
        assertThat(report.cardSalesCents).isEqualTo(0)
        assertThat(report.refundCount).isEqualTo(0)
        assertThat(report.refundCents).isEqualTo(0)
        assertThat(report.refundsSupported).isFalse()
    }

    @Test
    fun cashTenderedWithChangeRecordsSettledAmountSeparatelyFromDrawerRetainedTruth() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        val repository = RoomSalesDayReportRepository(dao)

        val result = repository.recordFinalizedSale(
            LocalFinalizedSaleRecord(
                id = "sale-1",
                sourcePosEventId = "source-1",
                ticketId = "ticket-1",
                finalizedAtEpochMillis = 1_000,
                totalCents = 900,
                payments = listOf(
                    LocalFinalizedSalePaymentRecord(
                        method = PaymentMethod.CASH,
                        amountCents = 900,
                        cashTenderedCents = 1_000,
                        cashChangeCents = 100,
                        cashRetainedCents = 900,
                    ),
                ),
            ),
        )

        assertThat(result).isEqualTo(PosResult.Success(Unit))
        assertThat(dao.payments.single().amountCents).isEqualTo(900)
        assertThat(dao.payments.single().cashTenderedCents).isEqualTo(1_000)
        assertThat(dao.payments.single().cashChangeCents).isEqualTo(100)
        assertThat(dao.payments.single().cashRetainedCents).isEqualTo(900)

        val report = repository.observeSalesReport(0, 2_000).first()
        assertThat(report.cashSalesCents).isEqualTo(900)
        assertThat(report.totalSalesCents).isEqualTo(900)
    }

    @Test
    fun cardOnlySaleAggregatesCardBreakdown() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "sale-1", finalizedAt = 1_000, totalCents = 1_250)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.CARD, amountCents = 1_250)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(0, 2_000)
            .first()

        assertThat(report.totalSalesCents).isEqualTo(1_250)
        assertThat(report.cardSalesCents).isEqualTo(1_250)
        assertThat(report.cashSalesCents).isEqualTo(0)
    }

    @Test
    fun splitCashAndCardSaleAggregatesBothPaymentMethods() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "sale-1", finalizedAt = 1_000, totalCents = 2_000)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.CASH, amountCents = 700)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.CARD, amountCents = 1_300)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(0, 2_000)
            .first()

        assertThat(report.totalSalesCents).isEqualTo(2_000)
        assertThat(report.cashSalesCents).isEqualTo(700)
        assertThat(report.cardSalesCents).isEqualTo(1_300)
    }

    @Test
    fun voucherSaleAggregatesVoucherBreakdown() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "sale-1", finalizedAt = 1_000, totalCents = 1_500)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.VOUCHER, amountCents = 1_500)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(0, 2_000)
            .first()

        assertThat(report.voucherSalesCents).isEqualTo(1_500)
        assertThat(report.otherSalesCents).isEqualTo(0)
    }

    @Test
    fun aggregationUsesExplicitTimeRange() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "before", finalizedAt = 999, totalCents = 500)
        dao.payments += payment(saleId = "before", method = PaymentMethod.CASH, amountCents = 500)
        dao.sales += sale(id = "inside", finalizedAt = 1_000, totalCents = 700)
        dao.payments += payment(saleId = "inside", method = PaymentMethod.CARD, amountCents = 700)
        dao.sales += sale(id = "after", finalizedAt = 2_000, totalCents = 900)
        dao.payments += payment(saleId = "after", method = PaymentMethod.CASH, amountCents = 900)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(1_000, 2_000)
            .first()

        assertThat(report.totalSalesCents).isEqualTo(700)
        assertThat(report.saleCount).isEqualTo(1)
        assertThat(report.cardSalesCents).isEqualTo(700)
        assertThat(report.cashSalesCents).isEqualTo(0)
    }

    @Test
    fun reportAggregationUsesStructuredPaymentRowsOnly() = runBlocking {
        val dao = FakeLocalFinalizedSalesReportDao()
        dao.sales += sale(id = "sale-1", finalizedAt = 1_000, totalCents = 2_400)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.CARD, amountCents = 1_400)
        dao.payments += payment(saleId = "sale-1", method = PaymentMethod.VOUCHER, amountCents = 1_000)

        val report = RoomSalesDayReportRepository(dao)
            .observeSalesReport(0, 2_000)
            .first()

        assertThat(report.paymentBreakdown.map { it.method }).containsExactly("CARD", "VOUCHER").inOrder()
        assertThat(report.cardSalesCents).isEqualTo(1_400)
        assertThat(report.voucherSalesCents).isEqualTo(1_000)
    }
}

private class FakeLocalFinalizedSalesReportDao : LocalFinalizedSalesReportDao {
    val sales = mutableListOf<LocalFinalizedSaleEntity>()
    val payments = mutableListOf<LocalFinalizedSalePaymentEntity>()

    override fun observeSalesInRange(
        startEpochMillisInclusive: Long,
        endEpochMillisExclusive: Long,
    ): Flow<List<LocalFinalizedSaleEntity>> {
        return flowOf(
            sales.filter {
                it.finalizedAtEpochMillis >= startEpochMillisInclusive &&
                    it.finalizedAtEpochMillis < endEpochMillisExclusive &&
                    it.status == "COMPLETED"
            },
        )
    }

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

private fun sale(
    id: String,
    finalizedAt: Long,
    totalCents: Int,
): LocalFinalizedSaleEntity =
    LocalFinalizedSaleEntity(
        id = id,
        sourcePosEventId = "$id-source",
        ticketId = "$id-ticket",
        openSaleId = null,
        receiptNumber = "$id-receipt",
        tableId = null,
        tableLabel = null,
        finalizedAtEpochMillis = finalizedAt,
        totalCents = totalCents,
        sellerStaffId = "staff-1",
        sellerDisplayName = "Aino Korhonen",
        terminalId = "terminal-1",
        restaurantId = null,
        status = "COMPLETED",
        createdAtEpochMillis = finalizedAt,
    )

private fun migration15To16(): Migration {
    val companionInstance = AirosPosDatabase.Companion
    val holders = listOf(
        AirosPosDatabase::class.java to null,
        companionInstance::class.java to companionInstance,
    )

    holders.forEach { (holderClass, receiver) ->
        holderClass.declaredFields.forEach { field ->
            field.isAccessible = true
            val value = runCatching { field.get(receiver) }.getOrNull()
            if (value is Migration && value.startVersion == 15 && value.endVersion == 16) {
                return value
            }
        }
    }

    error("MIGRATION_15_16 was not found by reflection.")
}

private class RecordingSqliteDatabase : InvocationHandler {
    val statements = mutableListOf<String>()
    val database: SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
        this,
    ) as SupportSQLiteDatabase

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.name == "execSQL" && args?.firstOrNull() is String) {
            statements += (args[0] as String).trimIndent()
            return null
        }
        return defaultValue(method.returnType)
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}

private fun payment(
    saleId: String,
    method: PaymentMethod,
    amountCents: Int,
): LocalFinalizedSalePaymentEntity =
    LocalFinalizedSalePaymentEntity(
        id = "$saleId-${method.name}-$amountCents",
        finalizedSaleId = saleId,
        method = method.name,
        amountCents = amountCents,
        cashTenderedCents = null,
        cashChangeCents = null,
        cashRetainedCents = null,
        createdAtEpochMillis = 1_000,
    )
