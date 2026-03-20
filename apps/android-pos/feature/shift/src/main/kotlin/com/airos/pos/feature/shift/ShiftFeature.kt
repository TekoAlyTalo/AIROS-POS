package com.airos.pos.feature.shift

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
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.ShiftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShiftUiState(
    val currentShift: PosShift? = null,
    val openingFloatInput: String = "5000",
    val countedCashInput: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

class ShiftViewModel(
    private val shiftRepository: ShiftRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            shiftRepository.observeCurrentShift().collect { shift ->
                mutableState.update { it.copy(currentShift = shift, busy = false) }
            }
        }
    }

    fun updateOpeningFloat(value: String) {
        mutableState.update { it.copy(openingFloatInput = value) }
    }

    fun updateCountedCash(value: String) {
        mutableState.update { it.copy(countedCashInput = value) }
    }

    fun openShift(staffId: String) {
        val amount = mutableState.value.openingFloatInput.toIntOrNull()
        if (amount == null) {
            mutableState.update { it.copy(message = "Opening float must be cents as an integer.") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = shiftRepository.openShift(amount, staffId)) {
                is PosResult.Success -> mutableState.update { it.copy(currentShift = result.value, busy = false) }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    fun closeShift() {
        val amount = mutableState.value.countedCashInput.toIntOrNull()
        if (amount == null) {
            mutableState.update { it.copy(message = "Counted cash must be cents as an integer.") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = shiftRepository.closeShift(amount)) {
                is PosResult.Success -> mutableState.update { it.copy(currentShift = result.value, busy = false) }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    companion object {
        fun factory(shiftRepository: ShiftRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShiftViewModel(shiftRepository) }
        }
    }
}

@Composable
fun ShiftScreen(
    state: ShiftUiState,
    currentStaffId: String?,
    onOpeningFloatChanged: (String) -> Unit,
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String) -> Unit,
    onCloseShift: () -> Unit,
    customerDisplayProbeStatus: String?,
    isCustomerDisplayProbeFailure: Boolean,
    onRunCustomerDisplayProbe: () -> Unit,
    receiptPrinterProbeStatus: String?,
    isReceiptPrinterProbeFailure: Boolean,
    onRunReceiptPrinterProbe: () -> Unit,
    scannerProbeStatus: String?,
    isScannerProbeFailure: Boolean,
    scannerAvailabilityLabel: String,
    scannerPackageLabel: String,
    scannerServiceBindLabel: String,
    scanManagerBindLabel: String,
    broadcastStatusLabel: String,
    scannerLastError: String?,
    lastScannerValue: String?,
    onStartScannerProbe: () -> Unit,
    onStopScannerProbe: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PosPane(
            title = "Shift control",
            supportingText = "Open and close the cash shift with deterministic local records.",
            modifier = Modifier.weight(1f),
        ) {
            state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = state.openingFloatInput,
                onValueChange = onOpeningFloatChanged,
                label = { Text("Opening float cents") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { currentStaffId?.let(onOpenShift) },
                enabled = !state.busy && currentStaffId != null,
            ) {
                Text("Open shift")
            }
            OutlinedTextField(
                value = state.countedCashInput,
                onValueChange = onCountedCashChanged,
                label = { Text("Counted cash cents") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onCloseShift, enabled = !state.busy && state.currentShift != null) {
                Text("Close shift")
            }
            Text(
                text = "Developer / Device Diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Customer display probe",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(onClick = onRunCustomerDisplayProbe) {
                Text("Run customer display probe")
            }
            customerDisplayProbeStatus?.let { status ->
                StatusBanner(
                    text = status,
                    tint = if (isCustomerDisplayProbeFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = "Receipt printer probe",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(onClick = onRunReceiptPrinterProbe) {
                Text("Run receipt printer probe")
            }
            receiptPrinterProbeStatus?.let { status ->
                StatusBanner(
                    text = status,
                    tint = if (isReceiptPrinterProbeFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        PosPane(
            title = "Current state",
            supportingText = "Shift metrics are stored in cents for deterministic money integrity.",
            modifier = Modifier.weight(1f),
        ) {
            KeyValueRow("Status", state.currentShift?.status?.name ?: "CLOSED")
            KeyValueRow("Opened by", state.currentShift?.openedByStaffId ?: "-")
            KeyValueRow(
                "Opening float",
                state.currentShift?.openingFloatCents?.let(CentsFormatter::format) ?: CentsFormatter.format(0),
            )
            KeyValueRow(
                "Expected cash",
                state.currentShift?.expectedCashCents?.let(CentsFormatter::format) ?: CentsFormatter.format(0),
            )
            KeyValueRow(
                "Counted cash",
                state.currentShift?.countedCashCents?.let(CentsFormatter::format) ?: "-",
            )
            Text(
                text = "Scanner probe",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Availability: $scannerAvailabilityLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Package: $scannerPackageLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "ScannerService bind: $scannerServiceBindLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "IScanManager bind: $scanManagerBindLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Broadcast: $broadcastStatusLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStartScannerProbe) {
                    Text("Start scanner probe")
                }
                Button(onClick = onStopScannerProbe) {
                    Text("Stop scanner probe")
                }
            }
            scannerProbeStatus?.let { status ->
                StatusBanner(
                    text = status,
                    tint = if (isScannerProbeFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            scannerLastError?.let { error ->
                StatusBanner(
                    text = error,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "Last scanner value: ${lastScannerValue ?: "-"}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
