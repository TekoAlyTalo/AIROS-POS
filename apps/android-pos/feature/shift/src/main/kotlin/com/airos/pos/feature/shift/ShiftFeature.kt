package com.airos.pos.feature.shift

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.CashDrawer
import com.airos.pos.core.model.CashExpectedState
import com.airos.pos.core.model.CashLedgerState
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.ShiftScheduleDay
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import com.airos.pos.core.model.ShiftStatus
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import com.airos.pos.core.ui.NumericMoneyPad
import com.airos.pos.domain.CashLedgerRepository
import com.airos.pos.domain.ShiftRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ShiftPageBackground = Color(0xFF0D151E)
private val ShiftPanelColor = Color(0xFF111B25)
private val ShiftPanelRaisedColor = Color(0xFF152231)
private val ShiftPanelDeepColor = Color(0xFF09111A)
private val ShiftBorderColor = Color(0x2637D6C8)
private val ShiftBorderWarmColor = Color(0x38D6A557)
private val ShiftTextPrimary = Color(0xFFF8FBFF)
private val ShiftTextSecondary = Color(0xFFDDE8EF)
private val ShiftTextMuted = Color(0xFF9FB0BD)
private val ShiftCyan = Color(0xFF85F5E0)
private val ShiftCyanSoft = Color(0xFF2C7C80)
private val ShiftGold = Color(0xFFD6A557)
private val ShiftSuccess = Color(0xFF7DD88F)
private val ShiftWarning = Color(0xFFE5B65D)
private val ShiftUpcoming = Color(0xFF5CAEFF)
private val ShiftAbsent = Color(0xFFB0A7D8)
private val ShiftDanger = Color(0xFFFF6B65)
private val ShiftLineColor = Color(0x1AFFFFFF)
private val ShiftKeyColor = Color(0xFF182633)
private val ShiftKeyContentColor = Color(0xFFEAF5FA)
private val ShiftJournalDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault())
private val ShiftJournalTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val ShiftCardShape = RoundedCornerShape(26.dp)
private val ShiftInnerShape = RoundedCornerShape(18.dp)

data class ShiftUiState(
    val currentShift: PosShift? = null,
    val openingFloatInput: String = "50,00",
    val countedCashInput: String = "",
    val cashLedgerState: CashLedgerState = CashLedgerState(),
    val busy: Boolean = false,
    val message: String? = null,
    val journalEventId: Long = 0,
    val journalEventText: String? = null,
)

class ShiftViewModel(
    private val shiftRepository: ShiftRepository,
    private val cashLedgerRepository: CashLedgerRepository,
    private val defaultOpeningFloatCents: Int = 5000,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ShiftUiState(openingFloatInput = centsToEuroInput(defaultOpeningFloatCents)),
    )
    val uiState: StateFlow<ShiftUiState> = mutableState.asStateFlow()
    private var truthMissingIssueRecorded = false

    init {
        viewModelScope.launch {
            shiftRepository.observeCurrentShift().collect { shift ->
                mutableState.update { it.copy(currentShift = shift?.takeIf { current -> current.status == ShiftStatus.OPEN }, busy = false) }
            }
        }
        viewModelScope.launch {
            cashLedgerRepository.observeState(CashDrawer.DEFAULT_DRAWER_ID).collect { ledgerState ->
                mutableState.update { it.copy(cashLedgerState = ledgerState) }
                if (ledgerState.expectedState == CashExpectedState.MISSING_TRUTH && !truthMissingIssueRecorded) {
                    truthMissingIssueRecorded = true
                    cashLedgerRepository.recordTruthMissing(
                        drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                        staffId = null,
                        staffName = null,
                        reason = ledgerState.warningMessage ?: "Cash truth missing in POS VUORO.",
                    )
                }
            }
        }
    }

    fun updateOpeningFloat(value: String) {
        mutableState.update { it.copy(openingFloatInput = value, message = null) }
    }

    fun updateCountedCash(value: String) {
        mutableState.update { it.copy(countedCashInput = value, message = null) }
    }

    fun openShift(staffId: String, staffName: String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            val state = mutableState.value
            val openingCents = state.cashLedgerState.expectedCashCents ?: defaultOpeningFloatCents
            val openingSource = if (state.cashLedgerState.expectedCashCents != null) {
                "EXPECTED_CASH"
            } else {
                "CONFIGURED_DEFAULT_OPENING_FLOAT"
            }
            when (val openResult = shiftRepository.openShift(openingCents, staffId)) {
                is PosResult.Failure -> mutableState.update { it.copy(message = openResult.message, busy = false) }
                is PosResult.Success -> {
                    when (val cashResult = cashLedgerRepository.recordCashOpened(
                        drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                        amountCents = openingCents,
                        staffId = staffId,
                        staffName = staffName,
                        source = openingSource,
                    )) {
                        is PosResult.Success -> mutableState.update {
                            it.copy(
                                currentShift = openResult.value,
                                openingFloatInput = centsToEuroInput(openingCents),
                                busy = false,
                                journalEventId = it.journalEventId + 1,
                                journalEventText = "Ravintola avattu · Pohjakassa ${CentsFormatter.format(openingCents)}",
                            )
                        }
                        is PosResult.Failure -> mutableState.update { it.copy(message = cashResult.message, busy = false) }
                    }
                }
            }
        }
    }

    fun recordCashCount(staffId: String, staffName: String?) {
        val cents = euroInputToCents(mutableState.value.countedCashInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Laskettu käteinen: syötä kelvollinen euromäärä, esimerkiksi 123,45.") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = cashLedgerRepository.recordCashCount(
                drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                amountCents = cents,
                staffId = staffId,
                staffName = staffName,
            )) {
                is PosResult.Success -> mutableState.update {
                    it.copy(
                        countedCashInput = centsToEuroInput(cents),
                        busy = false,
                        journalEventId = it.journalEventId + 1,
                        journalEventText = "Kassa laskettu · ${CentsFormatter.format(cents)}",
                    )
                }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    fun closeShiftWithCount(staffId: String, staffName: String?) {
        val cents = euroInputToCents(mutableState.value.countedCashInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Laskettu käteinen: syötä kelvollinen euromäärä, esimerkiksi 123,45.") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            val countResult = cashLedgerRepository.recordCashCount(
                drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                amountCents = cents,
                staffId = staffId,
                staffName = staffName,
                note = "Counted at restaurant close.",
            )
            if (countResult is PosResult.Failure) {
                mutableState.update { it.copy(message = countResult.message, busy = false) }
                return@launch
            }
            when (val closeResult = shiftRepository.closeShift(cents)) {
                is PosResult.Failure -> mutableState.update { it.copy(message = closeResult.message, busy = false) }
                is PosResult.Success -> {
                    when (val cashClose = cashLedgerRepository.recordCashClosed(
                        drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                        amountCents = cents,
                        staffId = staffId,
                        staffName = staffName,
                        countedAtClose = true,
                        note = "Restaurant closed with fresh cash count.",
                    )) {
                        is PosResult.Success -> mutableState.update {
                            it.copy(
                                currentShift = null,
                                countedCashInput = centsToEuroInput(cents),
                                busy = false,
                                journalEventId = it.journalEventId + 1,
                                journalEventText = "Ravintola suljettu · Kassa laskettu: ${CentsFormatter.format(cents)}",
                            )
                        }
                        is PosResult.Failure -> mutableState.update { it.copy(message = cashClose.message, busy = false) }
                    }
                }
            }
        }
    }

    fun closeShiftUsingLatestTruth(staffId: String, staffName: String?) {
        val amountCents = mutableState.value.cashLedgerState.latestExplicitCashCents
        if (amountCents == null) {
            mutableState.update {
                it.copy(message = "Kassaa ei voi sulkea ilman kassalaskentaa tai viimeisintä vahvistettua kassasummaa.")
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val closeResult = shiftRepository.closeShift(amountCents)) {
                is PosResult.Failure -> mutableState.update { it.copy(message = closeResult.message, busy = false) }
                is PosResult.Success -> {
                    when (val cashClose = cashLedgerRepository.recordCashClosed(
                        drawerId = CashDrawer.DEFAULT_DRAWER_ID,
                        amountCents = amountCents,
                        staffId = staffId,
                        staffName = staffName,
                        countedAtClose = false,
                        note = "Restaurant closed without new cash count; latest explicit cash truth used.",
                    )) {
                        is PosResult.Success -> mutableState.update {
                            it.copy(
                                currentShift = null,
                                busy = false,
                                journalEventId = it.journalEventId + 1,
                                journalEventText = "Ravintola suljettu · Kassaa ei laskettu · Käytetty viimeisintä kassasummaa ${CentsFormatter.format(amountCents)}",
                            )
                        }
                        is PosResult.Failure -> mutableState.update { it.copy(message = cashClose.message, busy = false) }
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            shiftRepository: ShiftRepository,
            cashLedgerRepository: CashLedgerRepository,
            defaultOpeningFloatCents: Int = 5000,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShiftViewModel(shiftRepository, cashLedgerRepository, defaultOpeningFloatCents) }
        }

        /**
         * Convert a user-typed euro string (e.g. "50,00" or "50.00" or "50") to cents.
         * Returns null if the input is not a valid euro amount.
         * Accepts comma or period as decimal separator.
         * At most 2 decimal places.
         */
        fun euroInputToCents(input: String): Int? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val normalized = trimmed.replace(',', '.')
            if (normalized.count { it == '.' } > 1) return null
            val parts = normalized.split('.')
            val integerPart = parts[0].toLongOrNull() ?: return null
            if (integerPart < 0) return null
            val fractionCents = if (parts.size == 2) {
                val frac = parts[1]
                if (frac.length > 2 || frac.isEmpty()) return null
                val padded = frac.padEnd(2, '0')
                padded.toIntOrNull() ?: return null
            } else {
                0
            }
            val totalCents = integerPart * 100 + fractionCents
            if (totalCents > Int.MAX_VALUE) return null
            return totalCents.toInt()
        }

        /** Convert cents to a user-friendly euro input string: 5000 → "50,00" */
        fun centsToEuroInput(cents: Int): String {
            val euros = cents / 100
            val remainder = cents % 100
            return "$euros,${remainder.toString().padStart(2, '0')}"
        }
    }
}

