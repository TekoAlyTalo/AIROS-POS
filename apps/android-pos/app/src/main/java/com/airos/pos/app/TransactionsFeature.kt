package com.airos.pos.app

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
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

    fun setScope(scope: TransactionsScope) {
        mutableState.update {
            it.copy(
                scope = scope,
                statusFilter = TransactionsStatusFilter.ALL,
                paymentFilter = TransactionsPaymentFilter.ALL,
            )
        }
    }

    fun setSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value) }
    }

    fun setDateRange(value: TransactionsDateRange) {
        mutableState.update { it.copy(dateRange = value) }
    }

    fun setStatusFilter(value: TransactionsStatusFilter) {
        mutableState.update { it.copy(statusFilter = value) }
    }

    fun setPaymentFilter(value: TransactionsPaymentFilter) {
        mutableState.update { it.copy(paymentFilter = value) }
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
    onScopeChange: (TransactionsScope) -> Unit,
    onSearchChange: (String) -> Unit,
    onDateRangeChange: (TransactionsDateRange) -> Unit,
    onStatusFilterChange: (TransactionsStatusFilter) -> Unit,
    onPaymentFilterChange: (TransactionsPaymentFilter) -> Unit,
    onSelectEvent: (String) -> Unit,
) {
    val strings = rememberCashierStrings()
    val visibleEvents = remember(
        state.scope,
        state.searchQuery,
        state.dateRange,
        state.statusFilter,
        state.paymentFilter,
        state.currentEvents,
        state.pastEvents,
    ) {
        filterTransactions(state)
    }
    val selectedEvent = visibleEvents.firstOrNull { it.stableId == state.selectedEventId } ?: visibleEvents.firstOrNull()
    val horizontalScrollState = rememberScrollState()
    val statusOptions = remember(state.scope) { statusOptionsForScope(state.scope) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleButton(
                    label = strings[CashierStringKey.TransactionsCurrent],
                    selected = state.scope == TransactionsScope.CURRENT,
                    onClick = { onScopeChange(TransactionsScope.CURRENT) },
                    modifier = Modifier.weight(1f),
                )
                ToggleButton(
                    label = strings[CashierStringKey.TransactionsPast],
                    selected = state.scope == TransactionsScope.PAST,
                    onClick = { onScopeChange(TransactionsScope.PAST) },
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings[CashierStringKey.TransactionsSearchPlaceholder]) },
                singleLine = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransactionsDateRange.entries.forEach { range ->
                    FilterToggleChip(
                        label = strings[range.toStringKey()],
                        selected = state.dateRange == range,
                        onClick = { onDateRangeChange(range) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                statusOptions.forEach { option ->
                    FilterToggleChip(
                        label = strings[option.toStringKey()],
                        selected = state.statusFilter == option,
                        onClick = { onStatusFilterChange(option) },
                    )
                }
            }

            if (state.scope == TransactionsScope.PAST) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransactionsPaymentFilter.entries.forEach { option ->
                        FilterToggleChip(
                            label = strings[option.toStringKey()],
                            selected = state.paymentFilter == option,
                            onClick = { onPaymentFilterChange(option) },
                        )
                    }
                }
            }

            if (visibleEvents.isEmpty()) {
                Text(
                    text = if (state.scope == TransactionsScope.CURRENT) {
                        strings[CashierStringKey.TransactionsListEmptyCurrent]
                    } else {
                        strings[CashierStringKey.TransactionsListEmptyPast]
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = visibleEvents,
                        key = { it.stableId },
                    ) { event ->
                        TransactionListRow(
                            event = event,
                            selected = selectedEvent?.stableId == event.stableId,
                            onClick = { onSelectEvent(event.stableId) },
                        )
                    }
                }
            }
        }

        PosPane(
            title = strings[CashierStringKey.TransactionsTitle],
            supportingText = selectedEvent?.detailSupportingText(strings)
                ?: strings[CashierStringKey.TransactionsDetailEmpty],
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight(),
        ) {
            if (selectedEvent == null) {
                Text(
                    text = strings[CashierStringKey.TransactionsDetailEmpty],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TransactionsDetailPane(
                    event = selectedEvent,
                    strings = strings,
                )
            }
        }
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
        onScopeChange = viewModel::setScope,
        onSearchChange = viewModel::setSearchQuery,
        onDateRangeChange = viewModel::setDateRange,
        onStatusFilterChange = viewModel::setStatusFilter,
        onPaymentFilterChange = viewModel::setPaymentFilter,
        onSelectEvent = viewModel::selectEvent,
    )
}

