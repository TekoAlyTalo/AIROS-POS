package com.airos.pos.device.scanner

import android.content.Context
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ScannerProbeDebugState(
    val scannerPackageFound: Boolean = false,
    val qrScannerPackageFound: Boolean = false,
    val broadcastReceiverRegistered: Boolean = false,
    val broadcastSeen: Boolean = false,
    val scannerServiceBindAttempted: Boolean = false,
    val scannerServiceBound: Boolean = false,
    val scannerServiceDescriptor: String? = null,
    val scanManagerBindAttempted: Boolean = false,
    val scanManagerBound: Boolean = false,
    val scanManagerDescriptor: String? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
)

interface ScannerService {
    val availability: StateFlow<DeviceConnectionState>
    val scanEvents: Flow<ScanEvent>
    val probeDebug: StateFlow<ScannerProbeDebugState>
    suspend fun start()
    suspend fun stop()
    suspend fun emitDebugScan(rawValue: String)
}

class SunmiScannerService(
    private val context: Context,
) : ScannerService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)
    private val scanEventsFlow = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4)
    private val probeDebugFlow = MutableStateFlow(ScannerProbeDebugState())
    private val probe = SunmiScannerProbe(
        context = context.applicationContext,
        onAvailabilityChanged = { availabilityFlow.value = it },
        onScanEvent = { event -> scanEventsFlow.tryEmit(event) },
        onDebugStateChanged = { state -> probeDebugFlow.value = state },
    )

    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow
    override val scanEvents: Flow<ScanEvent> = scanEventsFlow
    override val probeDebug: StateFlow<ScannerProbeDebugState> = probeDebugFlow

    override suspend fun start() {
        probe.start()
    }

    override suspend fun stop() {
        probe.stop()
    }

    override suspend fun emitDebugScan(rawValue: String) {
        scanEventsFlow.emit(
            ScanEvent(
                rawValue = rawValue,
                symbology = "DEBUG",
                scannedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}
