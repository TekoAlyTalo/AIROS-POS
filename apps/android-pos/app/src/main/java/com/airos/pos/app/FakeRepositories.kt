package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.core.datastore.StaffUiPreferencesStore
import com.airos.pos.core.model.AuthSession
import com.airos.pos.core.model.CashDrawer
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.KitchenOrder
import com.airos.pos.core.model.KitchenTicketDocument
import com.airos.pos.core.model.ManagerOverrideGrant
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptHandoffPayload
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.ReceiptPaymentRecord
import com.airos.pos.core.model.ReceiptTotals
import com.airos.pos.core.model.ReceiptVatRow
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ShiftStatus
import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.TableTruthSource
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.model.StaffUiPreferences
import com.airos.pos.core.model.StaffTableMapViewPreference
import com.airos.pos.core.model.Ticket
import com.airos.pos.core.model.TicketLine
import com.airos.pos.core.model.TicketStatus
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.AirosPosLedgerMapper
import com.airos.pos.domain.AuthRepository
import com.airos.pos.domain.CashLedgerRepository
import com.airos.pos.domain.KitchenRepository
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.MenuSyncResult
import com.airos.pos.domain.PaymentRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.StaffUiPreferencesRepository
import com.airos.pos.domain.ShiftRepository
import com.airos.pos.domain.SyncQueueRepository
import com.airos.pos.domain.TableRepository
import com.airos.pos.domain.TicketRepository
import com.airos.pos.domain.toJsonString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private const val BACKEND_RUNTIME_FLOOR_MAP_ID = "backend-authoritative-floor"
private const val BACKEND_RUNTIME_FLOOR_MAP_NAME = "Dining room"

private fun emptyBackendAuthoritativeFloorMap(): FloorMap {
    return FloorMap(
        id = BACKEND_RUNTIME_FLOOR_MAP_ID,
        name = BACKEND_RUNTIME_FLOOR_MAP_NAME,
        tables = emptyList(),
    )
}

class FakePosStore(
    initialFloorMap: FloorMap = emptyBackendAuthoritativeFloorMap(),
) {
    private val idCounter = AtomicInteger(100)

    // Menu items are served by BackendMenuRepository, not this store. The only
    // remaining reader is FakeTicketRepository.addItem, which is dormant in the
    // live checkout flow (OpenSaleRepository owns real line entry). Leave it
    // empty — any future caller must discover a real source, not a seed.
    val menuItems = MutableStateFlow<List<MenuItem>>(emptyList())
    val floorMap = MutableStateFlow(initialFloorMap)
    // Tickets are seeded empty: the previous ticket-t2-open fake made Table 2
    // appear OCCUPIED without a real sale. Real tickets arrive through the
    // OpenSale + backend truth paths.
    val tickets = MutableStateFlow<Map<String, Ticket>>(emptyMap())
    val kitchenOrders = MutableStateFlow<List<KitchenOrder>>(emptyList())
    val currentShift = MutableStateFlow<PosShift?>(null)
    val paymentsByTicket = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun now(): Long = System.currentTimeMillis()

    fun nextId(prefix: String): String = "$prefix-${idCounter.getAndIncrement()}"
}

