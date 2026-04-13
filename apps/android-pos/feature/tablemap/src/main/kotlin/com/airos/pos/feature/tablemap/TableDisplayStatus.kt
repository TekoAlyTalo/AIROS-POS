package com.airos.pos.feature.tablemap

import com.airos.pos.core.model.TableStatus

internal enum class TableDisplayStatusKind {
    AVAILABLE,
    OPEN_BILL,
    OCCUPIED,
    DIRTY,
    RESERVED,
    RESERVED_WITH_OPEN_BILL,
}

internal data class TableDisplayStatus(
    val kind: TableDisplayStatusKind,
    val label: String,
    val physicalStatus: TableStatus,
    val openBillCount: Int,
) {
    val physicalLabel: String
        get() = physicalStatus.physicalStatusLabel()

    val differsFromPhysical: Boolean
        get() = label != physicalLabel
}

internal fun resolveTableDisplayStatus(
    physicalStatus: TableStatus,
    openBillCount: Int,
): TableDisplayStatus {
    val safeOpenBillCount = openBillCount.coerceAtLeast(0)
    val kind = when {
        physicalStatus == TableStatus.DIRTY -> TableDisplayStatusKind.DIRTY
        safeOpenBillCount <= 0 -> physicalStatus.toDisplayStatusKind()
        physicalStatus == TableStatus.AVAILABLE -> TableDisplayStatusKind.OPEN_BILL
        physicalStatus == TableStatus.OCCUPIED -> TableDisplayStatusKind.OCCUPIED
        physicalStatus == TableStatus.RESERVED -> TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL
        else -> TableDisplayStatusKind.OPEN_BILL
    }

    return TableDisplayStatus(
        kind = kind,
        label = kind.labelFor(safeOpenBillCount),
        physicalStatus = physicalStatus,
        openBillCount = safeOpenBillCount,
    )
}

private fun TableStatus.toDisplayStatusKind(): TableDisplayStatusKind {
    return when (this) {
        TableStatus.AVAILABLE -> TableDisplayStatusKind.AVAILABLE
        TableStatus.OCCUPIED -> TableDisplayStatusKind.OCCUPIED
        TableStatus.DIRTY -> TableDisplayStatusKind.DIRTY
        TableStatus.RESERVED -> TableDisplayStatusKind.RESERVED
    }
}

private fun TableDisplayStatusKind.labelFor(openBillCount: Int): String {
    return when (this) {
        TableDisplayStatusKind.AVAILABLE -> "Free"
        TableDisplayStatusKind.OPEN_BILL -> if (openBillCount == 1) "Open bill" else "Open bills"
        TableDisplayStatusKind.OCCUPIED -> "Occupied"
        TableDisplayStatusKind.DIRTY -> "Needs cleaning"
        TableDisplayStatusKind.RESERVED -> "Reserved"
        TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL -> {
            if (openBillCount == 1) "Reserved + bill" else "Reserved + bills"
        }
    }
}

private fun TableStatus.physicalStatusLabel(): String {
    return when (this) {
        TableStatus.AVAILABLE -> "Free"
        TableStatus.OCCUPIED -> "Occupied"
        TableStatus.DIRTY -> "Needs cleaning"
        TableStatus.RESERVED -> "Reserved"
    }
}
