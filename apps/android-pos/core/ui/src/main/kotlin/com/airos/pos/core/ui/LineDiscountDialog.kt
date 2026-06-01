package com.airos.pos.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt

private val LineDiscountShellColor = Color(0xFF0D151E)
private val LineDiscountPanelColor = Color(0xFF131E29)
private val LineDiscountPanelAltColor = Color(0xFF182633)
private val LineDiscountPanelAccentColor = Color(0xFF153847)
private val LineDiscountBorderColor = Color(0x14FFFFFF)
private val LineDiscountTextPrimary = Color(0xFFFBFEFF)
private val LineDiscountTextSecondary = Color(0xFFE8F0F6)
private val LineDiscountTextMuted = Color(0xFFC0CCD6)
private val LineDiscountAccentTextColor = Color(0xFF85F5E0)

enum class LineDiscountMode(
    val dialogTitle: String,
    val inputLabel: String,
) {
    PERCENT(
        dialogTitle = "Alennus %",
        inputLabel = "Prosentti",
    ),
    AMOUNT(
        dialogTitle = "Alennus €",
        inputLabel = "Euromäärä",
    ),
}

data class LineDiscountEditorState(
    val itemId: String,
    val lineName: String,
    val unitPriceCents: Int,
    val lineTotalCents: Int,
    val mode: LineDiscountMode,
    val initialValue: String = "",
)

