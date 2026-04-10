package com.airos.pos.feature.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.PaymentEntry
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.TablePaymentRequest
import com.airos.pos.core.model.TablePaymentResult
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.TicketLine
import com.airos.pos.device.platform.CustomerDisplayService
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

private val MenuShellColor = Color(0xFF0D151E)
private val MenuPanelColor = Color(0xFF131E29)
private val MenuPanelAltColor = Color(0xFF182633)
private val MenuPanelAccentColor = Color(0xFF153847)
private val MenuBorderColor = Color(0x14FFFFFF)
private val MenuTextPrimary = Color(0xFFFBFEFF)
private val MenuTextSecondary = Color(0xFFE8F0F6)
private val MenuTextMuted = Color(0xFFC0CCD6)
private val MenuAccentTextColor = Color(0xFF85F5E0)
private val MenuPageTabActiveColor = Color(0xFF235D73)
private val PageTabShape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 8.dp, bottomEnd = 8.dp)

data class ProductGridConfig(
    val rows: Int = 3,
    val columns: Int = 5,
) {
    val itemsPerPage: Int = rows * columns
}

data class MenuUiState(
    val items: List<MenuItem> = emptyList(),
    val gridConfig: ProductGridConfig = ProductGridConfig(),
    val ticketLines: List<MenuTicketLine> = emptyList(),
    val activeTableId: String? = null,
    val activeTableLabel: String? = null,
    val paymentInProgress: Boolean = false,
    val paymentMessage: String? = null,
    val manualDrawerInProgress: Boolean = false,
)

data class MenuTicketLine(
    val itemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val taxRatePercent: Double,
    val discountPercent: Int? = null,
    val discountAmountCents: Int? = null,
)

private object MenuTicketDraftStore {
    private val ticketLinesByTableId = mutableMapOf<String, List<MenuTicketLine>>()

    fun load(tableId: String): List<MenuTicketLine> = ticketLinesByTableId[tableId].orEmpty()

    fun save(tableId: String, lines: List<MenuTicketLine>) {
        ticketLinesByTableId[tableId] = lines
    }

    fun clear(tableId: String) {
        ticketLinesByTableId.remove(tableId)
    }
}

