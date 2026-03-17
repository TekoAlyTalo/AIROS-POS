package com.airos.pos.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.PaymentMethod
import com.airos.pos.core.model.PaymentSummary
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptLine
import com.airos.pos.core.model.RefundRequest
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.device.cashdrawer.CashDrawerService
import com.airos.pos.device.printer.PrinterService
import com.airos.pos.domain.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaymentUiState(
    val ticketId: String,
    val summary: PaymentSummary? = null,
    val printerAvailability: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE,
    val drawerAvailability: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE,
    val message: String? = null,
)

class PaymentViewModel(
    private val ticketId: String,
    private val paymentRepository: PaymentRepository,
    private val printerService: PrinterService,
    private val cashDrawerService: CashDrawerService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PaymentUiState(ticketId = ticketId))
    val uiState: StateFlow<PaymentUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            paymentRepository.observePaymentSummary(ticketId).collect { summary ->
                mutableState.update { it.copy(summary = summary) }
            }
        }
        viewModelScope.launch {
            printerService.availability.collect { availability ->
                mutableState.update { it.copy(printerAvailability = availability) }
            }
        }
        viewModelScope.launch {
            cashDrawerService.availability.collect { availability ->
                mutableState.update { it.copy(drawerAvailability = availability) }
            }
        }
    }

    fun collect(method: PaymentMethod) {
        val summary = mutableState.value.summary ?: return
        viewModelScope.launch {
            when (val result = paymentRepository.collectPayment(ticketId, method, summary.remainingCents)) {
                is PosResult.Success -> mutableState.update { it.copy(summary = result.value, message = "${method.name} payment recorded.") }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message) }
            }
        }
    }

    fun printReceipt() {
        val summary = mutableState.value.summary ?: return
        viewModelScope.launch {
            val result = printerService.printReceipt(
                ReceiptDocument(
                    title = "AIROS Receipt",
                    lines = listOf(
                        ReceiptLine("Ticket", ticketId),
                        ReceiptLine("Due", CentsFormatter.format(summary.totalDueCents)),
                        ReceiptLine("Paid", CentsFormatter.format(summary.paidCents)),
                    ),
                    footer = "TODO-CONTRACT: receipt formatting and fiscal integration",
                ),
            )
            mutableState.update {
                it.copy(message = if (result is PosResult.Success) "Receipt print started." else (result as PosResult.Failure).message)
            }
        }
    }

    fun openCashDrawer() {
        viewModelScope.launch {
            val result = cashDrawerService.openDrawer("payment_screen")
            mutableState.update {
                it.copy(message = if (result is PosResult.Success) "Cash drawer opened." else (result as PosResult.Failure).message)
            }
        }
    }

    companion object {
        fun factory(
            ticketId: String,
            paymentRepository: PaymentRepository,
            printerService: PrinterService,
            cashDrawerService: CashDrawerService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PaymentViewModel(ticketId, paymentRepository, printerService, cashDrawerService) }
        }
    }
}

data class RefundUiState(
    val ticketId: String,
    val summary: PaymentSummary? = null,
    val amountInput: String = "",
    val reasonInput: String = "Customer request",
    val message: String? = null,
)

class RefundViewModel(
    private val ticketId: String,
    private val paymentRepository: PaymentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RefundUiState(ticketId = ticketId))
    val uiState: StateFlow<RefundUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            paymentRepository.observePaymentSummary(ticketId).collect { summary ->
                mutableState.update {
                    it.copy(
                        summary = summary,
                        amountInput = if (it.amountInput.isBlank()) (summary?.paidCents ?: 0).toString() else it.amountInput,
                    )
                }
            }
        }
    }

    fun updateAmount(value: String) {
        mutableState.update { it.copy(amountInput = value) }
    }

    fun updateReason(value: String) {
        mutableState.update { it.copy(reasonInput = value) }
    }

    fun submitRefund() {
        val amount = mutableState.value.amountInput.toIntOrNull()
        if (amount == null) {
            mutableState.update { it.copy(message = "Refund amount must be cents as an integer.") }
            return
        }
        viewModelScope.launch {
            when (
                val result = paymentRepository.refund(
                    RefundRequest(
                        ticketId = ticketId,
                        amountCents = amount,
                        reason = mutableState.value.reasonInput,
                    ),
                )
            ) {
                is PosResult.Success -> mutableState.update { it.copy(message = "Refund request queued.") }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message) }
            }
        }
    }

    companion object {
        fun factory(ticketId: String, paymentRepository: PaymentRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { RefundViewModel(ticketId, paymentRepository) }
        }
    }
}

@Composable
fun PaymentScreen(
    state: PaymentUiState,
    onCollectCash: () -> Unit,
    onCollectCard: () -> Unit,
    onPrintReceipt: () -> Unit,
    onOpenDrawer: () -> Unit,
    onGoToRefund: (String) -> Unit,
) {
    PosPane(
        title = "Payment • ${state.ticketId}",
        supportingText = "Payment collection stays deterministic and device access goes through service abstractions.",
        modifier = Modifier.fillMaxSize(),
    ) {
        state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
        KeyValueRow("Printer", state.printerAvailability.name)
        KeyValueRow("Cash drawer", state.drawerAvailability.name)
        KeyValueRow("Due", state.summary?.totalDueCents?.let(CentsFormatter::format) ?: "-")
        KeyValueRow("Paid", state.summary?.paidCents?.let(CentsFormatter::format) ?: "-")
        KeyValueRow("Remaining", state.summary?.remainingCents?.let(CentsFormatter::format) ?: "-")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCollectCash) { Text("Take cash") }
            Button(onClick = onCollectCard) { Text("Take card") }
            Button(onClick = onPrintReceipt) { Text("Print receipt") }
            Button(onClick = onOpenDrawer) { Text("Open drawer") }
            Button(onClick = { onGoToRefund(state.ticketId) }) { Text("Refund") }
        }
    }
}

@Composable
fun RefundScreen(
    state: RefundUiState,
    onAmountChanged: (String) -> Unit,
    onReasonChanged: (String) -> Unit,
    onSubmitRefund: () -> Unit,
) {
    PosPane(
        title = "Refund • ${state.ticketId}",
        supportingText = "Manager override and fiscal settlement details remain TODO-CONTRACT for the real backend/device flow.",
        modifier = Modifier.fillMaxSize(),
    ) {
        state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = onAmountChanged,
            label = { Text("Refund amount cents") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.reasonInput,
            onValueChange = onReasonChanged,
            label = { Text("Reason") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSubmitRefund) {
            Text("Queue refund")
        }
    }
}
