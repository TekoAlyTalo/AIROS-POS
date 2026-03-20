package com.airos.pos.device.cashdrawer

import android.content.Context
import android.os.RemoteException
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService as SunmiInnerPrinterService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface CashDrawerService {
    val availability: StateFlow<DeviceConnectionState>
    suspend fun openDrawer(reason: String): PosResult<Unit>
}

class SunmiCashDrawerService(
    private val context: Context,
) : CashDrawerService {
    private val availabilityFlow = MutableStateFlow(DeviceConnectionState.UNAVAILABLE)
    override val availability: StateFlow<DeviceConnectionState> = availabilityFlow

    private val connectMutex = Mutex()

    @Volatile
    private var printerService: SunmiInnerPrinterService? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<SunmiInnerPrinterService>? = null

    private val printerCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiInnerPrinterService) {
            printerService = service
            availabilityFlow.value = DeviceConnectionState.READY
            pendingConnection?.complete(service)
        }

        override fun onDisconnected() {
            printerService = null
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
        }
    }

    override suspend fun openDrawer(reason: String): PosResult<Unit> {
        val service = when (val connection = ensurePrinterService()) {
            is PosResult.Success -> connection.value
            is PosResult.Failure -> return connection
        }

        return try {
            val callback = createNoOpCallback()
            val opened = invokeOpenDrawer(service, callback) || invokeSendRawData(service, callback)

            if (!opened) {
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return PosResult.Failure(
                    "SUNMI cash drawer API not available. openDrawer/sendRAWData not found on printer service.",
                )
            }

            availabilityFlow.value = DeviceConnectionState.READY
            PosResult.Success(Unit)
        } catch (e: InnerPrinterException) {
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
            PosResult.Failure("SUNMI cash drawer failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: RemoteException) {
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
            PosResult.Failure("SUNMI cash drawer remote call failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (t: Throwable) {
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
            PosResult.Failure("SUNMI cash drawer crashed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun ensurePrinterService(): PosResult<SunmiInnerPrinterService> {
        printerService?.let { return PosResult.Success(it) }

        return connectMutex.withLock {
            printerService?.let { return@withLock PosResult.Success(it) }

            val deferred = CompletableDeferred<SunmiInnerPrinterService>()
            pendingConnection = deferred

            val bound = try {
                InnerPrinterManager.getInstance().bindService(context.applicationContext, printerCallback)
            } catch (e: InnerPrinterException) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer bind failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (t: Throwable) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer bind crashed: ${t.message ?: t.javaClass.simpleName}")
            }

            if (!bound) {
                pendingConnection = null
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                return@withLock PosResult.Failure("SUNMI printer service bind returned false.")
            }

            val connected = withTimeoutOrNull(4_000) { deferred.await() }
            pendingConnection = null

            if (connected == null) {
                availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
                PosResult.Failure("SUNMI printer service did not connect within timeout.")
            } else {
                availabilityFlow.value = DeviceConnectionState.READY
                PosResult.Success(connected)
            }
        }
    }

    private fun invokeOpenDrawer(
        service: SunmiInnerPrinterService,
        callback: InnerResultCallback,
    ): Boolean {
        return try {
            service.openDrawer(callback)
            true
        } catch (_: NoSuchMethodError) {
            false
        } catch (_: AbstractMethodError) {
            false
        }
    }

    private fun invokeSendRawData(
        service: SunmiInnerPrinterService,
        callback: InnerResultCallback,
    ): Boolean {
        val pulse = byteArrayOf(0x10, 0x14, 0x00, 0x00, 0x00)
        service.sendRAWData(pulse, callback)
        return true
    }

    private fun createNoOpCallback(): InnerResultCallback {
        return object : InnerResultCallback() {
            @Throws(RemoteException::class)
            override fun onRunResult(isSuccess: Boolean) = Unit

            @Throws(RemoteException::class)
            override fun onReturnString(result: String?) = Unit

            @Throws(RemoteException::class)
            override fun onRaiseException(code: Int, msg: String?) = Unit

            @Throws(RemoteException::class)
            override fun onPrintResult(code: Int, msg: String?) = Unit
        }
    }
}