class MenuViewModel(
    private val menuRepository: MenuRepository,
    private val paymentRepository: PaymentRepository,
    private val printReceipt: suspend (ReceiptDocument) -> PosResult<Unit>,
    private val openCashDrawer: suspend (String) -> PosResult<Unit>,
    private val verifyDrawerPin: suspend (String) -> PosResult<Unit>,
    private val customerDisplayService: CustomerDisplayService? = null,
    private val activeTableId: String? = null,
    private val activeTableLabel: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        MenuUiState(
            activeTableId = activeTableId,
            activeTableLabel = activeTableLabel,
            ticketLines = activeTableId?.let(MenuTicketDraftStore::load).orEmpty(),
        ),
    )
    val uiState: StateFlow<MenuUiState> = mutableState.asStateFlow()

    init {
        syncCustomerDisplayToCurrentTicket()
        viewModelScope.launch {
            menuRepository.observeMenuItems().collect { items ->
                mutableState.update { it.copy(items = items) }
            }
        }
    }

    fun syncCustomerDisplayToCurrentTicket() {
        customerDisplayService?.updateCustomerTotalDisplay(currentTicketTotalCentsOrNull())
    }

    fun clearCustomerDisplay() {
        customerDisplayService?.updateCustomerTotalDisplay(null)
    }

    private fun currentTicketTotalCentsOrNull(): Int? {
        return mutableState.value.ticketLines
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.totalCents() }
    }

    fun addToTicket(item: MenuItem) {
        updateTicketLines { currentLines ->
            // A product appears at most once per ticket. When it is already on the ticket,
            // only the quantity is incremented — unitPriceCents is never updated, even if
            // the backend menu price has since changed. This locks the sale price to the
            // moment the line was first opened. The new menu price takes effect only on
            // tickets that don't yet have this product.
            val existingIndex = currentLines.indexOfFirst { it.itemId == item.id }
            if (existingIndex >= 0) {
                currentLines.mapIndexed { index, line ->
                    if (index == existingIndex) {
                        line.copy(quantity = line.quantity + 1)
                    } else {
                        line
                    }
                }
            } else {
                currentLines + MenuTicketLine(
                    itemId = item.id,
                    name = item.name,
                    quantity = 1,
                    unitPriceCents = item.priceCents,
                    taxRatePercent = item.taxRatePercent,
                )
            }
        }
    }

    fun decrementTicketLine(itemId: String) {
        updateTicketLines { currentLines ->
            buildList(currentLines.size) {
                currentLines.forEach { line ->
                    when {
                        line.itemId != itemId -> add(line)
                        line.quantity > 1 -> add(line.copy(quantity = line.quantity - 1))
                    }
                }
            }
        }
    }

    fun removeTicketLine(itemId: String) {
        updateTicketLines { currentLines ->
            currentLines.filterNot { it.itemId == itemId }
        }
    }

    fun applyLinePercentDiscount(itemId: String, percent: Int) {
        updateTicketLines { currentLines ->
            currentLines.map { line ->
                if (line.itemId == itemId) {
                    if (percent <= 0) {
                        line.copy(discountPercent = null, discountAmountCents = null)
                    } else {
                        line.copy(
                            discountPercent = percent.coerceIn(0, 100),
                            discountAmountCents = null,
                        )
                    }
                } else {
                    line
                }
            }
        }
    }

    fun applyLineAmountDiscount(itemId: String, amountCents: Int) {
        updateTicketLines { currentLines ->
            currentLines.map { line ->
                if (line.itemId == itemId) {
                    if (amountCents <= 0) {
                        line.copy(discountPercent = null, discountAmountCents = null)
                    } else {
                        line.copy(
                            discountPercent = null,
                            discountAmountCents = amountCents.coerceAtMost(line.subtotalCents()),
                        )
                    }
                } else {
                    line
                }
            }
        }
    }

    fun submitPayment(result: MenuPaymentDialogResult) {
        val state = mutableState.value
        if (state.ticketLines.isEmpty()) {
            mutableState.update { it.copy(paymentMessage = "Ticket is empty.") }
            return
        }

        mutableState.update {
            it.copy(
                paymentInProgress = true,
                paymentMessage = null,
            )
        }

        viewModelScope.launch {
            val request = buildTablePaymentRequest(result, mutableState.value.ticketLines)
            when (val finalizeResult = paymentRepository.finalizeTablePayment(request)) {
                is PosResult.Success -> {
                    val tableResult = finalizeResult.value
                    val printResult = if (result.shouldPrintReceipt) {
                        printReceipt(tableResult.receiptDocument)
                    } else {
                        null
                    }
                    val drawerResult = if (request.payments.any { it.method == PaymentMethod.CASH }) {
                        openCashDrawer("menu_payment_auto")
                    } else {
                        PosResult.Success(Unit)
                    }

                    clearTicketAfterSuccessfulCheckout()

                    val messageParts = buildList {
                        add(
                            when (printResult) {
                                is PosResult.Success<*> -> "Receipt printed."
                                is PosResult.Failure -> "Payment completed, but receipt print failed: ${printResult.message}"
                                null -> "Receipt printing skipped."
                            },
                        )
                        if (tableResult.changeCents > 0) {
                            add("Change ${CentsFormatter.format(tableResult.changeCents)}.")
                        }
                        if (drawerResult is PosResult.Failure) {
                            add("Cash drawer failed: ${drawerResult.message}")
                        }
                        add("Ticket closed and bill cleared.")
                    }

                    mutableState.update {
                        it.copy(
                            paymentInProgress = false,
                            paymentMessage = messageParts.joinToString(" "),
                        )
                    }
                }

                is PosResult.Failure -> mutableState.update {
                    it.copy(
                        paymentInProgress = false,
                        paymentMessage = finalizeResult.message,
                    )
                }
            }
        }
    }

    fun clearPaymentMessage() {
        mutableState.update { it.copy(paymentMessage = null) }
    }

    fun openCashDrawerManually(pin: String) {
        mutableState.update {
            it.copy(
                manualDrawerInProgress = true,
                paymentMessage = null,
            )
        }

        viewModelScope.launch {
            when (val pinResult = verifyDrawerPin(pin)) {
                is PosResult.Success -> {
                    val result = openCashDrawer("menu_manual")
                    mutableState.update {
                        it.copy(
                            manualDrawerInProgress = false,
                            paymentMessage = when (result) {
                                is PosResult.Success<*> -> "Cash drawer opened."
                                is PosResult.Failure -> "Cash drawer failed: ${result.message}"
                            },
                        )
                    }
                }

                is PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            manualDrawerInProgress = false,
                            paymentMessage = "PIN rejected. ${pinResult.message}",
                        )
                    }
                }
            }
        }
    }

    private fun buildTablePaymentRequest(
        result: MenuPaymentDialogResult,
        lines: List<MenuTicketLine>,
    ): TablePaymentRequest {
        val ticketLines = lines.mapIndexed { index, line ->
            TicketLine(
                id = "${activeTableId ?: "walkin"}-line-$index",
                menuItemId = line.itemId,
                name = line.name,
                quantity = line.quantity,
                unitPriceCents = line.unitPriceCents,
                totalPriceCents = line.totalCents(),
                taxRatePercent = line.taxRatePercent,
            )
        }

        val paymentEntries = buildList {
            when (result.mode) {
                MenuPaymentMode.CASH -> {
                    add(
                        PaymentEntry(
                            method = PaymentMethod.CASH,
                            amountCents = result.finalTotalCents,
                            displayLabel = "Cash",
                        ),
                    )
                }

                MenuPaymentMode.CARD -> {
                    add(
                        PaymentEntry(
                            method = PaymentMethod.CARD,
                            amountCents = result.finalTotalCents,
                            displayLabel = "Card",
                        ),
                    )
                }

                MenuPaymentMode.VOUCHER -> {
                    add(
                        PaymentEntry(
                            method = PaymentMethod.VOUCHER,
                            amountCents = result.finalTotalCents,
                            reference = result.voucherBarcodeValue,
                            displayLabel = "Voucher",
                        ),
                    )
                }

                MenuPaymentMode.SPLIT_PAYMENT -> {
                    result.cashTenderedCents?.takeIf { it > 0 }?.let {
                        add(PaymentEntry(method = PaymentMethod.CASH, amountCents = it, displayLabel = "Cash"))
                    }
                    result.cardAmountCents?.takeIf { it > 0 }?.let {
                        add(PaymentEntry(method = PaymentMethod.CARD, amountCents = it, displayLabel = "Card"))
                    }
                    result.voucherAmountCents?.takeIf { it > 0 }?.let {
                        add(
                            PaymentEntry(
                                method = PaymentMethod.VOUCHER,
                                amountCents = it,
                                reference = result.voucherBarcodeValue,
                                displayLabel = "Voucher",
                            ),
                        )
                    }
                }
            }
        }

        return TablePaymentRequest(
            tableId = activeTableId,
            tableLabel = activeTableLabel,
            lines = ticketLines,
            payments = paymentEntries,
            cashTenderedCents = when (result.mode) {
                MenuPaymentMode.CASH -> result.cashTenderedCents
                MenuPaymentMode.SPLIT_PAYMENT -> result.cashTenderedCents
                else -> null
            },
            voucherBarcodeValue = result.voucherBarcodeValue,
            discountAmountCents = result.billDiscountAmountCents,
            discountLabel = when {
                result.billDiscountPercent != null -> "${result.billDiscountPercent}%"
                result.billDiscountAmountCents > 0 -> CentsFormatter.format(result.billDiscountAmountCents)
                else -> null
            },
        )
    }

    private fun clearTicketAfterSuccessfulCheckout() {
        activeTableId?.let(MenuTicketDraftStore::clear)
        customerDisplayService?.updateCustomerTotalDisplay(null)
        mutableState.update { currentState ->
            currentState.copy(
                ticketLines = emptyList(),
            )
        }
    }

    private fun updateTicketLines(
        transform: (List<MenuTicketLine>) -> List<MenuTicketLine>,
    ) {
        mutableState.update { currentState ->
            val updatedLines = transform(currentState.ticketLines)
            activeTableId?.let { tableId ->
                if (updatedLines.isEmpty()) {
                    MenuTicketDraftStore.clear(tableId)
                } else {
                    MenuTicketDraftStore.save(tableId, updatedLines)
                }
            }
            customerDisplayService?.updateCustomerTotalDisplay(
                updatedLines
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { it.totalCents() },
            )
            currentState.copy(ticketLines = updatedLines)
        }
    }

    companion object {
        fun factory(
            menuRepository: MenuRepository,
            paymentRepository: PaymentRepository,
            printReceipt: suspend (ReceiptDocument) -> PosResult<Unit>,
            openCashDrawer: suspend (String) -> PosResult<Unit>,
            verifyDrawerPin: suspend (String) -> PosResult<Unit>,
            customerDisplayService: CustomerDisplayService? = null,
            activeTableId: String? = null,
            activeTableLabel: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MenuViewModel(
                    menuRepository = menuRepository,
                    paymentRepository = paymentRepository,
                    printReceipt = printReceipt,
                    openCashDrawer = openCashDrawer,
                    verifyDrawerPin = verifyDrawerPin,
                    customerDisplayService = customerDisplayService,
                    activeTableId = activeTableId,
                    activeTableLabel = activeTableLabel,
                )
            }
        }
    }
}

