package com.airos.pos.feature.shift

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.RestaurantOperatingHoursDay
import com.airos.pos.core.model.ShiftScheduleDay
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import com.airos.pos.core.ui.NumericMoneyPad
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.ShiftRepository
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
    val busy: Boolean = false,
    val message: String? = null,
)

class ShiftViewModel(
    private val shiftRepository: ShiftRepository,
    defaultOpeningFloatCents: Int = 5000,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ShiftUiState(openingFloatInput = centsToEuroInput(defaultOpeningFloatCents)),
    )
    val uiState: StateFlow<ShiftUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            shiftRepository.observeCurrentShift().collect { shift ->
                mutableState.update { it.copy(currentShift = shift, busy = false) }
            }
        }
    }

    fun updateOpeningFloat(value: String) {
        mutableState.update { it.copy(openingFloatInput = value, message = null) }
    }

    fun updateCountedCash(value: String) {
        mutableState.update { it.copy(countedCashInput = value, message = null) }
    }

    fun openShift(staffId: String) {
        val cents = euroInputToCents(mutableState.value.openingFloatInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Pohjakassa: syötä kelvollinen euromäärä, esimerkiksi 50,00.") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = shiftRepository.openShift(cents, staffId)) {
                is PosResult.Success -> mutableState.update { it.copy(currentShift = result.value, busy = false) }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    fun closeShift() {
        val activeShift = mutableState.value.currentShift
        if (activeShift == null) {
            mutableState.update { it.copy(message = "Pohjakassaa ei voi sulkea, koska vuoro ei ole avoinna.") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = shiftRepository.closeShift(activeShift.openingFloatCents)) {
                is PosResult.Success -> mutableState.update { it.copy(currentShift = result.value, busy = false) }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    companion object {
        fun factory(
            shiftRepository: ShiftRepository,
            defaultOpeningFloatCents: Int = 5000,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShiftViewModel(shiftRepository, defaultOpeningFloatCents) }
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

@Composable
fun ShiftScreen(
    state: ShiftUiState,
    currentStaffId: String?,
    currentStaffName: String? = null,
    onOpeningFloatChanged: (String) -> Unit = {},
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String) -> Unit,
    onCloseShift: () -> Unit,
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
) {
    var cashEditorExpanded by remember { mutableStateOf(false) }
    val topRowWeight = if (cashEditorExpanded) 0.36f else 0.58f
    val bottomRowWeight = if (cashEditorExpanded) 0.64f else 0.42f
    val ownShiftsWeight = if (cashEditorExpanded) 0.78f else 1.35f
    val attendanceWeight = if (cashEditorExpanded) 0.62f else 0.95f
    val cashShiftWeight = if (cashEditorExpanded) 2.35f else 1.05f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftPageBackground)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShiftHeader()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(topRowWeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShiftSchedulePulseCard(
                schedule = schedule,
                attendance = attendance,
                loading = scheduleLoading,
                message = scheduleMessage,
                modifier = Modifier
                    .weight(1.7f)
                    .fillMaxHeight(),
            )
            ShiftJournalCard(
                state = state,
                currentStaffName = currentStaffName,
                isClockedIn = isClockedIn,
                attendance = attendance,
                attendanceNoticeMessage = attendanceNoticeMessage,
                attendanceMessage = attendanceMessage,
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(bottomRowWeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OwnShiftsCard(
                schedule = ownSchedule,
                currentStaffId = currentStaffId,
                loading = ownScheduleLoading,
                message = ownScheduleMessage,
                modifier = Modifier
                    .weight(ownShiftsWeight)
                    .fillMaxHeight(),
            )
            OnSiteAttendanceCard(
                attendance = attendance,
                modifier = Modifier
                    .weight(attendanceWeight)
                    .fillMaxHeight(),
            )
            CashShiftCard(
                state = state,
                currentStaffId = currentStaffId,
                currentStaffName = currentStaffName,
                editorExpanded = cashEditorExpanded,
                onEditorExpandedChange = { cashEditorExpanded = it },
                onOpeningFloatChanged = onOpeningFloatChanged,
                onCountedCashChanged = onCountedCashChanged,
                onOpenShift = onOpenShift,
                onCloseShift = onCloseShift,
                modifier = Modifier
                    .weight(cashShiftWeight)
                    .fillMaxHeight(),
            )
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
                text = "Hallitse kassavuoroa, työaikaa ja henkilöstöä.",
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
    editorExpanded: Boolean,
    onEditorExpandedChange: (Boolean) -> Unit,
    onOpeningFloatChanged: (String) -> Unit,
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String) -> Unit,
    onCloseShift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentShift = state.currentShift
    val isOpen = currentShift != null
    var cashCounterOpen by remember(isOpen) { mutableStateOf(false) }
    var openingFloatPadOpen by remember(isOpen) { mutableStateOf(false) }
    var replaceCountedCashOnNextInput by remember(isOpen) { mutableStateOf(false) }
    var replaceOpeningFloatOnNextInput by remember(isOpen) { mutableStateOf(true) }
    var openingFloatTouched by remember(isOpen) { mutableStateOf(false) }
    val openedByLabel = remember(currentShift, currentStaffId, currentStaffName) {
        when {
            currentShift == null -> ""
            currentShift.openedByStaffId == currentStaffId && !currentStaffName.isNullOrBlank() -> currentStaffName
            else -> currentShift.openedByStaffId
        }
    }

    fun openCountedCashPad() {
        cashCounterOpen = true
        openingFloatPadOpen = false
        onEditorExpandedChange(true)
    }

    fun openOpeningFloatPad() {
        openingFloatPadOpen = true
        cashCounterOpen = false
        onEditorExpandedChange(true)
    }

    fun collapseCashEditor() {
        cashCounterOpen = false
        openingFloatPadOpen = false
        onEditorExpandedChange(false)
    }

    LaunchedEffect(isOpen) {
        cashCounterOpen = false
        openingFloatPadOpen = false
        onEditorExpandedChange(false)
    }

    ShiftCard(
        title = "Pohjakassa",
        icon = "▣",
        modifier = modifier,
        statusLabel = if (isOpen) "Avoin" else "Suljettu",
        statusColor = if (isOpen) ShiftSuccess else ShiftWarning,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (currentShift != null) {
                    ShiftKeyValueRow("Avaaja", openedByLabel)
                    CashAmountDisplay(
                        value = CentsFormatter.format(currentShift.openingFloatCents),
                        label = "Pohjakassa",
                        onClick = {},
                    )
                    Text(
                        text = "Pohjakassa on kirjattu vuoron avauksessa. Sulje vuoro, kun kassavuoro päättyy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ShiftTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CashAmountDisplay(
                            value = state.openingFloatInput,
                            label = "Pohjakassa",
                            onClick = {
                                replaceOpeningFloatOnNextInput = true
                                if (openingFloatPadOpen) {
                                    collapseCashEditor()
                                } else {
                                    openOpeningFloatPad()
                                }
                            },
                        )
                        if (openingFloatPadOpen) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = if (editorExpanded) 286.dp else 220.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = ShiftPanelRaisedColor,
                                border = BorderStroke(1.dp, ShiftBorderColor),
                                contentColor = ShiftTextPrimary,
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (openingFloatTouched) {
                                            "Syötä pohjakassa"
                                        } else {
                                            "Laske pohjakassa ja syötä summa ennen vuoron avausta"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ShiftTextMuted,
                                    )
                                    NumericMoneyPad(
                                        onDigit = { digit ->
                                            val next = if (replaceOpeningFloatOnNextInput) digit
                                            else appendShiftMoneyDigit(state.openingFloatInput, digit)
                                            replaceOpeningFloatOnNextInput = false
                                            openingFloatTouched = true
                                            onOpeningFloatChanged(next)
                                        },
                                        onDecimal = {
                                            val next = if (replaceOpeningFloatOnNextInput) "0,"
                                            else appendShiftMoneyDecimal(state.openingFloatInput)
                                            replaceOpeningFloatOnNextInput = false
                                            openingFloatTouched = true
                                            onOpeningFloatChanged(next)
                                        },
                                        onBackspace = {
                                            replaceOpeningFloatOnNextInput = false
                                            openingFloatTouched = true
                                            onOpeningFloatChanged(removeShiftMoneyChar(state.openingFloatInput))
                                        },
                                        keyHeight = if (editorExpanded) 54.dp else 42.dp,
                                        keyColor = ShiftKeyColor,
                                        keyContentColor = ShiftKeyContentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner(text = it, tint = ShiftDanger)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (isOpen) {
                        collapseCashEditor()
                        onCloseShift()
                    } else {
                        if (parseShiftEuroInputToCents(state.openingFloatInput) == null || !openingFloatTouched) {
                            replaceOpeningFloatOnNextInput = true
                            openOpeningFloatPad()
                        } else {
                            collapseCashEditor()
                            currentStaffId?.let(onOpenShift)
                        }
                    }
                },
                enabled = !state.busy && (isOpen || currentStaffId != null),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOpen) ShiftGold.copy(alpha = 0.82f) else ShiftCyan.copy(alpha = 0.82f),
                    contentColor = Color(0xFF071109),
                    disabledContainerColor = ShiftPanelRaisedColor,
                    disabledContentColor = ShiftTextMuted,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (isOpen) "Sulje vuoro" else "Avaa vuoro",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun OnSiteAttendanceCard(
    attendance: WorktimeAttendanceSnapshot,
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Paikalla oleva henkilöstö",
        icon = "☷",
        modifier = modifier,
        statusLabel = attendance.currentlyOnSite.size.takeIf { it > 0 }?.toString(),
        statusColor = ShiftGold,
    ) {
        val entries = attendance.currentlyOnSite
        if (entries.isEmpty()) {
            ShiftEmptyText("Ei aktiivisia työvuoroja.")
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
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Työvuoropulssi",
        icon = "⌁",
        modifier = modifier,
    ) {
        val now = LocalDateTime.now()
        val publishedDays = schedule?.days.orEmpty().filter { it.hasPublishedScheduleTruth() }
        val operatingWindow = schedule?.operatingHours?.let { currentOperatingWindow(now, it) }
        val visibleShifts = operatingWindow?.let { window ->
            publishedDays
                .flatMap { it.plannedShifts }
                .filter { it.overlaps(window.start, window.end) }
                .sortedWith(compareBy<PlannedStaffShift> { it.startsAt }.thenBy { it.staffName })
        }.orEmpty()

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
                !schedule.operatingHoursMessage.isNullOrBlank() -> ShiftStatusBanner(
                    text = "Työvuoropulssi ei ole saatavilla. Aukioloaikatietoa ei saatu backendistä.",
                    tint = ShiftDanger,
                )
                schedule.operatingHours.isEmpty() -> ShiftStatusBanner(
                    text = "Työvuoropulssi ei ole saatavilla. Backend ei palauttanut aukioloaikatietoa.",
                    tint = ShiftDanger,
                )
                operatingWindow == null -> ShiftStatusBanner(
                    text = "Työvuoropulssi ei ole saatavilla. Tälle päivälle ei löydy aukioloaikaa backendistä.",
                    tint = ShiftDanger,
                )
                publishedDays.isEmpty() -> ShiftEmptyText("Aikavälillä ei ole julkaistua työvuorosuunnitelmaa.")
                visibleShifts.isEmpty() -> ShiftEmptyText("Julkaistuilla päivillä ei ole suunniteltuja vuoroja ravintolan aukioloaikana.")
                else -> {
                    val windowStart = operatingWindow.start
                    val windowEnd = operatingWindow.end
                    val timelineWidth = pulseTimelineWidth(windowStart = windowStart, windowEnd = windowEnd)
                    Text(
                        text = "${formatPulseWindowTime(windowStart)}-${formatPulseWindowTime(windowEnd)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ShiftTextMuted,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PulseTimelineHeader(
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            timelineWidth = timelineWidth,
                        )
                        Column(
                            modifier = Modifier
                                .width(timelineWidth)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            visibleShifts.forEachIndexed { index, shift ->
                                PulseTimelineShiftRow(
                                    shift = shift,
                                    attendance = attendance,
                                    now = now,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    timelineWidth = timelineWidth,
                                )
                                if (index != visibleShifts.lastIndex) {
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
                            "Ei julkaistuja vuoroja tälle käyttäjälle. " +
                                "Tarkista vuorosuunnittelu tarvittaessa hallinnasta.",
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
    modifier: Modifier = Modifier,
) {
    ShiftCard(
        title = "Vuoropäiväkirja",
        icon = "☰",
        modifier = modifier,
    ) {
        val events = buildList {
            state.currentShift?.let { shift ->
                add(
                    JournalEvent(
                        marker = "✓",
                        tint = ShiftSuccess,
                        title = "Kassavuoro avoinna",
                        detail = "Pohjakassa ${CentsFormatter.format(shift.openingFloatCents)}",
                        timestamp = shift.openedAtEpochMillis.toString(),
                    ),
                )
            }
            if (isClockedIn && currentStaffName != null) {
                add(
                    JournalEvent(
                        marker = "●",
                        tint = ShiftCyan,
                        title = "$currentStaffName työvuorossa",
                        detail = "Työaikakirjaus aktiivinen",
                        timestamp = attendance.currentlyOnSite.firstOrNull { it.staffName == currentStaffName }?.startedAt,
                    ),
                )
            }
            attendance.currentlyOnSite.forEach { entry ->
                add(
                    JournalEvent(
                        marker = "•",
                        tint = statusColorForAttendance(entry),
                        title = entry.staffName,
                        detail = "${statusLabelForAttendance(entry)} · ${formatDuration(entry.durationMinutes)}",
                        timestamp = entry.startedAt,
                    ),
                )
            }
            attendance.clockedInToday.forEach { entry ->
                if (attendance.currentlyOnSite.none { it.staffId == entry.staffId && it.startedAt == entry.startedAt }) {
                    add(
                        JournalEvent(
                            marker = "◦",
                            tint = statusColorForAttendance(entry),
                            title = entry.staffName,
                            detail = "${statusLabelForAttendance(entry)} · ${formatDuration(entry.durationMinutes)}",
                            timestamp = entry.startedAt,
                        ),
                    )
                }
            }
            attendanceNoticeMessage?.let {
                add(JournalEvent("i", ShiftCyan, "Työaikailmoitus", it))
            }
            attendanceMessage?.let {
                add(JournalEvent("!", ShiftDanger, "Työaikavirhe", it))
            }
        }

        if (events.isEmpty()) {
            ShiftEmptyText("Ei työvuoromerkintöjä.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
) {
    Surface(
        modifier = modifier,
        shape = ShiftInnerShape,
        color = ShiftPanelDeepColor.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, ShiftBorderColor),
        contentColor = ShiftTextPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
                style = MaterialTheme.typography.titleSmall,
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ShiftTextMuted,
            )
            Text(
                text = displayMoneyInput(value),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
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

private data class RestaurantOperatingWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
)

private fun currentOperatingWindow(
    now: LocalDateTime,
    operatingHours: List<RestaurantOperatingHoursDay>,
): RestaurantOperatingWindow? {
    if (operatingHours.isEmpty()) return null
    val windows = listOf(now.toLocalDate().minusDays(1), now.toLocalDate(), now.toLocalDate().plusDays(1))
        .mapNotNull { date -> operatingWindowForDate(date, operatingHours) }
        .sortedBy { it.start }

    return windows.lastOrNull { window ->
        !now.isBefore(window.start) && now.isBefore(window.end)
    } ?: windows.firstOrNull { window ->
        window.start.toLocalDate() == now.toLocalDate()
    } ?: windows.firstOrNull { window ->
        window.start.isAfter(now)
    }
}

private fun operatingWindowForDate(
    date: LocalDate,
    operatingHours: List<RestaurantOperatingHoursDay>,
): RestaurantOperatingWindow? {
    val weekday = date.dayOfWeek.value
    val day = operatingHours.firstOrNull { it.weekday == weekday } ?: return null
    if (day.closed) return null
    val open = day.open ?: return null
    val close = day.close ?: return null
    val start = date.atTime(open)
    val closeDate = if (close <= open) date.plusDays(1) else date
    val end = closeDate.atTime(close)
    if (!end.isAfter(start)) return null
    return RestaurantOperatingWindow(start = start, end = end)
}

private val PulseTimelineMinimumWidth = 1120.dp
private val PulseTimelineWidthPerHour = 96.dp
private val PulseLeftColumnWidth = 176.dp
private val PulseTickLabelWidth = 44.dp
private val PulseTickLabelHalfWidth = 22.dp

private fun pulseTimelineWidth(windowStart: LocalDateTime, windowEnd: LocalDateTime): Dp {
    val hours = Duration.between(windowStart, windowEnd).toMinutes().coerceAtLeast(60L) / 60f
    val calculated = PulseTimelineWidthPerHour * hours
    return if (calculated > PulseTimelineMinimumWidth) calculated else PulseTimelineMinimumWidth
}

private fun pulseTickTimes(windowStart: LocalDateTime, windowEnd: LocalDateTime): List<LocalDateTime> {
    val ticks = mutableListOf<LocalDateTime>()
    var cursor = windowStart.truncatedTo(ChronoUnit.HOURS)
    while (!cursor.isAfter(windowEnd)) {
        ticks += cursor
        cursor = cursor.plusHours(1)
    }
    if (ticks.lastOrNull() != windowEnd) {
        ticks += windowEnd
    }
    return ticks.distinct()
}

@Composable
private fun PulseTimelineHeader(
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    timelineWidth: Dp,
) {
    val tickTimes = pulseTickTimes(windowStart, windowEnd)
    Row(
        modifier = Modifier.width(timelineWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(PulseLeftColumnWidth))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
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

@Composable
private fun PulseTimelineShiftRow(
    shift: PlannedStaffShift,
    attendance: WorktimeAttendanceSnapshot,
    now: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    timelineWidth: Dp,
) {
    val pulseStatus = plannedPulseStatus(shift = shift, attendance = attendance, now = now)
    val startFraction = pulseFraction(shift.startsAt, windowStart, windowEnd).coerceIn(0f, 1f)
    val endFraction = pulseFraction(shift.endsAt, windowStart, windowEnd).coerceIn(startFraction + 0.035f, 1f)
    val barWidthFraction = (endFraction - startFraction).coerceIn(0.035f, 1f)
    val timeRange = plannedShiftTimeRange(shift)
    val nowFraction = pulseFraction(now, windowStart, windowEnd)

    Row(
        modifier = Modifier
            .width(timelineWidth)
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
                    text = timeRange,
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

private fun parseShiftEuroInputToCents(input: String): Int? {
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
        frac.padEnd(2, '0').toIntOrNull() ?: return null
    } else {
        0
    }
    val totalCents = integerPart * 100 + fractionCents
    if (totalCents > Int.MAX_VALUE) return null
    return totalCents.toInt()
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

private fun formatPulseWindowTime(value: LocalDateTime): String = value.format(ShiftJournalTimeFormatter)

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
        "active", "present", "clocked_in", "paikalla" -> PulseStatusVisual(PulseStatusKind.PRESENT, "Paikalla", ShiftSuccess, "!")
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

    val isOnSite = attendance.currentlyOnSite.any { entry ->
        entry.staffId == shift.staffId ||
            (entry.staffId.isBlank() && entry.staffName.equals(shift.staffName, ignoreCase = true))
    }

    return when {
        isOnSite -> PulseStatusVisual(PulseStatusKind.PRESENT, "Paikalla", ShiftSuccess, "!")
        now.isBefore(shift.startsAt) -> PulseStatusVisual(PulseStatusKind.UPCOMING, "Tulossa", ShiftUpcoming, "▷")
        now.isBefore(shift.endsAt) -> PulseStatusVisual(PulseStatusKind.LATE, "Myöhässä", ShiftDanger, "!")
        else -> PulseStatusVisual(PulseStatusKind.ABSENT, "Poissa", ShiftAbsent, "×")
    }
}

private fun statusLabelForAttendance(entry: AttendanceEntry): String {
    val normalized = entry.status.trim().lowercase(Locale.ROOT)
    return when (normalized) {
        "active", "present", "clocked_in", "paikalla" -> "Paikalla"
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
