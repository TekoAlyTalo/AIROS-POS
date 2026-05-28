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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.BoxScope
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
private val PaymentDialogBrandDark = Color(0xFF111C27)
private val PaymentDialogBrandDarkAlt = Color(0xFF142231)

private enum class VoucherProviderUi(
    val label: String,
    val logoRes: Int?,
    val containerColor: Color,
    val selectedContainerColor: Color,
    val borderColor: Color,
) {
    SMARTUM(
        label = "Smartum",
        logoRes = R.drawable.pay_logo_smartum,
        containerColor = PaymentDialogBrandDark,
        selectedContainerColor = Color(0xFF1D2B38),
        borderColor = Color(0xFFC68D2D),
    ),
    EDENRED(
        label = "Edenred",
        logoRes = R.drawable.pay_logo_edenred,
        containerColor = PaymentDialogBrandDarkAlt,
        selectedContainerColor = Color(0xFF1F2635),
        borderColor = Color(0xFFE96B46),
    ),
    EPASSI(
        label = "ePassi",
        logoRes = R.drawable.pay_logo_epassi,
        containerColor = PaymentDialogBrandDarkAlt,
        selectedContainerColor = Color(0xFF162A28),
        borderColor = Color(0xFF25A370),
    ),
    WOLT(
        label = "Wolt",
        logoRes = R.drawable.pay_logo_wolt,
        containerColor = PaymentDialogBrandDarkAlt,
        selectedContainerColor = Color(0xFF103246),
        borderColor = Color(0xFF2FA9DF),
    ),
}

