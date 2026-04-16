package com.airos.pos.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


data class ScannerUiState(
    val availability: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE,
    val lastScan: ScanEvent? = null,
)

class ScannerViewModel : ViewModel() {
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

    private val mutableIsPreviewVisible = MutableStateFlow(false)
    val isPreviewVisible: StateFlow<Boolean> = mutableIsPreviewVisible.asStateFlow()

    fun onPreviewAvailabilityChanged(availability: DeviceConnectionState) {
        mutableState.update { it.copy(availability = availability) }
    }

    fun onExternalScanEvent(event: ScanEvent) {
        mutableState.update { it.copy(lastScan = event) }
        mutableLastPresentedValue.value = event.rawValue
        mutableLastPresentedSymbology.value = event.symbology

        if (!mutableIsBusy.value) {
            return
        }

        if (mutableIsMultiScanEnabled.value) {
            mutableScanStatus.value = "Code read. Ready for the next scan."
        } else {
            mutableIsBusy.value = false
            mutableIsPreviewVisible.value = false
            mutableScanStatus.value = "Code read. Single scan finished and light turned off."
        }
    }

    fun setMultiScanEnabled(enabled: Boolean) {
        mutableIsMultiScanEnabled.value = enabled
        mutableScanStatus.value = if (enabled) {
            "Multiple scans selected. The camera preview stays active for repeated reads."
        } else {
            "Single scan selected. Scan stops after the first successful read."
        }
    }

    fun scanWithLight() {
        if (mutableIsBusy.value) {
            return
        }
        mutableIsBusy.value = true
        mutableIsPreviewVisible.value = true
        mutableScanStatus.value = "Opening camera preview and turning the light on."
    }

    fun onPreviewSessionStarted() {
        if (!mutableIsBusy.value) {
            return
        }
        mutableScanStatus.value = if (mutableIsMultiScanEnabled.value) {
            "Camera preview active. Multiple scans is active."
        } else {
            "Camera preview active. Waiting for one successful scan."
        }
    }

    fun onPreviewSessionFailed(message: String?) {
        mutableIsBusy.value = false
        mutableIsPreviewVisible.value = false
        mutableScanStatus.value = "Camera preview failed: ${message ?: "Unknown error"}"
    }

    fun onCameraPermissionDenied() {
        mutableIsBusy.value = false
        mutableIsPreviewVisible.value = false
        mutableScanStatus.value = "Camera permission is required for scanning."
    }

    fun stopScanning() {
        mutableIsBusy.value = false
        mutableIsPreviewVisible.value = false
        mutableScanStatus.value = "Scanner stopped and light turned off."
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScannerViewModel() }
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
    isPreviewVisible: Boolean,
    hasCameraPermission: Boolean,
    previewContent: (@Composable () -> Unit)?,
    onMultiScanEnabledChange: (Boolean) -> Unit,
    onScanWithLight: () -> Unit,
    onStopScanning: () -> Unit,
) {
    PosPane(
        title = "Scan",
        supportingText = "Press Scan to open the camera preview with the light already on. Single scan stops after one read. Multiple scans keeps the preview active for the next code.",
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Camera preview",
            style = MaterialTheme.typography.titleSmall,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasCameraPermission -> {
                    Text(
                        text = "Camera permission is required to show the scanner preview.",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                isPreviewVisible && previewContent != null -> previewContent()
                else -> {
                    Text(
                        text = "Preview idle. Press Scan to start the camera.",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

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
                    text = "Default is single. Turn this on when you want the camera preview to stay active for the next code.",
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
