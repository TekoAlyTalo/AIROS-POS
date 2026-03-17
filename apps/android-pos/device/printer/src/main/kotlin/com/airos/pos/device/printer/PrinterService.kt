package com.airos.pos.device.printer

import android.content.Context
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.KitchenTicketDocument
import com.airos.pos.core.model.ReceiptDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PrinterService {
    val availability: StateFlow<DeviceConnectionState>
    suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit>
    suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit>
}

class SunmiPrinterService(
    @Suppress("unused") private val context: Context,
) : PrinterService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)

    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow

    override suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit> {
        return PosResult.Failure("TODO-DEVICE: Sunmi printer SDK integration is not implemented yet.")
    }

    override suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit> {
        return PosResult.Failure("TODO-DEVICE: Sunmi kitchen printer flow is not implemented yet.")
    }
}