enum class MenuPaymentMode(
    val label: String,
) {
    CASH("Käteinen"),
    CARD("Kortti"),
    VOUCHER("Etuseteli"),
    SPLIT_PAYMENT("Käteinen + kortti"),
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
    val activeTarget = remember(activeInputTarget) { PaymentInputTarget.valueOf(activeInputTarget) }
    val splitRawCashCents = remember(splitCashInput) { parseEuroInputToCents(splitCashInput) ?: 0 }
    val splitRawCardCents = remember(splitCardInput) { parseEuroInputToCents(splitCardInput) ?: 0 }
    val splitManualTarget = when (activeTarget) {
        PaymentInputTarget.SPLIT_CARD -> PaymentInputTarget.SPLIT_CARD
        else -> PaymentInputTarget.SPLIT_CASH
    }
    val splitManualCents = when (splitManualTarget) {
        PaymentInputTarget.SPLIT_CARD -> splitRawCardCents
        else -> splitRawCashCents
    }
    val splitAutoCents = (finalTotalCents - splitManualCents).coerceAtLeast(0)
    val splitOverCents = (splitManualCents - finalTotalCents).coerceAtLeast(0)
    val splitCashCents = if (splitManualTarget == PaymentInputTarget.SPLIT_CASH) splitManualCents else splitAutoCents
    val splitCardCents = if (splitManualTarget == PaymentInputTarget.SPLIT_CARD) splitManualCents else splitAutoCents
    val splitVoucherCents = 0
    val splitPaidCents = splitCashCents + splitCardCents
    val splitCanFinalize = finalTotalCents > 0 &&
        splitOverCents == 0 &&
        splitManualCents > 0 &&
        splitAutoCents > 0

    val keypadVisibleForCurrentMode = when (activeTarget) {
        PaymentInputTarget.CASH_RECEIVED -> mode == MenuPaymentMode.CASH
        PaymentInputTarget.VOUCHER_AMOUNT -> mode == MenuPaymentMode.VOUCHER
        PaymentInputTarget.SPLIT_CASH,
        PaymentInputTarget.SPLIT_CARD,
        PaymentInputTarget.SPLIT_VOUCHER,
        -> mode == MenuPaymentMode.SPLIT_PAYMENT
        PaymentInputTarget.DISCOUNT_PERCENT -> discountMode == BillDiscountMode.PERCENT
        PaymentInputTarget.DISCOUNT_AMOUNT -> discountMode == BillDiscountMode.AMOUNT
    }
    var voucherProviderName by rememberSaveable { mutableStateOf(VoucherProviderUi.SMARTUM.name) }
    val voucherProvider = remember(voucherProviderName) { VoucherProviderUi.valueOf(voucherProviderName) }

    val confirmEnabled = when (mode) {
        MenuPaymentMode.CASH -> finalTotalCents > 0 && (cashTenderedCents ?: 0) >= finalTotalCents
        MenuPaymentMode.CARD -> finalTotalCents > 0
        MenuPaymentMode.VOUCHER -> finalTotalCents > 0 && (voucherAmountCents ?: 0) >= finalTotalCents
        MenuPaymentMode.SPLIT_PAYMENT -> splitCanFinalize
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
            MenuPaymentMode.SPLIT_PAYMENT -> null
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

    fun selectVoucherProvider(provider: VoucherProviderUi) {
        voucherProviderName = provider.name
        mode = MenuPaymentMode.VOUCHER
        activeInputTarget = PaymentInputTarget.VOUCHER_AMOUNT.name
    }

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
            Column(
                modifier = Modifier
                    .width(1100.dp)
                    .height(682.dp)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Maksuvaihtoehdot",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PaymentDialogTextPrimary,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(0.34f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PaymentSummaryCard(
                            paymentContextLabel = paymentContextLabel,
                            subtotalCents = subtotalCents,
                            finalTotalCents = finalTotalCents,
                            billDiscountAmountCents = billDiscountAmountCents,
                            discountMode = discountMode,
                            discountInput = discountInput,
                            mode = mode,
                            voucherProviderLabel = voucherProvider.label,
                            cashTenderedCents = cashTenderedCents,
                            voucherAmountCents = voucherAmountCents,
                            splitCashCents = splitCashCents,
                            splitCardCents = splitCardCents,
                            splitVoucherCents = splitVoucherCents,
                            splitPaidCents = splitPaidCents,
                        )

                        PaymentHintCard(
                            title = when (mode) {
                                MenuPaymentMode.CASH -> "Valittu maksutapa"
                                MenuPaymentMode.CARD -> "Valittu maksutapa"
                                MenuPaymentMode.VOUCHER -> "Valittu maksutapa"
                                MenuPaymentMode.SPLIT_PAYMENT -> "Käteinen + kortti"
                            },
                            message = when (mode) {
                                MenuPaymentMode.CASH -> "Käteinen käyttää vastaanotettua summaa ja laskee vaihtorahan automaattisesti."
                                MenuPaymentMode.CARD -> "Kortti veloittaa jäljellä olevan summan. Viimeistele, kun maksupääte on valmis."
                                MenuPaymentMode.VOUCHER -> "Etuseteli kirjataan maksulle. Syötä summa ja tunniste oikealla."
                                MenuPaymentMode.SPLIT_PAYMENT -> "Syötä käteisen tai kortin osuus. Toinen maksutapa lasketaan automaattisesti."
                            },
                        )

                        ReceiptPrintOptionCard(
                            shouldPrintReceipt = shouldPrintReceipt,
                            onToggle = { shouldPrintReceipt = !shouldPrintReceipt },
                        )
                    }

                    Column(
                        modifier = Modifier.weight(0.40f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Lisätoiminnot",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PaymentDialogTextPrimary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PaymentUtilityButton(
                                label = "Alennus %",
                                selected = discountMode == BillDiscountMode.PERCENT,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(80.dp),
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
                                modifier = Modifier
                                    .weight(1f)
                                    .height(80.dp),
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
                        }

                        Text(
                            text = "Maksutavat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PaymentDialogTextSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PaymentModeButton(
                                label = MenuPaymentMode.CARD.label,
                                selected = mode == MenuPaymentMode.CARD,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(72.dp),
                                icon = { CardTenderIcon() },
                                onClick = {
                                    mode = MenuPaymentMode.CARD
                                    activeInputTarget = PaymentInputTarget.SPLIT_CARD.name
                                },
                            )
                            PaymentModeButton(
                                label = MenuPaymentMode.CASH.label,
                                selected = mode == MenuPaymentMode.CASH,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(72.dp),
                                icon = { CashTenderIcon() },
                                onClick = {
                                    mode = MenuPaymentMode.CASH
                                    activeInputTarget = PaymentInputTarget.CASH_RECEIVED.name
                                },
                            )
                        }
                        PaymentModeButton(
                            label = MenuPaymentMode.SPLIT_PAYMENT.label,
                            selected = mode == MenuPaymentMode.SPLIT_PAYMENT,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            icon = { SplitPaymentIcon(selected = mode == MenuPaymentMode.SPLIT_PAYMENT) },
                            onClick = {
                                mode = MenuPaymentMode.SPLIT_PAYMENT
                                activeInputTarget = PaymentInputTarget.SPLIT_CASH.name
                                splitVoucherInput = ""
                            },
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.32f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (mode == MenuPaymentMode.CARD) {
                            PaymentAmountFocusCard(
                                mode = mode,
                                voucherProviderLabel = voucherProvider.label,
                                finalTotalCents = finalTotalCents,
                            )
                            CardFullPaymentGuardCard()
                        }

                        if (mode == MenuPaymentMode.SPLIT_PAYMENT) {
                            SplitPaymentNoScrollPanel(
                                finalTotalCents = finalTotalCents,
                                splitCashCents = splitCashCents,
                                splitCardCents = splitCardCents,
                                activeTarget = activeTarget,
                                onSelectCash = {
                                    if (splitManualTarget == PaymentInputTarget.SPLIT_CARD) {
                                        splitCashInput = formatEditableMoneyInput(splitCashCents)
                                    }
                                    activeInputTarget = PaymentInputTarget.SPLIT_CASH.name
                                },
                                onSelectCard = {
                                    if (splitManualTarget == PaymentInputTarget.SPLIT_CASH) {
                                        splitCardInput = formatEditableMoneyInput(splitCardCents)
                                    }
                                    activeInputTarget = PaymentInputTarget.SPLIT_CARD.name
                                },
                            )
                        }

                        if (mode != MenuPaymentMode.CARD && mode != MenuPaymentMode.SPLIT_PAYMENT) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = PaymentDialogShellColor,
                                border = BorderStroke(1.dp, PaymentDialogBorderColor),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Maksun syöttö",
                                        style = MaterialTheme.typography.titleMedium,
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
                                                supportingText = { Text("Syötä etusetelin tunniste, viivakoodi tai koodi.") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                            )
                                        }

                                        MenuPaymentMode.CARD,
                                        MenuPaymentMode.SPLIT_PAYMENT,
                                        -> Unit
                                    }
                                }
                            }
                        }

                        if (keypadVisibleForCurrentMode && mode != MenuPaymentMode.CARD) {
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
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    PaymentDialogButton(
                        label = "Sulje",
                        onClick = onDismiss,
                        primary = false,
                        modifier = Modifier.weight(0.48f),
                    )
                    PaymentDialogButton(
                        label = when (mode) {
                            MenuPaymentMode.CASH -> {
                                val missingCents = (finalTotalCents - (cashTenderedCents ?: 0)).coerceAtLeast(0)
                                if (missingCents > 0) "Puuttuu ${CentsFormatter.format(missingCents)}" else "Viimeistele käteismaksu"
                            }
                            MenuPaymentMode.CARD -> "Viimeistele korttimaksu"
                            MenuPaymentMode.VOUCHER -> "Viimeistele ${voucherProvider.label}-maksu"
                            MenuPaymentMode.SPLIT_PAYMENT -> {
                                when {
                                    splitOverCents > 0 -> "Ylittää ${CentsFormatter.format(splitOverCents)}"
                                    splitCanFinalize -> "Viimeistele käteinen + kortti"
                                    splitManualCents <= 0 -> "Syötä käteinen tai kortti"
                                    splitAutoCents <= 0 -> "Valitse tavallinen maksutapa"
                                    else -> "Viimeistele käteinen + kortti"
                                }
                            }
                        },
                        onClick = { onConfirm(result) },
                        modifier = Modifier.weight(0.52f),
                        enabled = confirmEnabled,
                    )
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
    voucherProviderLabel: String,
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
            Text(
                text = "Laskun yhteenveto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PaymentDialogTextPrimary,
            )
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
                    PaymentSummaryRow(label = "Valittu maksu", value = "Käteinen", emphasized = true)
                    PaymentSummaryRow(label = "Saatu käteinen", value = cashTenderedCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Puuttuu", value = CentsFormatter.format(remainingCents))
                    PaymentSummaryRow(label = "Vaihtoraha", value = CentsFormatter.format(changeCents), emphasized = true)
                }

                MenuPaymentMode.CARD -> {
                    PaymentSummaryRow(label = "Valittu maksu", value = "Kortti", emphasized = true)
                }

                MenuPaymentMode.VOUCHER -> {
                    val voucherCents = voucherAmountCents ?: 0
                    val remainingCents = (finalTotalCents - voucherCents).coerceAtLeast(0)
                    PaymentSummaryRow(label = "Valittu maksu", value = voucherProviderLabel, emphasized = true)
                    PaymentSummaryRow(label = voucherProviderLabel, value = voucherAmountCents?.let(CentsFormatter::format) ?: "—")
                    PaymentSummaryRow(label = "Puuttuu", value = CentsFormatter.format(remainingCents), emphasized = remainingCents == 0)
                }

                MenuPaymentMode.SPLIT_PAYMENT -> {
                    PaymentSummaryRow(label = "Valittu maksu", value = "Käteinen + kortti", emphasized = true)
                    PaymentSummaryRow(label = "Käteinen", value = CentsFormatter.format(splitCashCents))
                    PaymentSummaryRow(label = "Kortti", value = CentsFormatter.format(splitCardCents))
                    PaymentSummaryRow(label = "Maksettu yhteensä", value = CentsFormatter.format(splitPaidCents))
                    PaymentSummaryRow(
                        label = if (splitPaidCents > finalTotalCents) "Ylittää" else "Puuttuu",
                        value = CentsFormatter.format(abs(finalTotalCents - splitPaidCents)),
                        emphasized = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentAmountFocusCard(
    mode: MenuPaymentMode,
    voucherProviderLabel: String,
    finalTotalCents: Int,
) {
    val title = when (mode) {
        MenuPaymentMode.CASH -> "Maksetaan käteisellä"
        MenuPaymentMode.CARD -> "Maksetaan kortilla"
        MenuPaymentMode.VOUCHER -> "$voucherProviderLabel-maksu"
        MenuPaymentMode.SPLIT_PAYMENT -> "Käteinen + kortti"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PaymentDialogTextPrimary,
            )
            Text(
                text = CentsFormatter.format(finalTotalCents),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogAccentTextColor,
            )
        }
    }
}

@Composable
private fun CardFullPaymentGuardCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Korttimaksu veloittaa koko jäljellä olevan summan.",
                style = MaterialTheme.typography.bodyMedium,
                color = PaymentDialogTextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Käteisosan kirjaus tehdään Käteinen + kortti -toiminnolla.",
                style = MaterialTheme.typography.bodySmall,
                color = PaymentDialogTextMuted,
            )
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
private fun SplitPaymentNoScrollPanel(
    finalTotalCents: Int,
    splitCashCents: Int,
    splitCardCents: Int,
    activeTarget: PaymentInputTarget,
    onSelectCash: () -> Unit,
    onSelectCard: () -> Unit,
) {
    val paidCents = splitCashCents + splitCardCents
    val overCents = (paidCents - finalTotalCents).coerceAtLeast(0)
    val missingCents = if (overCents > 0) 0 else (finalTotalCents - paidCents).coerceAtLeast(0)
    val activeIsCash = activeTarget != PaymentInputTarget.SPLIT_CARD

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PaymentDialogShellColor,
        border = BorderStroke(1.dp, PaymentDialogBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Käteinen + kortti",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PaymentDialogTextPrimary,
                    )
                    Text(
                        text = "Syötä toinen osuus. Loppu kirjataan toiselle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaymentDialogTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (overCents > 0) "Ylittää" else "Puuttuu",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaymentDialogTextMuted,
                    )
                    Text(
                        text = CentsFormatter.format(if (overCents > 0) overCents else missingCents),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (overCents > 0) PaymentDialogVoucherRed else PaymentDialogAccentTextColor,
                    )
                }
            }

            SplitPaymentAmountRow(
                label = "Käteinen",
                valueCents = splitCashCents,
                selected = activeIsCash,
                badge = if (activeIsCash) "Syötä" else "Loput",
                onClick = onSelectCash,
            )
            SplitPaymentAmountRow(
                label = "Kortti",
                valueCents = splitCardCents,
                selected = !activeIsCash,
                badge = if (!activeIsCash) "Syötä" else "Loput",
                onClick = onSelectCard,
            )
        }
    }
}

