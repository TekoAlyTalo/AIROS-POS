package com.airos.pos.feature.tablemap

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
private const val ATTENTION_TICK_INTERVAL_MS = 1_800L

internal data class StatusTickPresentation(
    val label: String,
    val attentionVisible: Boolean,
    val pulseAlpha: Float = 1f,
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
    val pulseTransition = rememberInfiniteTransition(label = "attention-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (showingServiceAttention) 1.10f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "attention-pulse-alpha",
    )

    return StatusTickPresentation(
        label = if (attentionVisible) attentionLabel else primaryLabel,
        attentionVisible = attentionVisible,
        pulseAlpha = if (showingServiceAttention || showingCheckAttention) pulseAlpha else 1f,
        chipTint = when {
            showingCheckAttention -> Color(0xFFFF5353)
            showingServiceAttention -> Color(0xFFFFB23A)
            else -> null
        },
        textTint = when {
            showingCheckAttention -> Color(0xFFFF3B3B)
            showingServiceAttention -> Color(0xFFFFD36A)
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
