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

data class TablePosition(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class RestaurantTable(
    val id: String,
    val label: String,
    val areaName: String,
    val seats: Int,
    val status: TableStatus,
    val guestCount: Int = 0,
    val activeTicketId: String? = null,
    val position: TablePosition = TablePosition(0, 0, 180, 120),
    val cameraId: String? = null,
    val cameraLabel: String? = null,
)

data class FloorMap(
    val id: String,
    val name: String,
    val tables: List<RestaurantTable>,
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

data class ReceiptTotals(
    val subtotalCents: Int,
    val discountCents: Int = 0,
    val taxCents: Int = 0,
    val totalCents: Int,
)

data class ReceiptPaymentRecord(
    val method: PaymentMethod,
    val amountCents: Int,
    val reference: String? = null,
    val displayLabel: String? = null,
)

data class ReceiptDocument(
    val title: String = "Receipt",
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
)

data class TerminalSettings(
    val terminalName: String,
    val edgeBaseUrl: String,
    val offlineModeEnabled: Boolean,
    val nfcDirectLoginEnabled: Boolean = false,
    val preferredPrinterId: String? = null,
)
