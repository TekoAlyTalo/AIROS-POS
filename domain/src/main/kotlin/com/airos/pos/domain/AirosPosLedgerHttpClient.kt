package com.airos.pos.domain

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Lightweight HTTP client for AIROS POS ledger finalization.
 *
 * Important:
 * - avoids assuming Retrofit / Ktor / org.json in the pure Kotlin domain module
 * - serializes JSON manually so the domain module stays dependency-light
 */
interface AirosPosLedgerHttpClient {
    suspend fun finalizeSale(request: LedgerFinalizeSaleRequest): PosResult<LedgerFinalizeSaleResponse>
}

data class LedgerFinalizeAndQrResult(
    val ledgerResponse: LedgerFinalizeSaleResponse,
    val receiptDocumentWithQr: ReceiptDocument,
)

class DefaultAirosPosLedgerHttpClient(
    private val backendBaseUrlProvider: () -> String,
    private val authHeaderProvider: (() -> String?)? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val endpointPath: String = "/api/pos/sales/finalize",
) : AirosPosLedgerHttpClient {

    private fun debugLog(message: String) {
        System.err.println("[AIROS][LEDGER][HTTP] $message")
    }

    override suspend fun finalizeSale(request: LedgerFinalizeSaleRequest): PosResult<LedgerFinalizeSaleResponse> {
        return withContext(Dispatchers.IO) {
            val rawBaseUrl = backendBaseUrlProvider.invoke()
            val baseUrl = rawBaseUrl.trim().trimEnd('/')
            if (baseUrl.isEmpty()) {
                debugLog("baseUrl empty raw='${rawBaseUrl}'")
                return@withContext PosResult.Failure("AIROS ledger backend base URL is empty.")
            }

            val urlString = "$baseUrl/${endpointPath.trimStart('/')}"
            debugLog("finalizeSale start baseUrl='${baseUrl}' url='${urlString}' receipt='${request.receipt_number}' total=${request.total_cents}")

            val connection = try {
                (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doInput = true
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    authHeaderProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }?.let { header ->
                        setRequestProperty("Authorization", header)
                    }
                }
            } catch (t: Throwable) {
                debugLog("connection open failed url='${urlString}' type=${t.javaClass.simpleName} message=${t.message}")
                return@withContext PosResult.Failure(
                    "AIROS ledger connection open failed at ${urlString}: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                )
            }

            try {
                val requestJson = request.toJsonString()
                debugLog("request body bytes=${requestJson.toByteArray(StandardCharsets.UTF_8).size}")
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(requestJson)
                    writer.flush()
                }

                val statusCode = connection.responseCode
                val responseBody = readResponseBody(
                    if (statusCode in 200..299) connection.inputStream else connection.errorStream,
                )
                debugLog("response status=${statusCode} body=${responseBody.take(500)}")

                if (statusCode !in 200..299) {
                    val message = parseErrorMessage(responseBody)
                    return@withContext PosResult.Failure(
                        "AIROS ledger finalize failed at ${urlString}: HTTP $statusCode${if (message.isBlank()) "" else " - $message"}",
                    )
                }

                val parsed = try {
                    responseBody.toLedgerFinalizeSaleResponse()
                } catch (t: Throwable) {
                    debugLog("response parse failed type=${t.javaClass.simpleName} message=${t.message} body=${responseBody.take(500)}")
                    return@withContext PosResult.Failure(
                        "AIROS ledger finalize response parse failed from ${urlString}: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                    )
                }

                debugLog("success saleId='${parsed.sale_id}' receiptSnapshotId='${parsed.receipt_snapshot_id}' publicUrl='${parsed.public_url_path}'")
                PosResult.Success(parsed)
            } catch (t: Throwable) {
                debugLog("request failed url='${urlString}' type=${t.javaClass.simpleName} message=${t.message}")
                PosResult.Failure(
                    "AIROS ledger finalize request failed at ${urlString}: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}

object AirosPosLedgerFinalizeBridge {
    suspend fun finalizeSaleAndAttachQr(
        client: AirosPosLedgerHttpClient,
        backendBaseUrl: String,
        paymentRequest: TablePaymentRequest,
        paymentResult: TablePaymentResult,
        terminalId: String? = null,
        terminalName: String? = null,
        restaurantId: String? = null,
        cashierStaffId: String? = null,
        cashierName: String? = paymentResult.receiptDocument.cashierName,
        cashierSessionId: String? = null,
        cashierAuthMethodSnapshot: String? = null,
        countryProfile: String = "FI",
        languageCode: String = "fi",
        currencyCode: String = paymentResult.receiptDocument.currencyCode,
        saleChannel: String = if (paymentResult.tableId.isNullOrBlank()) "walk_in" else "table_service",
        sourcePosEventId: String? = null,
        enablePublicReceipt: Boolean = true,
        deliveryMode: String = "QR",
        tokenTtlMs: Long? = null,
        publicRoutePrefix: String = "/api/pos/receipts/public",
        qrLabel: String = "Electronic receipt",
    ): PosResult<LedgerFinalizeAndQrResult> {
        val request = AirosPosLedgerMapper.buildFinalizeSaleRequest(
            paymentRequest = paymentRequest,
            paymentResult = paymentResult,
            terminalId = terminalId,
            terminalName = terminalName,
            restaurantId = restaurantId,
            cashierStaffId = cashierStaffId,
            cashierName = cashierName,
            sessionId = cashierSessionId,
            authMethodSnapshot = cashierAuthMethodSnapshot,
            countryProfile = countryProfile,
            languageCode = languageCode,
            currencyCode = currencyCode,
            saleChannel = saleChannel,
            sourcePosEventId = sourcePosEventId,
            enablePublicReceipt = enablePublicReceipt,
            deliveryMode = deliveryMode,
            tokenTtlMs = tokenTtlMs,
            publicRoutePrefix = publicRoutePrefix,
        )

        return when (val finalize = client.finalizeSale(request)) {
            is PosResult.Success -> {
                val qrDocument = AirosPosLedgerMapper.applyPublicReceiptQr(
                    document = paymentResult.receiptDocument,
                    backendBaseUrl = backendBaseUrl,
                    ledgerResponse = finalize.value,
                    label = qrLabel,
                )
                PosResult.Success(
                    LedgerFinalizeAndQrResult(
                        ledgerResponse = finalize.value,
                        receiptDocumentWithQr = qrDocument,
                    ),
                )
            }
            is PosResult.Failure -> finalize
        }
    }
}

private fun LedgerFinalizeSaleRequest.toJsonString(): String = jsonString(toJsonMap())

private fun LedgerFinalizeSaleRequest.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "receipt_number" to receipt_number,
    "finalized_at_epoch_ms" to finalized_at_epoch_ms,
    "total_cents" to total_cents,
    "total_paid_cents" to total_paid_cents,
    "currency_code" to currency_code,
    "country_profile" to country_profile,
    "language_code" to language_code,
    "line_items" to line_items.map { it.toJsonMap() },
    "payments" to payments.map { it.toJsonMap() },
    "receipt_document" to receipt_document.toJsonMap(),
    "ticket_id" to ticket_id,
    "order_number" to order_number,
    "table_id" to table_id,
    "table_label" to table_label,
    "terminal_id" to terminal_id,
    "terminal_name" to terminal_name,
    "restaurant_id" to restaurant_id,
    "cashier_staff_id" to cashier_staff_id,
    "cashier_name" to cashier_name,
    "session_id" to session_id,
    "auth_method_snapshot" to auth_method_snapshot,
    "opened_at_epoch_ms" to opened_at_epoch_ms,
    "subtotal_cents" to subtotal_cents,
    "discount_cents" to discount_cents,
    "tax_cents" to tax_cents,
    "change_cents" to change_cents,
    "source_pos_event_id" to source_pos_event_id,
    "sale_channel" to sale_channel,
    "inventory_payload" to inventory_payload,
    "audit_details" to audit_details,
    "enable_public_receipt" to enable_public_receipt,
    "delivery_mode" to delivery_mode,
    "token_ttl_ms" to token_ttl_ms,
    "public_route_prefix" to public_route_prefix,
)

private fun LedgerFinalizeSaleLineItem.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "ticket_line_id" to ticket_line_id,
    "menu_item_id" to menu_item_id,
    "sku" to sku,
    "name" to name,
    "product_name" to product_name,
    "category" to category,
    "category_name" to category_name,
    "quantity" to quantity,
    "unit_price_cents" to unit_price_cents,
    "line_total_cents" to line_total_cents,
    "tax_rate_percent" to tax_rate_percent,
    "line_discount_cents" to line_discount_cents,
    "note" to note,
    "raw_payload" to raw_payload,
)

private fun LedgerFinalizeSalePayment.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "method" to method,
    "amount_cents" to amount_cents,
    "reference" to reference,
    "display_label" to display_label,
    "provider_name" to provider_name,
    "provider_txn_id" to provider_txn_id,
    "voucher_code" to voucher_code,
)

private fun LedgerReceiptDocument.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "title" to title,
    "lines" to lines.map { it.toJsonMap() },
    "footer" to footer,
    "currencyCode" to currencyCode,
    "business" to business?.toJsonMap(),
    "logo" to logo?.toJsonMap(),
    "headerText" to headerText,
    "footerText" to footerText,
    "barcode" to barcode?.toJsonMap(),
    "bonusProgram" to bonusProgram?.toJsonMap(),
    "totals" to totals?.toJsonMap(),
    "payments" to payments.map { it.toJsonMap() },
    "receiptNumber" to receiptNumber,
    "orderNumber" to orderNumber,
    "printedAtEpochMillis" to printedAtEpochMillis,
    "cashierName" to cashierName,
    "customerDisplayName" to customerDisplayName,
    "customerNote" to customerNote,
    "internalNote" to internalNote,
    "extraTextBlocks" to extraTextBlocks,
)

private fun LedgerReceiptLine.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "label" to label,
    "value" to value,
    "quantity" to quantity,
    "unitPriceCents" to unitPriceCents,
    "totalPriceCents" to totalPriceCents,
    "note" to note,
    "alignment" to alignment,
)

