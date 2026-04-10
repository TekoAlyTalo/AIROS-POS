package com.airos.pos.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.domain.MenuSyncResult
import com.airos.pos.core.model.ScanEvent
import com.airos.pos.feature.auth.AuthScreen
import com.airos.pos.feature.auth.AuthViewModel
import com.airos.pos.feature.kitchen.KitchenScreen
import com.airos.pos.feature.kitchen.KitchenViewModel
import com.airos.pos.feature.menu.MenuScreen
import com.airos.pos.feature.menu.MenuViewModel
import com.airos.pos.feature.payment.PaymentScreen
import com.airos.pos.feature.payment.PaymentViewModel
import com.airos.pos.feature.payment.RefundScreen
import com.airos.pos.feature.payment.RefundViewModel
import com.airos.pos.feature.scanner.ScannerScreen
import com.airos.pos.feature.scanner.ScannerViewModel
import com.airos.pos.feature.settings.SettingsScreen
import com.airos.pos.feature.settings.SettingsViewModel
import com.airos.pos.feature.shift.ShiftScreen
import com.airos.pos.feature.shift.ShiftViewModel
import com.airos.pos.feature.tablemap.TableMapScreen
import com.airos.pos.feature.tablemap.TableMapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AppShellBackground = Color(0xFF060C12)
private val AppShellRailColor = Color(0xFF09121A)
private val AppShellPanelColor = Color(0xFF101A23)
private val AppShellBorderColor = Color(0x18FFFFFF)
private val AppShellButtonColor = Color(0xFF121C27)
private val AppShellButtonMutedColor = Color(0xFF0F171F)
private val AppShellButtonActiveColor = Color(0xFF163847)
private val AppShellTextPrimary = Color(0xFFFBFEFF)
private val AppShellTextSecondary = Color(0xFFE1EBF2)
private val AppShellTextMuted = Color(0xFFB0C0CD)
private val AppShellAccentText = Color(0xFF85F5E0)
private const val CustomerDisplayLogTag = "SunmiCustomerDisplay"
private const val SunmiUiResultLogTag = "AIROS_SUNMI_UI_RESULT"

private object Routes {
    const val Auth = "auth"
    const val Shift = "shift"
    const val TableMap = "tablemap"
    const val Menu = "menu"
    const val MenuPattern = "menu?tableId={tableId}&tableLabel={tableLabel}"
    const val Kitchen = "kitchen"
    const val Scanner = "scanner"
    const val Settings = "settings"
    const val PaymentPattern = "payment/{ticketId}"
    const val RefundPattern = "refund/{ticketId}"

    fun menu(tableId: String? = null, tableLabel: String? = null): String {
        val queryParts = buildList {
            tableId?.let { add("tableId=${Uri.encode(it)}") }
            tableLabel?.let { add("tableLabel=${Uri.encode(it)}") }
        }
        return if (queryParts.isEmpty()) {
            Menu
        } else {
            "$Menu?${queryParts.joinToString("&")}"
        }
    }

    fun payment(ticketId: String): String = "payment/$ticketId"
    fun refund(ticketId: String): String = "refund/$ticketId"
}

private data class RailDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconTint: Color,
)

private val mainRailDestinations = listOf(
    RailDestination(
        route = Routes.TableMap,
        label = "Tables",
        icon = Icons.Filled.Dashboard,
        iconContainerColor = Color(0xFF143A45),
        iconTint = Color(0xFF8DF2E0),
    ),
    RailDestination(
        route = Routes.Menu,
        label = "Menu",
        icon = Icons.Filled.List,
        iconContainerColor = Color(0xFF1D3143),
        iconTint = Color(0xFFB8D8F5),
    ),
    RailDestination(
        route = Routes.Scanner,
        label = "Scan",
        icon = Icons.Filled.Search,
        iconContainerColor = Color(0xFF2E2A4A),
        iconTint = Color(0xFFD7C8FF),
    ),
    RailDestination(
        route = Routes.Shift,
        label = "Shift",
        icon = Icons.Filled.Tune,
        iconContainerColor = Color(0xFF3B2E23),
        iconTint = Color(0xFFFFD8A8),
    ),
    RailDestination(
        route = Routes.Settings,
        label = "Settings",
        icon = Icons.Filled.Settings,
        iconContainerColor = Color(0xFF2D353F),
        iconTint = Color(0xFFE5EEF6),
    ),
)