private enum class CashWorkspaceMode {
    COUNT,
    CLOSE,
}

@Composable
fun ShiftScreen(
    state: ShiftUiState,
    currentStaffId: String?,
    currentStaffName: String? = null,
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String, String?) -> Unit,
    onRecordCashCount: (String, String?) -> Unit,
    onCloseShiftWithCount: (String, String?) -> Unit,
    onCloseShiftUsingLatestTruth: (String, String?) -> Unit,
    attendance: WorktimeAttendanceSnapshot = WorktimeAttendanceSnapshot(),
    isClockedIn: Boolean = false,
    myAttendanceEntry: AttendanceEntry? = null,
    schedule: ShiftScheduleSnapshot? = null,
    scheduleLoading: Boolean = false,
    scheduleMessage: String? = null,
    ownSchedule: ShiftScheduleSnapshot? = null,
    ownScheduleLoading: Boolean = false,
    ownScheduleMessage: String? = null,
    attendanceStateLoading: Boolean = false,
    attendanceBusy: Boolean = false,
    attendanceNoticeMessage: String? = null,
    attendanceMessage: String? = null,
    onClockIn: () -> Unit = {},
    onClockOut: () -> Unit = {},
    journalNotes: List<JournalNote> = emptyList(),
    onNoteAdded: (String) -> Boolean = { false },
    lastSeenEvents: List<LastSeenAuthEvent> = emptyList(),
) {
    var cashWorkspaceMode by remember(state.currentShift?.id) { mutableStateOf<CashWorkspaceMode?>(null) }
    val activeStaffName = currentStaffName?.takeIf { it.isNotBlank() } ?: "Tuntematon"
    var pendingCashOpenJournalText by remember { mutableStateOf<String?>(null) }
    var pendingCashCloseShiftId by remember { mutableStateOf<String?>(null) }
    var pendingCashCloseJournalText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.journalEventId) {
        val text = state.journalEventText
        if (state.journalEventId > 0 && !text.isNullOrBlank()) {
            if (onNoteAdded(text)) {
                cashWorkspaceMode = null
            }
        }
    }

    LaunchedEffect(
        pendingCashOpenJournalText,
        state.busy,
        state.message,
        state.currentShift?.id,
        state.currentShift?.status,
    ) {
        val journalText = pendingCashOpenJournalText ?: return@LaunchedEffect
        if (state.busy) return@LaunchedEffect
        if (state.message != null) {
            pendingCashOpenJournalText = null
            return@LaunchedEffect
        }
        if (false && state.currentShift?.status == ShiftStatus.OPEN) {
            onNoteAdded(journalText)
            pendingCashOpenJournalText = null
        }
    }

    LaunchedEffect(
        pendingCashCloseShiftId,
        pendingCashCloseJournalText,
        state.busy,
        state.message,
        state.currentShift?.id,
        state.currentShift?.status,
    ) {
        val shiftId = pendingCashCloseShiftId ?: return@LaunchedEffect
        val journalText = pendingCashCloseJournalText ?: return@LaunchedEffect
        if (state.busy) return@LaunchedEffect
        if (state.message != null) {
            pendingCashCloseShiftId = null
            pendingCashCloseJournalText = null
            return@LaunchedEffect
        }
        val stillOpenSameShift = state.currentShift?.id == shiftId && state.currentShift.status == ShiftStatus.OPEN
        if (false && !stillOpenSameShift) {
            onNoteAdded(journalText)
            pendingCashCloseShiftId = null
            pendingCashCloseJournalText = null
        }
    }

    fun requestOpenRestaurant(staffId: String) {
        val openingFloatCents = ShiftViewModel.euroInputToCents(state.openingFloatInput)
        pendingCashOpenJournalText = openingFloatCents?.let {
            "Ravintola avattu · Pohjakassa ${CentsFormatter.format(it)}"
        }
        onOpenShift(staffId, activeStaffName)
    }

    fun requestCloseRestaurant() {
        cashWorkspaceMode = null
        val currentShift = state.currentShift
        val countedCashCents = ShiftViewModel.euroInputToCents(state.countedCashInput)
        if (currentShift != null && countedCashCents != null) {
            pendingCashCloseShiftId = currentShift.id
            pendingCashCloseJournalText = "Ravintola suljettu · Kassa laskettu: ${CentsFormatter.format(countedCashCents)}"
        } else {
            pendingCashCloseShiftId = null
            pendingCashCloseJournalText = null
        }
        currentStaffId?.let { staffId -> onCloseShiftWithCount(staffId, activeStaffName) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftPageBackground)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShiftHeader()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShiftJournalCard(
                        state = state,
                        currentStaffName = currentStaffName,
                        isClockedIn = isClockedIn,
                        attendance = attendance,
                        attendanceNoticeMessage = attendanceNoticeMessage,
                        attendanceMessage = attendanceMessage,
                        journalNotes = journalNotes,
                        onNoteAdded = onNoteAdded,
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                    )

                    ShiftSchedulePulseCard(
                        schedule = schedule,
                        attendance = attendance,
                        loading = scheduleLoading,
                        message = scheduleMessage,
                        lastSeenEvents = lastSeenEvents,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.65f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorktimeSummaryCard(
                        currentStaffId = currentStaffId,
                        currentStaffName = currentStaffName,
                        isClockedIn = isClockedIn,
                        myAttendanceEntry = myAttendanceEntry,
                        attendanceStateLoading = attendanceStateLoading,
                        attendanceBusy = attendanceBusy,
                        attendanceMessage = attendanceMessage,
                        ownSchedule = ownSchedule,
                        ownScheduleLoading = ownScheduleLoading,
                        ownScheduleMessage = ownScheduleMessage,
                        onClockIn = onClockIn,
                        onClockOut = onClockOut,
                        modifier = Modifier
                            .weight(1.35f)
                            .fillMaxHeight(),
                    )

                    CashShiftCard(
                        state = state,
                        currentStaffId = currentStaffId,
                        currentStaffName = currentStaffName,
                        onCashWorkspaceModeChanged = { cashWorkspaceMode = it },
                        onOpenShift = ::requestOpenRestaurant,
                        modifier = Modifier
                            .weight(0.75f)
                            .fillMaxHeight(),
                    )
                }
            }

            if (cashWorkspaceMode != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.34f))
                            .clickable(onClick = { cashWorkspaceMode = null }),
                    )
                    CashLedgerWorkspaceOverlay(
                        mode = cashWorkspaceMode ?: CashWorkspaceMode.COUNT,
                        state = state,
                        currentStaffId = currentStaffId,
                        currentStaffName = activeStaffName,
                        onDismiss = { cashWorkspaceMode = null },
                        onCountedCashChanged = onCountedCashChanged,
                        onRecordCashCount = { staffId -> onRecordCashCount(staffId, activeStaffName) },
                        onCloseWithCount = { staffId -> onCloseShiftWithCount(staffId, activeStaffName) },
                        onCloseWithoutNewCount = { staffId -> onCloseShiftUsingLatestTruth(staffId, activeStaffName) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.96f)
                            .fillMaxHeight(0.98f),
                    )
                }
            }
        }
    }
}


