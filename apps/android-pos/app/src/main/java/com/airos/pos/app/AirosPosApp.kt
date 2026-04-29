package com.airos.pos.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.ReceiptLong
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.airos.pos.core.model.StaffMember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.ScanEvent
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.domain.MenuSyncResult
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
import com.airos.pos.feature.shift.DeviceDiagnosticsScreen
import com.airos.pos.feature.shift.ShiftScreen
import com.airos.pos.feature.shift.ShiftViewModel
import com.airos.pos.feature.tablemap.TableAcknowledgeActionKind
import com.airos.pos.feature.tablemap.TableMapScreen
import com.airos.pos.feature.tablemap.TableMapViewModel
import com.airos.pos.feature.tablemap.TableSaleOpenSource
import com.airos.pos.feature.tablemap.TableTransferStage
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import com.airos.pos.core.ui.NumericPinPad
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
private const val NfcLogTag = "AIROS_NFC"
private const val SunmiUiResultLogTag = "AIROS_SUNMI_UI_RESULT"
private const val MenuPlaceResultSpotIdKey = "menu_place_result_spot_id"
private const val MenuPlaceResultSpotLabelKey = "menu_place_result_spot_label"

private object Routes {
    const val Auth = "auth"
    const val Shift = "shift"
    const val TableMap = "tablemap"
    const val TableMapPattern = "tablemap?menuPlacePicker={menuPlacePicker}"
    const val Menu = "menu"
    const val Transactions = "transactions"
    const val MenuPattern = "menu?tableId={tableId}&tableLabel={tableLabel}&saleId={saleId}&forceNewSale={forceNewSale}&spotType={spotType}&maxOpenBills={maxOpenBills}"
    const val Kitchen = "kitchen"
    const val Scanner = "scanner"
    const val Diagnostics = "diagnostics"
    const val Settings = "settings"
    const val PaymentPattern = "payment/{ticketId}"
    const val RefundPattern = "refund/{ticketId}"

    fun menu(
        tableId: String? = null,
        tableLabel: String? = null,
        saleId: String? = null,
        forceNewSale: Boolean = false,
        spotType: ServiceSpotType? = null,
        maxOpenBills: Int? = null,
    ): String {
        val queryParts = buildList {
            tableId?.let { add("tableId=${Uri.encode(it)}") }
            tableLabel?.let { add("tableLabel=${Uri.encode(it)}") }
            saleId?.let { add("saleId=${Uri.encode(it)}") }
            if (forceNewSale) add("forceNewSale=true")
            spotType?.let { add("spotType=${Uri.encode(it.name)}") }
            maxOpenBills?.let { add("maxOpenBills=$it") }
        }
        return if (queryParts.isEmpty()) {
            Menu
        } else {
            "$Menu?${queryParts.joinToString("&")}"
        }
    }

    fun tableMap(menuPlacePicker: Boolean = false): String {
        return if (menuPlacePicker) {
            "$TableMap?menuPlacePicker=true"
        } else {
            TableMap
        }
    }

    fun payment(ticketId: String): String = "payment/$ticketId"
    fun refund(ticketId: String): String = "refund/$ticketId"
}

private data class RailDestination(
    val route: String,
    val labelKey: CashierStringKey,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconTint: Color,
)