@Composable
private fun TemporaryFloorPlanStyleCard(
    useRichStyle: Boolean,
    onStyleChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = AppShellPanelColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Floor plan style",
                style = MaterialTheme.typography.titleMedium,
                color = AppShellTextPrimary,
            )
            Text(
                text = "Temporary home for Simple / Rich until personal staff settings are added.",
                style = MaterialTheme.typography.bodySmall,
                color = AppShellTextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TemporaryStyleChip(
                    label = "Simple",
                    selected = !useRichStyle,
                    onClick = { onStyleChange(false) },
                )
                TemporaryStyleChip(
                    label = "Rich",
                    selected = useRichStyle,
                    onClick = { onStyleChange(true) },
                )
            }
        }
    }
}

@Composable
private fun TemporaryStyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) AppShellButtonActiveColor else AppShellButtonMutedColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) AppShellAccentText.copy(alpha = 0.55f) else AppShellBorderColor,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) AppShellTextPrimary else AppShellTextSecondary,
            )
        }
    }
}

@Composable
fun AirosPosApp(
    appContainer: AppContainer,
) {
    val session by appContainer.authRepository.activeSession.collectAsState()

    if (session == null) {
        val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(appContainer.authRepository))
        val authState by authViewModel.uiState.collectAsState()

        // NFC preselect: react to new tag resolutions that happen while auth screen is open.
        // drop(1) skips the current StateFlow value so a stale resolution from a previous
        // session does not auto-select staff on screen entry.
        LaunchedEffect(Unit) {
            NfcProbe.status.drop(1).collect { status ->
                when (val r = status.lastStaffResolution) {
                    is NfcStaffResolution.Matched ->
                        authViewModel.selectStaffByNfc(
                            staffId = r.match.staffId,
                            noticeMessage = "NFC: ${r.match.displayName}",
                        )
                    is NfcStaffResolution.Unknown ->
                        authViewModel.showNfcUnknownTagNotice("Unknown NFC tag — not enrolled")
                    null -> { /* initial state, no tag tapped yet */ }
                }
            }
        }

        AuthScreen(
            state = authState,
            onStaffSelected = authViewModel::selectStaff,
            onDigit = authViewModel::appendPin,
            onBackspace = authViewModel::removePinDigit,
            onClearPin = authViewModel::clearPin,
            onSubmitPin = authViewModel::submitPin,
            onShowManagerOverride = authViewModel::showManagerOverrideDialog,
            onManagerSelected = authViewModel::selectManager,
            onManagerDigit = authViewModel::appendManagerPin,
            onManagerBackspace = authViewModel::removeManagerPinDigit,
            onClearManagerPin = authViewModel::clearManagerPin,
            onConfirmManagerOverride = authViewModel::confirmManagerOverride,
            onDismissManagerOverride = authViewModel::dismissManagerOverrideDialog,
        )
        return
    }

    val syncState by appContainer.menuRepository.syncState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        SignedInApp(
            appContainer = appContainer,
            currentStaffId = session!!.staffId,
        )
        MenuSyncBanner(syncState = syncState)
    }
}

