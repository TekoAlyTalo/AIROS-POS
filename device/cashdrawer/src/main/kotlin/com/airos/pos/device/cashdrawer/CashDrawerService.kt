package com.airos.pos.device.cashdrawer

import android.content.Context
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CashDrawerService {
    val availability: StateFlow<DeviceConnectionState>
    suspend fun openDrawer(reason: String): PosResult<Unit>
}

class SunmiCashDrawerService(
    @Suppress("unused") private val context: Context,
) : CashDrawerService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)

    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow

    override suspend fun openDrawer(reason: String): PosResult<Unit> {
        return PosResult.Failure("TODO-DEVICE: Cash drawer integration is not implemented yet.")
    }
}