@Composable
private fun TransactionListRow(
    event: TransactionRecord,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberCashierStrings()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.listHeadline(strings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = CentsFormatter.format(event.amountCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "${formatUiDateTime(event.occurredAtEpochMillis)} | ${event.locationLabel(strings)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = buildString {
                    append(strings[CashierStringKey.TransactionsFieldCashier])
                    append(": ")
                    append(event.actorDisplayName ?: strings[CashierStringKey.TransactionsUnavailable])
                    append(" | ")
                    append(strings[CashierStringKey.TransactionsFieldPaymentMethod])
                    append(": ")
                    append(event.paymentSummary(strings))
                    append(" | ")
                    append(strings[CashierStringKey.TransactionsFieldStatus])
                    append(": ")
                    append(event.statusLabel(strings))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransactionsDetailPane(
    event: TransactionRecord,
    strings: CashierStrings,
) {
    val timelineScroll = rememberScrollState()
    val summaryScroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyValueRow(strings[CashierStringKey.TransactionsFieldTime], formatUiDateTime(event.occurredAtEpochMillis))
        KeyValueRow(
            strings[CashierStringKey.TransactionsFieldReceipt],
            event.receiptNumber ?: strings[CashierStringKey.TransactionsUnavailable],
        )
        KeyValueRow(
            strings[CashierStringKey.TransactionsFieldSaleId],
            event.saleId ?: strings[CashierStringKey.TransactionsUnavailable],
        )
        KeyValueRow(strings[CashierStringKey.TransactionsFieldTable], event.locationLabel(strings))
        KeyValueRow(
            strings[CashierStringKey.TransactionsFieldCashier],
            event.actorDisplayName ?: strings[CashierStringKey.TransactionsUnavailable],
        )
        KeyValueRow(
            strings[CashierStringKey.TransactionsFieldCorrectionActor],
            strings[CashierStringKey.TransactionsUnavailable],
        )
        KeyValueRow(strings[CashierStringKey.TransactionsFieldAmount], CentsFormatter.format(event.amountCents))
        KeyValueRow(strings[CashierStringKey.TransactionsFieldPaymentMethod], event.paymentSummary(strings))
        KeyValueRow(strings[CashierStringKey.TransactionsFieldStatus], event.statusLabel(strings))
        event.syncStatus?.let {
            KeyValueRow(strings[CashierStringKey.TransactionsFieldSync], event.syncStatusLabel(strings))
        }
        if (event.source == TransactionsScope.PAST) {
            KeyValueRow(
                strings[CashierStringKey.TransactionsFieldPublicReceipt],
                if (event.publicReceiptUrlPath.isNullOrBlank()) {
                    strings[CashierStringKey.TransactionsPublicReceiptPending]
                } else {
                    strings[CashierStringKey.TransactionsPublicReceiptReady]
                },
            )
        } else {
            event.updatedAtEpochMillis?.let { updatedAt ->
                KeyValueRow(strings[CashierStringKey.TransactionsFieldUpdated], formatUiDateTime(updatedAt))
            }
        }

        if (event.receiptDocument != null) {
            ReceiptPreviewCard(
                title = strings[CashierStringKey.TransactionsReceiptPreview],
                document = event.receiptDocument,
                strings = strings,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )
        } else {
            CurrentBillSummaryCard(
                title = strings[CashierStringKey.TransactionsCurrentSummary],
                event = event,
                strings = strings,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = strings[CashierStringKey.TransactionsTimeline],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (event.timeline.isEmpty()) {
                    Text(
                        text = strings[CashierStringKey.TransactionsTimelineEmpty],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(timelineScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        event.timeline.forEach { entry ->
                            TimelineRow(entry = entry, strings = strings)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionReprint])
            }
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionRefund])
            }
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings[CashierStringKey.TransactionsActionCorrect])
            }
        }
    }
}

