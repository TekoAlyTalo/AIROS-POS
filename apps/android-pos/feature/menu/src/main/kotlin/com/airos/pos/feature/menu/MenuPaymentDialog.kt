package com.airos.pos.feature.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.airos.pos.core.common.CentsFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

private val PaymentDialogShellColor = Color(0xFF0D151E)
private val PaymentDialogPanelColor = Color(0xFF131E29)
private val PaymentDialogPanelAltColor = Color(0xFF122031)
private val PaymentDialogBorderColor = Color(0x14FFFFFF)
private val PaymentDialogTextPrimary = Color(0xFFFBFEFF)
private val PaymentDialogTextSecondary = Color(0xFFE8F0F6)
private val PaymentDialogTextMuted = Color(0xFF9CB2C4)
private val PaymentDialogAccentTextColor = Color(0xFF85F5E0)

enum class MenuPaymentMode(
    val label: String,
) {
    CASH("Cash"),
    CARD("Card"),
    VOUCHER("Voucher"),
    SPLIT_PAYMENT("Split payment"),
}

data class MenuPaymentDialogResult(
    val mode: MenuPaymentMode,
    val cashTenderedCents: Int?,
    val voucherAmountCents: Int?,
    val cardAmountCents: Int?,
    val voucherBarcodeValue: String?,
    val billDiscountPercent: Int?,
    val billDiscountAmountCents: Int,
    val finalTotalCents: Int,
    val shouldPrintReceipt: Boolean,
)

