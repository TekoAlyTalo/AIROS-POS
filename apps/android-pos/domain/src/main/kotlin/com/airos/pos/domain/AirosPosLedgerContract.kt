package com.airos.pos.domain

import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.ReceiptAlignment
import com.airos.pos.core.model.ReceiptBarcode
import com.airos.pos.core.model.ReceiptBarcodeFormat
import com.airos.pos.core.model.ReceiptBonusProgram
import com.airos.pos.core.model.ReceiptBusiness
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptImageSource
import com.airos.pos.core.model.ReceiptImageSourceType
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.ReceiptLogo
import com.airos.pos.core.model.ReceiptPaymentRecord
import com.airos.pos.core.model.ReceiptTotals
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import com.airos.pos.core.model.TicketLine

/**
 * POS <-> backend ledger contract for AIROS POS finalized sales.
 *
 * Purpose:
 * - give POS one stable payload shape for POST /api/pos/sales/finalize
 * - give POS one stable response shape for QR/public receipt data
 * - keep the mapping logic out of MenuFeature / dialog code
 *
 * Notes:
 * - Field names intentionally use snake_case to match the FastAPI request/response contract
 *   without requiring serializer-specific annotations.
 * - This file does NOT perform HTTP calls. It only defines the contract + mapping helpers.
 */

data class LedgerFinalizeSaleLineItem(
    val id: String? = null,
    val ticket_line_id: String? = null,
    val menu_item_id: String? = null,
    val sku: String? = null,
    val name: String? = null,
    val product_name: String? = null,
    val category: String? = null,
    val category_name: String? = null,
    val quantity: Int,
    val unit_price_cents: Int,
    val line_total_cents: Int,
    val tax_rate_percent: Double? = null,
    val line_discount_cents: Int = 0,
    val note: String? = null,
    val raw_payload: Map<String, Any?>? = null,
)

data class LedgerFinalizeSalePayment(
    val method: String,
    val amount_cents: Int,
    val reference: String? = null,
    val display_label: String? = null,
    val provider_name: String? = null,
    val provider_txn_id: String? = null,
    val voucher_code: String? = null,
)

data class LedgerReceiptLine(
    val label: String,
    val value: String? = null,
    val quantity: String? = null,
    val unitPriceCents: Int? = null,
    val totalPriceCents: Int? = null,
    val note: String? = null,
    val alignment: String = "LEFT",
)

data class LedgerReceiptImageSource(
    val type: String,
    val value: String,
)

data class LedgerReceiptLogo(
    val source: LedgerReceiptImageSource,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val align: String = "CENTER",
)

data class LedgerReceiptBarcode(
    val value: String,
    val format: String = "QR_CODE",
    val label: String? = null,
    val align: String = "CENTER",
    val heightPx: Int? = null,
    val moduleWidth: Int? = null,
)

data class LedgerReceiptBonusProgram(
    val programName: String,
    val memberId: String? = null,
    val memberDisplayName: String? = null,
    val pointsBalance: Int? = null,
    val pointsEarned: Int? = null,
    val pointsRedeemed: Int? = null,
    val tierName: String? = null,
    val footerMessage: String? = null,
)

data class LedgerReceiptBusiness(
    val displayName: String,
    val legalName: String? = null,
    val businessId: String? = null,
    val vatId: String? = null,
    val addressLines: List<String> = emptyList(),
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
)

data class LedgerReceiptTotals(
    val subtotalCents: Int,
    val discountCents: Int = 0,
    val taxCents: Int = 0,
    val totalCents: Int,
)

data class LedgerReceiptPaymentRecord(
    val method: String,
    val amountCents: Int,
    val reference: String? = null,
    val displayLabel: String? = null,
)