@Composable
private fun ShiftHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Vuoro",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = ShiftTextPrimary,
                )
                Text(
                    text = "✦",
                    style = MaterialTheme.typography.titleLarge,
                    color = ShiftGold,
                )
            }
            Text(
                text = "Hallitse pohjakassaa, työaikaa ja henkilöstöä.",
                style = MaterialTheme.typography.bodyMedium,
                color = ShiftTextMuted,
            )
        }
    }
}

@Composable
private fun CashShiftCard(
    state: ShiftUiState,
    currentStaffId: String?,
    currentStaffName: String?,
    onCashWorkspaceModeChanged: (CashWorkspaceMode) -> Unit,
    onOpenShift: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentShift = state.currentShift
    val isOpen = currentShift != null
    val ledger = state.cashLedgerState
    val openedByLabel = remember(currentShift, currentStaffId, currentStaffName) {
        when {
            currentShift == null -> "-"
            currentShift.openedByStaffId == currentStaffId && !currentStaffName.isNullOrBlank() -> currentStaffName
            else -> currentShift.openedByStaffId
        }
    }
    val feedbackContext = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(feedbackContext, message, Toast.LENGTH_LONG).show()
        }
    }

    ShiftCard(
        title = "Pohjakassa",
        icon = "▣",
        modifier = modifier,
        statusLabel = if (isOpen) "Avoin" else "Suljettu",
        statusColor = if (isOpen) ShiftSuccess else ShiftWarning,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isOpen) {
                    ShiftKeyValueRow("Avaaja", openedByLabel)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CashSummaryMetric(
                        label = "Pohjakassa",
                        value = cashOpeningFloatText(state),
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    CashSummaryMetric(
                        label = "Kassassa pitäisi olla",
                        value = cashExpectedText(ledger),
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
                CashSummaryMetric(
                    label = "Viimeisin kassalaskenta",
                    value = latestCashCountText(ledger),
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                if (ledger.expectedState == CashExpectedState.MISSING_TRUTH) {
                    ShiftStatusBanner(
                        text = ledger.warningMessage ?: "Kassassa pitäisi olla ei ole laskettavissa. Laske kassa.",
                        tint = ShiftDanger,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onCashWorkspaceModeChanged(CashWorkspaceMode.COUNT) },
                    enabled = !state.busy && currentStaffId != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ShiftCyan.copy(alpha = 0.82f),
                        contentColor = Color(0xFF071109),
                        disabledContainerColor = ShiftPanelRaisedColor,
                        disabledContentColor = ShiftTextMuted,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = "Laske kassa",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Button(
                    onClick = {
                        if (isOpen) {
                            onCashWorkspaceModeChanged(CashWorkspaceMode.CLOSE)
                        } else {
                            currentStaffId?.let(onOpenShift)
                        }
                    },
                    enabled = !state.busy && currentStaffId != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOpen) ShiftGold.copy(alpha = 0.82f) else ShiftSuccess.copy(alpha = 0.82f),
                        contentColor = Color(0xFF071109),
                        disabledContainerColor = ShiftPanelRaisedColor,
                        disabledContentColor = ShiftTextMuted,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = if (isOpen) "Sulje ravintola" else "Avaa ravintola",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CashLedgerWorkspaceOverlay(
    mode: CashWorkspaceMode,
    state: ShiftUiState,
    currentStaffId: String?,
    currentStaffName: String?,
    onDismiss: () -> Unit,
    onCountedCashChanged: (String) -> Unit,
    onRecordCashCount: (String) -> Unit,
    onCloseWithCount: (String) -> Unit,
    onCloseWithoutNewCount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledger = state.cashLedgerState
    var replaceCountedCashOnNextInput by remember(mode) { mutableStateOf(true) }

    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
        ),
        shape = RoundedCornerShape(24.dp),
        color = ShiftPanelRaisedColor,
        contentColor = ShiftTextPrimary,
        border = BorderStroke(1.dp, ShiftBorderWarmColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (mode == CashWorkspaceMode.CLOSE) "Sulje ravintola" else "Kassalaskenta",
                        style = MaterialTheme.typography.titleMedium,
                        color = ShiftTextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = currentStaffName?.takeIf { it.isNotBlank() } ?: "Aktiivinen myyjä puuttuu",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShiftTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    modifier = Modifier
                        .height(38.dp)
                        .clickable(onClick = onDismiss),
                    shape = RoundedCornerShape(999.dp),
                    color = ShiftCyanSoft.copy(alpha = 0.38f),
                    border = BorderStroke(1.dp, ShiftCyan.copy(alpha = 0.5f)),
                    contentColor = ShiftTextPrimary,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Piilota",
                            style = MaterialTheme.typography.labelMedium,
                            color = ShiftTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                ShiftStatusBanner(text = message, tint = ShiftWarning)
            }
            if (ledger.expectedState == CashExpectedState.MISSING_TRUTH) {
                ShiftStatusBanner(
                    text = ledger.warningMessage ?: "Kassassa pitäisi olla ei ole laskettavissa. Laske kassa.",
                    tint = ShiftDanger,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CashSummaryMetric(
                    label = "Pohjakassa",
                    value = cashOpeningFloatText(state),
                    modifier = Modifier.weight(1f),
                )
                CashSummaryMetric(
                    label = "Kassassa pitäisi olla",
                    value = cashExpectedText(ledger),
                    modifier = Modifier.weight(1f),
                )
            }
            CashSummaryMetric(
                label = "Viimeisin kassalaskenta",
                value = latestCashCountText(ledger),
                modifier = Modifier.fillMaxWidth(),
            )
            CashAmountDisplay(
                value = state.countedCashInput,
                label = "Laskettu käteinen",
            )
            NumericMoneyPad(
                onDigit = { digit ->
                    val nextValue = if (replaceCountedCashOnNextInput) {
                        digit
                    } else {
                        appendShiftMoneyDigit(state.countedCashInput, digit)
                    }
                    replaceCountedCashOnNextInput = false
                    onCountedCashChanged(nextValue)
                },
                onDecimal = {
                    val nextValue = if (replaceCountedCashOnNextInput) {
                        "0,"
                    } else {
                        appendShiftMoneyDecimal(state.countedCashInput)
                    }
                    replaceCountedCashOnNextInput = false
                    onCountedCashChanged(nextValue)
                },
                onBackspace = {
                    replaceCountedCashOnNextInput = false
                    onCountedCashChanged(removeShiftMoneyChar(state.countedCashInput))
                },
                keyHeight = 48.dp,
                keyColor = ShiftKeyColor,
                keyContentColor = ShiftKeyContentColor,
            )

            Spacer(modifier = Modifier.weight(1f))

            if (mode == CashWorkspaceMode.CLOSE && ledger.latestExplicitCashCents != null) {
                Button(
                    onClick = { currentStaffId?.let(onCloseWithoutNewCount) },
                    enabled = !state.busy && currentStaffId != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ShiftPanelDeepColor,
                        contentColor = ShiftTextSecondary,
                        disabledContainerColor = ShiftPanelDeepColor,
                        disabledContentColor = ShiftTextMuted,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = "Sulje ilman uutta laskentaa",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (mode == CashWorkspaceMode.CLOSE && ledger.latestExplicitCashCents == null) {
                ShiftStatusBanner(
                    text = "Sulkeminen vaatii kassalaskennan, koska vahvistettua kassasummaa ei ole.",
                    tint = ShiftDanger,
                )
            }

            Button(
                onClick = {
                    currentStaffId?.let { staffId ->
                        if (mode == CashWorkspaceMode.CLOSE) {
                            onCloseWithCount(staffId)
                        } else {
                            onRecordCashCount(staffId)
                        }
                    }
                },
                enabled = !state.busy && currentStaffId != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == CashWorkspaceMode.CLOSE) ShiftGold.copy(alpha = 0.86f) else ShiftCyan.copy(alpha = 0.86f),
                    contentColor = Color(0xFF071109),
                    disabledContainerColor = ShiftPanelDeepColor,
                    disabledContentColor = ShiftTextMuted,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (mode == CashWorkspaceMode.CLOSE) "Kirjaa laskenta ja sulje" else "Kirjaa kassalaskenta",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun cashOpeningFloatText(state: ShiftUiState): String {
    return state.currentShift?.openingFloatCents?.let(CentsFormatter::format)
        ?: displayMoneyInput(state.openingFloatInput)
}

private fun cashExpectedText(ledger: CashLedgerState): String {
    val expectedCashCents = ledger.expectedCashCents
    return if (ledger.expectedState == CashExpectedState.AVAILABLE && expectedCashCents != null) {
        CentsFormatter.format(expectedCashCents)
    } else {
        "Ei laskettavissa"
    }
}

private fun latestCashCountText(ledger: CashLedgerState): String {
    return ledger.latestCountedCashCents?.let(CentsFormatter::format) ?: "Ei kassalaskentaa"
}

@Composable
private fun WorktimeSummaryCard(
    currentStaffId: String?,
    currentStaffName: String?,
    isClockedIn: Boolean,
    myAttendanceEntry: AttendanceEntry?,
    attendanceStateLoading: Boolean,
    attendanceBusy: Boolean,
    attendanceMessage: String?,
    ownSchedule: ShiftScheduleSnapshot?,
    ownScheduleLoading: Boolean,
    ownScheduleMessage: String?,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStaffName = currentStaffName?.takeIf { it.isNotBlank() } ?: "Aktiivinen myyjä puuttuu"
    val startedAt = myAttendanceEntry
        ?.startedAt
        ?.let(::formatJournalTime)
        ?.takeIf { it != "--:--" }
    val durationText = myAttendanceEntry?.durationMinutes?.let(::formatDuration)

    ShiftCard(
        title = "Työaika",
        icon = "◷",
        modifier = modifier,
        statusLabel = when {
            attendanceStateLoading -> "Päivitetään"
            isClockedIn -> "Käynnissä"
            else -> "Ei käynnissä"
        },
        statusColor = when {
            attendanceStateLoading -> ShiftWarning
            isClockedIn -> ShiftSuccess
            else -> ShiftTextMuted
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.9f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShiftKeyValueRow("Henkilö", activeStaffName)
                    ShiftKeyValueRow("Tila", if (isClockedIn) "Työaika käynnissä" else "Ei aktiivista työaikaa")
                    if (isClockedIn) {
                        ShiftKeyValueRow("Aloitettu", startedAt ?: "Ei saatavilla")
                        ShiftKeyValueRow("Kesto", durationText ?: "Ei saatavilla", highlight = true)
                    }
                    attendanceMessage?.takeIf { it.isNotBlank() }?.let { message ->
                        ShiftStatusBanner(text = message, tint = ShiftWarning)
                    }
                }

                OwnShiftsCompactPanel(
                    schedule = ownSchedule,
                    currentStaffId = currentStaffId,
                    loading = ownScheduleLoading,
                    message = ownScheduleMessage,
                    modifier = Modifier.weight(1.1f),
                )
            }

            Button(
                onClick = if (isClockedIn) onClockOut else onClockIn,
                enabled = !attendanceBusy && !attendanceStateLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isClockedIn) ShiftGold.copy(alpha = 0.86f) else ShiftSuccess.copy(alpha = 0.86f),
                    contentColor = Color(0xFF071109),
                    disabledContainerColor = ShiftPanelRaisedColor,
                    disabledContentColor = ShiftTextMuted,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (isClockedIn) "Lopeta työaika" else "Aloita työaika",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun OwnShiftsCompactPanel(
    schedule: ShiftScheduleSnapshot?,
    currentStaffId: String?,
    loading: Boolean,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = ShiftTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Omat vuorot",
                    style = MaterialTheme.typography.labelLarge,
                    color = ShiftTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (schedule != null) ShiftUpcoming else ShiftTextMuted),
                )
            }

            when {
                currentStaffId.isNullOrBlank() -> ShiftCompactEmptyText("Ei aktiivista POS-henkilöä.")
                loading && schedule == null -> ShiftCompactEmptyText("Haetaan vuoroja...")
                message != null && schedule == null -> ShiftCompactEmptyText("Vuorot eivät ole saatavilla.")
                schedule == null -> ShiftCompactEmptyText("Vuoroja ei ole ladattu.")
                else -> {
                    val today = LocalDate.now()
                    val ownShifts = schedule.days
                        .filter { it.hasPublishedScheduleTruth() }
                        .flatMap { day ->
                            day.plannedShifts
                                .filter { shift ->
                                    shift.staffId == currentStaffId &&
                                        !shift.endsAt.toLocalDate().isBefore(today)
                                }
                                .map { shift -> day.date to shift }
                        }
                        .sortedBy { it.second.startsAt }
                        .take(3)
                    if (ownShifts.isEmpty()) {
                        ShiftCompactEmptyText("Ei tulevia julkaistuja vuoroja.")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            ownShifts.forEach { (date, shift) ->
                                OwnShiftCompactRow(date = date, shift = shift)
                            }
                        }
                    }
                }
            }

            if (message != null && schedule != null) {
                Text(
                    text = "Viimeksi ladattu näkymä",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShiftWarning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OwnShiftCompactRow(date: LocalDate, shift: PlannedStaffShift) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(ShiftUpcoming),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatCompactShiftDate(date),
                style = MaterialTheme.typography.labelMedium,
                color = ShiftTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = plannedShiftTimeRange(shift),
                style = MaterialTheme.typography.labelLarge,
                color = ShiftTextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShiftCompactEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ShiftTextMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun OnSiteAttendanceCard(
    attendance: WorktimeAttendanceSnapshot,
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Työajalla oleva henkilöstö",
        icon = "☷",
        modifier = modifier,
        statusLabel = attendance.currentlyOnSite.size.takeIf { it > 0 }?.toString(),
        statusColor = ShiftGold,
    ) {
        val entries = attendance.currentlyOnSite
        if (entries.isEmpty()) {
            ShiftEmptyText("Ei aktiivista työaikaa.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { entry ->
                    AttendanceCompactRow(entry)
                }
            }
        }
    }
}

@Composable
private fun ShiftSchedulePulseCard(
    schedule: ShiftScheduleSnapshot?,
    attendance: WorktimeAttendanceSnapshot,
    loading: Boolean,
    message: String?,
    lastSeenEvents: List<LastSeenAuthEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Työvuoropulssi",
        icon = "⌁",
        modifier = modifier,
    ) {
        var now by remember { mutableStateOf(LocalDateTime.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = LocalDateTime.now()
                val millisIntoMinute = System.currentTimeMillis() % 60_000L
                delay((60_000L - millisIntoMinute).coerceAtLeast(1_000L))
            }
        }
        var pulseZoom by remember { mutableStateOf(1f) }
        val todayDate = now.toLocalDate()
        val publishedDays = schedule?.days.orEmpty().filter { it.hasPublishedScheduleTruth() }
        val selectedDay = publishedDays
            .firstOrNull { it.date == todayDate }
            ?: publishedDays.firstOrNull { it.plannedShifts.isNotEmpty() }
            ?: publishedDays.firstOrNull()
        val operationalDay = selectedDay?.operationalDay
        val operationalWindowStart = operationalDay?.opensAt
        val operationalWindowEnd = operationalDay?.closesAt
        val visibleShifts = selectedDay
            ?.plannedShifts
            .orEmpty()
            .distinctBy { it.id }
            .sortedWith(compareBy<PlannedStaffShift> { it.startsAt }.thenBy { it.staffName })

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                loading && schedule == null -> ShiftEmptyText("Haetaan työvuorosuunnitelmaa backendistä.")
                message != null && schedule == null -> ShiftStatusBanner(
                    text = "Työvuorosuunnitelma ei ole saatavilla: $message",
                    tint = ShiftWarning,
                )
                schedule == null -> ShiftEmptyText("Työvuorosuunnitelma ei ole saatavilla.")
                publishedDays.isEmpty() -> ShiftEmptyText("Aikavälillä ei ole julkaistua työvuorosuunnitelmaa.")
                selectedDay == null -> ShiftEmptyText("Julkaistua työvuoropäivää ei ole valittavissa.")
                operationalDay == null || !operationalDay.truthAvailable -> ShiftStatusBanner(
                    text = "Ravintolan aukioloaikaa ei ole saatavilla.",
                    tint = ShiftDanger,
                )
                operationalDay.isClosed -> ShiftEmptyText("Ravintola on suljettu valittuna päivänä.")
                operationalWindowStart == null ||
                    operationalWindowEnd == null ||
                    !operationalWindowEnd.isAfter(operationalWindowStart) -> ShiftStatusBanner(
                    text = "Ravintolan aukioloaikaa ei ole saatavilla.",
                    tint = ShiftDanger,
                )
                else -> {
                    val windowStart = operationalWindowStart
                    val windowEnd = operationalWindowEnd
                    // Unscheduled active worktime: staff working without a planned shift must
                    // appear in Työvuoropulssi as a distinct worktime row so the active
                    // worktime truth is visible, but never as a planned-shift bar — and
                    // never with an invented end time. Identified by staffId not matching
                    // any planned shift for the selected day.
                    val plannedStaffIds = visibleShifts
                        .map { it.staffId }
                        .filter { it.isNotBlank() }
                        .toSet()
                    val unscheduledPresence = attendance.currentlyOnSite
                        .filter { it.staffId.isNotBlank() && it.staffId !in plannedStaffIds }
                        .distinctBy { it.staffId }
                    val latestLastSeenByStaffId = lastSeenEvents
                        .filter { event -> event.staffId.isNotBlank() && event.timestampMillis > 0L }
                        .filter { event ->
                            val ts = Instant.ofEpochMilli(event.timestampMillis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime()
                            !ts.isBefore(windowStart) && !ts.isAfter(windowEnd)
                        }
                        .groupBy { it.staffId }
                        .mapValues { (_, list) -> list.maxByOrNull { it.timestampMillis }!! }
                    val displayedStaffIds = plannedStaffIds +
                        unscheduledPresence.mapNotNull { it.staffId.takeIf { id -> id.isNotBlank() } }
                    val visibleLastSeen = latestLastSeenByStaffId.values
                        .filter { event -> event.staffId !in displayedStaffIds }
                        .sortedBy { it.timestampMillis }
                        .toList()
                    if (visibleShifts.isEmpty() && unscheduledPresence.isEmpty() && visibleLastSeen.isEmpty()) {
                        ShiftEmptyText("Julkaistuilla päivillä ei ole suunniteltuja vuoroja.")
                    } else {
                        val chartWidth = pulseChartWidth(
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            zoom = pulseZoom,
                        )
                        val horizontalScrollState = rememberScrollState()
                        val pinchZoomModifier = Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                var previousPinchDistance: Float? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedPointers = event.changes.filter { it.pressed }
                                    if (pressedPointers.size >= 2) {
                                        val pinchDistance = (pressedPointers[0].position - pressedPointers[1].position).getDistance()
                                        val previous = previousPinchDistance
                                        if (previous != null && previous > 0f && pinchDistance > 0f) {
                                            val zoomChange = (pinchDistance / previous).coerceIn(0.85f, 1.15f)
                                            if (zoomChange != 1f) {
                                                pulseZoom = (pulseZoom * zoomChange).coerceIn(PulseZoomMin, PulseZoomMax)
                                                pressedPointers.forEach { it.consume() }
                                            }
                                        }
                                        previousPinchDistance = pinchDistance
                                    } else {
                                        previousPinchDistance = null
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${formatPulseWindowTime(windowStart)}-${formatPulseWindowTime(windowEnd)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = ShiftTextMuted,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(pinchZoomModifier),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PulseTimelineHeader(
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                chartWidth = chartWidth,
                                horizontalScrollState = horizontalScrollState,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                            visibleShifts.forEachIndexed { index, shift ->
                                PulseTimelineShiftRow(
                                    shift = shift,
                                    attendance = attendance,
                                    now = now,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    chartWidth = chartWidth,
                                    horizontalScrollState = horizontalScrollState,
                                    lastSeenEvent = latestLastSeenByStaffId[shift.staffId],
                                )
                                val notLastShift = index != visibleShifts.lastIndex
                                val unscheduledFollows = unscheduledPresence.isNotEmpty()
                                val lastSeenFollows = visibleLastSeen.isNotEmpty()
                                if (notLastShift || unscheduledFollows || lastSeenFollows) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(ShiftLineColor),
                                    )
                                }
                            }
                            unscheduledPresence.forEachIndexed { index, entry ->
                                PulseTimelinePresenceRow(
                                    entry = entry,
                                    now = now,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    chartWidth = chartWidth,
                                    horizontalScrollState = horizontalScrollState,
                                    lastSeenEvent = latestLastSeenByStaffId[entry.staffId],
                                )
                                val moreLastSeenFollows = visibleLastSeen.isNotEmpty()
                                if (index != unscheduledPresence.lastIndex || moreLastSeenFollows) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(ShiftLineColor),
                                    )
                                }
                            }
                            // Standalone last-seen markers: factual authentication events for staff
                            // who are NOT currently in active worktime. Rendered as a
                            // point-in-time dot — never a duration bar. We suppress events
                            // for staff already shown as on-site to avoid duplicate or
                            // confusing display, keep only the latest event per staff,
                            // and clamp to the visible operational-day window without
                            // stretching it.
                            visibleLastSeen.forEachIndexed { index, event ->
                                PulseTimelineLastSeenRow(
                                    event = event,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    chartWidth = chartWidth,
                                    horizontalScrollState = horizontalScrollState,
                                )
                                if (index != visibleLastSeen.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(ShiftLineColor),
                                    )
                                }
                            }
                            }
                        }
                        }
                    }
                }

            if (message != null && schedule != null) {
                ShiftStatusBanner(
                    text = "Työvuorosuunnitelma käyttää viimeksi ladattua näkymää: $message",
                    tint = ShiftWarning,
                )
            }
        }
    }
}

