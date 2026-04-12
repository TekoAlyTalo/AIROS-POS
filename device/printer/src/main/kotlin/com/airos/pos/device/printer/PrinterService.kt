package com.airos.pos.device.printer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.RemoteException
import android.util.Base64
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.KitchenTicketDocument
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.ReceiptBarcodeFormat
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.ReceiptPaymentRecord
import com.airos.pos.core.model.ReceiptTotals
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService as SunmiInnerPrinterService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface PrinterService {
    val availability: StateFlow<DeviceConnectionState>
    suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit>
    suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit>
    suspend fun printDiagnosticReceipt(): PosResult<Unit>
}

class SunmiPrinterService(
    private val context: Context,
) : PrinterService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)
    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow

    private val connectMutex = Mutex()

    @Volatile
    private var printerService: SunmiInnerPrinterService? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<SunmiInnerPrinterService>? = null

    private val printerCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiInnerPrinterService) {
            printerService = service
            availabilityFlow.value = DeviceConnectionState.READY
            pendingConnection?.complete(service)
        }

        override fun onDisconnected() {
            printerService = null
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
        }
    }

    override suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit> {
        val service = when (val connection = ensurePrinterService()) {
            is PosResult.Success -> connection.value
            is PosResult.Failure -> return connection
        }

        return try {
            val callback = createNoOpCallback()
            val labels = receiptLabels(document.languageCode)
            val meta = buildReceiptMeta(document, labels)

            invokeIfPresent(service, "printerInit", callback)

            printLogoIfPresent(service, document, callback)
            printCenteredLine(service, document.title, callback)
            printBusinessBlock(service, document, callback, labels)
            printHeaderText(service, document, callback)
            printDivider(service, callback)
            printReceiptLines(service, document.lines, document.currencyCode, callback, labels)
            printDivider(service, callback)
            printTotals(service, document.totals, document.currencyCode, callback, labels)
            printDivider(service, callback)
            printPayments(service, document.payments, document.currencyCode, callback, labels)
            printDivider(service, callback)
            printMeta(service, meta, callback)
            printExtraTextBlocks(service, document, callback)
            printFooter(service, document, callback)
            printBarcodeIfPresent(service, document, callback)

            val wrapped = invokeIfPresent(service, "lineWrap", 3, callback)
            if (!wrapped) {
                invokeIfPresent(service, "printText", "\n\n\n", callback)
            }

            invokeIfPresent(service, "autoOutPaper", callback)
            PosResult.Success(Unit)
        } catch (t: Throwable) {
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
            PosResult.Failure("SUNMI receipt print failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override suspend fun printDiagnosticReceipt(): PosResult<Unit> {
        val diagnostic = ReceiptDocument(
            title = "AIROS TEST",
            lines = listOf(
                ReceiptLine(label = "Device", value = "SUNMI D3 MINI"),
                ReceiptLine(label = "Message", value = "Hello World"),
                ReceiptLine(label = "Path", value = "Receipt path probe"),
            ),
            footer = "If you can read this, the printer path works.",
            footerText = "If you can read this, the printer path works.",
            printedAtEpochMillis = System.currentTimeMillis()
        )
        return printReceipt(diagnostic)
    }

    override suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit> {
        return PosResult.Failure("Kitchen ticket printing is not implemented yet.")
    }

    private suspend fun ensurePrinterService(): PosResult<SunmiInnerPrinterService> {
        printerService?.let { return PosResult.Success(it) }

        return connectMutex.withLock {
            printerService?.let { return@withLock PosResult.Success(it) }

            val deferred = CompletableDeferred<SunmiInnerPrinterService>()
            pendingConnection = deferred

            val bound = try {
                InnerPrinterManager.getInstance().bindService(context.applicationContext, printerCallback)
            } catch (e: InnerPrinterException) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer bind failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (t: Throwable) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer bind crashed: ${t.message ?: t.javaClass.simpleName}")
            }

            if (!bound) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer service bind returned false.")
            }

            val connected = withTimeoutOrNull(4_000) { deferred.await() }
            pendingConnection = null

            if (connected == null) {
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                PosResult.Failure("SUNMI printer service did not connect within timeout.")
            } else {
                availabilityFlow.value = DeviceConnectionState.READY
                PosResult.Success(connected)
            }
        }
    }

    private fun printLogoIfPresent(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
    ) {
        val logo = document.logo ?: return
        val source = logo.source
        if (source.type.name != "DATA_URL") return
        val bitmap = decodeDataUrlBitmap(source.value) ?: return
        invokeIfPresent(service, "setAlignment", 1, callback)
        invokeIfPresent(service, "printBitmap", bitmap, callback)
        invokeIfPresent(service, "printText", "\n", callback)
        invokeIfPresent(service, "setAlignment", 0, callback)
    }

    private fun printHeaderText(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
    ) {
        val headerText = document.headerText?.trim().orEmpty()
        if (headerText.isEmpty()) return
        printCenteredMultiline(service, headerText, callback)
    }

    private fun printBusinessBlock(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
        labels: ReceiptLabels,
    ) {
        val business = document.business ?: return
        val rows = mutableListOf<String>().apply {
            add(business.displayName)
            business.legalName?.takeIf { it.isNotBlank() && it != business.displayName }?.let { add(it) }
            business.businessId?.takeIf { it.isNotBlank() }?.let { add("${labels.businessId}: $it") }
            business.vatId?.takeIf { it.isNotBlank() }?.let { add("${labels.vatId}: $it") }
            addAll(business.addressLines.filter { it.isNotBlank() })
            business.phone?.takeIf { it.isNotBlank() }?.let { add(it) }
            business.email?.takeIf { it.isNotBlank() }?.let { add(it) }
            business.website?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.filter { it.isNotBlank() }

        if (rows.isEmpty()) return
        invokeIfPresent(service, "setAlignment", 1, callback)
        rows.forEach { row -> invokeIfPresent(service, "printText", "$row\n", callback) }
        invokeIfPresent(service, "setAlignment", 0, callback)
    }

    private fun printReceiptLines(
        service: SunmiInnerPrinterService,
        lines: List<ReceiptLine>,
        currencyCode: String,
        callback: InnerResultCallback,
        labels: ReceiptLabels,
    ) {
        lines.forEach { line ->
            val rendered = renderReceiptLine(line, currencyCode)
            rendered.forEach { invokeIfPresent(service, "printText", "$it\n", callback) }
        }
    }

    private fun printTotals(
        service: SunmiInnerPrinterService,
        totals: ReceiptTotals?,
        currencyCode: String,
        callback: InnerResultCallback,
        labels: ReceiptLabels,
    ) {
        if (totals == null) return
        invokeIfPresent(service, "printText", "${labels.subtotal}: ${formatMoney(totals.subtotalCents, currencyCode)}\n", callback)
        if (totals.discountCents != 0) {
            invokeIfPresent(service, "printText", "${labels.discount}: -${formatMoney(totals.discountCents, currencyCode)}\n", callback)
        }
        if (totals.vatBreakdown.isNotEmpty()) {
            totals.vatBreakdown.forEach { row ->
                val rateStr = if (row.ratePercent % 1.0 == 0.0) row.ratePercent.toInt().toString() else row.ratePercent.toString()
                invokeIfPresent(service, "printText", "${labels.tax} $rateStr%: ${formatMoney(row.taxCents, currencyCode)}\n", callback)
            }
        } else if (totals.taxCents != 0) {
            invokeIfPresent(service, "printText", "${labels.tax}: ${formatMoney(totals.taxCents, currencyCode)}\n", callback)
        }
        invokeIfPresent(service, "printText", "${labels.total}: ${formatMoney(totals.totalCents, currencyCode)}\n", callback)
    }

    private fun printPayments(
        service: SunmiInnerPrinterService,
        payments: List<ReceiptPaymentRecord>,
        currencyCode: String,
        callback: InnerResultCallback,
        labels: ReceiptLabels,
    ) {
        payments.forEach { payment ->
            val label = payment.displayLabel?.takeIf { it.isNotBlank() }
                ?: when (payment.method) {
                    PaymentMethod.CASH -> labels.cash
                    PaymentMethod.CARD -> labels.card
                    PaymentMethod.VOUCHER -> labels.voucher
                }
            invokeIfPresent(service, "printText", "$label: ${formatMoney(payment.amountCents, currencyCode)}\n", callback)
        }
    }

    private fun printMeta(
        service: SunmiInnerPrinterService,
        meta: ReceiptMeta,
        callback: InnerResultCallback,
    ) {
        meta.rows.forEach { row -> invokeIfPresent(service, "printText", "$row\n", callback) }
    }

    private fun printExtraTextBlocks(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
    ) {
        val blocks = document.extraTextBlocks
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return
        printDivider(service, callback)
        blocks.forEach { block ->
            printCenteredMultiline(service, block, callback)
        }
    }

    private fun printFooter(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
    ) {
        val footer = document.primaryFooter.trim()
        if (footer.isEmpty()) return
        printDivider(service, callback)
        printCenteredMultiline(service, footer, callback)
    }

    private fun printBarcodeIfPresent(
        service: SunmiInnerPrinterService,
        document: ReceiptDocument,
        callback: InnerResultCallback,
    ) {
        val barcode = document.barcode ?: return
        val barcodeLabel = barcode.label?.takeIf { it.isNotBlank() }
        if (barcodeLabel != null) {
            printDivider(service, callback)
            printCenteredLine(service, barcodeLabel, callback)
        }
        if (barcode.format == ReceiptBarcodeFormat.QR_CODE) {
            invokeIfPresent(service, "setAlignment", 1, callback)
            val printed = invokeIfPresent(service, "printQRCode", barcode.value, 8, 0, callback) ||
                invokeIfPresent(service, "print2DCode", barcode.value, 4, 3, 0, callback)
            if (!printed) {
                invokeIfPresent(service, "printText", "${barcode.value}\n", callback)
            }
            invokeIfPresent(service, "printText", "\n", callback)
            invokeIfPresent(service, "setAlignment", 0, callback)
        }
    }

    private fun printDivider(service: SunmiInnerPrinterService, callback: InnerResultCallback) {
        invokeIfPresent(service, "setAlignment", 0, callback)
        invokeIfPresent(service, "printText", "------------------------------\n", callback)
    }

    private fun printCenteredLine(
        service: SunmiInnerPrinterService,
        text: String,
        callback: InnerResultCallback,
    ) {
        if (text.isBlank()) return
        invokeIfPresent(service, "setAlignment", 1, callback)
        invokeIfPresent(service, "printText", "${text.trim()}\n", callback)
        invokeIfPresent(service, "setAlignment", 0, callback)
    }

    private fun printCenteredMultiline(
        service: SunmiInnerPrinterService,
        text: String,
        callback: InnerResultCallback,
    ) {
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        invokeIfPresent(service, "setAlignment", 1, callback)
        lines.forEach { line -> invokeIfPresent(service, "printText", "$line\n", callback) }
        invokeIfPresent(service, "setAlignment", 0, callback)
    }

    private fun buildReceiptMeta(document: ReceiptDocument, labels: ReceiptLabels): ReceiptMeta {
        val printedAt = document.printedAtEpochMillis
        val date = printedAt?.let { formatReceiptDate(it) }
        val time = printedAt?.let { formatReceiptTime(it) }
        val rows = buildList {
            val tableLabel = document.tableLabel
            val receiptNumber = document.receiptNumber
            val orderNumber = document.orderNumber
            val cashierName = document.cashierName
            if (!tableLabel.isNullOrBlank()) add("${labels.place}: $tableLabel")
            if (!receiptNumber.isNullOrBlank()) add("${labels.receipt}: $receiptNumber")
            if (!orderNumber.isNullOrBlank()) add("${labels.order}: $orderNumber")
            if (!cashierName.isNullOrBlank()) add("${labels.cashier}: $cashierName")
            if (date != null) add("${labels.date}: $date")
            if (time != null) add("${labels.time}: $time")
        }
        return ReceiptMeta(rows)
    }

    private fun renderReceiptLine(line: ReceiptLine, currencyCode: String): List<String> {
        val rendered = mutableListOf<String>()
        val totalPriceCents = line.totalPriceCents
        if (!line.quantity.isNullOrBlank() && totalPriceCents != null) {
            rendered += "${line.quantity} ${line.label}".trim()
            rendered += formatMoney(totalPriceCents, currencyCode)
        } else if (!line.value.isNullOrBlank()) {
            rendered += "${line.label}: ${line.value}"
        } else {
            rendered += line.label
        }
        line.note?.takeIf { it.isNotBlank() }?.let { rendered += "  $it" }
        return rendered
    }

    private fun decodeDataUrlBitmap(value: String): android.graphics.Bitmap? {
        return try {
            val markerIndex = value.indexOf("base64,")
            val payload = if (markerIndex >= 0) value.substring(markerIndex + 7) else value
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatReceiptDate(epochMillis: Long): String {
        return SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(epochMillis))
    }

    private fun formatReceiptTime(epochMillis: Long): String {
        return SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMillis))
    }

    private fun formatMoney(amountCents: Int, currencyCode: String): String {
        val sign = if (amountCents < 0) "-" else ""
        val absCents = kotlin.math.abs(amountCents)
        val major = absCents / 100
        val minor = absCents % 100
        return "$sign$major,${minor.toString().padStart(2, '0')} ${currencySymbol(currencyCode)}"
    }

    private fun currencySymbol(currencyCode: String): String {
        return when (currencyCode.uppercase(Locale.ROOT)) {
            "EUR" -> "€"
            "SEK" -> "kr"
            "USD" -> "$"
            "GBP" -> "£"
            else -> currencyCode.uppercase(Locale.ROOT)
        }
    }

    private fun createNoOpCallback(): InnerResultCallback {
        return object : InnerResultCallback() {
            @Throws(RemoteException::class)
            override fun onRunResult(isSuccess: Boolean) = Unit

            @Throws(RemoteException::class)
            override fun onReturnString(result: String?) = Unit

            @Throws(RemoteException::class)
            override fun onRaiseException(code: Int, msg: String?) = Unit

            @Throws(RemoteException::class)
            override fun onPrintResult(code: Int, msg: String?) = Unit
        }
    }

    private fun invokeIfPresent(
        target: Any,
        methodName: String,
        vararg args: Any,
    ): Boolean {
        val methods = target.javaClass.methods.filter { it.name == methodName }
        if (methods.isEmpty()) return false

        for (method in methods) {
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size != args.size) continue
            if (!parametersMatch(parameterTypes, args)) continue

            method.isAccessible = true
            method.invoke(target, *args)
            return true
        }

        return false
    }

    private fun parametersMatch(
        parameterTypes: Array<Class<*>>,
        args: Array<out Any>,
    ): Boolean {
        return parameterTypes.indices.all { index ->
            val parameterType = parameterTypes[index]
            val arg = args[index]

            when {
                parameterType == Int::class.javaPrimitiveType || parameterType == Int::class.javaObjectType -> arg is Int
                parameterType == Boolean::class.javaPrimitiveType || parameterType == Boolean::class.javaObjectType -> arg is Boolean
                parameterType == Float::class.javaPrimitiveType || parameterType == Float::class.javaObjectType -> arg is Float
                parameterType == Double::class.javaPrimitiveType || parameterType == Double::class.javaObjectType -> arg is Double
                else -> parameterType.isAssignableFrom(arg.javaClass)
            }
        }
    }

    private fun receiptLabels(languageCode: String): ReceiptLabels = when (languageCode.trim().lowercase()) {
        "fi" -> ReceiptLabels(
            place = "Paikka",
            receipt = "Kuitti",
            order = "Tilaus",
            cashier = "Myyjä",
            date = "Päivä",
            time = "Aika",
            subtotal = "Yhteensä",
            discount = "Alennus",
            tax = "ALV",
            total = "YHTEENSÄ",
            businessId = "Y-tunnus",
            vatId = "ALV-tunnus",
            cash = "Käteinen",
            card = "Kortti",
            voucher = "Lahjakortti",
        )
        else -> ReceiptLabels(
            place = "Place",
            receipt = "Receipt",
            order = "Order",
            cashier = "Cashier",
            date = "Date",
            time = "Time",
            subtotal = "Subtotal",
            discount = "Discount",
            tax = "Tax",
            total = "TOTAL",
            businessId = "Business ID",
            vatId = "VAT",
            cash = "Cash",
            card = "Card",
            voucher = "Voucher",
        )
    }

    private data class ReceiptLabels(
        val place: String,
        val receipt: String,
        val order: String,
        val cashier: String,
        val date: String,
        val time: String,
        val subtotal: String,
        val discount: String,
        val tax: String,
        val total: String,
        val businessId: String,
        val vatId: String,
        val cash: String,
        val card: String,
        val voucher: String,
    )

    private data class ReceiptMeta(
        val rows: List<String>,
    )
}
