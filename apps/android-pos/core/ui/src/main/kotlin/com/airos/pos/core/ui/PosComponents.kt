package com.airos.pos.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PosPanelColor = Color(0xFF131E29)
private val PosPanelAltColor = Color(0xFF182633)
private val PosTextPrimary = Color(0xFFFBFEFF)
private val PosTextMuted = Color(0xFFC0CCD6)

@Composable
fun PosPane(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = PosPanelColor,
            contentColor = PosTextPrimary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PosPanelColor)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PosTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PosTextMuted,
                    )
                }
                content()
            },
        )
    }
}

@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = PosPanelAltColor,
        contentColor = PosTextPrimary,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = PosTextMuted)
            Text(text = value, style = MaterialTheme.typography.titleMedium, color = PosTextPrimary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun NumericPinPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PinPadRow(
            keys = listOf("1", "2", "3"),
            onDigit = onDigit,
            onBackspace = onBackspace,
        )
        PinPadRow(
            keys = listOf("4", "5", "6"),
            onDigit = onDigit,
            onBackspace = onBackspace,
        )
        PinPadRow(
            keys = listOf("7", "8", "9"),
            onDigit = onDigit,
            onBackspace = onBackspace,
        )
        PinPadRow(
            keys = listOf("", "0", "⌫"),
            onDigit = onDigit,
            onBackspace = onBackspace,
        )
    }
}

@Composable
fun NumericMoneyPad(
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 64.dp,
    keyColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    keyContentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PinPadRow(
            keys = listOf("1", "2", "3"),
            onDigit = onDigit,
            onBackspace = onBackspace,
            keyHeight = keyHeight,
            keyColor = keyColor,
            keyContentColor = keyContentColor,
        )
        PinPadRow(
            keys = listOf("4", "5", "6"),
            onDigit = onDigit,
            onBackspace = onBackspace,
            keyHeight = keyHeight,
            keyColor = keyColor,
            keyContentColor = keyContentColor,
        )
        PinPadRow(
            keys = listOf("7", "8", "9"),
            onDigit = onDigit,
            onBackspace = onBackspace,
            keyHeight = keyHeight,
            keyColor = keyColor,
            keyContentColor = keyContentColor,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("," to onDecimal, "0" to { onDigit("0") }, "⌫" to onBackspace)
                .forEach { (key, action) ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(keyHeight)
                            .clickable(onClick = action),
                        shape = RoundedCornerShape(16.dp),
                        color = keyColor,
                        contentColor = keyContentColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun PinPadRow(
    keys: List<String>,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    keyHeight: Dp = 64.dp,
    keyColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    keyContentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { key ->
            if (key.isEmpty()) {
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(keyHeight),
                )
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(keyHeight)
                        .clickable {
                            if (key == "⌫") onBackspace() else onDigit(key)
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = keyColor,
                    contentColor = keyContentColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBanner(text: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = text, color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = PosTextMuted)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = PosTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}