@Composable
private fun OwnShiftsCard(
    schedule: ShiftScheduleSnapshot?,
    currentStaffId: String?,
    loading: Boolean,
    message: String?,
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Omat vuorot",
        icon = "▤",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                currentStaffId.isNullOrBlank() -> ShiftEmptyText("Omia vuoroja ei voi näyttää ilman aktiivista POS-henkilöä.")
                loading && schedule == null -> ShiftEmptyText("Haetaan omia julkaistuja vuoroja backendistä.")
                message != null && schedule == null -> ShiftStatusBanner(
                    text = "Omat vuorot eivät ole saatavilla: $message",
                    tint = ShiftWarning,
                )
                schedule == null -> ShiftEmptyText("Omia suunniteltuja vuoroja ei ole ladattu.")
                else -> {
                    val publishedDays = schedule.days.filter { it.hasPublishedScheduleTruth() }
                    val ownShifts = publishedDays
                        .flatMap { day -> day.plannedShifts.map { day.date to it } }
                        .sortedBy { it.second.startsAt }
                    if (publishedDays.isEmpty()) {
                        ShiftEmptyText("Julkaistuja omia työvuoropäiviä ei ole tällä aikavälillä.")
                    } else if (ownShifts.isEmpty()) {
                        ShiftEmptyText(
                            "Julkaistuilla päivillä ei ole vuoroja tälle POS-henkilölle. " +
                                "Tarkista backend staff_id -yhteys ennen kuin tätä tulkitaan vapaapäiväksi.",
                        )
                    } else {
                        var lastDate: LocalDate? = null
                        ownShifts.forEach { (date, shift) ->
                            if (lastDate != date) {
                                ShiftDayBoundary(label = formatScheduleDate(date))
                                lastDate = date
                            }
                            OwnPlannedShiftRow(shift = shift)
                        }
                    }
                }
            }

            if (message != null && schedule != null) {
                ShiftStatusBanner(
                    text = "Omat vuorot käyttävät viimeksi ladattua näkymää: $message",
                    tint = ShiftWarning,
                )
            }
        }
    }
}

