package com.airos.pos.device.scanner

import android.content.Context
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ScannerService {
    val availability: StateFlow<DeviceConnectionState>
    val scanEvents: Flow<ScanEvent>
    suspend fun start()
    suspend fun stop()
    suspend fun emitDebugScan(rawValue: String)
}

class SunmiScannerService(
    @Suppress("unused") private val context: Context,
) : ScannerService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)
    private val scanEventsFlow = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4)

    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow
    override val scanEvents: Flow<ScanEvent> = scanEventsFlow

    override suspend fun start() {
        // TODO-DEVICE: Start Sunmi scanner broadcast/session listener.
    }

    override suspend fun stop() {
        // TODO-DEVICE: Stop Sunmi scanner listener.
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
