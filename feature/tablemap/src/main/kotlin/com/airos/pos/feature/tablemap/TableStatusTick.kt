package com.airos.pos.feature.tablemap

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val CHECK_TICK_LABEL = "CHECK"
private const val SERVICE_TICK_LABEL = "SERVE"
private const val ATTENTION_TICK_INTERVAL_MS = 2_400L

internal val TableCheckAttentionColor = Color(0xFFFF5353)
internal val TableServiceAttentionColor = Color(0xFFFFB23A)
internal val TableServiceAttentionTextColor = TableServiceAttentionColor

internal data class StatusTickPresentation(
    val label: String,
    val attentionVisible: Boolean,
    val chipTint: Color? = null,
    val textTint: Color? = null,
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

    val attentionVisible = displayStatus.hasAnyAttention && showAttention
    val primaryLabel = displayStatus.primaryTickLabel()
    val attentionLabel = when {
        displayStatus.hasCheckAttention -> CHECK_TICK_LABEL
        displayStatus.hasServiceAttention -> SERVICE_TICK_LABEL
        else -> primaryLabel
    }
    val showingServiceAttention = displayStatus.hasServiceAttention && attentionVisible
    val showingCheckAttention = displayStatus.hasCheckAttention && attentionVisible

    return StatusTickPresentation(
        label = if (attentionVisible) attentionLabel else primaryLabel,
        attentionVisible = attentionVisible,
        chipTint = when {
            showingCheckAttention -> TableCheckAttentionColor
            showingServiceAttention -> TableServiceAttentionColor
            else -> null
        },
        textTint = when {
            showingCheckAttention -> TableCheckAttentionColor
            showingServiceAttention -> TableServiceAttentionTextColor
            else -> null
        },
    )
}

private fun TableDisplayStatus.primaryTickLabel(): String {
    return when (kind) {
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

internal fun TableDisplayStatus.attentionVisualTint(): Color? {
    return when {
        hasCheckAttention -> TableCheckAttentionColor
        hasServiceAttention -> TableServiceAttentionColor
        else -> null
    }
}