@Composable
private fun MenuSyncBanner(syncState: MenuSyncResult?) {
    when (syncState) {
        is MenuSyncResult.FromCache -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFB45309))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Offline — tuotelista välimuistista",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFEF3C7),
                )
            }
        }
        is MenuSyncResult.NoData -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF991B1B))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Ei yhteyttä — tuotelista ei saatavilla",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFEE2E2),
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun SignedInApp(
    appContainer: AppContainer,
    currentStaffId: String,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var useRichFloorPlanStyle by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppShellBackground),
    ) {
        AppRail(
            navController = navController,
            onSignOut = {
                scope.launch {
                    appContainer.authRepository.signOut()
                }
            },
        )

        Scaffold(
            containerColor = AppShellBackground,
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Routes.TableMap,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppShellBackground)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                composable(Routes.Shift) {
                    val viewModel: ShiftViewModel = viewModel(factory = ShiftViewModel.factory(appContainer.shiftRepository))
                    val state by viewModel.uiState.collectAsState()
                    val context = LocalContext.current
                    var customerDisplayProbeStatus by rememberSaveable { mutableStateOf<String?>(null) }
                    var isCustomerDisplayProbeFailure by rememberSaveable { mutableStateOf(false) }
                    var receiptPrinterProbeStatus by rememberSaveable { mutableStateOf<String?>(null) }
                    var isReceiptPrinterProbeFailure by rememberSaveable { mutableStateOf(false) }
                    var scannerProbeStatus by rememberSaveable { mutableStateOf<String?>(null) }
                    var isScannerProbeFailure by rememberSaveable { mutableStateOf(false) }
                    var lastScannerValue by rememberSaveable { mutableStateOf<String?>(null) }
                    val nfcStatus by NfcProbe.status.collectAsState()
                    var nfcProbeStatus by rememberSaveable { mutableStateOf<String?>(null) }
                    var isNfcProbeFailure by rememberSaveable { mutableStateOf(false) }
                    val activity = context as? Activity
                    val sunmiScannerUiLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult(),
                    ) { result ->
                        val data = result.data
                        val extras = data?.extras

                        val keyList = extras?.keySet()?.toList().orEmpty()
                        val keys = if (keyList.isEmpty()) "-" else keyList.joinToString()
                        if (result.resultCode != Activity.RESULT_OK) {
                            Log.i(SunmiUiResultLogTag, "resultCode=${result.resultCode} (not OK) keys=$keys")
                            scannerProbeStatus = "Sunmi UI cancelled (resultCode=${result.resultCode})"
                            isScannerProbeFailure = true
                            return@rememberLauncherForActivityResult
                        }


                        fun stringifyExtraValue(value: Any?): String? = when (value) {
                            null -> null
                            is ByteArray -> runCatching { String(value, Charsets.UTF_8) }.getOrNull() ?: "byte[${value.size}]"
                            is CharSequence -> value.toString()
                            else -> value.toString()
                        }

                        fun extractSunmiResult(extras: android.os.Bundle?): Triple<String?, String?, ByteArray?> {
                            if (extras == null) return Triple(null, null, null)

                            // 1) Direct extras (some firmware versions use these keys directly)
                            val directBytes = extras.getByteArray("byteArrayExtra")
                            val directValue = extras.getString("VALUE") ?: extras.getString("value")
                            val directType = extras.getString("TYPE") ?: extras.getString("type")
                            if (directValue != null || directBytes != null || directType != null) {
                                return Triple(directValue, directType, directBytes)
                            }

                            // 2) Wrapped under "data" (observed on D3 Mini: ArrayList with a single map/bundle)
                            val dataExtra = extras.get("data")
                            if (dataExtra is ArrayList<*>) {
                                val first = dataExtra.firstOrNull()
                                when (first) {
                                    is android.os.Bundle -> {
                                        val v = first.getString("VALUE") ?: first.getString("value")
                                        val t = first.getString("TYPE") ?: first.getString("type")
                                        val b = first.getByteArray("byteArrayExtra")
                                        return Triple(v, t, b)
                                    }
                                    is Map<*, *> -> {
                                        val v = (first["VALUE"] ?: first["value"]) as? String
                                        val t = (first["TYPE"] ?: first["type"]) as? String
                                        val b = first["byteArrayExtra"] as? ByteArray
                                        return Triple(v, t, b)
                                    }
                                }
                            }

                            return Triple(null, null, null)
                        }

                        val (rawValueFromExtras, rawTypeFromExtras, rawBytesFromExtras) = extractSunmiResult(extras)

                        val byteArrayValue = rawBytesFromExtras
                            ?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }

                        val value = rawValueFromExtras
                            ?: byteArrayValue
                            ?: data?.dataString

                        val type = rawTypeFromExtras
                        val extrasDump = if (keyList.isEmpty()) {
                            "-"
                        } else {
                            keyList.joinToString { key ->
                                val raw = extras?.get(key)
                                val typeName = raw?.javaClass?.simpleName ?: "null"
                                val rendered = stringifyExtraValue(raw) ?: "null"
                                "$key=($typeName)$rendered"
                            }
                        }

                        Log.i(
                            SunmiUiResultLogTag,
                            "resultCode=${result.resultCode} action=${data?.action ?: "-"} data=${data?.dataString ?: "-"} keys=$keys extras=$extrasDump",
                        )

                        if (value != null) {
                            lastScannerValue = value
                            scannerProbeStatus = "Sunmi UI result: ${type ?: "?"}: $value"
                            isScannerProbeFailure = false
                        } else {
                            scannerProbeStatus = "Sunmi UI returned (resultCode=${result.resultCode}) keys=$keys but no VALUE"
                            isScannerProbeFailure = true
                        }
                    }
