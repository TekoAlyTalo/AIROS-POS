package com.airos.pos.feature.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
private val PaymentDialogUtilityBlue = Color(0xFF6FD3FF)
private val PaymentDialogVoucherRed = Color(0xFFE06A6A)
private val PaymentDialogCoinGold = Color(0xFFE5C56B)
private val PaymentDialogCashGreen = Color(0xFF77D6B7)
private val PaymentDialogCardBlue = Color(0xFF6FD3FF)
private val PaymentDialogBrandLight = Color(0xFFF7F2E8)
private val PaymentDialogBrandLightAlt = Color(0xFFF2EEE8)

private enum class VoucherProviderUi(
    val label: String,
    val logoRes: Int,
    val containerColor: Color,
    val selectedContainerColor: Color,
    val borderColor: Color,
) {
    SMARTUM(
        label = "Smartum",
        logoRes = R.drawable.pay_logo_smartum,
        containerColor = PaymentDialogBrandLight,
        selectedContainerColor = Color(0xFFF5E2B8),
        borderColor = Color(0xFFC68D2D),
    ),
    EDENRED(
        label = "Edenred",
        logoRes = R.drawable.pay_logo_edenred,
        containerColor = PaymentDialogBrandLightAlt,
        selectedContainerColor = Color(0xFFF8DDD5),
        borderColor = Color(0xFFE96B46),
    ),
    EPASSI(
        label = "ePassi",
        logoRes = R.drawable.pay_logo_epassi,
        containerColor = PaymentDialogBrandLightAlt,
        selectedContainerColor = Color(0xFFDDEFE7),
        borderColor = Color(0xFF25A370),
    ),
}

