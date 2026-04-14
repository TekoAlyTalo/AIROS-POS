package com.airos.pos.feature.tablemap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val CHECK_TICK_LABEL = "CHECK"
private const val SERVICE_TICK_LABEL = "SERVE"
private const val ATTENTION_TICK_INTERVAL_MS = 2_200L

internal data class StatusTickPresentation(
    val label: String,
    val attentionVisible: Boolean,
)

@Composable
internal fun rememberStatusTickPresentation(displayStatus: TableDisplayStatus): StatusTickPresentation {
    var showAttention by remember(displayStatus.kind, displayStatus.attentionFlag, displayStatus.openBillCount) {
        mutableStateOf(false)
    }

    LaunchedEffect(displayStatus.kind, displayStatus.attentionFlag, displayStatus.openBillCount) {
        showAttention = false
        while (displayStatus.hasAnyAttention) {
            delay(ATTENTION_TICK_INTERVAL_MS)
            showAttention = !showAttention
        }
    }

    val primaryLabel = displayStatus.primaryTickLabel()
    val attentionLabel = when {
        displayStatus.hasCheckAttention -> CHECK_TICK_LABEL
        displayStatus.hasServiceAttention -> SERVICE_TICK_LABEL
        else -> primaryLabel
    }

    return StatusTickPresentation(
        label = if (displayStatus.hasAnyAttention && showAttention) attentionLabel else primaryLabel,
        attentionVisible = displayStatus.hasAnyAttention && showAttention,
    )
}

private fun TableDisplayStatus.primaryTickLabel(): String {
    return when (kind) {
        TableDisplayStatusKind.AVAILABLE -> "F"
        TableDisplayStatusKind.OPEN_BILL,
        TableDisplayStatusKind.OCCUPIED,
        -> "O"
        TableDisplayStatusKind.DIRTY -> "N"
        TableDisplayStatusKind.RESERVED,
        TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL,
        -> "R"
    }
}