class FakeAuthRepository(
    private val authRecords: List<StaffAuthRecord>,
) : AuthRepository {
    private val activeSessionFlow = MutableStateFlow<AuthSession?>(null)
    private val staffFlow = MutableStateFlow(authRecords.filter { it.isEnabled }.map(StaffAuthRecord::toStaffMember))
    private val managerFlow = MutableStateFlow(
        authRecords
            .filter { it.isEnabled && it.isManager }
            .map(StaffAuthRecord::toStaffMember),
    )

    override val activeSession: StateFlow<AuthSession?> = activeSessionFlow.asStateFlow()

    override fun observeQuickSelectStaff(): Flow<List<StaffMember>> = staffFlow
    override fun observeManagerQuickSelectStaff(): Flow<List<StaffMember>> = managerFlow

    private fun buildSession(authRecord: StaffAuthRecord, authMethod: String): AuthSession {
        return AuthSession(
            staffId = authRecord.staffId,
            displayName = authRecord.displayName,
            role = authRecord.role,
            isManager = authRecord.isManager,
            authenticatedAtEpochMillis = System.currentTimeMillis(),
            sessionId = UUID.randomUUID().toString(),
            authMethodSnapshot = authMethod,
        )
    }

    override suspend fun signInWithPin(staffId: String, pin: String): PosResult<AuthSession> {
        val authRecord = authRecords.firstOrNull { it.staffId == staffId }
            ?: return PosResult.Failure("Staff profile was not found.")
        if (!authRecord.isEnabled) {
            return PosResult.Failure("This staff profile is disabled.")
        }
        if (authRecord.pin != pin) {
            return PosResult.Failure("Incorrect PIN.")
        }
        val session = buildSession(authRecord, "PIN")
        activeSessionFlow.value = session
        return PosResult.Success(session)
    }

    override suspend fun signInWithNfc(staffId: String): PosResult<AuthSession> {
        val authRecord = authRecords.firstOrNull { it.staffId == staffId }
            ?: return PosResult.Failure("Staff profile was not found.")
        if (!authRecord.isEnabled) {
            return PosResult.Failure("This staff profile is disabled.")
        }
        val session = buildSession(authRecord, "NFC")
        activeSessionFlow.value = session
        return PosResult.Success(session)
    }

    override suspend fun verifyManagerOverride(
        managerStaffId: String,
        pin: String,
        reason: ManagerOverrideReason,
    ): PosResult<ManagerOverrideGrant> {
        val manager = authRecords.firstOrNull { it.staffId == managerStaffId }
            ?: return PosResult.Failure("Manager profile was not found.")
        if (!manager.isEnabled) {
            return PosResult.Failure("This manager profile is disabled.")
        }
        if (!manager.isManager) {
            return PosResult.Failure("Selected staff member cannot approve manager overrides.")
        }
        if (manager.pin != pin) {
            return PosResult.Failure("Manager PIN rejected for $reason.")
        }
        return PosResult.Success(
            ManagerOverrideGrant(
                managerStaffId = manager.staffId,
                managerDisplayName = manager.displayName,
                role = manager.role,
                reason = reason,
                grantedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun signOut() {
        activeSessionFlow.value = null
    }
}

class FakeTableRepository(
    private val store: FakePosStore,
    private val syncQueueRepository: SyncQueueRepository,
) : TableRepository, BackendAuthoritativeFloorMapSink {
    override fun observeFloorMap(): Flow<FloorMap> = store.floorMap

    override fun observeTable(tableId: String): Flow<RestaurantTable?> {
        return store.floorMap.map { floorMap -> floorMap.tables.firstOrNull { it.id == tableId } }
    }

    override fun replaceBackendAuthoritativeFloorMap(floorMap: FloorMap) {
        val currentTablesById = store.floorMap.value.tables.associateBy(RestaurantTable::id)
        val openTicketsByTableId = store.tickets.value.values
            .filter { ticket -> ticket.status !in setOf(TicketStatus.CLOSED, TicketStatus.PAID) }
            .associateBy { ticket -> ticket.tableId }
        val mergedFloorMap = floorMap.copy(
            tables = floorMap.tables.map { table ->
                val currentTable = currentTablesById[table.id]
                val activeTicketId = currentTable?.activeTicketId
                    ?: openTicketsByTableId[table.id]?.id
                table.copy(activeTicketId = activeTicketId)
            },
        )
        if (store.floorMap.value != mergedFloorMap) {
            store.floorMap.value = mergedFloorMap
        }
    }

    override suspend fun openTable(tableId: String, guestCount: Int, openedByStaffId: String): PosResult<RestaurantTable> {
        val table = store.floorMap.value.tables.firstOrNull { it.id == tableId }
            ?: return PosResult.Failure("Table not found.")
        val updatedTable = table.copy(
            status = TableStatus.OCCUPIED,
            guestCount = if (table.requiresBackendGuestTruth()) table.guestCount else guestCount,
        )
        store.floorMap.value = store.floorMap.value.copy(
            tables = store.floorMap.value.tables.map { current -> if (current.id == tableId) updatedTable else current },
        )
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "table",
            aggregateId = tableId,
            action = "open_table",
            payloadJson = """{"guestCount":$guestCount,"openedBy":"$openedByStaffId"}""",
        )
        return PosResult.Success(updatedTable)
    }

    override suspend fun assignDraftToServiceSpot(
        fromSpotId: String?,
        toSpotId: String,
        openedByStaffId: String,
    ): PosResult<RestaurantTable> {
        if (fromSpotId == toSpotId) {
            return PosResult.Failure("Cannot assign to the same service spot.")
        }

        val tables = store.floorMap.value.tables
        val toSpot = tables.firstOrNull { it.id == toSpotId }
            ?: return PosResult.Failure("Service spot not found.")

        // Guard: destination is OCCUPIED. Check whether it holds an open ticket or another draft.
        if (toSpot.status == TableStatus.OCCUPIED) {
            val activeTicket = toSpot.activeTicketId?.let { store.tickets.value[it] }
            val hasOpenTicket = activeTicket != null &&
                activeTicket.status !in setOf(TicketStatus.CLOSED, TicketStatus.PAID)
            val hasDraftSession = toSpot.activeTicketId == null // occupied but no tracked ticket = draft
            if (hasOpenTicket || hasDraftSession) {
                val spotName = toSpot.label.ifBlank { toSpotId }
                return PosResult.Failure("$spotName is already in use.")
            }
            // Stale OCCUPIED with a closed ticket — allow assignment and clean up below.
        }

        store.floorMap.value = store.floorMap.value.copy(
            tables = tables.map { spot ->
                when {
                    fromSpotId != null && spot.id == fromSpotId ->
                        spot.copy(
                            status = TableStatus.AVAILABLE,
                            activeTicketId = null,
                            guestCount = if (spot.requiresBackendGuestTruth()) spot.guestCount else 0,
                        )
                    spot.id == toSpotId ->
                        spot.copy(status = TableStatus.OCCUPIED, activeTicketId = null)
                    else -> spot
                }
            },
        )

        val updated = store.floorMap.value.tables.first { it.id == toSpotId }
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "service_spot",
            aggregateId = toSpotId,
            action = "assign_draft_to_service_spot",
            payloadJson = """{"fromSpotId":${fromSpotId?.let { "\"$it\"" } ?: "null"},"toSpotId":"$toSpotId","openedBy":"$openedByStaffId"}""",
        )
        return PosResult.Success(updated)
    }
}

class FakeMenuRepository(
    private val store: FakePosStore,
) : MenuRepository {
    private val _syncState = MutableStateFlow<MenuSyncResult?>(null)
    override val syncState: StateFlow<MenuSyncResult?> = _syncState.asStateFlow()

    override fun observeMenuItems(): Flow<List<MenuItem>> = store.menuItems

    override suspend fun findItemByBarcode(rawValue: String): MenuItem? {
        return store.menuItems.value.firstOrNull { it.barcode == rawValue }
    }

    override suspend fun refresh(): MenuSyncResult {
        val result = MenuSyncResult.Fresh
        _syncState.value = result
        return result
    }
}

class FakeTicketRepository(
    private val store: FakePosStore,
    private val authRepository: AuthRepository,
    private val syncQueueRepository: SyncQueueRepository,
) : TicketRepository {
    override fun observeTicketForTable(tableId: String): Flow<Ticket?> {
        return combine(store.floorMap, store.tickets) { floorMap, tickets ->
            val activeTicketId = floorMap.tables.firstOrNull { it.id == tableId }?.activeTicketId
            activeTicketId?.let(tickets::get)
        }
    }

    override suspend fun ensureOpenTicket(tableId: String, openedByStaffId: String): PosResult<Ticket> {
        return PosResult.Success(ensureOpenTicketInternal(tableId, openedByStaffId))
    }

    override suspend fun addItem(tableId: String, menuItemId: String): PosResult<Ticket> {
        val menuItem = store.menuItems.value.firstOrNull { it.id == menuItemId }
            ?: return PosResult.Failure("Menu item not found.")
        val staffId = authRepository.activeSession.value?.staffId ?: "offline-staff"
        val ticket = ensureOpenTicketInternal(tableId, staffId)
        val line = TicketLine(
            id = store.nextId("line"),
            menuItemId = menuItem.id,
            name = menuItem.name,
            quantity = 1,
            unitPriceCents = menuItem.priceCents,
            totalPriceCents = menuItem.priceCents,
            taxRatePercent = menuItem.taxRatePercent,
        )
        val updatedTicket = recalculateTicket(ticket.copy(lines = ticket.lines + line))
        store.tickets.value = store.tickets.value + (updatedTicket.id to updatedTicket)
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "ticket",
            aggregateId = updatedTicket.id,
            action = "add_item",
            payloadJson = """{"menuItemId":"$menuItemId","tableId":"$tableId"}""",
        )
        return PosResult.Success(updatedTicket)
    }

    override suspend fun sendToKitchen(ticketId: String): PosResult<Ticket> {
        val ticket = store.tickets.value[ticketId] ?: return PosResult.Failure("Ticket not found.")
        val updated = ticket.copy(status = TicketStatus.SENT_TO_KITCHEN, syncState = SyncState.QUEUED)
        store.tickets.value = store.tickets.value + (ticketId to updated)
        val tableLabel = store.floorMap.value.tables.firstOrNull { it.id == ticket.tableId }?.label ?: ticket.tableId
        store.kitchenOrders.value = store.kitchenOrders.value + KitchenOrder(
            ticketId = ticketId,
            tableLabel = tableLabel,
            itemSummaries = ticket.lines.map { "${it.quantity}x ${it.name}" },
            sentAtEpochMillis = store.now(),
            syncState = SyncState.QUEUED,
        )
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "ticket",
            aggregateId = ticketId,
            action = "send_to_kitchen",
            payloadJson = """{"ticketId":"$ticketId"}""",
        )
        return PosResult.Success(updated)
    }

    private fun recalculateTicket(ticket: Ticket): Ticket {
        val subtotal = ticket.lines.sumOf { it.totalPriceCents }
        val tax = ticket.lines.sumOf { line ->
            val taxRate = line.taxRatePercent
            if (taxRate <= 0.0) 0 else ((line.totalPriceCents * taxRate) / (100.0 + taxRate)).roundToInt()
        }
        return ticket.copy(
            subtotalCents = subtotal,
            taxCents = tax,
            totalCents = subtotal,
            syncState = SyncState.QUEUED,
            status = if (ticket.status == TicketStatus.SENT_TO_KITCHEN) TicketStatus.SENT_TO_KITCHEN else TicketStatus.OPEN,
        )
    }

    private fun ensureOpenTicketInternal(tableId: String, openedByStaffId: String): Ticket {
        val currentTable = store.floorMap.value.tables.first { it.id == tableId }
        val currentTicket = currentTable.activeTicketId?.let { store.tickets.value[it] }
        if (currentTicket != null && currentTicket.status != TicketStatus.CLOSED) {
            return currentTicket
        }

        val ticket = Ticket(
            id = store.nextId("ticket"),
            tableId = tableId,
            openedByStaffId = openedByStaffId,
            openedAtEpochMillis = store.now(),
            status = TicketStatus.OPEN,
            lines = emptyList(),
            subtotalCents = 0,
            taxCents = 0,
            totalCents = 0,
            syncState = SyncState.LOCAL_ONLY,
        )
        store.tickets.value = store.tickets.value + (ticket.id to ticket)
        store.floorMap.value = store.floorMap.value.copy(
            tables = store.floorMap.value.tables.map { table ->
                if (table.id == tableId) {
                    table.copy(
                        status = TableStatus.OCCUPIED,
                        activeTicketId = ticket.id,
                        guestCount = if (table.requiresBackendGuestTruth()) {
                            table.guestCount
                        } else if (table.guestCount == 0) {
                            2
                        } else {
                            table.guestCount
                        },
                    )
                } else {
                    table
                }
            },
        )
        return ticket
    }
}

