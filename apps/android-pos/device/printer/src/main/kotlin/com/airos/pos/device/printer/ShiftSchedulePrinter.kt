package com.airos.pos.device.printer

import android.content.Context
import android.graphics.Bitmap
import android.os.RemoteException
import com.airos.pos.core.common.PosResult
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService as SunmiInnerPrinterService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimal bitmap print path for AIROS shift schedule cards.
 *
 * The normal receipt printer service prints structured receipt documents. The shift
 * schedule handout is intentionally image-based so the same landscape preview layout
 * can be rotated and sent to the built-in Sunmi thermal printer.
 */
class ShiftSchedulePrinter(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()

    @Volatile
    private var printerService: SunmiInnerPrinterService? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<SunmiInnerPrinterService>? = null

    private val printerCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiInnerPrinterService) {
            printerService = service
            pendingConnection?.complete(service)
        }

        override fun onDisconnected() {
            printerService = null
        }
    }

    suspend fun printBitmap(bitmap: Bitmap): PosResult<Unit> {
        if (bitmap.isRecycled) {
            return PosResult.Failure("Tulostettava työvuorokuva ei ole enää käytettävissä.")
        }

        val service = when (val connection = ensurePrinterService()) {
            is PosResult.Success -> connection.value
            is PosResult.Failure -> return connection
        }

        return try {
            val callback = createNoOpCallback()
            invokeIfPresent(service, "printerInit", callback)
            invokeIfPresent(service, "setAlignment", 1, callback)
            val printed = invokeIfPresent(service, "printBitmap", bitmap, callback)
            if (!printed) {
                return PosResult.Failure("SUNMI ei tarjonnut bitmap-tulostusmetodia.")
            }
            invokeIfPresent(service, "printText", "\n", callback)
            val wrapped = invokeIfPresent(service, "lineWrap", 3, callback)
            if (!wrapped) {
                invokeIfPresent(service, "printText", "\n\n\n", callback)
            }
            invokeIfPresent(service, "autoOutPaper", callback)
            PosResult.Success(Unit)
        } catch (t: Throwable) {
            printerService = null
            PosResult.Failure("SUNMI työvuorotulostus epäonnistui: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun ensurePrinterService(): PosResult<SunmiInnerPrinterService> {
        printerService?.let { return PosResult.Success(it) }

        return connectMutex.withLock {
            printerService?.let { return@withLock PosResult.Success(it) }

            val deferred = CompletableDeferred<SunmiInnerPrinterService>()
            pendingConnection = deferred

            val bound = try {
                InnerPrinterManager.getInstance().bindService(appContext, printerCallback)
            } catch (e: InnerPrinterException) {
                pendingConnection = null
                return@withLock PosResult.Failure("SUNMI tulostinpalvelun kytkentä epäonnistui: ${e.message ?: e.javaClass.simpleName}")
            } catch (t: Throwable) {
                pendingConnection = null
                return@withLock PosResult.Failure("SUNMI tulostinpalvelu kaatui kytkennässä: ${t.message ?: t.javaClass.simpleName}")
            }

            if (!bound) {
                pendingConnection = null
                return@withLock PosResult.Failure("SUNMI tulostinpalvelu palautti false.")
            }

            val connected = withTimeoutOrNull(4_000) { deferred.await() }
            pendingConnection = null

            if (connected == null) {
                PosResult.Failure("SUNMI tulostinpalvelu ei yhdistynyt aikarajassa.")
            } else {
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
