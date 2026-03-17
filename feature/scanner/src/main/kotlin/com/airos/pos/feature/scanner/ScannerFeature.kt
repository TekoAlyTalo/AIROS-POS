package com.airos.pos.feature.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.device.scanner.ScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScannerUiState(
    val availability: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE,
    val lastScan: ScanEvent? = null,
)

class ScannerViewModel(
    private val scannerService: ScannerService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            scannerService.availability.collect { availability ->
                mutableState.update { it.copy(availability = availability) }
            }
        }
        viewModelScope.launch {
            scannerService.scanEvents.collect { event ->
                mutableState.update { it.copy(lastScan = event) }
            }
        }
    }

    fun startScanner() {
        viewModelScope.launch { scannerService.start() }
    }

    fun stopScanner() {
        viewModelScope.launch { scannerService.stop() }
    }

    fun emitDebugScan(rawValue: String) {
        viewModelScope.launch { scannerService.emitDebugScan(rawValue) }
    }

    companion object {
        fun factory(scannerService: ScannerService): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScannerViewModel(scannerService) }
        }
    }
}

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDebugScan: (String) -> Unit,
) {
    PosPane(
        title = "Scanner diagnostics",
        supportingText = "Scanner events are exposed as a device service flow. Vendor SDK wiring stays behind the abstraction.",
        modifier = Modifier.fillMaxSize(),
    ) {
        KeyValueRow("Availability", state.availability.name)
        KeyValueRow("Last scan", state.lastScan?.rawValue ?: "No scans yet")
        KeyValueRow("Symbology", state.lastScan?.symbology ?: "-")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart) { Text("Start") }
            Button(onClick = onStop) { Text("Stop") }
            Button(onClick = { onDebugScan("5901234123457") }) { Text("Emit debug scan") }
        }
    }
}