@Composable
private fun ShiftJournalCard(
    state: ShiftUiState,
    currentStaffName: String?,
    isClockedIn: Boolean,
    attendance: WorktimeAttendanceSnapshot,
    attendanceNoticeMessage: String?,
    attendanceMessage: String?,
    journalNotes: List<JournalNote> = emptyList(),
    onNoteAdded: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Vuoropäiväkirja",
        icon = "☰",
        modifier = modifier,
    ) {
        // Vuoropäiväkirja shows journal events only — no recomposition-driven duplicates
        // of attendance state. The persistent journalNotes (written exactly once by the
        // app shell on worktime start/end and on cash open/close) are the truth here.
        val events = buildList {
            state.currentShift?.let { shift ->
                add(
                    JournalEvent(
                        marker = "✓",
                        tint = ShiftSuccess,
                        title = "Pohjakassa avoinna",
                        detail = "Pohjakassa ${CentsFormatter.format(shift.openingFloatCents)}",
                        timestamp = shift.openedAtEpochMillis.toString(),
                    ),
                )
            }
            attendanceNoticeMessage?.let {
                add(JournalEvent("i", ShiftCyan, "Työaikailmoitus", it))
            }
            attendanceMessage?.let {
                add(JournalEvent("!", ShiftDanger, "Työaikavirhe", it))
            }
            journalNotes.forEach { note ->
                add(
                    JournalEvent(
                        marker = "✎",
                        tint = ShiftGold,
                        title = note.text,
                        detail = "${note.authorName} · ${formatJournalTime(note.timestampMillis.toString())}",
                        timestamp = note.timestampMillis.toString(),
                    ),
                )
            }
        }

        var noteInput by remember { mutableStateOf("") }
        val journalScrollState = rememberScrollState()
        LaunchedEffect(events.size, journalScrollState.maxValue) {
            journalScrollState.scrollTo(journalScrollState.maxValue)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(journalScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (events.isEmpty()) {
                    ShiftEmptyText("Ei työvuoromerkintöjä.")
                } else {
                    var lastDay: String? = null
                    events.forEach { event ->
                        val day = formatJournalDay(event.timestamp)
                        if (day != lastDay) {
                            ShiftDayBoundary(label = day)
                            lastDay = day
                        }
                        JournalRow(event)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ShiftPanelDeepColor, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, ShiftBorderColor), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = ShiftTextPrimary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (noteInput.isBlank()) {
                                Text(
                                    text = "Kirjoita muistiinpano…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ShiftTextMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
                Surface(
                    modifier = Modifier
                        .heightIn(min = 30.dp)
                        .clickable(enabled = noteInput.isNotBlank()) {
                            if (noteInput.isNotBlank()) {
                                if (onNoteAdded(noteInput.trim())) {
                                    noteInput = ""
                                }
                            }
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = ShiftCyan.copy(alpha = if (noteInput.isNotBlank()) 0.18f else 0.06f),
                    border = BorderStroke(1.dp, ShiftCyan.copy(alpha = if (noteInput.isNotBlank()) 0.34f else 0.10f)),
                    contentColor = ShiftCyan,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Lisää",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShiftCyan.copy(alpha = if (noteInput.isNotBlank()) 1f else 0.38f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftCard(
    title: String,
    icon: String,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    statusColor: Color = ShiftCyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = ShiftCardShape,
        color = ShiftPanelColor,
        contentColor = ShiftTextPrimary,
        border = BorderStroke(1.dp, ShiftBorderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            ShiftPanelRaisedColor.copy(alpha = 0.78f),
                            ShiftPanelColor,
                            ShiftPanelDeepColor.copy(alpha = 0.84f),
                        ),
                    ),
                )
                .border(BorderStroke(1.dp, ShiftBorderWarmColor.copy(alpha = 0.12f)), ShiftCardShape)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShiftCardHeader(
                    title = title,
                    icon = icon,
                    statusLabel = statusLabel,
                    statusColor = statusColor,
                )
                content()
            }
        }
    }
}

@Composable
private fun ShiftCardHeader(
    title: String,
    icon: String,
    statusLabel: String?,
    statusColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ShiftCyanSoft.copy(alpha = 0.22f),
            border = BorderStroke(1.dp, ShiftBorderColor),
            contentColor = ShiftCyan,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = icon, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ShiftTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        statusLabel?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ShiftMiniActionButton(
    text: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.34f)),
        contentColor = tint,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CashSummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = ShiftTextPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ShiftTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ShiftGold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CashAmountDisplay(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val surfaceModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Surface(
        modifier = surfaceModifier,
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor,
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = ShiftTextPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 5.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ShiftTextMuted,
            )
            Text(
                text = displayMoneyInput(value),
                modifier = Modifier.fillMaxWidth(),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ShiftGold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StaffAvatar(name: String, tint: Color, modifier: Modifier = Modifier) {
    val initials = name
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .take(2)
        .joinToString("")
        .ifBlank { "?" }

    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        tint.copy(alpha = 0.34f),
                        ShiftPanelDeepColor,
                    ),
                ),
            )
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.34f)), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = ShiftTextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AttendanceCompactRow(entry: AttendanceEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaffAvatar(name = entry.staffName, tint = statusColorForAttendance(entry), modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.staffName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShiftTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabelForAttendance(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatDuration(entry.durationMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = statusColorForAttendance(entry),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private const val PulseZoomMin = 0.75f
private const val PulseZoomMax = 2.5f
private const val PulseZoomStep = 0.25f
private val PulseTimelineMinimumChartWidth = 684.dp
private val PulseTimelineWidthPerHour = 96.dp
private val PulseLeftColumnWidth = 176.dp
private val PulseTickLabelWidth = 48.dp
private val PulseTickLabelHalfWidth = 24.dp

private fun pulseChartWidth(windowStart: LocalDateTime, windowEnd: LocalDateTime, zoom: Float): Dp {
    val hours = Duration.between(windowStart, windowEnd).toMinutes().coerceAtLeast(60L) / 60f
    val chartWidth = PulseTimelineWidthPerHour * hours
    val baseChartWidth = if (chartWidth > PulseTimelineMinimumChartWidth) chartWidth else PulseTimelineMinimumChartWidth
    return baseChartWidth * zoom.coerceIn(PulseZoomMin, PulseZoomMax)
}

private fun pulseTickTimes(windowStart: LocalDateTime, windowEnd: LocalDateTime): List<LocalDateTime> {
    val ticks = mutableListOf<LocalDateTime>()
    var cursor = windowStart.truncatedTo(ChronoUnit.HOURS)
    while (!cursor.isAfter(windowEnd)) {
        ticks += cursor
        cursor = cursor.plusHours(1)
    }
    return ticks.distinct()
}

@Composable
private fun PulseZoomControls(
    zoom: Float,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onZoomIn: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseZoomButton(label = "-", enabled = zoom > PulseZoomMin, onClick = onZoomOut)
        PulseZoomButton(label = "${(zoom * 100).toInt()}%", enabled = true, onClick = onReset)
        PulseZoomButton(label = "+", enabled = zoom < PulseZoomMax, onClick = onZoomIn)
    }
}

@Composable
private fun PulseZoomButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(28.dp)
            .width(if (label.length > 1) 54.dp else 32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) ShiftPanelDeepColor else ShiftPanelDeepColor.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = if (enabled) ShiftTextPrimary else ShiftTextMuted,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) ShiftTextPrimary else ShiftTextMuted,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PulseTimelineHeader(
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
) {
    val tickTimes = pulseTickTimes(windowStart, windowEnd)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(PulseLeftColumnWidth))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .height(18.dp),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(chartWidth)
                    .height(18.dp),
            ) {
                tickTimes.forEach { tickTime ->
                    val fraction = pulseFraction(tickTime, windowStart, windowEnd)
                    Text(
                        text = formatPulseWindowTime(tickTime),
                        modifier = Modifier
                            .offset(x = (maxWidth * fraction) - PulseTickLabelHalfWidth)
                            .width(PulseTickLabelWidth),
                        style = MaterialTheme.typography.labelSmall,
                        color = ShiftTextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulseTimelineShiftRow(
    shift: PlannedStaffShift,
    attendance: WorktimeAttendanceSnapshot,
    now: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
    lastSeenEvent: LastSeenAuthEvent? = null,
) {
    val pulseStatus = plannedPulseStatus(shift = shift, attendance = attendance, now = now)
    val overlapsWindow = shift.overlaps(windowStart, windowEnd)
    val minimumBarWidthFraction = 0.035f
    val startFraction = if (overlapsWindow) {
        pulseFraction(if (shift.startsAt.isBefore(windowStart)) windowStart else shift.startsAt, windowStart, windowEnd)
    } else if (shift.endsAt.isBefore(windowStart) || shift.endsAt == windowStart) {
        0f
    } else {
        1f - minimumBarWidthFraction
    }
    val endFraction = if (overlapsWindow) {
        pulseFraction(if (shift.endsAt.isAfter(windowEnd)) windowEnd else shift.endsAt, windowStart, windowEnd)
    } else {
        (startFraction + minimumBarWidthFraction).coerceAtMost(1f)
    }
    val barWidthFraction = (endFraction - startFraction).coerceIn(minimumBarWidthFraction, 1f)
    val timeRange = plannedShiftTimeRange(shift)
    val nowFraction = pulseFraction(now, windowStart, windowEnd)
    val lastSeenTime = lastSeenEvent?.timestampMillis?.let { timestamp ->
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }
    val detailText = listOfNotNull(
        timeRange,
        lastSeenTime?.let { "Viimeksi nähty ${formatPulseWindowTime(it)}" },
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulseStatusGlyph(status = pulseStatus)
            Text(
                text = "♙",
                style = MaterialTheme.typography.titleMedium,
                color = ShiftTextSecondary,
                modifier = Modifier.width(18.dp),
                textAlign = TextAlign.Center,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shift.staffName.ifBlank { shift.staffId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShiftTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(chartWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                pulseTickTimes(windowStart, windowEnd).forEach { tickTime ->
                    val fraction = pulseFraction(tickTime, windowStart, windowEnd)
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * fraction)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                if (nowFraction in 0f..1f) {
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * nowFraction)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(ShiftGold.copy(alpha = 0.76f)),
                    )
                }
                lastSeenTime?.let { eventTime ->
                    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
                    if (markerFraction in 0f..1f) {
                        Box(
                            modifier = Modifier
                                .offset(x = maxWidth * markerFraction)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(ShiftCyan.copy(alpha = 0.48f)),
                        )
                        Box(
                            modifier = Modifier
                                .offset(x = maxWidth * markerFraction - 5.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ShiftCyan.copy(alpha = 0.58f))
                                .border(BorderStroke(1.dp, ShiftCyan.copy(alpha = 0.72f)), CircleShape),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * startFraction)
                        .width(maxWidth * barWidthFraction)
                        .height(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .pulseBarModifier(pulseStatus),
                )
            }
        }

    }
}

// Renders an unscheduled active-worktime row: a staff member with active worktime attendance
// but no planned shift on the selected day. Visual is distinct from planned-shift bars: a
// teal/cyan worktime chip that spans only the truth-backed clock-in->now range,
// clamped into the operational-day window. Never invents an end time.
@Composable
private fun PulseTimelinePresenceRow(
    entry: AttendanceEntry,
    now: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
    lastSeenEvent: LastSeenAuthEvent? = null,
) {
    val parsedStart = parseAttendanceStart(entry.startedAt)
    val durationDerivedStart = if (entry.durationMinutes > 0.0) {
        now.minusMinutes(entry.durationMinutes.toLong())
    } else {
        null
    }
    val rawStart = parsedStart ?: durationDerivedStart
    val effectiveStart = rawStart?.let {
        if (it.isBefore(windowStart)) windowStart else it
    }
    val effectiveEnd = if (now.isAfter(windowEnd)) windowEnd else now
    val hasTruthBackedRange = effectiveStart != null && effectiveEnd.isAfter(effectiveStart)
    val worktimeLabel = "Työaika käynnissä"
    val timeRangeText = if (hasTruthBackedRange && parsedStart != null) {
        "${formatPulseWindowTime(parsedStart)}-"
    } else {
        null
    }
    val tint = ShiftCyan
    val nowFraction = pulseFraction(now, windowStart, windowEnd)
    val lastSeenTime = lastSeenEvent?.timestampMillis?.let { timestamp ->
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }
    val detailText = listOfNotNull(
        timeRangeText?.let { "$worktimeLabel · $it" } ?: worktimeLabel,
        lastSeenTime?.let { "Viimeksi nähty ${formatPulseWindowTime(it)}" },
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f))
                    .border(BorderStroke(1.dp, tint.copy(alpha = 0.42f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "✦",
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                modifier = Modifier.width(18.dp),
                textAlign = TextAlign.Center,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.staffName.ifBlank { entry.staffId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShiftTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(chartWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                pulseTickTimes(windowStart, windowEnd).forEach { tickTime ->
                    val fraction = pulseFraction(tickTime, windowStart, windowEnd)
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * fraction)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                if (nowFraction in 0f..1f) {
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * nowFraction)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(ShiftGold.copy(alpha = 0.76f)),
                    )
                }
                lastSeenTime?.let { eventTime ->
                    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
                    if (markerFraction in 0f..1f) {
                        Box(
                            modifier = Modifier
                                .offset(x = maxWidth * markerFraction)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(ShiftCyan.copy(alpha = 0.48f)),
                        )
                        Box(
                            modifier = Modifier
                                .offset(x = maxWidth * markerFraction - 5.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ShiftCyan.copy(alpha = 0.58f))
                                .border(BorderStroke(1.dp, ShiftCyan.copy(alpha = 0.72f)), CircleShape),
                        )
                    }
                }
                if (hasTruthBackedRange) {
                    val startFraction = pulseFraction(effectiveStart!!, windowStart, windowEnd)
                    val endFraction = pulseFraction(effectiveEnd, windowStart, windowEnd)
                    val barWidthFraction = (endFraction - startFraction).coerceIn(0.015f, 1f)
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * startFraction)
                            .width(maxWidth * barWidthFraction)
                            .height(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(tint.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.70f)), RoundedCornerShape(5.dp)),
                    )
                } else if (nowFraction in 0f..1f) {
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * nowFraction - 8.dp)
                            .width(16.dp)
                            .height(16.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.55f))
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.70f)), CircleShape),
                    )
                }
            }
        }
    }
}

