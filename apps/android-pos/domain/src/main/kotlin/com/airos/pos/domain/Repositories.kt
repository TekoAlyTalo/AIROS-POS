package com.airos.pos.domain

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AuthSession
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.KitchenOrder
import com.airos.pos.core.model.ManagerOverrideGrant
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.NfcIdentityEvent
import com.airos.pos.core.model.NfcIdentityRecord
import com.airos.pos.core.model.NfcReceiptHandoffRecord
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.ReceiptHandoffPayload
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import com.airos.pos.core.model.SyncItem
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.model.StaffUiPreferences
import com.airos.pos.core.model.StaffTableMapViewPreference
import com.airos.pos.core.model.Ticket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val activeSession: StateFlow<AuthSession?>
    fun observeQuickSelectStaff(): Flow<List<StaffMember>>
    fun observeManagerQuickSelectStaff(): Flow<List<StaffMember>>
    suspend fun signInWithPin(staffId: String, pin: String): PosResult<AuthSession>
    suspend fun signInWithNfc(staffId: String): PosResult<AuthSession>
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

    /**
     * Assign or move an open draft to any service spot (table, bar seat, or future types).
     *
     * Both tables and bar seats are [RestaurantTable] entries distinguished by [RestaurantTable.spotType].
     * The assignment logic is identical for all spot types — this method does not branch on type.
     *
     * - [fromSpotId]: the spot currently holding the draft, or null for a walk-in draft.
     * - [toSpotId]: the destination spot; must exist and must not be actively occupied.
     *
     * On success, the old spot is released (AVAILABLE) and the new spot is marked OCCUPIED.
     * Fails with a clear message if:
     * - [fromSpotId] == [toSpotId] (same spot, no-op guard)
     * - [toSpotId] does not exist in the floor map
     * - [toSpotId] is already occupied by an open ticket or another draft session
     */
    suspend fun assignDraftToServiceSpot(
        fromSpotId: String?,
        toSpotId: String,
        openedByStaffId: String,
    ): PosResult<RestaurantTable>
}

sealed class MenuSyncResult {
    /** Network fetch succeeded; cache is up to date. */
    object Fresh : MenuSyncResult()
    /** Network unavailable; serving stale cache. */
    data class FromCache(val lastSyncedAt: Long) : MenuSyncResult()
    /** Network unavailable and no cache exists. */
    data class NoData(val reason: String) : MenuSyncResult()
}

interface MenuRepository {
    val syncState: StateFlow<MenuSyncResult?>
    fun observeMenuItems(): Flow<List<MenuItem>>
    suspend fun findItemByBarcode(rawValue: String): MenuItem?
    suspend fun refresh(): MenuSyncResult
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
    suspend fun finalizeTablePayment(request: TablePaymentRequest): PosResult<TablePaymentResult>
    suspend fun refund(request: RefundRequest): PosResult<Unit>
}

interface SettingsRepository {
    fun observeSettings(): Flow<TerminalSettings>
    suspend fun updateTerminalName(value: String)
    suspend fun updateEdgeBaseUrl(value: String)
    suspend fun setOfflineMode(enabled: Boolean)
    suspend fun setNfcDirectLoginEnabled(enabled: Boolean)
}

interface StaffUiPreferencesRepository {
    fun observeStaffUiPreferences(staffId: String): Flow<StaffUiPreferences>
    fun observeTableMapViewMode(staffId: String): Flow<StaffTableMapViewPreference>
    suspend fun setTableMapViewMode(
        staffId: String,
        mode: StaffTableMapViewPreference,
    )
    suspend fun setFloorPlanViewport(
        staffId: String,
        viewport: StaffFloorPlanViewportPreference,
    )
}


interface NfcIdentityRepository {
    fun observeStaffEnrollments(): Flow<List<NfcIdentityRecord>>
    fun observeCustomerEnrollments(): Flow<List<NfcIdentityRecord>>
    fun observeRecentEvents(limit: Int = 20): Flow<List<NfcIdentityEvent>>
    fun observeRecentReceiptHandoffs(limit: Int = 20): Flow<List<NfcReceiptHandoffRecord>>
    suspend fun resolveEnabledIdentity(canonicalUid: String): NfcIdentityRecord?
    suspend fun resolveCustomerIdentity(canonicalUid: String): NfcIdentityRecord?
    suspend fun enrollStaffTag(staff: StaffMember, canonicalUid: String): PosResult<NfcIdentityRecord>
    suspend fun removeStaffTag(staffId: String): PosResult<Unit>
    suspend fun enrollCustomerTag(canonicalUid: String, displayLabel: String): PosResult<NfcIdentityRecord>
    suspend fun removeCustomerTag(canonicalUid: String): PosResult<Unit>
    suspend fun recordReceiptHandoffStarted(payload: ReceiptHandoffPayload)
    suspend fun recordReceiptHandoffFailed(payload: ReceiptHandoffPayload, reason: String)
    suspend fun recordReceiptHandoff(canonicalUid: String, payload: ReceiptHandoffPayload): PosResult<NfcReceiptHandoffRecord>
    suspend fun recordUnknownTag(canonicalUid: String)
}

interface SyncQueueRepository {
    fun observeQueue(): Flow<List<SyncItem>>
    suspend fun enqueue(item: SyncItem): PosResult<Unit>
    suspend fun updateState(itemId: String, state: SyncState, lastError: String? = null): PosResult<Unit>
    suspend fun nextPending(limit: Int = 20): List<SyncItem>
}

interface OpenSaleRepository {
    fun observeOpenSales(): Flow<List<PersistedOpenSale>>
    suspend fun loadOpenSale(): PersistedOpenSale?
    suspend fun loadOpenSaleById(saleId: String): PersistedOpenSale?
    /** Returns the first OPEN sale for the given spot, or null. Pass null for walk-in (no assigned spot). */
    suspend fun loadOpenSaleForSpot(serviceSpotId: String?): PersistedOpenSale?
    /** Returns every OPEN sale for the given spot. Pass null for walk-in (no assigned spot). */
    suspend fun loadOpenSalesForSpot(serviceSpotId: String?): List<PersistedOpenSale>
    suspend fun createOpenSale(serviceSpotId: String?, serviceSpotLabel: String?): PersistedOpenSale
    suspend fun saveLines(saleId: String, lines: List<PersistedOpenSaleLine>)
    suspend fun assignServiceSpot(saleId: String, serviceSpotId: String?, serviceSpotLabel: String?)
    suspend fun clearOpenSale(saleId: String)
    suspend fun closeOpenSale(saleId: String)
}