val scannerAvailability by appContainer.scannerService.availability.collectAsState()
                    val scannerDiagnosticEvents by appContainer.scannerService.diagnosticEvents.collectAsState()
                    LaunchedEffect(appContainer.scannerService) {
                        appContainer.scannerService.scanEvents.collect { event: ScanEvent ->
                            lastScannerValue = event.rawValue
                            scannerProbeStatus = "Scanner read ${event.symbology}: ${event.rawValue}"
                            isScannerProbeFailure = false
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TemporaryFloorPlanStyleCard(
                            useRichStyle = useRichFloorPlanStyle,
                            onStyleChange = { useRichFloorPlanStyle = it },
                        )
                        Box(modifier = Modifier.weight(1f, fill = true)) {
                            ShiftScreen(
                                state = state,
                                currentStaffId = currentStaffId,
                                onOpeningFloatChanged = viewModel::updateOpeningFloat,
                                onCountedCashChanged = viewModel::updateCountedCash,
                                onOpenShift = viewModel::openShift,
                                onCloseShift = viewModel::closeShift,
                                customerDisplayProbeStatus = customerDisplayProbeStatus,
                                isCustomerDisplayProbeFailure = isCustomerDisplayProbeFailure,
                                onRunCustomerDisplayProbe = {
                            Log.i(
                                CustomerDisplayLogTag,
                                "Manual customer-display probe trigger pressed from Shift screen. availabilityBefore=${appContainer.customerDisplayService.availability}",
                            )
                            customerDisplayProbeStatus = "Customer display probe started..."
                            isCustomerDisplayProbeFailure = false
                            Toast.makeText(
                                context,
                                "Customer display probe started",
                                Toast.LENGTH_SHORT,
                            ).show()
                            scope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    appContainer.customerDisplayService.probeCapability(trigger = "shift-manual")
                                }
                                val availability = appContainer.customerDisplayService.availability
                                val statusMessage = if (report.sendSucceeded) {
                                    "Customer display probe succeeded. availability=$availability"
                                } else {
                                    "Customer display probe failed: ${report.blocker ?: "Unknown blocker"}"
                                }
                                isCustomerDisplayProbeFailure = !report.sendSucceeded
                                customerDisplayProbeStatus = statusMessage
                                Log.i(
                                    CustomerDisplayLogTag,
                                    "Manual customer-display probe finished. availability=$availability " +
                                        "path=${report.attemptedPath} service=${report.serviceComponent ?: "-"} " +
                                        "bindAttempted=${report.bindAttempted} bindSucceeded=${report.bindSucceeded} " +
                                        "binderClass=${report.binderClassName ?: "-"} descriptor=${report.binderDescriptor ?: "-"} " +
                                        "resolvedClass=${report.resolvedClassName ?: "-"} managerClassPresent=${report.managerClassPresent} " +
                                        "accessor=${report.managerAccessor ?: "-"} textMethod=${report.textMethod ?: "-"} " +
                                        "sendAttempted=${report.sendAttempted} sendSucceeded=${report.sendSucceeded} " +
                                        "blocker=${report.blocker ?: "-"}",
                                )
                                Toast.makeText(
                                    context,
                                    if (report.sendSucceeded) {
                                        "Customer display probe succeeded"
                                    } else {
                                        "Customer display probe failed"
                                    },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                                receiptPrinterProbeStatus = receiptPrinterProbeStatus,
                                isReceiptPrinterProbeFailure = isReceiptPrinterProbeFailure,
                                onRunReceiptPrinterProbe = {
                                    receiptPrinterProbeStatus = "Receipt printer probe started..."
                                    isReceiptPrinterProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Receipt printer probe started",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            appContainer.printerService.printDiagnosticReceipt()
                                        }
                                        val statusMessage = when (result) {
                                            is com.airos.pos.core.common.PosResult.Success<*> -> "Receipt printer probe succeeded."
                                            is com.airos.pos.core.common.PosResult.Failure -> "Receipt printer probe failed: ${result.message}"
                                        }
                                        isReceiptPrinterProbeFailure = result is com.airos.pos.core.common.PosResult.Failure
                                        receiptPrinterProbeStatus = statusMessage
                                        Toast.makeText(
                                            context,
                                            if (result is com.airos.pos.core.common.PosResult.Success<*>) "Receipt printer probe succeeded" else "Receipt printer probe failed",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                scannerProbeStatus = scannerProbeStatus,
                                isScannerProbeFailure = isScannerProbeFailure,
                                scannerAvailabilityLabel = scannerAvailability.name,
                                lastScannerValue = lastScannerValue,
                                scannerDiagnosticEvents = scannerDiagnosticEvents,
                                onPrepareScanner = {
                                    scannerProbeStatus = "Scanner prepared. Binder + broadcast are now standing by."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Preparing scanner",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.start()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Prepare scanner failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onTriggerScanner = {
                                    scannerProbeStatus = "Trigger scan requested through binder transaction 2 (aidl scan)."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Trigger scan",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.triggerScan()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Trigger scan failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onCameraOnAndScan = {
                                    scannerProbeStatus = "Camera on + scan requested through binder transactions 11 then 2."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Camera on + scan",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.cameraOnAndScan()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Camera on + scan failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onTriggerKeyDown = {
                                    scannerProbeStatus = "Key down sent through binder transaction 1."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Key down",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.keyDown()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Key down failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onTriggerKeyUp = {
                                    scannerProbeStatus = "Key up sent through binder transaction 1."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Key up",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.keyUp()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Key up failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onStopScannerProbe = {
                                    scannerProbeStatus = "Stop scanner requested through binder transaction 3."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Stop scanner",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.stopScanner()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Stop scanner failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onLaunchScannerUi = {
                                     scannerProbeStatus = "Launching Sunmi scanner UI (for result)."
                                     isScannerProbeFailure = false
                                     Log.i(SunmiUiResultLogTag, "Launching Sunmi scanner UI (for result). activityPresent=${activity != null}")
                                     Toast.makeText(
                                         context,
                                         "Launch Sunmi UI",
                                         Toast.LENGTH_SHORT,
                                     ).show()

                                     val intent = Intent("com.sunmi.scanner.qrscanner")
                                     intent.putExtra("IS_OPEN_LIGHT", true)
                                     scope.launch {
                                         if (activity != null) {
                                             sunmiScannerUiLauncher.launch(intent)
                                         } else {
                                             // Fallback: launch via service (uses NEW_TASK, no Activity result).
                                             runCatching {
                                                 withContext(Dispatchers.IO) {
                                                     appContainer.scannerService.launchScannerUi()
                                                 }
                                             }.onFailure { error ->
                                                 isScannerProbeFailure = true
                                                 scannerProbeStatus = "Launch Sunmi scanner UI failed: ${error.message ?: "Unknown error"}"
                                             }
                                         }
                                     }
                                },
                                onOpenScannerSettings = {
                                    scannerProbeStatus = "Opening Sunmi scanner settings."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Scanner settings",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.openScannerSettings()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Open scanner settings failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onOpenScannerDeviceSettings = {
                                    scannerProbeStatus = "Opening Sunmi scanner device settings."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Device settings",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.openScannerDeviceSettings()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Open scanner device settings failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onOpenScannerKeyboardSettings = {
                                    scannerProbeStatus = "Opening Sunmi scanner keyboard settings."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Keyboard settings",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.openScannerKeyboardSettings()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Open scanner keyboard settings failed: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                nfcAdapterSummary = when {
                                    !nfcStatus.adapterPresent -> "No NFC adapter on this device"
                                    !nfcStatus.enabled -> "NFC adapter present but disabled in system settings"
                                    else -> "NFC adapter present and enabled"
                                },
                                nfcProbeStatus = nfcProbeStatus,
                                isNfcProbeFailure = isNfcProbeFailure,
                                lastNfcTagSummary = nfcStatus.lastTagEvent?.let { event ->
                                    "Last tag: uid=${event.uid} techs=${event.techList.joinToString()}"
                                },
                                onRunNfcProbe = {
                                    val manager = context.getSystemService(android.nfc.NfcManager::class.java)
                                    val adapter = manager?.defaultAdapter
                                    NfcProbe.updateAdapterStatus(
                                        adapterPresent = adapter != null,
                                        enabled = adapter?.isEnabled == true,
                                    )
                                    nfcProbeStatus = when {
                                        adapter == null -> "No NFC adapter on this device"
                                        !adapter.isEnabled -> "NFC adapter present but disabled — enable in system settings"
                                        else -> "NFC adapter ready. Tap a card to test."
                                    }
                                    isNfcProbeFailure = adapter == null || adapter?.isEnabled != true
                                },
                                lastNfcStaffResolutionSummary = when (val r = nfcStatus.lastStaffResolution) {
                                    is NfcStaffResolution.Matched ->
                                        "Matched: ${r.match.displayName} · ${r.match.role} · staffId=${r.match.staffId}"
                                    is NfcStaffResolution.Unknown ->
                                        "Unknown tag: ${r.uid} — not in staff mapping"
                                    null -> null
                                },
                                isNfcStaffResolutionUnknown =
                                    nfcStatus.lastStaffResolution is NfcStaffResolution.Unknown,
                    )
                        }
                    }
                }

                composable(Routes.TableMap) {
                    val viewModel: TableMapViewModel = viewModel(
                        factory = TableMapViewModel.factory(
                            appContainer.tableRepository,
                            appContainer.settingsRepository,
                            appContainer.cameraPreviewService,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val selectedTableId = state.selectedTableId
                    val selectedTableLabel = state.floorMap?.tables?.firstOrNull { it.id == selectedTableId }?.label
                    TableMapScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        preferRichFloorPlanStyle = useRichFloorPlanStyle,
                        cameraPreviewService = appContainer.cameraPreviewService,
                        onSelectTable = viewModel::selectTable,
                        onOpenSelectedTable = { staffId ->
                            viewModel.openSelectedTable(staffId)
                            navController.navigate(
                                Routes.menu(
                                    tableId = selectedTableId,
                                    tableLabel = selectedTableLabel,
                                ),
                            )
                        },
                        onJoinTables = { },
                        onOpenLivePreview = viewModel::openLivePreview,
                        onRetryLivePreview = viewModel::retryLivePreview,
                        onCloseLivePreview = viewModel::closeLivePreview,
                    )
                }

                composable(
                    route = Routes.MenuPattern,
                    arguments = listOf(
                        navArgument("tableId") {
                            type = NavType.StringType
                            nullable = true
                        },
                        navArgument("tableLabel") {
                            type = NavType.StringType
                            nullable = true
                        },
                    ),
                ) { entry ->
                    val tableId = entry.arguments?.getString("tableId")
                    val tableLabel = entry.arguments?.getString("tableLabel")
                    val viewModel: MenuViewModel = viewModel(
                        key = "menu-${tableId ?: "general"}",
                        factory = MenuViewModel.factory(
                            menuRepository = appContainer.menuRepository,
                            paymentRepository = appContainer.paymentRepository,
                            printReceipt = appContainer.printerService::printReceipt,
                            openCashDrawer = appContainer.cashDrawerService::openDrawer,
                            verifyDrawerPin = { pin ->
                                val session = appContainer.authRepository.activeSession.first()
                                    ?: return@factory PosResult.Failure("No signed-in staff session for cash drawer access.")
                                when {
                                    session.isManager -> {
                                        when (
                                            val result = appContainer.authRepository.verifyManagerOverride(
                                                managerStaffId = session.staffId,
                                                pin = pin,
                                                reason = ManagerOverrideReason.OPEN_CASH_DRAWER,
                                            )
                                        ) {
                                            is PosResult.Success -> PosResult.Success(Unit)
                                            is PosResult.Failure -> PosResult.Failure(result.message)
                                        }
                                    }

                                    else -> {
                                        when (val result = appContainer.authRepository.signInWithPin(session.staffId, pin)) {
                                            is PosResult.Success -> PosResult.Success(Unit)
                                            is PosResult.Failure -> PosResult.Failure(result.message)
                                        }
                                    }
                                }
                            },
                            activeTableId = tableId,
                            activeTableLabel = tableLabel,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    MenuScreen(
                        state = state,
                        onAddItemToTicket = viewModel::addToTicket,
                        onDecrementTicketLine = viewModel::decrementTicketLine,
                        onRemoveTicketLine = viewModel::removeTicketLine,
                        onApplyLinePercentDiscount = viewModel::applyLinePercentDiscount,
                        onApplyLineAmountDiscount = viewModel::applyLineAmountDiscount,
                        onConfirmPayment = viewModel::submitPayment,
                        onDismissPaymentMessage = viewModel::clearPaymentMessage,
                        onOpenCashDrawer = viewModel::openCashDrawerManually,
                    )
                }

                composable(Routes.Kitchen) {
                    val viewModel: KitchenViewModel = viewModel(factory = KitchenViewModel.factory(appContainer.kitchenRepository))
                    val state by viewModel.uiState.collectAsState()
                    KitchenScreen(
                        state = state,
                        onMarkReady = viewModel::markReady,
                    )
                }

                composable(Routes.Scanner) {
                    val viewModel: ScannerViewModel = viewModel(
                        factory = ScannerViewModel.factory(
                            scannerService = appContainer.scannerService,
                            setTorch = appContainer.torchService::setTorch,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val lastPresentedValue by viewModel.lastPresentedValue.collectAsState()
                    val lastPresentedSymbology by viewModel.lastPresentedSymbology.collectAsState()
                    val scanStatus by viewModel.scanStatus.collectAsState()
                    val isMultiScanEnabled by viewModel.isMultiScanEnabled.collectAsState()
                    val isBusy by viewModel.isBusy.collectAsState()
                    ScannerScreen(
                        state = state,
                        lastPresentedValue = lastPresentedValue,
                        lastPresentedSymbology = lastPresentedSymbology,
                        scanStatus = scanStatus,
                        isMultiScanEnabled = isMultiScanEnabled,
                        isBusy = isBusy,
                        onMultiScanEnabledChange = viewModel::setMultiScanEnabled,
                        onScanWithLight = viewModel::scanWithLight,
                        onStopScanning = viewModel::stopScanning,
                    )
                }

                composable(Routes.Settings) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            appContainer.settingsRepository,
                            appContainer.syncQueueRepository,
                            appContainer.deviceInfoService,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    SettingsScreen(
                        state = state,
                        onTerminalNameChanged = viewModel::updateTerminalNameInput,
                        onEdgeBaseUrlChanged = viewModel::updateEdgeBaseUrlInput,
                        onSaveSettings = viewModel::saveSettings,
                        onOfflineModeChanged = viewModel::setOfflineMode,
                    )
                }

                composable(
                    route = Routes.PaymentPattern,
                    arguments = listOf(navArgument("ticketId") { type = NavType.StringType }),
                ) { entry ->
                    val ticketId = entry.arguments?.getString("ticketId") ?: return@composable
                    val viewModel: PaymentViewModel = viewModel(
                        key = "payment-$ticketId",
                        factory = PaymentViewModel.factory(
                            ticketId = ticketId,
                            paymentRepository = appContainer.paymentRepository,
                            printerService = appContainer.printerService,
                            cashDrawerService = appContainer.cashDrawerService,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    PaymentScreen(
                        state = state,
                        onCollectCash = { viewModel.collect(com.airos.pos.core.model.PaymentMethod.CASH) },
                        onCollectCard = { viewModel.collect(com.airos.pos.core.model.PaymentMethod.CARD) },
                        onCollectVoucher = { viewModel.collect(com.airos.pos.core.model.PaymentMethod.VOUCHER) },
                        onSplitPayment = viewModel::showSplitPaymentPlanned,
                        onPrintReceipt = viewModel::printReceipt,
                        onOpenDrawer = viewModel::openCashDrawer,
                        onGoToRefund = { navController.navigate(Routes.refund(ticketId)) },
                    )
                }

                composable(
                    route = Routes.RefundPattern,
                    arguments = listOf(navArgument("ticketId") { type = NavType.StringType }),
                ) { entry ->
                    val ticketId = entry.arguments?.getString("ticketId") ?: return@composable
                    val viewModel: RefundViewModel = viewModel(
                        key = "refund-$ticketId",
                        factory = RefundViewModel.factory(ticketId, appContainer.paymentRepository),
                    )
                    val state by viewModel.uiState.collectAsState()
                    RefundScreen(
                        state = state,
                        onAmountChanged = viewModel::updateAmount,
                        onReasonChanged = viewModel::updateReason,
                        onSubmitRefund = viewModel::submitRefund,
                    )
                }
            }
        }
    }
}


private fun isRailDestinationSelected(
    currentRoute: String?,
    destinationRoute: String,
): Boolean {
    return when (destinationRoute) {
        Routes.Menu -> currentRoute == Routes.Menu || currentRoute == Routes.MenuPattern
        else -> currentRoute == destinationRoute
    }
}


@Composable
private fun AppRail(
    navController: NavHostController,
    onSignOut: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(112.dp)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = AppShellRailColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.airos_logo),
                contentDescription = "AIROS",
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .size(74.dp),
                contentScale = ContentScale.Fit,
            )

            mainRailDestinations.forEach { destination ->
                RailButton(
                    label = destination.label,
                    icon = destination.icon,
                    iconContainerColor = destination.iconContainerColor,
                    iconTint = destination.iconTint,
                    selected = isRailDestinationSelected(currentRoute, destination.route),
                    onClick = { navController.navigate(destination.route) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            RailButton(
                label = "Sign out",
                icon = Icons.Filled.ExitToApp,
                iconContainerColor = Color(0xFF3C2630),
                iconTint = Color(0xFFFFC6D4),
                selected = false,
                onClick = onSignOut,
            )
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AppShellButtonActiveColor else AppShellButtonMutedColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) AppShellAccentText.copy(alpha = 0.45f) else AppShellBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .width(82.dp)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) iconContainerColor.copy(alpha = 0.95f) else iconContainerColor.copy(alpha = 0.72f),
                    )
                    .size(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) AppShellTextPrimary else iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(1.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) AppShellTextPrimary else AppShellTextSecondary,
            )
        }
    }
}