// Renders a "Viimeksi nähty" row: a factual authentication event for a staff member who
// is NOT in active worktime. Visual is a small vertical line + dot at the actual auth
// timestamp — never a horizontal duration bar. Calm neutral tint (subtle cyan / muted)
// so it cannot be misread as planned-shift green or late-shift red.
@Composable
private fun PulseTimelineLastSeenRow(
    event: LastSeenAuthEvent,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
) {
    val eventTime = Instant.ofEpochMilli(event.timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
    val tint = ShiftCyan
    val timeLabel = formatPulseWindowTime(eventTime)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.10f))
                    .border(BorderStroke(1.dp, tint.copy(alpha = 0.32f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "◌",
                style = MaterialTheme.typography.titleMedium,
                color = ShiftTextMuted,
                modifier = Modifier.width(18.dp),
                textAlign = TextAlign.Center,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.staffName.ifBlank { event.staffId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShiftTextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Viimeksi nähty $timeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(chartWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                pulseTickTimes(windowStart, windowEnd).forEach { tickTime ->
                    val fraction = pulseFraction(tickTime, windowStart, windowEnd)
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * fraction)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                if (markerFraction in 0f..1f) {
                    // Vertical line at the exact auth timestamp.
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * markerFraction)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(tint.copy(alpha = 0.55f)),
                    )
                    // Dot centered on the marker line — point-in-time only, no horizontal
                    // duration bar.
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * markerFraction - 5.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.55f))
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.70f)), CircleShape),
                    )
                }
            }
        }
    }
}

