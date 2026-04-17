package com.airos.pos.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner

@Composable
fun DeviceDiagnosticsScreen(
    customerDisplayProbeStatus: String?,
    isCustomerDisplayProbeFailure: Boolean,
    onRunCustomerDisplayProbe: () -> Unit,
    receiptPrinterProbeStatus: String?,
    isReceiptPrinterProbeFailure: Boolean,
    onRunReceiptPrinterProbe: () -> Unit,
    scannerProbeStatus: String?,
    isScannerProbeFailure: Boolean,
    scannerAvailabilityLabel: String,
    lastScannerValue: String?,
    scannerDiagnosticEvents: List<String>,
    onPrepareScanner: () -> Unit,
    onTriggerScanner: () -> Unit,
    onCameraOnAndScan: () -> Unit,
    onScannerFlashOn: () -> Unit = {},
    onScannerFlashOff: () -> Unit = {},
    onScannerTestA: () -> Unit = {},
    onScannerTestB: () -> Unit = {},
    onScannerTestC: () -> Unit = {},
    onAndroidTorchOn: () -> Unit = {},
    onAndroidTorchOff: () -> Unit = {},
    onTriggerKeyDown: () -> Unit,
    onTriggerKeyUp: () -> Unit,
    onStopScannerProbe: () -> Unit,
    onLaunchScannerUi: () -> Unit,
    onOpenScannerSettings: () -> Unit,
    onOpenScannerDeviceSettings: () -> Unit,
    onOpenScannerKeyboardSettings: () -> Unit,
    nfcAdapterSummary: String = "NFC not checked",
    nfcProbeStatus: String? = null,
    isNfcProbeFailure: Boolean = false,
    lastNfcTagSummary: String? = null,
    onRunNfcProbe: () -> Unit = {},
    lastNfcStaffResolutionSummary: String? = null,
    isNfcStaffResolutionUnknown: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PosPane(
            title = "Device probes",
            supportingText = "Customer display, receipt printer, and NFC hardware diagnostics.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                Text(
                    text = "NFC probe",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = nfcAdapterSummary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onRunNfcProbe) {
                    Text("Run NFC probe")
                }
                nfcProbeStatus?.let { status ->
                    StatusBanner(
                        text = status,
                        tint = if (isNfcProbeFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                lastNfcTagSummary?.let { summary ->
                    StatusBanner(
                        text = summary,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "NFC staff match",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (lastNfcStaffResolutionSummary != null) {
                    StatusBanner(
                        text = lastNfcStaffResolutionSummary,
                        tint = if (isNfcStaffResolutionUnknown) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                } else {
                    Text(
                        text = "No tag tapped yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        PosPane(
            title = "Scanner",
            supportingText = "Barcode scanner hardware diagnostics and control.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Last scanner value: ${lastScannerValue ?: "-"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Availability: $scannerAvailabilityLabel",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPrepareScanner) {
                        Text("Prepare scanner")
                    }
                    Button(onClick = onTriggerScanner) {
                        Text("Trigger scan")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCameraOnAndScan) {
                        Text("Camera on + scan")
                    }
                    Button(onClick = onTriggerKeyDown) {
                        Text("Key down")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onAndroidTorchOn) {
                        Text("Android torch ON")
                    }
                    Button(onClick = onAndroidTorchOff) {
                        Text("Android torch OFF")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onScannerFlashOn) {
                        Text("Scanner flash ON")
                    }
                    Button(onClick = onScannerFlashOff) {
                        Text("Scanner flash OFF")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onScannerTestA) {
                        Text("Test A")
                    }
                    Button(onClick = onScannerTestB) {
                        Text("Test B")
                    }
                    Button(onClick = onScannerTestC) {
                        Text("Test C")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onTriggerKeyUp) {
                        Text("Key up")
                    }
                    Button(onClick = onStopScannerProbe) {
                        Text("Stop scanner")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onLaunchScannerUi) {
                        Text("Launch Sunmi UI")
                    }
                    Button(onClick = onOpenScannerSettings) {
                        Text("Scanner settings")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onOpenScannerDeviceSettings) {
                        Text("Device settings")
                    }
                    Button(onClick = onOpenScannerKeyboardSettings) {
                        Text("Keyboard settings")
                    }
                }

                Text(
                    text = "Recent scanner events",
                    style = MaterialTheme.typography.titleSmall,
                )
                scannerDiagnosticEvents.take(6).forEach { event ->
                    Text(
                        text = "\u2022 $event",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