class FakeKitchenRepository(
    private val store: FakePosStore,
) : KitchenRepository {
    override fun observeKitchenOrders(): Flow<List<KitchenOrder>> = store.kitchenOrders

    override suspend fun markReady(ticketId: String): PosResult<Unit> {
        store.kitchenOrders.value = store.kitchenOrders.value.filterNot { it.ticketId == ticketId }
        store.tickets.value[ticketId]?.let { ticket ->
            store.tickets.value = store.tickets.value + (ticketId to ticket.copy(status = TicketStatus.READY_TO_PAY))
        }
        return PosResult.Success(Unit)
    }
}

class FakePaymentRepository(
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

        val retainedCashCents = (paymentRecords
            .filter { it.method == PaymentMethod.CASH }
            .sumOf { it.amountCents } - changeCents).coerceAtLeast(0)
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

class FakeShiftRepository(
    private val store: FakePosStore,
    private val syncQueueRepository: SyncQueueRepository,
) : ShiftRepository {
    override fun observeCurrentShift(): Flow<PosShift?> = store.currentShift

    override suspend fun openShift(openingFloatCents: Int, staffId: String): PosResult<PosShift> {
        val shift = PosShift(
            id = store.nextId("shift"),
            openedByStaffId = staffId,
            openedAtEpochMillis = store.now(),
            status = ShiftStatus.OPEN,
            openingFloatCents = openingFloatCents,
            expectedCashCents = openingFloatCents,
        )
        store.currentShift.value = shift
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "shift",
            aggregateId = shift.id,
            action = "open_shift",
            payloadJson = """{"openingFloatCents":$openingFloatCents,"staffId":"$staffId"}""",
        )
        return PosResult.Success(shift)
    }

    override suspend fun closeShift(countedCashCents: Int, managerPin: String?): PosResult<PosShift> {
        val current = store.currentShift.value ?: return PosResult.Failure("No open shift.")
        val updated = current.copy(
            status = ShiftStatus.CLOSED,
            countedCashCents = countedCashCents,
            closedAtEpochMillis = store.now(),
        )
        store.currentShift.value = updated
        enqueueSyncItem(
            store = store,
            syncQueueRepository = syncQueueRepository,
            aggregateType = "shift",
            aggregateId = updated.id,
            action = "close_shift",
            payloadJson = """{"countedCashCents":$countedCashCents}""",
        )
        return PosResult.Success(updated)
    }
}

