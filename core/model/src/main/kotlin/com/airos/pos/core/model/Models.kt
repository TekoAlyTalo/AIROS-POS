package com.airos.pos.core.model

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
)

val AuthSession.staffName: String
    get() = displayName

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
    val taxRatePercent: Int,
    val barcode: String? = null,
    val requiresManagerOverride: Boolean = false,
)

data class TicketLine(
    val id: String,
    val menuItemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val totalPriceCents: Int,
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

data class RefundRequest(
    val ticketId: String,
    val amountCents: Int,
    val reason: String,
    val managerOverrideRequired: Boolean = true,
)

data class ReceiptLine(
    val label: String,
    val value: String? = null,
)

data class ReceiptDocument(
    val title: String,
    val lines: List<ReceiptLine>,
    val footer: String,
)

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
    val preferredPrinterId: String? = null,
)