private fun parseAttendanceStart(raw: String?): LocalDateTime? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    value.toLongOrNull()?.let { epochMillis ->
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
        }.getOrNull()
    }
    runCatching { java.time.OffsetDateTime.parse(value).toLocalDateTime() }
        .getOrNull()
        ?.let { return it }
    runCatching { LocalDateTime.parse(value) }
        .getOrNull()
        ?.let { return it }
    return runCatching {
        Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }.getOrNull()
}

@Composable
private fun PulseStatusGlyph(status: PulseStatusVisual) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(status.tint.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, status.tint.copy(alpha = 0.42f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = status.marker,
            style = MaterialTheme.typography.labelSmall,
            color = status.tint,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.pulseBarModifier(status: PulseStatusVisual): Modifier {
    return when (status.kind) {
        PulseStatusKind.UPCOMING -> this
            .background(status.tint.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
            .border(BorderStroke(1.dp, status.tint.copy(alpha = 0.80f)), RoundedCornerShape(5.dp))
        PulseStatusKind.ABSENT -> this
            .background(status.tint.copy(alpha = 0.20f), RoundedCornerShape(5.dp))
            .border(BorderStroke(1.dp, status.tint.copy(alpha = 0.58f)), RoundedCornerShape(5.dp))
        else -> this
            .background(
                Brush.horizontalGradient(
                    colors = listOf(status.tint.copy(alpha = 0.72f), status.tint.copy(alpha = 0.95f)),
                ),
                RoundedCornerShape(5.dp),
            )
            .border(BorderStroke(1.dp, status.tint.copy(alpha = 0.38f)), RoundedCornerShape(5.dp))
    }
}


@Composable
private fun OwnPlannedShiftRow(shift: PlannedStaffShift) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor,
        border = BorderStroke(1.dp, ShiftBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ShiftUpcoming),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plannedShiftTimeRange(shift),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ShiftTextPrimary,
                )
                Text(
                    text = shift.role?.takeIf { it.isNotBlank() } ?: "Suunniteltu vuoro",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShiftDayBoundary(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(ShiftLineColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ShiftTextMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(ShiftLineColor),
        )
    }
}

@Composable
private fun JournalRow(event: JournalEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(event.tint.copy(alpha = 0.18f))
                .border(BorderStroke(1.dp, event.tint.copy(alpha = 0.32f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = event.marker,
                style = MaterialTheme.typography.labelMedium,
                color = event.tint,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = ShiftTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = event.detail,
                style = MaterialTheme.typography.bodySmall,
                color = ShiftTextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShiftKeyValueRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ShiftTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) ShiftGold else ShiftTextSecondary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShiftStatusBanner(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.25f)), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ShiftEmptyText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor,
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = ShiftTextMuted,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = ShiftTextMuted,
        )
    }
}

data class JournalNote(
    val text: String,
    val authorName: String,
    val timestampMillis: Long,
)

/**
 * Factual authentication / recognition event for a single staff member.
 *
 * Semantics: "this staff member authenticated / was recognized at this time" and nothing
 * more. It does NOT imply on-site, working, headcount, or duration. Last-seen markers in
 * Työvuoropulssi must render as a point-in-time dot, never as a presence bar, and must be
 * suppressed for staff that are currently in active worktime.
 */
data class LastSeenAuthEvent(
    val staffId: String,
    val staffName: String,
    val timestampMillis: Long,
)

private data class JournalEvent(
    val marker: String,
    val tint: Color,
    val title: String,
    val detail: String,
    val timestamp: String? = null,
)

private fun formatShiftStatus(status: String): String {
    return when (status.uppercase()) {
        "OPEN" -> "Avoin"
        "CLOSED" -> "Suljettu"
        else -> status
    }
}

private fun appendShiftMoneyDigit(current: String, digit: String): String {
    val s = current.replace('.', ',').trim()
    if (s.isBlank()) return digit
    return if (s.contains(',')) {
        val dec = s.substringAfter(',')
        if (dec.length >= 2) s else s + digit
    } else {
        if (s == "0") digit else s + digit
    }
}

private fun appendShiftMoneyDecimal(current: String): String {
    val s = current.replace('.', ',').trim()
    return if (s.contains(',')) s else "${s.ifBlank { "0" }},"
}

private fun removeShiftMoneyChar(current: String): String = current.dropLast(1)

private fun displayMoneyInput(value: String): String {
    return "${value.ifBlank { "0,00" }.replace('.', ',')} €"
}

private fun formatJournalDay(raw: String?): String {
    val value = raw?.trim().orEmpty()
    value.toLongOrNull()?.let { epochMillis ->
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(ShiftJournalDayFormatter)
        }.getOrDefault("Tänään")
    }
    if (value.length >= 10 && value[4] == '-' && value[7] == '-') {
        val year = value.substring(0, 4)
        val month = value.substring(5, 7)
        val day = value.substring(8, 10)
        return "$day.$month.$year"
    }
    return "Tänään"
}