@Composable
fun LineDiscountDialog(
    editor: LineDiscountEditorState,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmDiscountCents: (Int) -> Unit,
) {
    var value by rememberSaveable(editor.itemId, editor.mode) { mutableStateOf(editor.initialValue) }
    val preview = remember(editor, value) { calculateLineDiscountPreview(editor, value) }
    val isConfirmEnabled = when (editor.mode) {
        LineDiscountMode.PERCENT -> parseLinePercentDiscount(value) != null
        LineDiscountMode.AMOUNT -> parseLineEuroDiscountToCents(value) != null
    } && preview.enteredDiscountCents > 0 && preview.enteredDiscountCents <= editor.lineTotalCents && !busy

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LineDiscountPanelColor,
            border = BorderStroke(1.dp, LineDiscountBorderColor),
            contentColor = LineDiscountTextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = editor.mode.dialogTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = LineDiscountTextPrimary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = LineDiscountShellColor,
                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = editor.lineName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = LineDiscountTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LineDiscountInfoRow(
                            label = "Yksikköhinta",
                            value = formatLineDiscountCents(editor.unitPriceCents),
                        )
                        LineDiscountInfoRow(
                            label = "Rivin summa",
                            value = formatLineDiscountCents(editor.lineTotalCents),
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = LineDiscountShellColor,
                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = editor.mode.inputLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = LineDiscountTextMuted,
                        )
                        Text(
                            text = formatLineDiscountDisplayValue(value, editor.mode),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = LineDiscountTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LineDiscountInfoRow(
                            label = "Alkuperäinen summa",
                            value = formatLineDiscountCents(preview.originalLineAmountCents),
                        )
                        LineDiscountInfoRow(
                            label = "Syötetty alennus",
                            value = formatLineDiscountCents(preview.enteredDiscountCents),
                        )
                        LineDiscountInfoRow(
                            label = "Alennettu yhteensä",
                            value = formatLineDiscountCents(preview.discountedTotalCents),
                            emphasized = true,
                        )
                    }
                }
                LineDiscountKeypad(
                    mode = editor.mode,
                    value = value,
                    onDigit = { digit -> value = appendLineDiscountDigit(value, digit, editor.mode) },
                    onDecimal = { value = appendLineDiscountDecimal(value, editor.mode) },
                    onBackspace = { value = value.dropLast(1) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LineDiscountActionButton(
                        label = "Peru",
                        onClick = onDismiss,
                        primary = false,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                    LineDiscountActionButton(
                        label = "OK",
                        onClick = { onConfirmDiscountCents(preview.enteredDiscountCents) },
                        modifier = Modifier.weight(1f),
                        enabled = isConfirmEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineDiscountInfoRow(
    label: String,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LineDiscountTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) LineDiscountAccentTextColor else LineDiscountTextSecondary,
        )
    }
}

@Composable
private fun LineDiscountKeypad(
    mode: LineDiscountMode,
    value: String,
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (mode == LineDiscountMode.AMOUNT) "," else "", "0", "⌫"),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val enabled = when (key) {
                        "" -> false
                        "⌫" -> value.isNotEmpty()
                        "," -> mode == LineDiscountMode.AMOUNT
                        else -> true
                    }
                    LineDiscountKeypadButton(
                        label = key.ifBlank { " " },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = {
                            when (key) {
                                "⌫" -> onBackspace()
                                "," -> onDecimal()
                                "" -> Unit
                                else -> onDigit(key)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LineDiscountKeypadButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) LineDiscountPanelAltColor else LineDiscountPanelAltColor.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (enabled) Color(0x18FFFFFF) else Color(0x08FFFFFF)),
        contentColor = if (enabled) LineDiscountTextPrimary else LineDiscountTextMuted.copy(alpha = 0.45f),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LineDiscountActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = LineDiscountPanelAccentColor,
                contentColor = LineDiscountTextPrimary,
                disabledContainerColor = LineDiscountPanelAccentColor.copy(alpha = 0.38f),
                disabledContentColor = LineDiscountTextMuted,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = LineDiscountPanelAltColor,
                contentColor = LineDiscountTextPrimary,
                disabledContainerColor = LineDiscountPanelAltColor.copy(alpha = 0.38f),
                disabledContentColor = LineDiscountTextMuted,
            )
        },
        border = BorderStroke(1.dp, Color(0x18FFFFFF)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class LineDiscountPreview(
    val originalLineAmountCents: Int,
    val enteredDiscountCents: Int,
    val discountedTotalCents: Int,
)

private fun calculateLineDiscountPreview(
    editor: LineDiscountEditorState,
    value: String,
): LineDiscountPreview {
    val enteredDiscountCents = when (editor.mode) {
        LineDiscountMode.PERCENT -> parseLinePercentDiscount(value)?.let { percent ->
            (editor.lineTotalCents * (percent / 100.0)).roundToInt()
        }
        LineDiscountMode.AMOUNT -> parseLineEuroDiscountToCents(value)
    }
        ?.coerceIn(0, editor.lineTotalCents)
        ?: 0

    return LineDiscountPreview(
        originalLineAmountCents = editor.lineTotalCents,
        enteredDiscountCents = enteredDiscountCents,
        discountedTotalCents = (editor.lineTotalCents - enteredDiscountCents).coerceAtLeast(0),
    )
}

private fun parseLinePercentDiscount(input: String): Int? {
    val percent = input.trim().toIntOrNull() ?: return null
    if (percent !in 0..100) return null
    return percent
}

private fun parseLineEuroDiscountToCents(input: String): Int? {
    val normalized = input.trim().replace(',', '.')
    if (normalized.isBlank()) return null
    if (normalized.count { it == '.' } > 1) return null
    val parts = normalized.split('.')
    val euros = parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: return null
    if (euros < 0) return null
    val cents = when (parts.size) {
        1 -> 0
        2 -> {
            val fraction = parts[1]
            if (fraction.isEmpty() || fraction.length > 2) return null
            fraction.padEnd(2, '0').toIntOrNull() ?: return null
        }
        else -> return null
    }
    val total = euros * 100L + cents
    if (total > Int.MAX_VALUE) return null
    return total.toInt()
}

private fun appendLineDiscountDigit(
    current: String,
    digit: String,
    mode: LineDiscountMode,
): String {
    val next = current + digit
    return when (mode) {
        LineDiscountMode.PERCENT -> next.take(3)
        LineDiscountMode.AMOUNT -> next.take(8)
    }
}

private fun appendLineDiscountDecimal(
    current: String,
    mode: LineDiscountMode,
): String {
    if (mode != LineDiscountMode.AMOUNT) return current
    if (current.any { it == ',' || it == '.' }) return current
    return if (current.isBlank()) "0," else "$current,"
}

private fun formatLineDiscountDisplayValue(
    value: String,
    mode: LineDiscountMode,
): String {
    return when (mode) {
        LineDiscountMode.PERCENT -> "${value.ifBlank { "0" }} %"
        LineDiscountMode.AMOUNT -> if (value.isBlank()) "0 €" else "$value €"
    }
}

private fun formatLineDiscountCents(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val absolute = kotlin.math.abs(cents)
    val euros = absolute / 100
    val remainder = absolute % 100
    return String.format(Locale("fi", "FI"), "%s%d,%02d €", sign, euros, remainder)
}
