package com.airos.pos.app

import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val iconText: String,
)

private val topRailDestinations = listOf(
    RailDestination(Routes.TableMap, "Tables", "T"),
    RailDestination(Routes.Menu, "Menu", "M"),
)

private val moreRailDestinations = listOf(
    RailDestination(Routes.Shift, "Shift", "S"),
    RailDestination(Routes.Settings, "Settings", "⚙"),
)

private val signInDestination = RailDestination(Routes.Auth, "Sign in", "↪")

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

    SignedInApp(
        appContainer = appContainer,
        currentStaffId = session!!.staffId,
    )
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
                    val scannerAvailability by appContainer.scannerService.availability.collectAsState()
                    val scannerDebug by appContainer.scannerService.probeDebug.collectAsState()
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
                                scannerProbeStatus = scannerProbeStatus ?: scannerDebug.lastStatus,
                                isScannerProbeFailure = isScannerProbeFailure || scannerDebug.lastError != null,
                                scannerAvailabilityLabel = scannerAvailability.name,
                                scannerPackageLabel = when {
                                    scannerDebug.scannerPackageFound && scannerDebug.qrScannerPackageFound -> "scanner + qr scanner found"
                                    scannerDebug.scannerPackageFound -> "scanner package found"
                                    scannerDebug.qrScannerPackageFound -> "qr scanner package found"
                                    else -> "not found yet"
                                },
                                scannerServiceBindLabel = when {
                                    scannerDebug.scannerServiceBound -> "connected (${scannerDebug.scannerServiceDescriptor ?: "no descriptor"})"
                                    scannerDebug.scannerServiceBindAttempted -> "attempted, not connected"
                                    else -> "not tried yet"
                                },
                                scanManagerBindLabel = when {
                                    scannerDebug.scanManagerBound -> "connected (${scannerDebug.scanManagerDescriptor ?: "no descriptor"})"
                                    scannerDebug.scanManagerBindAttempted -> "attempted, not connected"
                                    else -> "not tried yet"
                                },
                                broadcastStatusLabel = when {
                                    scannerDebug.broadcastSeen -> "received scan broadcast"
                                    scannerDebug.broadcastReceiverRegistered -> "receiver ready, waiting"
                                    else -> "not listening yet"
                                },
                                scannerLastError = scannerDebug.lastError,
                                lastScannerValue = lastScannerValue,
                                onStartScannerProbe = {
                                    scannerProbeStatus = "Scanner probe started. Watch the lines on the right to see which scanner door opened."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Scanner probe started",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.start()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Scanner probe failed to start: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
                                onStopScannerProbe = {
                                    scannerProbeStatus = "Scanner probe stopped."
                                    isScannerProbeFailure = false
                                    Toast.makeText(
                                        context,
                                        "Scanner probe stopped",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.scannerService.stop()
                                            }
                                        }.onFailure { error ->
                                            isScannerProbeFailure = true
                                            scannerProbeStatus = "Scanner probe failed to stop: ${error.message ?: "Unknown error"}"
                                        }
                                    }
                                },
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
                    val viewModel: ScannerViewModel = viewModel(factory = ScannerViewModel.factory(appContainer.scannerService))
                    val state by viewModel.uiState.collectAsState()
                    ScannerScreen(
                        state = state,
                        onStart = viewModel::startScanner,
                        onStop = viewModel::stopScanner,
                        onDebugScan = viewModel::emitDebugScan,
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
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }

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

            topRailDestinations.forEach { destination ->
                RailButton(
                    label = destination.label,
                    iconText = destination.iconText,
                    selected = isRailDestinationSelected(currentRoute, destination.route),
                    onClick = { navController.navigate(destination.route) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            RailButton(
                label = signInDestination.label,
                iconText = signInDestination.iconText,
                selected = isRailDestinationSelected(currentRoute, signInDestination.route),
                onClick = { navController.navigate(signInDestination.route) },
            )

            RailButton(
                label = "Sign out",
                iconText = "⏻",
                selected = false,
                onClick = onSignOut,
            )

            Box {
                RailButton(
                    label = "More",
                    iconText = "…",
                    selected = showMoreMenu,
                    onClick = { showMoreMenu = true },
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    modifier = Modifier.background(AppShellPanelColor),
                ) {
                    moreRailDestinations.forEach { destination ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = destination.label,
                                    color = AppShellTextPrimary,
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                navController.navigate(destination.route)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    iconText: String,
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
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = iconText,
                style = MaterialTheme.typography.titleLarge,
                color = if (selected) AppShellAccentText else AppShellTextSecondary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) AppShellTextPrimary else AppShellTextSecondary,
            )
        }
    }
}