data class LedgerReceiptDocument(
    val title: String = "Receipt",
    val lines: List<LedgerReceiptLine> = emptyList(),
    val footer: String = "",
    val currencyCode: String = "EUR",
    val business: LedgerReceiptBusiness? = null,
    val logo: LedgerReceiptLogo? = null,
    val headerText: String? = null,
    val footerText: String? = null,
    val barcode: LedgerReceiptBarcode? = null,
    val bonusProgram: LedgerReceiptBonusProgram? = null,
    val totals: LedgerReceiptTotals? = null,
    val payments: List<LedgerReceiptPaymentRecord> = emptyList(),
    val receiptNumber: String? = null,
    val orderNumber: String? = null,
    val printedAtEpochMillis: Long? = null,
    val cashierName: String? = null,
    val customerDisplayName: String? = null,
    val customerNote: String? = null,
    val internalNote: String? = null,
    val extraTextBlocks: List<String> = emptyList(),
)

data class LedgerFinalizeSaleRequest(
    val receipt_number: String,
    val finalized_at_epoch_ms: Long,
    val total_cents: Int,
    val total_paid_cents: Int,
    val currency_code: String = "EUR",
    val country_profile: String = "FI",
    val language_code: String = "fi",
    val line_items: List<LedgerFinalizeSaleLineItem> = emptyList(),
    val payments: List<LedgerFinalizeSalePayment> = emptyList(),
    val receipt_document: LedgerReceiptDocument,
    val ticket_id: String? = null,
    val order_number: String? = null,
    val table_id: String? = null,
    val table_label: String? = null,
    val terminal_id: String? = null,
    val terminal_name: String? = null,
    val restaurant_id: String? = null,
    val cashier_staff_id: String? = null,
    val cashier_name: String? = null,
    val opened_at_epoch_ms: Long? = null,
    val subtotal_cents: Int? = null,
    val discount_cents: Int = 0,
    val tax_cents: Int = 0,
    val change_cents: Int = 0,
    val source_pos_event_id: String? = null,
    val sale_channel: String? = null,
    val inventory_payload: Map<String, Any?>? = null,
    val audit_details: Map<String, Any?>? = null,
    val enable_public_receipt: Boolean = true,
    val delivery_mode: String = "QR",
    val token_ttl_ms: Long? = null,
    val public_route_prefix: String = "/api/pos/receipts/public",
)

data class LedgerFinalizeSaleResponse(
    val ok: Boolean = true,
    val sale_id: String,
    val receipt_snapshot_id: String,
    val inventory_event_id: String? = null,
    val delivery_token_ids: List<String> = emptyList(),
    val raw_public_token: String? = null,
    val public_url_path: String? = null,
) {
    fun absolutePublicReceiptUrl(backendBaseUrl: String): String? {
        val path = public_url_path?.trim().orEmpty()
        if (path.isEmpty()) return null
        val base = backendBaseUrl.trim().trimEnd('/')
        return if (path.startsWith("http://") || path.startsWith("https://")) path else "$base/${path.trimStart('/')}"
    }
}