private val mainRailDestinations = listOf(
    RailDestination(
        route = Routes.TableMap,
        labelKey = CashierStringKey.RailTables,
        icon = Icons.Filled.Dashboard,
        iconContainerColor = Color(0xFF143A45),
        iconTint = Color(0xFF8DF2E0),
    ),
    RailDestination(
        route = Routes.Menu,
        labelKey = CashierStringKey.RailMenu,
        icon = Icons.Filled.List,
        iconContainerColor = Color(0xFF1D3143),
        iconTint = Color(0xFFB8D8F5),
    ),
    RailDestination(
        route = Routes.Transactions,
        labelKey = CashierStringKey.RailTransactions,
        icon = Icons.Filled.ReceiptLong,
        iconContainerColor = Color(0xFF2F2748),
        iconTint = Color(0xFFE2CCFF),
    ),
    RailDestination(
        route = Routes.Scanner,
        labelKey = CashierStringKey.RailScan,
        icon = Icons.Filled.Search,
        iconContainerColor = Color(0xFF2E2A4A),
        iconTint = Color(0xFFD7C8FF),
    ),
    RailDestination(
        route = Routes.Shift,
        labelKey = CashierStringKey.RailShift,
        icon = Icons.Filled.Tune,
        iconContainerColor = Color(0xFF3B2E23),
        iconTint = Color(0xFFFFD8A8),
    ),
    RailDestination(
        route = Routes.Settings,
        labelKey = CashierStringKey.RailSettings,
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

    // Auto clock-in: when a new sign-in happens (session transitions null → non-null),
    // clock the newly signed-in staff in without requiring a separate action.
    // drop(1) skips the initial StateFlow emission so app restarts with a persisted
    // session do not re-clock-in staff who were already clocked in.
    LaunchedEffect(Unit) {
        var prevId: String? = appContainer.authRepository.activeSession.value?.sessionId
        appContainer.authRepository.activeSession.drop(1).collect { newSession ->
            val newId = newSession?.sessionId
            if (newId != null && prevId == null) {
                appContainer.worktimeAttendanceRepository.clockIn(
                    newSession.staffId,
                    newSession.displayName,
                )
            }
            prevId = newId
        }
    }

    if (session == null) {
        val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(appContainer.authRepository))
        val authState by authViewModel.uiState.collectAsState()
        val terminalSettings by appContainer.settingsRepository.observeSettings().collectAsState(
            initial = TerminalSettings(
                terminalName = "",
                edgeBaseUrl = "",
                offlineModeEnabled = true,
            ),
        )
        val nfcDirectLoginEnabled = terminalSettings.nfcDirectLoginEnabled

        // Signed-out auth screen NFC handling.
        // When direct-login is OFF: keep preselect + notice behavior.
        // When direct-login is ON: known tags sign in directly via AuthRepository.
        // drop(1) skips a stale StateFlow value from an earlier screen/session.
        LaunchedEffect(authViewModel, nfcDirectLoginEnabled) {
            Log.d(NfcLogTag, "Auth screen NFC handler active | directLoginEnabled=$nfcDirectLoginEnabled")
            NfcProbe.status.drop(1).collect { status ->
                when (val r = status.lastStaffResolution) {
                    is NfcStaffResolution.Matched -> {
                        Log.i(
                            NfcLogTag,
                            "Matched NFC tag on auth screen | uid=${r.match.uid} staffId=${r.match.staffId} directLoginEnabled=$nfcDirectLoginEnabled",
                        )
                        if (nfcDirectLoginEnabled) {
                            authViewModel.signInWithNfc(
                                staffId = r.match.staffId,
                                noticeMessage = "NFC: ${r.match.displayName}",
                            )
                        } else {
                            authViewModel.selectStaffByNfc(
                                staffId = r.match.staffId,
                                noticeMessage = "NFC: ${r.match.displayName}",
                            )
                        }
                    }
                    is NfcStaffResolution.Unknown -> {
                        Log.w(
                            NfcLogTag,
                            "Unknown NFC tag on auth screen | uid=${r.uid} directLoginEnabled=$nfcDirectLoginEnabled",
                        )
                        authViewModel.showNfcUnknownTagNotice("Unknown NFC tag — not enrolled")
                    }
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

    // Collect current-user attendance state at app-shell level so the offline/syncing
    // notice is visible on every screen, not only when the Shift tab is open.
    // This is the same flow the Shift composable collects; hoisting it here ensures
    // auto-clock-in (which fires on sign-in, before the user navigates to Shift) also
    // surfaces its pending sync state in the global banner.
    val currentStaffId = session!!.staffId
    val attendanceGlobalStateFlow = remember(appContainer.worktimeAttendanceRepository, currentStaffId) {
        appContainer.worktimeAttendanceRepository.observeCurrentUserState(currentStaffId)
    }
    val attendanceGlobalState by attendanceGlobalStateFlow.collectAsState(
        initial = WorktimeEffectiveAttendanceState(),
    )
    val attendanceSyncNotice: String? = when {
        attendanceGlobalState.unresolvedEventCount > 0 &&
            attendanceGlobalState.syncMetadata.syncState == "syncing" -> "Syncing attendance..."
        attendanceGlobalState.syncMetadata.syncState == "reconciling" ->
            "Confirming attendance with server..."
        attendanceGlobalState.unresolvedEventCount > 0 -> "Offline, syncing later"
        else -> null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SignedInApp(
            appContainer = appContainer,
            currentStaffId = currentStaffId,
            currentStaffName = session!!.displayName,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuSyncBanner(syncState = syncState)
            AttendanceSyncBanner(message = attendanceSyncNotice)
        }
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
                    text = "Tuotelista välimuistista",
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
                    text = "Tuotelista ei saatavilla",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFEE2E2),
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun AttendanceSyncBanner(message: String?) {
    if (message == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D2F3D))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB3E5FC),
        )
    }
}

@Composable
private fun SignedInApp(
    appContainer: AppContainer,
    currentStaffId: String,
    currentStaffName: String,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var useRichFloorPlanStyle by rememberSaveable { mutableStateOf(false) }
    val terminalSettings by appContainer.settingsRepository.observeSettings().collectAsState(
        initial = TerminalSettings(
            terminalName = "",
            edgeBaseUrl = "",
            offlineModeEnabled = true,
        ),
    )

    var signOutDialogVisible by remember { mutableStateOf(false) }
    var signOutPin by remember { mutableStateOf("") }
    var signOutPinError by remember { mutableStateOf<String?>(null) }

    var sellerSwitchDialogVisible by remember { mutableStateOf(false) }
    var sellerSwitchSelectedStaff by remember { mutableStateOf<StaffMember?>(null) }
    var sellerSwitchPin by remember { mutableStateOf("") }
    var sellerSwitchPinError by remember { mutableStateOf<String?>(null) }
    val quickSelectStaffFlow = remember(appContainer.authRepository) {
        appContainer.authRepository.observeQuickSelectStaff()
    }
    val quickSelectStaff by quickSelectStaffFlow.collectAsState(initial = emptyList())

    // NFC fast-path: current staff's badge confirms sign-out identity without PIN entry.
    LaunchedEffect(signOutDialogVisible, currentStaffId) {
        if (!signOutDialogVisible) return@LaunchedEffect
        NfcProbe.status.drop(1).collect { status ->
            val r = status.lastStaffResolution
            if (r is NfcStaffResolution.Matched && r.match.staffId == currentStaffId) {
                signOutDialogVisible = false
                signOutPin = ""
                signOutPinError = null
                scope.launch {
                    appContainer.worktimeAttendanceRepository.clockOut(currentStaffId, currentStaffName)
                    appContainer.authRepository.signOut()
                }
            }
        }
    }

    // Rule 6 + Rule 7: NFC badge while already signed in.
    // Same person → restrained acknowledgement, no action.
    // Different person → seller switch: sign out current user (NO clockOut — work session continues),
    //   sign in new user. Auto-clock-in fires from AirosPosApp LaunchedEffect if not already clocked in.
    val sellerSwitchContext = LocalContext.current
    LaunchedEffect(currentStaffId) {
        NfcProbe.status.drop(1).collect { status ->
            if (signOutDialogVisible) return@collect
            val r = status.lastStaffResolution as? NfcStaffResolution.Matched ?: return@collect
            val match = r.match
            if (match.staffId == currentStaffId) {
                Toast.makeText(sellerSwitchContext, "Already signed in as ${match.displayName}", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    appContainer.authRepository.signOut()
                    appContainer.authRepository.signInWithNfc(match.staffId)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppShellBackground),
    ) {
        AppRail(
            navController = navController,
            currentStaffName = currentStaffName,
            onSellerSwitchRequested = {
                // Rule 8: sign-out dialog has priority — never open seller switch while it is active.
                if (!signOutDialogVisible) {
                    sellerSwitchSelectedStaff = null
                    sellerSwitchPin = ""
                    sellerSwitchPinError = null
                    sellerSwitchDialogVisible = true
                }
            },
            onSignOut = {
                signOutPin = ""
                signOutPinError = null
                sellerSwitchDialogVisible = false  // Rule 8: sign-out takes priority
                signOutDialogVisible = true
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
                    val viewModel: ShiftViewModel = viewModel(
                        factory = ShiftViewModel.factory(
                            shiftRepository = appContainer.shiftRepository,
                            defaultOpeningFloatCents = terminalSettings.defaultOpeningFloatCents,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val attendanceRepository = appContainer.worktimeAttendanceRepository
                    val attendanceFlow = remember(attendanceRepository) { attendanceRepository.observeAttendance() }
                    val attendance by attendanceFlow.collectAsState(initial = WorktimeAttendanceSnapshot())
                    val currentUserStateFlow = remember(attendanceRepository, currentStaffId) { attendanceRepository.observeCurrentUserState(currentStaffId) }
                    val currentAttendance by currentUserStateFlow.collectAsState(initial = WorktimeEffectiveAttendanceState())
                    val attendanceScope = rememberCoroutineScope()
                    var attendanceBusy by remember { mutableStateOf(false) }
                    var attendanceMessage by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(attendanceRepository, currentStaffId, currentStaffName) {
                        attendanceRepository.syncAndRefreshCurrentUser(currentStaffId, currentStaffName)
                    }

                    val polledMyEntry = attendance.currentlyOnSite.find { it.staffId == currentStaffId }
                    val myEntry = currentAttendance.activeSession?.toAttendanceEntry(
                        fallbackStaffName = currentStaffName,
                        fallbackDurationMinutes = polledMyEntry?.durationMinutes ?: 0.0,
                    )
                    val isClockedIn = currentAttendance.activeSession != null
                    val attendanceSyncBlockedMessage = if (currentAttendance.syncMetadata.syncState == "contract_blocked") {
                        "Attendance sync error — a clock event was rejected by the server and will not be retried. Contact support."
                    } else {
                        null
                    }
                    val attendanceNoticeMessage = when {
                        currentAttendance.syncMetadata.syncState == "contract_blocked" -> null
                        currentAttendance.unresolvedEventCount > 0 &&
                            currentAttendance.syncMetadata.syncState == "syncing" -> "Syncing attendance..."
                        currentAttendance.syncMetadata.syncState == "reconciling" -> "Confirming attendance with server..."
                        currentAttendance.unresolvedEventCount > 0 -> "Offline, syncing later"
                        else -> null
                    }

                    // Build a deterministic, deduplicated snapshot for the UI:
                    // Step 1 — offline-first: if the current user is locally clocked in but
                    //   absent from the backend on-site list (backend stale/offline), prepend
                    //   a synthetic local entry so they remain visible.
                    // Step 2 — deduplicate on-site by staffId (first/active entry wins).
                    // Step 3 — strip from clockedInToday any staffId already in on-site;
                    //   this removes the ghost "done · 0min" row that appears when the
                    //   backend returns the same person in both lists simultaneously.
                    // Step 4 — deduplicate clockedInToday by staffId, keeping the entry with
                    //   the highest durationMinutes (the most meaningful completed session).
                    val effectiveAttendance = run {
                        val rawOnSite = if (currentAttendance.activeSession != null &&
                            attendance.currentlyOnSite.none { it.staffId == currentStaffId }
                        ) {
                            val localEntry = currentAttendance.activeSession!!.toAttendanceEntry(
                                fallbackStaffName = currentStaffName,
                                fallbackDurationMinutes = 0.0,
                            )
                            listOf(localEntry) + attendance.currentlyOnSite
                        } else {
                            attendance.currentlyOnSite
                        }
                        val dedupedOnSite = rawOnSite.distinctBy { it.staffId }
                        val onSiteIds = dedupedOnSite.mapTo(mutableSetOf()) { it.staffId }
                        val dedupedClockedInToday = attendance.clockedInToday
                            .filter { it.staffId !in onSiteIds }
                            .groupBy { it.staffId }
                            .values
                            .map { entries -> entries.maxByOrNull { it.durationMinutes } ?: entries.first() }
                        attendance.copy(
                            currentlyOnSite = dedupedOnSite,
                            clockedInToday = dedupedClockedInToday,
                        )
                    }

                    ShiftScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        currentStaffName = currentStaffName,
                        onOpeningFloatChanged = viewModel::updateOpeningFloat,
                        onCountedCashChanged = viewModel::updateCountedCash,
                        onOpenShift = viewModel::openShift,
                        onCloseShift = viewModel::closeShift,
                        attendance = effectiveAttendance,
                        isClockedIn = isClockedIn,
                        myAttendanceEntry = myEntry,
                        attendanceStateLoading = false,
                        attendanceBusy = attendanceBusy,
                        attendanceNoticeMessage = attendanceNoticeMessage,
                        attendanceMessage = attendanceSyncBlockedMessage ?: attendanceMessage,
                        onClockIn = {
                            attendanceScope.launch {
                                attendanceBusy = true
                                attendanceMessage = null
                                try {
                                    when (val result = attendanceRepository.clockIn(currentStaffId, currentStaffName)) {
                                        is PosResult.Success -> attendanceMessage = null
                                        is PosResult.Failure -> attendanceMessage = result.message
                                    }
                                } finally {
                                    attendanceBusy = false
                                }
                            }
                        },
                        onClockOut = {
                            signOutPin = ""
                            signOutPinError = null
                            signOutDialogVisible = true
                        },
                    )

                }

                composable(Routes.Diagnostics) {
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
                    DeviceDiagnosticsScreen(
                        customerDisplayProbeStatus = customerDisplayProbeStatus,
                        isCustomerDisplayProbeFailure = isCustomerDisplayProbeFailure,
                        onRunCustomerDisplayProbe = {
                            Log.i(
                                CustomerDisplayLogTag,
                                "Manual customer-display probe trigger pressed. availabilityBefore=${appContainer.customerDisplayService.availability}",
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
                                    appContainer.customerDisplayService.probeCapability(trigger = "diagnostics-manual")
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
                                "Unknown tag: ${r.uid} — not enrolled"
                            null -> null
                        },
                        isNfcStaffResolutionUnknown =
                            nfcStatus.lastStaffResolution is NfcStaffResolution.Unknown,
                    )
                }

                composable(
                    route = Routes.TableMapPattern,
                    arguments = listOf(
                        navArgument("menuPlacePicker") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val context = LocalContext.current
                    val menuPlacePicker = entry.arguments?.getBoolean("menuPlacePicker") ?: false
                    val viewModel: TableMapViewModel = viewModel(
                        key = "tablemap-$currentStaffId",
                        factory = TableMapViewModel.factory(
                            currentStaffId = currentStaffId,
                            currentStaffDisplayName = currentStaffName,
                            tableRepository = appContainer.tableRepository,
                            settingsRepository = appContainer.settingsRepository,
                            cameraPreviewService = appContainer.cameraPreviewService,
                            openSaleRepository = appContainer.openSaleRepository,
                            staffUiPreferencesRepository = appContainer.staffUiPreferencesRepository,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val selectedTableId = state.selectedTableId
                    val selectedTableLabel = state.floorMap?.tables?.firstOrNull { it.id == selectedTableId }?.label
                    TableMapScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        preferRichFloorPlanStyle = useRichFloorPlanStyle,
                        placeSelectionMode = menuPlacePicker,
                        cameraPreviewService = appContainer.cameraPreviewService,
                        onSelectTable = viewModel::selectTable,
                        onSelectPlace = { tableId, tableLabel ->
                            if (menuPlacePicker) {
                                navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotIdKey, tableId)
                                navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotLabelKey, tableLabel)
                                navController.popBackStack()
                            }
                        },
                        onViewModeChange = viewModel::setViewMode,
                        onFloorPlanViewportChange = viewModel::setFloorPlanViewport,
                        onOpenTableSale = { tableId, tableLabel, saleId, source ->

                            val transfer = state.transferState
                            val hasSelectedBills = transfer?.selectedSaleIds?.isNotEmpty() == true

                            // Hard guard: while transfer mode is active, table taps should never open Menu.
                            // If user taps a different table while having 1+ selected bills, treat it as target selection.
                            if (source == TableSaleOpenSource.TABLE_TAP && transfer != null) {
                                if (hasSelectedBills && tableId != transfer.sourceSpotId) {
                                    viewModel.transferSelectedBillsTo(tableId)
                                }
                            } else if (source == TableSaleOpenSource.TABLE_TAP && transfer?.stage == TableTransferStage.PICKING_TARGET) {
                                viewModel.transferSelectedBillsTo(tableId)
                            } else if (menuPlacePicker) {
                                navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotIdKey, tableId)
                                navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotLabelKey, tableLabel)
                                navController.popBackStack()
                            } else {
                                val openBillCount = state.openChecksBySpotId[tableId]?.count ?: 0
                                val blockTableTapMultiBill =
                                    source == TableSaleOpenSource.TABLE_TAP && openBillCount > 1

                                if (!blockTableTapMultiBill) {
                                    val selectedSpot = state.floorMap?.tables?.firstOrNull { it.id == tableId }
                                    navController.navigate(
                                        Routes.menu(
                                            tableId = tableId,
                                            tableLabel = tableLabel,
                                            saleId = saleId,
                                            forceNewSale = source == TableSaleOpenSource.NEW_SALE_BUTTON,
                                            spotType = selectedSpot?.spotType,
                                            maxOpenBills = selectedSpot?.maxOpenBills,
                                        ),
                                    )
                                }
                            }

                        },
                        onStartTransferMode = viewModel::startTransferMode,
                        onStartTransferModeForSale = viewModel::startTransferModeForSale,
                        onToggleTransferSale = viewModel::toggleTransferSale,
                        onBeginTransferTargetSelection = viewModel::beginTransferTargetSelection,
                        onTransferTargetSelected = viewModel::transferSelectedBillsTo,
                        onCancelTransferMode = viewModel::cancelTransferMode,
                        onOpenLivePreview = viewModel::openLivePreview,
                        onRetryLivePreview = viewModel::retryLivePreview,
                        onCloseLivePreview = viewModel::closeLivePreview,
                        onAcknowledgeTableAction = { tableId, actionKind ->
                            scope.launch {
                                val backendTruthRepository = appContainer.tableRepository as? BackendTruthTableRepository
                                if (backendTruthRepository == null) {
                                    Log.w(
                                        "AIROS",
                                        "[AirosPosApp] table action acknowledge requested but tableRepository is not BackendTruthTableRepository.",
                                    )
                                    Toast.makeText(
                                        context,
                                        "Table acknowledgement is not available in this build.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@launch
                                }

                                val session = appContainer.authRepository.activeSession.value
                                val acknowledged = withContext(Dispatchers.IO) {
                                    when (actionKind) {
                                        TableAcknowledgeActionKind.CHECK ->
                                            backendTruthRepository.acknowledgeCheckTable(
                                                tableId = tableId,
                                                actorStaffId = session?.staffId,
                                                actorDisplayName = session?.displayName,
                                            )
                                        TableAcknowledgeActionKind.NEEDS_CLEANING ->
                                            backendTruthRepository.markCleanedTable(
                                                tableId = tableId,
                                                actorStaffId = session?.staffId,
                                                actorDisplayName = session?.displayName,
                                            )
                                    }
                                }
                                val actionLabel = when (actionKind) {
                                    TableAcknowledgeActionKind.CHECK -> "CHECK"
                                    TableAcknowledgeActionKind.NEEDS_CLEANING -> "Cleaning"
                                }

                                Toast.makeText(
                                    context,
                                    if (acknowledged) {
                                        "$actionLabel acknowledged"
                                    } else {
                                        "$actionLabel acknowledge failed"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                composable(Routes.Transactions) {
                    TransactionsRoute(
                        openSaleRepository = appContainer.openSaleRepository,
                        salesLedgerOutboxDao = appContainer.database.salesLedgerOutboxDao(),
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
                        navArgument("saleId") {
                            type = NavType.StringType
                            nullable = true
                        },
                        navArgument("forceNewSale") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument("spotType") {
                            type = NavType.StringType
                            nullable = true
                        },
                        navArgument("maxOpenBills") {
                            type = NavType.IntType
                            nullable = true
                            defaultValue = -1
                        },
                    ),
                ) { entry ->
                    val context = LocalContext.current
                    val tableId = entry.arguments?.getString("tableId")
                    val tableLabel = entry.arguments?.getString("tableLabel")
                    val saleId = entry.arguments?.getString("saleId")
                    val forceNewSale = entry.arguments?.getBoolean("forceNewSale") ?: false
                    val initialTableSpotType = entry.arguments
                        ?.getString("spotType")
                        ?.let { raw -> runCatching { ServiceSpotType.valueOf(raw) }.getOrNull() }
                    val initialTableMaxOpenBills = entry.arguments
                        ?.getInt("maxOpenBills")
                        ?.takeIf { it >= 0 }
                    val viewModel: MenuViewModel = viewModel(
                        key = "menu-${saleId ?: tableId ?: "general"}-${if (forceNewSale) "new" else "existing"}",
                        factory = MenuViewModel.factory(
                            menuRepository = appContainer.menuRepository,
                            paymentRepository = appContainer.paymentRepository,
                            nfcIdentityRepository = appContainer.nfcIdentityRepository,
                            printReceipt = appContainer.printerService::printReceipt,
                            openCashDrawer = appContainer.cashDrawerService::openDrawer,
                            customerDisplayService = appContainer.customerDisplayService,
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
                            initialTableSpotType = initialTableSpotType,
                            initialTableMaxOpenBills = initialTableMaxOpenBills,
                            activeSaleId = saleId,
                            forceNewSale = forceNewSale,
                            tableRepository = appContainer.tableRepository,
                            activeStaffIdProvider = {
                                appContainer.authRepository.activeSession.value?.staffId
                            },
                            activeStaffDisplayNameProvider = {
                                appContainer.authRepository.activeSession.value?.displayName ?: currentStaffName
                            },
                            openSaleRepository = appContainer.openSaleRepository,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val selectedSpotId by entry.savedStateHandle.getStateFlow<String?>(MenuPlaceResultSpotIdKey, null).collectAsState()
                    val selectedSpotLabel by entry.savedStateHandle.getStateFlow<String?>(MenuPlaceResultSpotLabelKey, null).collectAsState()
                    LaunchedEffect(viewModel, selectedSpotId, selectedSpotLabel) {
                        val resolvedSpotId = selectedSpotId
                        val resolvedSpotLabel = selectedSpotLabel
                        if (resolvedSpotId.isNullOrBlank() || resolvedSpotLabel.isNullOrBlank()) {
                            return@LaunchedEffect
                        }
                        entry.savedStateHandle.remove<String>(MenuPlaceResultSpotIdKey)
                        entry.savedStateHandle.remove<String>(MenuPlaceResultSpotLabelKey)
                        viewModel.requestServiceSpotAssignment(resolvedSpotId, resolvedSpotLabel)
                    }
                    LaunchedEffect(viewModel, state.receiptHandoffWaiting) {
                        if (!state.receiptHandoffWaiting) {
                            return@LaunchedEffect
                        }
                        NfcProbe.status.drop(1).collect { status ->
                            status.lastTagEvent?.let { event ->
                                viewModel.handleReceiptHandoffTap(
                                    canonicalUid = event.uid,
                                    detectedAtEpochMillis = event.timestamp,
                                )
                            }
                        }
                    }
                    MenuScreen(
                        state = state,
                        onAddItemToTicket = viewModel::addToTicket,
                        onDecrementTicketLine = viewModel::decrementTicketLine,
                        onRemoveTicketLine = viewModel::removeTicketLine,
                        onApplyLinePercentDiscount = viewModel::applyLinePercentDiscount,
                        onApplyLineAmountDiscount = viewModel::applyLineAmountDiscount,
                        onConfirmPayment = viewModel::submitPayment,
                        onDismissPaymentMessage = viewModel::clearPaymentMessage,
                        onStartReceiptHandoff = viewModel::startReceiptHandoff,
                        onCancelReceiptHandoff = viewModel::cancelReceiptHandoff,
                        onOpenCashDrawer = viewModel::openCashDrawerManually,
                        onStartNewSale = {
                            scope.launch {
                                runCatching {
                                    appContainer.openSaleRepository.createOpenSale(null, null)
                                }.onSuccess { sale ->
                                    navController.navigate(Routes.menu(saleId = sale.saleId))
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "Uutta laskua ei voitu avata.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onOpenServiceSpotSelection = {
                            navController.navigate(Routes.tableMap(menuPlacePicker = true))
                        },
                        onAcknowledgeCheck = {
                            scope.launch {
                                val backendTruthRepository = appContainer.tableRepository as? BackendTruthTableRepository
                                if (backendTruthRepository == null) {
                                    Log.w(
                                        "AIROS",
                                        "[AirosPosApp] Menu CHECK acknowledge requested but tableRepository is not BackendTruthTableRepository.",
                                    )
                                    Toast.makeText(
                                        context,
                                        "CHECK acknowledge is not available in this build.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@launch
                                }

                                val backendTableId = state.activeTableId
                                if (backendTableId.isNullOrBlank()) {
                                    Toast.makeText(
                                        context,
                                        "No active table is selected for CHECK acknowledge.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@launch
                                }

                                val session = appContainer.authRepository.activeSession.value
                                val acknowledged = withContext(Dispatchers.IO) {
                                    backendTruthRepository.acknowledgeCheckTable(
                                        tableId = backendTableId,
                                        actorStaffId = session?.staffId,
                                        actorDisplayName = session?.displayName,
                                    )
                                }

                                if (acknowledged) {
                                    viewModel.clearCheckAddBlockedWarning()
                                }

                                Toast.makeText(
                                    context,
                                    if (acknowledged) {
                                        "CHECK acknowledged"
                                    } else {
                                        "CHECK acknowledge failed"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onScreenShown = viewModel::syncCustomerDisplayToCurrentTicket,
                        onScreenDisposed = viewModel::clearCustomerDisplay,
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
                        factory = ScannerViewModel.factory(),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val lastPresentedValue by viewModel.lastPresentedValue.collectAsState()
                    val lastPresentedSymbology by viewModel.lastPresentedSymbology.collectAsState()
                    val scanStatus by viewModel.scanStatus.collectAsState()
                    val isMultiScanEnabled by viewModel.isMultiScanEnabled.collectAsState()
                    val isBusy by viewModel.isBusy.collectAsState()
                    val isPreviewVisible by viewModel.isPreviewVisible.collectAsState()
                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val cameraScannerController = appContainer.cameraScannerController
                    val previewHostView = androidx.compose.runtime.remember(context) {
                        cameraScannerController.createPreviewView(context)
                    }
                    var hasCameraPermission by rememberSaveable {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED,
                        )
                    }
                    var pendingScanStart by rememberSaveable { mutableStateOf(false) }
                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted: Boolean ->
                        hasCameraPermission = granted
                        if (granted) {
                            if (pendingScanStart) {
                                viewModel.scanWithLight()
                            }
                        } else {
                            viewModel.onCameraPermissionDenied()
                        }
                        pendingScanStart = false
                    }

                    LaunchedEffect(cameraScannerController) {
                        cameraScannerController.bindingState.collect { bindingState: com.airos.pos.device.camera.CameraScannerController.BindingState ->
                            val availability = when (bindingState) {
                                com.airos.pos.device.camera.CameraScannerController.BindingState.BOUND -> DeviceConnectionState.READY
                                com.airos.pos.device.camera.CameraScannerController.BindingState.UNBOUND -> DeviceConnectionState.UNAVAILABLE
                                com.airos.pos.device.camera.CameraScannerController.BindingState.ERROR -> DeviceConnectionState.UNAVAILABLE
                            }
                            viewModel.onPreviewAvailabilityChanged(availability)
                        }
                    }
                    LaunchedEffect(cameraScannerController, isMultiScanEnabled) {
                        var blinkJob: Job? = null
                        val effectScope = this
                        cameraScannerController.scanEvents.collect { event: ScanEvent ->
                            viewModel.onExternalScanEvent(event)
                            if (isMultiScanEnabled) {
                                blinkJob?.cancel()
                                blinkJob = effectScope.launch {
                                    try {
                                        cameraScannerController.setTorch(false)
                                    } catch (_: Throwable) {
                                    }
                                    kotlinx.coroutines.delay(500)
                                    try {
                                        cameraScannerController.setTorch(true)
                                    } catch (_: Throwable) {
                                    }
                                }
                            } else {
                                try {
                                    cameraScannerController.setTorch(false)
                                } catch (_: Throwable) {
                                }
                                try {
                                    cameraScannerController.unbind()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    LaunchedEffect(isPreviewVisible, hasCameraPermission, lifecycleOwner, cameraScannerController) {
                        if (isPreviewVisible) {
                            if (!hasCameraPermission) {
                                viewModel.onCameraPermissionDenied()
                            } else {
                                try {
                                    cameraScannerController.bindToLifecycle(lifecycleOwner, previewHostView)
                                    cameraScannerController.setTorch(true)
                                    viewModel.onPreviewSessionStarted()
                                } catch (error: Throwable) {
                                    try {
                                        cameraScannerController.setTorch(false)
                                    } catch (_: Throwable) {
                                    }
                                    try {
                                        cameraScannerController.unbind()
                                    } catch (_: Throwable) {
                                    }
                                    viewModel.onPreviewSessionFailed(error.message)
                                }
                            }
                        } else {
                            try {
                                cameraScannerController.setTorch(false)
                            } catch (_: Throwable) {
                            }
                            try {
                                cameraScannerController.unbind()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    androidx.compose.runtime.DisposableEffect(cameraScannerController) {
                        onDispose {
                            scope.launch {
                                try {
                                    cameraScannerController.setTorch(false)
                                } catch (_: Throwable) {
                                }
                                try {
                                    cameraScannerController.unbind()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    ScannerScreen(
                        state = state,
                        lastPresentedValue = lastPresentedValue,
                        lastPresentedSymbology = lastPresentedSymbology,
                        scanStatus = scanStatus,
                        isMultiScanEnabled = isMultiScanEnabled,
                        isBusy = isBusy,
                        isPreviewVisible = isPreviewVisible,
                        hasCameraPermission = hasCameraPermission,
                        previewContent = {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { previewHostView },
                            )
                        },
                        onMultiScanEnabledChange = viewModel::setMultiScanEnabled,
                        onScanWithLight = {
                            if (hasCameraPermission) {
                                viewModel.scanWithLight()
                            } else {
                                pendingScanStart = true
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onStopScanning = {
                            scope.launch {
                                try {
                                    cameraScannerController.setTorch(false)
                                } catch (_: Throwable) {
                                }
                                try {
                                    cameraScannerController.unbind()
                                } catch (_: Throwable) {
                                }
                            }
                            viewModel.stopScanning()
                        },
                    )
                }

                composable(Routes.Settings) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            settingsRepository = appContainer.settingsRepository,
                            syncQueueRepository = appContainer.syncQueueRepository,
                            authRepository = appContainer.authRepository,
                            nfcIdentityRepository = appContainer.nfcIdentityRepository,
                            deviceInfoService = appContainer.deviceInfoService,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val nfcStatus by NfcProbe.status.collectAsState()
                    LaunchedEffect(viewModel, state.hasPendingNfcEnrollment) {
                        if (!state.hasPendingNfcEnrollment) {
                            return@LaunchedEffect
                        }
                        NfcProbe.status.drop(1).collect { status ->
                            status.lastTagEvent?.let { event ->
                                viewModel.handlePendingNfcTag(
                                    canonicalUid = event.uid,
                                    detectedAtEpochMillis = event.timestamp,
                                )
                            }
                        }
                    }
                    SettingsScreen(
                        state = state,
                        onTerminalNameChanged = viewModel::updateTerminalNameInput,
                        onEdgeBaseUrlChanged = viewModel::updateEdgeBaseUrlInput,
                        onRestaurantKeyChanged = viewModel::updateRestaurantKeyInput,
                        onDefaultOpeningFloatChanged = viewModel::updateDefaultOpeningFloatInput,
                        onSaveSettings = viewModel::saveSettings,
                        onOfflineModeChanged = viewModel::setOfflineMode,
                        onNfcDirectLoginChanged = viewModel::setNfcDirectLoginEnabled,
                        onBeginNfcEnrollment = viewModel::beginNfcEnrollment,
                        onCancelNfcEnrollment = viewModel::cancelNfcEnrollment,
                        onUseLastSeenNfcTag = {
                            nfcStatus.lastTagEvent?.uid?.let(viewModel::useLastSeenNfcTag)
                        },
                        onRemoveNfcEnrollment = viewModel::removeNfcEnrollment,
                        onCustomerEnrollmentLabelChanged = viewModel::updateCustomerEnrollmentLabelInput,
                        onBeginCustomerEnrollment = viewModel::beginCustomerEnrollment,
                        onRemoveCustomerEnrollment = viewModel::removeCustomerEnrollment,
                        lastNfcTagSummary = nfcStatus.lastTagEvent?.let { event ->
                            "uid=${event.uid} techs=${event.techList.joinToString()}"
                        },
                        lastNfcTagUid = nfcStatus.lastTagEvent?.uid,
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

    if (sellerSwitchDialogVisible) {
        Dialog(onDismissRequest = {
            sellerSwitchDialogVisible = false
            sellerSwitchSelectedStaff = null
            sellerSwitchPin = ""
            sellerSwitchPinError = null
        }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppShellPanelColor,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Switch seller",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppShellTextPrimary,
                    )
                    if (sellerSwitchSelectedStaff == null) {
                        Text(
                            text = "Who is taking over?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppShellTextSecondary,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            quickSelectStaff.forEach { staff ->
                                Button(
                                    onClick = {
                                        sellerSwitchSelectedStaff = staff
                                        sellerSwitchPin = ""
                                        sellerSwitchPinError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppShellButtonColor,
                                        contentColor = AppShellTextPrimary,
                                    ),
                                ) {
                                    Text(staff.displayName)
                                }
                            }
                        }
                    } else {
                        val selectedStaff = sellerSwitchSelectedStaff!!
                        Text(
                            text = selectedStaff.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppShellTextSecondary,
                        )
                        Text(
                            text = "●".repeat(sellerSwitchPin.length).padEnd(4, '○'),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppShellTextPrimary,
                        )
                        sellerSwitchPinError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        NumericPinPad(
                            onDigit = { digit ->
                                if (sellerSwitchPin.length < 4) {
                                    val updated = sellerSwitchPin + digit
                                    sellerSwitchPin = updated
                                    if (updated.length == 4) {
                                        val isSamePerson = selectedStaff.id == currentStaffId
                                        scope.launch {
                                            when (val result = appContainer.authRepository.signInWithPin(selectedStaff.id, updated)) {
                                                is PosResult.Success -> {
                                                    sellerSwitchDialogVisible = false
                                                    sellerSwitchPin = ""
                                                    sellerSwitchPinError = null
                                                    sellerSwitchSelectedStaff = null
                                                    if (isSamePerson) {
                                                        Toast.makeText(
                                                            sellerSwitchContext,
                                                            "Already signed in as ${selectedStaff.displayName}",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    } else {
                                                        // signInWithPin already replaced the active session
                                                        // without going through null, so auto-clock-in
                                                        // (null→non-null guard) did not fire. Call it explicitly.
                                                        appContainer.worktimeAttendanceRepository.clockIn(
                                                            selectedStaff.id,
                                                            selectedStaff.displayName,
                                                        )
                                                    }
                                                }
                                                is PosResult.Failure -> {
                                                    sellerSwitchPin = ""
                                                    sellerSwitchPinError = result.message
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onBackspace = {
                                if (sellerSwitchPin.isNotEmpty()) sellerSwitchPin = sellerSwitchPin.dropLast(1)
                            },
                        )
                        Text(
                            text = "← Change staff",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppShellTextMuted,
                            modifier = Modifier.clickable {
                                sellerSwitchSelectedStaff = null
                                sellerSwitchPin = ""
                                sellerSwitchPinError = null
                            },
                        )
                    }
                }
            }
        }
    }

    if (signOutDialogVisible) {
        Dialog(onDismissRequest = { signOutDialogVisible = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppShellPanelColor,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Confirm identity to sign out",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppShellTextPrimary,
                    )
                    Text(
                        text = currentStaffName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppShellTextSecondary,
                    )
                    Text(
                        text = "●".repeat(signOutPin.length).padEnd(4, '○'),
                        style = MaterialTheme.typography.titleLarge,
                        color = AppShellTextPrimary,
                    )
                    signOutPinError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    NumericPinPad(
                        onDigit = { digit ->
                            if (signOutPin.length < 4) {
                                val updated = signOutPin + digit
                                signOutPin = updated
                                if (updated.length == 4) {
                                    scope.launch {
                                        when (val authResult = appContainer.authRepository.signInWithPin(currentStaffId, updated)) {
                                            is PosResult.Success -> {
                                                signOutDialogVisible = false
                                                signOutPin = ""
                                                signOutPinError = null
                                                appContainer.worktimeAttendanceRepository.clockOut(currentStaffId, currentStaffName)
                                                appContainer.authRepository.signOut()
                                            }
                                            is PosResult.Failure -> {
                                                signOutPin = ""
                                                signOutPinError = authResult.message
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onBackspace = {
                            if (signOutPin.isNotEmpty()) signOutPin = signOutPin.dropLast(1)
                        },
                    )
                }
            }
        }
    }
}


private fun WorktimeActiveSession.toAttendanceEntry(
    fallbackStaffName: String,
    fallbackDurationMinutes: Double,
): AttendanceEntry {
    val effectiveDurationMinutes = if (fallbackDurationMinutes > 0.0) {
        fallbackDurationMinutes
    } else {
        startedAtEpochMillis?.let { startedAt ->
            maxOf(0.0, (System.currentTimeMillis() - startedAt).toDouble() / 60_000.0)
        } ?: 0.0
    }
    return AttendanceEntry(
        staffId = staffId,
        staffName = staffName.ifBlank { fallbackStaffName },
        status = status,
        startedAt = startedAt,
        durationMinutes = effectiveDurationMinutes,
    )
}


private fun isRailDestinationSelected(
    currentRoute: String?,
    destinationRoute: String,
): Boolean {
    return when (destinationRoute) {
        Routes.TableMap -> currentRoute == Routes.TableMap || currentRoute == Routes.TableMapPattern
        Routes.Menu -> currentRoute == Routes.Menu || currentRoute == Routes.MenuPattern
        else -> currentRoute == destinationRoute
    }
}


@Composable
private fun AppRail(
    navController: NavHostController,
    currentStaffName: String,
    onSellerSwitchRequested: () -> Unit,
    onSignOut: () -> Unit,
) {
    val strings = rememberCashierStrings()
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
                    label = strings[destination.labelKey],
                    icon = destination.icon,
                    iconContainerColor = destination.iconContainerColor,
                    iconTint = destination.iconTint,
                    selected = isRailDestinationSelected(currentRoute, destination.route),
                    onClick = {
                        if (destination.route == Routes.TableMap) {
                            val previousRoute = navController.previousBackStackEntry?.destination?.route
                            when {
                                isRailDestinationSelected(currentRoute, destination.route) -> Unit
                                previousRoute == Routes.TableMap || previousRoute == Routes.TableMapPattern ->
                                    navController.popBackStack()
                                else ->
                                    navController.navigate(Routes.tableMap()) {
                                        launchSingleTop = true
                                    }
                            }
                        } else {
                            navController.navigate(destination.route)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Active seller indicator — tappable to open seller-switch PIN dialog.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clickable(onClick = onSellerSwitchRequested),
                shape = RoundedCornerShape(12.dp),
                color = AppShellButtonMutedColor,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings[CashierStringKey.RailActive],
                        style = MaterialTheme.typography.labelSmall,
                        color = AppShellTextMuted,
                    )
                    Text(
                        text = currentStaffName,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppShellAccentText,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            RailButton(
                label = strings[CashierStringKey.RailSignOut],
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
