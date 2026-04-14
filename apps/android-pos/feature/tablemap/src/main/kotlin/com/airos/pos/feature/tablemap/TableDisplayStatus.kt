package com.airos.pos.feature.tablemap

import com.airos.pos.core.model.TableAttentionFlag
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
    val attentionFlag: TableAttentionFlag = TableAttentionFlag.NONE,
) {
    val physicalLabel: String
        get() = physicalStatus.physicalStatusLabel()

    val differsFromPhysical: Boolean
        get() = label != physicalLabel

    val hasCheckAttention: Boolean
        get() = attentionFlag == TableAttentionFlag.CHECK_TABLE &&
            kind in setOf(
                TableDisplayStatusKind.OCCUPIED,
                TableDisplayStatusKind.OPEN_BILL,
                TableDisplayStatusKind.DIRTY,
            )

    val hasServiceAttention: Boolean
        get() = !hasCheckAttention &&
            physicalStatus == TableStatus.OCCUPIED &&
            openBillCount <= 0 &&
            kind == TableDisplayStatusKind.OCCUPIED

    val hasAnyAttention: Boolean
        get() = hasCheckAttention || hasServiceAttention
}

internal fun resolveTableDisplayStatus(
    physicalStatus: TableStatus,
    openBillCount: Int,
    attentionFlag: TableAttentionFlag = TableAttentionFlag.NONE,
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
        attentionFlag = attentionFlag,
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
        TableDisplayStatusKind.OPEN_BILL,
        TableDisplayStatusKind.OCCUPIED,
        -> "Occupied"
        TableDisplayStatusKind.DIRTY -> "Needs Cleaning"
        TableDisplayStatusKind.RESERVED,
        TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL,
        -> "Reserved"
    }
}

private fun TableStatus.physicalStatusLabel(): String {
    return when (this) {
        TableStatus.AVAILABLE -> "Free"
        TableStatus.OCCUPIED -> "Occupied"
        TableStatus.DIRTY -> "Needs Cleaning"
        TableStatus.RESERVED -> "Reserved"
    }
}
