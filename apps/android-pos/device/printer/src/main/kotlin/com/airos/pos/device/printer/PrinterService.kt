package com.airos.pos.device.printer

import android.content.Context
import android.os.RemoteException
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.KitchenTicketDocument
import com.airos.pos.core.model.ReceiptDocument
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

interface PrinterService {
    val availability: StateFlow<DeviceConnectionState>
    suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit>
    suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit>
    suspend fun printDiagnosticReceipt(): PosResult<Unit>
}

class SunmiPrinterService(
    private val context: Context,
) : PrinterService {
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

    override suspend fun printReceipt(document: ReceiptDocument): PosResult<Unit> {
        val service = when (val connection = ensurePrinterService()) {
            is PosResult.Success -> connection.value
            is PosResult.Failure -> return connection
        }

        return try {
            val callback = createNoOpCallback()

            invokeIfPresent(service, "printerInit", callback)
            invokeIfPresent(service, "setAlignment", 1, callback)
            invokeIfPresent(service, "printText", "${document.title}\n", callback)
            invokeIfPresent(service, "setAlignment", 0, callback)
            invokeIfPresent(service, "printText", "------------------------------\n", callback)

            document.lines.forEach { line ->
                val rendered = if (line.value.isNullOrBlank()) {
                    line.label
                } else {
                    "${line.label}: ${line.value}"
                }
                invokeIfPresent(service, "printText", "$rendered\n", callback)
            }

            invokeIfPresent(service, "printText", "------------------------------\n", callback)
            invokeIfPresent(service, "printText", "${document.footer}\n", callback)

            val wrapped = invokeIfPresent(service, "lineWrap", 3, callback)
            if (!wrapped) {
                invokeIfPresent(service, "printText", "\n\n\n", callback)
            }

            invokeIfPresent(service, "autoOutPaper", callback)
            PosResult.Success(Unit)
        } catch (t: Throwable) {
            availabilityFlow.value = DeviceConnectionState.UNAVAILABLE
            PosResult.Failure("SUNMI receipt print failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override suspend fun printDiagnosticReceipt(): PosResult<Unit> {
        val diagnostic = ReceiptDocument(
            title = "AIROS TEST",
            lines = listOf(
                com.airos.pos.core.model.ReceiptLine(label = "Device", value = "SUNMI D3 MINI"),
                com.airos.pos.core.model.ReceiptLine(label = "Message", value = "Hello World"),
                com.airos.pos.core.model.ReceiptLine(label = "Path", value = "Receipt path probe"),
            ),
            footer = "If you can read this, the printer path works.",
        )
        return printReceipt(diagnostic)
    }

    override suspend fun printKitchenTicket(document: KitchenTicketDocument): PosResult<Unit> {
        return PosResult.Failure("Kitchen ticket printing is not implemented yet.")
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

    private fun invokeIfPresent(
        target: Any,
        methodName: String,
        vararg args: Any,
    ): Boolean {
        val methods = target.javaClass.methods.filter { it.name == methodName }
        if (methods.isEmpty()) return false

        for (method in methods) {
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size != args.size) continue
            if (!parametersMatch(parameterTypes, args)) continue

            method.isAccessible = true
            method.invoke(target, *args)
            return true
        }

        return false
    }

    private fun parametersMatch(
        parameterTypes: Array<Class<*>>,
        args: Array<out Any>,
    ): Boolean {
        return parameterTypes.indices.all { index ->
            val parameterType = parameterTypes[index]
            val arg = args[index]

            when {
                parameterType == Int::class.javaPrimitiveType || parameterType == Int::class.javaObjectType -> arg is Int
                parameterType == Boolean::class.javaPrimitiveType || parameterType == Boolean::class.javaObjectType -> arg is Boolean
                parameterType == Float::class.javaPrimitiveType || parameterType == Float::class.javaObjectType -> arg is Float
                parameterType == Double::class.javaPrimitiveType || parameterType == Double::class.javaObjectType -> arg is Double
                else -> parameterType.isAssignableFrom(arg.javaClass)
            }
        }
    }
}
