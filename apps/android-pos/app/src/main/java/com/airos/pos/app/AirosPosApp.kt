package com.airos.pos.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.StaffUiLanguage
import com.airos.pos.core.model.StaffUiPreferences
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
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.FloorMapObject
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.ScanEvent
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.ShiftScheduleDay
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.domain.MenuSyncResult
import com.airos.pos.feature.auth.AuthScheduleStatus
import com.airos.pos.feature.auth.AuthScreen
import com.airos.pos.feature.auth.AuthStaffStatus
import com.airos.pos.feature.auth.AuthViewModel
import com.airos.pos.feature.kitchen.KitchenScreen
import com.airos.pos.feature.kitchen.KitchenViewModel
import com.airos.pos.feature.menu.MenuScreen
import com.airos.pos.feature.menu.MenuViewModel
import com.airos.pos.feature.payment.PaymentScreen
import com.airos.pos.feature.payment.PaymentViewModel
import com.airos.pos.feature.payment.RefundScreen
import com.airos.pos.feature.payment.RefundViewModel
import com.airos.pos.device.printer.ShiftSchedulePrinter
import com.airos.pos.feature.scanner.ScannerScreen
import com.airos.pos.feature.scanner.ScannerViewModel
import com.airos.pos.feature.settings.SettingsScreen
import com.airos.pos.feature.settings.SettingsViewModel
import com.airos.pos.feature.shift.JournalNote
import com.airos.pos.feature.shift.JOURNAL_NOTE_SOURCE_MANUAL
import com.airos.pos.feature.shift.JOURNAL_NOTE_SOURCE_SYSTEM
import com.airos.pos.feature.shift.LastSeenAuthEvent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.airos.pos.core.ui.NumericPinPad
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.URL
import java.net.URLEncoder
import androidx.compose.ui.unit.IntOffset

private val AppShellBackground = Color(0xFF0D151E)
private val AppShellRailColor = Color(0xFF131E29)
private val AppShellPanelColor = Color(0xFF131E29)
private val AppShellBorderColor = Color(0x14FFFFFF)
private val AppShellButtonColor = Color(0xFF182633)
private val AppShellButtonMutedColor = Color(0xFF131E29)
private val AppShellButtonActiveColor = Color(0xFF163847)
private val AppShellTextPrimary = Color(0xFFFBFEFF)
private val AppShellTextSecondary = Color(0xFFE1EBF2)
private val AppShellTextMuted = Color(0xFFB0C0CD)
private val AppShellAccentText = Color(0xFF85F5E0)

private val SHELL_CONTENT_GUTTER = 12.dp
private val RAIL_WIDTH = 136.dp
private val RAIL_BUTTON_WIDTH = 104.dp
private val STAFF_MENU_WIDTH = 392.dp
private val STAFF_MENU_MAX_HEIGHT = 420.dp
private val STAFF_MENU_AVATAR_SIZE = 48.dp
private val STAFF_MENU_SCHEDULE_DOT_SIZE = 10.dp

private data class ActiveSaleContext(
    val tableId: String?,
    val tableLabel: String?,
    val saleId: String?,
    val spotType: ServiceSpotType? = null,
    val maxOpenBills: Int? = null,
    val returnToTableView: Boolean = false,
)

private val ShellNowFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val CustomerDisplayLogTag = "SunmiCustomerDisplay"
private const val NfcLogTag = "AIROS_NFC"
private const val SunmiUiResultLogTag = "AIROS_SUNMI_UI_RESULT"
private const val MenuPlaceResultSpotIdKey = "menu_place_result_spot_id"
private const val MenuPlaceResultSpotLabelKey = "menu_place_result_spot_label"
private const val ReservationPlaceResultTableIdKey = "reservation_place_result_table_id"
private const val ReservationPlaceResultTableLabelKey = "reservation_place_result_table_label"
private const val MenuMaxOpenBillsWireUnbounded = -1
private const val CashierLockDebugTag = "AIROS_LOCK_DEBUG"
private const val AUTO_LOCK_TIMEOUT_MILLIS = 600_000L
private const val CAMERAS_FRAME_REFRESH_MILLIS = 2_000L
private const val CAMERAS_GRID_FRAME_REFRESH_MILLIS = 4_500L
private val CAMERAS_PAGE_PADDING = 12.dp
private val CAMERAS_GRID_SPACING = 8.dp
private val CAMERAS_GRID_CARD_MIN_HEIGHT = 170.dp
private const val CAMERAS_GRID_TWO_COLUMNS = 2
private const val CAMERAS_GRID_THREE_COLUMNS = 3
private const val CAMERAS_GRID_BALANCED_CAMERA_COUNT = 4
private const val CAMERAS_DIALOG_WIDTH_FRACTION = 0.90f
private const val CAMERAS_DIALOG_HEIGHT_FRACTION = 0.86f

private fun findShiftReceiptLogoResId(context: android.content.Context): Int {
    val names = listOf(
        "barlast_logo",
        "barlast",
        "bar_last_logo",
        "barlast_receipt_logo",
        "receipt_logo_barlast",
        "barlast_logo_receipt",
        "barlast_receipt",
        "restaurant_logo_barlast",
        "airos_demo_restaurant_logo",
        "demo_restaurant_logo",
        "receipt_logo",
        "restaurant_logo",
        "logo_barlast",
        "barlast_black_logo",
    )
    return names.firstNotNullOfOrNull { name ->
        context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
    } ?: names.firstNotNullOfOrNull { name ->
        context.resources.getIdentifier(name, "mipmap", context.packageName).takeIf { it != 0 }
    } ?: 0
}