private fun LedgerReceiptImageSource.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "type" to type,
    "value" to value,
)

private fun LedgerReceiptLogo.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "source" to source.toJsonMap(),
    "widthPx" to widthPx,
    "heightPx" to heightPx,
    "align" to align,
)

private fun LedgerReceiptBarcode.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "value" to value,
    "format" to format,
    "label" to label,
    "align" to align,
    "heightPx" to heightPx,
    "moduleWidth" to moduleWidth,
)

private fun LedgerReceiptBonusProgram.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "programName" to programName,
    "memberId" to memberId,
    "memberDisplayName" to memberDisplayName,
    "pointsBalance" to pointsBalance,
    "pointsEarned" to pointsEarned,
    "pointsRedeemed" to pointsRedeemed,
    "tierName" to tierName,
    "footerMessage" to footerMessage,
)

private fun LedgerReceiptBusiness.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "displayName" to displayName,
    "legalName" to legalName,
    "businessId" to businessId,
    "vatId" to vatId,
    "addressLines" to addressLines,
    "phone" to phone,
    "email" to email,
    "website" to website,
)

private fun LedgerReceiptTotals.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "subtotalCents" to subtotalCents,
    "discountCents" to discountCents,
    "taxCents" to taxCents,
    "totalCents" to totalCents,
)

