package com.airos.pos.core.model

import java.util.UUID

enum class StaffRole {
    SERVER,
    MANAGER,
    CASHIER,
    KITCHEN,
    ADMIN,
}

data class StaffMember(
    val id: String,
    val displayName: String,
    val role: StaffRole,
    val quickColorHex: String,
    val isManager: Boolean = role == StaffRole.MANAGER || role == StaffRole.ADMIN,
    val isEnabled: Boolean = true,
)

val StaffMember.isActive: Boolean
    get() = isEnabled

data class StaffAuthRecord(
    val staffId: String,
    val displayName: String,
    val role: StaffRole,
    val pin: String,
    val isManager: Boolean,
    val isEnabled: Boolean,
    val quickColorHex: String,
) {
    fun toStaffMember(): StaffMember {
        return StaffMember(
            id = staffId,
            displayName = displayName,
            role = role,
            quickColorHex = quickColorHex,
            isManager = isManager,
            isEnabled = isEnabled,
        )
    }
}

data class AuthSession(
    val staffId: String,
    val displayName: String,
    val role: StaffRole,
    val isManager: Boolean,
    val authenticatedAtEpochMillis: Long,
    val sessionId: String = UUID.randomUUID().toString(),
    val authMethodSnapshot: String = "UNKNOWN",
)

val AuthSession.staffName: String
    get() = displayName


enum class StaffTableMapViewPreference {
    FLOOR_PLAN,
    GRID,
}

data class StaffFloorPlanViewportPreference(
    val zoomScale: Float? = null,
    val panX: Float? = null,
    val panY: Float? = null,
)

data class StaffUiPreferences(
    val tableMapViewMode: StaffTableMapViewPreference = StaffTableMapViewPreference.FLOOR_PLAN,
    val floorPlanViewport: StaffFloorPlanViewportPreference = StaffFloorPlanViewportPreference(),
)

enum class NfcLinkedEntityType {
    STAFF,
    LOYALTY_MEMBER,
    RECEIPT_HANDOFF,
    GENERIC_TRIGGER,
}

enum class NfcIdentityEventType {
    ENROLLED,
    REPLACED,
    REMOVED,
    MATCHED,
    UNKNOWN_TAG,
    RECEIPT_HANDOFF_STARTED,
    RECEIPT_HANDOFF_LINKED,
    RECEIPT_HANDOFF_FAILED,
    CUSTOMER_MATCHED,
    UNKNOWN_CUSTOMER_TAG,
}

