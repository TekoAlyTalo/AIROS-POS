package com.airos.pos.app

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.core.model.AuthSession
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.KitchenOrder
import com.airos.pos.core.model.KitchenTicketDocument
import com.airos.pos.core.model.ManagerOverrideGrant
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ShiftStatus
import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.model.Ticket
import com.airos.pos.core.model.TicketLine
import com.airos.pos.core.model.TicketStatus
import com.airos.pos.domain.AuthRepository
import com.airos.pos.domain.KitchenRepository
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.PaymentRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.ShiftRepository
import com.airos.pos.domain.SyncQueueRepository
import com.airos.pos.domain.TableRepository
import com.airos.pos.domain.TicketRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

class FakePosStore {
    private val idCounter = AtomicInteger(100)

    val menuItems = MutableStateFlow(SampleData.menuItems())
    val floorMap = MutableStateFlow(SampleData.floorMap())
    val tickets = MutableStateFlow(SampleData.initialTickets())
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

    override suspend fun signInWithPin(staffId: String, pin: String): PosResult<AuthSession> {
        val authRecord = authRecords.firstOrNull { it.staffId == staffId }
            ?: return PosResult.Failure("Staff profile was not found.")
        if (!authRecord.isEnabled) {
            return PosResult.Failure("This staff profile is disabled.")
        }
        if (authRecord.pin != pin) {
            return PosResult.Failure("Incorrect PIN.")
        }
        val session = AuthSession(
            staffId = authRecord.staffId,
            displayName = authRecord.displayName,
            role = authRecord.role,
            isManager = authRecord.isManager,
            authenticatedAtEpochMillis = System.currentTimeMillis(),
        )
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
) : TableRepository {
    override fun observeFloorMap(): Flow<FloorMap> = store.floorMap

    override fun observeTable(tableId: String): Flow<RestaurantTable?> {
        return store.floorMap.map { floorMap -> floorMap.tables.firstOrNull { it.id == tableId } }
    }

    override suspend fun openTable(tableId: String, guestCount: Int, openedByStaffId: String): PosResult<RestaurantTable> {
        val table = store.floorMap.value.tables.firstOrNull { it.id == tableId }
            ?: return PosResult.Failure("Table not found.")
        val updatedTable = table.copy(status = TableStatus.OCCUPIED, guestCount = guestCount)
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
}

class FakeMenuRepository(
    private val store: FakePosStore,
) : MenuRepository {
    override fun observeMenuItems(): Flow<List<MenuItem>> = store.menuItems

    override suspend fun findItemByBarcode(rawValue: String): MenuItem? {
        return store.menuItems.value.firstOrNull { it.barcode == rawValue }
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
        val menuIndex = store.menuItems.value.associateBy { it.id }
        val subtotal = ticket.lines.sumOf { it.totalPriceCents }
        val tax = ticket.lines.sumOf { line ->
            val taxRate = menuIndex[line.menuItemId]?.taxRatePercent ?: 0
            if (taxRate <= 0) 0 else (line.totalPriceCents * taxRate) / (100 + taxRate)
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
                    table.copy(status = TableStatus.OCCUPIED, activeTicketId = ticket.id, guestCount = if (table.guestCount == 0) 2 else table.guestCount)
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
) : PaymentRepository {
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
                        table.copy(status = TableStatus.AVAILABLE, activeTicketId = null, guestCount = 0)
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