private fun LedgerReceiptPaymentRecord.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "method" to method,
    "amountCents" to amountCents,
    "reference" to reference,
    "displayLabel" to displayLabel,
)

private fun jsonString(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> jsonQuote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                jsonQuote(k.toString()) + ":" + jsonString(v)
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> jsonString(item) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> jsonString(item) }
        else -> jsonQuote(value.toString())
    }
}

private fun jsonQuote(value: String): String {
    val out = StringBuilder(value.length + 16)
    out.append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}

private fun String.toLedgerFinalizeSaleResponse(): LedgerFinalizeSaleResponse {
    return LedgerFinalizeSaleResponse(
        ok = jsonBoolean("ok") ?: true,
        sale_id = requireJsonString("sale_id"),
        receipt_snapshot_id = requireJsonString("receipt_snapshot_id"),
        inventory_event_id = jsonNullableString("inventory_event_id"),
        delivery_token_ids = jsonStringArray("delivery_token_ids"),
        raw_public_token = jsonNullableString("raw_public_token"),
        public_url_path = jsonNullableString("public_url_path"),
    )
}

private fun String.requireJsonString(key: String): String {
    return jsonNullableString(key) ?: error("Missing required field '$key'")
}

private fun String.jsonNullableString(key: String): String? {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|\"((?:\\\\.|[^\\\"])*)\")")
    val match = pattern.find(this) ?: return null
    val raw = match.groupValues[1]
    if (raw == "null") return null
    return unescapeJsonString(match.groupValues[2]).takeIf { it.isNotBlank() }
}

private fun String.jsonBoolean(key: String): Boolean? {
    val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
    val match = pattern.find(this) ?: return null
    return match.groupValues[1].toBooleanStrictOrNull()
}

private fun String.jsonStringArray(key: String): List<String> {
    val arrayBody = jsonArrayBody(key) ?: return emptyList()
    val itemPattern = Regex("\"((?:\\\\.|[^\\\"])*)\"")
    return itemPattern.findAll(arrayBody)
        .map { unescapeJsonString(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .toList()
}

private fun String.jsonArrayBody(key: String): String? {
    val keyPattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[")
    val match = keyPattern.find(this) ?: return null
    val startIndex = match.range.last + 1
    var depth = 1
    var i = startIndex
    var inString = false
    var escaped = false
    while (i < length) {
        val ch = this[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        return substring(startIndex, i)
                    }
                }
            }
        }
        i += 1
    }
    return null
}

private fun unescapeJsonString(value: String): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch != '\\') {
            out.append(ch)
            i += 1
            continue
        }
        if (i + 1 >= value.length) {
            out.append('\\')
            break
        }
        when (val next = value[i + 1]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                val hexStart = i + 2
                val hexEnd = (i + 6).coerceAtMost(value.length)
                if (hexEnd - hexStart == 4) {
                    val code = value.substring(hexStart, hexEnd).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                out.append('u')
            }
            else -> out.append(next)
        }
        i += 2
    }
    return out.toString()
}

private fun parseErrorMessage(responseBody: String): String {
    if (responseBody.isBlank()) return ""
    return responseBody.jsonNullableString("detail")
        ?: responseBody.jsonNullableString("message")
        ?: responseBody.take(300)
}

private fun readResponseBody(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.use { input ->
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    append(line)
                }
            }
        }
    }
}
