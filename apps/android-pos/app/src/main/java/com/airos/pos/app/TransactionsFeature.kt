package com.airos.pos.app

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.database.dao.SalesLedgerOutboxDao
import com.airos.pos.core.database.entity.SalesLedgerOutboxLocalEntity
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.core.model.PersistedOpenSaleTransferEvent
import com.airos.pos.core.model.ReceiptAlignment
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.ReceiptPaymentRecord
import com.airos.pos.core.model.ReceiptTotals
import com.airos.pos.core.model.ReceiptVatRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.domain.OpenSaleRepository
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class TransactionsScope {
    CURRENT,
    PAST,
}

private enum class TransactionsDateRange {
    ALL,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}

private enum class TransactionsStatusFilter {
    ALL,
    OPEN,
    PAID,
    SYNCED,
    PENDING,
    FAILED,
    BLOCKED,
}

private enum class TransactionsPaymentFilter {
    ALL,
    CASH,
    CARD,
    VOUCHER,
    MIXED,
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

private enum class SalesActionDialogType {
    REPRINT,
    REFUND,
    CORRECT,
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
    val receiptDocument: ReceiptDocument? = null,
    val openSale: PersistedOpenSale? = null,
    val transferEvents: List<PersistedOpenSaleTransferEvent> = emptyList(),
    val timeline: List<TransactionTimelineEntry> = emptyList(),
    val publicReceiptUrlPath: String? = null,
)

private data class ParsedFinalizeSaleRequest(
    val occurredAtEpochMillis: Long?,
    val receiptNumber: String?,
    val tableId: String?,
    val tableLabel: String?,
    val cashierStaffId: String?,
    val cashierName: String?,
    val totalCents: Int?,
    val payments: List<TransactionPaymentSnapshot>,
    val receiptDocument: ReceiptDocument?,
)

private data class TransactionsUiState(
    val scope: TransactionsScope = TransactionsScope.CURRENT,
    val searchQuery: String = "",
    val dateRange: TransactionsDateRange = TransactionsDateRange.ALL,
    val statusFilter: TransactionsStatusFilter = TransactionsStatusFilter.ALL,
    val paymentFilter: TransactionsPaymentFilter = TransactionsPaymentFilter.ALL,
    val currentEvents: List<TransactionRecord> = emptyList(),
    val pastEvents: List<TransactionRecord> = emptyList(),
    val selectedEventId: String? = null,
)

private data class HourlyBucket(val hour: Int, val amountCents: Int)

private data class PaymentMethodBucket(
    val methodCode: String,
    val label: String,
    val amountCents: Int,
    val totalCents: Int,
)

private class TransactionsRepository(
    private val openSaleRepository: OpenSaleRepository,
    private val salesLedgerOutboxDao: SalesLedgerOutboxDao,
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
                        receiptDocument = null,
                        openSale = sale,
                        transferEvents = saleTransfers,
                        timeline = buildCurrentTimeline(sale, saleTransfers),
                        publicReceiptUrlPath = null,
                    )
                }
        }
    }

    fun observePastTransactions(): Flow<List<TransactionRecord>> {
        return salesLedgerOutboxDao.observeAll().map { entities ->
            entities
                .sortedByDescending { it.createdAtEpochMillis }
                .map { entity ->
                    val parsed = parseFinalizeSaleRequest(entity.requestJson)
                    val syncStatus = entity.syncStatus.toTransactionSyncStatus()
                    TransactionRecord(
                        stableId = "past:${entity.sourcePosEventId}",
                        source = TransactionsScope.PAST,
                        occurredAtEpochMillis = parsed.occurredAtEpochMillis ?: entity.createdAtEpochMillis,
                        updatedAtEpochMillis = entity.updatedAtEpochMillis,
                        receiptNumber = parsed.receiptNumber ?: entity.receiptNumber,
                        saleId = entity.serverSaleId,
                        tableId = parsed.tableId ?: entity.tableId,
                        tableLabel = parsed.tableLabel,
                        actorStaffId = parsed.cashierStaffId ?: entity.cashierStaffId,
                        actorDisplayName = parsed.cashierName ?: parsed.receiptDocument?.cashierName,
                        amountCents = parsed.totalCents ?: entity.totalCents,
                        businessStatus = TransactionBusinessStatus.PAID,
                        syncStatus = syncStatus,
                        paymentMethods = parsed.payments,
                        receiptDocument = parsed.receiptDocument,
                        openSale = null,
                        transferEvents = emptyList(),
                        timeline = buildPastTimeline(entity, syncStatus),
                        publicReceiptUrlPath = entity.publicUrlPath,
                    )
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

    private fun buildPastTimeline(
        entity: SalesLedgerOutboxLocalEntity,
        syncStatus: TransactionSyncStatus,
    ): List<TransactionTimelineEntry> {
        val timeline = mutableListOf(
            TransactionTimelineEntry(
                id = "finalized:${entity.sourcePosEventId}",
                titleKey = CashierStringKey.TransactionsTimelineFinalized,
                occurredAtEpochMillis = entity.createdAtEpochMillis,
                supportingText = entity.receiptNumber,
            ),
        )
        entity.syncedAtEpochMillis?.let { syncedAt ->
            timeline += TransactionTimelineEntry(
                id = "synced:${entity.sourcePosEventId}",
                titleKey = CashierStringKey.TransactionsTimelineSynced,
                occurredAtEpochMillis = syncedAt,
                supportingText = entity.serverSaleId ?: entity.publicUrlPath,
            )
        }
        if (syncStatus == TransactionSyncStatus.FAILED) {
            timeline += TransactionTimelineEntry(
                id = "failed:${entity.sourcePosEventId}",
                titleKey = CashierStringKey.TransactionsTimelineFailed,
                occurredAtEpochMillis = entity.updatedAtEpochMillis,
                supportingText = entity.lastError,
            )
        }
        if (syncStatus == TransactionSyncStatus.BLOCKED) {
            timeline += TransactionTimelineEntry(
                id = "blocked:${entity.sourcePosEventId}",
                titleKey = CashierStringKey.TransactionsTimelineBlocked,
                occurredAtEpochMillis = entity.updatedAtEpochMillis,
                supportingText = entity.lastError,
            )
        }
        return timeline.sortedBy { it.occurredAtEpochMillis }
    }
}

private class TransactionsViewModel private constructor(
    repository: TransactionsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCurrentTransactions().collect { items ->
                mutableState.update { current -> current.copy(currentEvents = items) }
            }
        }
        viewModelScope.launch {
            repository.observePastTransactions().collect { items ->
                mutableState.update { current -> current.copy(pastEvents = items) }
            }
        }
    }

    fun selectEvent(stableId: String) {
        mutableState.update { it.copy(selectedEventId = stableId) }
    }

    companion object {
        fun factory(
            openSaleRepository: OpenSaleRepository,
            salesLedgerOutboxDao: SalesLedgerOutboxDao,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    repository = TransactionsRepository(
                        openSaleRepository = openSaleRepository,
                        salesLedgerOutboxDao = salesLedgerOutboxDao,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TransactionsScreen(
    state: TransactionsUiState,
    onSelectEvent: (String) -> Unit,
) {
    val strings = rememberCashierStrings()
    val todayStart = remember { startOfTodayEpochMillis() }

    val todayPast = remember(state.pastEvents, todayStart) {
        state.pastEvents.filter { it.occurredAtEpochMillis >= todayStart }
    }
    val todayTotal = remember(todayPast) { todayPast.sumOf { it.amountCents } }
    val lastHourTotal = remember(state.pastEvents) {
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        state.pastEvents.filter { it.occurredAtEpochMillis >= hourAgo }.sumOf { it.amountCents }
    }
    val receiptCount = remember(todayPast) { todayPast.size }
    val openBillsCount = remember(state.currentEvents) { state.currentEvents.size }

    val hourlyBuckets = remember(todayPast) { computeHourlyBuckets(todayPast) }
    val paymentBuckets = remember(todayPast) { computePaymentBuckets(todayPast, strings) }
    val recentSales = remember(state.pastEvents) { state.pastEvents.take(20) }
    val openBills = state.currentEvents

    val selectedEvent = remember(state.selectedEventId, openBills, recentSales) {
        (openBills + recentSales).firstOrNull { it.stableId == state.selectedEventId }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosPane(
            title = strings[CashierStringKey.TransactionsTitle],
            supportingText = strings[CashierStringKey.TransactionsSupporting],
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
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SalesKpiCard(
                            label = strings[CashierStringKey.SalesDashboardToday],
                            value = CentsFormatter.format(todayTotal),
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = strings[CashierStringKey.SalesDashboardLastHour],
                            value = CentsFormatter.format(lastHourTotal),
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = strings[CashierStringKey.SalesDashboardReceiptCount],
                            value = receiptCount.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = strings[CashierStringKey.SalesDashboardOpenBillsKpi],
                            value = openBillsCount.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardHourlySales]) }
                item {
                    HourlySalesChart(
                        buckets = hourlyBuckets,
                        noDataText = strings[CashierStringKey.SalesDashboardNoHourlyData],
                    )
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardPaymentMethods]) }
                if (paymentBuckets.isEmpty()) {
                    item {
                        Text(
                            text = strings[CashierStringKey.SalesDashboardNoData],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(paymentBuckets, key = { it.methodCode }) { bucket ->
                        PaymentMethodRow(bucket = bucket)
                    }
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardByCategory]) }
                item {
                    CategoryBreakdownSection(
                        totalCents = todayTotal,
                        categoryLabel = strings[CashierStringKey.SalesDashboardCategoryOther],
                        noDataText = strings[CashierStringKey.SalesDashboardNoCategoryData],
                    )
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardOpenBillsSection]) }
                if (openBills.isEmpty()) {
                    item {
                        Text(
                            text = strings[CashierStringKey.SalesDashboardNoOpenBills],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(openBills, key = { it.stableId }) { event ->
                        OpenBillRow(
                            event = event,
                            selected = selectedEvent?.stableId == event.stableId,
                            strings = strings,
                            onClick = { onSelectEvent(event.stableId) },
                        )
                    }
                }

                item { DashboardSectionHeader(strings[CashierStringKey.SalesDashboardRecentSales]) }
                if (recentSales.isEmpty()) {
                    item {
                        Text(
                            text = strings[CashierStringKey.SalesDashboardNoData],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(recentSales, key = { it.stableId }) { event ->
                        RecentSaleRow(
                            event = event,
                            selected = selectedEvent?.stableId == event.stableId,
                            strings = strings,
                            onClick = { onSelectEvent(event.stableId) },
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        PaperReceiptPane(
            event = selectedEvent,
            strings = strings,
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight(),
        )
    }
}

@Composable
internal fun TransactionsRoute(
    openSaleRepository: OpenSaleRepository,
    salesLedgerOutboxDao: SalesLedgerOutboxDao,
) {
    val viewModel: TransactionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TransactionsViewModel.factory(
            openSaleRepository = openSaleRepository,
            salesLedgerOutboxDao = salesLedgerOutboxDao,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    TransactionsScreen(
        state = state,
        onSelectEvent = viewModel::selectEvent,
    )
}

@Composable
private fun SalesKpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun HourlySalesChart(
    buckets: List<HourlyBucket>,
    noDataText: String,
) {
    val hasData = buckets.any { it.amountCents > 0 }
    if (!hasData) {
        Text(
            text = noDataText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val maxCents = buckets.maxOf { it.amountCents }.coerceAtLeast(1)
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            val fraction = bucket.amountCents.toFloat() / maxCents.toFloat()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height((80.dp * fraction).coerceAtLeast(2.dp))
                        .background(
                            color = if (bucket.amountCents > 0) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
                Text(
                    text = "${bucket.hour}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
private fun CategoryBreakdownSection(
    totalCents: Int,
    categoryLabel: String,
    noDataText: String,
) {
    if (totalCents == 0) {
        Text(
            text = noDataText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = categoryLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "100%  ${CentsFormatter.format(totalCents)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

@Composable
private fun OpenBillRow(
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.locationLabel(strings),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun PaperReceiptPane(
    event: TransactionRecord?,
    strings: CashierStrings,
    modifier: Modifier = Modifier,
) {
    var activeDialog by remember { mutableStateOf<SalesActionDialogType?>(null) }

    PosPane(
        title = strings[CashierStringKey.TransactionsReceiptPreview],
        supportingText = event?.detailSupportingText(strings)
            ?: strings[CashierStringKey.SalesDashboardSelectSaleHint],
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (event == null) {
                Text(
                    text = strings[CashierStringKey.SalesDashboardSelectSaleHint],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PaperReceiptCard(
                    event = event,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { activeDialog = SalesActionDialogType.REPRINT },
                enabled = event != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionReprint])
            }
            OutlinedButton(
                onClick = { activeDialog = SalesActionDialogType.REFUND },
                enabled = event?.businessStatus == TransactionBusinessStatus.PAID,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionRefund])
            }
            OutlinedButton(
                onClick = { activeDialog = SalesActionDialogType.CORRECT },
                enabled = event?.businessStatus == TransactionBusinessStatus.PAID,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionCorrect])
            }
        }
    }

    activeDialog?.let { type ->
        AirosActionDialog(
            type = type,
            strings = strings,
            onDismiss = { activeDialog = null },
        )
    }
}

@Composable
private fun PaperReceiptCard(
    event: TransactionRecord,
    strings: CashierStrings,
    modifier: Modifier = Modifier,
) {
    val receiptPaper = Color(0xFFFAF9F5)
    val receiptInk = Color(0xFF1C1410)
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = receiptPaper,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Header block
            Text(
                text = event.receiptDocument?.title?.takeIf { it.isNotBlank() }
                    ?: strings[CashierStringKey.TransactionsReceiptPreview],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = receiptInk,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            val headerTimestamp = event.receiptDocument?.printedAtEpochMillis
                ?: event.occurredAtEpochMillis
            Text(
                text = formatUiDateTime(headerTimestamp),
                style = MaterialTheme.typography.bodySmall,
                color = receiptInk.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            event.receiptDocument?.receiptNumber?.let { num ->
                Text(
                    text = num,
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            val location = event.receiptDocument?.tableLabel ?: event.tableLabel
            if (!location.isNullOrBlank()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            event.receiptDocument?.cashierName?.takeIf { it.isNotBlank() }?.let { cashier ->
                Text(
                    text = cashier,
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            event.receiptDocument?.headerText?.takeIf { it.isNotBlank() }?.let { header ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = header,
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.8f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            ReceiptDivider(inkColor = receiptInk)
            Spacer(modifier = Modifier.height(4.dp))

            // Line items
            if (event.receiptDocument != null) {
                event.receiptDocument.lines.forEach { line ->
                    ReceiptLineRow(line = line, inkColor = receiptInk)
                }
            } else if (event.openSale != null) {
                event.openSale.lines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${line.quantity}x ${line.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = receiptInk,
                        )
                        Text(
                            text = CentsFormatter.format(line.totalCents()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = receiptInk,
                        )
                    }
                }
            } else {
                Text(
                    text = strings[CashierStringKey.TransactionsUnavailable],
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.55f),
                )
            }

            // Totals
            Spacer(modifier = Modifier.height(4.dp))
            ReceiptDivider(inkColor = receiptInk)
            Spacer(modifier = Modifier.height(4.dp))

            val totals = event.receiptDocument?.totals
            if (totals != null) {
                if (totals.discountCents > 0) {
                    ReceiptKvRow(strings[CashierStringKey.TransactionsDiscount], CentsFormatter.format(totals.discountCents), receiptInk)
                }
                if (totals.taxCents > 0) {
                    ReceiptKvRow(strings[CashierStringKey.TransactionsTax], CentsFormatter.format(totals.taxCents), receiptInk)
                }
                ReceiptKvRow(strings[CashierStringKey.TransactionsTotal], CentsFormatter.format(totals.totalCents), receiptInk, bold = true)
            } else {
                ReceiptKvRow(strings[CashierStringKey.TransactionsTotal], CentsFormatter.format(event.amountCents), receiptInk, bold = true)
            }

            // Payments
            val docPayments = event.receiptDocument?.payments.orEmpty()
            if (docPayments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                docPayments.forEach { payment ->
                    ReceiptKvRow(
                        label = payment.displayLabel ?: payment.method.name.toPaymentLabel(strings),
                        value = CentsFormatter.format(payment.amountCents),
                        color = receiptInk,
                    )
                }
            } else if (event.paymentMethods.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                event.paymentMethods.forEach { pm ->
                    ReceiptKvRow(
                        label = pm.methodCode.toPaymentLabel(strings),
                        value = CentsFormatter.format(pm.amountCents),
                        color = receiptInk,
                    )
                }
            }

            // Footer
            event.receiptDocument?.footerText?.takeIf { it.isNotBlank() }?.let { footer ->
                Spacer(modifier = Modifier.height(8.dp))
                ReceiptDivider(inkColor = receiptInk)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = receiptInk.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ReceiptLineRow(line: ReceiptLine, inkColor: Color) {
    when (line.alignment) {
        ReceiptAlignment.CENTER -> {
            Text(
                text = buildString {
                    line.quantity?.let { append(it); append(' ') }
                    append(line.label)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = inkColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        ReceiptAlignment.RIGHT -> {
            Text(
                text = line.label,
                style = MaterialTheme.typography.bodyMedium,
                color = inkColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
        ReceiptAlignment.LEFT -> {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = buildString {
                            line.quantity?.let { append(it); append(' ') }
                            append(line.label)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = inkColor,
                    )
                    val priceText = line.value
                        ?: line.totalPriceCents?.let(CentsFormatter::format)
                        ?: line.unitPriceCents?.let(CentsFormatter::format)
                    if (priceText != null) {
                        Text(
                            text = priceText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = inkColor,
                        )
                    }
                }
                line.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = inkColor.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptKvRow(label: String, value: String, color: Color, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color.copy(alpha = if (bold) 1f else 0.85f),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun ReceiptDivider(inkColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(inkColor.copy(alpha = 0.18f)),
    )
}

@Composable
private fun AirosActionDialog(
    type: SalesActionDialogType,
    strings: CashierStrings,
    onDismiss: () -> Unit,
) {
    val titleKey = when (type) {
        SalesActionDialogType.REPRINT -> CashierStringKey.SalesDashboardReprintDialogTitle
        SalesActionDialogType.REFUND -> CashierStringKey.SalesDashboardRefundDialogTitle
        SalesActionDialogType.CORRECT -> CashierStringKey.SalesDashboardCorrectDialogTitle
    }
    val bodyKey = when (type) {
        SalesActionDialogType.REPRINT -> CashierStringKey.SalesDashboardReprintDialogBody
        SalesActionDialogType.REFUND -> CashierStringKey.SalesDashboardRefundDialogBody
        SalesActionDialogType.CORRECT -> CashierStringKey.SalesDashboardCorrectDialogBody
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[titleKey]) },
        text = { Text(strings[bodyKey]) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings[CashierStringKey.SalesDashboardDialogOk])
            }
        },
    )
}

private fun computeHourlyBuckets(events: List<TransactionRecord>): List<HourlyBucket> {
    val byHour = Array(24) { 0 }
    events.forEach { event ->
        val cal = Calendar.getInstance().apply { timeInMillis = event.occurredAtEpochMillis }
        byHour[cal.get(Calendar.HOUR_OF_DAY)] += event.amountCents
    }
    return (0 until 24).map { h -> HourlyBucket(hour = h, amountCents = byHour[h]) }
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

private fun TransactionRecord.matchesDateRange(
    range: TransactionsDateRange,
    nowEpochMillis: Long,
): Boolean {
    return when (range) {
        TransactionsDateRange.ALL -> true
        TransactionsDateRange.TODAY -> occurredAtEpochMillis >= startOfTodayEpochMillis()
        TransactionsDateRange.LAST_7_DAYS -> occurredAtEpochMillis >= nowEpochMillis - (7 * 24 * 60 * 60 * 1000L)
        TransactionsDateRange.LAST_30_DAYS -> occurredAtEpochMillis >= nowEpochMillis - (30 * 24 * 60 * 60 * 1000L)
    }
}

private fun TransactionRecord.matchesStatus(filter: TransactionsStatusFilter): Boolean {
    return when (filter) {
        TransactionsStatusFilter.ALL -> true
        TransactionsStatusFilter.OPEN -> businessStatus == TransactionBusinessStatus.OPEN
        TransactionsStatusFilter.PAID -> businessStatus == TransactionBusinessStatus.PAID
        TransactionsStatusFilter.SYNCED -> syncStatus == TransactionSyncStatus.SYNCED
        TransactionsStatusFilter.PENDING -> syncStatus == TransactionSyncStatus.QUEUED || syncStatus == TransactionSyncStatus.SYNCING
        TransactionsStatusFilter.FAILED -> syncStatus == TransactionSyncStatus.FAILED
        TransactionsStatusFilter.BLOCKED -> syncStatus == TransactionSyncStatus.BLOCKED
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
    }.lowercase(Locale.ROOT)
    return haystack.contains(query)
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

private fun TransactionRecord.statusLabel(strings: CashierStrings): String {
    return when (businessStatus) {
        TransactionBusinessStatus.OPEN -> strings[CashierStringKey.TransactionsStatusOpen]
        TransactionBusinessStatus.PAID -> {
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

private fun TransactionsDateRange.toStringKey(): CashierStringKey {
    return when (this) {
        TransactionsDateRange.ALL -> CashierStringKey.TransactionsRangeAll
        TransactionsDateRange.TODAY -> CashierStringKey.TransactionsRangeToday
        TransactionsDateRange.LAST_7_DAYS -> CashierStringKey.TransactionsRangeLast7Days
        TransactionsDateRange.LAST_30_DAYS -> CashierStringKey.TransactionsRangeLast30Days
    }
}

private fun TransactionsStatusFilter.toStringKey(): CashierStringKey {
    return when (this) {
        TransactionsStatusFilter.ALL -> CashierStringKey.TransactionsFilterAll
        TransactionsStatusFilter.OPEN -> CashierStringKey.TransactionsStatusOpen
        TransactionsStatusFilter.PAID -> CashierStringKey.TransactionsStatusPaid
        TransactionsStatusFilter.SYNCED -> CashierStringKey.TransactionsStatusSynced
        TransactionsStatusFilter.PENDING -> CashierStringKey.TransactionsStatusPending
        TransactionsStatusFilter.FAILED -> CashierStringKey.TransactionsStatusFailed
        TransactionsStatusFilter.BLOCKED -> CashierStringKey.TransactionsStatusBlocked
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

private fun parseFinalizeSaleRequest(requestJson: String): ParsedFinalizeSaleRequest {
    return runCatching {
        val root = JSONObject(requestJson)
        val payments = root.optJSONArray("payments").toPaymentSnapshots()
        val receiptNumber = root.optStringOrNull("receipt_number")
        val tableLabel = root.optStringOrNull("table_label")
        val occurredAt = root.optLongOrNull("finalized_at_epoch_ms")
        val receiptDocument = root.optJSONObject("receipt_document")?.toReceiptDocument(
            defaultReceiptNumber = receiptNumber,
            defaultPrintedAtEpochMillis = occurredAt,
            tableLabel = tableLabel,
            languageCode = root.optStringOrNull("language_code") ?: "fi",
        )
        ParsedFinalizeSaleRequest(
            occurredAtEpochMillis = occurredAt,
            receiptNumber = receiptNumber,
            tableId = root.optStringOrNull("table_id"),
            tableLabel = tableLabel,
            cashierStaffId = root.optStringOrNull("cashier_staff_id"),
            cashierName = root.optStringOrNull("cashier_name"),
            totalCents = root.optIntOrNull("total_cents"),
            payments = payments,
            receiptDocument = receiptDocument,
        )
    }.getOrElse {
        ParsedFinalizeSaleRequest(
            occurredAtEpochMillis = null,
            receiptNumber = null,
            tableId = null,
            tableLabel = null,
            cashierStaffId = null,
            cashierName = null,
            totalCents = null,
            payments = emptyList(),
            receiptDocument = null,
        )
    }
}

private fun JSONObject.toReceiptDocument(
    defaultReceiptNumber: String?,
    defaultPrintedAtEpochMillis: Long?,
    tableLabel: String?,
    languageCode: String,
): ReceiptDocument {
    val documentPayments = optJSONArray("payments").toReceiptPaymentRecords()
    return ReceiptDocument(
        title = optStringOrNull("title").orEmpty(),
        lines = optJSONArray("lines").toReceiptLines(),
        footer = optStringOrNull("footer").orEmpty(),
        currencyCode = optStringOrNull("currencyCode") ?: "EUR",
        headerText = optStringOrNull("headerText"),
        footerText = optStringOrNull("footerText"),
        totals = optJSONObject("totals")?.toReceiptTotals(),
        payments = documentPayments,
        receiptNumber = optStringOrNull("receiptNumber") ?: defaultReceiptNumber,
        orderNumber = optStringOrNull("orderNumber"),
        printedAtEpochMillis = optLongOrNull("printedAtEpochMillis") ?: defaultPrintedAtEpochMillis,
        cashierName = optStringOrNull("cashierName"),
        customerDisplayName = optStringOrNull("customerDisplayName"),
        customerNote = optStringOrNull("customerNote"),
        internalNote = optStringOrNull("internalNote"),
        extraTextBlocks = optJSONArray("extraTextBlocks").toStringList(),
        tableLabel = tableLabel,
        languageCode = languageCode,
    )
}

private fun JSONObject.toReceiptTotals(): ReceiptTotals {
    return ReceiptTotals(
        subtotalCents = optInt("subtotalCents"),
        discountCents = optInt("discountCents"),
        taxCents = optInt("taxCents"),
        totalCents = optInt("totalCents"),
        vatBreakdown = optJSONArray("vatBreakdown").toVatRows(),
    )
}

private fun JSONArray?.toReceiptLines(): List<ReceiptLine> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        ReceiptLine(
            label = item.optStringOrNull("label").orEmpty(),
            value = item.optStringOrNull("value"),
            quantity = item.optStringOrNull("quantity"),
            unitPriceCents = item.optIntOrNull("unitPriceCents"),
            totalPriceCents = item.optIntOrNull("totalPriceCents"),
            note = item.optStringOrNull("note"),
            alignment = item.optStringOrNull("alignment").toReceiptAlignment(),
        )
    }
}

private fun JSONArray?.toReceiptPaymentRecords(): List<ReceiptPaymentRecord> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        val method = item.optStringOrNull("method").toPaymentMethod() ?: return@mapNotNull null
        ReceiptPaymentRecord(
            method = method,
            amountCents = item.optInt("amountCents"),
            reference = item.optStringOrNull("reference"),
            displayLabel = item.optStringOrNull("displayLabel"),
        )
    }
}

private fun JSONArray?.toPaymentSnapshots(): List<TransactionPaymentSnapshot> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        TransactionPaymentSnapshot(
            methodCode = item.optStringOrNull("method") ?: return@mapNotNull null,
            amountCents = item.optInt("amount_cents"),
            displayLabel = item.optStringOrNull("display_label"),
        )
    }
}

private fun JSONArray?.toVatRows(): List<ReceiptVatRow> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        ReceiptVatRow(
            ratePercent = item.optDouble("ratePercent"),
            taxCents = item.optInt("taxCents"),
            baseCents = item.optInt("baseCents"),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }
}

private fun String?.toPaymentMethod(): PaymentMethod? {
    val normalized = this?.trim()?.uppercase(Locale.ROOT) ?: return null
    return runCatching { PaymentMethod.valueOf(normalized) }.getOrNull()
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

private fun String?.toReceiptAlignment(): ReceiptAlignment {
    return when (this?.trim()?.uppercase(Locale.ROOT)) {
        ReceiptAlignment.CENTER.name -> ReceiptAlignment.CENTER
        ReceiptAlignment.RIGHT.name -> ReceiptAlignment.RIGHT
        else -> ReceiptAlignment.LEFT
    }
}

private fun String?.toTransactionSyncStatus(): TransactionSyncStatus {
    return when (this?.trim()?.lowercase(Locale.ROOT)) {
        "queued" -> TransactionSyncStatus.QUEUED
        "syncing" -> TransactionSyncStatus.SYNCING
        "synced" -> TransactionSyncStatus.SYNCED
        "failed" -> TransactionSyncStatus.FAILED
        "blocked" -> TransactionSyncStatus.BLOCKED
        else -> TransactionSyncStatus.UNKNOWN
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
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

private fun startOfTodayEpochMillis(): Long {
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun formatUiDateTime(epochMillis: Long): String {
    return DateFormat.format("dd.MM HH:mm", epochMillis).toString()
}