enum class MenuPaymentMode(
    val label: String,
) {
    CASH("Käteinen"),
    CARD("Kortti"),
    VOUCHER("Etuseteli"),
    SPLIT_PAYMENT("Jaa maksu"),
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
    paymentContextLabel: String = "Baari",
    onDismiss: () -> Unit,
    onConfirm: (MenuPaymentDialogResult) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(MenuPaymentMode.CARD) }
    var activeInputTarget by rememberSaveable { mutableStateOf(PaymentInputTarget.SPLIT_CARD.name) }
    var cashInput by rememberSaveable { mutableStateOf("") }
    var voucherInput by rememberSaveable { mutableStateOf("") }
    var splitCashInput by rememberSaveable { mutableStateOf("") }
    var splitCardInput by rememberSaveable { mutableStateOf("") }
    var splitVoucherInput by rememberSaveable { mutableStateOf("") }
    var voucherBarcodeInput by rememberSaveable { mutableStateOf("") }
    var discountModeName by rememberSaveable { mutableStateOf(BillDiscountMode.NONE.name) }
    var discountInput by rememberSaveable { mutableStateOf("") }
    var shouldPrintReceipt by rememberSaveable { mutableStateOf(false) }

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
    var voucherProviderName by rememberSaveable { mutableStateOf(VoucherProviderUi.SMARTUM.name) }
    val voucherProvider = remember(voucherProviderName) { VoucherProviderUi.valueOf(voucherProviderName) }


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
                        text = "Maksuvaihtoehdot",
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
                            MenuPaymentMode.CASH -> "Käteismaksu"
                            MenuPaymentMode.CARD -> "Korttimaksu"
                            MenuPaymentMode.VOUCHER -> "${voucherProvider.label}-maksu"
                            MenuPaymentMode.SPLIT_PAYMENT -> "Jaettu maksu"
                        },
                        message = when (mode) {
                            MenuPaymentMode.CASH -> "Syötä vastaanotettu käteinen oikealla. Vaihtoraha lasketaan automaattisesti."
                            MenuPaymentMode.CARD -> "Korttimaksu käyttää alennettua loppusummaa automaattisesti. Kassalaatikkoa ei avata, ellei mukana ole käteistä."
                            MenuPaymentMode.VOUCHER -> "Valitse ${voucherProvider.label} yllä olevista painikkeista, syötä summa oikealla ja jätä koodikenttä valmiiksi myöhempää skannerikytkentää varten."
                            MenuPaymentMode.SPLIT_PAYMENT -> "Kokoa maksu oikealla käteisestä, kortista ja etusetelistä, kunnes koko summa on katettu."
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
                        PaymentUtilityButton(
                            label = "Alennus %",
                            selected = discountMode == BillDiscountMode.PERCENT,
                            modifier = Modifier.weight(1f),
                            icon = { DiscountPercentIcon(selected = discountMode == BillDiscountMode.PERCENT) },
                            onClick = {
                                if (discountMode == BillDiscountMode.PERCENT) {
                                    discountModeName = BillDiscountMode.NONE.name
                                    discountInput = ""
                                } else {
                                    discountModeName = BillDiscountMode.PERCENT.name
                                    activeInputTarget = PaymentInputTarget.DISCOUNT_PERCENT.name
                                }
                            },
                        )
                        PaymentUtilityButton(
                            label = "Alennus €",
                            selected = discountMode == BillDiscountMode.AMOUNT,
                            modifier = Modifier.weight(1f),
                            icon = { DiscountCoinsIcon(selected = discountMode == BillDiscountMode.AMOUNT) },
                            onClick = {
                                if (discountMode == BillDiscountMode.AMOUNT) {
                                    discountModeName = BillDiscountMode.NONE.name
                                    discountInput = ""
                                } else {
                                    discountModeName = BillDiscountMode.AMOUNT.name
                                    activeInputTarget = PaymentInputTarget.DISCOUNT_AMOUNT.name
                                }
                            },
                        )
                        PaymentUtilityButton(
                            label = "Etuseteli",
                            selected = mode == MenuPaymentMode.VOUCHER,
                            modifier = Modifier.weight(1f),
                            icon = { VoucherTicketIcon(selected = mode == MenuPaymentMode.VOUCHER) },
                            onClick = {
                                mode = MenuPaymentMode.VOUCHER
                                activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name
                            },
                        )
                        PaymentUtilityButton(
                            label = "Jaa maksu",
                            selected = mode == MenuPaymentMode.SPLIT_PAYMENT,
                            modifier = Modifier.weight(1f),
                            icon = { SplitPaymentIcon(selected = mode == MenuPaymentMode.SPLIT_PAYMENT) },
                            onClick = {
                                mode = MenuPaymentMode.SPLIT_PAYMENT
                                activeInputTarget = PaymentInputTarget.SPLIT_CASH.name
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaymentModeButton(
                            label = MenuPaymentMode.CASH.label,
                            selected = mode == MenuPaymentMode.CASH,
                            modifier = Modifier.weight(1f),
                            icon = { CashTenderIcon() },
                            onClick = {
                                mode = MenuPaymentMode.CASH
                                activeInputTarget = PaymentInputTarget.CASH_RECEIVED.name
                            },
                        )
                        PaymentModeButton(
                            label = MenuPaymentMode.CARD.label,
                            selected = mode == MenuPaymentMode.CARD,
                            modifier = Modifier.weight(1f),
                            icon = { CardTenderIcon() },
                            onClick = {
                                mode = MenuPaymentMode.CARD
                                activeInputTarget = PaymentInputTarget.SPLIT_CARD.name
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VoucherProviderUi.values().forEach { provider ->
                            PaymentBrandButton(
                                label = provider.label,
                                logoRes = provider.logoRes,
                                selected = mode == MenuPaymentMode.VOUCHER && voucherProvider == provider,
                                modifier = Modifier.weight(1f),
                                containerColor = provider.containerColor,
                                selectedContainerColor = provider.selectedContainerColor,
                                borderColor = provider.borderColor,
                                onClick = {
                                    voucherProviderName = provider.name
                                    mode = MenuPaymentMode.VOUCHER
                                    activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name
                                },
                            )
                        }
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
                                    text = "Maksun syöttö",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PaymentDialogTextPrimary,
                                )

                                when (mode) {
                                    MenuPaymentMode.CASH -> {
                                        PaymentInputSelector(
                                            label = "Saatu käteinen",
                                            value = cashInput,
                                            hint = "Käytä pikapainikkeita tai näppäimistöä",
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
                                            title = "Korttimaksu",
                                            message = "Korttimaksu käyttää alennettua loppusummaa automaattisesti. Viimeistele maksu, kun maksupääte on valmis.",
                                        )
                                    }

                                    MenuPaymentMode.VOUCHER -> {
                                        PaymentInputSelector(
                                            label = "${voucherProvider.label}-summa",
                                            value = voucherInput,
                                            hint = "${voucherProvider.label}-summa vaaditaan",
                                            selected = activeTarget == PaymentInputTarget.VOUCHER_AMOUNT,
                                            onClick = { activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name },
                                        )
                                        OutlinedTextField(
                                            value = voucherBarcodeInput,
                                            onValueChange = { voucherBarcodeInput = it },
                                            label = { Text("${voucherProvider.label}-viivakoodi / koodi") },
                                            supportingText = { Text("Manuaalinen syöttö nyt. Skanneri voi täyttää tämän myöhemmin.") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    }

                                    MenuPaymentMode.SPLIT_PAYMENT -> {
                                        PaymentInputSelector(
                                            label = "Käteisosuus",
                                            value = splitCashInput,
                                            hint = "Avaa laatikon, jos käteistä käytetään",
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
                                            label = "Korttiosuus",
                                            value = splitCardInput,
                                            hint = "Valinnainen",
                                            selected = activeTarget == PaymentInputTarget.SPLIT_CARD,
                                            onClick = { activeInputTarget = PaymentInputTarget.SPLIT_CARD.name },
                                        )
                                        PaymentInputSelector(
                                            label = "Etuseteliosuus",
                                            value = splitVoucherInput,
                                            hint = "Valinnainen",
                                            selected = activeTarget == PaymentInputTarget.SPLIT_VOUCHER,
                                            onClick = { activeInputTarget = PaymentInputTarget.SPLIT_VOUCHER.name },
                                        )
                                        OutlinedTextField(
                                            value = voucherBarcodeInput,
                                            onValueChange = { voucherBarcodeInput = it },
                                            label = { Text("Etusetelin viivakoodi / koodi") },
                                            supportingText = { Text("Käytetään, kun jaetussa maksussa on etuseteli.") },
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
                                    text = "Näppäimistö",
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
                            label = "Sulje",
                            onClick = onDismiss,
                            primary = false,
                            modifier = Modifier.weight(0.42f),
                        )
                        PaymentDialogButton(
                            label = when (mode) {
                                MenuPaymentMode.CASH -> "Viimeistele käteismaksu"
                                MenuPaymentMode.CARD -> "Viimeistele korttimaksu"
                                MenuPaymentMode.VOUCHER -> "Viimeistele ${voucherProvider.label}-maksu"
                                MenuPaymentMode.SPLIT_PAYMENT -> "Viimeistele jaettu maksu"
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
            PaymentSummaryRow(label = "Lasku", value = paymentContextLabel, emphasized = true)
            PaymentSummaryRow(label = "Välisumma", value = CentsFormatter.format(subtotalCents))
            if (billDiscountAmountCents > 0) {
                val discountLabel = when {
                    discountMode == BillDiscountMode.PERCENT && discountInput.isNotBlank() ->
                        "Alennus (${discountInput.trimStart('0').ifBlank { "0" }}%)"
                    else -> "Alennus"
                }
                PaymentSummaryRow(
                    label = discountLabel,
                    value = "-${CentsFormatter.format(billDiscountAmountCents)}",
                )
            }
            PaymentSummaryRow(label = "Maksettavaa", value = CentsFormatter.format(finalTotalCents), emphasized = true)
            when (mode) {
                MenuPaymentMode.CASH -> {
                    val changeCents = ((cashTenderedCents ?: 0) - finalTotalCents).coerceAtLeast(0)
                    val remainingCents = (finalTotalCents - (cashTenderedCents ?: 0)).coerceAtLeast(0)
                    PaymentSummaryRow(label = "Saatu käteinen", value = cashTenderedCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Puuttuu", value = CentsFormatter.format(remainingCents))
                    PaymentSummaryRow(label = "Vaihtoraha", value = CentsFormatter.format(changeCents), emphasized = true)
                }

                MenuPaymentMode.CARD -> {
                    PaymentSummaryRow(label = "Korttimaksu", value = CentsFormatter.format(finalTotalCents), emphasized = true)
                }

                MenuPaymentMode.VOUCHER -> {
                    val voucherCents = voucherAmountCents ?: 0
                    val remainingCents = (finalTotalCents - voucherCents).coerceAtLeast(0)
                    PaymentSummaryRow(label = "Etuseteli", value = voucherAmountCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Puuttuu", value = CentsFormatter.format(remainingCents), emphasized = remainingCents == 0)
                }

                MenuPaymentMode.SPLIT_PAYMENT -> {
                    PaymentSummaryRow(label = "Käteinen", value = CentsFormatter.format(splitCashCents))
                    PaymentSummaryRow(label = "Kortti", value = CentsFormatter.format(splitCardCents))
                    PaymentSummaryRow(label = "Etuseteli", value = CentsFormatter.format(splitVoucherCents))
                    PaymentSummaryRow(label = "Maksettu yhteensä", value = CentsFormatter.format(splitPaidCents))
                    PaymentSummaryRow(
                        label = if (splitPaidCents >= finalTotalCents) "Vaihtoraha / ylimaksu" else "Puuttuu",
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
                discountMode == BillDiscountMode.PERCENT && discountInput.isNotBlank() -> "Alennus % (${discountInput.trimStart('0').ifBlank { "0" }}%)"
                else -> "Alennus %"
            },
            onClick = onSelectPercent,
            primary = discountMode == BillDiscountMode.PERCENT,
            modifier = Modifier.weight(1f),
        )
        PaymentDialogButton(
            label = when {
                discountMode == BillDiscountMode.AMOUNT && discountInput.isNotBlank() -> "Alennus € (${discountInput.replace('.', ',')})"
                else -> "Alennus €"
            },
            onClick = onSelectAmount,
            primary = discountMode == BillDiscountMode.AMOUNT,
            modifier = Modifier.weight(1f),
        )
        PaymentDialogButton(
            label = if (billDiscountAmountCents > 0) "Tyhjennä" else "Ei alennusta",
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
                label = "Tasan",
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
                    text = "Kuitin tulostus",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PaymentDialogTextPrimary,
                )
                Text(
                    text = if (shouldPrintReceipt) "Kuitti tulostetaan maksun jälkeen." else "Älä tulosta kuittia tästä maksusta.",
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
private fun PaymentUtilityButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 58.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) PaymentDialogPanelAltColor else PaymentDialogShellColor,
        border = BorderStroke(1.dp, if (selected) PaymentDialogUtilityBlue.copy(alpha = 0.85f) else PaymentDialogBorderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PaymentDialogTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PaymentModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else PaymentDialogShellColor,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else PaymentDialogBorderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color(0xFF092016) else PaymentDialogTextPrimary,
            )
        }
    }
}

@Composable
private fun PaymentBrandButton(
    label: String,
    logoRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color,
    selectedContainerColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 62.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) selectedContainerColor else containerColor,
        border = BorderStroke(1.dp, borderColor.copy(alpha = if (selected) 0.95f else 0.7f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun DiscountPercentIcon(
    selected: Boolean,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) PaymentDialogUtilityBlue.copy(alpha = 0.18f) else Color(0xFF102131),
        border = BorderStroke(1.dp, if (selected) PaymentDialogUtilityBlue else PaymentDialogBorderColor),
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "%",
                color = PaymentDialogUtilityBlue,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DiscountCoinsIcon(
    selected: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == 0) 14.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (selected) PaymentDialogCoinGold else PaymentDialogCoinGold.copy(alpha = 0.92f)),
            )
        }
    }
}

@Composable
private fun VoucherTicketIcon(
    selected: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) PaymentDialogVoucherRed.copy(alpha = 0.24f) else Color(0xFF152436),
        border = BorderStroke(1.dp, if (selected) PaymentDialogVoucherRed else PaymentDialogBorderColor),
    ) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 20.dp)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PaymentDialogVoucherRed),
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(12.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun SplitPaymentIcon(
    selected: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) PaymentDialogUtilityBlue.copy(alpha = 0.18f) else Color(0xFF102131),
        border = BorderStroke(1.dp, if (selected) PaymentDialogUtilityBlue else PaymentDialogBorderColor),
    ) {
        Box(
            modifier = Modifier.size(width = 28.dp, height = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⇄",
                color = PaymentDialogUtilityBlue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CashTenderIcon() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0E2A20),
        border = BorderStroke(1.dp, PaymentDialogCashGreen.copy(alpha = 0.75f)),
    ) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 20.dp)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PaymentDialogCashGreen),
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0xFF0E2A20)),
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(2.dp)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF0E2A20)),
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(2.dp)
                    .align(Alignment.CenterEnd)
                    .background(Color(0xFF0E2A20)),
            )
        }
    }
}

@Composable
private fun CardTenderIcon() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF102131),
        border = BorderStroke(1.dp, PaymentDialogCardBlue.copy(alpha = 0.75f)),
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 18.dp)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PaymentDialogCardBlue),
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0xFF102131)),
            )
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(2.dp)
                    .align(Alignment.BottomEnd)
                    .background(Color(0xFF102131)),
            )
        }
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
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
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
    CASH_RECEIVED("Saatu käteinen", true, ::formatPaymentDisplayValue),
    VOUCHER_AMOUNT("Etusetelin summa", true, ::formatPaymentDisplayValue),
    SPLIT_CASH("Käteisosuus", true, ::formatPaymentDisplayValue),
    SPLIT_CARD("Korttiosuus", true, ::formatPaymentDisplayValue),
    SPLIT_VOUCHER("Etuseteliosuus", true, ::formatPaymentDisplayValue),
    DISCOUNT_PERCENT("Alennus %", false, ::formatPercentDisplayValue),
    DISCOUNT_AMOUNT("Alennus €", true, ::formatPaymentDisplayValue),
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
