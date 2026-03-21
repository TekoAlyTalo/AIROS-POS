package com.airos.pos.device.scanner

import android.content.Context
import android.content.Intent
import android.util.Log
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ScannerService {
    val availability: StateFlow<DeviceConnectionState>
    val scanEvents: Flow<ScanEvent>

    /**
     * Small rolling log meant for the Shift debug UI.
     * Newest items first.
     */
    val diagnosticEvents: StateFlow<List<String>>

    suspend fun start()
    suspend fun stop()
    suspend fun emitDebugScan(rawValue: String)

    // Extra helpers for the Shift test screen.
    suspend fun prepareScanner()

    /**
     * Sunmi scanhead illumination control (NOT Android camera torch).
     *
     * Commonly seen config key:
     * - scan00000107=1;   (enable)
     * - scan00000107=0;   (disable)
     */
    suspend fun setFlashControl(enabled: Boolean)

    suspend fun triggerScan()
    suspend fun cameraOnAndScan()
    suspend fun keyDown()
    suspend fun keyUp()
    suspend fun stopScanner()

    suspend fun launchScannerUi()
    suspend fun openScannerSettings()
    suspend fun openScannerDeviceSettings()
    suspend fun openScannerKeyboardSettings()
}

class SunmiScannerService(
    private val context: Context,
) : ScannerService {
    private val appContext = context.applicationContext

    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)
    private val scanEventsFlow = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    private val diagnosticEventsFlow = MutableStateFlow<List<String>>(emptyList())

    private val probe by lazy {
        SunmiScannerProbe(
            context = appContext,
            onAvailabilityChanged = { state -> availabilityFlow.value = state },
            onScanEvent = { event ->
                appendDiagnosticEvent("Scan event: ${event.symbology}: ${event.rawValue}")
                scanEventsFlow.tryEmit(event)
            },
            onDiagnosticEvent = ::appendDiagnosticEvent,
        )
    }

    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow
    override val scanEvents: Flow<ScanEvent> = scanEventsFlow
    override val diagnosticEvents: StateFlow<List<String>> = diagnosticEventsFlow

    override suspend fun start() {
        appendDiagnosticEvent("start()")
        probe.prepare()
    }

    override suspend fun stop() {
        appendDiagnosticEvent("stop()")
        probe.stop()
    }

    override suspend fun emitDebugScan(rawValue: String) {
        appendDiagnosticEvent("Debug scan emitted: $rawValue")
        scanEventsFlow.emit(
            ScanEvent(
                rawValue = rawValue,
                symbology = "DEBUG",
                scannedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun prepareScanner() {
        appendDiagnosticEvent("prepareScanner()")
        probe.prepare()
    }

    override suspend fun setFlashControl(enabled: Boolean) {
        appendDiagnosticEvent("setFlashControl(enabled=$enabled)")
        probe.setFlashControl(enabled)
    }

    override suspend fun triggerScan() {
        appendDiagnosticEvent("triggerScan()")
        probe.triggerScan()
    }

    override suspend fun cameraOnAndScan() {
        appendDiagnosticEvent("cameraOnAndScan()")
        probe.cameraOnAndScan()
    }

    override suspend fun keyDown() {
        appendDiagnosticEvent("keyDown()")
        probe.sendKeyDown()
    }

    override suspend fun keyUp() {
        appendDiagnosticEvent("keyUp()")
        probe.sendKeyUp()
    }

    override suspend fun stopScanner() {
        appendDiagnosticEvent("stopScanner()")
        probe.stopScanOnly()
    }

    override suspend fun launchScannerUi() {
        appendDiagnosticEvent("launchScannerUi()")
        startActivitySafely(Intent("com.sunmi.scanner.qrscanner"))
    }

    override suspend fun openScannerSettings() {
        appendDiagnosticEvent("openScannerSettings()")
        startActivitySafely(Intent("com.sunmi.scanner.SettingActivity"))
    }

    override suspend fun openScannerDeviceSettings() {
        appendDiagnosticEvent("openScannerDeviceSettings()")
        startActivitySafely(Intent("com.sunmi.scanner.ui.DeviceSettingActivity"))
    }

    override suspend fun openScannerKeyboardSettings() {
        appendDiagnosticEvent("openScannerKeyboardSettings()")
        startActivitySafely(Intent("com.sunmi.scanner.ui.KeyboardSettingActivity"))
    }

    private fun startActivitySafely(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onSuccess { appendDiagnosticEvent("Started activity: ${intent.action}") }
            .onFailure {
                val msg = it.message ?: it.javaClass.simpleName
                appendDiagnosticEvent("Failed to start activity ${intent.action}: $msg")
                Log.w(TAG, "Failed to start activity action=${intent.action}", it)
            }
    }

    private fun appendDiagnosticEvent(message: String) {
        diagnosticEventsFlow.value = (listOf(message) + diagnosticEventsFlow.value).take(MAX_DIAGNOSTIC_EVENTS)
    }

    private companion object {
        private const val TAG = "ScannerService"
        private const val MAX_DIAGNOSTIC_EVENTS = 30
    }
}
