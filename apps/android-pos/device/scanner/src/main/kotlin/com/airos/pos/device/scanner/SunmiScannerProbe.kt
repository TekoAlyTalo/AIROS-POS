package com.airos.pos.device.scanner

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent

internal class SunmiScannerProbe(
    private val context: Context,
    private val onAvailabilityChanged: (DeviceConnectionState) -> Unit,
    private val onScanEvent: (ScanEvent) -> Unit,
    private val onDiagnosticEvent: (String) -> Unit,
) {
    private var prepared = false
    private var receiverRegistered = false
    private var callbackRegistered = false
    private var scannerBinder: IBinder? = null
    private var scannerConnection: ServiceConnection? = null
    private var pendingFlashControl: Boolean? = null


    private val callbackKey = "${context.packageName}-airos-scan"
    private val dataCallback = object : Binder(), IInterface {
        init {
            attachInterface(this, DATA_CALLBACK_TOKEN)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DATA_CALLBACK_TOKEN)
                    true
                }
                TRANSACTION_CALLBACK_DATA -> {
                    data.enforceInterface(DATA_CALLBACK_TOKEN)
                    val first = data.readString().orEmpty()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val second = data.readString().orEmpty()
                    reply?.writeNoException()
                    handleCallback(first, bytes, second)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    fun prepare() {
        if (!isScannerPackageInstalled()) {
            onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
            emitDiagnostic("Scanner package not found.")
            Log.i(TAG, "Sunmi scanner package not found.")
            return
        }
        emitDiagnostic("Prepare scanner requested.")
        registerBroadcastReceiver()
        bindScannerService()
    }

    fun triggerScan() {
        prepare()
        val binder = scannerBinder
        if (binder == null) {
            emitDiagnostic("Trigger requested before scanner binder was ready.")
            Log.i(TAG, "Trigger requested before scanner binder was ready.")
            return
        }
        val ok = transactNoArg(binder, TRANSACTION_SCAN, "scan")
        emitDiagnostic("Binder scan transaction result=$ok")
        Log.i(TAG, "Trigger scan result=$ok")
        if (ok) {
            onAvailabilityChanged(resolveConnectedState())
        }
    }

    fun cameraOnAndScan() {
        prepare()
        val binder = scannerBinder
        if (binder == null) {
            emitDiagnostic("Camera-on scan requested before scanner binder was ready.")
            Log.i(TAG, "Camera-on scan requested before scanner binder was ready.")
            return
        }
        val cameraOnOk = transactInt(binder, TRANSACTION_ON_CAMERA_ON, 1500, "onCameraOn")
        emitDiagnostic("onCameraOn transaction result=$cameraOnOk")
        Log.i(TAG, "Camera on result=$cameraOnOk")
        val scanOk = transactNoArg(binder, TRANSACTION_SCAN, "scan")
        emitDiagnostic("Camera on + scan transaction result=$scanOk")
        Log.i(TAG, "Camera on + scan result=$scanOk")
        if (cameraOnOk || scanOk) {
            onAvailabilityChanged(resolveConnectedState())
        }
    }

    fun sendKeyDown() {
        sendKeyEvent(KeyEvent.ACTION_DOWN)
    }

    fun sendKeyUp() {
        sendKeyEvent(KeyEvent.ACTION_UP)
    }

    fun stopScanOnly() {
        val binder = scannerBinder ?: return
        val ok = transactNoArg(binder, TRANSACTION_STOP, "stop")
        emitDiagnostic("Stop scan transaction result=$ok")
        Log.i(TAG, "Stop scan result=$ok")
    }

    fun stop() {
        stopScanOnly()
        unregisterCallback()
        unbindScannerService()
        unregisterBroadcastReceiver()
        scannerBinder = null
        prepared = false
        onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
        emitDiagnostic("Scanner probe stopped.")
        Log.i(TAG, "Sunmi scanner stopped.")
    }

    fun launchScannerUi(): Boolean {
    // Ensure receivers + binder callbacks are registered BEFORE launching the vendor UI,
    // so we can catch broadcast/callback based output modes.
    prepare()
    return launchActivity(
        action = ACTION_QR_SCANNER,
        diagnosticLabel = "Launch Sunmi scanner UI",
    )
}

    fun openScannerSettings(): Boolean = launchActivity(
        action = ACTION_SCANNER_SETTINGS,
        diagnosticLabel = "Open scanner settings",
    )

    fun openScannerDeviceSettings(): Boolean = launchActivity(
        action = ACTION_DEVICE_SETTINGS,
        diagnosticLabel = "Open device settings",
    )

    fun openScannerKeyboardSettings(): Boolean = launchActivity(
        action = ACTION_KEYBOARD_SETTINGS,
        diagnosticLabel = "Open keyboard settings",
    )

    /**
     * Best-effort torch/illumination control for the Sunmi scanner UI (NOT Android camera torch).
     *
     * We use the config key found in reverse engineering: scan00000107=1; / scan00000107=0;
     * Transaction code for the underlying "sendCommand" is not confirmed on D3 Mini, so we probe
     * a small set of likely binder transaction codes and log which ones acknowledge the call.
     */
    fun setFlashControl(enabled: Boolean) {
        pendingFlashControl = enabled
        prepare()

        val binder = scannerBinder
        if (binder == null) {
            emitDiagnostic("FlashControl pending enabled=$enabled (binder not ready)")
            Log.i(TAG, "FlashControl pending enabled=$enabled (binder not ready)")
            return
        }
        applyFlashControl(binder = binder, enabled = enabled)
    }

    private fun applyFlashControl(
        binder: IBinder,
        enabled: Boolean,
    ) {
        val value = if (enabled) "1" else "0"
        val cmdWithSemicolon = "${FLASH_CONTROL_COMMAND_KEY}=$value;"
        val cmdNoSemicolon = "${FLASH_CONTROL_COMMAND_KEY}=$value"
        val commands = listOf(cmdWithSemicolon, cmdNoSemicolon)

        val okTx = mutableListOf<Int>()
        for (tx in FLASH_CONTROL_TX_GUESSES) {
            var txOk = false
            for (cmd in commands) {
                val ok = transactStringQuiet(
                    binder = binder,
                    transactionCode = tx,
                    value = cmd,
                    callLabel = "flashControl tx=$tx",
                )
                if (ok) {
                    txOk = true
                    break
                }
            }
            if (txOk) okTx.add(tx)
        }

        val txLabel = if (okTx.isEmpty()) "-" else okTx.joinToString()
        emitDiagnostic("FlashControl requested enabled=$enabled okTx=$txLabel")
        Log.i(TAG, "FlashControl requested enabled=$enabled okTx=$txLabel")
    }

    private fun bindScannerService() {
        if (scannerConnection != null) {
            ensureCallbackRegistered()
            return
        }

        val component = ComponentName(SUNMI_SCANNER_PACKAGE, SCANNER_SERVICE_CLASS)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                scannerBinder = service
                val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
                emitDiagnostic("ScannerService connected. descriptor=$descriptor")
                Log.i(TAG, "ScannerService connected. component=$name descriptor=$descriptor")
                ensureCallbackRegistered()
                pendingFlashControl?.let { enabled ->
                    service?.let { binder ->
                        applyFlashControl(binder = binder, enabled = enabled)
                    }
                }
                onAvailabilityChanged(resolveConnectedState())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                callbackRegistered = false
                scannerBinder = null
                onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
                emitDiagnostic("ScannerService disconnected.")
                Log.i(TAG, "ScannerService disconnected. component=$name")
            }
        }

        val bound = runCatching {
            context.bindService(
                Intent().setComponent(component),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse { error ->
            Log.w(TAG, "ScannerService bind failed.", error)
            false
        }

        if (bound) {
            scannerConnection = connection
            prepared = true
            emitDiagnostic("ScannerService bind requested.")
            Log.i(TAG, "ScannerService bind requested.")
        } else {
            onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
            emitDiagnostic("ScannerService bind returned false.")
            Log.i(TAG, "ScannerService bind returned false.")
        }
    }

    private fun unbindScannerService() {
        val connection = scannerConnection ?: return
        runCatching { context.unbindService(connection) }
            .onFailure { Log.w(TAG, "Failed to unbind ScannerService.", it) }
        scannerConnection = null
    }

    private fun ensureCallbackRegistered() {
        if (callbackRegistered) return
        val binder = scannerBinder ?: return
        val ok = transactRegisterCallback(
            binder = binder,
            key = callbackKey,
            callbackBinder = dataCallback,
        )
        callbackRegistered = ok
        emitDiagnostic("Callback registration result=$ok key=$callbackKey")
        Log.i(TAG, "Callback registration result=$ok key=$callbackKey")
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        val binder = scannerBinder ?: return
        val ok = transactString(binder, TRANSACTION_UNREGISTER_CALLBACK, callbackKey, "unregisterCallback")
        emitDiagnostic("Callback unregister result=$ok key=$callbackKey")
        Log.i(TAG, "Callback unregister result=$ok key=$callbackKey")
        callbackRegistered = false
    }

    private fun sendKeyEvent(action: Int) {
        prepare()
        val binder = scannerBinder
        if (binder == null) {
            emitDiagnostic("Key event requested before scanner binder was ready.")
            Log.i(TAG, "Key event requested before scanner binder was ready.")
            return
        }

        val keyEvent = KeyEvent(action, KeyEvent.KEYCODE_UNKNOWN)
        val ok = transactKeyEvent(binder, keyEvent, "sendKeyEvent action=$action")
        emitDiagnostic("Key event result=$ok action=$action")
        Log.i(TAG, "Key event result=$ok action=$action")
        if (ok) {
            onAvailabilityChanged(resolveConnectedState())
        }
    }

    private fun handleCallback(first: String, bytes: ByteArray, second: String) {
        val decodedBytes = bytes.toReadableString()
        val rawValue = when {
            second.isNotBlank() -> second
            decodedBytes.isNotBlank() -> decodedBytes
            first.isNotBlank() -> first
            else -> "(empty callback)"
        }
        val symbology = when {
            first.isNotBlank() && first != rawValue -> first
            else -> "CALLBACK"
        }

        emitDiagnostic("IDataCallback hit. first=$first second=$second bytes=${bytes.size}")
        Log.i(
            TAG,
            "IDataCallback hit. first='$first' second='$second' bytes=${bytes.size} decoded='$decodedBytes'",
        )

        onScanEvent(
            ScanEvent(
                rawValue = rawValue,
                symbology = symbology,
                scannedAtEpochMillis = System.currentTimeMillis(),
            ),
        )

        // Turn the illumination back off after a successful decode.
        if (pendingFlashControl == true) {
            pendingFlashControl = false
            scannerBinder?.let { binder ->
                applyFlashControl(binder = binder, enabled = false)
            }
        }
    }

    private fun ByteArray.toReadableString(): String {
        if (isEmpty()) return ""
        return try {
            val decoded = decodeToString()
            if (decoded.isBlank()) "" else decoded.trim('\u0000', '\n', '\r', ' ')
        } catch (_: Throwable) {
            joinToString(separator = " ") { byte -> "%02X".format(byte) }
        }
    }

    private fun transactNoArg(
        binder: IBinder,
        transactionCode: Int,
        callLabel: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            val transactOk = binder.transact(transactionCode, data, reply, 0)
            if (!transactOk) {
                Log.w(TAG, "Binder transact returned false for $callLabel.")
                false
            } else {
                reply.readException()
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Binder transact failed for $callLabel.", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactString(
        binder: IBinder,
        transactionCode: Int,
        value: String,
        callLabel: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(value)
            val transactOk = binder.transact(transactionCode, data, reply, 0)
            if (!transactOk) {
                Log.w(TAG, "Binder transact returned false for $callLabel.")
                false
            } else {
                reply.readException()
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Binder transact failed for $callLabel.", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactStringQuiet(
        binder: IBinder,
        transactionCode: Int,
        value: String,
        callLabel: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(value)
            val transactOk = binder.transact(transactionCode, data, reply, 0)
            if (!transactOk) {
                false
            } else {
                reply.readException()
                true
            }
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }


    private fun transactInt(
        binder: IBinder,
        transactionCode: Int,
        value: Int,
        callLabel: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(value)
            val transactOk = binder.transact(transactionCode, data, reply, 0)
            if (!transactOk) {
                Log.w(TAG, "Binder transact returned false for $callLabel.")
                false
            } else {
                reply.readException()
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Binder transact failed for $callLabel.", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactKeyEvent(
        binder: IBinder,
        keyEvent: KeyEvent,
        callLabel: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(1)
            keyEvent.writeToParcel(data, 0)
            val transactOk = binder.transact(TRANSACTION_SEND_KEY_EVENT, data, reply, 0)
            if (!transactOk) {
                Log.w(TAG, "Binder transact returned false for $callLabel.")
                false
            } else {
                reply.readException()
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Binder transact failed for $callLabel.", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactRegisterCallback(
        binder: IBinder,
        key: String,
        callbackBinder: IBinder,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(key)
            data.writeStrongBinder(callbackBinder)
            val transactOk = binder.transact(TRANSACTION_REGISTER_CALLBACK, data, reply, 0)
            if (!transactOk) {
                Log.w(TAG, "Binder transact returned false for registerCallback.")
                false
            } else {
                reply.readException()
                val result = reply.readInt()
                Log.i(TAG, "registerCallback returned result=$result")
                result >= 0
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Binder transact failed for registerCallback.", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun registerBroadcastReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(ACTION_DATA_CODE_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scannerResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(scannerResultReceiver, filter)
        }
        receiverRegistered = true
        emitDiagnostic("Broadcast receiver registered for $ACTION_DATA_CODE_RECEIVED")
        Log.i(TAG, "Registered receiver for $ACTION_DATA_CODE_RECEIVED")
    }

    private fun unregisterBroadcastReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(scannerResultReceiver) }
            .onFailure { Log.w(TAG, "Failed to unregister scanner receiver.", it) }
        receiverRegistered = false
    }

    private val scannerResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_DATA_CODE_RECEIVED) return

            val rawValue = (
                intent.getStringExtra(EXTRA_DATA)
                    ?: intent.getStringExtra(EXTRA_VALUE)
            )?.trim().orEmpty()
            if (rawValue.isBlank()) {
                emitDiagnostic("Scanner broadcast received without payload.")
                Log.i(TAG, "Scanner broadcast received without payload.")
                return
            }

            val symbology =
                intent.getStringExtra(EXTRA_CODE_TYPE)
                    ?: intent.getStringExtra(EXTRA_TYPE)
                    ?: "BROADCAST"

            onScanEvent(
                ScanEvent(
                    rawValue = rawValue,
                    symbology = symbology,
                    scannedAtEpochMillis = System.currentTimeMillis(),
                ),
            )

            // Turn the illumination back off after a successful decode.
            if (pendingFlashControl == true) {
                pendingFlashControl = false
                scannerBinder?.let { binder ->
                    applyFlashControl(binder = binder, enabled = false)
                }
            }

            emitDiagnostic("Scanner broadcast value=$rawValue symbology=$symbology")
            Log.i(TAG, "Scanner broadcast value=$rawValue symbology=$symbology")
        }
    }

    private fun launchActivity(
        action: String,
        diagnosticLabel: String,
    ): Boolean {
        val launchIntent = Intent(action)
            .setPackage(SUNMI_SCANNER_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = runCatching {
            context.packageManager.resolveActivity(launchIntent, 0)
        }.getOrNull()

        if (resolved == null) {
            emitDiagnostic("$diagnosticLabel failed: no activity resolved for $action")
            return false
        }

        return runCatching {
            context.startActivity(launchIntent)
            emitDiagnostic("$diagnosticLabel succeeded via $action")
            true
        }.getOrElse { error ->
            emitDiagnostic("$diagnosticLabel failed: ${error.message ?: error::class.java.simpleName}")
            Log.w(TAG, "$diagnosticLabel failed for action=$action", error)
            false
        }
    }

    private fun emitDiagnostic(message: String) {
        onDiagnosticEvent(message)
    }

    private fun isScannerPackageInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(SUNMI_SCANNER_PACKAGE, 0) }.isSuccess

    private fun resolveConnectedState(): DeviceConnectionState {
        return tryEnum("READY")
            ?: tryEnum("CONNECTED")
            ?: tryEnum("AVAILABLE")
            ?: DeviceConnectionState.UNAVAILABLE
    }

    private fun tryEnum(name: String): DeviceConnectionState? =
        runCatching { java.lang.Enum.valueOf(DeviceConnectionState::class.java, name) }.getOrNull()

    companion object {
        private const val TAG = "SunmiScannerProbe"
        private const val SUNMI_SCANNER_PACKAGE = "com.sunmi.scanner"
        private const val SCANNER_SERVICE_CLASS = "com.sunmi.scanner.service.ScannerService"
        private const val INTERFACE_TOKEN = "com.sunmi.scanner.IScanInterface"
        private const val DATA_CALLBACK_TOKEN = "com.sunmi.scanner.IDataCallback"

        private const val ACTION_QR_SCANNER = "com.sunmi.scanner.qrscanner"
        private const val ACTION_SCANNER_SETTINGS = "com.sunmi.scanner.SettingActivity"
        private const val ACTION_DEVICE_SETTINGS = "com.sunmi.scanner.ui.DeviceSettingActivity"
        private const val ACTION_KEYBOARD_SETTINGS = "com.sunmi.scanner.ui.KeyboardSettingActivity"
        private const val ACTION_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_VALUE = "VALUE"
        private const val EXTRA_TYPE = "TYPE"
        private const val EXTRA_CODE_TYPE = "codeType"

        private const val FLASH_CONTROL_COMMAND_KEY = "scan00000107"
        private val FLASH_CONTROL_TX_GUESSES = intArrayOf(5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16)

        private const val TRANSACTION_SEND_KEY_EVENT = 1
        private const val TRANSACTION_SCAN = 2
        private const val TRANSACTION_STOP = 3
        private const val TRANSACTION_ON_CAMERA_ON = 11
        private const val TRANSACTION_REGISTER_CALLBACK = 17
        private const val TRANSACTION_UNREGISTER_CALLBACK = 18

        private const val TRANSACTION_CALLBACK_DATA = 1
    }
}