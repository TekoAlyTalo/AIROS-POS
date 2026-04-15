package com.airos.pos.feature.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ScannerUiState(
    val availability: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE,
    val lastScan: ScanEvent? = null,
)

class ScannerViewModel(
    private val scannerService: ScannerService,
    private val setTorch: suspend (Boolean) -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private val mutableLastPresentedValue = MutableStateFlow<String?>(null)
    val lastPresentedValue: StateFlow<String?> = mutableLastPresentedValue.asStateFlow()

    private val mutableLastPresentedSymbology = MutableStateFlow<String?>(null)
    val lastPresentedSymbology: StateFlow<String?> = mutableLastPresentedSymbology.asStateFlow()

    private val mutableScanStatus = MutableStateFlow<String?>(null)
    val scanStatus: StateFlow<String?> = mutableScanStatus.asStateFlow()

    private val mutableIsMultiScanEnabled = MutableStateFlow(false)
    val isMultiScanEnabled: StateFlow<Boolean> = mutableIsMultiScanEnabled.asStateFlow()

    private val mutableIsBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = mutableIsBusy.asStateFlow()

    init {
        viewModelScope.launch {
            scannerService.availability.collect { availability ->
                mutableState.update { it.copy(availability = availability) }
            }
        }
        viewModelScope.launch {
            scannerService.scanEvents.collect { event ->
                mutableState.update { it.copy(lastScan = event) }
                mutableLastPresentedValue.value = event.rawValue
                mutableLastPresentedSymbology.value = event.symbology
                handleCompletedScan()
            }
        }
    }

    fun setMultiScanEnabled(enabled: Boolean) {
        mutableIsMultiScanEnabled.value = enabled
        mutableScanStatus.value = if (enabled) {
            "Multiple scans selected. Light will blink between successful reads."
        } else {
            "Single scan selected. Scan stops after the first successful read."
        }
    }

    fun scanWithLight() {
        if (mutableIsBusy.value) {
            return
        }

        viewModelScope.launch {
            mutableIsBusy.value = true
            mutableScanStatus.value = "Opening scanner and turning the light on."
            runCatching {
                scannerService.prepareScanner()
                setTorch(true)
                // Odota että SunmiScannerProbe on saanut bindServicen kautta binder-kahvan
                // ja päivittänyt availabilityn READY-tilaan. Ilman tätä cameraOnAndScan()
                // voi kutsua ennen kuin binder on valmis ja probe vain loggaa
                // "requested before scanner binder was ready" ja palaa hiljaa.
                val ready = withTimeoutOrNull(SCANNER_READY_TIMEOUT_MS) {
                    scannerService.availability.first { it != DeviceConnectionState.UNAVAILABLE }
                }
                if (ready == null) {
                    throw ScannerNotReadyException()
                }
                scannerService.cameraOnAndScan()
            }.onSuccess {
                mutableScanStatus.value = if (mutableIsMultiScanEnabled.value) {
                    "Scanner opened. Multiple scans is active."
                } else {
                    "Scanner opened. Waiting for one successful scan."
                }
            }.onFailure { error: Throwable ->
                runCatching { setTorch(false) }
                mutableIsBusy.value = false
                mutableScanStatus.value = if (error is ScannerNotReadyException) {
                    "Scanner not ready yet. Try again."
                } else {
                    "Scan start failed: ${error.message ?: "Unknown error"}"
                }
            }
        }
    }

    private class ScannerNotReadyException :
        RuntimeException("Scanner binder did not become ready in time")

    fun stopScanning() {
        viewModelScope.launch {
            runCatching { scannerService.stopScanner() }
            runCatching { setTorch(false) }
            mutableIsBusy.value = false
            mutableScanStatus.value = "Scanner stopped and light turned off."
        }
    }

    private suspend fun handleCompletedScan() {
        if (!mutableIsBusy.value) {
            return
        }

        if (mutableIsMultiScanEnabled.value) {
            mutableScanStatus.value = "Code read. Blinking the light before the next scan."
            runCatching { setTorch(false) }
            delay(500)
            runCatching { setTorch(true) }
            mutableScanStatus.value = "Ready for the next scan."
        } else {
            runCatching { setTorch(false) }
            mutableIsBusy.value = false
            mutableScanStatus.value = "Code read. Single scan finished and light turned off."
        }
    }

    companion object {
        private const val SCANNER_READY_TIMEOUT_MS = 2_000L

        fun factory(
            scannerService: ScannerService,
            setTorch: suspend (Boolean) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScannerViewModel(scannerService, setTorch) }
        }
    }
}

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    lastPresentedValue: String?,
    lastPresentedSymbology: String?,
    scanStatus: String?,
    isMultiScanEnabled: Boolean,
    isBusy: Boolean,
    onMultiScanEnabledChange: (Boolean) -> Unit,
    onScanWithLight: () -> Unit,
    onStopScanning: () -> Unit,
) {
    PosPane(
        title = "Scan",
        supportingText = "Press Scan to open the camera with the light already on. Single scan stops after one read. Multiple scans blinks the light and brings the scanner back for the next code.",
        modifier = Modifier.fillMaxSize(),
    ) {
        KeyValueRow("Availability", state.availability.name)
        KeyValueRow("Mode", if (isMultiScanEnabled) "Multiple scans" else "Single scan")
        KeyValueRow("Last scan", lastPresentedValue ?: state.lastScan?.rawValue ?: "No scans yet")
        KeyValueRow("Symbology", lastPresentedSymbology ?: state.lastScan?.symbology ?: "-")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Multiple scans",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Default is single. Turn this on when you want the scanner to keep coming back for the next code.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = isMultiScanEnabled,
                onCheckedChange = onMultiScanEnabledChange,
                enabled = !isBusy,
            )
        }

        scanStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onScanWithLight, enabled = !isBusy) {
                Text(if (isBusy) "Scanning..." else "Scan")
            }
            Button(onClick = onStopScanning) {
                Text("Light off")
            }
        }
    }
}