@Composable
fun MenuScreen(
    state: MenuUiState,
    onAddItemToTicket: (MenuItem) -> Unit,
    onDecrementTicketLine: (String) -> Unit,
    onRemoveTicketLine: (String) -> Unit,
    onApplyLinePercentDiscount: (String, Int) -> Unit,
    onApplyLineAmountDiscount: (String, Int) -> Unit,
    onConfirmPayment: (MenuPaymentDialogResult) -> Unit,
    onDismissPaymentMessage: () -> Unit,
    onOpenCashDrawer: (String) -> Unit,
    onScreenShown: () -> Unit = {},
    onScreenDisposed: () -> Unit = {},
) {
    DisposableEffect(Unit) {
        onScreenShown()
        onDispose(onScreenDisposed)
    }

    val categoryGroups = remember(state.items) { buildCategoryGroups(state.items) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val activePageByCategory = remember { mutableStateMapOf<String, Int>() }
    var selectedSubcategory by rememberSaveable { mutableStateOf<String?>(null) }

    val currentGroup = categoryGroups.firstOrNull { it.name == selectedCategory } ?: categoryGroups.firstOrNull()
    val gridConfig = (currentGroup?.gridConfig ?: state.gridConfig).sanitized()
    val currentCategory = currentGroup?.name
    val subcategories = remember(currentGroup) {
        currentGroup?.items
            ?.mapNotNull { it.subcategory?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }
    val showSubcategoryPicker = subcategories.isNotEmpty() && selectedSubcategory == null
    val filteredItems = remember(currentGroup, selectedSubcategory, subcategories) {
        val base = currentGroup?.items.orEmpty()
        when {
            subcategories.isEmpty() -> base
            selectedSubcategory != null -> base.filter { it.subcategory == selectedSubcategory }
            else -> emptyList()
        }
    }
    val pageCount = if (filteredItems.isEmpty()) 0 else maxOf(
        ((filteredItems.size + gridConfig.itemsPerPage - 1) / gridConfig.itemsPerPage).coerceAtLeast(1),
        minimumPageCountForCategory(currentCategory),
    )
    val requestedPage = currentCategory?.let { activePageByCategory[it] } ?: 0
    val activePageIndex = if (pageCount == 0) 0 else requestedPage.coerceIn(0, pageCount - 1)
    val activePageItems = filteredItems
        .drop(activePageIndex * gridConfig.itemsPerPage)
        .take(gridConfig.itemsPerPage)
    val pageSlots = List(gridConfig.itemsPerPage) { index -> activePageItems.getOrNull(index) }
    val totalTicketItems = state.ticketLines.sumOf { it.quantity }
    val ticketSubtotalCents = state.ticketLines.sumOf { it.totalCents() }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1.55f),
            shape = RoundedCornerShape(28.dp),
            color = MenuShellColor,
            border = BorderStroke(1.dp, MenuBorderColor),
            contentColor = MenuTextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Products",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MenuTextPrimary,
                )

                if (categoryGroups.isEmpty()) {
                    EmptyWorkspaceState()
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        categoryGroups.forEach { group ->
                            ProductGroupChip(
                                label = group.name,
                                selected = group.name == currentCategory,
                                onClick = {
                                    if (group.name == currentCategory) {
                                        selectedSubcategory = null
                                    } else {
                                        selectedCategory = group.name
                                        selectedSubcategory = null
                                    }
                                },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (showSubcategoryPicker) {
                            SubcategoryPickerGrid(
                                modifier = Modifier.weight(1f),
                                subcategories = subcategories,
                                onSelectSubcategory = { sub ->
                                    selectedSubcategory = sub
                                    currentCategory?.let { activePageByCategory[it] = 0 }
                                },
                            )
                        } else {
                            ProductGrid(
                                modifier = Modifier.weight(1f),
                                config = gridConfig,
                                slots = pageSlots,
                                onSelectItem = onAddItemToTicket,
                            )
                            PageRail(
                                pageCount = pageCount,
                                activePageIndex = activePageIndex,
                                onSelectPage = { pageIndex ->
                                    currentCategory?.let { category ->
                                        activePageByCategory[category] = pageIndex
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        TicketPane(
            activeTableId = state.activeTableId,
            activeTableLabel = state.activeTableLabel,
            ticketLines = state.ticketLines,
            totalTicketItems = totalTicketItems,
            ticketSubtotalCents = ticketSubtotalCents,
            paymentInProgress = state.paymentInProgress,
            paymentMessage = state.paymentMessage,
            manualDrawerInProgress = state.manualDrawerInProgress,
            onDecrementTicketLine = onDecrementTicketLine,
            onRemoveTicketLine = onRemoveTicketLine,
            onApplyLinePercentDiscount = onApplyLinePercentDiscount,
            onApplyLineAmountDiscount = onApplyLineAmountDiscount,
            onConfirmPayment = onConfirmPayment,
            onDismissPaymentMessage = onDismissPaymentMessage,
            onOpenCashDrawer = onOpenCashDrawer,
        )
    }
}

@Composable
private fun EmptyWorkspaceState() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = MenuPanelColor,
        border = BorderStroke(1.dp, MenuBorderColor),
        contentColor = MenuTextPrimary,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No menu items available yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MenuTextSecondary,
            )
        }
    }
}

@Composable
private fun ProductGroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MenuPanelAccentColor else MenuPanelAltColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MenuBorderColor,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MenuAccentTextColor else MenuTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProductGrid(
    modifier: Modifier = Modifier,
    config: ProductGridConfig,
    slots: List<MenuItem?>,
    onSelectItem: (MenuItem) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(config.rows) { rowIndex ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(config.columns) { columnIndex ->
                    val slotIndex = (rowIndex * config.columns) + columnIndex
                    val item = slots.getOrNull(slotIndex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        if (item == null) {
                            EmptyProductCard()
                        } else {
                            ProductCard(
                                item = item,
                                onClick = { onSelectItem(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubcategoryPickerGrid(
    modifier: Modifier = Modifier,
    subcategories: List<String>,
    onSelectSubcategory: (String) -> Unit,
) {
    val config = ProductGridConfig(rows = 3, columns = 3)
    val slots = List(config.itemsPerPage) { index -> subcategories.getOrNull(index) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(config.rows) { rowIndex ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(config.columns) { columnIndex ->
                    val slotIndex = (rowIndex * config.columns) + columnIndex
                    val sub = slots.getOrNull(slotIndex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        if (sub == null) {
                            EmptyProductCard()
                        } else {
                            SubcategoryCard(
                                label = sub,
                                onClick = { onSelectSubcategory(sub) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubcategoryCard(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MenuPanelColor,
        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
        contentColor = MenuTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MenuPanelAltColor,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MenuTextSecondary,
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MenuTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProductCard(
    item: MenuItem,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MenuPanelColor,
        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
        contentColor = MenuTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MenuPanelAltColor,
            ) {
                ProductImage(
                    imageUrl = item.imageUrl,
                    contentDescription = item.name,
                    fallbackLabel = item.name.take(2).uppercase(),
                )
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MenuTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProductImage(
    imageUrl: String?,
    contentDescription: String,
    fallbackLabel: String,
) {
    if (!imageUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                else -> ProductImageFallback(fallbackLabel)
            }
        }
    } else {
        ProductImageFallback(fallbackLabel)
    }
}

@Composable
private fun ProductImageFallback(fallbackLabel: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MenuShellColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fallbackLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MenuTextSecondary,
        )
    }
}

@Composable
private fun EmptyProductCard() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = MenuShellColor,
        border = BorderStroke(1.dp, Color(0x0FFFFFFF)),
        contentColor = MenuTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MenuPanelAltColor.copy(alpha = 0.45f),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MenuTextMuted.copy(alpha = 0.42f),
                    )
                }
            }

            Text(
                text = " ",
                modifier = Modifier.height(20.dp),
            )

            Text(
                text = " ",
                modifier = Modifier.height(18.dp),
            )
        }
    }
}

@Composable
private fun PageRail(
    pageCount: Int,
    activePageIndex: Int,
    onSelectPage: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(60.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(pageCount) { pageIndex ->
            val selected = pageIndex == activePageIndex
            Surface(
                modifier = Modifier
                    .padding(start = if (selected) 0.dp else 6.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clickable { onSelectPage(pageIndex) },
                shape = PageTabShape,
                color = if (selected) MenuPageTabActiveColor else MenuPanelAltColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) MenuAccentTextColor.copy(alpha = 0.5f) else MenuBorderColor,
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (pageIndex + 1).toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MenuAccentTextColor else MenuTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TicketPane(
    activeTableId: String?,
    activeTableLabel: String?,
    ticketLines: List<MenuTicketLine>,
    totalTicketItems: Int,
    ticketSubtotalCents: Int,
    paymentInProgress: Boolean,
    paymentMessage: String?,
    manualDrawerInProgress: Boolean,
    onDecrementTicketLine: (String) -> Unit,
    onRemoveTicketLine: (String) -> Unit,
    onApplyLinePercentDiscount: (String, Int) -> Unit,
    onApplyLineAmountDiscount: (String, Int) -> Unit,
    onConfirmPayment: (MenuPaymentDialogResult) -> Unit,
    onDismissPaymentMessage: () -> Unit,
    onOpenCashDrawer: (String) -> Unit,
) {
    var isPaymentDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isDrawerPinDialogOpen by rememberSaveable { mutableStateOf(false) }
    var selectedActionLineId by rememberSaveable { mutableStateOf<String?>(null) }
    var discountEditor by remember { mutableStateOf<LineDiscountEditorState?>(null) }
    val selectedActionLine = ticketLines.firstOrNull { it.itemId == selectedActionLineId }
    val listState = rememberLazyListState()
    var previousTicketLines by remember { mutableStateOf(emptyList<MenuTicketLine>()) }
    val showUpHint by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val showDownHint by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val lastVisibleItemClipped = lastVisibleItem.offset + lastVisibleItem.size > layoutInfo.viewportEndOffset
            lastVisibleItem.index < layoutInfo.totalItemsCount - 1 || lastVisibleItemClipped
        }
    }

    LaunchedEffect(ticketLines) {
        findTicketAutoFollowIndex(previousTicketLines, ticketLines)?.let { targetIndex ->
            listState.animateScrollToItem(targetIndex)
        }
        previousTicketLines = ticketLines
    }

    Surface(
        modifier = Modifier.weight(0.85f),
        shape = RoundedCornerShape(28.dp),
        color = MenuShellColor,
        border = BorderStroke(1.dp, MenuBorderColor),
        contentColor = MenuTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = buildReceiptTitle(activeTableId = activeTableId, activeTableLabel = activeTableLabel),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MenuTextPrimary,
                )
                if (!activeTableLabel.isNullOrBlank() || !activeTableId.isNullOrBlank()) {
                    Text(
                        text = buildReceiptSubtitle(
                            activeTableId = activeTableId,
                            activeTableLabel = activeTableLabel,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MenuTextSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MenuBorderColor),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (ticketLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = buildEmptyReceiptMessage(activeTableId = activeTableId, activeTableLabel = activeTableLabel),
                            style = MaterialTheme.typography.titleLarge,
                            color = MenuTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        ReceiptScrollHint(
                            visible = showUpHint,
                            direction = ReceiptScrollHintDirection.UP,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 1.dp, bottom = 1.dp),
                        )

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            itemsIndexed(
                                items = ticketLines,
                                key = { _, line -> line.itemId },
                            ) { _, line ->
                                TicketLineRow(
                                    line = line,
                                    onClick = { onDecrementTicketLine(line.itemId) },
                                    onLongPress = { selectedActionLineId = line.itemId },
                                )
                            }
                        }

                        ReceiptScrollHint(
                            visible = showDownHint,
                            direction = ReceiptScrollHintDirection.DOWN,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 1.dp, bottom = 1.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MenuBorderColor),
                )
                MenuKeyValueRow("Items", totalTicketItems.toString())
                MenuKeyValueRow("Lines", ticketLines.size.toString())
                MenuKeyValueRow("Subtotal", CentsFormatter.format(ticketSubtotalCents), emphasized = true)
                paymentMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (paymentInProgress) MenuTextSecondary else MenuAccentTextColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDismissPaymentMessage),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { isDrawerPinDialogOpen = true },
                        modifier = Modifier.weight(0.95f),
                        enabled = !paymentInProgress && !manualDrawerInProgress,
                    ) {
                        Text(
                            text = if (manualDrawerInProgress) "Avaan..." else "Avaa laatikko",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = { isPaymentDialogOpen = true },
                        modifier = Modifier.weight(1.35f),
                        enabled = ticketLines.isNotEmpty() && !paymentInProgress,
                    ) {
                        Text(
                            text = "Maksa",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (isPaymentDialogOpen) {
                MenuPaymentDialog(
                    subtotalCents = ticketSubtotalCents,
                    paymentContextLabel = when {
                        !activeTableLabel.isNullOrBlank() -> activeTableLabel
                        !activeTableId.isNullOrBlank() -> activeTableId
                        else -> "Bar"
                    },
                    onDismiss = { isPaymentDialogOpen = false },
                    onConfirm = { result ->
                        isPaymentDialogOpen = false
                        onConfirmPayment(result)
                    },
                )
            }

            if (isDrawerPinDialogOpen) {
                CashDrawerPinDialog(
                    inProgress = manualDrawerInProgress,
                    onDismiss = { isDrawerPinDialogOpen = false },
                    onConfirm = { pin ->
                        isDrawerPinDialogOpen = false
                        onOpenCashDrawer(pin)
                    },
                )
            }

            selectedActionLine?.let { line ->
                LineActionsDialog(
                    line = line,
                    onDismiss = { selectedActionLineId = null },
                    onDiscountPercent = {
                        discountEditor = LineDiscountEditorState(
                            itemId = line.itemId,
                            lineName = line.name,
                            unitPriceCents = line.unitPriceCents,
                            lineTotalCents = line.subtotalCents(),
                            mode = LineDiscountMode.PERCENT,
                            initialValue = line.discountPercent?.toString().orEmpty(),
                        )
                        selectedActionLineId = null
                    },
                    onDiscountAmount = {
                        discountEditor = LineDiscountEditorState(
                            itemId = line.itemId,
                            lineName = line.name,
                            unitPriceCents = line.unitPriceCents,
                            lineTotalCents = line.subtotalCents(),
                            mode = LineDiscountMode.AMOUNT,
                            initialValue = line.discountAmountCents?.let(::formatDiscountAmountInput).orEmpty(),
                        )
                        selectedActionLineId = null
                    },
                    onRemoveLine = {
                        onRemoveTicketLine(line.itemId)
                        selectedActionLineId = null
                    },
                )
            }

            discountEditor?.let { editor ->
                DiscountEntryDialog(
                    editor = editor,
                    onDismiss = { discountEditor = null },
                    onConfirm = { input ->
                        when (editor.mode) {
                            LineDiscountMode.PERCENT -> {
                                parsePercentDiscount(input)?.let { percent ->
                                    onApplyLinePercentDiscount(editor.itemId, percent)
                                    discountEditor = null
                                }
                            }

                            LineDiscountMode.AMOUNT -> {
                                parseEuroDiscountToCents(input)?.let { amountCents ->
                                    onApplyLineAmountDiscount(editor.itemId, amountCents)
                                    discountEditor = null
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TicketLineRow(
    line: MenuTicketLine,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(line.itemId) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${line.quantity}x",
            modifier = Modifier
                .width(34.dp)
                .padding(top = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MenuAccentTextColor,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = line.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MenuTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildLineMetaText(line),
                style = MaterialTheme.typography.bodySmall,
                color = MenuTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "Total",
                style = MaterialTheme.typography.bodySmall,
                color = MenuTextMuted,
            )
            if (line.hasDiscount()) {
                Text(
                    text = CentsFormatter.format(line.subtotalCents()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MenuTextMuted,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
            Text(
                text = CentsFormatter.format(line.totalCents()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MenuAccentTextColor,
            )
        }
    }
}

private enum class ReceiptScrollHintDirection {
    UP,
    DOWN,
}

@Composable
private fun ReceiptScrollHint(
    visible: Boolean,
    direction: ReceiptScrollHintDirection,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Canvas(modifier = modifier.size(width = 18.dp, height = 18.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val color = Color.White.copy(alpha = 0.92f)
        val left = size.width * 0.22f
        val center = size.width * 0.5f
        val right = size.width * 0.78f
        val chevronHeight = size.height * 0.18f
        val tops = listOf(size.height * 0.18f, size.height * 0.48f)

        tops.forEach { top ->
            val apexY = if (direction == ReceiptScrollHintDirection.UP) top else top + chevronHeight
            val baseY = if (direction == ReceiptScrollHintDirection.UP) top + chevronHeight else top

            drawLine(
                color = color,
                start = Offset(left, baseY),
                end = Offset(center, apexY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(center, apexY),
                end = Offset(right, baseY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CashDrawerPinDialog(
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    val canSubmit = pin.length >= 4 && !inProgress

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MenuPanelColor,
            border = BorderStroke(1.dp, MenuBorderColor),
            contentColor = MenuTextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Avaa kassalaatikko",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MenuTextPrimary,
                )
                Text(
                    text = "Syötä sisäänkirjautuneen käyttäjän PIN-koodi ennen laatikon avausta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MenuTextSecondary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MenuShellColor,
                    border = BorderStroke(1.dp, MenuBorderColor),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "PIN",
                            style = MaterialTheme.typography.labelLarge,
                            color = MenuTextMuted,
                        )
                        Text(
                            text = if (pin.isBlank()) "• • • •" else List(pin.length) { "•" }.joinToString(" "),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MenuAccentTextColor,
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("Tyhjennä", "0", "⌫"),
                    ).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { key ->
                                Button(
                                    onClick = {
                                        pin = when (key) {
                                            "Tyhjennä" -> ""
                                            "⌫" -> pin.dropLast(1)
                                            else -> if (pin.length < 8) pin + key else pin
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !inProgress,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MenuPanelAltColor,
                                        contentColor = MenuTextPrimary,
                                        disabledContainerColor = MenuPanelAltColor.copy(alpha = 0.45f),
                                        disabledContentColor = MenuTextPrimary.copy(alpha = 0.45f),
                                    ),
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !inProgress,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MenuShellColor,
                            contentColor = MenuTextSecondary,
                        ),
                    ) {
                        Text("Peru")
                    }
                    Button(
                        onClick = { onConfirm(pin) },
                        modifier = Modifier.weight(1f),
                        enabled = canSubmit,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(if (inProgress) "Avaan..." else "Avaa laatikko")
                    }
                }
            }
        }
    }
}


@Composable
private fun LineActionsDialog(
    line: MenuTicketLine,
    onDismiss: () -> Unit,
    onDiscountPercent: () -> Unit,
    onDiscountAmount: () -> Unit,
    onRemoveLine: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MenuPanelColor,
            border = BorderStroke(1.dp, MenuBorderColor),
            contentColor = MenuTextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MenuTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ActionDialogButton(
                    label = "Discount %",
                    onClick = onDiscountPercent,
                )
                ActionDialogButton(
                    label = "Discount €",
                    onClick = onDiscountAmount,
                )
                ActionDialogButton(
                    label = "Remove line",
                    onClick = onRemoveLine,
                    danger = true,
                )
                ActionDialogButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun DiscountEntryDialog(
    editor: LineDiscountEditorState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(editor.itemId, editor.mode) { mutableStateOf(editor.initialValue) }
    val preview = remember(editor, value) { calculateDiscountPreview(editor, value) }
    val isConfirmEnabled = when (editor.mode) {
        LineDiscountMode.PERCENT -> parsePercentDiscount(value) != null
        LineDiscountMode.AMOUNT -> parseEuroDiscountToCents(value) != null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MenuPanelColor,
            border = BorderStroke(1.dp, MenuBorderColor),
            contentColor = MenuTextPrimary,
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
                    color = MenuTextPrimary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MenuShellColor,
                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = editor.lineName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MenuTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DiscountInfoRow(
                            label = "Unit price",
                            value = CentsFormatter.format(editor.unitPriceCents),
                        )
                        DiscountInfoRow(
                            label = "Line total",
                            value = CentsFormatter.format(editor.lineTotalCents),
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MenuShellColor,
                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = editor.mode.inputLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MenuTextMuted,
                        )
                        Text(
                            text = formatDiscountDisplayValue(value, editor.mode),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MenuTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DiscountInfoRow(
                            label = "Original amount",
                            value = CentsFormatter.format(preview.originalLineAmountCents),
                        )
                        DiscountInfoRow(
                            label = "Entered discount",
                            value = CentsFormatter.format(preview.enteredDiscountCents),
                        )
                        DiscountInfoRow(
                            label = "Discounted total",
                            value = CentsFormatter.format(preview.discountedTotalCents),
                            emphasized = true,
                        )
                    }
                }
                DiscountKeypad(
                    mode = editor.mode,
                    value = value,
                    onDigit = { digit -> value = appendDiscountDigit(value, digit, editor.mode) },
                    onDecimal = { value = appendDiscountDecimal(value, editor.mode) },
                    onBackspace = { value = value.dropLast(1) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionDialogButton(
                        label = "Cancel",
                        onClick = onDismiss,
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                    ActionDialogButton(
                        label = "OK",
                        onClick = { onConfirm(value) },
                        modifier = Modifier.weight(1f),
                        enabled = isConfirmEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscountInfoRow(
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
            style = MaterialTheme.typography.bodySmall,
            color = MenuTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MenuAccentTextColor else MenuTextSecondary,
        )
    }
}

@Composable
private fun DiscountKeypad(
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
                    KeypadButton(
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
private fun KeypadButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) MenuPanelAltColor else MenuPanelAltColor.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (enabled) Color(0x18FFFFFF) else Color(0x08FFFFFF)),
        contentColor = if (enabled) MenuTextPrimary else MenuTextMuted.copy(alpha = 0.45f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
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
private fun ActionDialogButton(
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFF5B2020),
                contentColor = MenuTextPrimary,
            )
        } else if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MenuPanelAccentColor,
                contentColor = MenuTextPrimary,
                disabledContainerColor = MenuPanelAccentColor.copy(alpha = 0.38f),
                disabledContentColor = MenuTextMuted,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MenuPanelAltColor,
                contentColor = MenuTextPrimary,
                disabledContainerColor = MenuPanelAltColor.copy(alpha = 0.38f),
                disabledContentColor = MenuTextMuted,
            )
        },
        border = BorderStroke(1.dp, if (danger) Color(0x33FF8B8B) else Color(0x18FFFFFF)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MenuKeyValueRow(
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
            style = MaterialTheme.typography.bodyLarge,
            color = MenuTextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MenuAccentTextColor else MenuTextPrimary,
        )
    }
}


private fun buildReceiptTitle(
    activeTableId: String?,
    activeTableLabel: String?,
): String {
    val resolvedLabel = activeTableLabel?.takeIf { it.isNotBlank() } ?: activeTableId
    return if (resolvedLabel.isNullOrBlank()) {
        "Receipt"
    } else {
        "Receipt • $resolvedLabel"
    }
}

private fun buildReceiptSubtitle(
    activeTableId: String?,
    activeTableLabel: String?,
): String {
    return when {
        !activeTableLabel.isNullOrBlank() && !activeTableId.isNullOrBlank() && activeTableLabel != activeTableId ->
            "Active table: $activeTableLabel ($activeTableId)"
        !activeTableLabel.isNullOrBlank() -> "Active table: $activeTableLabel"
        !activeTableId.isNullOrBlank() -> "Active table: $activeTableId"
        else -> ""
    }
}

private fun buildEmptyReceiptMessage(
    activeTableId: String?,
    activeTableLabel: String?,
): String {
    val resolvedLabel = activeTableLabel?.takeIf { it.isNotBlank() } ?: activeTableId
    return if (resolvedLabel.isNullOrBlank()) {
        "Tap a product tile to start this ticket."
    } else {
        "Tap a product tile to start the receipt for $resolvedLabel."
    }
}

private data class MenuCategoryGroup(
    val name: String,
    val items: List<MenuItem>,
    val gridConfig: ProductGridConfig? = null,
)

private fun buildCategoryGroups(items: List<MenuItem>): List<MenuCategoryGroup> {
    val visibleItems = items.filterNot(::isHiddenMenuItem)

    return visibleItems
        .groupBy { item -> item.category.ifBlank { "Other" } }
        .toList()
        .sortedBy { (category, _) -> category }
        .map { (category, categoryItems) ->
            MenuCategoryGroup(
                name = category,
                items = categoryItems.sortedBy { it.name },
                gridConfig = when {
                    category.equals("Desserts", ignoreCase = true) -> ProductGridConfig(rows = 2, columns = 4)
                    else -> null
                },
            )
        }
}

private fun minimumPageCountForCategory(category: String?): Int {
    return if (category.equals("Bar", ignoreCase = true)) 2 else 1
}

private fun ProductGridConfig.sanitized(): ProductGridConfig {
    val safeRows = rows.coerceIn(2, 3)
    val safeColumns = columns.coerceIn(2, 5)
    return if (safeRows == rows && safeColumns == columns) {
        this
    } else {
        ProductGridConfig(
            rows = safeRows,
            columns = safeColumns,
        )
    }
}

private enum class LineDiscountMode(
    val dialogTitle: String,
    val inputLabel: String,
) {
    PERCENT(
        dialogTitle = "Discount %",
        inputLabel = "Percent",
    ),
    AMOUNT(
        dialogTitle = "Discount €",
        inputLabel = "Amount in euros",
    ),
}

private data class LineDiscountEditorState(
    val itemId: String,
    val lineName: String,
    val unitPriceCents: Int,
    val lineTotalCents: Int,
    val mode: LineDiscountMode,
    val initialValue: String,
) {
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<LineDiscountEditorState, String>(
            save = {
                listOf(
                    it.itemId,
                    it.lineName,
                    it.unitPriceCents.toString(),
                    it.lineTotalCents.toString(),
                    it.mode.name,
                    it.initialValue,
                )
            },
            restore = {
                LineDiscountEditorState(
                    itemId = it[0],
                    lineName = it[1],
                    unitPriceCents = it[2].toInt(),
                    lineTotalCents = it[3].toInt(),
                    mode = LineDiscountMode.valueOf(it[4]),
                    initialValue = it[5],
                )
            },
        )
    }
}

private fun MenuTicketLine.subtotalCents(): Int = quantity * unitPriceCents

private fun MenuTicketLine.discountCents(): Int {
    val subtotal = subtotalCents()
    return when {
        discountPercent != null -> ((subtotal * discountPercent.coerceIn(0, 100)) / 100).coerceIn(0, subtotal)
        discountAmountCents != null -> discountAmountCents.coerceIn(0, subtotal)
        else -> 0
    }
}

private fun MenuTicketLine.totalCents(): Int = (subtotalCents() - discountCents()).coerceAtLeast(0)

private fun MenuTicketLine.hasDiscount(): Boolean = discountCents() > 0

private fun MenuTicketLine.discountLabel(): String? {
    return when {
        discountPercent != null && discountPercent > 0 -> "-${discountPercent}%"
        discountAmountCents != null && discountAmountCents > 0 -> "-${CentsFormatter.format(discountCents())}"
        else -> null
    }
}

private fun buildLineMetaText(line: MenuTicketLine): String {
    val base = "Unit ${CentsFormatter.format(line.unitPriceCents)}"
    val discountLabel = line.discountLabel() ?: return base
    return "$base • $discountLabel"
}

private data class DiscountPreview(
    val originalLineAmountCents: Int,
    val enteredDiscountCents: Int,
    val discountedTotalCents: Int,
)

private fun calculateDiscountPreview(
    editor: LineDiscountEditorState,
    rawValue: String,
): DiscountPreview {
    val originalLineAmountCents = editor.lineTotalCents.coerceAtLeast(0)
    val enteredDiscountCents = when (editor.mode) {
        LineDiscountMode.PERCENT -> {
            parsePercentDiscount(rawValue)
                ?.coerceIn(0, 100)
                ?.let { percent -> (originalLineAmountCents * percent) / 100 }
                ?: 0
        }

        LineDiscountMode.AMOUNT -> {
            parseEuroDiscountToCents(rawValue)
                ?.coerceIn(0, originalLineAmountCents)
                ?: 0
        }
    }

    return DiscountPreview(
        originalLineAmountCents = originalLineAmountCents,
        enteredDiscountCents = enteredDiscountCents,
        discountedTotalCents = (originalLineAmountCents - enteredDiscountCents).coerceAtLeast(0),
    )
}

private fun findTicketAutoFollowIndex(
    previousLines: List<MenuTicketLine>,
    currentLines: List<MenuTicketLine>,
): Int? {
    if (previousLines.isEmpty() || currentLines.isEmpty()) return null

    val previousById = previousLines.associateBy { it.itemId }
    val targetIndex = currentLines.indexOfFirst { line ->
        val previous = previousById[line.itemId]
        previous == null || line.quantity > previous.quantity
    }
    return targetIndex.takeIf { it >= 0 }
}

private fun formatDiscountDisplayValue(
    value: String,
    mode: LineDiscountMode,
): String {
    if (value.isBlank()) {
        return when (mode) {
            LineDiscountMode.PERCENT -> "0 %"
            LineDiscountMode.AMOUNT -> "0,00 €"
        }
    }

    return when (mode) {
        LineDiscountMode.PERCENT -> "${value.trimStart('0').ifBlank { "0" }} %"
        LineDiscountMode.AMOUNT -> "${value.replace('.', ',')} €"
    }
}

private fun appendDiscountDigit(
    current: String,
    digit: String,
    mode: LineDiscountMode,
): String {
    if (digit !in "0".."9") return current

    return when (mode) {
        LineDiscountMode.PERCENT -> {
            val sanitized = current.filter(Char::isDigit)
            (if (sanitized == "0") digit else sanitized + digit).take(3)
        }

        LineDiscountMode.AMOUNT -> {
            val sanitized = current.replace('.', ',')
            val decimalIndex = sanitized.indexOf(',')
            if (decimalIndex >= 0 && sanitized.length - decimalIndex > 2) {
                return sanitized
            }
            if (sanitized == "0") digit else sanitized + digit
        }
    }
}

private fun appendDiscountDecimal(
    current: String,
    mode: LineDiscountMode,
): String {
    if (mode != LineDiscountMode.AMOUNT) return current
    if (current.contains(',') || current.contains('.')) return current
    return if (current.isBlank()) "0," else "$current,"
}

private fun parsePercentDiscount(raw: String): Int? {
    return raw.trim()
        .removeSuffix("%")
        .toIntOrNull()
        ?.coerceIn(0, 100)
}

private fun parseEuroDiscountToCents(raw: String): Int? {
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

private fun formatDiscountAmountInput(amountCents: Int): String {
    return BigDecimal(amountCents).movePointLeft(2).stripTrailingZeros().toPlainString()
}

private fun isHiddenMenuItem(item: MenuItem): Boolean {
    return item.name.equals("Manual Discount", ignoreCase = true)
}