@Composable
private fun SplitPaymentAmountRow(
    label: String,
    valueCents: Int,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) PaymentDialogPanelAltColor else PaymentDialogPanelColor,
        border = BorderStroke(
            1.dp,
            if (selected) PaymentDialogAccentTextColor.copy(alpha = 0.75f) else PaymentDialogBorderColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) PaymentDialogAccentTextColor else PaymentDialogTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!badge.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) PaymentDialogAccentTextColor.copy(alpha = 0.16f) else PaymentDialogBorderColor.copy(alpha = 0.26f),
                        border = BorderStroke(1.dp, if (selected) PaymentDialogAccentTextColor.copy(alpha = 0.45f) else PaymentDialogBorderColor.copy(alpha = 0.40f)),
                    ) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) PaymentDialogAccentTextColor else PaymentDialogTextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = CentsFormatter.format(valueCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogTextPrimary,
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) PaymentDialogAccentTextColor else PaymentDialogTextMuted,
            )
            Text(
                text = formatPaymentDisplayValue(value),
                style = MaterialTheme.typography.titleLarge,
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
                .height(46.dp)
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
            .fillMaxHeight()
            .defaultMinSize(minHeight = 84.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) PaymentDialogPanelAltColor else PaymentDialogShellColor,
        border = BorderStroke(1.dp, if (selected) PaymentDialogUtilityBlue.copy(alpha = 0.95f) else PaymentDialogBorderColor.copy(alpha = 0.88f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogTextPrimary,
                maxLines = 1,
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
private fun PaymentIntegrationPreviewButton(
    label: String,
    logoRes: Int?,
    modifier: Modifier = Modifier,
    containerColor: Color,
    borderColor: Color,
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = containerColor.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (label) {
                    "Smartum" -> SmartumWordmark()
                    "Edenred" -> EdenredWordmark()
                    "ePassi" -> EPassiWordmark()
                    else -> {
                        if (logoRes != null) {
                            Image(
                                painter = painterResource(id = logoRes),
                                contentDescription = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = PaymentDialogUtilityBlue,
                            )
                        }
                    }
                }
            }
            Text(
                text = "Tulossa",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = PaymentDialogTextMuted,
                maxLines = 1,
            )
        }
    }
}