private fun formatJournalTime(raw: String?): String {
    val value = raw?.trim().orEmpty()
    value.toLongOrNull()?.let { epochMillis ->
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(ShiftJournalTimeFormatter)
        }.getOrDefault("--:--")
    }
    val tIndex = value.indexOf('T')
    if (tIndex >= 0 && value.length >= tIndex + 6) {
        return value.substring(tIndex + 1, tIndex + 6)
    }
    val timeMatch = Regex("\\b\\d{1,2}:\\d{2}\\b").find(value)
    return timeMatch?.value ?: "--:--"
}

private fun formatScheduleDate(date: LocalDate): String = date.format(ShiftJournalDayFormatter)

private fun formatCompactShiftDate(date: LocalDate): String {
    val today = LocalDate.now()
    if (date == today) return "Tänään"
    val day = when (date.dayOfWeek.value) {
        1 -> "Ma"
        2 -> "Ti"
        3 -> "Ke"
        4 -> "To"
        5 -> "Pe"
        6 -> "La"
        else -> "Su"
    }
    return "$day ${date.dayOfMonth}.${date.monthValue}."
}

private fun formatPulseWindowTime(value: LocalDateTime): String = value.format(ShiftJournalTimeFormatter)

private fun LocalDateTime.roundUpToHour(): LocalDateTime {
    val roundedDown = truncatedTo(ChronoUnit.HOURS)
    return if (this == roundedDown) roundedDown else roundedDown.plusHours(1)
}

private fun plannedShiftTimeRange(shift: PlannedStaffShift): String {
    return "${formatPulseWindowTime(shift.startsAt)}-${formatPulseWindowTime(shift.endsAt)}"
}

private fun ShiftScheduleDay.hasPublishedScheduleTruth(): Boolean {
    return publicationStatus == ShiftSchedulePublicationStatus.PUBLISHED ||
        publicationStatus == ShiftSchedulePublicationStatus.CLOSED
}

private fun PlannedStaffShift.overlaps(windowStart: LocalDateTime, windowEnd: LocalDateTime): Boolean {
    return startsAt.isBefore(windowEnd) && endsAt.isAfter(windowStart)
}

private fun pulseFraction(value: LocalDateTime, windowStart: LocalDateTime, windowEnd: LocalDateTime): Float {
    val totalMillis = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(1L)
    val offsetMillis = Duration.between(windowStart, value).toMillis()
    return (offsetMillis.toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
}

private fun formatDuration(minutes: Double): String {
    val safeMinutes = minutes.coerceAtLeast(0.0)
    val hours = (safeMinutes / 60).toInt()
    val mins = (safeMinutes % 60).toInt()
    return if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
}

private enum class PulseStatusKind {
    PRESENT,
    UPCOMING,
    LATE,
    ABSENT,
    COMPLETED,
    UNKNOWN,
}

private data class PulseStatusVisual(
    val kind: PulseStatusKind,
    val label: String,
    val tint: Color,
    val marker: String,
)

private fun pulseStatusForAttendance(entry: AttendanceEntry): PulseStatusVisual {
    val normalized = entry.status.trim().lowercase(Locale.ROOT)
    val start = formatJournalTime(entry.startedAt).takeIf { it != "--:--" }
    return when (normalized) {
        "active", "present", "clocked_in", "paikalla" -> PulseStatusVisual(PulseStatusKind.PRESENT, "Työaika käynnissä", ShiftSuccess, "!")
        "scheduled", "upcoming", "incoming", "tulossa" -> PulseStatusVisual(
            PulseStatusKind.UPCOMING,
            start?.let { "Tulossa $it" } ?: "Tulossa",
            ShiftUpcoming,
            "◷",
        )
        "late", "myöhässä", "missing" -> PulseStatusVisual(PulseStatusKind.LATE, "Myöhässä", ShiftDanger, "!")
        "absent", "away", "off", "poissa", "no_show" -> PulseStatusVisual(PulseStatusKind.ABSENT, "Poissa", ShiftAbsent, "×")
        "completed", "done", "valmis" -> PulseStatusVisual(PulseStatusKind.COMPLETED, "Valmis", ShiftTextMuted, "✓")
        "on_break", "break", "tauolla" -> PulseStatusVisual(PulseStatusKind.PRESENT, "Tauolla", ShiftWarning, "Ⅱ")
        else -> PulseStatusVisual(PulseStatusKind.PRESENT, statusLabelForAttendance(entry), ShiftSuccess, "•")
    }
}

private fun plannedPulseStatus(
    shift: PlannedStaffShift,
    attendance: WorktimeAttendanceSnapshot,
    now: LocalDateTime,
): PulseStatusVisual {
    val normalized = shift.status?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized in setOf("absent", "away", "off", "poissa", "no_show", "cancelled", "canceled")) {
        return PulseStatusVisual(PulseStatusKind.ABSENT, "Poissa", ShiftAbsent, "×")
    }

    val isClockedIn = shift.staffId.isNotBlank() &&
        attendance.currentlyOnSite.any { entry -> entry.staffId == shift.staffId }
    val hasAmbiguousStaffIdentity = shift.staffId.isBlank() ||
        attendance.currentlyOnSite.any { entry ->
            entry.staffId.isNotBlank() &&
                entry.staffId != shift.staffId &&
                entry.staffName.equals(shift.staffName, ignoreCase = true)
        }
    val hasAttendanceTruth = attendance.currentlyOnSite.isNotEmpty() || attendance.clockedInToday.isNotEmpty()
    val shiftIsCurrent = !now.isBefore(shift.startsAt) && now.isBefore(shift.endsAt)

    return when {
        isClockedIn -> PulseStatusVisual(PulseStatusKind.PRESENT, "Työaika käynnissä", ShiftSuccess, "!")
        now.isBefore(shift.startsAt) -> PulseStatusVisual(PulseStatusKind.UPCOMING, "Tulossa", ShiftUpcoming, "▷")
        shiftIsCurrent && hasAttendanceTruth && !hasAmbiguousStaffIdentity -> {
            PulseStatusVisual(PulseStatusKind.LATE, "Myöhässä", ShiftDanger, "!")
        }
        shiftIsCurrent -> PulseStatusVisual(PulseStatusKind.UNKNOWN, "Työaika tuntematon", ShiftTextMuted, "?")
        else -> PulseStatusVisual(PulseStatusKind.COMPLETED, "Valmis", ShiftTextMuted, "✓")
    }
}

private fun statusLabelForAttendance(entry: AttendanceEntry): String {
    val normalized = entry.status.trim().lowercase(Locale.ROOT)
    return when (normalized) {
        "active", "present", "clocked_in", "paikalla" -> "Työaika käynnissä"
        "scheduled", "upcoming", "incoming", "tulossa" -> "Tulossa"
        "late", "myöhässä", "missing" -> "Myöhässä"
        "absent", "away", "off", "poissa", "no_show" -> "Poissa"
        "on_break", "break", "tauolla" -> "Tauolla"
        "completed", "done", "valmis" -> "Valmis"
        else -> entry.status
    }
}

private fun statusColorForAttendance(entry: AttendanceEntry): Color {
    val normalized = entry.status.trim().lowercase(Locale.ROOT)
    return when (normalized) {
        "active", "present", "clocked_in", "paikalla" -> ShiftSuccess
        "scheduled", "upcoming", "incoming", "tulossa" -> ShiftUpcoming
        "late", "myöhässä", "missing" -> ShiftDanger
        "absent", "away", "off", "poissa", "no_show" -> ShiftAbsent
        "on_break", "break", "tauolla" -> ShiftWarning
        "completed", "done", "valmis" -> ShiftTextMuted
        else -> ShiftCyan
    }
}
