package com.airos.pos.device.scanner

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.ScanEvent

internal class SunmiScannerProbe(
    private val context: Context,
    private val onAvailabilityChanged: (DeviceConnectionState) -> Unit,
    private val onScanEvent: (ScanEvent) -> Unit,
    private val onDebugStateChanged: (ScannerProbeDebugState) -> Unit,
) {
    private var started = false
    private var broadcastRegistered = false
    private var scannerConnection: ServiceConnection? = null
    private var managerConnection: ServiceConnection? = null
    private var debugState = ScannerProbeDebugState()

    fun start() {
        if (started) return
        started = true

        val scannerInstalled = isPackageInstalled(SUNMI_SCANNER_PACKAGE)
        val qrScannerInstalled = isPackageInstalled(SUNMI_QR_SCANNER_PACKAGE)
        updateDebugState {
            copy(
                scannerPackageFound = scannerInstalled,
                qrScannerPackageFound = qrScannerInstalled,
                lastStatus = "Probe starting",
                lastError = null,
            )
        }

        if (!scannerInstalled && !qrScannerInstalled) {
            onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
            updateDebugState {
                copy(
                    lastStatus = "No supported Sunmi scanner package found",
                    lastError = "Neither com.sunmi.scanner nor com.sunmi.sunmiqrcodescanner is installed",
                )
            }
            Log.i(TAG, "Sunmi scanner package not found on device.")
            return
        }

        registerBroadcastReceiver()
        bindScannerService()
        bindScanManagerService()

        updateDebugState {
            copy(lastStatus = "Probe started. Waiting for bind and scan data")
        }
        Log.i(
            TAG,
            "Sunmi scanner probe started. scannerInstalled=$scannerInstalled qrScannerInstalled=$qrScannerInstalled",
        )
    }

    fun stop() {
        if (!started) return
        started = false

        unregisterBroadcastReceiver()
        unbind(scannerConnection)
        unbind(managerConnection)
        scannerConnection = null
        managerConnection = null
        onAvailabilityChanged(DeviceConnectionState.UNAVAILABLE)
        updateDebugState {
            copy(
                broadcastReceiverRegistered = false,
                scannerServiceBound = false,
                scanManagerBound = false,
                lastStatus = "Probe stopped",
            )
        }
        Log.i(TAG, "Sunmi scanner probe stopped.")
    }

    private fun registerBroadcastReceiver() {
        if (broadcastRegistered) return

        val filter = IntentFilter(ACTION_DATA_CODE_RECEIVED)
        val receiver = scannerResultReceiver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        broadcastRegistered = true
        updateDebugState {
            copy(
                broadcastReceiverRegistered = true,
                lastStatus = "Broadcast receiver registered",
            )
        }
        Log.i(TAG, "Registered scanner broadcast receiver for $ACTION_DATA_CODE_RECEIVED")
    }

    private fun unregisterBroadcastReceiver() {
        if (!broadcastRegistered) return
        runCatching { context.unregisterReceiver(scannerResultReceiver) }
            .onFailure {
                updateDebugState {
                    copy(
                        lastStatus = "Broadcast receiver unregister failed",
                        lastError = it.message,
                    )
                }
                Log.w(TAG, "Failed to unregister scanner broadcast receiver.", it)
            }
        broadcastRegistered = false
    }

    private fun bindScannerService() {
        if (scannerConnection != null) return

        updateDebugState {
            copy(
                scannerServiceBindAttempted = true,
                lastStatus = "Binding ScannerService",
            )
        }
        val component = ComponentName(SUNMI_SCANNER_PACKAGE, SCANNER_SERVICE_CLASS)
        val connection = loggingServiceConnection("ScannerService") { descriptor ->
            updateDebugState {
                copy(
                    scannerServiceBound = true,
                    scannerServiceDescriptor = descriptor,
                    lastStatus = "ScannerService connected",
                    lastError = null,
                )
            }
        }
        val bound = runCatching {
            context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            updateDebugState {
                copy(
                    lastStatus = "ScannerService bind threw an exception",
                    lastError = error.message,
                )
            }
            Log.w(TAG, "ScannerService bind failed.", error)
            false
        }

        if (bound) {
            scannerConnection = connection
            updateDebugState {
                copy(lastStatus = "ScannerService bind requested")
            }
        } else {
            updateDebugState {
                copy(
                    lastStatus = "ScannerService bind returned false",
                    lastError = "bindService returned false for ScannerService",
                )
            }
            Log.i(TAG, "ScannerService bind returned false.")
        }
    }

    private fun bindScanManagerService() {
        if (managerConnection != null) return

        updateDebugState {
            copy(
                scanManagerBindAttempted = true,
                lastStatus = "Binding IScanManager",
            )
        }
        val component = ComponentName(SUNMI_SCANNER_PACKAGE, SCAN_MANAGER_SERVICE_CLASS)
        val connection = loggingServiceConnection("IScanManager") { descriptor ->
            updateDebugState {
                copy(
                    scanManagerBound = true,
                    scanManagerDescriptor = descriptor,
                    lastStatus = "IScanManager connected",
                    lastError = null,
                )
            }
        }
        val bound = runCatching {
            context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            updateDebugState {
                copy(
                    lastStatus = "IScanManager bind threw an exception",
                    lastError = error.message,
                )
            }
            Log.w(TAG, "IScanManager bind failed.", error)
            false
        }

        if (bound) {
            managerConnection = connection
            updateDebugState {
                copy(lastStatus = "IScanManager bind requested")
            }
        } else {
            updateDebugState {
                copy(
                    lastStatus = "IScanManager bind returned false",
                    lastError = "bindService returned false for IScanManager",
                )
            }
            Log.i(TAG, "IScanManager bind returned false.")
        }
    }

    private fun loggingServiceConnection(
        label: String,
        onConnected: (descriptor: String?) -> Unit,
    ): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
                val alive = service?.isBinderAlive ?: false
                val ping = service?.pingBinder() ?: false

                onConnected(descriptor)
                Log.i(
                    TAG,
                    "$label connected. component=$name descriptor=$descriptor alive=$alive ping=$ping",
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (label == "ScannerService") {
                    updateDebugState {
                        copy(
                            scannerServiceBound = false,
                            lastStatus = "ScannerService disconnected",
                        )
                    }
                } else {
                    updateDebugState {
                        copy(
                            scanManagerBound = false,
                            lastStatus = "IScanManager disconnected",
                        )
                    }
                }
                Log.i(TAG, "$label disconnected. component=$name")
            }

            override fun onBindingDied(name: ComponentName?) {
                updateDebugState {
                    copy(
                        lastStatus = "$label binding died",
                        lastError = name?.flattenToShortString(),
                    )
                }
                Log.w(TAG, "$label binding died. component=$name")
            }

            override fun onNullBinding(name: ComponentName?) {
                updateDebugState {
                    copy(
                        lastStatus = "$label returned null binding",
                        lastError = name?.flattenToShortString(),
                    )
                }
                Log.w(TAG, "$label returned null binding. component=$name")
            }
        }

    private fun unbind(connection: ServiceConnection?) {
        if (connection == null) return
        runCatching { context.unbindService(connection) }
            .onFailure {
                updateDebugState {
                    copy(
                        lastStatus = "Failed to unbind scanner service",
                        lastError = it.message,
                    )
                }
                Log.w(TAG, "Failed to unbind scanner service.", it)
            }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfoCompat(packageName)
            true
        }.getOrElse { false }

    private val scannerResultReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_DATA_CODE_RECEIVED) return

                val rawValue = intent.getStringExtra(EXTRA_DATA)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return

                val event = ScanEvent(
                    rawValue = rawValue,
                    symbology = inferSymbology(intent),
                    scannedAtEpochMillis = System.currentTimeMillis(),
                )

                updateDebugState {
                    copy(
                        broadcastSeen = true,
                        lastStatus = "Broadcast scan received",
                        lastError = null,
                    )
                }
                Log.i(
                    TAG,
                    "Received scanner broadcast. action=${intent.action} value=$rawValue symbology=${event.symbology}",
                )
                onScanEvent(event)
            }
        }

    private fun inferSymbology(intent: Intent): String =
        intent.getStringExtra(EXTRA_CODE_TYPE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_TYPE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "SUNMI_BROADCAST"

    private fun PackageManager.getPackageInfoCompat(packageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
    }

    private fun updateDebugState(transform: ScannerProbeDebugState.() -> ScannerProbeDebugState) {
        debugState = debugState.transform()
        onDebugStateChanged(debugState)
    }

    private companion object {
        private const val TAG = "SunmiScannerProbe"
        private const val SUNMI_SCANNER_PACKAGE = "com.sunmi.scanner"
        private const val SUNMI_QR_SCANNER_PACKAGE = "com.sunmi.sunmiqrcodescanner"
        private const val SCANNER_SERVICE_CLASS = "com.sunmi.scanner.service.ScannerService"
        private const val SCAN_MANAGER_SERVICE_CLASS = "com.sunmi.scannerdevice.service.IScanManager"

        private const val ACTION_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_TYPE = "TYPE"
        private const val EXTRA_CODE_TYPE = "codeType"
    }
}