@Composable
fun MenuPaymentDialog(
    subtotalCents: Int,
    paymentContextLabel: String = "Bar",
    onDismiss: () -> Unit,
    onConfirm: (MenuPaymentDialogResult) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(MenuPaymentMode.CASH) }
    var activeInputTarget by rememberSaveable { mutableStateOf(PaymentInputTarget.CASH_RECEIVED.name) }
    var cashInput by rememberSaveable { mutableStateOf("") }
    var voucherInput by rememberSaveable { mutableStateOf("") }
    var splitCashInput by rememberSaveable { mutableStateOf("") }
    var splitCardInput by rememberSaveable { mutableStateOf("") }
    var splitVoucherInput by rememberSaveable { mutableStateOf("") }
    var voucherBarcodeInput by rememberSaveable { mutableStateOf("") }
    var discountModeName by rememberSaveable { mutableStateOf(BillDiscountMode.NONE.name) }
    var discountInput by rememberSaveable { mutableStateOf("") }
    var shouldPrintReceipt by rememberSaveable { mutableStateOf(true) }

    val discountMode = remember(discountModeName) { BillDiscountMode.valueOf(discountModeName) }

    val billDiscountAmountCents = remember(subtotalCents, discountMode, discountInput) {
        when (discountMode) {
            BillDiscountMode.NONE -> 0
            BillDiscountMode.PERCENT -> {
                val percent = parsePercentInput(discountInput) ?: 0
                ((subtotalCents * percent.coerceIn(0, 100)) / 100).coerceIn(0, subtotalCents)
            }
            BillDiscountMode.AMOUNT -> {
                (parseEuroInputToCents(discountInput) ?: 0).coerceIn(0, subtotalCents)
            }
        }
    }
    val finalTotalCents = (subtotalCents - billDiscountAmountCents).coerceAtLeast(0)

    val cashTenderedCents = remember(cashInput) { parseEuroInputToCents(cashInput) }
    val voucherAmountCents = remember(voucherInput) { parseEuroInputToCents(voucherInput) }
    val splitCashCents = remember(splitCashInput) { parseEuroInputToCents(splitCashInput) ?: 0 }
    val splitCardCents = remember(splitCardInput) { parseEuroInputToCents(splitCardInput) ?: 0 }
    val splitVoucherCents = remember(splitVoucherInput) { parseEuroInputToCents(splitVoucherInput) ?: 0 }
    val splitPaidCents = splitCashCents + splitCardCents + splitVoucherCents

    val activeTarget = remember(activeInputTarget) { PaymentInputTarget.valueOf(activeInputTarget) }

    val confirmEnabled = when (mode) {
        MenuPaymentMode.CASH -> finalTotalCents > 0 && (cashTenderedCents ?: 0) >= finalTotalCents
        MenuPaymentMode.CARD -> finalTotalCents > 0
        MenuPaymentMode.VOUCHER -> finalTotalCents > 0 && (voucherAmountCents ?: 0) >= finalTotalCents
        MenuPaymentMode.SPLIT_PAYMENT -> finalTotalCents > 0 && splitPaidCents >= finalTotalCents && splitPaidCents > 0
    }

    val result = MenuPaymentDialogResult(
        mode = mode,
        cashTenderedCents = when (mode) {
            MenuPaymentMode.CASH -> cashTenderedCents
            MenuPaymentMode.SPLIT_PAYMENT -> splitCashCents.takeIf { it > 0 }
            else -> null
        },
        voucherAmountCents = when (mode) {
            MenuPaymentMode.VOUCHER -> voucherAmountCents
            MenuPaymentMode.SPLIT_PAYMENT -> splitVoucherCents.takeIf { it > 0 }
            else -> null
        },
        cardAmountCents = when (mode) {
            MenuPaymentMode.CARD -> finalTotalCents
            MenuPaymentMode.SPLIT_PAYMENT -> splitCardCents.takeIf { it > 0 }
            else -> null
        },
        voucherBarcodeValue = voucherBarcodeInput.trim().ifBlank { null },
        billDiscountPercent = if (discountMode == BillDiscountMode.PERCENT) parsePercentInput(discountInput) else null,
        billDiscountAmountCents = billDiscountAmountCents,
        finalTotalCents = finalTotalCents,
        shouldPrintReceipt = shouldPrintReceipt,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PaymentDialogPanelColor,
            border = BorderStroke(1.dp, PaymentDialogBorderColor),
            contentColor = PaymentDialogTextPrimary,
        ) {
            Row(
                modifier = Modifier
                    .width(1100.dp)
                    .heightIn(max = 760.dp)
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(0.42f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Payment options",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = PaymentDialogTextPrimary,
                    )
                    PaymentSummaryCard(
                        paymentContextLabel = paymentContextLabel,
                        subtotalCents = subtotalCents,
                        finalTotalCents = finalTotalCents,
                        billDiscountAmountCents = billDiscountAmountCents,
                        discountMode = discountMode,
                        discountInput = discountInput,
                        mode = mode,
                        cashTenderedCents = cashTenderedCents,
                        voucherAmountCents = voucherAmountCents,
                        splitCashCents = splitCashCents,
                        splitCardCents = splitCardCents,
                        splitVoucherCents = splitVoucherCents,
                        splitPaidCents = splitPaidCents,
                    )

                    PaymentHintCard(
                        title = when (mode) {
                            MenuPaymentMode.CASH -> "Cash flow"
                            MenuPaymentMode.CARD -> "Card flow"
                            MenuPaymentMode.VOUCHER -> "Voucher flow"
                            MenuPaymentMode.SPLIT_PAYMENT -> "Split payment flow"
                        },
                        message = when (mode) {
                            MenuPaymentMode.CASH -> "Enter received cash on the right. Change is calculated automatically."
                            MenuPaymentMode.CARD -> "Card uses the discounted total automatically. No drawer open unless cash is included."
                            MenuPaymentMode.VOUCHER -> "Enter voucher amount on the right. Barcode field stays ready for the later scanner hookup."
                            MenuPaymentMode.SPLIT_PAYMENT -> "Build the payment on the right with cash, card, and voucher parts until the total is covered."
                        },
                    )

                    ReceiptPrintOptionCard(
                        shouldPrintReceipt = shouldPrintReceipt,
                        onToggle = { shouldPrintReceipt = !shouldPrintReceipt },
                    )
                }

                Column(
                    modifier = Modifier.weight(0.58f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaymentDialogButton(
                            label = when {
                                discountMode == BillDiscountMode.PERCENT && discountInput.isNotBlank() -> "Discount % (${discountInput.trimStart('0').ifBlank { "0" }}%)"
                                else -> "Discount %"
                            },
                            onClick = {
                                if (discountMode == BillDiscountMode.PERCENT) {
                                    discountModeName = BillDiscountMode.NONE.name
                                    discountInput = ""
                                } else {
                                    discountModeName = BillDiscountMode.PERCENT.name
                                    activeInputTarget = PaymentInputTarget.DISCOUNT_PERCENT.name
                                }
                            },
                            primary = discountMode == BillDiscountMode.PERCENT,
                            modifier = Modifier.weight(1f),
                        )
                        PaymentDialogButton(
                            label = when {
                                discountMode == BillDiscountMode.AMOUNT && discountInput.isNotBlank() -> "Discount € (${discountInput.replace('.', ',')})"
                                else -> "Discount €"
                            },
                            onClick = {
                                if (discountMode == BillDiscountMode.AMOUNT) {
                                    discountModeName = BillDiscountMode.NONE.name
                                    discountInput = ""
                                } else {
                                    discountModeName = BillDiscountMode.AMOUNT.name
                                    activeInputTarget = PaymentInputTarget.DISCOUNT_AMOUNT.name
                                }
                            },
                            primary = discountMode == BillDiscountMode.AMOUNT,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaymentDialogButton(
                            label = MenuPaymentMode.VOUCHER.label,
                            onClick = {
                                mode = MenuPaymentMode.VOUCHER
                                activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name
                            },
                            primary = mode == MenuPaymentMode.VOUCHER,
                            modifier = Modifier.weight(1f),
                        )
                        PaymentDialogButton(
                            label = MenuPaymentMode.SPLIT_PAYMENT.label,
                            onClick = {
                                mode = MenuPaymentMode.SPLIT_PAYMENT
                                activeInputTarget = PaymentInputTarget.SPLIT_CASH.name
                            },
                            primary = mode == MenuPaymentMode.SPLIT_PAYMENT,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaymentDialogButton(
                            label = MenuPaymentMode.CASH.label,
                            onClick = {
                                mode = MenuPaymentMode.CASH
                                activeInputTarget = PaymentInputTarget.CASH_RECEIVED.name
                            },
                            primary = mode == MenuPaymentMode.CASH,
                            modifier = Modifier.weight(1f),
                        )
                        PaymentDialogButton(
                            label = MenuPaymentMode.CARD.label,
                            onClick = {
                                mode = MenuPaymentMode.CARD
                                activeInputTarget = PaymentInputTarget.DISCOUNT_AMOUNT.name
                            },
                            primary = mode == MenuPaymentMode.CARD,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = PaymentDialogShellColor,
                        border = BorderStroke(1.dp, PaymentDialogBorderColor),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Payment input",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PaymentDialogTextPrimary,
                                )

                                when (mode) {
                                    MenuPaymentMode.CASH -> {
                                        PaymentInputSelector(
                                            label = "Cash received",
                                            value = cashInput,
                                            hint = "Use quick buttons or keypad",
                                            selected = activeTarget == PaymentInputTarget.CASH_RECEIVED,
                                            onClick = { activeInputTarget = PaymentInputTarget.CASH_RECEIVED.name },
                                        )
                                        QuickCashRow(
                                            exactAmountCents = finalTotalCents,
                                            onSelectAmount = { amountCents ->
                                                activeInputTarget = PaymentInputTarget.CASH_RECEIVED.name
                                                cashInput = formatEditableMoneyInput(amountCents)
                                            },
                                        )
                                    }

                                    MenuPaymentMode.CARD -> {
                                        PaymentHintCard(
                                            title = "Card payment",
                                            message = "Card uses the discounted total automatically. Finish the payment when the terminal side is done.",
                                        )
                                    }

                                    MenuPaymentMode.VOUCHER -> {
                                        PaymentInputSelector(
                                            label = "Voucher amount",
                                            value = voucherInput,
                                            hint = "Voucher value is required",
                                            selected = activeTarget == PaymentInputTarget.VOUCHER_AMOUNT,
                                            onClick = { activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name },
                                        )
                                        OutlinedTextField(
                                            value = voucherBarcodeInput,
                                            onValueChange = { voucherBarcodeInput = it },
                                            label = { Text("Voucher barcode / code") },
                                            supportingText = { Text("Manual entry now. Scanner hookup can fill this later.") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    }

                                    MenuPaymentMode.SPLIT_PAYMENT -> {
                                        PaymentInputSelector(
                                            label = "Cash part",
                                            value = splitCashInput,
                                            hint = "Opens drawer if cash is used",
                                            selected = activeTarget == PaymentInputTarget.SPLIT_CASH,
                                            onClick = { activeInputTarget = PaymentInputTarget.SPLIT_CASH.name },
                                        )
                                        QuickCashRow(
                                            exactAmountCents = finalTotalCents,
                                            onSelectAmount = { amountCents ->
                                                activeInputTarget = PaymentInputTarget.SPLIT_CASH.name
                                                splitCashInput = formatEditableMoneyInput(amountCents)
                                            },
                                        )
                                        PaymentInputSelector(
                                            label = "Card part",
                                            value = splitCardInput,
                                            hint = "Optional",
                                            selected = activeTarget == PaymentInputTarget.SPLIT_CARD,
                                            onClick = { activeInputTarget = PaymentInputTarget.SPLIT_CARD.name },
                                        )
                                        PaymentInputSelector(
                                            label = "Voucher part",
                                            value = splitVoucherInput,
                                            hint = "Optional",
                                            selected = activeTarget == PaymentInputTarget.SPLIT_VOUCHER,
                                            onClick = { activeInputTarget = PaymentInputTarget.SPLIT_VOUCHER.name },
                                        )
                                        OutlinedTextField(
                                            value = voucherBarcodeInput,
                                            onValueChange = { voucherBarcodeInput = it },
                                            label = { Text("Voucher barcode / code") },
                                            supportingText = { Text("Used when split includes a voucher.") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.width(290.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Keypad",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PaymentDialogTextPrimary,
                                )
                                PaymentKeypad(
                                    target = activeTarget,
                                    value = currentValueForTarget(
                                        target = activeTarget,
                                        cashInput = cashInput,
                                        voucherInput = voucherInput,
                                        splitCashInput = splitCashInput,
                                        splitCardInput = splitCardInput,
                                        splitVoucherInput = splitVoucherInput,
                                        discountInput = discountInput,
                                    ),
                                    onDigit = { digit ->
                                        when (activeTarget) {
                                            PaymentInputTarget.CASH_RECEIVED -> cashInput = appendInputDigit(cashInput, digit, InputMode.MONEY)
                                            PaymentInputTarget.VOUCHER_AMOUNT -> voucherInput = appendInputDigit(voucherInput, digit, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_CASH -> splitCashInput = appendInputDigit(splitCashInput, digit, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_CARD -> splitCardInput = appendInputDigit(splitCardInput, digit, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_VOUCHER -> splitVoucherInput = appendInputDigit(splitVoucherInput, digit, InputMode.MONEY)
                                            PaymentInputTarget.DISCOUNT_PERCENT -> discountInput = appendInputDigit(discountInput, digit, InputMode.PERCENT)
                                            PaymentInputTarget.DISCOUNT_AMOUNT -> discountInput = appendInputDigit(discountInput, digit, InputMode.MONEY)
                                        }
                                    },
                                    onDecimal = {
                                        when (activeTarget) {
                                            PaymentInputTarget.CASH_RECEIVED -> cashInput = appendInputDecimal(cashInput, InputMode.MONEY)
                                            PaymentInputTarget.VOUCHER_AMOUNT -> voucherInput = appendInputDecimal(voucherInput, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_CASH -> splitCashInput = appendInputDecimal(splitCashInput, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_CARD -> splitCardInput = appendInputDecimal(splitCardInput, InputMode.MONEY)
                                            PaymentInputTarget.SPLIT_VOUCHER -> splitVoucherInput = appendInputDecimal(splitVoucherInput, InputMode.MONEY)
                                            PaymentInputTarget.DISCOUNT_PERCENT -> Unit
                                            PaymentInputTarget.DISCOUNT_AMOUNT -> discountInput = appendInputDecimal(discountInput, InputMode.MONEY)
                                        }
                                    },
                                    onBackspace = {
                                        when (activeTarget) {
                                            PaymentInputTarget.CASH_RECEIVED -> cashInput = cashInput.dropLast(1)
                                            PaymentInputTarget.VOUCHER_AMOUNT -> voucherInput = voucherInput.dropLast(1)
                                            PaymentInputTarget.SPLIT_CASH -> splitCashInput = splitCashInput.dropLast(1)
                                            PaymentInputTarget.SPLIT_CARD -> splitCardInput = splitCardInput.dropLast(1)
                                            PaymentInputTarget.SPLIT_VOUCHER -> splitVoucherInput = splitVoucherInput.dropLast(1)
                                            PaymentInputTarget.DISCOUNT_PERCENT -> discountInput = discountInput.dropLast(1)
                                            PaymentInputTarget.DISCOUNT_AMOUNT -> discountInput = discountInput.dropLast(1)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PaymentDialogButton(
                            label = "Close",
                            onClick = onDismiss,
                            primary = false,
                            modifier = Modifier.weight(0.42f),
                        )
                        PaymentDialogButton(
                            label = when (mode) {
                                MenuPaymentMode.CASH -> "Finish cash payment"
                                MenuPaymentMode.CARD -> "Finish card payment"
                                MenuPaymentMode.VOUCHER -> "Finish voucher payment"
                                MenuPaymentMode.SPLIT_PAYMENT -> "Finish split payment"
                            },
                            onClick = { onConfirm(result) },
                            modifier = Modifier.weight(0.58f),
                            enabled = confirmEnabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentSummaryCard(
    paymentContextLabel: String,
    subtotalCents: Int,
    finalTotalCents: Int,
    billDiscountAmountCents: Int,
    discountMode: BillDiscountMode,
    discountInput: String,
    mode: MenuPaymentMode,
    cashTenderedCents: Int?,
    voucherAmountCents: Int?,
    splitCashCents: Int,
    splitCardCents: Int,
    splitVoucherCents: Int,
    splitPaidCents: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaymentSummaryRow(label = "Bill", value = paymentContextLabel, emphasized = true)
            PaymentSummaryRow(label = "Subtotal", value = CentsFormatter.format(subtotalCents))
            if (billDiscountAmountCents > 0) {
                val discountLabel = when {
                    discountMode == BillDiscountMode.PERCENT && discountInput.isNotBlank() ->
                        "Discount (${discountInput.trimStart('0').ifBlank { "0" }}%)"
                    else -> "Discount"
                }
                PaymentSummaryRow(
                    label = discountLabel,
                    value = "-${CentsFormatter.format(billDiscountAmountCents)}",
                )
            }
            PaymentSummaryRow(label = "To pay", value = CentsFormatter.format(finalTotalCents), emphasized = true)
            when (mode) {
                MenuPaymentMode.CASH -> {
                    val changeCents = ((cashTenderedCents ?: 0) - finalTotalCents).coerceAtLeast(0)
                    val remainingCents = (finalTotalCents - (cashTenderedCents ?: 0)).coerceAtLeast(0)
                    PaymentSummaryRow(label = "Cash received", value = cashTenderedCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Remaining", value = CentsFormatter.format(remainingCents))
                    PaymentSummaryRow(label = "Change", value = CentsFormatter.format(changeCents), emphasized = true)
                }

                MenuPaymentMode.CARD -> {
                    PaymentSummaryRow(label = "Card amount", value = CentsFormatter.format(finalTotalCents), emphasized = true)
                }

                MenuPaymentMode.VOUCHER -> {
                    val voucherCents = voucherAmountCents ?: 0
                    val remainingCents = (finalTotalCents - voucherCents).coerceAtLeast(0)
                    PaymentSummaryRow(label = "Voucher amount", value = voucherAmountCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Remaining", value = CentsFormatter.format(remainingCents), emphasized = remainingCents == 0)
                }

                MenuPaymentMode.SPLIT_PAYMENT -> {
                    PaymentSummaryRow(label = "Cash", value = CentsFormatter.format(splitCashCents))
                    PaymentSummaryRow(label = "Card", value = CentsFormatter.format(splitCardCents))
                    PaymentSummaryRow(label = "Voucher", value = CentsFormatter.format(splitVoucherCents))
                    PaymentSummaryRow(label = "Paid together", value = CentsFormatter.format(splitPaidCents))
                    PaymentSummaryRow(
                        label = if (splitPaidCents >= finalTotalCents) "Change / overpay" else "Remaining",
                        value = CentsFormatter.format(abs(finalTotalCents - splitPaidCents)),
                        emphasized = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscountSelectorRow(
    discountMode: BillDiscountMode,
    discountInput: String,
    billDiscountAmountCents: Int,
    onSelectPercent: () -> Unit,
    onSelectAmount: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PaymentDialogButton(
            label = when {
                discountMode == BillDiscountMode.PERCENT && discountInput.isNotBlank() -> "Discount % (${discountInput.trimStart('0').ifBlank { "0" }}%)"
                else -> "Discount %"
            },
            onClick = onSelectPercent,
            primary = discountMode == BillDiscountMode.PERCENT,
            modifier = Modifier.weight(1f),
        )
        PaymentDialogButton(
            label = when {
                discountMode == BillDiscountMode.AMOUNT && discountInput.isNotBlank() -> "Discount € (${discountInput.replace('.', ',')})"
                else -> "Discount €"
            },
            onClick = onSelectAmount,
            primary = discountMode == BillDiscountMode.AMOUNT,
            modifier = Modifier.weight(1f),
        )
        PaymentDialogButton(
            label = if (billDiscountAmountCents > 0) "Clear" else "No disc.",
            onClick = onClear,
            primary = false,
            modifier = Modifier.weight(0.72f),
        )
    }
}

@Composable
private fun QuickCashRow(
    exactAmountCents: Int,
    onSelectAmount: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaymentDialogButton(
                label = "50 €",
                onClick = { onSelectAmount(5000) },
                primary = false,
                modifier = Modifier.weight(1f),
            )
            PaymentDialogButton(
                label = "20 €",
                onClick = { onSelectAmount(2000) },
                primary = false,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaymentDialogButton(
                label = "10 €",
                onClick = { onSelectAmount(1000) },
                primary = false,
                modifier = Modifier.weight(1f),
            )
            PaymentDialogButton(
                label = "Exact",
                onClick = { onSelectAmount(exactAmountCents) },
                primary = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PaymentInputSelector(
    label: String,
    value: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) PaymentDialogPanelAltColor else PaymentDialogShellColor,
        border = BorderStroke(
            1.dp,
            if (selected) PaymentDialogAccentTextColor.copy(alpha = 0.75f) else PaymentDialogBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) PaymentDialogAccentTextColor else PaymentDialogTextMuted,
            )
            Text(
                text = formatPaymentDisplayValue(value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = PaymentDialogTextMuted,
            )
        }
    }
}


@Composable
private fun ReceiptPrintOptionCard(
    shouldPrintReceipt: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Receipt printing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PaymentDialogTextPrimary,
                )
                Text(
                    text = if (shouldPrintReceipt) "Receipt will be printed after payment." else "Do not print a receipt for this payment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaymentDialogTextSecondary,
                )
            }
            Checkbox(
                checked = shouldPrintReceipt,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

@Composable
private fun PaymentHintCard(
    title: String,
    message: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PaymentDialogTextPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = PaymentDialogTextSecondary,
            )
        }
    }
}

@Composable
private fun PaymentKeypad(
    target: PaymentInputTarget,
    value: String,
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (target.supportsDecimal) "," else "", "0", "⌫"),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = target.title,
                style = MaterialTheme.typography.titleSmall,
                color = PaymentDialogTextMuted,
            )
            Text(
                text = target.formatValue(value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogAccentTextColor,
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { key ->
                        val enabled = when (key) {
                            "" -> false
                            "⌫" -> value.isNotEmpty()
                            "," -> target.supportsDecimal
                            else -> true
                        }
                        PaymentKeyButton(
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
}

@Composable
private fun PaymentKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) PaymentDialogPanelAltColor else PaymentDialogPanelAltColor.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (enabled) Color(0x18FFFFFF) else Color(0x08FFFFFF)),
        contentColor = if (enabled) PaymentDialogTextPrimary else PaymentDialogTextMuted.copy(alpha = 0.45f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
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
private fun PaymentSummaryRow(
    label: String,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = PaymentDialogTextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) PaymentDialogAccentTextColor else PaymentDialogTextPrimary,
        )
    }
}

@Composable
private fun PaymentDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = PaymentDialogTextPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                disabledContentColor = PaymentDialogTextPrimary.copy(alpha = 0.6f),
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = PaymentDialogShellColor,
                contentColor = PaymentDialogTextSecondary,
                disabledContainerColor = PaymentDialogShellColor.copy(alpha = 0.55f),
                disabledContentColor = PaymentDialogTextSecondary.copy(alpha = 0.55f),
            )
        },
        border = BorderStroke(1.dp, if (primary) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else PaymentDialogBorderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class BillDiscountMode {
    NONE,
    PERCENT,
    AMOUNT,
}

private enum class InputMode {
    MONEY,
    PERCENT,
}

private enum class PaymentInputTarget(
    val title: String,
    val supportsDecimal: Boolean,
    val formatter: (String) -> String,
) {
    CASH_RECEIVED("Cash received", true, ::formatPaymentDisplayValue),
    VOUCHER_AMOUNT("Voucher amount", true, ::formatPaymentDisplayValue),
    SPLIT_CASH("Cash part", true, ::formatPaymentDisplayValue),
    SPLIT_CARD("Card part", true, ::formatPaymentDisplayValue),
    SPLIT_VOUCHER("Voucher part", true, ::formatPaymentDisplayValue),
    DISCOUNT_PERCENT("Discount %", false, ::formatPercentDisplayValue),
    DISCOUNT_AMOUNT("Discount €", true, ::formatPaymentDisplayValue),
    ;

    fun formatValue(value: String): String = formatter(value)
}

private fun currentValueForTarget(
    target: PaymentInputTarget,
    cashInput: String,
    voucherInput: String,
    splitCashInput: String,
    splitCardInput: String,
    splitVoucherInput: String,
    discountInput: String,
): String {
    return when (target) {
        PaymentInputTarget.CASH_RECEIVED -> cashInput
        PaymentInputTarget.VOUCHER_AMOUNT -> voucherInput
        PaymentInputTarget.SPLIT_CASH -> splitCashInput
        PaymentInputTarget.SPLIT_CARD -> splitCardInput
        PaymentInputTarget.SPLIT_VOUCHER -> splitVoucherInput
        PaymentInputTarget.DISCOUNT_PERCENT,
        PaymentInputTarget.DISCOUNT_AMOUNT,
        -> discountInput
    }
}

private fun formatPaymentDisplayValue(raw: String): String {
    if (raw.isBlank()) return "0,00 €"
    return "${raw.replace('.', ',')} €"
}

private fun formatPercentDisplayValue(raw: String): String {
    if (raw.isBlank()) return "0 %"
    return "${raw.trimStart('0').ifBlank { "0" }} %"
}

private fun formatEditableMoneyInput(amountCents: Int): String {
    val euros = amountCents / 100
    val cents = amountCents % 100
    return if (cents == 0) euros.toString() else "%d,%02d".format(euros, cents)
}

private fun appendInputDigit(
    value: String,
    digit: String,
    mode: InputMode,
): String {
    val sanitized = when (mode) {
        InputMode.MONEY -> sanitizeMoneyInput(value)
        InputMode.PERCENT -> sanitizePercentInput(value)
    }
    return when (mode) {
        InputMode.MONEY -> {
            if (sanitized.contains(',')) {
                val decimals = sanitized.substringAfter(',')
                if (decimals.length >= 2) sanitized else sanitized + digit
            } else {
                if (sanitized == "0") digit else sanitized + digit
            }
        }
        InputMode.PERCENT -> {
            val merged = (sanitized + digit).trimStart('0').ifBlank { "0" }
            merged.take(3).toIntOrNull()?.coerceIn(0, 100)?.toString() ?: sanitized
        }
    }
}

private fun appendInputDecimal(
    value: String,
    mode: InputMode,
): String {
    return when (mode) {
        InputMode.MONEY -> {
            val sanitized = sanitizeMoneyInput(value)
            if (sanitized.contains(',')) sanitized else "${sanitized.ifBlank { "0" }},"
        }
        InputMode.PERCENT -> value
    }
}

private fun sanitizeMoneyInput(input: String): String {
    val filtered = buildString(input.length) {
        input.forEach { ch ->
            if (ch.isDigit() || ch == ',' || ch == '.') {
                append(ch)
            }
        }
    }.replace('.', ',')

    val commaIndex = filtered.indexOf(',')
    if (commaIndex < 0) return filtered
    val integerPart = filtered.substring(0, commaIndex)
    val decimalPart = filtered.substring(commaIndex + 1).take(2)
    return "$integerPart,$decimalPart"
}

private fun sanitizePercentInput(input: String): String = input.filter { it.isDigit() }.take(3)

private fun parsePercentInput(raw: String): Int? = raw.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull()?.coerceIn(0, 100)

private fun parseEuroInputToCents(raw: String): Int? {
    val normalized = raw.trim()
        .replace("€", "")
        .replace(",", ".")
    if (normalized.isBlank()) return null

    return runCatching {
        BigDecimal(normalized)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
            .coerceAtLeast(0)
    }.getOrNull()
}