object AirosPosLedgerMapper {
    fun buildFinalizeSaleRequest(
        paymentRequest: TablePaymentRequest,
        paymentResult: TablePaymentResult,
        terminalId: String? = null,
        terminalName: String? = null,
        restaurantId: String? = null,
        cashierStaffId: String? = null,
        cashierName: String? = paymentResult.receiptDocument.cashierName,
        countryProfile: String = "FI",
        languageCode: String = "fi",
        currencyCode: String = paymentResult.receiptDocument.currencyCode,
        saleChannel: String = if (paymentResult.tableId.isNullOrBlank()) "walk_in" else "table_service",
        sourcePosEventId: String? = null,
        inventoryPayload: Map<String, Any?>? = defaultInventoryPayload(paymentRequest.lines),
        auditDetails: Map<String, Any?>? = null,
        enablePublicReceipt: Boolean = true,
        deliveryMode: String = "QR",
        tokenTtlMs: Long? = null,
        publicRoutePrefix: String = "/api/pos/receipts/public",
    ): LedgerFinalizeSaleRequest {
        val receiptDocument = paymentResult.receiptDocument
        val receiptNumber = receiptDocument.receiptNumber ?: paymentResult.ticketId
        val orderNumber = receiptDocument.orderNumber ?: paymentResult.ticketId
        val lineItems = paymentRequest.lines.map { it.toLedgerLineItem() }
        val payments = paymentRequest.payments.mapIndexed { index, entry ->
            entry.toLedgerPayment(voucherCode = voucherCodeForEntry(entry.method, paymentRequest.voucherBarcodeValue, index))
        }

        return LedgerFinalizeSaleRequest(
            receipt_number = receiptNumber,
            finalized_at_epoch_ms = receiptDocument.printedAtEpochMillis ?: System.currentTimeMillis(),
            total_cents = paymentResult.totalDueCents,
            total_paid_cents = paymentResult.totalPaidCents,
            currency_code = currencyCode,
            country_profile = countryProfile,
            language_code = languageCode,
            line_items = lineItems,
            payments = payments,
            receipt_document = receiptDocument.toLedgerReceiptDocument(),
            ticket_id = paymentResult.ticketId,
            order_number = orderNumber,
            table_id = paymentResult.tableId,
            table_label = paymentResult.tableLabel,
            terminal_id = terminalId,
            terminal_name = terminalName,
            restaurant_id = restaurantId,
            cashier_staff_id = cashierStaffId,
            cashier_name = cashierName,
            opened_at_epoch_ms = null,
            subtotal_cents = receiptDocument.totals?.subtotalCents ?: paymentResult.totalDueCents,
            discount_cents = receiptDocument.totals?.discountCents ?: 0,
            tax_cents = receiptDocument.totals?.taxCents ?: 0,
            change_cents = paymentResult.changeCents,
            source_pos_event_id = sourcePosEventId,
            sale_channel = saleChannel,
            inventory_payload = inventoryPayload,
            audit_details = auditDetails,
            enable_public_receipt = enablePublicReceipt,
            delivery_mode = deliveryMode,
            token_ttl_ms = tokenTtlMs,
            public_route_prefix = publicRoutePrefix,
        )
    }

    fun applyPublicReceiptQr(
        document: ReceiptDocument,
        backendBaseUrl: String,
        ledgerResponse: LedgerFinalizeSaleResponse,
        label: String = "Electronic receipt",
    ): ReceiptDocument {
        val absoluteUrl = ledgerResponse.absolutePublicReceiptUrl(backendBaseUrl) ?: return document
        return document.copy(
            barcode = ReceiptBarcode(
                value = absoluteUrl,
                format = ReceiptBarcodeFormat.QR_CODE,
                label = label,
                align = ReceiptAlignment.CENTER,
            ),
        )
    }

    fun defaultInventoryPayload(lines: List<TicketLine>): Map<String, Any?> {
        return mapOf(
            "event_type" to "SALE_FINALIZED",
            "line_items" to lines.map { line ->
                mapOf(
                    "ticket_line_id" to line.id,
                    "menu_item_id" to line.menuItemId,
                    "product_name" to line.name,
                    "quantity" to line.quantity,
                    "unit_price_cents" to line.unitPriceCents,
                    "line_total_cents" to line.totalPriceCents,
                    "tax_rate_percent" to line.taxRatePercent,
                )
            },
        )
    }

    private fun voucherCodeForEntry(method: PaymentMethod, voucherBarcodeValue: String?, index: Int): String? {
        if (method != PaymentMethod.VOUCHER) return null
        val value = voucherBarcodeValue?.trim().orEmpty()
        return if (value.isEmpty()) null else value
    }
}

private fun TicketLine.toLedgerLineItem(): LedgerFinalizeSaleLineItem {
    return LedgerFinalizeSaleLineItem(
        id = id,
        ticket_line_id = id,
        menu_item_id = menuItemId,
        product_name = name,
        quantity = quantity,
        unit_price_cents = unitPriceCents,
        line_total_cents = totalPriceCents,
        tax_rate_percent = taxRatePercent,
        note = note,
    )
}