class DataStoreSettingsRepository(
    private val preferencesStore: TerminalPreferencesStore,
) : SettingsRepository {
    override fun observeSettings(): Flow<TerminalSettings> = preferencesStore.settings

    override suspend fun updateTerminalName(value: String) {
        preferencesStore.updateTerminalName(value)
    }

    override suspend fun updateEdgeBaseUrl(value: String) {
        preferencesStore.updateEdgeBaseUrl(value)
    }

    override suspend fun setOfflineMode(enabled: Boolean) {
        preferencesStore.setOfflineMode(enabled)
    }

    override suspend fun setNfcDirectLoginEnabled(enabled: Boolean) {
        preferencesStore.setNfcDirectLoginEnabled(enabled)
    }

    override suspend fun updateDefaultOpeningFloatCents(cents: Int) {
        preferencesStore.updateDefaultOpeningFloatCents(cents)
    }

    override suspend fun updateRestaurantKey(value: String) {
        preferencesStore.updateRestaurantKey(value)
    }
}


class DataStoreStaffUiPreferencesRepository(
    private val preferencesStore: StaffUiPreferencesStore,
) : StaffUiPreferencesRepository {
    override fun observeStaffUiPreferences(staffId: String): Flow<StaffUiPreferences> {
        return preferencesStore.observeStaffUiPreferences(staffId)
    }

    override fun observeTableMapViewMode(staffId: String): Flow<StaffTableMapViewPreference> {
        return preferencesStore.observeTableMapViewMode(staffId)
    }

    override suspend fun setTableMapViewMode(
        staffId: String,
        mode: StaffTableMapViewPreference,
    ) {
        preferencesStore.setTableMapViewMode(staffId, mode)
    }

    override suspend fun setFloorPlanViewport(
        staffId: String,
        viewport: StaffFloorPlanViewportPreference,
    ) {
        preferencesStore.setFloorPlanViewport(staffId, viewport)
    }
}

private suspend fun enqueueSyncItem(
    store: FakePosStore,
    syncQueueRepository: SyncQueueRepository,
    aggregateType: String,
    aggregateId: String,
    action: String,
    payloadJson: String,
) {
    syncQueueRepository.enqueue(
        SyncItem(
            id = store.nextId("sync"),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            action = action,
            payloadJson = payloadJson,
            state = SyncState.QUEUED,
            attemptCount = 0,
            createdAtEpochMillis = store.now(),
            updatedAtEpochMillis = store.now(),
        ),
    )
}

private fun RestaurantTable.requiresBackendGuestTruth(): Boolean = truthSource == TableTruthSource.BACKEND
