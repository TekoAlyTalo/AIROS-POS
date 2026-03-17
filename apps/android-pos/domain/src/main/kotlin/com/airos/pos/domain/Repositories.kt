package com.airos.pos.domain

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AuthSession
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.KitchenOrder
import com.airos.pos.core.model.ManagerOverrideGrant
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.model.Ticket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val activeSession: StateFlow<AuthSession?>
    fun observeQuickSelectStaff(): Flow<List<StaffMember>>
    fun observeManagerQuickSelectStaff(): Flow<List<StaffMember>>
    suspend fun signInWithPin(staffId: String, pin: String): PosResult<AuthSession>
    suspend fun verifyManagerOverride(
        managerStaffId: String,
        pin: String,
        reason: ManagerOverrideReason,
    ): PosResult<ManagerOverrideGrant>
    suspend fun signOut()
}

interface ShiftRepository {
    fun observeCurrentShift(): Flow<PosShift?>
    suspend fun openShift(openingFloatCents: Int, staffId: String): PosResult<PosShift>
    suspend fun closeShift(countedCashCents: Int, managerPin: String? = null): PosResult<PosShift>
}

interface TableRepository {
    fun observeFloorMap(): Flow<FloorMap>
    fun observeTable(tableId: String): Flow<RestaurantTable?>
    suspend fun openTable(tableId: String, guestCount: Int, openedByStaffId: String): PosResult<RestaurantTable>
}

interface MenuRepository {
    fun observeMenuItems(): Flow<List<MenuItem>>
    suspend fun findItemByBarcode(rawValue: String): MenuItem?
}

interface TicketRepository {
    fun observeTicketForTable(tableId: String): Flow<Ticket?>
    suspend fun ensureOpenTicket(tableId: String, openedByStaffId: String): PosResult<Ticket>
    suspend fun addItem(tableId: String, menuItemId: String): PosResult<Ticket>
    suspend fun sendToKitchen(ticketId: String): PosResult<Ticket>
}

interface KitchenRepository {
    fun observeKitchenOrders(): Flow<List<KitchenOrder>>
    suspend fun markReady(ticketId: String): PosResult<Unit>
}

interface PaymentRepository {
    fun observePaymentSummary(ticketId: String): Flow<PaymentSummary?>
    suspend fun collectPayment(ticketId: String, method: PaymentMethod, amountCents: Int): PosResult<PaymentSummary>
    suspend fun refund(request: RefundRequest): PosResult<Unit>
}

interface SettingsRepository {
    fun observeSettings(): Flow<TerminalSettings>
    suspend fun updateTerminalName(value: String)
    suspend fun updateEdgeBaseUrl(value: String)
    suspend fun setOfflineMode(enabled: Boolean)
}

interface SyncQueueRepository {
    fun observeQueue(): Flow<List<SyncItem>>
    suspend fun enqueue(item: SyncItem): PosResult<Unit>
    suspend fun updateState(itemId: String, state: SyncState, lastError: String? = null): PosResult<Unit>
    suspend fun nextPending(limit: Int = 20): List<SyncItem>
}
