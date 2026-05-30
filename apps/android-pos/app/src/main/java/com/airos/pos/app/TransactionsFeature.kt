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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.airos.pos.core.model.LocalFinalizedSaleRecord
import com.airos.pos.core.model.LocalSalesDayReport
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.core.model.PersistedOpenSaleTransferEvent
import com.airos.pos.core.ui.PosPane
import com.airos.pos.domain.OpenSaleRepository
import com.airos.pos.domain.SalesDayReportRepository
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
    AMOUNT_DESC,
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
)

private data class TransactionsUiState(
    val scope: TransactionsScope = TransactionsScope.CURRENT,
    val searchQuery: String = "",
    val dateRange: TransactionsDateRange = TransactionsDateRange.ALL,
    val statusFilter: TransactionsStatusFilter = TransactionsStatusFilter.ALL,
    val paymentFilter: TransactionsPaymentFilter = TransactionsPaymentFilter.ALL,
    val sortOption: TransactionsSortOption = TransactionsSortOption.TIME_DESC,
    val currentEvents: List<TransactionRecord> = emptyList(),
    val pastEvents: List<TransactionRecord> = emptyList(),
    val selectedEventId: String? = null,
    val localDayReport: LocalSalesDayReport? = null,
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
                    )
                }
        }
    }

    fun observeTodaySalesReport(): Flow<LocalSalesDayReport> {
        return salesDayReportRepository.observeSalesReport(
            startEpochMillisInclusive = startOfTodayEpochMillis(),
            endEpochMillisExclusive = startOfTomorrowEpochMillis(),
        )
    }

    fun observePaidTransactions(): Flow<List<TransactionRecord>> {
        return observeTodaySalesReport().map { report ->
            report.finalizedSales
                .sortedByDescending { it.finalizedAtEpochMillis }
                .map { sale -> sale.toTransactionRecord(backendBaseUrl) }
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
        amountCents = totalCents,
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
    )
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
            repository.observePaidTransactions().collect { items ->
                mutableState.update { current -> current.copy(pastEvents = items) }
            }
        }
        viewModelScope.launch {
            repository.observeTodaySalesReport().collect { report ->
                mutableState.update { it.copy(localDayReport = report) }
            }
        }
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

    fun setSortOption(sortOption: TransactionsSortOption) {
        mutableState.update { it.copy(sortOption = sortOption) }
    }

    companion object {
        fun factory(
            openSaleRepository: OpenSaleRepository,
            salesDayReportRepository: SalesDayReportRepository,
            backendBaseUrl: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    repository = TransactionsRepository(
                        openSaleRepository = openSaleRepository,
                        salesDayReportRepository = salesDayReportRepository,
                        backendBaseUrl = backendBaseUrl,
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
    onSearchQueryChange: (String) -> Unit,
    onStatusFilterChange: (TransactionsStatusFilter) -> Unit,
    onSortOptionChange: (TransactionsSortOption) -> Unit,
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
                    TransactionsSortOption.AMOUNT_DESC -> events.sortedByDescending { it.amountCents }
                }
            }
    }

    val dayReport = state.localDayReport
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

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosPane(
            title = "Päivän myynti",
            supportingText = "Tämän kassan myynti, maksutavat ja avoimet laskut.",
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
                            label = "Myynti tänään",
                            value = dayReport?.let { CentsFormatter.format(it.totalSalesCents) } ?: "...",
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Avoinna",
                            value = openBills.size.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Myyntejä",
                            value = dayReport?.saleCount?.toString() ?: "...",
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Käteinen",
                            value = dayReport?.let { CentsFormatter.format(it.cashSalesCents) } ?: "...",
                            modifier = Modifier.weight(1f),
                        )
                        SalesKpiCard(
                            label = "Kortti",
                            value = dayReport?.let { CentsFormatter.format(it.cardSalesCents) } ?: "...",
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
                        placeholder = { Text("Hae myyntiä, pöytää tai kuittia") },
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
                        Spacer(modifier = Modifier.weight(1f))
                        ReportFilterChip(
                            label = "Uusin ensin",
                            selected = state.sortOption == TransactionsSortOption.TIME_DESC,
                            onClick = { onSortOptionChange(TransactionsSortOption.TIME_DESC) },
                        )
                        ReportFilterChip(
                            label = "Suurin summa",
                            selected = state.sortOption == TransactionsSortOption.AMOUNT_DESC,
                            onClick = { onSortOptionChange(TransactionsSortOption.AMOUNT_DESC) },
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
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight(),
        )
    }
}

@Composable
internal fun TransactionsRoute(
    openSaleRepository: OpenSaleRepository,
    salesDayReportRepository: SalesDayReportRepository,
    backendBaseUrl: String?,
) {
    val viewModel: TransactionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TransactionsViewModel.factory(
            openSaleRepository = openSaleRepository,
            salesDayReportRepository = salesDayReportRepository,
            backendBaseUrl = backendBaseUrl,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    TransactionsScreen(
        state = state,
        onSelectEvent = viewModel::selectEvent,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onStatusFilterChange = viewModel::setStatusFilter,
        onSortOptionChange = viewModel::setSortOption,
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
private fun SalesDetailPane(
    event: TransactionRecord?,
    strings: CashierStrings,
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                event.publicReceiptUrl.isNullOrBlank() -> {
                    ReceiptTruthError(
                        message = "Julkisen kuitin linkki puuttuu tältä myynniltä. Kuittia ei näytetä ilman backendin kuittitotuutta.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    PublicReceiptPreview(
                        publicReceiptUrl = event.publicReceiptUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenBillDetailCard(
    event: TransactionRecord,
    strings: CashierStrings,
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PublicReceiptPreview(
    publicReceiptUrl: String,
    modifier: Modifier = Modifier,
) {
    var loadError by remember(publicReceiptUrl) { mutableStateOf<String?>(null) }

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
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    loadError = "Kuitin lataus epäonnistui: ${error.description}"
                                }
                            }
                        }
                        tag = publicReceiptUrl
                        loadUrl(publicReceiptUrl)
                    }
                },
                update = { webView ->
                    if (webView.tag != publicReceiptUrl) {
                        loadError = null
                        webView.tag = publicReceiptUrl
                        webView.loadUrl(publicReceiptUrl)
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

private fun startOfTodayEpochMillis(): Long {
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun startOfTomorrowEpochMillis(): Long {
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}

private fun formatUiDateTime(epochMillis: Long): String {
    return DateFormat.format("dd.MM HH:mm", epochMillis).toString()
}