@Composable
private fun PaymentBrandButton(
    label: String,
    logoRes: Int?,
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
            when (label) {
                "Smartum" -> SmartumWordmark()
                "Edenred" -> EdenredWordmark()
                "ePassi" -> EPassiWordmark()
                else -> {
                    if (logoRes != null) {
                        Image(
                            painter = painterResource(id = logoRes),
                            contentDescription = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = PaymentDialogUtilityBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartumWordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "smartum",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = PaymentDialogTextPrimary,
            maxLines = 1,
        )
        Text(
            text = "pay",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = PaymentDialogCoinGold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EdenredWordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color(0xFFE8483F), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "e",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Text(
            text = "edenred",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF6856),
            maxLines = 1,
        )
    }
}

@Composable
private fun EPassiWordmark() {
    Text(
        text = "ePassi",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFFF7A2F),
        maxLines = 1,
    )
}

@Composable
private fun WoltWordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color(0xFF2FA9DF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape),
            )
        }
        Text(
            text = "Wolt",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF52C7F7),
            maxLines = 1,
        )
    }
}

@Composable
private fun PaymentUtilityIconTile(
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.size(width = 58.dp, height = 42.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.26f) else Color(0xFF152839),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.95f) else accent.copy(alpha = 0.32f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 24.dp, height = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = if (selected) 0.22f else 0.14f)),
            )
            content()
        }
    }
}

