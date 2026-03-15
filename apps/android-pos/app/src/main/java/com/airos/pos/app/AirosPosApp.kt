package com.airos.pos.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.airos.pos.feature.ticket.TicketScreen
import com.airos.pos.feature.ticket.TicketViewModel
import kotlinx.coroutines.launch

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

private object Routes {
    const val Auth = "auth"
    const val Shift = "shift"
    const val TableMap = "tablemap"
    const val Menu = "menu"
    const val Kitchen = "kitchen"
    const val Scanner = "scanner"
    const val Settings = "settings"
    const val TicketPattern = "ticket/{tableId}"
    const val PaymentPattern = "payment/{ticketId}"
    const val RefundPattern = "refund/{ticketId}"

    fun ticket(tableId: String): String = "ticket/$tableId"
    fun payment(ticketId: String): String = "payment/$ticketId"
    fun refund(ticketId: String): String = "refund/$ticketId"
}

private data class RailDestination(
    val route: String,
    val label: String,
    val iconText: String,
)

private val primaryRailDestinations = listOf(
    RailDestination(Routes.TableMap, "Tables", "T"),
    RailDestination(Routes.Menu, "Menu", "M"),
    RailDestination(Routes.Shift, "Shift", "S"),
)

private val secondaryRailDestinations = listOf(
    RailDestination(Routes.Kitchen, "Kitchen", "K"),
    RailDestination(Routes.Scanner, "Scanner", "B"),
    RailDestination(Routes.Settings, "Settings", "⚙"),
)

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
                startDestination = Routes.Shift,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppShellBackground)
                    .padding(20.dp),
            ) {
                composable(Routes.Shift) {
                    val viewModel: ShiftViewModel = viewModel(factory = ShiftViewModel.factory(appContainer.shiftRepository))
                    val state by viewModel.uiState.collectAsState()
                    ShiftScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        onOpeningFloatChanged = viewModel::updateOpeningFloat,
                        onCountedCashChanged = viewModel::updateCountedCash,
                        onOpenShift = viewModel::openShift,
                        onCloseShift = viewModel::closeShift,
                    )
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
                    TableMapScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        cameraPreviewService = appContainer.cameraPreviewService,
                        onSelectTable = viewModel::selectTable,
                        onOpenSelectedTable = viewModel::openSelectedTable,
                        onOpenTicket = { tableId -> navController.navigate(Routes.ticket(tableId)) },
                        onOpenLivePreview = viewModel::openLivePreview,
                        onRetryLivePreview = viewModel::retryLivePreview,
                        onCloseLivePreview = viewModel::closeLivePreview,
                    )
                }

                composable(Routes.Menu) {
                    val viewModel: MenuViewModel = viewModel(factory = MenuViewModel.factory(appContainer.menuRepository))
                    val state by viewModel.uiState.collectAsState()
                    MenuScreen(
                        state = state,
                        onAddItemToTicket = viewModel::addToTicket,
                        onDecrementTicketLine = viewModel::decrementTicketLine,
                        onRemoveTicketLine = viewModel::removeTicketLine,
                        onApplyLinePercentDiscount = viewModel::applyLinePercentDiscount,
                        onApplyLineAmountDiscount = viewModel::applyLineAmountDiscount,
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
                    route = Routes.TicketPattern,
                    arguments = listOf(navArgument("tableId") { type = NavType.StringType }),
                ) { entry ->
                    val tableId = entry.arguments?.getString("tableId") ?: return@composable
                    val viewModel: TicketViewModel = viewModel(
                        key = "ticket-$tableId",
                        factory = TicketViewModel.factory(tableId, appContainer.ticketRepository, appContainer.menuRepository),
                    )
                    val state by viewModel.uiState.collectAsState()
                    TicketScreen(
                        state = state,
                        onAddItem = viewModel::addItem,
                        onSendToKitchen = viewModel::sendToKitchen,
                        onGoToPayment = { ticketId -> navController.navigate(Routes.payment(ticketId)) },
                        onGoToScanner = { navController.navigate(Routes.Scanner) },
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

@Composable
private fun AppRail(
    navController: NavHostController,
    onSignOut: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var showMoreDestinations by rememberSaveable { mutableStateOf(false) }
    val isSecondaryRouteOpen = secondaryRailDestinations.any { it.route == currentRoute }
    val showSecondaryDestinations = showMoreDestinations || isSecondaryRouteOpen

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(30.dp),
        color = AppShellRailColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.airos_logo),
                contentDescription = "AIROS",
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .size(128.dp),
                contentScale = ContentScale.Fit,
            )

            primaryRailDestinations.forEach { destination ->
                RailNavigationButton(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    prominent = true,
                    onClick = { navController.navigate(destination.route) },
                )
            }

            RailControlButton(
                label = if (showSecondaryDestinations) "Less" else "More",
                iconText = if (showSecondaryDestinations) "−" else "…",
                onClick = { showMoreDestinations = !showMoreDestinations },
            )

            if (showSecondaryDestinations) {
                secondaryRailDestinations.forEach { destination ->
                    RailNavigationButton(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        prominent = false,
                        onClick = { navController.navigate(destination.route) },
                    )
                }
            }
            Button(
                onClick = onSignOut,
                modifier = Modifier.padding(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF153847),
                    contentColor = AppShellTextPrimary,
                ),
            ) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun RailNavigationButton(
    destination: RailDestination,
    selected: Boolean,
    prominent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(if (prominent) 22.dp else 20.dp),
        color = when {
            selected -> AppShellButtonActiveColor
            prominent -> AppShellButtonColor
            else -> AppShellButtonMutedColor
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                selected -> AppShellAccentText.copy(alpha = 0.5f)
                prominent -> AppShellBorderColor
                else -> Color(0x12FFFFFF)
            },
        ),
    ) {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(if (prominent) 100.dp else 88.dp),
            icon = {
                Text(
                    text = destination.iconText,
                    style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                )
            },
            label = {
                Text(
                    text = destination.label,
                    style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                )
            },
            alwaysShowLabel = true,
            colors = NavigationRailItemDefaults.colors(
                indicatorColor = Color.Transparent,
                selectedIconColor = AppShellAccentText,
                selectedTextColor = AppShellTextPrimary,
                unselectedIconColor = if (prominent) AppShellTextSecondary else AppShellTextMuted,
                unselectedTextColor = if (prominent) AppShellTextSecondary else AppShellTextMuted,
            ),
        )
    }
}

@Composable
private fun RailControlButton(
    label: String,
    iconText: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppShellButtonMutedColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x12FFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$iconText\n$label",
                style = MaterialTheme.typography.titleSmall,
                color = AppShellTextMuted,
            )
        }
    }
}