private fun decodeShiftReceiptLogoBitmap(
    context: android.content.Context,
    resId: Int,
    maxSidePx: Int = 420,
): Bitmap? {
    if (resId == 0) return null
    return try {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        var sampleSize = 1
        while ((sourceWidth / sampleSize) > maxSidePx * 2 || (sourceHeight / sampleSize) > maxSidePx * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeResource(context.resources, resId, decodeOptions) ?: return null
        val longestSide = maxOf(decoded.width, decoded.height)
        if (longestSide <= maxSidePx) {
            decoded
        } else {
            val scale = maxSidePx.toFloat() / longestSide.toFloat()
            val targetWidth = maxOf(1, (decoded.width * scale).toInt())
            val targetHeight = maxOf(1, (decoded.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        }
    } catch (t: Throwable) {
        Log.w("AIROS_SHIFT_PRINT", "receipt logo decode failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }
}

private object Routes {
    const val Auth = "auth"
    const val Shift = "shift"
    const val TableMap = "tablemap"
    const val TableMapPattern = "tablemap?menuPlacePicker={menuPlacePicker}&reservationPlacePicker={reservationPlacePicker}"
    const val Cameras = "cameras"
    const val Menu = "menu"
    const val Transactions = "transactions"
    const val Reservations = "reservations"
    const val MenuPattern = "menu?tableId={tableId}&tableLabel={tableLabel}&saleId={saleId}&forceNewSale={forceNewSale}&spotType={spotType}&maxOpenBillsWire={maxOpenBillsWire}&returnToTableView={returnToTableView}"
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
        returnToTableView: Boolean = false,
    ): String {
        val maxOpenBillsWire = maxOpenBills ?: MenuMaxOpenBillsWireUnbounded
        val queryParts = buildList {
            tableId?.let { add("tableId=${Uri.encode(it)}") }
            tableLabel?.let { add("tableLabel=${Uri.encode(it)}") }
            saleId?.let { add("saleId=${Uri.encode(it)}") }
            if (forceNewSale) add("forceNewSale=true")
            spotType?.let { add("spotType=${Uri.encode(it.name)}") }
            add("maxOpenBillsWire=$maxOpenBillsWire")
            if (returnToTableView) add("returnToTableView=true")
        }
        return if (queryParts.isEmpty()) {
            Menu
        } else {
            "$Menu?${queryParts.joinToString("&")}"
        }
    }

    fun tableMap(menuPlacePicker: Boolean = false, reservationPlacePicker: Boolean = false): String {
        return if (!menuPlacePicker && !reservationPlacePicker) {
            TableMap
        } else {
            "$TableMap?menuPlacePicker=$menuPlacePicker&reservationPlacePicker=$reservationPlacePicker"
        }
    }

    fun payment(ticketId: String): String = "payment/$ticketId"
    fun refund(ticketId: String): String = "refund/$ticketId"
}

private enum class RailIconKind {
    TABLES,
    PRODUCTS,
    SALES,
    RESERVATIONS,
    CAMERAS,
    SETTINGS,
    STAFF,
}

private data class RailDestination(
    val route: String,
    val labelKey: CashierStringKey,
    val iconKind: RailIconKind,
    val iconContainerColor: Color,
    val iconTint: Color,
)


private fun railIconDrawableRes(kind: RailIconKind): Int = when (kind) {
    RailIconKind.TABLES -> R.drawable.airos_rail_icon_tables_v2
    RailIconKind.PRODUCTS -> R.drawable.airos_rail_icon_products_v2
    RailIconKind.SALES -> R.drawable.airos_rail_icon_sales_v2
    RailIconKind.RESERVATIONS -> R.drawable.airos_rail_icon_reservations_v2
    RailIconKind.CAMERAS -> R.drawable.airos_rail_icon_cameras_v2
    RailIconKind.SETTINGS -> R.drawable.airos_rail_icon_settings_v2
    RailIconKind.STAFF -> R.drawable.airos_rail_icon_staff_v2
}

private val mainRailDestinations = listOf(
    RailDestination(
        route = Routes.TableMap,
        labelKey = CashierStringKey.RailTables,
        iconKind = RailIconKind.TABLES,
        iconContainerColor = Color(0xFF143A45),
        iconTint = Color(0xFF8DF2E0),
    ),
    RailDestination(
        route = Routes.Menu,
        labelKey = CashierStringKey.RailMenu,
        iconKind = RailIconKind.PRODUCTS,
        iconContainerColor = Color(0xFF1D3143),
        iconTint = Color(0xFFB8D8F5),
    ),
    RailDestination(
        route = Routes.Transactions,
        labelKey = CashierStringKey.RailTransactions,
        iconKind = RailIconKind.SALES,
        iconContainerColor = Color(0xFF2F2748),
        iconTint = Color(0xFFE2CCFF),
    ),
    RailDestination(
        route = Routes.Reservations,
        labelKey = CashierStringKey.RailReservations,
        iconKind = RailIconKind.RESERVATIONS,
        iconContainerColor = Color(0xFF243A2F),
        iconTint = Color(0xFFB7F3C8),
    ),
    RailDestination(
        route = Routes.Cameras,
        labelKey = CashierStringKey.RailCameras,
        iconKind = RailIconKind.CAMERAS,
        iconContainerColor = Color(0xFF26384B),
        iconTint = Color(0xFFC7E7FF),
    ),
    RailDestination(
        route = Routes.Settings,
        labelKey = CashierStringKey.RailSettings,
        iconKind = RailIconKind.SETTINGS,
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
    val shellNow = rememberCurrentMinute()
    var cashierAutoLockAwaitingAuth by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(session?.sessionId, cashierAutoLockAwaitingAuth) {
        val activeSession = session
        if (cashierAutoLockAwaitingAuth && activeSession != null) {
            Log.w(
                CashierLockDebugTag,
                "staff authenticated after auto-lock staffId=${activeSession.staffId} " +
                    "displayName=${activeSession.displayName} authMethod=${activeSession.authMethodSnapshot}",
            )
            cashierAutoLockAwaitingAuth = false
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
        var authScheduleSnapshot by remember { mutableStateOf<ShiftScheduleSnapshot?>(null) }
        val authScheduleAnchorDate = remember(shellNow) { shellNow.toLocalDate() }
        val authScheduleStartDate = remember(authScheduleAnchorDate) { authScheduleAnchorDate.minusDays(1) }
        val authScheduleEndDate = remember(authScheduleAnchorDate) { authScheduleAnchorDate.plusDays(1) }

        LaunchedEffect(appContainer.shiftScheduleRepository, authScheduleStartDate, authScheduleEndDate) {
            authScheduleSnapshot = when (
                val result = appContainer.shiftScheduleRepository.fetchPosSchedule(
                    authScheduleStartDate,
                    authScheduleEndDate,
                )
            ) {
                is PosResult.Success -> result.value
                is PosResult.Failure -> {
                    Log.d("AIROS", "[AirosPosApp] auth screen schedule truth unavailable: ${result.message}")
                    null
                }
            }
        }

        val authStaffStatusById = remember(authScheduleSnapshot, shellNow) {
            scheduleStatusByStaffIdForAuthScreen(
                schedule = authScheduleSnapshot,
                now = shellNow,
            )
        }

        // Signed-out auth screen NFC handling.
        // When direct-login is OFF: keep preselect + notice behavior.
        // When direct-login is ON: known tags sign in directly via AuthRepository.
        // drop(1) skips a stale StateFlow value from an earlier screen/session.
        LaunchedEffect(authViewModel, nfcDirectLoginEnabled, cashierAutoLockAwaitingAuth) {
            Log.d(NfcLogTag, "Auth screen NFC handler active | directLoginEnabled=$nfcDirectLoginEnabled")
            NfcProbe.status.drop(1).collect { status ->
                when (val r = status.lastStaffResolution) {
                    is NfcStaffResolution.Matched -> {
                        Log.i(
                            NfcLogTag,
                            "Matched NFC tag on auth screen | uid=${r.match.uid} staffId=${r.match.staffId} directLoginEnabled=$nfcDirectLoginEnabled",
                        )
                        val signInDirectly = nfcDirectLoginEnabled || cashierAutoLockAwaitingAuth
                        if (signInDirectly) {
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

        Box(modifier = Modifier.fillMaxSize()) {
            AuthScreen(
                state = authState,
                staffStatusById = authStaffStatusById,
                onStaffSelected = authViewModel::selectStaff,
                onDigit = authViewModel::appendPin,
                onBackspace = authViewModel::removePinDigit,
                onClearPin = authViewModel::clearPin,
                onShowManagerOverride = authViewModel::showManagerOverrideDialog,
                onManagerSelected = authViewModel::selectManager,
                onManagerDigit = authViewModel::appendManagerPin,
                onManagerBackspace = authViewModel::removeManagerPinDigit,
                onClearManagerPin = authViewModel::clearManagerPin,
                onConfirmManagerOverride = authViewModel::confirmManagerOverride,
                onDismissManagerOverride = authViewModel::dismissManagerOverrideDialog,
                staffPhotoPainter = { staff -> staffPhotoPainterFor(staff) },
            )
            ShellNowStamp(
                now = shellNow,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp),
            )
        }
        return
    }

    val syncState by appContainer.menuRepository.syncState.collectAsState()

    // Collect current-user attendance state at app-shell level so the offline/syncing
    // notice is visible on every screen, not only when the Shift tab is open.
    // This is the same flow the Shift composable collects; hoisting it here ensures
    // a sign-in refresh can surface existing attendance sync state before the user
    // navigates to Shift. Authentication itself never starts or ends worktime.
    val currentSessionId = session!!.sessionId
    val currentStaffId = session!!.staffId
    var cashierLocked by remember(currentStaffId) { mutableStateOf(false) }
    var lastCashierActivityAtMillis by remember(currentStaffId) {
        mutableStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(currentStaffId, currentSessionId) {
        Log.w(
            CashierLockDebugTag,
            "auto-lock timer started staffId=$currentStaffId timeoutMillis=$AUTO_LOCK_TIMEOUT_MILLIS",
        )
        while (true) {
            delay(1_000L)
            val elapsedIdleMillis = System.currentTimeMillis() - lastCashierActivityAtMillis
            if (!cashierLocked && elapsedIdleMillis >= AUTO_LOCK_TIMEOUT_MILLIS) {
                cashierLocked = true
                Log.w(
                    CashierLockDebugTag,
                    "auto-lock timeout triggered elapsedIdleMillis=$elapsedIdleMillis staffId=$currentStaffId",
                )
            }
        }
    }

    LaunchedEffect(cashierLocked) {
        if (cashierLocked) {
            cashierAutoLockAwaitingAuth = true
            Log.w(
                CashierLockDebugTag,
                "auto-lock routed to existing seller authentication previousStaffId=$currentStaffId",
            )
            appContainer.authRepository.signOut()
        }
    }

    val attendanceGlobalStateFlow = remember(appContainer.worktimeAttendanceRepository, currentStaffId, currentSessionId) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentStaffId, cashierLocked) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (!cashierLocked) {
                            lastCashierActivityAtMillis = System.currentTimeMillis()
                        }
                    }
                }
            },
    ) {
        SignedInApp(
            appContainer = appContainer,
            currentSessionId = currentSessionId,
            currentStaffId = currentStaffId,
            currentStaffName = session!!.displayName,
            now = shellNow,
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

private fun buildReservationTickerMessages(
    now: LocalDateTime,
    appSessionStartedAt: Instant,
    reservations: List<BackendReservation>,
    tableLabelsByBackendId: Map<Int, String>,
): List<String> {
    val weighted = mutableListOf<String>()
    val today = now.toLocalDate()
    val todaysReservations = reservations
        .mapNotNull { reservation ->
            val start = parseShellReservationDateTime(reservation.startTime) ?: return@mapNotNull null
            if (start.toLocalDate() != today) return@mapNotNull null
            reservation to start
        }
        .sortedBy { it.second }

    if (todaysReservations.isEmpty()) {
        return weighted
    }

    val warmup = Duration.between(appSessionStartedAt, Instant.now()).toMinutes() in 0 until 10

    val reservationEntries = todaysReservations
        .filter { (_, start) -> start >= now.minusHours(3) }
        .take(5)
        .map { (reservation, start) ->
            val tableLabel = tableLabelsByBackendId[reservation.tableId] ?: "T${reservation.tableId}"
            val text = "Varaus • $tableLabel ${start.format(ShellNowFormatter)} • ${reservation.persons} hlö"
            val minutesUntil = Duration.between(now, start).toMinutes()
            val overdue = minutesUntil < 0
            val frequency = when {
                overdue -> 2
                minutesUntil <= 15 -> 2
                minutesUntil <= 60 -> 2
                warmup -> 2
                else -> 1
            }
            text to frequency
        }

    reservationEntries.forEach { (message, frequency) ->
        repeat(frequency) { weighted += message }
    }

    return weighted
}

private fun parseShellReservationDateTime(raw: String): LocalDateTime? {
    val value = raw.trim()
    if (value.isBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
}

private fun formatFinnishNowStamp(now: LocalDateTime): String {
    val day = when (now.dayOfWeek.value) {
        1 -> "Ma"
        2 -> "Ti"
        3 -> "Ke"
        4 -> "To"
        5 -> "Pe"
        6 -> "La"
        else -> "Su"
    }
    return "$day ${now.dayOfMonth}.${now.monthValue}. ${now.format(ShellNowFormatter)}"
}

private fun scheduleStatusByStaffIdForAuthScreen(
    schedule: ShiftScheduleSnapshot?,
    now: LocalDateTime,
): Map<String, AuthStaffStatus> {
    val scheduleDay = schedule?.authScheduleDayFor(now) ?: return emptyMap()
    return scheduleDay.plannedShifts
        .asSequence()
        .filter { shift -> shift.staffId.isNotBlank() }
        .filterNot { shift -> shift.isIgnoredForAuthScheduleStatus() }
        .groupBy { it.staffId }
        .mapValuesNotNull { (_, shifts) ->
            when {
                shifts.any { shift -> shift.isActiveAt(now) } -> AuthStaffStatus(AuthScheduleStatus.ACTIVE)
                shifts.any { shift -> shift.startsAt.isAfter(now) } -> AuthStaffStatus(AuthScheduleStatus.UPCOMING)
                shifts.any { shift -> !shift.endsAt.isAfter(now) } -> AuthStaffStatus(AuthScheduleStatus.ENDED)
                else -> null
            }
        }
}

private fun ShiftScheduleSnapshot.authScheduleDayFor(now: LocalDateTime): ShiftScheduleDay? {
    val publishedDays = days.filter { it.hasPublishedAuthScheduleTruth() }
    return publishedDays.firstOrNull { day ->
        val operationalDay = day.operationalDay
        operationalDay.truthAvailable &&
            !operationalDay.isClosed &&
            operationalDay.opensAt != null &&
            operationalDay.closesAt != null &&
            !now.isBefore(operationalDay.opensAt) &&
            now.isBefore(operationalDay.closesAt)
    } ?: publishedDays.firstOrNull { day -> day.date == now.toLocalDate() }
}

private fun ShiftScheduleDay.hasPublishedAuthScheduleTruth(): Boolean {
    return publicationStatus == ShiftSchedulePublicationStatus.PUBLISHED ||
        publicationStatus == ShiftSchedulePublicationStatus.CLOSED
}

private fun PlannedStaffShift.isActiveAt(now: LocalDateTime): Boolean {
    return !now.isBefore(startsAt) && now.isBefore(endsAt)
}

private fun PlannedStaffShift.isIgnoredForAuthScheduleStatus(): Boolean {
    val normalized = status?.trim()?.lowercase().orEmpty()
    return normalized in setOf("absent", "away", "off", "poissa", "no_show", "cancelled", "canceled")
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
    return mapNotNull { entry ->
        transform(entry)?.let { value -> entry.key to value }
    }.toMap()
}

@Composable
private fun staffPhotoPainterFor(staff: StaffMember): Painter? {
    return staffPhotoPainterForStaffId(staff.id)
        ?: staffPhotoPainterForDisplayName(staff.displayName)
}

@Composable
private fun staffPhotoPainterForStaffId(staffId: String): Painter? {
    return when (staffId) {
        "demo-miikka-martsalo" -> painterResource(id = R.drawable.staff_miikka_martsalo)
        else -> null
    }
}

@Composable
private fun staffPhotoPainterForDisplayName(displayName: String): Painter? {
    return when (displayName.trim().lowercase()) {
        "aino korhonen" -> painterResource(id = R.drawable.airos_staff_avatar_aino_korhonen_v1)
        "lauri niemi" -> painterResource(id = R.drawable.airos_staff_avatar_lauri_niemi_v1)
        "salla virtanen" -> painterResource(id = R.drawable.airos_staff_avatar_salla_virtanen_v1)
        "oona lehtinen" -> painterResource(id = R.drawable.airos_staff_avatar_oona_lehtinen_v1)
        "miikka martsalo" -> painterResource(id = R.drawable.staff_miikka_martsalo)
        else -> null
    }
}

@Composable
private fun rememberCurrentMinute(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val millisIntoMinute = System.currentTimeMillis() % 60_000L
            delay((60_000L - millisIntoMinute).coerceAtLeast(1_000L))
        }
    }
    return now
}

@Composable
private fun ShellNowStamp(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = AppShellPanelColor.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Text(
            text = formatFinnishNowStamp(now),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = AppShellTextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShellActiveSellerStamp(
    label: String,
    currentStaffName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = AppShellPanelColor.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = AppShellTextMuted,
                maxLines = 1,
            )
            Text(
                text = currentStaffName,
                style = MaterialTheme.typography.titleSmall,
                color = AppShellAccentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SignedInApp(
    appContainer: AppContainer,
    currentSessionId: String,
    currentStaffId: String,
    currentStaffName: String,
    now: LocalDateTime,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val staffUiPreferencesFlow = remember(appContainer.staffUiPreferencesRepository, currentStaffId) {
        appContainer.staffUiPreferencesRepository.observeStaffUiPreferences(currentStaffId)
    }
    val staffUiPreferences by staffUiPreferencesFlow.collectAsState(initial = StaffUiPreferences())
    val staffUiLanguage = staffUiPreferences.uiLanguage
    val strings = rememberCashierStrings(staffUiLanguage.toCashierLanguage())
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
    var sellerSwitchAuthStaff by remember { mutableStateOf<StaffMember?>(null) }
    var sellerSwitchPin by remember { mutableStateOf("") }
    var sellerSwitchPinError by remember { mutableStateOf<String?>(null) }
    val quickSelectStaffFlow = remember(appContainer.authRepository) {
        appContainer.authRepository.observeQuickSelectStaff()
    }
    val quickSelectStaff by quickSelectStaffFlow.collectAsState(initial = emptyList())
    val staffMenuStaff = remember(quickSelectStaff) {
        quickSelectStaff.sortedWith(
            compareBy<StaffMember> { staffSurnameSortKey(it.displayName) }
                .thenBy { it.displayName.lowercase() },
        )
    }

    val context = LocalContext.current
    val shiftJournalPrefs = remember { context.getSharedPreferences("shift_journal_notes", 0) }
    var journalNotes by remember { mutableStateOf(loadJournalNotesFromPrefs(shiftJournalPrefs)) }
    // Factual authentication-event tracking. Local UI/session state only — this is NOT
    // production audit truth; durable/backend storage of authentication events is future
    // work. Persisted in SharedPreferences so a cold start during the same operational
    // day still surfaces an earlier sign-in as "Viimeksi nähty". Deduped by sessionId so
    // recomposition / state refresh cannot fabricate a new entry while the same sign-in
    // is still current.
    val lastSeenPrefs = remember { context.getSharedPreferences("shift_last_seen_auth", 0) }
    var lastSeenEvents by remember {
        mutableStateOf(loadLastSeenEventsFromPrefs(lastSeenPrefs))
    }
    val staffPanelAttendanceFlow = remember(appContainer.worktimeAttendanceRepository, currentStaffId, currentSessionId) {
        appContainer.worktimeAttendanceRepository.observeCurrentUserState(currentStaffId)
    }
    val staffPanelAttendanceState by staffPanelAttendanceFlow.collectAsState(
        initial = WorktimeEffectiveAttendanceState(),
    )
    val staffPanelAttendanceSnapshotFlow = remember(appContainer.worktimeAttendanceRepository) {
        appContainer.worktimeAttendanceRepository.observeAttendance()
    }
    val staffPanelAttendanceSnapshot by staffPanelAttendanceSnapshotFlow.collectAsState(
        initial = WorktimeAttendanceSnapshot(),
    )
    var staffPanelAttendanceBusy by remember { mutableStateOf(false) }
    val staffPanelClockedInStaff = remember(
        staffPanelAttendanceSnapshot,
        staffPanelAttendanceState.activeSession,
        currentStaffId,
        currentStaffName,
    ) {
        val localCurrentEntry = staffPanelAttendanceState.activeSession?.toAttendanceEntry(
            fallbackStaffName = currentStaffName,
            fallbackDurationMinutes = 0.0,
        )
        val entries = if (localCurrentEntry != null &&
            staffPanelAttendanceSnapshot.currentlyOnSite.none { it.staffId == currentStaffId }
        ) {
            listOf(localCurrentEntry) + staffPanelAttendanceSnapshot.currentlyOnSite
        } else {
            staffPanelAttendanceSnapshot.currentlyOnSite
        }
        entries.distinctBy { it.staffId }
    }
    val staffMenuPresentStaffIds = remember(staffPanelClockedInStaff) {
        staffPanelClockedInStaff
            .mapNotNull { entry -> entry.staffId.takeIf { it.isNotBlank() } }
            .toSet()
    }

    fun addShiftJournalNote(
        text: String,
        authorName: String = currentStaffName.ifBlank { "Tuntematon" },
        source: String = JOURNAL_NOTE_SOURCE_MANUAL,
        editable: Boolean = source == JOURNAL_NOTE_SOURCE_MANUAL,
    ): Boolean {
        if (text.isBlank()) return false
        val note = JournalNote(
            text = text,
            authorName = authorName,
            timestampMillis = System.currentTimeMillis(),
            source = source,
            editable = editable,
        )
        val persistedNotes = loadJournalNotesFromPrefs(shiftJournalPrefs)
        val baseNotes = if (persistedNotes.size > journalNotes.size) persistedNotes else journalNotes
        val updated = baseNotes + note
        if (saveJournalNotesToPrefs(shiftJournalPrefs, updated)) {
            journalNotes = updated
            return true
        } else {
            Toast.makeText(context, "Vuoropäiväkirjamerkintää ei voitu tallentaa.", Toast.LENGTH_LONG).show()
            return false
        }
    }

    fun updateShiftJournalNote(note: JournalNote, updatedText: String): Boolean {
        val trimmed = updatedText.trim()
        if (trimmed.isBlank()) return false
        val persistedNotes = loadJournalNotesFromPrefs(shiftJournalPrefs)
        val baseNotes = if (persistedNotes.size >= journalNotes.size) persistedNotes else journalNotes
        val updated = baseNotes.map { existing ->
            if (sameJournalNoteIdentity(existing, note)) {
                existing.copy(text = trimmed)
            } else {
                existing
            }
        }
        if (updated == baseNotes) {
            Toast.makeText(context, "Vuoropäiväkirjamerkintää ei löytynyt.", Toast.LENGTH_LONG).show()
            return false
        }
        if (saveJournalNotesToPrefs(shiftJournalPrefs, updated)) {
            journalNotes = updated
            return true
        }
        Toast.makeText(context, "Vuoropäiväkirjamerkintää ei voitu tallentaa.", Toast.LENGTH_LONG).show()
        return false
    }

    fun deleteShiftJournalNote(note: JournalNote): Boolean {
        val persistedNotes = loadJournalNotesFromPrefs(shiftJournalPrefs)
        val baseNotes = if (persistedNotes.size >= journalNotes.size) persistedNotes else journalNotes
        val updated = baseNotes.filterNot { existing -> sameJournalNoteIdentity(existing, note) }
        if (updated.size == baseNotes.size) {
            Toast.makeText(context, "Vuoropäiväkirjamerkintää ei löytynyt.", Toast.LENGTH_LONG).show()
            return false
        }
        if (saveJournalNotesToPrefs(shiftJournalPrefs, updated)) {
            journalNotes = updated
            return true
        }
        Toast.makeText(context, "Vuoropäiväkirjamerkintää ei voitu poistaa.", Toast.LENGTH_LONG).show()
        return false
    }

    fun startCurrentWorktime() {
        if (staffPanelAttendanceBusy) return
        scope.launch {
            staffPanelAttendanceBusy = true
            try {
                when (val result = appContainer.worktimeAttendanceRepository.clockIn(currentStaffId, currentStaffName)) {
                    is PosResult.Success -> {
                        addShiftJournalNote(
                            "$currentStaffName työvuorossa",
                            source = JOURNAL_NOTE_SOURCE_SYSTEM,
                            editable = false,
                        )
                        Toast.makeText(context, "Työaika aloitettu", Toast.LENGTH_SHORT).show()
                        appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(currentStaffId, currentStaffName)
                    }
                    is PosResult.Failure -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                staffPanelAttendanceBusy = false
            }
        }
    }

    fun endWorktimeForStaff(staffId: String, staffName: String, signOutAfter: Boolean = false) {
        if (staffPanelAttendanceBusy) return
        scope.launch {
            staffPanelAttendanceBusy = true
            try {
                when (val result = appContainer.worktimeAttendanceRepository.clockOut(staffId, staffName)) {
                    is PosResult.Success -> {
                        addShiftJournalNote(
                            "$staffName lopetti työvuoron",
                            authorName = staffName,
                            source = JOURNAL_NOTE_SOURCE_SYSTEM,
                            editable = false,
                        )
                        Toast.makeText(context, "Työaika päätetty", Toast.LENGTH_SHORT).show()
                        appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(staffId, staffName)
                    }
                    is PosResult.Failure -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
                if (signOutAfter && staffId == currentStaffId) {
                    appContainer.authRepository.signOut()
                }
            } finally {
                staffPanelAttendanceBusy = false
            }
        }
    }

    fun requestSignOut() {
        signOutPin = ""
        signOutPinError = null
        sellerSwitchDialogVisible = false
        sellerSwitchAuthStaff = null
        sellerSwitchPin = ""
        sellerSwitchPinError = null
        signOutDialogVisible = true
    }

    fun handleStaffMenuStaffTap(staff: StaffMember) {
        if (staff.id == currentStaffId) {
            sellerSwitchDialogVisible = false
            navController.navigate(Routes.Shift) {
                launchSingleTop = true
            }
            return
        }
        sellerSwitchDialogVisible = false
        sellerSwitchAuthStaff = staff
        sellerSwitchPin = ""
        sellerSwitchPinError = null
    }

    fun handlePulseStaffTap(staffId: String, staffName: String) {
        val staff = staffId.takeIf { it.isNotBlank() }
            ?.let { id -> staffMenuStaff.firstOrNull { it.id == id } }
            ?: staffName.takeIf { staffId.isBlank() && it.isNotBlank() }
                ?.let { name ->
                    staffMenuStaff
                        .filter { it.displayName.equals(name, ignoreCase = true) }
                        .takeIf { it.size == 1 }
                        ?.single()
                }
        if (staff == null) {
            val label = staffName.takeIf { it.isNotBlank() } ?: staffId.ifBlank { "Tuntematon" }
            Toast.makeText(context, "Henkilöä ei löydy henkilöstölistasta: $label", Toast.LENGTH_LONG).show()
            return
        }
        handleStaffMenuStaffTap(staff)
    }

    fun submitSellerSwitchPin(staff: StaffMember, pin: String) {
        if (staffPanelAttendanceBusy) return
        scope.launch {
            staffPanelAttendanceBusy = true
            try {
                when (val signInResult = appContainer.authRepository.signInWithPin(staff.id, pin)) {
                    is PosResult.Success -> {
                        appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(staff.id, staff.displayName)
                        sellerSwitchAuthStaff = null
                        sellerSwitchPin = ""
                        sellerSwitchPinError = null
                        Toast.makeText(
                            context,
                            "${strings[CashierStringKey.StaffMenuSellerToastPrefix]}: ${staff.displayName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is PosResult.Failure -> {
                        sellerSwitchPin = ""
                        sellerSwitchPinError = signInResult.message
                    }
                }
            } finally {
                staffPanelAttendanceBusy = false
            }
        }
    }

    fun handleNfcSellerSwitch(staffId: String, staffName: String) {
        if (staffPanelAttendanceBusy) return
        scope.launch {
            staffPanelAttendanceBusy = true
            try {
                when (val signInResult = appContainer.authRepository.signInWithNfc(staffId)) {
                    is PosResult.Success -> {
                        appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(staffId, staffName)
                        sellerSwitchDialogVisible = false
                        Toast.makeText(
                            context,
                            "${strings[CashierStringKey.StaffMenuSellerToastPrefix]}: $staffName",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is PosResult.Failure -> {
                        Toast.makeText(context, signInResult.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                staffPanelAttendanceBusy = false
            }
        }
    }

    // Capture each successful authentication / recognition as a factual event:
    //   1. Update the persistent last-seen map (one entry per staffId, latest wins).
    //   2. Append one Vuoropäiväkirja note "<Name> tunnistautui kassalla".
    // Dedup is by AuthSession.sessionId — the AuthRepository mints a fresh sessionId on
    // every signIn call, so PIN, NFC, and seller-switch all produce a new id. Recomposition
    // and StateFlow replay to new subscribers reuse the existing sessionId and therefore
    // do not log a duplicate. The lastLoggedSessionId is persisted so a cold start with a
    // restored session does not synthesise a fake re-authentication. This event is NOT
    // worktime, NOT presence, NOT headcount — see PulseTimelineLastSeenRow for the visual
    // contract.
    LaunchedEffect(Unit) {
        appContainer.authRepository.activeSession.collect { session ->
            if (session == null) return@collect
            val previouslyLogged = lastSeenPrefs.getString("last_logged_session_id", null)
            if (previouslyLogged == session.sessionId) return@collect
            val event = LastSeenAuthEvent(
                staffId = session.staffId,
                staffName = session.displayName,
                timestampMillis = session.authenticatedAtEpochMillis,
            )
            val updated = lastSeenEvents
                .filterNot { it.staffId == event.staffId } + event
            lastSeenEvents = updated
            saveLastSeenEventsToPrefs(lastSeenPrefs, updated)
            lastSeenPrefs.edit().putString("last_logged_session_id", session.sessionId).apply()
            addShiftJournalNote(
                text = "${session.displayName} tunnistautui kassalla",
                authorName = session.displayName,
                source = JOURNAL_NOTE_SOURCE_SYSTEM,
                editable = false,
            )
        }
    }

    // Authentication changes active seller only. Refresh existing attendance truth for
    // the active staff member, but never create or end worktime from auth.
    LaunchedEffect(currentStaffId, currentSessionId) {
        if (currentStaffId.isBlank()) return@LaunchedEffect
        appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(currentStaffId, currentStaffName)
    }

    // NFC fast-path: current staff's badge confirms sign-out identity without PIN entry.
    // Active-seller logout only — worktime attendance is unaffected and ends only via the
    // per-staff "Lopeta" row under the active worktime list.
    LaunchedEffect(signOutDialogVisible, currentStaffId) {
        if (!signOutDialogVisible) return@LaunchedEffect
        NfcProbe.status.drop(1).collect { status ->
            val r = status.lastStaffResolution
            if (r is NfcStaffResolution.Matched && r.match.staffId == currentStaffId) {
                signOutDialogVisible = false
                signOutPin = ""
                signOutPinError = null
                appContainer.authRepository.signOut()
            }
        }
    }

    // NFC badge while already signed in authenticates the next seller only. Existing
    // worktime is refreshed from attendance truth, but auth never starts worktime.
    val sellerSwitchContext = LocalContext.current
    LaunchedEffect(currentStaffId) {
        NfcProbe.status.drop(1).collect { status ->
            if (signOutDialogVisible) return@collect
            val r = status.lastStaffResolution as? NfcStaffResolution.Matched ?: return@collect
            val match = r.match
            if (match.staffId == currentStaffId) {
                Toast.makeText(
                    sellerSwitchContext,
                    "${strings[CashierStringKey.StaffMenuAlreadyActivePrefix]}: ${match.displayName}",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                handleNfcSellerSwitch(match.staffId, match.displayName)
            }
        }
    }

    val sellerSessionStartedAt = remember(currentStaffId) { Instant.now() }
    var todayReservations by remember { mutableStateOf<List<BackendReservation>>(emptyList()) }
    var tableLabelsByBackendId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    // Quick sale / active context state — tracked at shell level to bridge rail nav and MenuScreen.
    var activeSaleContext by remember { mutableStateOf<ActiveSaleContext?>(null) }
    var quickSaleHasLines by remember { mutableStateOf(false) }
    var showQuickSaleLeaveDialog by remember { mutableStateOf(false) }
    var pendingNavDestination by remember { mutableStateOf<String?>(null) }
    var openPaymentDialogRequest by remember { mutableStateOf(false) }
    var clearQuickSaleRequest by remember { mutableStateOf(false) }

    LaunchedEffect(appContainer.tableRepository) {
        appContainer.tableRepository.observeFloorMap().collect { floorMap ->
            tableLabelsByBackendId = floorMap.tables
                .mapNotNull { table -> table.backendTableId?.let { it to table.label } }
                .toMap()
        }
    }

    LaunchedEffect(appContainer.reservationsRepository, currentStaffId) {
        while (true) {
            when (val result = appContainer.reservationsRepository.listReservations()) {
                is PosResult.Success -> todayReservations = result.value
                is PosResult.Failure -> Log.d(
                    "AIROS",
                    "[AirosPosApp] reservation ticker fetch failed: ${result.message}",
                )
            }
            delay(60_000L)
        }
    }

    val reservationTickerMessages = remember(
        now,
        todayReservations,
        tableLabelsByBackendId,
        sellerSessionStartedAt,
    ) {
        buildReservationTickerMessages(
            now = now,
            appSessionStartedAt = sellerSessionStartedAt,
            reservations = todayReservations,
            tableLabelsByBackendId = tableLabelsByBackendId,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppShellBackground),
    ) {
        AppRail(
            navController = navController,
            strings = strings,
            onSellerSwitchRequested = {
                if (!signOutDialogVisible) {
                    sellerSwitchDialogVisible = true
                }
            },
            isQuickSaleDirty = quickSaleHasLines,
            onNavigationBlocked = { destination ->
                pendingNavDestination = destination
                showQuickSaleLeaveDialog = true
            },
            onNavigateToMenu = {
                val ctx = activeSaleContext
                if (ctx != null && (ctx.tableId != null || quickSaleHasLines)) {
                    navController.navigate(
                        Routes.menu(
                            tableId = ctx.tableId,
                            tableLabel = ctx.tableLabel,
                            saleId = ctx.saleId,
                            spotType = ctx.spotType,
                            maxOpenBills = ctx.maxOpenBills,
                            returnToTableView = ctx.returnToTableView,
                        ),
                    )
                } else {
                    if (ctx != null && ctx.tableId == null && ctx.saleId != null) {
                        val saleId = ctx.saleId
                        scope.launch {
                            runCatching { appContainer.openSaleRepository.clearOpenSale(saleId) }
                        }
                    }
                    activeSaleContext = null
                    navController.navigate(Routes.Menu)
                }
            },
            onWillNavigateAway = {
                val ctx = activeSaleContext
                if (ctx != null && ctx.tableId == null && !quickSaleHasLines) {
                    val saleId = ctx.saleId
                    activeSaleContext = null
                    if (saleId != null) {
                        scope.launch {
                            runCatching { appContainer.openSaleRepository.clearOpenSale(saleId) }
                        }
                    }
                }
            },
        )

        Scaffold(
            containerColor = AppShellBackground,
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppShellBackground)
                    .padding(horizontal = SHELL_CONTENT_GUTTER, vertical = 8.dp),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.TableMap,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(Routes.Shift) {
                    val shiftRouteContext = LocalContext.current
                    val shiftReceiptLogoResId = remember(shiftRouteContext) {
                        findShiftReceiptLogoResId(shiftRouteContext)
                    }
                    val shiftReceiptLogoPainter: Painter? = null
                    val shiftReceiptLogoBitmap = remember(shiftRouteContext, shiftReceiptLogoResId) {
                        shiftReceiptLogoResId.takeIf { it != 0 }?.let { logoResId ->
                            decodeShiftReceiptLogoBitmap(
                                context = shiftRouteContext,
                                resId = logoResId,
                                maxSidePx = 420,
                            )
                        }
                    }
                    val shiftSchedulePrinter = remember(shiftRouteContext) {
                        ShiftSchedulePrinter(shiftRouteContext.applicationContext)
                    }
                    val viewModel: ShiftViewModel = viewModel(
                        factory = ShiftViewModel.factory(
                            shiftRepository = appContainer.shiftRepository,
                            cashLedgerRepository = appContainer.cashLedgerRepository,
                            defaultOpeningFloatCents = terminalSettings.defaultOpeningFloatCents,
                        ),
                    )
                    val state by viewModel.uiState.collectAsState()
                    val attendanceRepository = appContainer.worktimeAttendanceRepository
                    val attendanceFlow = remember(attendanceRepository) { attendanceRepository.observeAttendance() }
                    val attendance by attendanceFlow.collectAsState(initial = WorktimeAttendanceSnapshot())
                    val shiftScheduleRepository = appContainer.shiftScheduleRepository
                    val scheduleToday = remember { LocalDate.now() }
                    val scheduleDateFrom = remember(scheduleToday) { scheduleToday.minusDays(1) }
                    val scheduleDateTo = remember(scheduleToday) { scheduleToday.plusDays(20) }
                    var scheduleSnapshot by remember { mutableStateOf<ShiftScheduleSnapshot?>(null) }
                    var scheduleLoading by remember { mutableStateOf(false) }
                    var scheduleMessage by remember { mutableStateOf<String?>(null) }
                    var ownScheduleSnapshot by remember(currentStaffId) { mutableStateOf<ShiftScheduleSnapshot?>(null) }
                    var ownScheduleLoading by remember(currentStaffId) { mutableStateOf(false) }
                    var ownScheduleMessage by remember(currentStaffId) { mutableStateOf<String?>(null) }
                    val currentUserStateFlow = remember(attendanceRepository, currentStaffId, currentSessionId) {
                        attendanceRepository.observeCurrentUserState(currentStaffId)
                    }
                    val currentAttendance by currentUserStateFlow.collectAsState(initial = WorktimeEffectiveAttendanceState())
                    val attendanceScope = rememberCoroutineScope()
                    var attendanceBusy by remember(currentStaffId, currentSessionId) { mutableStateOf(false) }
                    var attendanceMessage by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(attendanceRepository, currentStaffId, currentStaffName, currentSessionId) {
                        attendanceRepository.syncAndRefreshCurrentUser(currentStaffId, currentStaffName)
                    }

                    LaunchedEffect(shiftScheduleRepository, currentStaffId, scheduleDateFrom, scheduleDateTo) {
                        scheduleLoading = true
                        scheduleMessage = null
                        ownScheduleLoading = currentStaffId.isNotBlank()
                        ownScheduleMessage = null

                        when (val result = shiftScheduleRepository.fetchPosSchedule(scheduleDateFrom, scheduleDateTo)) {
                            is PosResult.Success -> {
                                scheduleSnapshot = result.value
                                scheduleMessage = null
                            }
                            is PosResult.Failure -> {
                                scheduleSnapshot = null
                                scheduleMessage = result.message
                            }
                        }
                        scheduleLoading = false

                        if (currentStaffId.isBlank()) {
                            ownScheduleSnapshot = null
                            ownScheduleMessage = "Omia vuoroja ei voi hakea ilman aktiivista henkilöllisyyttä."
                            ownScheduleLoading = false
                        } else {
                            when (val result = shiftScheduleRepository.fetchOwnShifts(currentStaffId, scheduleDateFrom, scheduleDateTo)) {
                                is PosResult.Success -> {
                                    ownScheduleSnapshot = result.value
                                    ownScheduleMessage = null
                                }
                                is PosResult.Failure -> {
                                    ownScheduleSnapshot = null
                                    ownScheduleMessage = result.message
                                }
                            }
                            ownScheduleLoading = false
                        }
                    }

                    val polledMyEntry = attendance.currentlyOnSite.find { it.staffId == currentStaffId }
                    val myEntry = currentAttendance.activeSession?.toAttendanceEntry(
                        fallbackStaffName = currentStaffName,
                        fallbackDurationMinutes = polledMyEntry?.durationMinutes ?: 0.0,
                    ) ?: polledMyEntry
                    val isClockedIn = currentAttendance.activeSession != null || polledMyEntry != null
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

                    // Build a deterministic, deduplicated snapshot for the UI.
                    // Active seller/authentication is separate from attendance truth: changing
                    // the active POS user must never remove anyone from the on-site/worktime
                    // list. If the current user's local active session has just been confirmed
                    // from backend but the global attendance poll has not caught up yet, we may
                    // add that confirmed session for display. We do not subtract backend rows
                    // based on the current active seller's local state.
                    val effectiveAttendance = run {
                        val rawOnSite = if (
                            currentAttendance.activeSession != null &&
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
                        onCountedCashChanged = viewModel::updateCountedCash,
                        onOpenShift = viewModel::openShift,
                        onRecordCashCount = viewModel::recordCashCount,
                        onCloseShiftWithCount = viewModel::closeShiftWithCount,
                        onCloseShiftUsingLatestTruth = viewModel::closeShiftUsingLatestTruth,
                        attendance = effectiveAttendance,
                        isClockedIn = isClockedIn,
                        myAttendanceEntry = myEntry,
                        schedule = scheduleSnapshot,
                        scheduleLoading = scheduleLoading,
                        scheduleMessage = scheduleMessage,
                        ownSchedule = ownScheduleSnapshot,
                        textInputLanguage = staffUiLanguage,
                        ownScheduleLoading = ownScheduleLoading,
                        ownScheduleMessage = ownScheduleMessage,
                        attendanceStateLoading = false,
                        attendanceBusy = attendanceBusy,
                        attendanceNoticeMessage = attendanceNoticeMessage,
                        attendanceMessage = attendanceSyncBlockedMessage ?: attendanceMessage,
                        onClockIn = ::startCurrentWorktime,
                        onClockOut = {
                            endWorktimeForStaff(currentStaffId, currentStaffName)
                        },
                        onAcknowledgeRequiresReview = { sessionId ->
                            if (!attendanceBusy) {
                                attendanceScope.launch {
                                    attendanceBusy = true
                                    try {
                                        when (val result = appContainer.worktimeAttendanceRepository.acknowledgeRequiresReview(sessionId)) {
                                            is PosResult.Success -> {
                                                Toast.makeText(context, "Tarkistus kirjattu", Toast.LENGTH_SHORT).show()
                                                appContainer.worktimeAttendanceRepository.syncAndRefreshCurrentUser(currentStaffId, currentStaffName)
                                            }
                                            is PosResult.Failure -> {
                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } finally {
                                        attendanceBusy = false
                                    }
                                }
                            }
                        },
                        journalNotes = journalNotes,
                        onNoteAdded = { text -> addShiftJournalNote(text) },
                        onSystemNoteAdded = { text ->
                            addShiftJournalNote(
                                text = text,
                                source = JOURNAL_NOTE_SOURCE_SYSTEM,
                                editable = false,
                            )
                        },
                        onNoteUpdated = ::updateShiftJournalNote,
                        onNoteDeleted = ::deleteShiftJournalNote,
                        lastSeenEvents = lastSeenEvents,
                        onPulseStaffSelected = ::handlePulseStaffTap,
                        receiptLogoPainter = shiftReceiptLogoPainter,
                        receiptLogoBitmap = shiftReceiptLogoBitmap,
                        onPrintShiftReceiptBitmap = shiftSchedulePrinter::printBitmap,
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
                    LaunchedEffect(Unit) {
                        navController.navigate(Routes.Settings) {
                            popUpTo(Routes.Diagnostics) {
                                inclusive = true
                            }
                        }
                    }
                    /*
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
                    */
                }

                composable(
                    route = Routes.TableMapPattern,
                    arguments = listOf(
                        navArgument("menuPlacePicker") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument("reservationPlacePicker") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val context = LocalContext.current
                    val menuPlacePicker = entry.arguments?.getBoolean("menuPlacePicker") ?: false
                    val reservationPlacePicker = entry.arguments?.getBoolean("reservationPlacePicker") ?: false
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
                    val tableMapNow = rememberCurrentMinute()
                    val selectedTableId = state.selectedTableId
                    val selectedTableLabel = state.floorMap?.tables?.firstOrNull { it.id == selectedTableId }?.label
                    fun finishPlaceSelection(tableId: String, tableLabel: String) {
                        if (menuPlacePicker) {
                            navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotIdKey, tableId)
                            navController.previousBackStackEntry?.savedStateHandle?.set(MenuPlaceResultSpotLabelKey, tableLabel)
                            navController.popBackStack()
                        } else if (reservationPlacePicker) {
                            navController.previousBackStackEntry?.savedStateHandle?.set(ReservationPlaceResultTableIdKey, tableId)
                            navController.previousBackStackEntry?.savedStateHandle?.set(ReservationPlaceResultTableLabelKey, tableLabel)
                            navController.popBackStack()
                        }
                    }
                    TableMapScreen(
                        state = state,
                        currentStaffId = currentStaffId,
                        currentStaffDisplayName = currentStaffName,
                        now = tableMapNow,
                        preferRichFloorPlanStyle = useRichFloorPlanStyle,
                        placeSelectionMode = menuPlacePicker || reservationPlacePicker,
                        reservationTickerMessages = reservationTickerMessages,
                        cameraPreviewService = appContainer.cameraPreviewService,
                        onSelectTable = viewModel::selectTable,
                        onSelectPlace = { tableId, tableLabel ->
                            finishPlaceSelection(tableId, tableLabel)
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
                            } else if (menuPlacePicker || reservationPlacePicker) {
                                finishPlaceSelection(tableId, tableLabel)
                            } else {
                                val openBillCount = state.openChecksBySpotId[tableId]?.count ?: 0
                                val blockTableTapMultiBill =
                                    source == TableSaleOpenSource.TABLE_TAP && openBillCount > 1

                                if (!blockTableTapMultiBill) {
                                    val selectedSpot = state.floorMap?.tables?.firstOrNull { it.id == tableId }
                                    activeSaleContext = ActiveSaleContext(
                                        tableId = tableId,
                                        tableLabel = tableLabel,
                                        saleId = saleId,
                                        spotType = selectedSpot?.spotType,
                                        maxOpenBills = selectedSpot?.maxOpenBills,
                                        returnToTableView = true,
                                    )
                                    navController.navigate(
                                        Routes.menu(
                                            tableId = tableId,
                                            tableLabel = tableLabel,
                                            saleId = saleId,
                                            forceNewSale = source == TableSaleOpenSource.NEW_SALE_BUTTON,
                                            spotType = selectedSpot?.spotType,
                                            maxOpenBills = selectedSpot?.maxOpenBills,
                                            returnToTableView = true,
                                        ),
                                    )
                                }
                            }

                        },
                        onReserveTable = { tableId, tableLabel ->
                            navController.navigate(Routes.Reservations)
                            navController.currentBackStackEntry?.savedStateHandle?.set(
                                ReservationPlaceResultTableIdKey,
                                tableId,
                            )
                            navController.currentBackStackEntry?.savedStateHandle?.set(
                                ReservationPlaceResultTableLabelKey,
                                tableLabel,
                            )
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

                composable(Routes.Cameras) {
                    val floorMap by appContainer.tableRepository.observeFloorMap().collectAsState(
                        initial = FloorMap(
                            id = "cameras-loading",
                            name = "",
                            tables = emptyList(),
                        ),
                    )
                    CamerasRoute(
                        floorMap = floorMap,
                        edgeBaseUrl = terminalSettings.edgeBaseUrl,
                    )
                }

                composable(Routes.Transactions) {
                    TransactionsRoute(
                        openSaleRepository = appContainer.openSaleRepository,
                        salesLedgerOutboxDao = appContainer.database.salesLedgerOutboxDao(),
                        salesDayReportRepository = appContainer.salesDayReportRepository,
                    )
                }

                composable(Routes.Reservations) { entry ->
                    val reservationPlaceId by entry.savedStateHandle
                        .getStateFlow<String?>(ReservationPlaceResultTableIdKey, null)
                        .collectAsState()
                    val reservationPlaceLabel by entry.savedStateHandle
                        .getStateFlow<String?>(ReservationPlaceResultTableLabelKey, null)
                        .collectAsState()
                    ReservationsRoute(
                        reservationsRepository = appContainer.reservationsRepository,
                        tableRepository = appContainer.tableRepository,
                        title = strings[CashierStringKey.ReservationsTitle],
                        selectTableLabel = strings[CashierStringKey.ReservationsSelectTable],
                        noTableSelectedLabel = strings[CashierStringKey.ReservationsNoTableSelected],
                        textInputLanguage = staffUiLanguage,
                        pickedTableId = reservationPlaceId,
                        pickedTableLabel = reservationPlaceLabel,
                        onPickedTableConsumed = {
                            entry.savedStateHandle.remove<String>(ReservationPlaceResultTableIdKey)
                            entry.savedStateHandle.remove<String>(ReservationPlaceResultTableLabelKey)
                        },
                        onOpenTablePicker = {
                            navController.navigate(Routes.tableMap(reservationPlacePicker = true))
                        },
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
                        navArgument("maxOpenBillsWire") {
                            type = NavType.IntType
                            defaultValue = MenuMaxOpenBillsWireUnbounded
                        },
                        navArgument("returnToTableView") {
                            type = NavType.BoolType
                            defaultValue = false
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
                    val maxOpenBillsWire = entry.arguments
                        ?.getInt("maxOpenBillsWire")
                        ?: MenuMaxOpenBillsWireUnbounded
                    val initialTableMaxOpenBills = maxOpenBillsWire.takeIf { it >= 0 }
                    val returnToTableView = entry.arguments?.getBoolean("returnToTableView") ?: false
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
                    // Clear quick sale and navigate to pending destination when requested by leave guard.
                    LaunchedEffect(clearQuickSaleRequest) {
                        if (clearQuickSaleRequest) {
                            viewModel.clearAndAbortQuickSale()
                            activeSaleContext = null
                            quickSaleHasLines = false
                            clearQuickSaleRequest = false
                            pendingNavDestination?.let { dest ->
                                navController.navigate(dest)
                                pendingNavDestination = null
                            }
                        }
                    }
                    // Collect open sales count for the no-active-bill panel.
                    val openSalesFlow = remember(appContainer.openSaleRepository) {
                        appContainer.openSaleRepository.observeOpenSales()
                    }
                    val openSales by openSalesFlow.collectAsState(initial = emptyList())
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
                                    activeSaleContext = ActiveSaleContext(
                                        tableId = null,
                                        tableLabel = null,
                                        saleId = sale.saleId,
                                        returnToTableView = returnToTableView,
                                    )
                                    navController.navigate(
                                        Routes.menu(
                                            saleId = sale.saleId,
                                            returnToTableView = returnToTableView,
                                        ),
                                    )
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "Uutta laskua ei voitu avata.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onBackToTableView = if (returnToTableView) {
                            {
                                if (!navController.popBackStack(Routes.TableMapPattern, inclusive = false)) {
                                    navController.popBackStack(Routes.TableMap, inclusive = false)
                                }
                            }
                        } else {
                            null
                        },
                        onOpenServiceSpotSelection = {
                            navController.navigate(Routes.tableMap(menuPlacePicker = true))
                        },
                        onNavigateToTableMap = {
                            if (!navController.popBackStack(Routes.TableMapPattern, inclusive = false)) {
                                if (!navController.popBackStack(Routes.TableMap, inclusive = false)) {
                                    navController.navigate(Routes.tableMap())
                                }
                            }
                        },
                        onQuickSaleLineCountChanged = { count -> quickSaleHasLines = count > 0 },
                        openSalesCount = openSales.size,
                        requestOpenPaymentDialog = openPaymentDialogRequest,
                        onPaymentDialogRequestConsumed = { openPaymentDialogRequest = false },
                        onClearAndAbortQuickSale = viewModel::clearAndAbortQuickSale,
                        onSaleJustClosed = {
                            activeSaleContext = null
                            quickSaleHasLines = false
                            viewModel.acknowledgeSaleClosed()
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
                        currentStaffName = currentStaffName,
                        staffUiLanguage = staffUiLanguage,
                        onTerminalNameChanged = viewModel::updateTerminalNameInput,
                        onEdgeBaseUrlChanged = viewModel::updateEdgeBaseUrlInput,
                        onRestaurantKeyChanged = viewModel::updateRestaurantKeyInput,
                        onDefaultOpeningFloatChanged = viewModel::updateDefaultOpeningFloatInput,
                        onStaffUiLanguageChanged = { language ->
                            scope.launch {
                                appContainer.staffUiPreferencesRepository.setUiLanguage(currentStaffId, language)
                            }
                        },
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
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShellActiveSellerStamp(
                        label = strings[CashierStringKey.RailActive],
                        currentStaffName = currentStaffName,
                    )
                    ShellNowStamp(now = now)
                }
            }
        }
    }

    if (showQuickSaleLeaveDialog) {
        QuickSaleLeaveDialog(
            onPay = {
                showQuickSaleLeaveDialog = false
                pendingNavDestination = null
                openPaymentDialogRequest = true
            },
            onMoveToTable = {
                showQuickSaleLeaveDialog = false
                pendingNavDestination = null
                navController.navigate(Routes.tableMap(menuPlacePicker = true))
            },
            onClearAndLeave = {
                showQuickSaleLeaveDialog = false
                clearQuickSaleRequest = true
            },
            onStayOnBill = {
                showQuickSaleLeaveDialog = false
                pendingNavDestination = null
            },
        )
    }

    val sellerSwitchPopupOffset = with(LocalDensity.current) {
        IntOffset((RAIL_WIDTH + 16.dp).roundToPx(), (-14).dp.roundToPx())
    }

    if (sellerSwitchDialogVisible) {
        Popup(
            alignment = Alignment.BottomStart,
            offset = sellerSwitchPopupOffset,
            onDismissRequest = {
                sellerSwitchDialogVisible = false
            },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                modifier = Modifier.width(STAFF_MENU_WIDTH),
                shape = RoundedCornerShape(16.dp),
                color = AppShellPanelColor,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = strings[CashierStringKey.StaffMenuTitle],
                        style = MaterialTheme.typography.titleMedium,
                        color = AppShellTextPrimary,
                    )
                    Text(
                        text = strings[CashierStringKey.StaffMenuPrompt],
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppShellTextSecondary,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = STAFF_MENU_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        staffMenuStaff.forEach { staff ->
                            StaffMenuRow(
                                staff = staff,
                                present = staff.id in staffMenuPresentStaffIds,
                                enabled = !staffPanelAttendanceBusy,
                                onClick = { handleStaffMenuStaffTap(staff) },
                            )
                        }
                    }
                    Button(
                        onClick = { requestSignOut() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppShellButtonMutedColor,
                            contentColor = AppShellTextSecondary,
                        ),
                    ) {
                        Text(strings[CashierStringKey.StaffMenuSignOut])
                    }
                }
            }
        }
    }

    sellerSwitchAuthStaff?.let { staff ->
        Dialog(
            onDismissRequest = {
                if (!staffPanelAttendanceBusy) {
                    sellerSwitchAuthStaff = null
                    sellerSwitchPin = ""
                    sellerSwitchPinError = null
                }
            },
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppShellPanelColor,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = strings[CashierStringKey.StaffMenuAuthTitle],
                        style = MaterialTheme.typography.titleMedium,
                        color = AppShellTextPrimary,
                    )
                    Text(
                        text = staff.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppShellAccentText,
                    )
                    Text(
                        text = strings[CashierStringKey.StaffMenuAuthPrompt],
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
                            if (!staffPanelAttendanceBusy && sellerSwitchPin.length < 4) {
                                val updated = sellerSwitchPin + digit
                                sellerSwitchPin = updated
                                if (updated.length == 4) {
                                    submitSellerSwitchPin(staff, updated)
                                }
                            }
                        },
                        onBackspace = {
                            if (!staffPanelAttendanceBusy && sellerSwitchPin.isNotEmpty()) {
                                sellerSwitchPin = sellerSwitchPin.dropLast(1)
                            }
                        },
                    )
                    Button(
                        onClick = {
                            sellerSwitchAuthStaff = null
                            sellerSwitchPin = ""
                            sellerSwitchPinError = null
                        },
                        enabled = !staffPanelAttendanceBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppShellButtonMutedColor,
                            contentColor = AppShellTextSecondary,
                        ),
                    ) {
                        Text(strings[CashierStringKey.StaffMenuAuthCancel])
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
                        text = "Vahvista kassasta uloskirjautuminen",
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
                                                // Active-seller logout only; worktime attendance
                                                // is preserved and must be ended via the per-staff
                                                // targeted "Lopeta" row under the active worktime list.
                                                signOutDialogVisible = false
                                                signOutPin = ""
                                                signOutPinError = null
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


@Composable
private fun QuickSaleLeaveDialog(
    onPay: () -> Unit,
    onMoveToTable: () -> Unit,
    onClearAndLeave: () -> Unit,
    onStayOnBill: () -> Unit,
) {
    Dialog(onDismissRequest = onStayOnBill) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppShellPanelColor,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Pikamyyntilasku on auki",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppShellTextPrimary,
                )
                Text(
                    text = "Laskulla on avoimia tuotteita. Valitse toiminto ennen poistumista.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppShellTextSecondary,
                )
                Button(
                    onClick = onPay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Maksa")
                }
                Button(
                    onClick = onMoveToTable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppShellButtonColor,
                        contentColor = AppShellTextPrimary,
                    ),
                ) {
                    Text("Siirrä pöytään")
                }
                Button(
                    onClick = onClearAndLeave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppShellButtonColor,
                        contentColor = AppShellTextPrimary,
                    ),
                ) {
                    Text("Tyhjennä ja sulje")
                }
                Button(
                    onClick = onStayOnBill,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppShellButtonMutedColor,
                        contentColor = AppShellTextSecondary,
                    ),
                ) {
                    Text("Palaa laskulle")
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
        Routes.TableMap -> currentRoute == Routes.TableMap || currentRoute == Routes.TableMapPattern
        Routes.Menu -> currentRoute == Routes.Menu || currentRoute == Routes.MenuPattern
        else -> currentRoute == destinationRoute
    }
}

private enum class CamerasPageRole {
    TABLE,
    QUEUE,
    GENERAL,
    CAMERA,
}

private data class CamerasPageCamera(
    val cameraId: String,
    val label: String,
    val role: CamerasPageRole,
    val tableLabel: String? = null,
)

private data class CamerasPageFrameState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
)

@Composable
private fun CamerasRoute(
    floorMap: FloorMap,
    edgeBaseUrl: String?,
) {
    val cameras = remember(floorMap) { buildCamerasPageCameras(floorMap) }
    var selectedCamera by remember(cameras) { mutableStateOf<CamerasPageCamera?>(null) }
    val gridColumns = when (cameras.size) {
        1 -> GridCells.Fixed(1)
        2 -> GridCells.Fixed(CAMERAS_GRID_TWO_COLUMNS)
        3 -> GridCells.Fixed(CAMERAS_GRID_THREE_COLUMNS)
        CAMERAS_GRID_BALANCED_CAMERA_COUNT -> GridCells.Fixed(CAMERAS_GRID_TWO_COLUMNS)
        else -> GridCells.Fixed(CAMERAS_GRID_THREE_COLUMNS)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(CAMERAS_PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "KAMERAT",
                style = MaterialTheme.typography.headlineSmall,
                color = AppShellTextPrimary,
            )
            Text(
                text = "Kameranäkymät erillään pöytien tilatiedoista.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppShellTextMuted,
            )

            if (cameras.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = AppShellPanelColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
                ) {
                    Text(
                        text = "Kamerat eivät ole saatavilla.",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppShellTextSecondary,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = gridColumns,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = CAMERAS_PAGE_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(CAMERAS_GRID_SPACING),
                    verticalArrangement = Arrangement.spacedBy(CAMERAS_GRID_SPACING),
                ) {
                    items(
                        items = cameras,
                        key = { it.cameraId },
                    ) { camera ->
                        CameraGridCard(
                            camera = camera,
                            edgeBaseUrl = edgeBaseUrl,
                            onClick = { selectedCamera = camera },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = CAMERAS_GRID_CARD_MIN_HEIGHT),
                        )
                    }
                }
            }
        }

        selectedCamera?.let { camera ->
            CamerasPageDialog(
                camera = camera,
                edgeBaseUrl = edgeBaseUrl,
                onDismiss = { selectedCamera = null },
            )
        }
    }
}

@Composable
private fun CameraGridCard(
    camera: CamerasPageCamera,
    edgeBaseUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = AppShellPanelColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppShellTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = camera.cameraId,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppShellTextMuted,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AppShellButtonActiveColor,
                ) {
                    Text(
                        text = camera.role.displayLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppShellAccentText,
                        maxLines = 1,
                    )
                }
            }
            CameraPageStillPreview(
                cameraId = camera.cameraId,
                edgeBaseUrl = edgeBaseUrl,
                label = camera.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                refreshMillis = CAMERAS_GRID_FRAME_REFRESH_MILLIS,
            )
        }
    }
}

@Composable
private fun CamerasPageDialog(
    camera: CamerasPageCamera,
    edgeBaseUrl: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(CAMERAS_DIALOG_WIDTH_FRACTION)
                .fillMaxHeight(CAMERAS_DIALOG_HEIGHT_FRACTION),
            shape = RoundedCornerShape(28.dp),
            color = AppShellPanelColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = camera.label,
                            style = MaterialTheme.typography.titleLarge,
                            color = AppShellTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${camera.cameraId} · ${camera.role.displayLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppShellTextMuted,
                            maxLines = 1,
                        )
                    }
                    Button(onClick = onDismiss) {
                        Text("Sulje")
                    }
                }
                CameraPageStillPreview(
                    cameraId = camera.cameraId,
                    edgeBaseUrl = edgeBaseUrl,
                    label = camera.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CameraPageStillPreview(
    cameraId: String,
    edgeBaseUrl: String?,
    label: String,
    modifier: Modifier = Modifier,
    refreshMillis: Long = CAMERAS_FRAME_REFRESH_MILLIS,
) {
    val normalizedBaseUrl = edgeBaseUrl?.trim().orEmpty()
    var frameState by remember(cameraId, normalizedBaseUrl) {
        mutableStateOf(CamerasPageFrameState(isLoading = true))
    }

    LaunchedEffect(cameraId, normalizedBaseUrl) {
        if (cameraId.isBlank()) {
            frameState = CamerasPageFrameState(isLoading = false, message = "Kameratunnus puuttuu.")
            return@LaunchedEffect
        }
        if (normalizedBaseUrl.isBlank()) {
            frameState = CamerasPageFrameState(isLoading = false, message = "Kamerayhteyttä ei ole määritetty.")
            return@LaunchedEffect
        }

        while (true) {
            val requestUrl = camerasPageLatestFrameUrl(
                edgeBaseUrl = normalizedBaseUrl,
                cameraId = cameraId,
                cacheBustEpochMillis = System.currentTimeMillis(),
            )
            frameState = frameState.copy(isLoading = frameState.bitmap == null)
            val result = runCatching { fetchCamerasPageFrameBitmap(requestUrl) }
            frameState = result.fold(
                onSuccess = { bitmap ->
                    CamerasPageFrameState(
                        bitmap = bitmap,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    frameState.copy(
                        isLoading = false,
                        message = error.message ?: "Kamerakuva ei ole saatavilla.",
                    )
                },
            )
            delay(refreshMillis)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppShellButtonMutedColor),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = frameState.bitmap
        if (bitmap != null) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "$label camera preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (bitmap == null || frameState.message != null) {
            val message = when {
                frameState.isLoading -> "Ladataan kamerakuvaa..."
                else -> frameState.message ?: "Kamerakuva ei ole saatavilla."
            }
            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = AppShellTextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun buildCamerasPageCameras(floorMap: FloorMap): List<CamerasPageCamera> {
    val byCameraId = linkedMapOf<String, CamerasPageCamera>()

    fun addCamera(camera: CamerasPageCamera) {
        val existing = byCameraId[camera.cameraId]
        byCameraId[camera.cameraId] = when {
            existing == null -> camera
            existing.label == existing.cameraId && camera.label != camera.cameraId -> camera
            existing.role == CamerasPageRole.CAMERA && camera.role != CamerasPageRole.CAMERA -> camera
            else -> existing
        }
    }

    floorMap.tables.forEach { table ->
        val cameraId = table.cameraId?.takeIf(String::isNotBlank) ?: return@forEach
        addCamera(
            CamerasPageCamera(
                cameraId = cameraId,
                label = table.cameraLabel?.takeIf(String::isNotBlank) ?: "${table.label} kamera",
                role = CamerasPageRole.TABLE,
                tableLabel = table.label,
            ),
        )
    }

    floorMap.objects
        .asSequence()
        .filter { it.type.equals("camera", ignoreCase = true) }
        .forEach { floorObject ->
            val cameraId = floorObject.cameraId?.takeIf(String::isNotBlank) ?: return@forEach
            val label = floorObject.label.takeIf(String::isNotBlank) ?: cameraId
            addCamera(
                CamerasPageCamera(
                    cameraId = cameraId,
                    label = label,
                    role = floorObject.cameraPageRole(),
                    tableLabel = floorObject.linkedTargetId,
                ),
            )
        }

    return byCameraId.values.sortedWith(
        compareBy<CamerasPageCamera> { it.role.sortOrder() }
            .thenBy { it.cameraId },
    )
}

private fun FloorMapObject.cameraPageRole(): CamerasPageRole {
    val linked = linkedTargetType.orEmpty()
    val coverage = coverageType.orEmpty().lowercase()
    return when {
        linked.equals("table", ignoreCase = true) -> CamerasPageRole.TABLE
        "queue" in coverage || "lineup" in coverage || "jono" in coverage -> CamerasPageRole.QUEUE
        "general" in coverage -> CamerasPageRole.GENERAL
        else -> CamerasPageRole.CAMERA
    }
}

private fun CamerasPageRole.displayLabel(): String {
    return when (this) {
        CamerasPageRole.TABLE -> "TABLE"
        CamerasPageRole.QUEUE -> "QUEUE"
        CamerasPageRole.GENERAL -> "GENERAL"
        CamerasPageRole.CAMERA -> "CAMERA"
    }
}

private fun CamerasPageRole.sortOrder(): Int {
    return when (this) {
        CamerasPageRole.TABLE -> 0
        CamerasPageRole.QUEUE -> 1
        CamerasPageRole.GENERAL -> 2
        CamerasPageRole.CAMERA -> 3
    }
}

private fun StaffUiLanguage.toCashierLanguage(): CashierLanguage {
    return when (this) {
        StaffUiLanguage.FI -> CashierLanguage.FI
        StaffUiLanguage.EN -> CashierLanguage.EN
    }
}

private fun camerasPageLatestFrameUrl(
    edgeBaseUrl: String,
    cameraId: String,
    cacheBustEpochMillis: Long,
): String {
    val base = edgeBaseUrl.trim().trimEnd('/')
    val encodedCameraId = URLEncoder.encode(cameraId, Charsets.UTF_8.name())
    return "$base/vision/frame/latest?camera_id=$encodedCameraId&_=$cacheBustEpochMillis"
}

private suspend fun fetchCamerasPageFrameBitmap(urlString: String): Bitmap = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1_500
            readTimeout = 2_500
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "image/jpeg,image/png,*/*")
        }
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            throw IllegalStateException("Kamerakuva ei ole saatavilla.")
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Kamerakuvaa ei voitu lukea.")
    } finally {
        connection?.disconnect()
    }
}


@Composable
private fun AppRail(
    navController: NavHostController,
    strings: CashierStrings,
    onSellerSwitchRequested: () -> Unit,
    isQuickSaleDirty: Boolean = false,
    onNavigationBlocked: ((String) -> Unit)? = null,
    onNavigateToMenu: (() -> Unit)? = null,
    onWillNavigateAway: (() -> Unit)? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
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
                    iconKind = destination.iconKind,
                    iconContainerColor = destination.iconContainerColor,
                    iconTint = destination.iconTint,
                    selected = isRailDestinationSelected(currentRoute, destination.route),
                    onClick = {
                        val alreadySelected = isRailDestinationSelected(currentRoute, destination.route)
                        if (isQuickSaleDirty && !alreadySelected) {
                            onNavigationBlocked?.invoke(destination.route)
                        } else if (destination.route == Routes.TableMap) {
                            val previousRoute = navController.previousBackStackEntry?.destination?.route
                            if (!alreadySelected) {
                                onWillNavigateAway?.invoke()
                                if (previousRoute == Routes.TableMap || previousRoute == Routes.TableMapPattern) {
                                    navController.popBackStack()
                                } else {
                                    navController.navigate(Routes.tableMap()) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        } else if (destination.route == Routes.Menu) {
                            if (!alreadySelected) onNavigateToMenu?.invoke()
                        } else {
                            onWillNavigateAway?.invoke()
                            navController.navigate(destination.route)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            RailButton(
                label = strings[CashierStringKey.RailStaff],
                iconKind = RailIconKind.STAFF,
                iconContainerColor = Color(0xFF243A2F),
                iconTint = Color(0xFFB7F3C8),
                selected = isRailDestinationSelected(currentRoute, Routes.Shift),
                onClick = onSellerSwitchRequested,
            )
        }
    }
}

@Composable
private fun StaffMenuRow(
    staff: StaffMember,
    present: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = AppShellButtonColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaffMiniAvatar(
                staffName = staff.displayName,
                photoPainter = staffPhotoPainterFor(staff),
                modifier = Modifier.size(STAFF_MENU_AVATAR_SIZE),
            )
            Text(
                text = staff.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = AppShellTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (present) {
                Box(
                    modifier = Modifier
                        .size(STAFF_MENU_SCHEDULE_DOT_SIZE)
                        .background(Color(0xFF7DD88F), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun StaffClockedInRow(
    entry: AttendanceEntry,
    enabled: Boolean,
    onClockOut: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppShellButtonMutedColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppShellBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaffMiniAvatar(
                staffName = entry.staffName,
                photoPainter = staffPhotoPainterForStaffId(entry.staffId),
                modifier = Modifier.size(34.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.staffName,
                    style = MaterialTheme.typography.labelLarge,
                    color = AppShellTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Työaikakirjaus aktiivinen",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppShellTextMuted,
                    maxLines = 1,
                )
            }
            Button(
                onClick = onClockOut,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppShellButtonColor,
                    contentColor = AppShellTextPrimary,
                ),
            ) {
                Text("Lopeta")
            }
        }
    }
}

@Composable
private fun StaffMiniAvatar(
    staffName: String,
    photoPainter: Painter?,
    modifier: Modifier = Modifier,
) {
    if (photoPainter != null) {
        Image(
            painter = photoPainter,
            contentDescription = staffName,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(AppShellButtonColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = staffInitials(staffName),
                style = MaterialTheme.typography.labelMedium,
                color = AppShellAccentText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun staffInitials(displayName: String): String {
    return displayName
        .split(" ")
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar()?.toString() }
        .take(2)
        .joinToString("")
        .ifBlank { "AI" }
}

private fun staffSurnameSortKey(displayName: String): String {
    val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return (parts.lastOrNull() ?: displayName).lowercase()
}


@Composable
private fun PremiumRailIcon(
    kind: RailIconKind,
    tint: Color,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val s = size.minDimension
        val stroke = Stroke(
            width = s * 0.078f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val thinStroke = Stroke(
            width = s * 0.055f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val strongStroke = Stroke(
            width = s * 0.092f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val outline = tint.copy(alpha = if (selected) 0.96f else 0.82f)
        val soft = accent.copy(alpha = if (selected) 0.34f else 0.20f)
        val faint = accent.copy(alpha = if (selected) 0.18f else 0.10f)
        drawCircle(
            color = faint,
            radius = s * 0.48f,
            center = Offset(w / 2f, h / 2f),
        )

        when (kind) {
            RailIconKind.TABLES -> {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(w * 0.16f, h * 0.15f),
                    size = Size(w * 0.68f, h * 0.68f),
                    cornerRadius = CornerRadius(s * 0.10f, s * 0.10f),
                    style = stroke,
                )
                drawLine(outline, Offset(w * 0.32f, h * 0.15f), Offset(w * 0.32f, h * 0.34f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                drawLine(outline, Offset(w * 0.16f, h * 0.48f), Offset(w * 0.35f, h * 0.48f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                drawLine(outline, Offset(w * 0.63f, h * 0.15f), Offset(w * 0.63f, h * 0.34f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                drawLine(outline, Offset(w * 0.63f, h * 0.66f), Offset(w * 0.84f, h * 0.66f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                drawCircle(soft, radius = s * 0.18f, center = Offset(w * 0.50f, h * 0.55f))
                drawCircle(outline, radius = s * 0.16f, center = Offset(w * 0.50f, h * 0.55f), style = thinStroke)
                drawRoundRect(outline, Offset(w * 0.43f, h * 0.79f), Size(w * 0.14f, h * 0.06f), CornerRadius(s * 0.03f, s * 0.03f))
                drawRoundRect(outline, Offset(w * 0.43f, h * 0.25f), Size(w * 0.14f, h * 0.06f), CornerRadius(s * 0.03f, s * 0.03f))
                drawRoundRect(outline, Offset(w * 0.21f, h * 0.50f), Size(w * 0.06f, h * 0.14f), CornerRadius(s * 0.03f, s * 0.03f))
                drawRoundRect(outline, Offset(w * 0.73f, h * 0.50f), Size(w * 0.06f, h * 0.14f), CornerRadius(s * 0.03f, s * 0.03f))
            }
            RailIconKind.PRODUCTS -> {
                val boxSize = s * 0.25f
                val gap = s * 0.12f
                val startX = w * 0.20f
                val startY = h * 0.20f
                listOf(
                    Offset(startX, startY),
                    Offset(startX + boxSize + gap, startY),
                    Offset(startX, startY + boxSize + gap),
                    Offset(startX + boxSize + gap, startY + boxSize + gap),
                ).forEachIndexed { index, topLeft ->
                    drawRoundRect(
                        color = if (index == 0 && selected) soft else Color.Transparent,
                        topLeft = topLeft,
                        size = Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(s * 0.07f, s * 0.07f),
                    )
                    drawRoundRect(
                        color = outline,
                        topLeft = topLeft,
                        size = Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(s * 0.07f, s * 0.07f),
                        style = thinStroke,
                    )
                }
                drawLine(outline, Offset(w * 0.20f, h * 0.86f), Offset(w * 0.80f, h * 0.86f), strokeWidth = s * 0.055f, cap = StrokeCap.Round)
            }
            RailIconKind.SALES -> {
                val receipt = Path().apply {
                    moveTo(w * 0.26f, h * 0.15f)
                    lineTo(w * 0.74f, h * 0.15f)
                    quadraticBezierTo(w * 0.82f, h * 0.15f, w * 0.82f, h * 0.23f)
                    lineTo(w * 0.82f, h * 0.76f)
                    lineTo(w * 0.70f, h * 0.84f)
                    lineTo(w * 0.60f, h * 0.76f)
                    lineTo(w * 0.50f, h * 0.84f)
                    lineTo(w * 0.40f, h * 0.76f)
                    lineTo(w * 0.30f, h * 0.84f)
                    lineTo(w * 0.18f, h * 0.76f)
                    lineTo(w * 0.18f, h * 0.23f)
                    quadraticBezierTo(w * 0.18f, h * 0.15f, w * 0.26f, h * 0.15f)
                    close()
                }
                drawPath(receipt, soft)
                drawPath(receipt, outline, style = stroke)
                drawLine(outline, Offset(w * 0.35f, h * 0.42f), Offset(w * 0.68f, h * 0.42f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                drawLine(outline.copy(alpha = 0.76f), Offset(w * 0.33f, h * 0.57f), Offset(w * 0.70f, h * 0.57f), strokeWidth = s * 0.045f, cap = StrokeCap.Round)
                drawCircle(outline, radius = s * 0.09f, center = Offset(w * 0.43f, h * 0.30f), style = thinStroke)
                drawLine(outline, Offset(w * 0.40f, h * 0.30f), Offset(w * 0.55f, h * 0.30f), strokeWidth = s * 0.045f, cap = StrokeCap.Round)
            }
            RailIconKind.RESERVATIONS -> {
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.17f, h * 0.22f),
                    size = Size(w * 0.66f, h * 0.58f),
                    cornerRadius = CornerRadius(s * 0.09f, s * 0.09f),
                )
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(w * 0.17f, h * 0.22f),
                    size = Size(w * 0.66f, h * 0.58f),
                    cornerRadius = CornerRadius(s * 0.09f, s * 0.09f),
                    style = stroke,
                )
                drawLine(outline, Offset(w * 0.28f, h * 0.13f), Offset(w * 0.28f, h * 0.29f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
                drawLine(outline, Offset(w * 0.72f, h * 0.13f), Offset(w * 0.72f, h * 0.29f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
                drawLine(outline, Offset(w * 0.18f, h * 0.39f), Offset(w * 0.82f, h * 0.39f), strokeWidth = s * 0.055f, cap = StrokeCap.Round)
                listOf(0.32f to 0.53f, 0.50f to 0.53f, 0.32f to 0.68f).forEach { (x, y) ->
                    drawCircle(outline, radius = s * 0.035f, center = Offset(w * x, h * y))
                }
                drawPath(Path().apply {
                    moveTo(w * 0.62f, h * 0.70f)
                    quadraticBezierTo(w * 0.71f, h * 0.68f, w * 0.72f, h * 0.56f)
                    lineTo(w * 0.72f, h * 0.50f)
                    lineTo(w * 0.78f, h * 0.50f)
                    lineTo(w * 0.78f, h * 0.74f)
                    lineTo(w * 0.62f, h * 0.74f)
                    close()
                }, outline, style = thinStroke)
            }
            RailIconKind.CAMERAS -> {
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.17f, h * 0.33f),
                    size = Size(w * 0.66f, h * 0.42f),
                    cornerRadius = CornerRadius(s * 0.10f, s * 0.10f),
                )
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(w * 0.17f, h * 0.33f),
                    size = Size(w * 0.66f, h * 0.42f),
                    cornerRadius = CornerRadius(s * 0.10f, s * 0.10f),
                    style = stroke,
                )
                drawPath(Path().apply {
                    moveTo(w * 0.32f, h * 0.33f)
                    lineTo(w * 0.40f, h * 0.24f)
                    lineTo(w * 0.61f, h * 0.24f)
                    lineTo(w * 0.69f, h * 0.33f)
                }, outline, style = stroke)
                drawCircle(outline, radius = s * 0.15f, center = Offset(w * 0.50f, h * 0.54f), style = stroke)
                drawCircle(outline.copy(alpha = 0.55f), radius = s * 0.035f, center = Offset(w * 0.70f, h * 0.43f))
            }
            RailIconKind.SETTINGS -> {
                drawCircle(soft, radius = s * 0.24f, center = Offset(w * 0.50f, h * 0.50f))
                drawCircle(outline, radius = s * 0.21f, center = Offset(w * 0.50f, h * 0.50f), style = strongStroke)
                drawCircle(AppShellRailColor.copy(alpha = 0.92f), radius = s * 0.095f, center = Offset(w * 0.50f, h * 0.50f))
                drawCircle(outline, radius = s * 0.090f, center = Offset(w * 0.50f, h * 0.50f), style = thinStroke)
                listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { angle ->
                    val radians = Math.toRadians(angle.toDouble()).toFloat()
                    val dx = kotlin.math.cos(radians)
                    val dy = kotlin.math.sin(radians)
                    drawLine(
                        outline,
                        Offset(w * 0.50f + dx * s * 0.27f, h * 0.50f + dy * s * 0.27f),
                        Offset(w * 0.50f + dx * s * 0.37f, h * 0.50f + dy * s * 0.37f),
                        strokeWidth = s * 0.065f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            RailIconKind.STAFF -> {
                drawCircle(soft, radius = s * 0.17f, center = Offset(w * 0.50f, h * 0.31f))
                drawCircle(outline, radius = s * 0.15f, center = Offset(w * 0.50f, h * 0.31f), style = stroke)
                drawPath(Path().apply {
                    moveTo(w * 0.22f, h * 0.82f)
                    quadraticBezierTo(w * 0.26f, h * 0.57f, w * 0.50f, h * 0.57f)
                    quadraticBezierTo(w * 0.74f, h * 0.57f, w * 0.78f, h * 0.82f)
                    close()
                }, soft)
                drawPath(Path().apply {
                    moveTo(w * 0.22f, h * 0.82f)
                    quadraticBezierTo(w * 0.26f, h * 0.57f, w * 0.50f, h * 0.57f)
                    quadraticBezierTo(w * 0.74f, h * 0.57f, w * 0.78f, h * 0.82f)
                }, outline, style = stroke)
                drawLine(outline.copy(alpha = 0.72f), Offset(w * 0.39f, h * 0.68f), Offset(w * 0.61f, h * 0.68f), strokeWidth = s * 0.055f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    iconKind: RailIconKind,
    iconContainerColor: Color,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(vertical = 2.dp)
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
                .width(RAIL_BUTTON_WIDTH)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = railIconDrawableRes(iconKind)),
                contentDescription = label,
                modifier = Modifier.size(58.dp),
                contentScale = ContentScale.Fit,
            )

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) AppShellTextPrimary else AppShellTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun loadJournalNotesFromPrefs(prefs: SharedPreferences): List<JournalNote> {
    val json = prefs.getString("notes_json", null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            JournalNote(
                text = obj.getString("text"),
                authorName = obj.getString("authorName"),
                timestampMillis = obj.getLong("timestampMillis"),
                source = obj.optString("source", ""),
                editable = obj.optBoolean("editable", false),
            )
        }
    }.getOrDefault(emptyList())
}

private fun sameJournalNoteIdentity(left: JournalNote, right: JournalNote): Boolean {
    return left.timestampMillis == right.timestampMillis &&
        left.authorName == right.authorName &&
        left.text == right.text
}

private fun saveJournalNotesToPrefs(prefs: SharedPreferences, notes: List<JournalNote>): Boolean {
    val arr = JSONArray()
    notes.forEach { note ->
        arr.put(
            JSONObject().apply {
                put("text", note.text)
                put("authorName", note.authorName)
                put("timestampMillis", note.timestampMillis)
                put("source", note.source)
                put("editable", note.editable)
            },
        )
    }
    return prefs.edit().putString("notes_json", arr.toString()).commit()
}

private fun loadLastSeenEventsFromPrefs(prefs: SharedPreferences): List<LastSeenAuthEvent> {
    val json = prefs.getString("events_json", null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LastSeenAuthEvent(
                staffId = obj.getString("staffId"),
                staffName = obj.getString("staffName"),
                timestampMillis = obj.getLong("timestampMillis"),
            )
        }
    }.getOrDefault(emptyList())
}

private fun saveLastSeenEventsToPrefs(prefs: SharedPreferences, events: List<LastSeenAuthEvent>) {
    val arr = JSONArray()
    events.forEach { event ->
        arr.put(
            JSONObject().apply {
                put("staffId", event.staffId)
                put("staffName", event.staffName)
                put("timestampMillis", event.timestampMillis)
            },
        )
    }
    prefs.edit().putString("events_json", arr.toString()).apply()
}