@Composable
private fun DiscountPercentIcon(
    selected: Boolean,
) {
    PaymentUtilityIconTile(
        selected = selected,
        accent = PaymentDialogCardBlue,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(PaymentDialogCardBlue.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "%",
                color = PaymentDialogCardBlue,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun DiscountCoinsIcon(
    selected: Boolean,
) {
    PaymentUtilityIconTile(
        selected = selected,
        accent = PaymentDialogCoinGold,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == 1) 18.dp else 14.dp)
                        .clip(CircleShape)
                        .background(PaymentDialogCoinGold.copy(alpha = if (selected) 1f else 0.90f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (index == 1) {
                        Text(
                            text = "€",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF2C2108),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoucherTicketIcon(
    selected: Boolean,
) {
    PaymentUtilityIconTile(
        selected = selected,
        accent = PaymentDialogVoucherRed,
    ) {
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(PaymentDialogVoucherRed.copy(alpha = 0.92f)),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(Color.White.copy(alpha = 0.78f)),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Color(0xFF152839)),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF152839)),
            )
        }
    }
}

@Composable
private fun SplitPaymentIcon(
    selected: Boolean,
) {
    PaymentUtilityIconTile(
        selected = selected,
        accent = PaymentDialogUtilityBlue,
    ) {
        Box(
            modifier = Modifier.size(width = 44.dp, height = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 26.dp, height = 17.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(5.dp))
                    .background(PaymentDialogCardBlue.copy(alpha = 0.92f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.TopCenter)
                        .background(Color(0xFF102234).copy(alpha = 0.56f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 27.dp, height = 16.dp)
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(5.dp))
                    .background(PaymentDialogCashGreen.copy(alpha = 0.94f)),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color(0xFF10271F).copy(alpha = 0.72f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(PaymentDialogCoinGold),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    color = Color(0xFF2C2108),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CashTenderIcon() {
    PaymentTenderIconTile(
        accent = PaymentDialogCashGreen,
        background = Color(0xFF10271F),
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(PaymentDialogCashGreen.copy(alpha = 0.96f)),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 15.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFB8F5DF).copy(alpha = 0.42f)),
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0xFF10271F).copy(alpha = 0.72f)),
            )
            Box(
                modifier = Modifier
                    .size(width = 11.dp, height = 4.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF10271F).copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier
                    .size(width = 11.dp, height = 4.dp)
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF10271F).copy(alpha = 0.55f)),
            )
        }
        Box(
            modifier = Modifier
                .size(15.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(PaymentDialogCoinGold),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "€",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color(0xFF2C2108),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CardTenderIcon() {
    PaymentTenderIconTile(
        accent = PaymentDialogCardBlue,
        background = Color(0xFF102234),
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 27.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(PaymentDialogCardBlue.copy(alpha = 0.97f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .align(Alignment.TopCenter)
                    .background(Color(0xFF0B1A27).copy(alpha = 0.58f)),
            )
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 8.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE9FBFF).copy(alpha = 0.72f)),
            )
            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 4.dp)
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF0B1A27).copy(alpha = 0.50f)),
            )
        }
    }
}

@Composable
private fun PaymentTenderIconTile(
    accent: Color,
    background: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = background,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
    ) {
        Box(
            modifier = Modifier
                .size(width = 60.dp, height = 42.dp)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 30.dp, height = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
            )
            content()
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
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
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
    SPLIT_CASH("Käteisellä", true, ::formatPaymentDisplayValue),
    SPLIT_CARD("Kortilla", true, ::formatPaymentDisplayValue),
    SPLIT_VOUCHER("Etusetelillä", true, ::formatPaymentDisplayValue),
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