@Composable
private fun ReceiptPreviewCard(
    title: String,
    document: ReceiptDocument,
    strings: CashierStrings,
    modifier: Modifier = Modifier,
) {
    val summaryScroll = rememberScrollState()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(summaryScroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            document.headerText?.takeIf { it.isNotBlank() }?.let { headerText ->
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            document.lines.forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = buildString {
                            line.quantity?.let {
                                append(it)
                                append(' ')
                            }
                            append(line.label)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = line.value
                            ?: line.totalPriceCents?.let(CentsFormatter::format)
                            ?: line.unitPriceCents?.let(CentsFormatter::format)
                            ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                line.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            document.totals?.let { totals ->
                Spacer(modifier = Modifier.height(4.dp))
                KeyValueRow(strings[CashierStringKey.TransactionsSubtotal], CentsFormatter.format(totals.subtotalCents))
                if (totals.discountCents > 0) {
                    KeyValueRow(strings[CashierStringKey.TransactionsDiscount], CentsFormatter.format(totals.discountCents))
                }
                if (totals.taxCents > 0) {
                    KeyValueRow(strings[CashierStringKey.TransactionsTax], CentsFormatter.format(totals.taxCents))
                }
                KeyValueRow(strings[CashierStringKey.TransactionsTotal], CentsFormatter.format(totals.totalCents))
            }
            if (document.payments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                document.payments.forEach { payment ->
                    KeyValueRow(
                        label = payment.displayLabel ?: payment.method.name.toPaymentLabel(strings),
                        value = CentsFormatter.format(payment.amountCents),
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentBillSummaryCard(
    title: String,
    event: TransactionRecord,
    strings: CashierStrings,
    modifier: Modifier = Modifier,
) {
    val sale = event.openSale
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (sale == null) {
                Text(
                    text = strings[CashierStringKey.TransactionsUnavailable],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (sale.lines.isEmpty()) {
                Text(
                    text = strings[CashierStringKey.TransactionsNoLineItems],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sale.lines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${line.quantity}x ${line.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = CentsFormatter.format(line.totalCents()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
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
                Spacer(modifier = Modifier.height(4.dp))
                KeyValueRow(strings[CashierStringKey.TransactionsTotal], CentsFormatter.format(event.amountCents))
            }
        }
    }
}

@Composable
private fun TimelineRow(
    entry: TransactionTimelineEntry,
    strings: CashierStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = strings[entry.titleKey],
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatUiDateTime(entry.occurredAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.supportingText?.takeIf { it.isNotBlank() }?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun FilterToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                shape = RoundedCornerShape(999.dp),
            ),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            Color.Transparent
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun filterTransactions(state: TransactionsUiState): List<TransactionRecord> {
    val source = when (state.scope) {
        TransactionsScope.CURRENT -> state.currentEvents
        TransactionsScope.PAST -> state.pastEvents
    }
    val now = System.currentTimeMillis()
    val query = state.searchQuery.trim().lowercase(Locale.ROOT)

    return source.filter { event ->
        event.matchesDateRange(state.dateRange, now) &&
            event.matchesStatus(state.statusFilter) &&
            event.matchesPayment(state.paymentFilter) &&
            event.matchesSearch(query)
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

private fun TransactionRecord.listHeadline(strings: CashierStrings): String {
    return when (source) {
        TransactionsScope.CURRENT -> saleId?.takeLast(6)?.uppercase(Locale.ROOT)?.let {
            "${strings[CashierStringKey.TransactionsStatusOpen]} $it"
        }
            ?: strings[CashierStringKey.TransactionsStatusOpen]
        TransactionsScope.PAST -> receiptNumber ?: saleId ?: strings[CashierStringKey.TransactionsStatusPaid]
    }
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

private fun statusOptionsForScope(scope: TransactionsScope): List<TransactionsStatusFilter> {
    return when (scope) {
        TransactionsScope.CURRENT -> listOf(
            TransactionsStatusFilter.ALL,
            TransactionsStatusFilter.OPEN,
        )
        TransactionsScope.PAST -> listOf(
            TransactionsStatusFilter.ALL,
            TransactionsStatusFilter.PAID,
            TransactionsStatusFilter.SYNCED,
            TransactionsStatusFilter.PENDING,
            TransactionsStatusFilter.FAILED,
            TransactionsStatusFilter.BLOCKED,
        )
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
