package com.airos.pos.feature.shift

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.airos.pos.core.model.CashDrawerStatus
import com.airos.pos.core.model.CashExpectedState
import com.airos.pos.core.model.CashLedgerState
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.ShiftScheduleDay
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import com.airos.pos.core.model.ShiftStatus
import com.airos.pos.core.model.StaffUiLanguage
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
private val ShiftPanelColor = Color(0xFF131E29)
private val ShiftPanelRaisedColor = Color(0xFF182633)
private val ShiftPanelDeepColor = Color(0xFF0D151E)
private val ShiftBorderColor = Color(0x14FFFFFF)
private val ShiftBorderWarmColor = Color(0x38D6A557)
private val ShiftTextPrimary = Color(0xFFFBFEFF)
private val ShiftTextSecondary = Color(0xFFE8F0F6)
private val ShiftTextMuted = Color(0xFFC0CCD6)
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

private fun staffTextKeyboardOptions(
    language: StaffUiLanguage,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions {
    return KeyboardOptions(
        capitalization = capitalization,
        keyboardType = keyboardType,
        imeAction = imeAction,
        hintLocales = language.keyboardLocaleList(),
    )
}

private fun StaffUiLanguage.keyboardLocaleList(): ComposeLocaleList {
    return ComposeLocaleList(
        ComposeLocale(
            when (this) {
                StaffUiLanguage.FI -> "fi-FI"
                StaffUiLanguage.EN -> "en-US"
            },
        ),
    )
}

@Composable
private fun ShiftRhombusMark(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = ShiftGold,
    alpha: Float = 1f,
) {
    Box(
        modifier = modifier
            .size(size)
            .rotate(45f)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = alpha)),
    )
}

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
    textInputLanguage: StaffUiLanguage = StaffUiLanguage.FI,
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
    onSystemNoteAdded: (String) -> Boolean = { false },
    onNoteUpdated: (JournalNote, String) -> Boolean = { _, _ -> false },
    onNoteDeleted: (JournalNote) -> Boolean = { false },
    lastSeenEvents: List<LastSeenAuthEvent> = emptyList(),
    onPulseStaffSelected: (String, String) -> Unit = { _, _ -> },
    receiptLogoPainter: Painter? = null,
    receiptLogoBitmap: Bitmap? = null,
    onPrintShiftReceiptBitmap: suspend (Bitmap) -> PosResult<Unit> = {
        PosResult.Failure("Kuittitulostinta ei ole kytketty tähän näkymään.")
    },
) {
    var cashWorkspaceMode by remember(state.currentShift?.id) { mutableStateOf<CashWorkspaceMode?>(null) }
    val activeStaffName = currentStaffName?.takeIf { it.isNotBlank() } ?: "Tuntematon"
    var pendingCashOpenJournalText by remember { mutableStateOf<String?>(null) }
    var pendingCashCloseShiftId by remember { mutableStateOf<String?>(null) }
    var pendingCashCloseJournalText by remember { mutableStateOf<String?>(null) }
    var selectedPulseDate by remember { mutableStateOf<LocalDate?>(null) }
    var showPrintPreview by remember { mutableStateOf(false) }

    LaunchedEffect(state.journalEventId) {
        val text = state.journalEventText
        if (state.journalEventId > 0 && !text.isNullOrBlank()) {
            if (onSystemNoteAdded(text)) {
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

    val baseTypography = MaterialTheme.typography
    val shiftTypography = remember(baseTypography) {
        baseTypography.copy(
            bodySmall = baseTypography.bodySmall.copy(fontSize = (baseTypography.bodySmall.fontSize.value + 1.5f).sp),
            bodyMedium = baseTypography.bodyMedium.copy(fontSize = (baseTypography.bodyMedium.fontSize.value + 1.5f).sp),
            labelSmall = baseTypography.labelSmall.copy(fontSize = (baseTypography.labelSmall.fontSize.value + 1.5f).sp),
            labelMedium = baseTypography.labelMedium.copy(fontSize = (baseTypography.labelMedium.fontSize.value + 1.5f).sp),
            labelLarge = baseTypography.labelLarge.copy(fontSize = (baseTypography.labelLarge.fontSize.value + 1.5f).sp),
            titleSmall = baseTypography.titleSmall.copy(fontSize = (baseTypography.titleSmall.fontSize.value + 1f).sp),
            titleMedium = baseTypography.titleMedium.copy(fontSize = (baseTypography.titleMedium.fontSize.value + 1f).sp),
            titleLarge = baseTypography.titleLarge.copy(fontSize = (baseTypography.titleLarge.fontSize.value + 1f).sp),
        )
    }

    MaterialTheme(typography = shiftTypography) {
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
                        .weight(0.86f),
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
                        onNoteUpdated = onNoteUpdated,
                        onNoteDeleted = onNoteDeleted,
                        textInputLanguage = textInputLanguage,
                        modifier = Modifier
                            .weight(0.80f)
                            .fillMaxHeight(),
                    )

                    ShiftSchedulePulseCard(
                        schedule = schedule,
                        attendance = attendance,
                        loading = scheduleLoading,
                        message = scheduleMessage,
                        selectedScheduleDate = selectedPulseDate,
                        lastSeenEvents = lastSeenEvents,
                        onStaffSelected = onPulseStaffSelected,
                        modifier = Modifier
                            .weight(1.25f)
                            .fillMaxHeight(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.82f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorktimeSummaryCard(
                        currentStaffId = currentStaffId,
                        isClockedIn = isClockedIn,
                        myAttendanceEntry = myAttendanceEntry,
                        attendanceStateLoading = attendanceStateLoading,
                        ownSchedule = ownSchedule,
                        ownScheduleLoading = ownScheduleLoading,
                        ownScheduleMessage = ownScheduleMessage,
                        selectedScheduleDate = selectedPulseDate,
                        onScheduleDateSelected = { selectedPulseDate = it },
                        modifier = Modifier
                            .weight(1.65f)
                            .fillMaxHeight(),
                    )

                    CashShiftCard(
                        state = state,
                        currentStaffId = currentStaffId,
                        onPrintPreview = { showPrintPreview = true },
                        onCashWorkspaceModeChanged = { cashWorkspaceMode = it },
                        onOpenShift = ::requestOpenRestaurant,
                        isClockedIn = isClockedIn,
                        attendanceBusy = attendanceBusy,
                        attendanceStateLoading = attendanceStateLoading,
                        onClockIn = onClockIn,
                        onClockOut = onClockOut,
                        modifier = Modifier
                            .weight(0.80f)
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

            if (showPrintPreview) {
                ShiftPrintPreviewOverlay(
                    schedule = ownSchedule,
                    currentStaffId = currentStaffId,
                    currentStaffName = currentStaffName,
                    loading = ownScheduleLoading,
                    message = ownScheduleMessage,
                    anchorDate = selectedPulseDate ?: LocalDate.now(),
                    onDismiss = { showPrintPreview = false },
                    receiptLogoPainter = receiptLogoPainter,
                    receiptLogoBitmap = receiptLogoBitmap,
                    onPrintReceiptBitmap = onPrintShiftReceiptBitmap,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(3f),
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
                ShiftRhombusMark(size = 12.dp)
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
private fun ShiftActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.84f),
            contentColor = Color(0xFF071109),
            disabledContainerColor = ShiftPanelRaisedColor,
            disabledContentColor = ShiftTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CashShiftCard(
    state: ShiftUiState,
    currentStaffId: String?,
    onPrintPreview: () -> Unit,
    onCashWorkspaceModeChanged: (CashWorkspaceMode) -> Unit,
    onOpenShift: (String) -> Unit,
    isClockedIn: Boolean,
    attendanceBusy: Boolean,
    attendanceStateLoading: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentShift = state.currentShift
    val isRestaurantOpen = currentShift?.status == ShiftStatus.OPEN
    val ledger = state.cashLedgerState
    val isCashLedgerOpen = ledger.drawer.status == CashDrawerStatus.OPEN
    val feedbackContext = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(feedbackContext, message, Toast.LENGTH_LONG).show()
        }
    }

    ShiftCard(
        title = "Vuoron tilanne",
        icon = "▣",
        modifier = modifier,
        statusLabel = if (isRestaurantOpen) "Ravintola avoin" else "Ravintola suljettu",
        statusColor = if (isRestaurantOpen) ShiftSuccess else ShiftTextMuted,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Toiminnot",
                style = MaterialTheme.typography.labelLarge,
                color = ShiftTextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ShiftActionButton(
                        text = "TULOSTA VUOROT",
                        onClick = onPrintPreview,
                        enabled = true,
                        color = ShiftCyan,
                        modifier = Modifier.weight(1f),
                    )
                    ShiftActionButton(
                        text = if (isClockedIn) "LOPETA TYÖAIKA" else "ALOITA TYÖAIKA",
                        onClick = if (isClockedIn) onClockOut else onClockIn,
                        enabled = !attendanceBusy && !attendanceStateLoading && currentStaffId != null,
                        color = if (isClockedIn) ShiftGold else ShiftSuccess,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ShiftActionButton(
                        text = "LASKE KASSA",
                        onClick = { onCashWorkspaceModeChanged(CashWorkspaceMode.COUNT) },
                        enabled = !state.busy && currentStaffId != null,
                        color = ShiftCyan,
                        modifier = Modifier.weight(1f),
                    )
                    ShiftActionButton(
                        text = if (isRestaurantOpen) "SULJE RAVINTOLA" else "AVAA RAVINTOLA",
                        onClick = {
                            if (isRestaurantOpen) {
                                onCashWorkspaceModeChanged(CashWorkspaceMode.CLOSE)
                            } else {
                                currentStaffId?.let(onOpenShift)
                            }
                        },
                        enabled = !state.busy && currentStaffId != null,
                        color = if (isRestaurantOpen) ShiftGold else ShiftSuccess,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = ShiftInnerShape,
                color = ShiftPanelDeepColor.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, ShiftBorderColor),
                contentColor = ShiftTextPrimary,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Ravintola ja kassakirja",
                        style = MaterialTheme.typography.labelLarge,
                        color = ShiftTextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    ShiftKeyValueRow("Ravintola", if (isRestaurantOpen) "Avoin" else "Suljettu")
                    ShiftKeyValueRow("Kassakirja", cashDrawerStatusText(ledger))
                    ShiftKeyValueRow("Ravintola avattu", restaurantOpenedAtText(currentShift))
                    ShiftKeyValueRow("Pohjakassa", restaurantOpeningFloatText(currentShift))
                    ShiftKeyValueRow("Laskettu kassa", latestCashCountText(ledger))
                    ShiftKeyValueRow("Rahaa pitäisi olla nyt", cashExpectedText(ledger), highlight = true)
                    if (isRestaurantOpen != isCashLedgerOpen) {
                        ShiftStatusBanner(
                            text = "Ravintolan tila ja kassakirjan tila eivät täsmää.",
                            tint = ShiftWarning,
                        )
                    }
                    if (ledger.expectedState == CashExpectedState.MISSING_TRUTH) {
                        ShiftStatusBanner(
                            text = ledger.warningMessage ?: "Kassassa pitäisi olla ei ole laskettavissa. Laske kassa.",
                            tint = ShiftDanger,
                        )
                    }
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

private fun cashDrawerStatusText(ledger: CashLedgerState): String {
    return when (ledger.drawer.status) {
        CashDrawerStatus.OPEN -> "Avoin"
        CashDrawerStatus.CLOSED -> "Suljettu"
    }
}

private fun restaurantOpenedAtText(shift: PosShift?): String {
    return when (shift?.status) {
        ShiftStatus.OPEN -> formatJournalTime(shift.openedAtEpochMillis.toString()).takeIf { it != "--:--" }
            ?: "Ei saatavilla"
        ShiftStatus.CLOSED -> "Suljettu"
        null -> "Ei avointa kassapäivää"
    }
}

private fun restaurantOpeningFloatText(shift: PosShift?): String {
    return if (shift?.status == ShiftStatus.OPEN) {
        CentsFormatter.format(shift.openingFloatCents)
    } else {
        "Ei avointa kassapäivää"
    }
}

@Composable
private fun WorktimeSummaryCard(
    currentStaffId: String?,
    isClockedIn: Boolean,
    myAttendanceEntry: AttendanceEntry?,
    attendanceStateLoading: Boolean,
    ownSchedule: ShiftScheduleSnapshot?,
    ownScheduleLoading: Boolean,
    ownScheduleMessage: String?,
    selectedScheduleDate: LocalDate?,
    onScheduleDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                shape = ShiftInnerShape,
                color = ShiftPanelDeepColor.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, ShiftBorderColor.copy(alpha = 0.14f)),
                contentColor = ShiftTextPrimary,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Alkoi",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShiftTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isClockedIn) startedAt ?: "Ei saatavilla" else "Ei käynnissä",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShiftTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.weight(0.70f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Kesto",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShiftTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isClockedIn) durationText ?: "Ei saatavilla" else "--",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isClockedIn) ShiftGold else ShiftTextSecondary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            OwnShiftsCompactPanel(
                schedule = ownSchedule,
                currentStaffId = currentStaffId,
                loading = ownScheduleLoading,
                message = ownScheduleMessage,
                selectedScheduleDate = selectedScheduleDate,
                onScheduleDateSelected = onScheduleDateSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun OwnShiftsCompactPanel(
    schedule: ShiftScheduleSnapshot?,
    currentStaffId: String?,
    loading: Boolean,
    message: String?,
    selectedScheduleDate: LocalDate?,
    onScheduleDateSelected: (LocalDate) -> Unit,
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
                schedule.days.none { it.hasPublishedScheduleTruth() } -> ShiftCompactEmptyText("Ei julkaistuja vuoropäiviä.")
                else -> {
                    val today = LocalDate.now()
                    val weeks = remember(schedule, currentStaffId, today) {
                        ownShiftCalendarWeeks(
                            schedule = schedule,
                            currentStaffId = currentStaffId,
                            today = today,
                        )
                    }
                    OwnShiftCalendarGrid(
                        weeks = weeks,
                        selectedDate = selectedScheduleDate,
                        onDateSelected = onScheduleDateSelected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
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

private data class OwnShiftCalendarCell(
    val date: LocalDate,
    val shifts: List<PlannedStaffShift>,
    val isToday: Boolean,
)

private val OwnShiftCalendarWeekdayLabels = listOf("Ma", "Ti", "Ke", "To", "Pe", "La", "Su")

private fun ownShiftCalendarWeeks(
    schedule: ShiftScheduleSnapshot,
    currentStaffId: String?,
    today: LocalDate,
): List<List<OwnShiftCalendarCell>> {
    val currentWeekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val publishedDays = schedule.days.filter { it.hasPublishedScheduleTruth() }
    val lastPublishedDate = publishedDays.map { it.date }.maxOrNull()
    val minimumEndDate = currentWeekStart.plusDays(13)
    val calendarEndDate = if (lastPublishedDate != null && lastPublishedDate.isAfter(minimumEndDate)) {
        lastPublishedDate
    } else {
        minimumEndDate
    }
    val weekCount = ((ChronoUnit.DAYS.between(currentWeekStart, calendarEndDate).coerceAtLeast(13L) / 7L) + 1L)
        .toInt()
        .coerceAtLeast(2)
    val shiftsByDate = publishedDays
        .flatMap { day ->
            day.plannedShifts
                .filter { shift -> shift.staffId == currentStaffId }
                .map { shift -> day.date to shift }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, shifts) -> shifts.sortedBy { it.startsAt } }

    return (0 until weekCount).map { weekIndex ->
        (0..6).map { dayIndex ->
            val date = currentWeekStart.plusDays((weekIndex * 7 + dayIndex).toLong())
            OwnShiftCalendarCell(
                date = date,
                shifts = shiftsByDate[date].orEmpty(),
                isToday = date == today,
            )
        }
    }
}

@Composable
private fun OwnShiftCalendarGrid(
    weeks: List<List<OwnShiftCalendarCell>>,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OwnShiftCalendarWeekdayLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = ShiftCyan,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    week.forEach { cell ->
                        OwnShiftCalendarDayCell(
                            cell = cell,
                            selected = selectedDate == cell.date,
                            onSelected = { onDateSelected(cell.date) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnShiftCalendarDayCell(
    cell: OwnShiftCalendarCell,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        selected -> ShiftGold.copy(alpha = 0.72f)
        cell.isToday -> ShiftCyan.copy(alpha = 0.40f)
        else -> ShiftBorderColor
    }
    val backgroundColor = when {
        selected -> ShiftGold.copy(alpha = 0.16f)
        cell.isToday -> ShiftCyanSoft.copy(alpha = 0.20f)
        else -> ShiftPanelColor.copy(alpha = 0.52f)
    }
    val dateColor = when {
        selected -> ShiftTextSecondary
        cell.isToday -> ShiftTextSecondary
        else -> ShiftTextMuted
    }
    Surface(
        modifier = modifier.clickable(onClick = onSelected),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
                Text(
                    text = "${cell.date.dayOfMonth}.${cell.date.monthValue}.",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = dateColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (cell.shifts.isEmpty()) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = ShiftTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                cell.shifts.take(2).forEach { shift ->
                    Text(
                        text = plannedShiftCalendarTimeRange(shift),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                        color = ShiftGold,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (cell.shifts.size > 2) {
                    Text(
                        text = "+${cell.shifts.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShiftWarning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    selectedScheduleDate: LocalDate?,
    lastSeenEvents: List<LastSeenAuthEvent> = emptyList(),
    onStaffSelected: (String, String) -> Unit = { _, _ -> },
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
        val allDays = schedule?.days.orEmpty()
        val publishedDays = allDays.filter { it.hasPublishedScheduleTruth() }
        val selectedDay = if (selectedScheduleDate != null) {
            allDays.firstOrNull { it.date == selectedScheduleDate }
        } else {
            publishedDays
                .firstOrNull { day ->
                    val od = day.operationalDay
                    od.truthAvailable && !od.isClosed &&
                        od.opensAt != null && od.closesAt != null &&
                        !now.isBefore(od.opensAt) && !now.isAfter(od.closesAt)
                }
                ?: publishedDays.firstOrNull { it.plannedShifts.isNotEmpty() }
                ?: publishedDays.firstOrNull()
        }
        val operationalDay = selectedDay?.operationalDay
        val operationalWindowStart = operationalDay?.opensAt
        val operationalWindowEnd = operationalDay?.closesAt

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
                selectedScheduleDate != null && selectedDay == null ->
                    ShiftEmptyText("Valitulla päivällä ei ole työvuoropäivän tietoa.")
                selectedScheduleDate != null && selectedDay != null && !selectedDay.hasPublishedScheduleTruth() ->
                    ShiftEmptyText("Valitulla päivällä ei ole julkaistua työvuorosuunnitelmaa.")
                publishedDays.isEmpty() -> ShiftEmptyText("Aikavälillä ei ole julkaistua työvuorosuunnitelmaa.")
                selectedDay == null -> ShiftEmptyText("Julkaistua työvuoropäivää ei ole valittavissa.")
                operationalDay == null || !operationalDay.truthAvailable ->
                    ShiftEmptyText("Ravintolan aukioloaikatieto ei ole saatavilla.")
                operationalDay.isClosed ->
                    ShiftEmptyText("Ravintola on suljettu valitulle päivälle.")
                operationalWindowStart == null || operationalWindowEnd == null ||
                    !operationalWindowEnd.isAfter(operationalWindowStart) ->
                    ShiftEmptyText("Ravintolan aukioloaikatieto on virheellinen.")
                else -> {
                    val windowStart = operationalWindowStart
                    val windowEnd = operationalWindowEnd
                    val visibleShifts = selectedDay.plannedShifts
                        .distinctBy { it.id }
                        .filter { it.overlaps(windowStart, windowEnd) }
                        .sortedWith(compareBy<PlannedStaffShift> { it.startsAt }.thenBy { it.staffName })
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
                        val nowFraction = pulseFraction(now, windowStart, windowEnd)
                        val showNowLine = isInsidePulseWindow(now, windowStart, windowEnd)
                        val horizontalScrollState = rememberScrollState()
                        val density = LocalDensity.current
                        LaunchedEffect(chartWidth, windowStart, windowEnd, now, horizontalScrollState.maxValue) {
                            val maxScroll = horizontalScrollState.maxValue
                            if (maxScroll <= 0 || now.isBefore(windowStart) || now.isAfter(windowEnd)) {
                                return@LaunchedEffect
                            }
                            val chartWidthPx = with(density) { chartWidth.toPx() }
                            val edgePaddingPx = with(density) { PulseChartEdgePadding.toPx() }
                            val dataWidthPx = (chartWidthPx - edgePaddingPx * 2f).coerceAtLeast(1f)
                            val viewportWidthPx = (chartWidthPx - maxScroll).coerceAtLeast(0f)
                            val nowX = edgePaddingPx + dataWidthPx * pulseFraction(now, windowStart, windowEnd)
                            val targetScroll = (nowX - viewportWidthPx / 2f)
                                .toInt()
                                .coerceIn(0, maxScroll)
                            horizontalScrollState.animateScrollTo(targetScroll)
                        }
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
                            val windowLabel = "${formatPulseWindowTime(windowStart)}-${formatPulseWindowTime(windowEnd)}"
                            val operationalSource = operationalDay.source?.takeIf { it.isNotBlank() }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatPulseScheduleDate(selectedDay.date),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ShiftTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = ShiftPanelDeepColor.copy(alpha = 0.54f),
                                    border = BorderStroke(1.dp, ShiftBorderColor.copy(alpha = 0.24f)),
                                    contentColor = ShiftTextMuted,
                                ) {
                                    Text(
                                        text = windowLabel,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ShiftTextMuted,
                                        maxLines = 1,
                                    )
                                }
                                operationalSource?.let { source ->
                                    Text(
                                        text = source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ShiftTextMuted.copy(alpha = 0.78f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ShiftRhombusMark(size = 10.dp, alpha = 0.70f)
                                Text(
                                    text = "viimeksi nähty",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ShiftTextMuted.copy(alpha = 0.82f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(pinchZoomModifier),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            PulseTimelineHeader(
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                chartWidth = chartWidth,
                                horizontalScrollState = horizontalScrollState,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
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
                                    onStaffSelected = onStaffSelected,
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
                                    onStaffSelected = onStaffSelected,
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
                            // point-in-time rhombus — never a duration bar. We suppress events
                            // for staff already shown as on-site to avoid duplicate or
                            // confusing display, keep only the latest event per staff,
                            // and clamp to the visible operational-day window without
                            // stretching it.
                            visibleLastSeen.forEachIndexed { index, event ->
                                PulseTimelineLastSeenRow(
                                    event = event,
                                    now = now,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    chartWidth = chartWidth,
                                    horizontalScrollState = horizontalScrollState,
                                    onStaffSelected = onStaffSelected,
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
                                if (showNowLine) {
                                    PulseCurrentTimeOverlay(
                                        chartWidth = chartWidth,
                                        horizontalScrollState = horizontalScrollState,
                                        fraction = nowFraction,
                                        modifier = Modifier.matchParentSize(),
                                    )
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
    onNoteUpdated: (JournalNote, String) -> Boolean = { _, _ -> false },
    onNoteDeleted: (JournalNote) -> Boolean = { false },
    textInputLanguage: StaffUiLanguage = StaffUiLanguage.FI,
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
                        note = note,
                        editable = note.isEditableJournalNote(),
                    ),
                )
            }
        }

        var noteInput by remember { mutableStateOf("") }
        var editingNote by remember { mutableStateOf<JournalNote?>(null) }
        var selectedNote by remember { mutableStateOf<JournalNote?>(null) }
        var confirmDeleteNote by remember { mutableStateOf<JournalNote?>(null) }
        val journalScrollState = rememberScrollState()
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        fun clearJournalEditor() {
            noteInput = ""
            editingNote = null
            selectedNote = null
            confirmDeleteNote = null
            keyboardController?.hide()
            focusManager.clearFocus()
        }
        fun submitNoteInput(rawText: String = noteInput): Boolean {
            val trimmed = rawText.trim()
            if (trimmed.isBlank()) return false
            val edited = editingNote
            return if (edited != null) {
                val saved = onNoteUpdated(edited, trimmed)
                if (saved) clearJournalEditor()
                saved
            } else {
                // App shell owns the real journal write path. Treat the Add action as
                // dispatched after calling it so the input does not stay visually stuck if
                // the host records the note through a side-effect/state update path.
                onNoteAdded(trimmed)
                clearJournalEditor()
                true
            }
        }

        LaunchedEffect(events.size) {
            if (events.isEmpty()) {
                journalScrollState.scrollTo(0)
            } else {
                delay(40)
                journalScrollState.scrollTo(journalScrollState.maxValue)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .background(ShiftPanelDeepColor.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, ShiftBorderColor.copy(alpha = 0.20f)), RoundedCornerShape(14.dp))
                    .padding(10.dp)
                    .verticalScroll(journalScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (events.isEmpty()) {
                    Text(
                        text = "Ei työvuoromerkintöjä.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShiftTextMuted,
                    )
                } else {
                    var lastDay: String? = null
                    events.forEach { event ->
                        val day = formatJournalDay(event.timestamp)
                        if (day != lastDay) {
                            ShiftDayBoundary(label = day)
                            lastDay = day
                        }
                        val note = event.note
                        JournalRow(
                            event = event,
                            selected = note != null && selectedNote?.sameJournalIdentity(note) == true,
                            confirmingDelete = note != null && confirmDeleteNote?.sameJournalIdentity(note) == true,
                            onSelected = {
                                if (event.editable && note != null) {
                                    selectedNote = if (selectedNote?.sameJournalIdentity(note) == true) null else note
                                    confirmDeleteNote = null
                                }
                            },
                            onEdit = {
                                if (event.editable && note != null) {
                                    editingNote = note
                                    noteInput = note.text
                                    selectedNote = null
                                    confirmDeleteNote = null
                                }
                            },
                            onDeleteRequested = {
                                if (event.editable && note != null) {
                                    confirmDeleteNote = note
                                    selectedNote = note
                                }
                            },
                            onDeleteConfirmed = {
                                if (event.editable && note != null && onNoteDeleted(note)) {
                                    if (editingNote?.sameJournalIdentity(note) == true) {
                                        editingNote = null
                                        noteInput = ""
                                    }
                                    selectedNote = null
                                    confirmDeleteNote = null
                                }
                            },
                            onDeleteCancelled = { confirmDeleteNote = null },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .background(ShiftPanelDeepColor, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, ShiftBorderColor), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .heightIn(min = 38.dp, max = 86.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = ShiftPanelRaisedColor.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, ShiftBorderColor.copy(alpha = 0.64f)),
                    contentColor = ShiftTextPrimary,
                ) {
                    BasicTextField(
                        value = noteInput,
                        onValueChange = { value ->
                            if (value.any { it == '\n' || it == '\r' }) {
                                val cleaned = value.replace("\r", "").replace("\n", "")
                                noteInput = cleaned
                                submitNoteInput(cleaned)
                            } else {
                                noteInput = value
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                    submitNoteInput()
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ShiftTextPrimary),
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = staffTextKeyboardOptions(
                            language = textInputLanguage,
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submitNoteInput() }),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (noteInput.isBlank()) {
                                    Text(
                                        text = if (editingNote != null) "Muokkaa merkintää…" else "Kirjoita muistiinpano…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ShiftTextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                Button(
                    onClick = { submitNoteInput() },
                    enabled = noteInput.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .widthIn(min = 92.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ShiftCyan.copy(alpha = 0.86f),
                        contentColor = Color(0xFF071109),
                        disabledContainerColor = ShiftPanelRaisedColor,
                        disabledContentColor = ShiftTextMuted,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (editingNote != null) "Tallenna" else "Lisää",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
private val PulseLeftColumnWidth = 224.dp
private val PulseTimelineGap = 10.dp
private val PulseTickLabelWidth = 48.dp
private val PulseTickLabelHalfWidth = 24.dp
private val PulseChartEdgePadding = PulseTickLabelHalfWidth + 6.dp

private fun pulseChartWidth(windowStart: LocalDateTime, windowEnd: LocalDateTime, zoom: Float): Dp {
    val hours = Duration.between(windowStart, windowEnd).toMinutes().coerceAtLeast(60L) / 60f
    val chartWidth = PulseTimelineWidthPerHour * hours
    val baseChartWidth = if (chartWidth > PulseTimelineMinimumChartWidth) chartWidth else PulseTimelineMinimumChartWidth
    return (baseChartWidth * zoom.coerceIn(PulseZoomMin, PulseZoomMax)) + (PulseChartEdgePadding * 2)
}

private fun pulseTickTimes(windowStart: LocalDateTime, windowEnd: LocalDateTime): List<LocalDateTime> {
    val ticks = mutableListOf<LocalDateTime>()
    ticks += windowStart
    var cursor = windowStart.truncatedTo(ChronoUnit.HOURS)
    if (!cursor.isAfter(windowStart)) {
        cursor = cursor.plusHours(1)
    }
    while (cursor.isBefore(windowEnd)) {
        ticks += cursor
        cursor = cursor.plusHours(1)
    }
    ticks += windowEnd
    return ticks.distinct()
}

private fun pulseTimelineEdgePadding(maxWidth: Dp): Dp {
    return if (maxWidth > PulseChartEdgePadding * 2) PulseChartEdgePadding else 0.dp
}

private fun pulseTimelineDataWidth(maxWidth: Dp): Dp {
    val edgePadding = pulseTimelineEdgePadding(maxWidth)
    return maxWidth - (edgePadding * 2)
}

private fun pulseTimelineX(maxWidth: Dp, fraction: Float): Dp {
    val edgePadding = pulseTimelineEdgePadding(maxWidth)
    return edgePadding + (pulseTimelineDataWidth(maxWidth) * fraction.coerceIn(0f, 1f))
}

private fun pulseTimelineSpan(maxWidth: Dp, fraction: Float): Dp {
    return pulseTimelineDataWidth(maxWidth) * fraction.coerceIn(0f, 1f)
}

private fun isInsidePulseWindow(value: LocalDateTime, windowStart: LocalDateTime, windowEnd: LocalDateTime): Boolean {
    return !value.isBefore(windowStart) && !value.isAfter(windowEnd)
}

@Composable
private fun PulseCurrentTimeOverlay(
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val lineX = with(density) {
        val chartX = pulseTimelineX(chartWidth, fraction).toPx() - horizontalScrollState.value
        PulseLeftColumnWidth.toPx() + PulseTimelineGap.toPx() + chartX
    }
    Box(
        modifier = modifier
            .zIndex(20f),
    ) {
        Box(
            modifier = Modifier
                .offset(x = with(density) { lineX.toDp() } - 2.dp)
                .width(4.dp)
                .fillMaxHeight()
                .background(ShiftGold),
        )
    }
}

@Composable
private fun PulseLastSeenRhombusMarker(maxWidth: Dp, fraction: Float) {
    Box(
        modifier = Modifier
            .offset(x = pulseTimelineX(maxWidth, fraction) - 10.dp)
            .size(20.dp)
            .zIndex(1.4f),
        contentAlignment = Alignment.Center,
    ) {
        ShiftRhombusMark(size = 16.dp, alpha = 0.78f)
    }
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
    onStaffSelected: (String, String) -> Unit = { _, _ -> },
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
                .padding(start = PulseTimelineGap)
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
                            .offset(x = pulseTimelineX(maxWidth, fraction) - PulseTickLabelHalfWidth)
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
    onStaffSelected: (String, String) -> Unit = { _, _ -> },
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
    val lastSeenTime = lastSeenEvent?.timestampMillis?.let { timestamp ->
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clickable(enabled = shift.staffId.isNotBlank() || shift.staffName.isNotBlank()) {
                onStaffSelected(shift.staffId, shift.staffName)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(start = PulseTimelineGap)
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
                            .offset(x = pulseTimelineX(maxWidth, fraction))
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                lastSeenTime?.let { eventTime ->
                    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
                    if (markerFraction in 0f..1f) {
                        PulseLastSeenRhombusMarker(maxWidth = maxWidth, fraction = markerFraction)
                    }
                }
                Box(
                    modifier = Modifier
                        .offset(x = pulseTimelineX(maxWidth, startFraction))
                        .width(pulseTimelineSpan(maxWidth, barWidthFraction))
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
    onStaffSelected: (String, String) -> Unit = { _, _ -> },
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
    val tint = ShiftCyan
    val lastSeenTime = lastSeenEvent?.timestampMillis?.let { timestamp ->
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clickable(enabled = entry.staffId.isNotBlank() || entry.staffName.isNotBlank()) {
                onStaffSelected(entry.staffId, entry.staffName)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f))
                    .border(BorderStroke(1.dp, tint.copy(alpha = 0.42f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "✦",
                style = MaterialTheme.typography.titleSmall,
                color = tint,
                modifier = Modifier.width(15.dp),
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
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(start = PulseTimelineGap)
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
                            .offset(x = pulseTimelineX(maxWidth, fraction))
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                lastSeenTime?.let { eventTime ->
                    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
                    if (markerFraction in 0f..1f) {
                        PulseLastSeenRhombusMarker(maxWidth = maxWidth, fraction = markerFraction)
                    }
                }
                if (hasTruthBackedRange) {
                    val startFraction = pulseFraction(effectiveStart!!, windowStart, windowEnd)
                    val endFraction = pulseFraction(effectiveEnd, windowStart, windowEnd)
                    val barWidthFraction = (endFraction - startFraction).coerceIn(0.015f, 1f)
                    Box(
                        modifier = Modifier
                            .offset(x = pulseTimelineX(maxWidth, startFraction))
                            .width(pulseTimelineSpan(maxWidth, barWidthFraction))
                            .height(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(tint.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.70f)), RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
    }
}

// Renders a "Viimeksi nähty" row: a factual authentication event for a staff member who
// is NOT in active worktime. Visual is a small yellow rhombus at the actual auth
// timestamp — never a horizontal duration bar or round presence dot.
@Composable
private fun PulseTimelineLastSeenRow(
    event: LastSeenAuthEvent,
    now: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    chartWidth: Dp,
    horizontalScrollState: ScrollState,
    onStaffSelected: (String, String) -> Unit = { _, _ -> },
) {
    val eventTime = Instant.ofEpochMilli(event.timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    val markerFraction = pulseFraction(eventTime, windowStart, windowEnd)
    val tint = ShiftCyan

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clickable(enabled = event.staffId.isNotBlank() || event.staffName.isNotBlank()) {
                onStaffSelected(event.staffId, event.staffName)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(PulseLeftColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(17.dp),
                contentAlignment = Alignment.Center,
            ) {
                ShiftRhombusMark(size = 16.dp, alpha = 0.82f)
            }
            Spacer(modifier = Modifier.width(15.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.staffName.ifBlank { event.staffId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShiftTextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(start = PulseTimelineGap)
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
                            .offset(x = pulseTimelineX(maxWidth, fraction))
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(ShiftLineColor),
                    )
                }
                if (markerFraction in 0f..1f) {
                    PulseLastSeenRhombusMarker(maxWidth = maxWidth, fraction = markerFraction)
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
            .size(17.dp)
            .clip(CircleShape)
            .background(status.tint.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, status.tint.copy(alpha = 0.42f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = status.marker,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = status.tint,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.pulseBarModifier(status: PulseStatusVisual): Modifier {
    return when (status.kind) {
        PulseStatusKind.UPCOMING -> this
            .background(status.tint.copy(alpha = 0.34f), RoundedCornerShape(5.dp))
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
private fun JournalRow(
    event: JournalEvent,
    selected: Boolean,
    confirmingDelete: Boolean,
    onSelected: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ShiftPanelDeepColor.copy(alpha = 0.86f) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (selected) ShiftBorderWarmColor else Color.Transparent),
                RoundedCornerShape(14.dp),
            )
            .pointerInput(event.timestamp, event.title, event.editable) {
                detectTapGestures(
                    onTap = { if (event.editable) onSelected() },
                    onLongPress = { if (event.editable) onSelected() },
                )
            }
            .padding(if (selected) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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

        if (selected && event.editable) {
            if (confirmingDelete) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Poistetaanko merkintä?",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = ShiftTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    JournalActionChip(label = "Peru", tint = ShiftTextMuted, onClick = onDeleteCancelled)
                    JournalActionChip(label = "Poista", tint = ShiftDanger, onClick = onDeleteConfirmed)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JournalActionChip(label = "Muokkaa", tint = ShiftCyan, onClick = onEdit)
                    JournalActionChip(label = "Poista", tint = ShiftDanger, onClick = onDeleteRequested)
                }
            }
        }
    }
}

@Composable
private fun JournalActionChip(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.30f)),
        contentColor = tint,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
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
    val source: String = "",
    val editable: Boolean = false,
)

const val JOURNAL_NOTE_SOURCE_MANUAL = "manual"
const val JOURNAL_NOTE_SOURCE_SYSTEM = "system"

/**
 * Factual authentication / recognition event for a single staff member.
 *
 * Semantics: "this staff member authenticated / was recognized at this time" and nothing
 * more. It does NOT imply on-site, working, headcount, or duration. Last-seen markers in
 * Työvuoropulssi must render as a point-in-time rhombus, never as a presence bar, and must
 * be suppressed for staff that are currently in active worktime.
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
    val note: JournalNote? = null,
    val editable: Boolean = false,
)

private fun JournalNote.sameJournalIdentity(other: JournalNote): Boolean {
    return timestampMillis == other.timestampMillis &&
        authorName == other.authorName &&
        text == other.text
}

private fun JournalNote.isEditableJournalNote(): Boolean {
    return when (source) {
        JOURNAL_NOTE_SOURCE_MANUAL -> editable
        JOURNAL_NOTE_SOURCE_SYSTEM -> false
        else -> !looksLikeSystemJournalNote(text)
    }
}

private fun looksLikeSystemJournalNote(text: String): Boolean {
    val normalized = text.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return false
    return listOf(
        "tunnistautui kassalla",
        "työvuorossa",
        "lopetti työvuoron",
        "ravintola avattu",
        "ravintola suljettu",
        "kassa laskettu",
        "pohjakassa",
        "kassaa ei laskettu",
    ).any { marker -> marker in normalized }
}

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

private fun formatPulseScheduleDate(date: LocalDate): String {
    val day = when (date.dayOfWeek.value) {
        1 -> "Ma"
        2 -> "Ti"
        3 -> "Ke"
        4 -> "To"
        5 -> "Pe"
        6 -> "La"
        else -> "Su"
    }
    return "$day ${date.dayOfMonth}.${date.monthValue}.${date.year}"
}

private fun formatPulseWindowTime(value: LocalDateTime): String = value.format(ShiftJournalTimeFormatter)

private fun LocalDateTime.roundUpToHour(): LocalDateTime {
    val roundedDown = truncatedTo(ChronoUnit.HOURS)
    return if (this == roundedDown) roundedDown else roundedDown.plusHours(1)
}

private fun plannedShiftCalendarTimeRange(shift: PlannedStaffShift): String {
    fun compactTime(value: LocalDateTime): String {
        val endsAtMidnightNextDay = value.toLocalDate().isAfter(shift.startsAt.toLocalDate()) &&
            value.hour == 0 && value.minute == 0
        val hour = if (endsAtMidnightNextDay) 24 else value.hour
        return "$hour.${value.minute.toString().padStart(2, '0')}"
    }
    val range = "${compactTime(shift.startsAt)}–${compactTime(shift.endsAt)}"
    return if (shift.endsAt.toLocalDate().isAfter(shift.startsAt.toLocalDate()) &&
        !(shift.endsAt.hour == 0 && shift.endsAt.minute == 0)
    ) {
        "$range +1"
    } else {
        range
    }
}

private fun plannedShiftTimeRange(shift: PlannedStaffShift): String {
    val range = "${formatPulseWindowTime(shift.startsAt)}-${formatPulseWindowTime(shift.endsAt)}"
    return if (shift.endsAt.toLocalDate().isAfter(shift.startsAt.toLocalDate())) {
        "$range +1 pv"
    } else {
        range
    }
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
        "active", "present", "clocked_in", "paikalla" -> PulseStatusVisual(
            PulseStatusKind.PRESENT,
            "Suunnittelematon työaika",
            ShiftCyan,
            "!",
        )
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
        else -> PulseStatusVisual(PulseStatusKind.PRESENT, statusLabelForAttendance(entry), ShiftCyan, "•")
    }
}

private fun plannedPulseStatus(
    shift: PlannedStaffShift,
    attendance: WorktimeAttendanceSnapshot,
    now: LocalDateTime,
): PulseStatusVisual {
    val normalized = shift.status?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized in setOf("absent", "away", "off", "poissa", "no_show", "cancelled", "canceled")) {
        return PulseStatusVisual(PulseStatusKind.ABSENT, "Poissa", ShiftDanger, "×")
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
        isClockedIn -> PulseStatusVisual(PulseStatusKind.PRESENT, "Suunniteltu · työaika käynnissä", ShiftSuccess, "✓")
        now.isBefore(shift.startsAt) -> PulseStatusVisual(PulseStatusKind.UPCOMING, "Suunniteltu", ShiftSuccess, "▷")
        shiftIsCurrent && hasAttendanceTruth && !hasAmbiguousStaffIdentity -> {
            PulseStatusVisual(PulseStatusKind.LATE, "Työaika puuttuu", ShiftDanger, "!")
        }
        shiftIsCurrent -> PulseStatusVisual(PulseStatusKind.UNKNOWN, "Työaika tuntematon", ShiftTextMuted, "?")
        else -> PulseStatusVisual(PulseStatusKind.COMPLETED, "Suunniteltu valmis", ShiftSuccess, "✓")
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
        "active", "present", "clocked_in", "paikalla" -> ShiftCyan
        "scheduled", "upcoming", "incoming", "tulossa" -> ShiftUpcoming
        "late", "myöhässä", "missing" -> ShiftDanger
        "absent", "away", "off", "poissa", "no_show" -> ShiftAbsent
        "on_break", "break", "tauolla" -> ShiftWarning
        "completed", "done", "valmis" -> ShiftTextMuted
        else -> ShiftCyan
    }
}