private fun com.airos.pos.core.model.PaymentEntry.toLedgerPayment(voucherCode: String?): LedgerFinalizeSalePayment {
    return LedgerFinalizeSalePayment(
        method = method.name,
        amount_cents = amountCents,
        reference = reference,
        display_label = displayLabel,
        voucher_code = voucherCode,
    )
}

private fun ReceiptDocument.toLedgerReceiptDocument(): LedgerReceiptDocument {
    return LedgerReceiptDocument(
        title = title,
        lines = lines.map { it.toLedgerReceiptLine() },
        footer = footer,
        currencyCode = currencyCode,
        business = business?.toLedgerReceiptBusiness(),
        logo = logo?.toLedgerReceiptLogo(),
        headerText = headerText,
        footerText = footerText,
        barcode = barcode?.toLedgerReceiptBarcode(),
        bonusProgram = bonusProgram?.toLedgerReceiptBonusProgram(),
        totals = totals?.toLedgerReceiptTotals(),
        payments = payments.map { it.toLedgerReceiptPaymentRecord() },
        receiptNumber = receiptNumber,
        orderNumber = orderNumber,
        printedAtEpochMillis = printedAtEpochMillis,
        cashierName = cashierName,
        customerDisplayName = customerDisplayName,
        customerNote = customerNote,
        internalNote = internalNote,
        extraTextBlocks = extraTextBlocks,
    )
}

private fun ReceiptLine.toLedgerReceiptLine(): LedgerReceiptLine {
    return LedgerReceiptLine(
        label = label,
        value = value,
        quantity = quantity,
        unitPriceCents = unitPriceCents,
        totalPriceCents = totalPriceCents,
        note = note,
        alignment = alignment.name,
    )
}

private fun ReceiptBusiness.toLedgerReceiptBusiness(): LedgerReceiptBusiness {
    return LedgerReceiptBusiness(
        displayName = displayName,
        legalName = legalName,
        businessId = businessId,
        vatId = vatId,
        addressLines = addressLines,
        phone = phone,
        email = email,
        website = website,
    )
}

private fun ReceiptLogo.toLedgerReceiptLogo(): LedgerReceiptLogo {
    return LedgerReceiptLogo(
        source = source.toLedgerReceiptImageSource(),
        widthPx = widthPx,
        heightPx = heightPx,
        align = align.name,
    )
}

private fun ReceiptImageSource.toLedgerReceiptImageSource(): LedgerReceiptImageSource {
    return LedgerReceiptImageSource(
        type = type.name,
        value = value,
    )
}

private fun ReceiptBarcode.toLedgerReceiptBarcode(): LedgerReceiptBarcode {
    return LedgerReceiptBarcode(
        value = value,
        format = format.name,
        label = label,
        align = align.name,
        heightPx = heightPx,
        moduleWidth = moduleWidth,
    )
}

private fun ReceiptBonusProgram.toLedgerReceiptBonusProgram(): LedgerReceiptBonusProgram {
    return LedgerReceiptBonusProgram(
        programName = programName,
        memberId = memberId,
        memberDisplayName = memberDisplayName,
        pointsBalance = pointsBalance,
        pointsEarned = pointsEarned,
        pointsRedeemed = pointsRedeemed,
        tierName = tierName,
        footerMessage = footerMessage,
    )
}

private fun ReceiptTotals.toLedgerReceiptTotals(): LedgerReceiptTotals {
    return LedgerReceiptTotals(
        subtotalCents = subtotalCents,
        discountCents = discountCents,
        taxCents = taxCents,
        totalCents = totalCents,
    )
}

private fun ReceiptPaymentRecord.toLedgerReceiptPaymentRecord(): LedgerReceiptPaymentRecord {
    return LedgerReceiptPaymentRecord(
        method = method.name,
        amountCents = amountCents,
        reference = reference,
        displayLabel = displayLabel,
    )
}