data class NfcIdentityRecord(
    val canonicalUid: String,
    val entityType: NfcLinkedEntityType,
    val entityId: String,
    val entityDisplayLabel: String,
    val entityRoleLabel: String? = null,
    val nickname: String? = null,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class NfcIdentityEvent(
    val id: Long,
    val type: NfcIdentityEventType,
    val canonicalUid: String? = null,
    val entityType: NfcLinkedEntityType? = null,
    val entityId: String? = null,
    val entityDisplayLabel: String? = null,
    val message: String,
    val occurredAtEpochMillis: Long,
)

data class ReceiptHandoffPayload(
    val receiptNumber: String,
    val ticketId: String,
    val saleId: String? = null,
    val receiptSnapshotId: String? = null,
    val publicReceiptUrl: String? = null,
    val publicUrlPath: String? = null,
    val rawPublicToken: String? = null,
    val deliveryTokenIds: List<String> = emptyList(),
    val createdAtEpochMillis: Long,
)

data class NfcReceiptHandoffRecord(
    val id: Long = 0,
    val canonicalUid: String,
    val receiptNumber: String,
    val ticketId: String,
    val saleId: String? = null,
    val receiptSnapshotId: String? = null,
    val publicReceiptUrl: String? = null,
    val publicUrlPath: String? = null,
    val rawPublicToken: String? = null,
    val deliveryTokenIds: List<String> = emptyList(),
    val linkedCustomerEntityId: String? = null,
    val linkedCustomerDisplayLabel: String? = null,
    val createdAtEpochMillis: Long,
)

enum class ManagerOverrideReason {
    REFUND,
    VOID_TICKET,
    SHIFT_CLOSE,
    OPEN_CASH_DRAWER,
    SETTINGS_CHANGE,
}

data class ManagerOverrideGrant(
    val managerStaffId: String,
    val managerDisplayName: String,
    val role: StaffRole,
    val reason: ManagerOverrideReason,
    val grantedAtEpochMillis: Long,
)

enum class TableStatus {
    AVAILABLE,
    OCCUPIED,
    DIRTY,
    RESERVED,
}

enum class TableAttentionFlag {
    NONE,
    CHECK_TABLE,
}

enum class TableOperationalFlag {
    CHECK,
    NEEDS_CLEANING,
}

enum class TableTruthSource {
    LOCAL,
    BACKEND,
}

/**
 * Distinguishes the physical/functional type of a service spot.
 * Both types flow through the same assignment and ticket lifecycle —
 * this is a display/UI hint, not a logic fork.
 *
 * Extend here when additional spot categories are needed (e.g. TERRACE_LOUNGE).
 */
enum class ServiceSpotType {
    TABLE,
    BAR_SEAT,
}

enum class FloorPlanMarkerAnchor {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    CENTER,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
    BOTTOM_RIGHT;

    companion object {
        fun fromRawValue(raw: String?): FloorPlanMarkerAnchor? {
            return when (raw?.trim()?.lowercase()) {
                "top-left" -> TOP_LEFT
                "top" -> TOP
                "top-right" -> TOP_RIGHT
                "left" -> LEFT
                "center" -> CENTER
                "right" -> RIGHT
                "bottom-left" -> BOTTOM_LEFT
                "bottom" -> BOTTOM
                "bottom-right" -> BOTTOM_RIGHT
                else -> null
            }
        }
    }
}

data class TablePosition(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class RestaurantTable(
    val id: String,
    val backendTableId: Int? = null,
    val label: String,
    val areaName: String,
    val seats: Int,
    val status: TableStatus,
    val guestCount: Int = 0,
    val activeTicketId: String? = null,
    val position: TablePosition = TablePosition(0, 0, 180, 120),
    val cameraId: String? = null,
    val cameraLabel: String? = null,
    val attentionFlag: TableAttentionFlag = TableAttentionFlag.NONE,
    val operationalFlags: Set<TableOperationalFlag> = emptySet(),
    val emptyAnchorTime: String? = null,
    val reviewAnchorTime: String? = null,
    val reviewFrom: String? = null,
    val reviewTo: String? = null,
    val truthSource: TableTruthSource = TableTruthSource.LOCAL,
    /** Functional type of this service spot. Defaults to TABLE for backward compatibility. */
    val spotType: ServiceSpotType = ServiceSpotType.TABLE,
    /** Backend-authoritative local open-bill cap. Null = unbounded. */
    val maxOpenBills: Int? = null,
    /** Editor floor-plan truth. Existing Int [position] remains only for legacy table flows. */
    val floorPlanX: Float? = null,
    val floorPlanY: Float? = null,
    val floorPlanWidth: Float? = null,
    val floorPlanHeight: Float? = null,
    val floorPlanRotation: Float = 0f,
    val floorPlanShape: String? = null,
    val chairLayout: String? = null,
    val tableNumber: Int? = null,
    val color: String? = null,
    val backrestDirection: String? = null,
    val backrestMode: String? = null,
    val statusChipAnchor: FloorPlanMarkerAnchor? = null,
    val seatMarkerAnchor: FloorPlanMarkerAnchor? = null,
)
data class FloorMapArea(
    val id: String,
    val label: String,
    /** Legacy rounded coordinates. Renderer must prefer the exact Float fields below. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val xPx: Float = x.toFloat(),
    val yPx: Float = y.toFloat(),
    val widthPx: Float = width.toFloat(),
    val heightPx: Float = height.toFloat(),
    val shape: String = "rectangle",
    val rotation: Float = 0f,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val areaType: String? = null,
    val surfaceMaterial: String? = null,
    val p1XPercent: Float? = null,
    val p1YPercent: Float? = null,
    val p2XPercent: Float? = null,
    val p2YPercent: Float? = null,
    val p3XPercent: Float? = null,
    val p3YPercent: Float? = null,
    val apexXPercent: Float? = null,
)

enum class FloorPlanSofaStyle {
    PREMIUM_LEATHER,
    TERRACE_POLY_RATTAN,
}

data class FloorMapObject(
    val id: String,
    val type: String,
    val label: String,
    /** Legacy rounded coordinates. Renderer must prefer the exact Float fields below. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val xPx: Float = x.toFloat(),
    val yPx: Float = y.toFloat(),
    val widthPx: Float = width.toFloat(),
    val heightPx: Float = height.toFloat(),
    val rotation: Float = 0f,
    val color: String? = null,
    val backrestDirection: String? = null,
    val backrestMode: String? = null,
    val armrestMode: String? = null,
    val sofaStyle: FloorPlanSofaStyle? = null,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val shape: String? = null,
    val chairLayout: String? = null,
    val capacity: Int? = null,
    val tableNumber: Int? = null,
    val cameraId: String? = null,
    val coverageType: String? = null,
    val linkedTargetType: String? = null,
    val linkedTargetId: String? = null,
    val doorHingeSide: String? = null,
    val doorSwingDirection: String? = null,
)

data class FloorMap(
    val id: String,
    val name: String,
    val tables: List<RestaurantTable>,
    val areas: List<FloorMapArea> = emptyList(),
    val objects: List<FloorMapObject> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val widthPx: Float? = width?.toFloat(),
    val heightPx: Float? = height?.toFloat(),
    val pxPerMeter: Float? = null,
    val scaleStatus: String? = null,
    val isAuthoritativeFloorPlan: Boolean = false,
    val floorPlanError: String? = null,
)
enum class TicketStatus {
    OPEN,
    SENT_TO_KITCHEN,
    READY_TO_PAY,
    PAID,
    REFUND_PENDING,
    CLOSED,
}

enum class SyncState {
    LOCAL_ONLY,
    QUEUED,
    SYNCING,
    SYNCED,
    FAILED,
}

data class MenuItem(
    val id: String,
    val sku: String,
    val name: String,
    val category: String,
    val priceCents: Int,
    val taxRatePercent: Double,
    val barcode: String? = null,
    val imageUrl: String? = null,
    val subcategory: String? = null,
    val subcategoryImageUrl: String? = null,
    val requiresManagerOverride: Boolean = false,
)

data class TicketLine(
    val id: String,
    val menuItemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val totalPriceCents: Int,
    val taxRatePercent: Double = 0.0,
    val note: String? = null,
)

data class Ticket(
    val id: String,
    val tableId: String,
    val openedByStaffId: String,
    val openedAtEpochMillis: Long,
    val status: TicketStatus,
    val lines: List<TicketLine>,
    val subtotalCents: Int,
    val taxCents: Int,
    val totalCents: Int,
    val syncState: SyncState,
)

data class PersistedOpenSale(
    val saleId: String,
    val serviceSpotId: String?,
    val serviceSpotLabel: String?,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lines: List<PersistedOpenSaleLine> = emptyList(),
)

data class PersistedOpenSaleLine(
    val saleId: String,
    val itemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val taxRatePercent: Double,
    val discountPercent: Int? = null,
    val discountAmountCents: Int? = null,
)

data class PersistedOpenSaleTransferEvent(
    val id: Long,
    val saleId: String,
    val fromServiceSpotId: String?,
    val fromServiceSpotLabel: String?,
    val toServiceSpotId: String?,
    val toServiceSpotLabel: String?,
    val actedByStaffId: String,
    val actedByDisplayName: String,
    val occurredAtEpochMillis: Long,
)

data class KitchenOrder(
    val ticketId: String,
    val tableLabel: String,
    val itemSummaries: List<String>,
    val sentAtEpochMillis: Long,
    val syncState: SyncState,
)

enum class ShiftStatus {
    OPEN,
    CLOSED,
}

data class PosShift(
    val id: String,
    val openedByStaffId: String,
    val openedAtEpochMillis: Long,
    val status: ShiftStatus,
    val openingFloatCents: Int,
    val expectedCashCents: Int,
    val countedCashCents: Int? = null,
    val closedAtEpochMillis: Long? = null,
)

data class AttendanceEntry(
    val staffId: String,
    val staffName: String,
    val status: String,
    val startedAt: String,
    val durationMinutes: Double,
)

data class WorktimeAttendanceSnapshot(
    val currentlyOnSite: List<AttendanceEntry> = emptyList(),
    val clockedInToday: List<AttendanceEntry> = emptyList(),
)

enum class PaymentMethod {
    CASH,
    CARD,
    VOUCHER,
}

data class PaymentSummary(
    val ticketId: String,
    val totalDueCents: Int,
    val paidCents: Int,
    val remainingCents: Int,
    val availableMethods: List<PaymentMethod>,
    val refundEligible: Boolean,
)

data class PaymentEntry(
    val method: PaymentMethod,
    val amountCents: Int,
    val reference: String? = null,
    val displayLabel: String? = null,
)

data class TablePaymentRequest(
    val tableId: String? = null,
    val tableLabel: String? = null,
    val lines: List<TicketLine>,
    val payments: List<PaymentEntry>,
    val cashTenderedCents: Int? = null,
    val voucherBarcodeValue: String? = null,
    val discountAmountCents: Int = 0,
    val discountLabel: String? = null,
)

data class TablePaymentResult(
    val ticketId: String,
    val tableId: String? = null,
    val tableLabel: String? = null,
    val totalDueCents: Int,
    val totalPaidCents: Int,
    val changeCents: Int,
    val payments: List<ReceiptPaymentRecord>,
    val receiptDocument: ReceiptDocument,
    val receiptHandoff: ReceiptHandoffPayload? = null,
)

data class RefundRequest(
    val ticketId: String,
    val amountCents: Int,
    val reason: String,
    val managerOverrideRequired: Boolean = true,
)

data class ReceiptLine(
    val label: String,
    val value: String? = null,
    val quantity: String? = null,
    val unitPriceCents: Int? = null,
    val totalPriceCents: Int? = null,
    val taxRatePercent: Double? = null,
    val note: String? = null,
    val alignment: ReceiptAlignment = ReceiptAlignment.LEFT,
)

enum class ReceiptAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

enum class ReceiptImageSourceType {
    ANDROID_RESOURCE,
    FILE_PATH,
    CONTENT_URI,
    NETWORK_URL,
    DATA_URL,
}

data class ReceiptImageSource(
    val type: ReceiptImageSourceType,
    val value: String,
)

data class ReceiptLogo(
    val source: ReceiptImageSource,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val align: ReceiptAlignment = ReceiptAlignment.CENTER,
)

enum class ReceiptBarcodeFormat {
    CODE128,
    EAN13,
    EAN8,
    UPC_A,
    UPC_E,
    QR_CODE,
    PDF417,
}

data class ReceiptBarcode(
    val value: String,
    val format: ReceiptBarcodeFormat = ReceiptBarcodeFormat.QR_CODE,
    val label: String? = null,
    val align: ReceiptAlignment = ReceiptAlignment.CENTER,
    val heightPx: Int? = null,
    val moduleWidth: Int? = null,
)

data class ReceiptBonusProgram(
    val programName: String,
    val memberId: String? = null,
    val memberDisplayName: String? = null,
    val pointsBalance: Int? = null,
    val pointsEarned: Int? = null,
    val pointsRedeemed: Int? = null,
    val tierName: String? = null,
    val footerMessage: String? = null,
)

data class ReceiptBusiness(
    val displayName: String,
    val legalName: String? = null,
    val businessId: String? = null,
    val vatId: String? = null,
    val addressLines: List<String> = emptyList(),
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
)

data class ReceiptVatRow(
    val ratePercent: Double,
    val taxCents: Int,
    val baseCents: Int,
)

data class ReceiptTotals(
    val subtotalCents: Int,
    val discountCents: Int = 0,
    val taxCents: Int = 0,
    val totalCents: Int,
    val vatBreakdown: List<ReceiptVatRow> = emptyList(),
)

data class ReceiptPaymentRecord(
    val method: PaymentMethod,
    val amountCents: Int,
    val reference: String? = null,
    val displayLabel: String? = null,
)

data class ReceiptDocument(
    val title: String = "",
    val lines: List<ReceiptLine> = emptyList(),
    val footer: String = "",
    val currencyCode: String = "EUR",
    val business: ReceiptBusiness? = null,
    val logo: ReceiptLogo? = null,
    val headerText: String? = null,
    val footerText: String? = null,
    val barcode: ReceiptBarcode? = null,
    val bonusProgram: ReceiptBonusProgram? = null,
    val totals: ReceiptTotals? = null,
    val payments: List<ReceiptPaymentRecord> = emptyList(),
    val receiptNumber: String? = null,
    val orderNumber: String? = null,
    val printedAtEpochMillis: Long? = null,
    val cashierName: String? = null,
    val customerDisplayName: String? = null,
    val customerNote: String? = null,
    val internalNote: String? = null,
    val extraTextBlocks: List<String> = emptyList(),
    val tableLabel: String? = null,
    val countryProfile: String = "FI",
    val languageCode: String = "fi",
) {
    val primaryFooter: String
        get() = footerText ?: footer
}
data class KitchenTicketDocument(
    val title: String,
    val lines: List<String>,
)

data class SyncItem(
    val id: String,
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val payloadJson: String,
    val state: SyncState,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastError: String? = null,
)

enum class DeviceVendor {
    SUNMI,
    GENERIC_ANDROID,
}

enum class DeviceConnectionState {
    READY,
    UNAVAILABLE,
    BUSY,
}

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val vendor: DeviceVendor,
    val hasBuiltInPrinter: Boolean,
    val hasScanner: Boolean,
    val hasCashDrawer: Boolean,
    val hasRearCamera: Boolean,
)

data class ScanEvent(
    val rawValue: String,
    val symbology: String,
    val scannedAtEpochMillis: Long,
)

enum class CameraConnectionState {
    IDLE,
    CONNECTING,
    WAITING_FOR_VIDEO,
    LIVE,
    RECONNECTING,
    ERROR,
}

data class CameraPreviewRequest(
    val cameraId: String,
    val signalingBaseUrl: String,
    val tableId: String? = null,
    val tableLabel: String? = null,
    val sourceLabel: String = cameraId,
)

data class CameraPreviewState(
    val isStreaming: Boolean = false,
    val sourceLabel: String = "Camera preview",
    val cameraId: String? = null,
    val tableId: String? = null,
    val tableLabel: String? = null,
    val connectionState: CameraConnectionState = CameraConnectionState.IDLE,
    val detailMessage: String = "Preview idle",
    val errorMessage: String? = null,
    val signalingUrl: String? = null,
    val lastFrameAtEpochMillis: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoRotationDeg: Int? = null,
)

data class TerminalSettings(
    val terminalName: String,
    val edgeBaseUrl: String,
    val offlineModeEnabled: Boolean,
    val nfcDirectLoginEnabled: Boolean = false,
    val preferredPrinterId: String? = null,
    val defaultOpeningFloatCents: Int = 5000,
    // Restaurant scope key. Shared by attendance sync, menu fetch, and anything else
    // that needs restaurant-scoped API calls. Persisted in DataStore so attendance
    // sync metadata and menu cache scoping agree on a single source of truth.
    val restaurantKey: String = "ravintola_default",
)
