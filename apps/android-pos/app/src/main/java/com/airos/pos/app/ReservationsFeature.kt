package com.airos.pos.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.TableRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class ReservationTableOption(
    val serviceSpotId: String,
    val backendTableId: Int?,
    val label: String,
    val seats: Int,
    val areaName: String,
)

private enum class ReservationTimeField {
    START,
    END,
}

private enum class AirosClockHand {
    HOUR,
    MINUTE,
}

private enum class ReservationsViewTab {
    INBOX,
    TIMELINE,
}

private enum class ReservationQueueFilter(
    val label: String,
) {
    ALL("Kaikki"),
    UPCOMING("Tulevat"),
    ATTENTION("Huomio"),
    UNASSIGNED("Ei pöytää"),
}

private enum class ReservationSortMode(
    val label: String,
) {
    TIME("Aika"),
    DAY("Päivä"),
    TABLE("Pöytä"),
    NAME("Nimi"),
    STATUS("Tila"),
}

private enum class ReservationWizardStep {
    DATE_TIME,
    PARTY,
    GUEST,
    TABLE,
}

private enum class ReservationStatus(
    val tag: String,
    val label: String,
) {
    BOOKED("BOOKED", "Varattu"),
    ARRIVED("ARRIVED", "Saapunut"),
    SEATED("SEATED", "Istutettu"),
    CANCELLED("CANCELLED", "Peruttu"),
    NOSHOW("NOSHOW", "Ei saapunut"),
}

private enum class ReservationTimeWindow(
    val label: String,
    val start: LocalTime?,
    val end: LocalTime?,
) {
    ALL("Kaikki ajat", null, null),
    LUNCH("Lounas", LocalTime.of(11, 0), LocalTime.of(15, 0)),
    AFTERNOON("Iltapäivä", LocalTime.of(15, 0), LocalTime.of(18, 0)),
    EVENING("Ilta", LocalTime.of(18, 0), LocalTime.of(23, 0)),
}

// Local product/demo step until Dashboard owner settings define reservation time precision.
private const val RESERVATION_TIME_STEP_MINUTES = 3L
private const val RESERVATION_PULSE_START_HOUR = 12
private const val RESERVATION_PULSE_END_HOUR = 23
private const val RESERVATION_PULSE_SLOT_MINUTES = 30L
private const val RESERVATION_PULSE_PAST_DAYS = 7
private const val RESERVATION_PULSE_FUTURE_DAYS = 28
private val ReservationPulseSlotStarts: List<LocalTime> = generateSequence(
    LocalTime.of(RESERVATION_PULSE_START_HOUR, 0),
) { it.plusMinutes(RESERVATION_PULSE_SLOT_MINUTES) }
    .takeWhile { it.isBefore(LocalTime.of(RESERVATION_PULSE_END_HOUR, 0)) }
    .toList()
private val ReservationPulseHourMarkers = setOf(12, 14, 16, 18, 20, 22)

private data class ReservationFormState(
    val editingReservationId: Int? = null,
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val allergies: String = "",
    val status: ReservationStatus = ReservationStatus.BOOKED,
    val persons: String = "2",
    val selectedTableId: Int? = null,
    val selectedTableLabel: String? = null,
    val startTime: String = "18:00",
    val durationMinutes: String = "120",
    val endTime: String = "20:00",
    val notes: String = "",
)

private data class ReservationsUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val dateInput: String = LocalDate.now().format(DateInputFormatter),
    val reservations: List<BackendReservation> = emptyList(),
    val pulseReservations: List<BackendReservation> = emptyList(),
    val tables: List<ReservationTableOption> = emptyList(),
    val form: ReservationFormState = ReservationFormState(),
    val searchQuery: String = "",
    val tableFilterId: Int? = null,
    val unassignedOnly: Boolean = false,
    val queueFilter: ReservationQueueFilter = ReservationQueueFilter.ALL,
    val sortMode: ReservationSortMode = ReservationSortMode.TIME,
    val timeWindowFilter: ReservationTimeWindow = ReservationTimeWindow.ALL,
    val wizardOpen: Boolean = false,
    val wizardStep: ReservationWizardStep = ReservationWizardStep.DATE_TIME,
    val slotWorkbenchSelection: ReservationPulseSlotSelection? = null,
    val pendingAssignReservationId: Int? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

private class ReservationsViewModel(
    private val reservationsRepository: BackendReservationsRepository,
    private val tableRepository: TableRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReservationsUiState(isLoading = true))
    val uiState: StateFlow<ReservationsUiState> = mutableState.asStateFlow()

    private var allReservations: List<BackendReservation> = emptyList()

    init {
        viewModelScope.launch {
            tableRepository.observeFloorMap().collect { floorMap ->
                val options = floorMap.tables
                    .filter { it.spotType == ServiceSpotType.TABLE }
                    .map { it.toReservationTableOption() }
                    .sortedBy { it.label.lowercase() }
                mutableState.update { state ->
                    val selectedOption = state.form.selectedTableId
                        ?.let { backendId -> options.firstOrNull { it.backendTableId == backendId } }
                    state.copy(
                        tables = options,
                        form = state.form.copy(
                            selectedTableLabel = selectedOption?.label ?: state.form.selectedTableLabel,
                        ),
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            when (val result = reservationsRepository.listReservations()) {
                is PosResult.Success -> {
                    allReservations = result.value
                    mutableState.update { state ->
                        state.copy(
                            reservations = reservationsForDay(state.selectedDate),
                            pulseReservations = reservationsForPulseWindow(state.selectedDate),
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                is PosResult.Failure -> {
                    mutableState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }

    fun previousDay() {
        selectDate(uiState.value.selectedDate.minusDays(1))
    }

    fun today() {
        selectDate(LocalDate.now())
    }

    fun nextDay() {
        selectDate(uiState.value.selectedDate.plusDays(1))
    }

    fun selectCalendarDate(date: LocalDate) {
        selectDate(date)
    }

    private fun selectDate(date: LocalDate) {
        mutableState.update {
            it.copy(
                selectedDate = date,
                dateInput = date.format(DateInputFormatter),
                reservations = reservationsForDay(date),
                pulseReservations = reservationsForPulseWindow(date),
                message = null,
                error = null,
            )
        }
    }

    fun updateDateInput(value: String) {
        mutableState.update { it.copy(dateInput = value.take(10), message = null, error = null) }
    }

    fun applyDateInput() {
        val parsed = parseDateInput(uiState.value.dateInput)
        if (parsed == null) {
            mutableState.update { it.copy(error = "Päivämäärän muodon pitää olla VVVV-KK-PP.", message = null) }
            return
        }
        selectDate(parsed)
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value.take(120)) }
    }

    fun updateTableFilter(tableId: Int?) {
        mutableState.update { it.copy(tableFilterId = tableId, unassignedOnly = false, queueFilter = ReservationQueueFilter.ALL) }
    }

    fun updateUnassignedFilter(enabled: Boolean) {
        mutableState.update {
            it.copy(
                unassignedOnly = enabled,
                tableFilterId = null,
                queueFilter = if (enabled) ReservationQueueFilter.UNASSIGNED else ReservationQueueFilter.ALL,
            )
        }
    }

    fun updateQueueFilter(filter: ReservationQueueFilter) {
        mutableState.update {
            it.copy(
                queueFilter = filter,
                unassignedOnly = filter == ReservationQueueFilter.UNASSIGNED,
                tableFilterId = null,
            )
        }
    }

    fun updateSortMode(mode: ReservationSortMode) {
        mutableState.update { it.copy(sortMode = mode) }
    }

    fun updateTimeWindowFilter(window: ReservationTimeWindow) {
        mutableState.update { it.copy(timeWindowFilter = window) }
    }

    fun openNewReservation() {
        mutableState.update {
            it.copy(
                form = ReservationFormState(),
                wizardOpen = true,
                wizardStep = ReservationWizardStep.DATE_TIME,
                slotWorkbenchSelection = null,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun openSlotWorkbench(selection: ReservationPulseSlotSelection) {
        val start = selection.start.withSecond(0).withNano(0)
        val duration = 120L
        mutableState.update {
            it.copy(
                selectedDate = selection.date,
                dateInput = selection.date.format(DateInputFormatter),
                reservations = reservationsForDay(selection.date),
                pulseReservations = reservationsForPulseWindow(selection.date),
                form = ReservationFormState(
                    startTime = start.format(TimeFormatter),
                    durationMinutes = duration.toString(),
                    endTime = start.plusMinutes(duration).format(TimeFormatter),
                ),
                wizardOpen = false,
                wizardStep = ReservationWizardStep.DATE_TIME,
                slotWorkbenchSelection = selection,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun closeWizard() {
        mutableState.update {
            it.copy(
                form = ReservationFormState(),
                wizardOpen = false,
                wizardStep = ReservationWizardStep.DATE_TIME,
                slotWorkbenchSelection = null,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun closeSlotWorkbench() {
        mutableState.update {
            it.copy(
                form = ReservationFormState(),
                slotWorkbenchSelection = null,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun setWizardStep(step: ReservationWizardStep) {
        mutableState.update { it.copy(wizardStep = step, message = null, error = null) }
    }

    fun updateCustomerName(value: String) = updateForm { it.copy(customerName = value) }
    fun updateCustomerPhone(value: String) = updateForm { it.copy(customerPhone = normalizeReservationPhoneInput(value)) }
    fun updateCustomerEmail(value: String) = updateForm { it.copy(customerEmail = value.take(160)) }
    fun updateAllergies(value: String) = updateForm { it.copy(allergies = value.take(300)) }
    fun updateStatus(status: ReservationStatus) = updateForm { it.copy(status = status) }
    fun updatePersons(value: String) = updateForm { it.copy(persons = value.filter(Char::isDigit).take(3)) }
    fun adjustPersons(delta: Int) = updateForm { form ->
        val current = form.persons.toIntOrNull() ?: 2
        form.copy(persons = (current + delta).coerceIn(1, 99).toString())
    }
    fun updateStartTime(value: String) = updateForm { form ->
        val nextStartTime = value.take(5)
        val duration = form.durationMinutes.toIntOrNull()
        val start = parseFormTime(nextStartTime)
        if (start != null && duration != null && duration > 0) {
            form.copy(
                startTime = nextStartTime,
                endTime = start.plusMinutes(duration.toLong()).format(TimeFormatter),
            )
        } else {
            form.copy(startTime = nextStartTime)
        }
    }

    fun updateDurationMinutes(value: String) = updateForm { form ->
        val nextDuration = value.filter(Char::isDigit).take(4)
        val minutes = nextDuration.toIntOrNull()
        val start = parseFormTime(form.startTime)
        if (start != null && minutes != null && minutes > 0) {
            form.copy(
                durationMinutes = nextDuration,
                endTime = start.plusMinutes(minutes.toLong()).format(TimeFormatter),
            )
        } else {
            form.copy(durationMinutes = nextDuration)
        }
    }

    fun updateEndTime(value: String) = updateForm { form ->
        val nextEndTime = value.take(5)
        val duration = durationMinutesBetween(form.startTime, nextEndTime)
        form.copy(
            endTime = nextEndTime,
            durationMinutes = duration?.toString() ?: form.durationMinutes,
        )
    }

    fun updateNotes(value: String) = updateForm { it.copy(notes = value.take(500)) }
    fun clearSelectedTable() = updateForm { it.copy(selectedTableId = null, selectedTableLabel = null) }

    fun beginFormTablePicker() {
        mutableState.update { it.copy(pendingAssignReservationId = null, message = null, error = null) }
    }

    fun beginAssignTable(reservation: BackendReservation) {
        mutableState.update {
            it.copy(
                pendingAssignReservationId = reservation.id,
                message = null,
                error = null,
            )
        }
    }

    fun selectTableFromPicker(serviceSpotId: String, fallbackLabel: String?) {
        val state = uiState.value
        val option = state.tables.firstOrNull { it.serviceSpotId == serviceSpotId }
        if (option == null) {
            mutableState.update {
                it.copy(
                    error = "Valittua pöytää ei löytynyt nykyisestä pöytätotuudesta.",
                    message = null,
                )
            }
            return
        }
        val backendTableId = option.backendTableId
        if (backendTableId == null) {
            mutableState.update {
                it.copy(
                    error = "Valitulla pöydällä ei ole backendin pöytä-ID:tä.",
                    message = null,
                )
            }
            return
        }
        val pendingAssignReservationId = state.pendingAssignReservationId
        if (pendingAssignReservationId != null) {
            assignTableFromPicker(
                reservationId = pendingAssignReservationId,
                backendTableId = backendTableId,
            )
            return
        }
        val availability = option.availability(state)
        if (!availability.isSelectable) {
            mutableState.update { it.copy(error = availability.reason, message = null) }
            return
        }
        updateForm {
            it.copy(
                selectedTableId = backendTableId,
                selectedTableLabel = option.label.ifBlank { fallbackLabel.orEmpty() },
            )
        }
    }

    private fun updateForm(change: (ReservationFormState) -> ReservationFormState) {
        mutableState.update { it.copy(form = change(it.form), message = null, error = null) }
    }

    fun editReservation(reservation: BackendReservation) {
        val parsedStart = parseReservationDateTime(reservation.startTime)
        val targetDate = parsedStart?.toLocalDate()
        val selection = reservationPulseSelectionForReservation(reservation)
        mutableState.update { state ->
            val nextDate = targetDate ?: state.selectedDate
            state.copy(
                selectedDate = nextDate,
                reservations = reservationsForDay(nextDate),
                pulseReservations = reservationsForPulseWindow(nextDate),
                form = reservationFormState(reservation, state),
                wizardOpen = false,
                wizardStep = ReservationWizardStep.DATE_TIME,
                slotWorkbenchSelection = selection,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun loadReservationIntoSlotWorkbench(reservation: BackendReservation) {
        mutableState.update { state ->
            state.copy(
                form = reservationFormState(reservation, state),
                wizardOpen = false,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    private fun reservationFormState(
        reservation: BackendReservation,
        state: ReservationsUiState,
    ): ReservationFormState {
        val parsedStart = parseReservationDateTime(reservation.startTime)
        val parsedEnd = parseReservationDateTime(reservation.endTime)
        val noteParts = splitReservationNotes(reservation.notes.orEmpty())
        return ReservationFormState(
            editingReservationId = reservation.id,
            customerName = reservation.customerName,
            customerPhone = normalizeReservationPhoneInput(reservation.customerPhone.orEmpty()),
            customerEmail = noteParts.email,
            allergies = noteParts.allergies,
            status = noteParts.status,
            persons = reservation.persons.toString(),
            selectedTableId = reservation.tableId,
            selectedTableLabel = reservation.tableId?.let { tableId ->
                state.tables.firstOrNull { it.backendTableId == tableId }?.label ?: "Pöytä $tableId"
            },
            startTime = parsedStart?.toLocalTime()?.format(TimeFormatter) ?: state.form.startTime,
            durationMinutes = durationMinutesBetween(
                parsedStart?.toLocalTime()?.format(TimeFormatter).orEmpty(),
                parsedEnd?.toLocalTime()?.format(TimeFormatter).orEmpty(),
            )?.toString() ?: state.form.durationMinutes,
            endTime = parsedEnd?.toLocalTime()?.format(TimeFormatter) ?: state.form.endTime,
            notes = noteParts.notes,
        )
    }

    fun clearForm() {
        mutableState.update { state ->
            state.copy(
                form = ReservationFormState(),
                wizardOpen = false,
                wizardStep = ReservationWizardStep.DATE_TIME,
                slotWorkbenchSelection = null,
                pendingAssignReservationId = null,
                message = null,
                error = null,
            )
        }
    }

    fun submit() {
        val state = uiState.value
        val selectedOption = state.tables.firstOrNull { it.backendTableId == state.form.selectedTableId }
        val selectedAvailability = selectedOption?.availability(state)
        if (state.form.selectedTableId != null && selectedAvailability?.isSelectable != true) {
            mutableState.update {
                it.copy(
                    error = selectedAvailability?.reason ?: "Valitse käytettävissä oleva pöytä.",
                    message = null,
                )
            }
            return
        }
        val payload = state.form.toPayload(state.selectedDate)
        if (payload is PosResult.Failure) {
            mutableState.update { it.copy(error = payload.message, message = null) }
            return
        }
        val write = (payload as PosResult.Success).value
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, error = null, message = null) }
            val result = state.form.editingReservationId?.let { id ->
                reservationsRepository.updateReservation(id, write)
            } ?: reservationsRepository.createReservation(write)
            when (result) {
                is PosResult.Success -> {
                    refreshAfterMutation(
                        message = if (state.form.editingReservationId == null) {
                            "Varaus luotu."
                        } else {
                            "Varaus päivitetty."
                        },
                    )
                }
                is PosResult.Failure -> {
                    mutableState.update { it.copy(isSaving = false, error = result.message) }
                }
            }
        }
    }

    private fun assignTableFromPicker(reservationId: Int, backendTableId: Int) {
        val reservation = allReservations.firstOrNull { it.id == reservationId }
            ?: uiState.value.reservations.firstOrNull { it.id == reservationId }
        if (reservation == null) {
            mutableState.update {
                it.copy(
                    pendingAssignReservationId = null,
                    error = "Varausta ei löytynyt.",
                    message = null,
                )
            }
            return
        }
        val parsedStart = parseReservationDateTime(reservation.startTime)
        val parsedEnd = parseReservationDateTime(reservation.endTime)
        if (parsedStart == null || parsedEnd == null) {
            mutableState.update {
                it.copy(
                    pendingAssignReservationId = null,
                    error = "Varauksen aikaa ei voitu tulkita.",
                    message = null,
                )
            }
            return
        }
        val payload = BackendReservationWrite(
            tableId = backendTableId,
            customerName = reservation.customerName,
            customerPhone = reservation.customerPhone,
            customerProfileId = reservation.customerProfileId,
            startTime = parsedStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            endTime = parsedEnd.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            persons = reservation.persons,
            notes = reservation.notes,
        )
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    pendingAssignReservationId = null,
                    isSaving = true,
                    error = null,
                    message = null,
                )
            }
            when (val result = reservationsRepository.updateReservation(reservation.id, payload)) {
                is PosResult.Success -> refreshAfterMutation(message = "Pöytä valittu.")
                is PosResult.Failure -> mutableState.update { it.copy(isSaving = false, error = result.message) }
            }
        }
    }

    fun updateReservationStatus(reservation: BackendReservation, status: ReservationStatus) {
        val parsedStart = parseReservationDateTime(reservation.startTime)
        val parsedEnd = parseReservationDateTime(reservation.endTime)
        if (parsedStart == null || parsedEnd == null) {
            mutableState.update { it.copy(error = "Varauksen aikaa ei voitu tulkita.", message = null) }
            return
        }
        val parts = splitReservationNotes(reservation.notes.orEmpty())
        val payload = BackendReservationWrite(
            tableId = reservation.tableId,
            customerName = reservation.customerName,
            customerPhone = reservation.customerPhone,
            customerProfileId = reservation.customerProfileId,
            startTime = parsedStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            endTime = parsedEnd.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            persons = reservation.persons,
            notes = buildReservationNotes(
                status = status,
                email = parts.email,
                allergies = parts.allergies,
                notes = parts.notes,
            ),
        )
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, error = null, message = null) }
            when (val result = reservationsRepository.updateReservation(reservation.id, payload)) {
                is PosResult.Success -> refreshAfterMutation(message = "Varauksen tila päivitetty.")
                is PosResult.Failure -> mutableState.update { it.copy(isSaving = false, error = result.message) }
            }
        }
    }

    private suspend fun refreshAfterMutation(message: String) {
        when (val refreshed = reservationsRepository.listReservations()) {
            is PosResult.Success -> {
                allReservations = refreshed.value
                mutableState.update { state ->
                    state.copy(
                        reservations = reservationsForDay(state.selectedDate),
                        pulseReservations = reservationsForPulseWindow(state.selectedDate),
                        form = ReservationFormState(),
                        wizardOpen = false,
                        wizardStep = ReservationWizardStep.DATE_TIME,
                        slotWorkbenchSelection = null,
                        pendingAssignReservationId = null,
                        isSaving = false,
                        isLoading = false,
                        message = message,
                        error = null,
                    )
                }
            }
            is PosResult.Failure -> {
                mutableState.update {
                    it.copy(
                        form = ReservationFormState(),
                        wizardOpen = false,
                        wizardStep = ReservationWizardStep.DATE_TIME,
                        slotWorkbenchSelection = null,
                        pendingAssignReservationId = null,
                        isSaving = false,
                        isLoading = false,
                        message = message,
                        error = refreshed.message,
                    )
                }
            }
        }
    }

    private fun reservationsForDay(date: LocalDate): List<BackendReservation> {
        return allReservations
            .filter { parseReservationDateTime(it.startTime)?.toLocalDate() == date }
            .sortedBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MIN }
    }

    private fun reservationsForPulseWindow(date: LocalDate): List<BackendReservation> {
        val windowStartDate = reservationPulseWindowStart(date)
        val windowStart = windowStartDate.atStartOfDay()
        val windowEnd = windowStart.plusDays((RESERVATION_PULSE_PAST_DAYS + RESERVATION_PULSE_FUTURE_DAYS + 1).toLong())
        return allReservations
            .filter { reservation ->
                val start = parseReservationDateTime(reservation.startTime)
                val end = parseReservationDateTime(reservation.endTime)
                start != null && end != null && start.isBefore(windowEnd) && end.isAfter(windowStart)
            }
            .sortedBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MIN }
    }

    companion object {
        fun factory(
            reservationsRepository: BackendReservationsRepository,
            tableRepository: TableRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReservationsViewModel(reservationsRepository, tableRepository) }
        }
    }
}

@Composable
fun ReservationsRoute(
    reservationsRepository: BackendReservationsRepository,
    tableRepository: TableRepository,
    title: String,
    selectTableLabel: String,
    noTableSelectedLabel: String,
    pickedTableId: String?,
    pickedTableLabel: String?,
    onPickedTableConsumed: () -> Unit,
    onOpenTablePicker: () -> Unit,
) {
    val viewModel: ReservationsViewModel = viewModel(
        factory = ReservationsViewModel.factory(
            reservationsRepository = reservationsRepository,
            tableRepository = tableRepository,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(pickedTableId, pickedTableLabel, state.tables) {
        val tableId = pickedTableId
        if (tableId != null && state.tables.isNotEmpty()) {
            viewModel.selectTableFromPicker(tableId, pickedTableLabel)
            onPickedTableConsumed()
        }
    }
    ReservationsScreen(
        title = title,
        state = state,
        selectTableLabel = selectTableLabel,
        noTableSelectedLabel = noTableSelectedLabel,
        onPreviousDay = viewModel::previousDay,
        onToday = viewModel::today,
        onNextDay = viewModel::nextDay,
        onCalendarDateSelected = viewModel::selectCalendarDate,
        onDateInputChange = viewModel::updateDateInput,
        onApplyDateInput = viewModel::applyDateInput,
        onRefresh = viewModel::refresh,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onTableFilterChange = viewModel::updateTableFilter,
        onUnassignedFilterChange = viewModel::updateUnassignedFilter,
        onQueueFilterChange = viewModel::updateQueueFilter,
        onSortModeChange = viewModel::updateSortMode,
        onTimeWindowFilterChange = viewModel::updateTimeWindowFilter,
        onOpenNewReservation = viewModel::openNewReservation,
        onOpenSlotWorkbench = viewModel::openSlotWorkbench,
        onCloseWizard = viewModel::closeWizard,
        onCloseSlotWorkbench = viewModel::closeSlotWorkbench,
        onWizardStepChange = viewModel::setWizardStep,
        onCustomerNameChange = viewModel::updateCustomerName,
        onCustomerPhoneChange = viewModel::updateCustomerPhone,
        onCustomerEmailChange = viewModel::updateCustomerEmail,
        onAllergiesChange = viewModel::updateAllergies,
        onStatusChange = viewModel::updateStatus,
        onPersonsChange = viewModel::updatePersons,
        onAdjustPersons = viewModel::adjustPersons,
        onOpenTablePicker = {
            viewModel.beginFormTablePicker()
            onOpenTablePicker()
        },
        onClearSelectedTable = viewModel::clearSelectedTable,
        onStartTimeChange = viewModel::updateStartTime,
        onDurationMinutesChange = viewModel::updateDurationMinutes,
        onEndTimeChange = viewModel::updateEndTime,
        onNotesChange = viewModel::updateNotes,
        onSubmit = viewModel::submit,
        onEdit = viewModel::editReservation,
        onLoadReservationIntoSlotWorkbench = viewModel::loadReservationIntoSlotWorkbench,
        onStatusUpdate = viewModel::updateReservationStatus,
        onAssignTable = { reservation ->
            viewModel.beginAssignTable(reservation)
            onOpenTablePicker()
        },
        onClearForm = viewModel::clearForm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReservationsScreen(
    title: String,
    state: ReservationsUiState,
    selectTableLabel: String,
    noTableSelectedLabel: String,
    onPreviousDay: () -> Unit,
    onToday: () -> Unit,
    onNextDay: () -> Unit,
    onCalendarDateSelected: (LocalDate) -> Unit,
    onDateInputChange: (String) -> Unit,
    onApplyDateInput: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTableFilterChange: (Int?) -> Unit,
    onUnassignedFilterChange: (Boolean) -> Unit,
    onQueueFilterChange: (ReservationQueueFilter) -> Unit,
    onSortModeChange: (ReservationSortMode) -> Unit,
    onTimeWindowFilterChange: (ReservationTimeWindow) -> Unit,
    onOpenNewReservation: () -> Unit,
    onOpenSlotWorkbench: (ReservationPulseSlotSelection) -> Unit,
    onCloseWizard: () -> Unit,
    onCloseSlotWorkbench: () -> Unit,
    onWizardStepChange: (ReservationWizardStep) -> Unit,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onCustomerEmailChange: (String) -> Unit,
    onAllergiesChange: (String) -> Unit,
    onStatusChange: (ReservationStatus) -> Unit,
    onPersonsChange: (String) -> Unit,
    onAdjustPersons: (Int) -> Unit,
    onOpenTablePicker: () -> Unit,
    onClearSelectedTable: () -> Unit,
    onStartTimeChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onEdit: (BackendReservation) -> Unit,
    onLoadReservationIntoSlotWorkbench: (BackendReservation) -> Unit,
    onStatusUpdate: (BackendReservation, ReservationStatus) -> Unit,
    onAssignTable: (BackendReservation) -> Unit,
    onClearForm: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val visibleReservations = remember(
        state.reservations,
        state.tables,
        state.searchQuery,
        state.tableFilterId,
        state.unassignedOnly,
        state.queueFilter,
        state.sortMode,
        state.timeWindowFilter,
    ) {
        filteredReservations(state)
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedDate.toDatePickerMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis
                            ?.let { onCalendarDateSelected(datePickerMillisToLocalDate(it)) }
                        showDatePicker = false
                    },
                ) {
                    Text("Valitse")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Peruuta")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    state.slotWorkbenchSelection?.let { selection ->
        ReservationSlotWorkbenchDialog(
            state = state,
            selection = selection,
            reservations = reservationsForPulseSelection(state, selection),
            onDismiss = onCloseSlotWorkbench,
            onCustomerNameChange = onCustomerNameChange,
            onCustomerPhoneChange = onCustomerPhoneChange,
            onCustomerEmailChange = onCustomerEmailChange,
            onAllergiesChange = onAllergiesChange,
            onAdjustPersons = onAdjustPersons,
            onOpenTablePicker = onOpenTablePicker,
            onClearSelectedTable = onClearSelectedTable,
            onDurationMinutesChange = onDurationMinutesChange,
            onNotesChange = onNotesChange,
            onSubmit = onSubmit,
            onSelectReservation = onLoadReservationIntoSlotWorkbench,
        )
    }

    if (state.wizardOpen) {
        ReservationWizardDialog(
            state = state,
            selectTableLabel = selectTableLabel,
            noTableSelectedLabel = noTableSelectedLabel,
            onDismiss = onCloseWizard,
            onStepChange = onWizardStepChange,
            onCalendarClick = { showDatePicker = true },
            onCustomerNameChange = onCustomerNameChange,
            onCustomerPhoneChange = onCustomerPhoneChange,
            onCustomerEmailChange = onCustomerEmailChange,
            onAllergiesChange = onAllergiesChange,
            onStatusChange = onStatusChange,
            onPersonsChange = onPersonsChange,
            onAdjustPersons = onAdjustPersons,
            onOpenTablePicker = onOpenTablePicker,
            onClearSelectedTable = onClearSelectedTable,
            onStartTimeChange = onStartTimeChange,
            onDurationMinutesChange = onDurationMinutesChange,
            onEndTimeChange = onEndTimeChange,
            onNotesChange = onNotesChange,
            onSubmit = onSubmit,
            onCloseEdit = onCloseWizard,
        )
    }

    var searchExpanded by remember { mutableStateOf(state.searchQuery.isNotBlank()) }
    var transientFeedback by remember { mutableStateOf<ReservationFeedback?>(null) }
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isNotBlank()) {
            searchExpanded = true
        }
    }
    LaunchedEffect(state.message, state.error) {
        val feedback = when {
            state.error != null -> ReservationFeedback(text = state.error, isError = true)
            state.message != null -> ReservationFeedback(text = state.message, isError = false)
            else -> null
        }
        feedback?.let { next ->
            transientFeedback = next
            delay(if (next.isError) 4200L else 2600L)
            if (transientFeedback == next) {
                transientFeedback = null
            }
        }
    }

    PosPane(
        title = title,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReservationPulsePanel(
                    state = state,
                    selectedSlot = state.slotWorkbenchSelection,
                    onSelectDate = onCalendarDateSelected,
                    onCalendarClick = { showDatePicker = true },
                    onSlotClick = onOpenSlotWorkbench,
                )

                val inboxSections = remember(visibleReservations) { buildReservationWorkQueue(visibleReservations) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ReservationFilterRail(
                        state = state,
                        onQueueFilterChange = onQueueFilterChange,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.isLoading) {
                                    "Ladataan varauksia..."
                                } else {
                                    "Työjono ${inboxSections.activeCount} / ${state.reservations.size}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (searchExpanded) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    label = { Text("Haku") },
                                    singleLine = true,
                                    modifier = Modifier.width(230.dp),
                                )
                                TextButton(
                                    onClick = {
                                        onSearchQueryChange("")
                                        searchExpanded = false
                                    },
                                ) {
                                    Text("Sulje haku")
                                }
                            } else {
                                OutlinedButton(onClick = { searchExpanded = true }) {
                                    Icon(imageVector = Icons.Filled.Search, contentDescription = "Haku")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Haku")
                                }
                            }
                            Button(onClick = onOpenNewReservation) { Text("+ Uusi") }
                        }
                        ReservationInbox(
                            state = state,
                            reservations = visibleReservations,
                            onEdit = onEdit,
                            onLoadReservationIntoSlotWorkbench = onLoadReservationIntoSlotWorkbench,
                            onStatusUpdate = onStatusUpdate,
                            onAssignTable = onAssignTable,
                        )
                    }
                    ReservationSortRail(
                        selected = state.sortMode,
                        onSelect = onSortModeChange,
                    )
                }
            }

            transientFeedback?.let { feedback ->
                ReservationTransientToast(
                    feedback = feedback,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                )
            }
        }
    }

}

private data class ReservationFeedback(
    val text: String,
    val isError: Boolean,
)

@Composable
private fun ReservationTransientToast(
    feedback: ReservationFeedback,
    modifier: Modifier = Modifier,
) {
    val tint = if (feedback.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.52f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.airos_logo),
                contentDescription = "AIROS",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .size(36.dp),
            )
            Text(
                text = feedback.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatPulseCompactDate(date: LocalDate): String {
    val locale = Locale("fi", "FI")
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$weekday ${date.dayOfMonth}.${date.monthValue}."
}

@Composable
private fun ReservationWeekStrip(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCalendarClick: () -> Unit,
) {
    val weekStart = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
    val locale = Locale("fi", "FI")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPreviousWeek) { Text("<") }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(7) { index ->
                val date = weekStart.plusDays(index.toLong())
                val selected = date == selectedDate
                Surface(
                    modifier = Modifier
                        .width(92.dp)
                        .clickable { onSelectDate(date) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(2)
                                .replaceFirstChar { it.uppercase(locale) },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${date.dayOfMonth}.${date.monthValue}.",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Button(onClick = onCalendarClick) {
            Icon(imageVector = Icons.Filled.DateRange, contentDescription = "Kalenteri")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Kalenteri")
        }
        OutlinedButton(onClick = onNextWeek) { Text(">") }
    }
}

@Composable
private fun ReservationPulsePanel(
    state: ReservationsUiState,
    selectedSlot: ReservationPulseSlotSelection?,
    onSelectDate: (LocalDate) -> Unit,
    onCalendarClick: () -> Unit,
    onSlotClick: (ReservationPulseSlotSelection) -> Unit,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = LocalDateTime.now()
        }
    }
    val rows = remember(state.selectedDate, state.pulseReservations) {
        buildLocalReservationPulseRows(
            selectedDate = state.selectedDate,
            reservations = state.pulseReservations,
        )
    }
    val selectedIndex = rows.indexOfFirst { it.date == state.selectedDate }
        .takeIf { it >= 0 }
        ?: RESERVATION_PULSE_PAST_DAYS
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val stripScrollState = rememberScrollState()
    val todayIndex = rows.indexOfFirst { it.date == now.toLocalDate() }
    val slotWidth = 38.dp
    val stripWidth = (ReservationPulseSlotStarts.size * 40).dp
    LaunchedEffect(state.selectedDate, rows.size) {
        rows.indexOfFirst { it.date == state.selectedDate }
            .takeIf { it >= 0 }
            ?.let { index ->
                listState.animateScrollToItem(index)
            }
    }
    var todayBarrierLocked by remember { mutableStateOf(false) }
    LaunchedEffect(todayBarrierLocked) {
        if (todayBarrierLocked) {
            delay(220L)
            todayBarrierLocked = false
        }
    }
    LaunchedEffect(listState, todayIndex) {
        if (todayIndex < 0) return@LaunchedEffect
        var wasScrolling = false
        var gestureStartedFutureSide = false
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.layoutInfo.visibleItemsInfo.map { it.index },
            )
        }.collectLatest { (isScrollInProgress, firstVisibleItemIndex, visibleIndices) ->
            val firstVisible = visibleIndices.minOrNull() ?: firstVisibleItemIndex

            if (isScrollInProgress && !wasScrolling) {
                gestureStartedFutureSide = firstVisible > todayIndex
            }

            if (
                isScrollInProgress &&
                gestureStartedFutureSide &&
                !todayBarrierLocked &&
                (todayIndex in visibleIndices || firstVisible <= todayIndex)
            ) {
                todayBarrierLocked = true
                listState.scrollToItem(todayIndex)
                gestureStartedFutureSide = false
            }

            if (!isScrollInProgress && wasScrolling) {
                gestureStartedFutureSide = false
            }

            wasScrolling = isScrollInProgress
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0B1719),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCalendarClick,
                    modifier = Modifier
                        .width(96.dp)
                        .height(38.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = "Kalenteri",
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatPulseCompactDate(state.selectedDate),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(stripScrollState),
                ) {
                    Row(
                        modifier = Modifier.width(stripWidth),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ReservationPulseSlotStarts.forEach { slot ->
                            Box(
                                modifier = Modifier.width(slotWidth),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (slot.minute == 0 && slot.hour in ReservationPulseHourMarkers) {
                                    Text(
                                        text = slot.hour.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                userScrollEnabled = !todayBarrierLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(258.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(rows, key = { it.date }) { row ->
                    ReservationPulseDayRow(
                        row = row,
                        selected = row.date == state.selectedDate,
                        selectedSlot = selectedSlot,
                        stripWidth = stripWidth,
                        slotWidth = slotWidth,
                        stripScrollState = stripScrollState,
                        now = now,
                        onSelectDate = onSelectDate,
                        onSlotClick = onSlotClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReservationPulseDayRow(
    row: ReservationPulseRow,
    selected: Boolean,
    selectedSlot: ReservationPulseSlotSelection?,
    stripWidth: androidx.compose.ui.unit.Dp,
    slotWidth: androidx.compose.ui.unit.Dp,
    stripScrollState: androidx.compose.foundation.ScrollState,
    now: LocalDateTime,
    onSelectDate: (LocalDate) -> Unit,
    onSlotClick: (ReservationPulseSlotSelection) -> Unit,
) {
    val locale = Locale("fi", "FI")
    val dayIsPast = row.date.isBefore(now.toLocalDate())
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .width(96.dp)
                .height(38.dp)
                .clickable { onSelectDate(row.date) },
            shape = RoundedCornerShape(999.dp),
            color = when {
                selected -> Color(0xFF143D35)
                dayIsPast -> Color(0xFF151A1C)
                else -> Color(0xFF102123)
            },
            border = when {
                selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                dayIsPast -> BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                else -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(2)
                        .replaceFirstChar { it.uppercase(locale) },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        dayIsPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Text(
                    text = "${row.date.dayOfMonth}.${row.date.monthValue}.",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dayIsPast && !selected) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(stripScrollState),
        ) {
            Row(
                modifier = Modifier.width(stripWidth),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.slots.forEach { slot ->
                    val slotSelected = selectedSlot?.date == row.date && selectedSlot.start == slot.start
                    val slotIsPast = LocalDateTime.of(row.date, slot.end).isBefore(now) ||
                        LocalDateTime.of(row.date, slot.end).isEqual(now)
                    Surface(
                        modifier = Modifier
                            .width(slotWidth)
                            .height(34.dp)
                            .clickable {
                                onSlotClick(
                                    ReservationPulseSlotSelection(
                                        date = row.date,
                                        start = slot.start,
                                        end = slot.end,
                                    ),
                                )
                            },
                        shape = RoundedCornerShape(9.dp),
                        color = reservationPulseSlotDisplayColor(
                            score = slot.guestLoad,
                            maxScore = row.maxGuestLoad,
                            isPast = slotIsPast,
                        ),
                        border = if (slotSelected) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(
                                1.dp,
                                if (slotIsPast) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.05f),
                            )
                        },
                    ) {
                        if (slot.reservationCount > 0) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = slot.reservationCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (slotIsPast) {
                                        Color.White.copy(alpha = 0.46f)
                                    } else {
                                        Color.White.copy(alpha = 0.86f)
                                    },
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun reservationPulseSlotDisplayColor(
    score: Int,
    maxScore: Int,
    isPast: Boolean,
): Color {
    if (isPast) {
        return if (score <= 0) Color(0xFF14191B) else Color(0xFF293033)
    }
    return when {
        score <= 0 -> Color(0xFF142526)
        maxScore <= 4 -> Color(0xFF2B6B58)
        score < (maxScore * 0.45f).roundToInt().coerceAtLeast(1) -> Color(0xFF2E7664)
        score < (maxScore * 0.75f).roundToInt().coerceAtLeast(1) -> Color(0xFFC79B42)
        else -> Color(0xFFE05B5A)
    }
}

@Composable
private fun ReservationSlotWorkbenchDialog(
    state: ReservationsUiState,
    selection: ReservationPulseSlotSelection,
    reservations: List<BackendReservation>,
    onDismiss: () -> Unit,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onCustomerEmailChange: (String) -> Unit,
    onAllergiesChange: (String) -> Unit,
    onAdjustPersons: (Int) -> Unit,
    onOpenTablePicker: () -> Unit,
    onClearSelectedTable: () -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectReservation: (BackendReservation) -> Unit,
) {
    val form = state.form
    val editing = form.editingReservationId != null
    val headerDate = if (editing) state.selectedDate else selection.date
    val headerTime = if (editing) form.startTime else selection.start.format(TimeFormatter)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(if (reservations.isNotEmpty()) 1040.dp else 820.dp)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (editing) "Muokkaa varausta" else "Uusi varaus",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${formatSelectedDateFinnish(headerDate)} • klo $headerTime alkaen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Sulje") }
                }

                state.error?.let { error ->
                    ReservationTransientToast(
                        feedback = ReservationFeedback(text = error, isError = true),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ReservationSlotWorkbenchPartyCard(
                                persons = form.persons,
                                onAdjustPersons = onAdjustPersons,
                                modifier = Modifier.weight(0.8f),
                            )
                            ReservationSlotWorkbenchDurationCard(
                                durationMinutes = form.durationMinutes,
                                onDurationMinutesChange = onDurationMinutesChange,
                                modifier = Modifier.weight(1.2f),
                            )
                        }

                        ReservationSlotWorkbenchGuestSection(
                            form = form,
                            onCustomerNameChange = onCustomerNameChange,
                            onCustomerPhoneChange = onCustomerPhoneChange,
                            onCustomerEmailChange = onCustomerEmailChange,
                            onAllergiesChange = onAllergiesChange,
                            onNotesChange = onNotesChange,
                        )

                        ReservationSlotWorkbenchTableSection(
                            state = state,
                            onOpenTablePicker = onOpenTablePicker,
                            onClearSelectedTable = onClearSelectedTable,
                        )
                    }

                    if (reservations.isNotEmpty()) {
                        ReservationSlotWorkbenchExistingReservations(
                            selection = selection,
                            reservations = reservations,
                            state = state,
                            onSelectReservation = onSelectReservation,
                            modifier = Modifier.width(300.dp).heightIn(max = 430.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onDismiss, enabled = !state.isSaving) {
                        Text("Peruuta")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(onClick = onSubmit, enabled = !state.isSaving) {
                        Text(if (editing) "Tallenna muutokset" else "Tallenna varaus")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReservationSlotWorkbenchExistingReservations(
    selection: ReservationPulseSlotSelection,
    reservations: List<BackendReservation>,
    state: ReservationsUiState,
    onSelectReservation: (BackendReservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalGuests = reservations.sumOf { it.persons.coerceAtLeast(1) }
    val unassignedCount = reservations.count { reservationIsUnassigned(it) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101F22),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Tässä slotissa ${selection.start.format(TimeFormatter)}-${selection.end.format(TimeFormatter)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${reservations.size} varausta • $totalGuests hlö • $unassignedCount ilman pöytää",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reservations.forEach { reservation ->
                ReservationSlotWorkbenchReservationRow(
                    reservation = reservation,
                    tableLabel = reservationTableLabel(reservation, state.tables),
                    selected = reservation.id == state.form.editingReservationId,
                    onClick = { onSelectReservation(reservation) },
                )
            }
        }
    }
}

@Composable
private fun ReservationSlotWorkbenchReservationRow(
    reservation: BackendReservation,
    tableLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val status = remember(reservation.notes) { splitReservationNotes(reservation.notes.orEmpty()).status }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatReservationInterval(reservation),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = tableLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (reservationIsUnassigned(reservation)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReservationStatusChip(status)
            }
            Text(
                text = "${reservation.persons} hlö • ${reservation.customerName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReservationSlotWorkbenchPartyCard(
    persons: String,
    onAdjustPersons: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Henkilömäärä",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { onAdjustPersons(-1) }) { Text("−") }
                Text(
                    text = persons.ifBlank { "2" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 26.dp),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = { onAdjustPersons(1) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun ReservationSlotWorkbenchDurationCard(
    durationMinutes: String,
    onDurationMinutesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val quickDurationOptions = listOf(
        60 to "1 h",
        90 to "1 h 30",
        120 to "2 h",
        150 to "2 h 30",
    )
    val selectedDurationMinutes = durationMinutes.toIntOrNull()
    val longDurationMinutes = selectedDurationMinutes
        ?.takeIf { it >= 180 }
        ?: 180

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Kesto",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickDurationOptions.forEach { (minutes, label) ->
                    ReservationChip(
                        text = label,
                        selected = durationMinutes == minutes.toString(),
                        onClick = { onDurationMinutesChange(minutes.toString()) },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val nextMinutes = (longDurationMinutes - 30).coerceAtLeast(180)
                            onDurationMinutesChange(nextMinutes.toString())
                        },
                        enabled = longDurationMinutes > 180,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text("−")
                    }
                    ReservationChip(
                        text = formatReservationDuration(longDurationMinutes),
                        selected = selectedDurationMinutes != null && selectedDurationMinutes >= 180,
                        onClick = { onDurationMinutesChange(longDurationMinutes.toString()) },
                    )
                    OutlinedButton(
                        onClick = {
                            val nextMinutes = longDurationMinutes + 30
                            onDurationMinutesChange(nextMinutes.toString())
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text("+")
                    }
                }
            }
        }
    }
}

private fun formatReservationDuration(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) {
        "$hours h"
    } else {
        "$hours h ${remainder.toString().padStart(2, '0')}"
    }
}

@Composable
private fun ReservationSlotWorkbenchGuestSection(
    form: ReservationFormState,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onCustomerEmailChange: (String) -> Unit,
    onAllergiesChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Asiakastiedot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = form.customerName,
                    onValueChange = onCustomerNameChange,
                    label = { Text("Nimi *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.customerPhone,
                    onValueChange = onCustomerPhoneChange,
                    label = { Text("Puhelin (vapaaehtoinen)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    visualTransformation = FinnishPhoneVisualTransformation,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        moreOpen = !moreOpen
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                ) {
                    Text(if (moreOpen) "Piilota lisätiedot" else "Lisätiedot")
                }
            }
            if (moreOpen) {
                OutlinedTextField(
                    value = form.customerEmail,
                    onValueChange = onCustomerEmailChange,
                    label = { Text("Sähköposti (vapaaehtoinen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.allergies,
                    onValueChange = onAllergiesChange,
                    label = { Text("Allergiat (vapaaehtoinen)") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                    ),
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = onNotesChange,
                    label = { Text("Muistiinpanot (vapaaehtoinen)") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                    ),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReservationSlotWorkbenchTableSection(
    state: ReservationsUiState,
    onOpenTablePicker: () -> Unit,
    onClearSelectedTable: () -> Unit,
) {
    val form = state.form
    val noTableSelected = form.selectedTableId == null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pöytä",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = form.selectedTableLabel ?: "Ei pöytää",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (noTableSelected) {
                        "Voidaan tallentaa ilman pöytää ja kohdistaa myöhemmin."
                    } else {
                        "Pöydän voi vaihtaa vielä ennen tallennusta."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenTablePicker) { Text("Valitse pöytä") }
                if (noTableSelected) {
                    Button(onClick = onClearSelectedTable) { Text("Ei pöytää") }
                } else {
                    OutlinedButton(onClick = onClearSelectedTable) { Text("Ei pöytää") }
                }
            }
        }
    }
}

@Composable
private fun ReservationFilterBar(
    state: ReservationsUiState,
    onQueueFilterChange: (ReservationQueueFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReservationChip(
            text = ReservationQueueFilter.ALL.label,
            selected = state.queueFilter == ReservationQueueFilter.ALL,
            onClick = { onQueueFilterChange(ReservationQueueFilter.ALL) },
        )
        ReservationChip(
            text = ReservationQueueFilter.UPCOMING.label,
            selected = state.queueFilter == ReservationQueueFilter.UPCOMING,
            onClick = { onQueueFilterChange(ReservationQueueFilter.UPCOMING) },
        )
        ReservationChip(
            text = ReservationQueueFilter.ATTENTION.label,
            selected = state.queueFilter == ReservationQueueFilter.ATTENTION,
            onClick = { onQueueFilterChange(ReservationQueueFilter.ATTENTION) },
        )
        ReservationChip(
            text = ReservationQueueFilter.UNASSIGNED.label,
            selected = state.queueFilter == ReservationQueueFilter.UNASSIGNED,
            onClick = { onQueueFilterChange(ReservationQueueFilter.UNASSIGNED) },
        )
    }
}


@Composable
private fun ReservationFilterRail(
    state: ReservationsUiState,
    onQueueFilterChange: (ReservationQueueFilter) -> Unit,
) {
    Column(
        modifier = Modifier.width(112.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReservationQueueFilter.values().forEach { filter ->
            ReservationChip(
                text = filter.label,
                selected = state.queueFilter == filter,
                onClick = { onQueueFilterChange(filter) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReservationTabRow(
    selectedTab: ReservationsViewTab,
    onSelect: (ReservationsViewTab) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReservationChip(
            text = "Inbox",
            selected = selectedTab == ReservationsViewTab.INBOX,
            onClick = { onSelect(ReservationsViewTab.INBOX) },
        )
        ReservationChip(
            text = "Aikajana",
            selected = selectedTab == ReservationsViewTab.TIMELINE,
            onClick = { onSelect(ReservationsViewTab.TIMELINE) },
        )
    }
}

@Composable
private fun ReservationChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReservationInbox(
    state: ReservationsUiState,
    reservations: List<BackendReservation>,
    onEdit: (BackendReservation) -> Unit,
    onLoadReservationIntoSlotWorkbench: (BackendReservation) -> Unit,
    onStatusUpdate: (BackendReservation, ReservationStatus) -> Unit,
    onAssignTable: (BackendReservation) -> Unit,
) {
    var selectedReservation by remember { mutableStateOf<BackendReservation?>(null) }
    var pastExpanded by remember(state.selectedDate, state.searchQuery, state.queueFilter) {
        mutableStateOf(false)
    }
    val sections = remember(reservations) { buildReservationWorkQueue(reservations) }
    selectedReservation?.let { reservation ->
        val tableLabel = reservationTableLabel(reservation, state.tables)
        ReservationActionSheet(
            reservation = reservation,
            tableLabel = tableLabel,
            onDismiss = { selectedReservation = null },
            onEdit = {
                selectedReservation = null
                onEdit(reservation)
            },
            onStatusUpdate = { status ->
                selectedReservation = null
                onStatusUpdate(reservation, status)
            },
            onAssignTable = {
                selectedReservation = null
                onAssignTable(reservation)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.isLoading && reservations.isEmpty()) {
            EmptyReservationList()
        }
        val activeReservations = sections.attention + sections.unassigned + sections.upcoming
        activeReservations.forEach { reservation ->
            ReservationCompactRow(
                reservation = reservation,
                tableLabel = reservationTableLabel(reservation, state.tables),
                onClick = { selectedReservation = reservation },
                enabled = !state.isSaving,
            )
        }
        PastReservationsSection(
            reservations = sections.past,
            state = state,
            expanded = pastExpanded,
            enabled = !state.isSaving,
            onToggle = { pastExpanded = !pastExpanded },
            onSelect = { selectedReservation = it },
        )
        if (!state.isLoading && reservations.isNotEmpty() && sections.activeCount == 0 && sections.past.isEmpty()) {
            EmptyReservationList()
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ReservationSortBar(
    selected: ReservationSortMode,
    onSelect: (ReservationSortMode) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReservationSortMode.values().forEach { mode ->
            ReservationChip(
                text = mode.label,
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}


@Composable
private fun ReservationSortRail(
    selected: ReservationSortMode,
    onSelect: (ReservationSortMode) -> Unit,
) {
    Column(
        modifier = Modifier.width(88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReservationSortMode.values().forEach { mode ->
            ReservationChip(
                text = mode.label,
                selected = selected == mode,
                onClick = { onSelect(mode) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReservationWorkQueueSection(
    title: String,
    reservations: List<BackendReservation>,
    state: ReservationsUiState,
    enabled: Boolean,
    onSelect: (BackendReservation) -> Unit,
) {
    if (reservations.isEmpty()) return
    Text(
        text = "$title (${reservations.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    reservations.forEach { reservation ->
        ReservationCompactRow(
            reservation = reservation,
            tableLabel = reservationTableLabel(reservation, state.tables),
            onClick = { onSelect(reservation) },
            enabled = enabled,
        )
    }
}

@Composable
private fun PastReservationsSection(
    reservations: List<BackendReservation>,
    state: ReservationsUiState,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSelect: (BackendReservation) -> Unit,
) {
    if (reservations.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Menneet (${reservations.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (expanded) "Piilota" else "Näytä",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (expanded) {
        reservations.forEach { reservation ->
            ReservationCompactRow(
                reservation = reservation,
                tableLabel = reservationTableLabel(reservation, state.tables),
                onClick = { onSelect(reservation) },
                enabled = enabled,
                compactMuted = true,
            )
        }
    }
}

@Composable
private fun ReservationTimeline(
    state: ReservationsUiState,
    reservations: List<BackendReservation>,
    onSlotClick: (LocalTime) -> Unit,
) {
    val slots = (11..22).map { LocalTime.of(it, 0) }
    val bands = listOf(
        "Kaikki" to { _: BackendReservation -> true },
        "1-2 hlö" to { reservation: BackendReservation -> reservation.persons <= 2 },
        "3-4 hlö" to { reservation: BackendReservation -> reservation.persons in 3..4 },
        "5+ hlö" to { reservation: BackendReservation -> reservation.persons >= 5 },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Aikajana ${formatSelectedDate(state.selectedDate)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(modifier = Modifier.height(38.dp))
                bands.forEach { (label, _) ->
                    Box(
                        modifier = Modifier
                            .width(86.dp)
                            .height(58.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            slots.forEach { slot ->
                Column(
                    modifier = Modifier.width(86.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = slot.format(TimeFormatter),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    bands.forEach { (_, accepts) ->
                        val count = reservations.count { reservation ->
                            accepts(reservation) && reservationStartsInSlot(reservation, slot)
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .clickable { onSlotClick(slot) },
                            shape = RoundedCornerShape(14.dp),
                            color = timelineHeatColor(count),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (count == 0) "+" else count.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (count >= 3) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyReservationList() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "Ei varauksia tälle päivälle.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReservationCompactRow(
    reservation: BackendReservation,
    tableLabel: String,
    onClick: () -> Unit,
    enabled: Boolean,
    compactMuted: Boolean = false,
) {
    val noteParts = remember(reservation.notes) { splitReservationNotes(reservation.notes.orEmpty()) }
    val status = noteParts.status
    val unassigned = reservationIsUnassigned(reservation)
    val urgent = reservationNeedsAttention(reservation, LocalDateTime.now())
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (compactMuted) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatReservationTime(reservation.startTime),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = tableLabel,
                modifier = Modifier.widthIn(min = 56.dp, max = 92.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (unassigned) FontWeight.Bold else FontWeight.SemiBold,
                color = if (unassigned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${reservation.persons} hlö",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = reservation.customerName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ReservationStatusChip(status)
            if (urgent) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.error,
                ) {
                    Text(
                        text = "!",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReservationActionSheet(
    reservation: BackendReservation,
    tableLabel: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onStatusUpdate: (ReservationStatus) -> Unit,
    onAssignTable: () -> Unit,
) {
    val noteParts = remember(reservation.notes) { splitReservationNotes(reservation.notes.orEmpty()) }
    val status = noteParts.status
    val unassigned = reservationIsUnassigned(reservation)
    val started = (parseReservationDateTime(reservation.startTime) ?: LocalDateTime.MAX) <= LocalDateTime.now()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 460.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${formatReservationTime(reservation.startTime)} • ${reservation.customerName}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "$tableLabel • ${reservation.persons} hlö",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReservationStatusChip(status)
                }
                noteParts.allergies.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Allergiat: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                noteParts.notes.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { onStatusUpdate(ReservationStatus.ARRIVED) },
                    enabled = status != ReservationStatus.ARRIVED && status != ReservationStatus.SEATED,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Saapunut") }
                Button(
                    onClick = { onStatusUpdate(ReservationStatus.SEATED) },
                    enabled = !unassigned && status != ReservationStatus.SEATED,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Istuta") }
                if (unassigned) {
                    Button(
                        onClick = onAssignTable,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Valitse pöytä") }
                } else {
                    OutlinedButton(
                        onClick = onAssignTable,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Vaihda pöytä") }
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Muokkaa") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onStatusUpdate(ReservationStatus.CANCELLED) },
                        enabled = status != ReservationStatus.CANCELLED,
                        modifier = Modifier.weight(1f),
                    ) { Text("Peru varaus") }
                    OutlinedButton(
                        onClick = { onStatusUpdate(ReservationStatus.NOSHOW) },
                        enabled = started && status != ReservationStatus.NOSHOW,
                        modifier = Modifier.weight(1f),
                    ) { Text("Ei saapunut") }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Sulje") }
            }
        }
    }
}

@Composable
private fun ReservationRow(
    reservation: BackendReservation,
    tableLabel: String,
    onEdit: () -> Unit,
    onStatusUpdate: (BackendReservation, ReservationStatus) -> Unit,
    onAssignTable: (BackendReservation) -> Unit,
    enabled: Boolean,
) {
    val noteParts = remember(reservation.notes) { splitReservationNotes(reservation.notes.orEmpty()) }
    val status = noteParts.status
    val unassigned = reservationIsUnassigned(reservation)
    val urgentUnassigned = unassigned && isReservationStartingSoon(reservation)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onEdit),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (urgentUnassigned) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.error,
                            ) {
                                Text(
                                    text = "!",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            }
                        }
                        Text(
                            text = reservation.customerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ReservationStatusChip(status)
                    }
                    Text(
                        text = "${formatReservationTime(reservation.startTime)}-${formatReservationTime(reservation.endTime)} | $tableLabel | ${reservation.persons} persons",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reservation.customerPhone?.let {
                        Text(
                            text = formatFinnishPhoneForDisplay(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    noteParts.email.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    noteParts.allergies.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "Allergies: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    noteParts.notes.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onEdit, enabled = enabled) { Text("Muokkaa") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onStatusUpdate(reservation, ReservationStatus.ARRIVED) },
                            enabled = enabled && status != ReservationStatus.ARRIVED,
                        ) { Text("Saapunut") }
                        TextButton(
                            onClick = { onStatusUpdate(reservation, ReservationStatus.SEATED) },
                            enabled = enabled && status != ReservationStatus.SEATED,
                        ) { Text("Istuta") }
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }, enabled = enabled) { Text("Lisää") }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Valitse pöytä") },
                                onClick = {
                                    menuOpen = false
                                    onAssignTable(reservation)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Peru varaus") },
                                onClick = {
                                    menuOpen = false
                                    onStatusUpdate(reservation, ReservationStatus.CANCELLED)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Ei saapunut") },
                                onClick = {
                                    menuOpen = false
                                    onStatusUpdate(reservation, ReservationStatus.NOSHOW)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReservationStatusChip(status: ReservationStatus) {
    val color = when (status) {
        ReservationStatus.BOOKED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        ReservationStatus.ARRIVED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)
        ReservationStatus.SEATED -> MaterialTheme.colorScheme.primary
        ReservationStatus.CANCELLED -> MaterialTheme.colorScheme.surface
        ReservationStatus.NOSHOW -> MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
    }
    val textColor = when (status) {
        ReservationStatus.SEATED -> MaterialTheme.colorScheme.onPrimary
        ReservationStatus.NOSHOW -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color,
    ) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
    }
}

@Composable
private fun ReservationTimeSelector(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(74.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AirosReservationTimePickerDialog(
    title: String,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    var selectedTime by remember(initialTime) { mutableStateOf(initialTime.withSecond(0).withNano(0)) }
    var activeHand by remember { mutableStateOf<AirosClockHand?>(null) }
    var keypadDigits by remember { mutableStateOf("") }

    fun setTime(next: LocalTime, clearKeypad: Boolean = true) {
        selectedTime = next.withSecond(0).withNano(0)
        if (clearKeypad) {
            keypadDigits = ""
        }
    }

    fun adjustMinutes(delta: Long) {
        setTime(selectedTime.plusMinutes(delta))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AirosClockFace(
                            time = selectedTime,
                            activeHand = activeHand,
                            onActiveHandChange = { activeHand = it },
                            onTimeChange = { setTime(it) },
                            modifier = Modifier.size(300.dp),
                        )
                        TimeRepeatAdjuster(
                            onForward = { adjustMinutes(1) },
                            onBackward = { adjustMinutes(-1) },
                        )
                    }

                    Column(
                        modifier = Modifier.width(150.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CompactTimeKeypad(
                            onDigit = { digit ->
                                val nextDigits = if (keypadDigits.length >= 4) digit else keypadDigits + digit
                                keypadDigits = nextDigits.take(4)
                                if (keypadDigits.length == 4) {
                                    setTime(timeFromKeypadDigits(keypadDigits), clearKeypad = false)
                                }
                            },
                            onBackspace = {
                                keypadDigits = keypadDigits.dropLast(1)
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Peruuta")
                    }
                    Button(onClick = { onConfirm(selectedTime) }) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

@Composable
private fun AirosClockFace(
    time: LocalTime,
    activeHand: AirosClockHand?,
    onActiveHandChange: (AirosClockHand?) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var clockSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingHand by remember { mutableStateOf<AirosClockHand?>(null) }
    val latestTime by rememberUpdatedState(time)

    fun chooseHand(offset: Offset): AirosClockHand {
        if (clockSize.width <= 0 || clockSize.height <= 0) return AirosClockHand.MINUTE
        val center = Offset(clockSize.width / 2f, clockSize.height / 2f)
        val dx = offset.x - center.x
        val dy = offset.y - center.y
        val radius = minOf(clockSize.width, clockSize.height) / 2f
        val innerThreshold = radius * 0.56f
        return if ((dx * dx + dy * dy) >= innerThreshold * innerThreshold) {
            AirosClockHand.MINUTE
        } else {
            AirosClockHand.HOUR
        }
    }

    fun selectFromOffset(offset: Offset, hand: AirosClockHand) {
        if (clockSize.width <= 0 || clockSize.height <= 0) return
        val center = Offset(clockSize.width / 2f, clockSize.height / 2f)
        val angle = normalizedClockAngleRadians(offset = offset, center = center)
        if (hand == AirosClockHand.HOUR) {
            val rawHour = ((angle / FullCircleRadians) * 12.0).roundToInt() % 12
            val hour12 = if (rawHour == 0) 12 else rawHour
            val currentTime = latestTime
            val nextHour = if (currentTime.hour >= 12) {
                if (hour12 == 12) 12 else hour12 + 12
            } else {
                if (hour12 == 12) 0 else hour12
            }
            onTimeChange(currentTime.withHour(nextHour))
        } else {
            val minute = ((angle / FullCircleRadians) * 60.0).roundToInt() % 60
            onTimeChange(latestTime.withMinute(minute))
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { clockSize = it }
                .pointerInput(clockSize) {
                    detectTapGestures(
                        onTap = { offset ->
                            val hand = chooseHand(offset)
                            onActiveHandChange(hand)
                            selectFromOffset(offset, hand)
                        },
                    )
                }
                .pointerInput(clockSize) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val hand = chooseHand(offset)
                            draggingHand = hand
                            onActiveHandChange(hand)
                            selectFromOffset(offset, hand)
                        },
                        onDrag = { change, _ ->
                            val hand = draggingHand ?: chooseHand(change.position)
                            selectFromOffset(change.position, hand)
                        },
                        onDragEnd = {
                            draggingHand = null
                            onActiveHandChange(null)
                        },
                        onDragCancel = {
                            draggingHand = null
                            onActiveHandChange(null)
                        },
                    )
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.42f
            drawCircle(
                color = outline,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
            repeat(12) { index ->
                val angle = FullCircleRadians * index / 12.0
                val tickCenter = Offset(
                    x = center.x + sin(angle).toFloat() * radius,
                    y = center.y - cos(angle).toFloat() * radius,
                )
                drawCircle(
                    color = muted,
                    radius = if (index % 3 == 0) 5.dp.toPx() else 3.dp.toPx(),
                    center = tickCenter,
                )
            }

            val hourAngle = FullCircleRadians * ((time.hour % 12) + time.minute / 60.0) / 12.0
            val minuteAngle = FullCircleRadians * time.minute / 60.0
            val hourEnd = Offset(
                x = center.x + sin(hourAngle).toFloat() * radius * 0.48f,
                y = center.y - cos(hourAngle).toFloat() * radius * 0.48f,
            )
            val minuteEnd = Offset(
                x = center.x + sin(minuteAngle).toFloat() * radius * 0.82f,
                y = center.y - cos(minuteAngle).toFloat() * radius * 0.82f,
            )
            drawLine(
                color = accent,
                start = center,
                end = hourEnd,
                strokeWidth = if (activeHand == AirosClockHand.HOUR) 8.dp.toPx() else 6.dp.toPx(),
            )
            drawLine(
                color = accent.copy(alpha = if (activeHand == AirosClockHand.MINUTE) 1f else 0.78f),
                start = center,
                end = minuteEnd,
                strokeWidth = if (activeHand == AirosClockHand.MINUTE) 5.dp.toPx() else 3.dp.toPx(),
            )
            drawCircle(color = accent, radius = 7.dp.toPx(), center = hourEnd)
            drawCircle(color = accent, radius = 6.dp.toPx(), center = minuteEnd)
            drawCircle(color = accent, radius = 4.dp.toPx(), center = center)
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = time.format(TimeFormatter),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TimeRepeatAdjuster(
    onForward: () -> Unit,
    onBackward: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RepeatingArrowButton(text = "▲", onStep = onForward)
            RepeatingArrowButton(text = "▼", onStep = onBackward)
        }
    }
}

@Composable
private fun RepeatingArrowButton(
    text: String,
    onStep: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val latestStep by rememberUpdatedState(onStep)

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        var delayMillis = 360L
        delay(delayMillis)
        while (pressed) {
            latestStep()
            delayMillis = (delayMillis * 0.78).toLong().coerceAtLeast(55L)
            delay(delayMillis)
        }
    }

    Surface(
        modifier = Modifier
            .width(58.dp)
            .height(78.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        latestStep()
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = if (pressed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (pressed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun CompactTimeKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "Del"),
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key ->
                    if (key.isBlank()) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable {
                                    if (key == "Del") {
                                        onBackspace()
                                    } else {
                                        onDigit(key)
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReservationWizardDialog(
    state: ReservationsUiState,
    selectTableLabel: String,
    noTableSelectedLabel: String,
    onDismiss: () -> Unit,
    onStepChange: (ReservationWizardStep) -> Unit,
    onCalendarClick: () -> Unit,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onCustomerEmailChange: (String) -> Unit,
    onAllergiesChange: (String) -> Unit,
    onStatusChange: (ReservationStatus) -> Unit,
    onPersonsChange: (String) -> Unit,
    onAdjustPersons: (Int) -> Unit,
    onOpenTablePicker: () -> Unit,
    onClearSelectedTable: () -> Unit,
    onStartTimeChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCloseEdit: () -> Unit,
) {
    val form = state.form
    val editing = form.editingReservationId != null
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = if (editing) "Muokkaa varausta" else "Uusi varaus",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = formatSelectedDate(state.selectedDate),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Sulje") }
                }

                ReservationWizardSteps(
                    current = state.wizardStep,
                    onStepChange = onStepChange,
                )

                state.error?.let { error ->
                    ReservationTransientToast(
                        feedback = ReservationFeedback(text = error, isError = true),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                when (state.wizardStep) {
                    ReservationWizardStep.DATE_TIME -> ReservationWizardDateTimeStep(
                        state = state,
                        onCalendarClick = onCalendarClick,
                        onStartTimeChange = onStartTimeChange,
                        onDurationMinutesChange = onDurationMinutesChange,
                        onEndTimeChange = onEndTimeChange,
                    )
                    ReservationWizardStep.PARTY -> ReservationWizardPartyStep(
                        persons = form.persons,
                        onPersonsChange = onPersonsChange,
                        onAdjustPersons = onAdjustPersons,
                    )
                    ReservationWizardStep.GUEST -> ReservationWizardGuestStep(
                        form = form,
                        onCustomerNameChange = onCustomerNameChange,
                        onCustomerPhoneChange = onCustomerPhoneChange,
                        onCustomerEmailChange = onCustomerEmailChange,
                        onAllergiesChange = onAllergiesChange,
                        onStatusChange = onStatusChange,
                        onNotesChange = onNotesChange,
                    )
                    ReservationWizardStep.TABLE -> ReservationWizardTableStep(
                        state = state,
                        selectTableLabel = selectTableLabel,
                        noTableSelectedLabel = noTableSelectedLabel,
                        onOpenTablePicker = onOpenTablePicker,
                        onClearSelectedTable = onClearSelectedTable,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            OutlinedButton(onClick = onCloseEdit, enabled = !state.isSaving) {
                                Text("Sulje")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val previous = previousWizardStep(state.wizardStep)
                                if (previous == null) onDismiss() else onStepChange(previous)
                            },
                            enabled = !state.isSaving,
                        ) {
                            Text(if (state.wizardStep == ReservationWizardStep.DATE_TIME) "Peruuta" else "Takaisin")
                        }
                        if (state.wizardStep == ReservationWizardStep.TABLE) {
                            Button(
                                onClick = onSubmit,
                                enabled = !state.isSaving,
                            ) {
                                Text(if (editing) "Tallenna" else "Tallenna")
                            }
                        } else {
                            Button(
                                onClick = { onStepChange(nextWizardStep(state.wizardStep)) },
                                enabled = !state.isSaving,
                            ) {
                                Text("Seuraava")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReservationWizardSteps(
    current: ReservationWizardStep,
    onStepChange: (ReservationWizardStep) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            ReservationWizardStep.DATE_TIME to "1 Date",
            ReservationWizardStep.PARTY to "2 Party",
            ReservationWizardStep.GUEST to "3 Guest",
            ReservationWizardStep.TABLE to "4 Table",
        ).forEach { (step, label) ->
            ReservationChip(
                text = label,
                selected = current == step,
                onClick = { onStepChange(step) },
            )
        }
    }
}

@Composable
private fun ReservationWizardDateTimeStep(
    state: ReservationsUiState,
    onCalendarClick: () -> Unit,
    onStartTimeChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
) {
    val form = state.form
    var timePickerField by remember { mutableStateOf<ReservationTimeField?>(null) }
    timePickerField?.let { field ->
        AirosReservationTimePickerDialog(
            title = if (field == ReservationTimeField.START) "Aloitusaika" else "Päättymisaika",
            initialTime = when (field) {
                ReservationTimeField.START -> parseFormTime(form.startTime) ?: LocalTime.of(18, 0)
                ReservationTimeField.END -> parseFormTime(form.endTime) ?: LocalTime.of(20, 0)
            },
            onDismiss = { timePickerField = null },
            onConfirm = { selectedTime ->
                val value = selectedTime.format(TimeFormatter)
                if (field == ReservationTimeField.START) {
                    onStartTimeChange(value)
                } else {
                    onEndTimeChange(value)
                }
                timePickerField = null
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Päivä", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = formatSelectedDate(state.selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(onClick = onCalendarClick) {
                    Icon(imageVector = Icons.Filled.DateRange, contentDescription = "Kalenteri")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kalenteri")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReservationTimeSelector(
                label = "Aloitusaika",
                value = form.startTime,
                onClick = { timePickerField = ReservationTimeField.START },
                modifier = Modifier.weight(1f),
            )
            ReservationTimeSelector(
                label = "Päättymisaika",
                value = form.endTime,
                onClick = { timePickerField = ReservationTimeField.END },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("60", "90", "120").forEach { minutes ->
                ReservationChip(
                    text = "$minutes min",
                    selected = form.durationMinutes == minutes,
                    onClick = { onDurationMinutesChange(minutes) },
                )
            }
            OutlinedTextField(
                value = form.durationMinutes,
                onValueChange = onDurationMinutesChange,
                label = { Text("Omat min") },
                singleLine = true,
                modifier = Modifier.width(150.dp),
            )
        }
    }
}

@Composable
private fun ReservationWizardPartyStep(
    persons: String,
    onPersonsChange: (String) -> Unit,
    onAdjustPersons: (Int) -> Unit,
    title: String = "Party size",
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("2", "3", "4").forEach { size ->
                ReservationChip(
                    text = "$size hlö",
                    selected = persons == size,
                    onClick = { onPersonsChange(size) },
                )
            }
            OutlinedButton(onClick = { onAdjustPersons(-1) }) { Text("-") }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = persons.ifBlank { "0" },
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(onClick = { onAdjustPersons(1) }) { Text("+") }
        }
    }
}

@Composable
private fun ReservationWizardGuestStep(
    form: ReservationFormState,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onCustomerEmailChange: (String) -> Unit,
    onAllergiesChange: (String) -> Unit,
    onStatusChange: (ReservationStatus) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = form.customerName,
            onValueChange = onCustomerNameChange,
            label = { Text("Nimi *") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.customerPhone,
            onValueChange = onCustomerPhoneChange,
            label = { Text("Puhelin, vapaaehtoinen") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = FinnishPhoneVisualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReservationStatus.values().forEach { status ->
                ReservationChip(
                    text = status.label,
                    selected = form.status == status,
                    onClick = { onStatusChange(status) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { moreOpen = !moreOpen }) {
                Text(if (moreOpen) "Piilota lisätiedot" else "Lisätiedot")
            }
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
            ) {
                Text("Valmis")
            }
        }
        if (moreOpen) {
            OutlinedTextField(
                value = form.customerEmail,
                onValueChange = onCustomerEmailChange,
                label = { Text("Sähköposti, vapaaehtoinen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.allergies,
                onValueChange = onAllergiesChange,
                label = { Text("Allergiat, vapaaehtoinen") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.notes,
                onValueChange = onNotesChange,
                label = { Text("Muistiinpanot, vapaaehtoinen") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReservationWizardTableStep(
    state: ReservationsUiState,
    selectTableLabel: String,
    noTableSelectedLabel: String,
    onOpenTablePicker: () -> Unit,
    onClearSelectedTable: () -> Unit,
) {
    val form = state.form
    val selectedAvailability = state.tables
        .firstOrNull { it.backendTableId == form.selectedTableId }
        ?.availability(state)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Suggested table: (none for now)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(selectTableLabel, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = form.selectedTableLabel ?: "Pöytä: Ei valittu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (form.selectedTableId != null && selectedAvailability?.isSelectable == false) {
                        Text(
                            text = selectedAvailability.reason.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenTablePicker) { Text("Valitse pöytä") }
                    OutlinedButton(
                        onClick = onClearSelectedTable,
                        enabled = form.selectedTableId != null,
                    ) {
                        Text("Poista pöytä")
                    }
                }
            }
        }
        if (form.selectedTableId == null) {
            StatusBanner(
                text = "Ei pöytää: varaus tallennetaan ilman pöytää ja voidaan kohdistaa myöhemmin.",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReservationForm(
    state: ReservationsUiState,
    modifier: Modifier,
    selectTableLabel: String,
    noTableSelectedLabel: String,
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onPersonsChange: (String) -> Unit,
    onOpenTablePicker: () -> Unit,
    onStartTimeChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearForm: () -> Unit,
) {
    val form = state.form
    val editing = form.editingReservationId != null
    val selectedAvailability = state.tables
        .firstOrNull { it.backendTableId == form.selectedTableId }
        ?.availability(state)
    var timePickerField by remember { mutableStateOf<ReservationTimeField?>(null) }

    timePickerField?.let { field ->
        AirosReservationTimePickerDialog(
            title = if (field == ReservationTimeField.START) "Aloitusaika" else "Päättymisaika",
            initialTime = when (field) {
                ReservationTimeField.START -> parseFormTime(form.startTime) ?: LocalTime.of(18, 0)
                ReservationTimeField.END -> parseFormTime(form.endTime) ?: LocalTime.of(20, 0)
            },
            onDismiss = { timePickerField = null },
            onConfirm = { selectedTime ->
                val value = selectedTime.format(TimeFormatter)
                if (field == ReservationTimeField.START) {
                    onStartTimeChange(value)
                } else {
                    onEndTimeChange(value)
                }
                timePickerField = null
            },
        )
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (editing) "Muokkaa varausta" else "Uusi varaus",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = form.customerName,
            onValueChange = onCustomerNameChange,
            label = { Text("Asiakkaan nimi") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.customerPhone,
            onValueChange = onCustomerPhoneChange,
            label = { Text("Asiakkaan puhelin") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = FinnishPhoneVisualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.persons,
                onValueChange = onPersonsChange,
                label = { Text("Henkilöä") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReservationTimeSelector(
                label = "Aloitusaika",
                value = form.startTime,
                onClick = { timePickerField = ReservationTimeField.START },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.durationMinutes,
                onValueChange = onDurationMinutesChange,
                label = { Text("Kesto min") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ReservationTimeSelector(
                label = "Päättymisaika",
                value = form.endTime,
                onClick = { timePickerField = ReservationTimeField.END },
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = selectTableLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = form.selectedTableLabel ?: noTableSelectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (form.selectedTableId != null && selectedAvailability?.isSelectable == false) {
                        Text(
                            text = selectedAvailability.reason.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenTablePicker) {
                    Text(selectTableLabel)
                }
            }
        }
        OutlinedTextField(
            value = form.notes,
            onValueChange = onNotesChange,
            label = { Text("Muistiinpanot") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
            ),
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onSubmit,
                enabled = !state.isSaving && selectedAvailability?.isSelectable == true,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (editing) "Tallenna muutokset" else "Luo varaus")
            }
            OutlinedButton(
                onClick = onClearForm,
                enabled = !state.isSaving,
            ) {
                Text("Tyhjennä")
            }
        }
    }
}

private fun previousWizardStep(step: ReservationWizardStep): ReservationWizardStep? {
    return when (step) {
        ReservationWizardStep.DATE_TIME -> null
        ReservationWizardStep.PARTY -> ReservationWizardStep.DATE_TIME
        ReservationWizardStep.GUEST -> ReservationWizardStep.PARTY
        ReservationWizardStep.TABLE -> ReservationWizardStep.GUEST
    }
}

private fun nextWizardStep(step: ReservationWizardStep): ReservationWizardStep {
    return when (step) {
        ReservationWizardStep.DATE_TIME -> ReservationWizardStep.PARTY
        ReservationWizardStep.PARTY -> ReservationWizardStep.GUEST
        ReservationWizardStep.GUEST -> ReservationWizardStep.TABLE
        ReservationWizardStep.TABLE -> ReservationWizardStep.TABLE
    }
}

private data class ReservationWorkQueueSections(
    val attention: List<BackendReservation>,
    val unassigned: List<BackendReservation>,
    val upcoming: List<BackendReservation>,
    val past: List<BackendReservation>,
) {
    val activeCount: Int = attention.size + unassigned.size + upcoming.size
}

private data class ReservationPulseRow(
    val date: LocalDate,
    val slots: List<ReservationPulseSlot>,
) {
    val maxGuestLoad: Int = slots.maxOfOrNull { it.guestLoad } ?: 0
}

private data class ReservationPulseSlot(
    val start: LocalTime,
    val end: LocalTime,
    val reservationCount: Int,
    val guestLoad: Int,
)

private data class ReservationPulseSlotSelection(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
)

private fun buildLocalReservationPulseRows(
    selectedDate: LocalDate,
    reservations: List<BackendReservation>,
): List<ReservationPulseRow> {
    val activeReservations = reservations.filter { reservation ->
        val status = splitReservationNotes(reservation.notes.orEmpty()).status
        status != ReservationStatus.CANCELLED && status != ReservationStatus.NOSHOW
    }
    return reservationPulseWindowDates(selectedDate).map { date ->
        val slotReservationsByStart = ReservationPulseSlotStarts.associateWith { mutableListOf<BackendReservation>() }
        activeReservations.forEach { reservation ->
            pulseCoveredSlotStarts(reservation, date).forEach { slotStart ->
                slotReservationsByStart.getValue(slotStart).add(reservation)
            }
        }
        ReservationPulseRow(
            date = date,
            slots = ReservationPulseSlotStarts.map { slotStart ->
                val slotReservations = slotReservationsByStart.getValue(slotStart)
                ReservationPulseSlot(
                    start = slotStart,
                    end = slotStart.plusMinutes(RESERVATION_PULSE_SLOT_MINUTES),
                    reservationCount = slotReservations.size,
                    guestLoad = slotReservations.sumOf { it.persons.coerceAtLeast(1) },
                )
            },
        )
    }
}

private fun pulseCoveredSlotStarts(
    reservation: BackendReservation,
    date: LocalDate,
): List<LocalTime> {
    return ReservationPulseSlotStarts.filter { slotStart ->
        reservationOverlapsPulseSlot(
            reservation = reservation,
            date = date,
            slotStartTime = slotStart,
            slotEndTime = slotStart.plusMinutes(RESERVATION_PULSE_SLOT_MINUTES),
        )
    }
}

private fun reservationOverlapsPulseSlot(
    reservation: BackendReservation,
    date: LocalDate,
    slotStartTime: LocalTime,
    slotEndTime: LocalTime,
): Boolean {
    val start = parseReservationDateTime(reservation.startTime) ?: return false
    val end = parseReservationDateTime(reservation.endTime) ?: return false
    val slotStart = LocalDateTime.of(date, slotStartTime)
    val slotEnd = LocalDateTime.of(date, slotEndTime)
    return start.isBefore(slotEnd) && end.isAfter(slotStart)
}

private fun reservationPulseWindowStart(selectedDate: LocalDate): LocalDate {
    val today = LocalDate.now()
    val defaultStart = today.minusDays(RESERVATION_PULSE_PAST_DAYS.toLong())
    val defaultEnd = today.plusDays(RESERVATION_PULSE_FUTURE_DAYS.toLong())
    return if (selectedDate.isBefore(defaultStart) || selectedDate.isAfter(defaultEnd)) {
        selectedDate.minusDays(RESERVATION_PULSE_PAST_DAYS.toLong())
    } else {
        defaultStart
    }
}

private fun reservationPulseWindowDates(selectedDate: LocalDate): List<LocalDate> {
    val start = reservationPulseWindowStart(selectedDate)
    return (0..(RESERVATION_PULSE_PAST_DAYS + RESERVATION_PULSE_FUTURE_DAYS)).map { offset ->
        start.plusDays(offset.toLong())
    }
}

private fun reservationPulseSelectionForReservation(
    reservation: BackendReservation,
): ReservationPulseSlotSelection? {
    val start = parseReservationDateTime(reservation.startTime) ?: return null
    val slotStart = LocalTime.of(
        start.hour,
        if (start.minute < 30) 0 else 30,
    )
    return ReservationPulseSlotSelection(
        date = start.toLocalDate(),
        start = slotStart,
        end = slotStart.plusMinutes(RESERVATION_PULSE_SLOT_MINUTES),
    )
}

private fun reservationsForPulseSelection(
    state: ReservationsUiState,
    selection: ReservationPulseSlotSelection,
): List<BackendReservation> {
    return state.pulseReservations
        .filter { reservation ->
            reservationOverlapsPulseSlot(
                reservation = reservation,
                date = selection.date,
                slotStartTime = selection.start,
                slotEndTime = selection.end,
            )
        }
        .sortedBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX }
}

private fun buildReservationWorkQueue(reservations: List<BackendReservation>): ReservationWorkQueueSections {
    val now = LocalDateTime.now()
    val sorted = reservations.sortedBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX }
    val past = sorted.filter { reservationIsPast(it, now) }
    val active = sorted.filterNot { reservation -> past.any { it.id == reservation.id } }
    val attention = active.filter { reservationNeedsAttention(it, now) }
    val attentionIds = attention.map { it.id }.toSet()
    val unassigned = active.filter { it.id !in attentionIds && reservationIsUnassigned(it) }
    val unassignedIds = unassigned.map { it.id }.toSet()
    val upcoming = active.filter { it.id !in attentionIds && it.id !in unassignedIds }
    return ReservationWorkQueueSections(
        attention = attention,
        unassigned = unassigned,
        upcoming = upcoming,
        past = past,
    )
}

private fun filteredReservations(state: ReservationsUiState): List<BackendReservation> {
    val tableLabels = state.tables
        .mapNotNull { option -> option.backendTableId?.let { it to option.label } }
        .toMap()
    val query = state.searchQuery.trim().lowercase()
    val now = LocalDateTime.now()
    val filtered = state.reservations.filter { reservation ->
        val tableLabel = reservation.tableId?.let { tableLabels[it] } ?: tableLabelForReservation(reservation)
        val parts = splitReservationNotes(reservation.notes.orEmpty())
        val startTime = formatReservationTime(reservation.startTime)
        val endTime = formatReservationTime(reservation.endTime)
        val matchesQuery = query.isBlank() || listOf(
            reservation.customerName,
            reservation.customerPhone.orEmpty(),
            parts.email,
            parts.allergies,
            parts.notes,
            parts.status.label,
            parts.status.tag,
            tableLabel,
            startTime,
            endTime,
            reservation.persons.toString(),
        ).any { it.lowercase().contains(query) }
        val matchesTable = state.tableFilterId == null || reservation.tableId == state.tableFilterId
        val matchesUnassigned = !state.unassignedOnly || reservationIsUnassigned(reservation)
        val matchesQueue = when (state.queueFilter) {
            ReservationQueueFilter.ALL -> true
            ReservationQueueFilter.UPCOMING -> !reservationIsPast(reservation, now) && !reservationNeedsAttention(reservation, now)
            ReservationQueueFilter.ATTENTION -> reservationNeedsAttention(reservation, now)
            ReservationQueueFilter.UNASSIGNED -> reservationIsUnassigned(reservation)
        }
        matchesQuery && matchesTable && matchesUnassigned && matchesQueue
    }
    return sortReservations(filtered, state.sortMode, tableLabels)
}

private fun sortReservations(
    reservations: List<BackendReservation>,
    mode: ReservationSortMode,
    tableLabels: Map<Int, String>,
): List<BackendReservation> {
    return when (mode) {
        ReservationSortMode.TIME -> reservations.sortedBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX }
        ReservationSortMode.DAY -> reservations.sortedWith(
            compareBy<BackendReservation> { parseReservationDateTime(it.startTime)?.toLocalDate() ?: LocalDate.MAX }
                .thenBy { parseReservationDateTime(it.startTime)?.toLocalTime() ?: LocalTime.MAX },
        )
        ReservationSortMode.TABLE -> reservations.sortedWith(
            compareBy<BackendReservation> {
                it.tableId?.let { tableId -> tableLabels[tableId] } ?: tableLabelForReservation(it)
            }.thenBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX },
        )
        ReservationSortMode.NAME -> reservations.sortedWith(
            compareBy<BackendReservation> { it.customerName.lowercase() }
                .thenBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX },
        )
        ReservationSortMode.STATUS -> reservations.sortedWith(
            compareBy<BackendReservation> { splitReservationNotes(it.notes.orEmpty()).status.ordinal }
                .thenBy { parseReservationDateTime(it.startTime) ?: LocalDateTime.MAX },
        )
    }
}

private fun reservationMatchesTimeWindow(
    reservation: BackendReservation,
    window: ReservationTimeWindow,
): Boolean {
    if (window == ReservationTimeWindow.ALL) return true
    val start = parseReservationDateTime(reservation.startTime)?.toLocalTime() ?: return false
    val windowStart = window.start ?: return true
    val windowEnd = window.end ?: return true
    return !start.isBefore(windowStart) && start.isBefore(windowEnd)
}

private fun reservationStartsInSlot(
    reservation: BackendReservation,
    slot: LocalTime,
): Boolean {
    val start = parseReservationDateTime(reservation.startTime)?.toLocalTime() ?: return false
    val slotEnd = slot.plusHours(1)
    return !start.isBefore(slot) && start.isBefore(slotEnd)
}

private fun tableLabelForReservation(reservation: BackendReservation): String {
    return if (reservationIsUnassigned(reservation)) {
        "Ei pöytää"
    } else {
        reservation.tableId?.let { "Pöytä $it" } ?: "Ei pöytää"
    }
}

private fun reservationTableLabel(
    reservation: BackendReservation,
    tables: List<ReservationTableOption>,
): String {
    return reservation.tableId?.let { tableId ->
        tables.firstOrNull { it.backendTableId == tableId }?.label
    } ?: tableLabelForReservation(reservation)
}

private fun reservationIsUnassigned(reservation: BackendReservation): Boolean {
    val tableId = reservation.tableId
    if (tableId == null || tableId <= 0) return true
    val raw = reservation.notes.orEmpty()
    return raw.lineSequence().any { line ->
        line.trim().equals("TABLE: UNASSIGNED", ignoreCase = true)
    }
}

private fun reservationIsTerminal(status: ReservationStatus): Boolean {
    return status == ReservationStatus.SEATED ||
        status == ReservationStatus.CANCELLED ||
        status == ReservationStatus.NOSHOW
}

private fun reservationIsPast(
    reservation: BackendReservation,
    now: LocalDateTime,
): Boolean {
    val status = splitReservationNotes(reservation.notes.orEmpty()).status
    val end = parseReservationDateTime(reservation.endTime)
    return reservationIsTerminal(status) || (end != null && end.isBefore(now))
}

private fun reservationNeedsAttention(
    reservation: BackendReservation,
    now: LocalDateTime,
): Boolean {
    if (reservationIsPast(reservation, now)) return false
    val status = splitReservationNotes(reservation.notes.orEmpty()).status
    if (reservationIsTerminal(status)) return false
    val start = parseReservationDateTime(reservation.startTime) ?: return false
    val startsSoon = !start.isBefore(now) && !start.isAfter(now.plusMinutes(60))
    val startedButNotSeated = start.isBefore(now) && status != ReservationStatus.SEATED
    return startedButNotSeated || (reservationIsUnassigned(reservation) && startsSoon)
}

private fun isReservationStartingSoon(reservation: BackendReservation): Boolean {
    val start = parseReservationDateTime(reservation.startTime) ?: return false
    val now = LocalDateTime.now()
    val status = splitReservationNotes(reservation.notes.orEmpty()).status
    if (status == ReservationStatus.CANCELLED || status == ReservationStatus.NOSHOW || status == ReservationStatus.SEATED) {
        return false
    }
    return !start.isBefore(now) && !start.isAfter(now.plusMinutes(60))
}

@Composable
private fun timelineHeatColor(count: Int) = when {
    count <= 0 -> MaterialTheme.colorScheme.surfaceVariant
    count == 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    count == 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
    else -> MaterialTheme.colorScheme.primary
}

private fun RestaurantTable.toReservationTableOption(): ReservationTableOption {
    return ReservationTableOption(
        serviceSpotId = id,
        backendTableId = backendTableId,
        label = label,
        seats = seats,
        areaName = areaName,
    )
}

private data class ReservationTableAvailability(
    val isSelectable: Boolean,
    val reason: String? = null,
)

private data class ReservationNoteParts(
    val status: ReservationStatus = ReservationStatus.BOOKED,
    val email: String = "",
    val allergies: String = "",
    val notes: String = "",
)

private fun ReservationTableOption.availability(state: ReservationsUiState): ReservationTableAvailability {
    val backendId = backendTableId
        ?: return ReservationTableAvailability(isSelectable = false, reason = "Ei backendin pöytä-ID:tä")
    val persons = state.form.persons.toIntOrNull()
        ?: return ReservationTableAvailability(isSelectable = false, reason = "Aseta henkilömäärä ensin")
    if (seats <= 0) {
        return ReservationTableAvailability(isSelectable = false, reason = "Kapasiteetti ei ole saatavilla")
    }
    if (persons > seats) {
        return ReservationTableAvailability(isSelectable = false, reason = "Liian pieni $persons henkilölle")
    }
    if (hasClientSideOverlap(backendId, state)) {
        return ReservationTableAvailability(isSelectable = false, reason = "Varattu tähän aikaan")
    }
    return ReservationTableAvailability(isSelectable = true)
}

private fun hasClientSideOverlap(
    backendTableId: Int,
    state: ReservationsUiState,
): Boolean {
    val proposed = state.form.proposedInterval(state.selectedDate) ?: return false
    return state.reservations.any { reservation ->
        reservation.id != state.form.editingReservationId &&
            reservation.tableId == backendTableId &&
            intervalsOverlap(
                proposed.first,
                proposed.second,
                parseReservationDateTime(reservation.startTime),
                parseReservationDateTime(reservation.endTime),
            )
    }
}

private fun intervalsOverlap(
    startA: LocalDateTime,
    endA: LocalDateTime,
    startB: LocalDateTime?,
    endB: LocalDateTime?,
): Boolean {
    if (startB == null || endB == null) return false
    return startB < endA && endB > startA
}

private fun ReservationFormState.proposedInterval(date: LocalDate): Pair<LocalDateTime, LocalDateTime>? {
    val start = parseFormTime(startTime) ?: return null
    val end = parseFormTime(endTime) ?: return null
    if (!end.isAfter(start)) return null
    return LocalDateTime.of(date, start) to LocalDateTime.of(date, end)
}

private object FinnishPhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = normalizeReservationPhoneInput(text.text)
        val formatted = formatFinnishPhoneForDisplay(raw)
        return TransformedText(
            text = AnnotatedString(formatted),
            offsetMapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    return phoneOriginalToTransformedOffset(
                        formatted = formatted,
                        offset = offset.coerceIn(0, raw.length),
                    )
                }

                override fun transformedToOriginal(offset: Int): Int {
                    return formatted
                        .take(offset.coerceIn(0, formatted.length))
                        .count { it != ' ' }
                        .coerceIn(0, raw.length)
                }
            },
        )
    }
}

private fun phoneOriginalToTransformedOffset(formatted: String, offset: Int): Int {
    if (offset <= 0) return 0
    var rawSeen = 0
    formatted.forEachIndexed { index, char ->
        if (char != ' ') {
            rawSeen += 1
            if (rawSeen == offset) return index + 1
        }
    }
    return formatted.length
}

private fun normalizeReservationPhoneInput(value: String): String {
    val input = value.trim()
    val builder = StringBuilder()
    input.forEach { char ->
        when {
            char.isDigit() -> builder.append(char)
            char == '+' && builder.isEmpty() -> builder.append(char)
        }
    }
    return builder.toString()
}

private fun formatFinnishPhoneForDisplay(value: String): String {
    val phone = normalizeReservationPhoneInput(value)
    if (phone.isBlank()) return ""
    return when {
        phone.startsWith("+358") -> {
            val localDigits = phone.removePrefix("+358")
            listOf("+358")
                .plus(groupPhoneDigits(localDigits, listOf(2, 3)))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        phone.startsWith("0") -> groupPhoneDigits(phone, listOf(3, 3)).joinToString(" ")
        else -> groupPhoneDigits(phone, listOf(3, 3, 4)).joinToString(" ")
    }
}

private fun groupPhoneDigits(value: String, sizes: List<Int>): List<String> {
    if (value.isBlank()) return emptyList()
    val groups = mutableListOf<String>()
    var offset = 0
    sizes.forEach { size ->
        if (offset >= value.length) return groups
        val end = (offset + size).coerceAtMost(value.length)
        groups += value.substring(offset, end)
        offset = end
    }
    if (offset < value.length) {
        groups += value.substring(offset)
    }
    return groups
}

private fun ReservationFormState.toPayload(date: LocalDate): PosResult<BackendReservationWrite> {
    val tableId = selectedTableId
    val name = customerName.trim()
    if (name.isBlank()) return PosResult.Failure("Asiakkaan nimi vaaditaan.")
    val phone = normalizeReservationPhoneInput(customerPhone)
    val personsInt = persons.toIntOrNull()?.takeIf { it > 0 } ?: return PosResult.Failure("Henkilömäärän pitää olla suurempi kuin nolla.")
    val start = parseFormTime(startTime) ?: return PosResult.Failure("Aloitusajan muodon pitää olla HH:mm.")
    val end = parseFormTime(endTime) ?: return PosResult.Failure("Päättymisajan muodon pitää olla HH:mm.")
    if (!end.isAfter(start)) return PosResult.Failure("Päättymisajan pitää olla aloitusajan jälkeen.")

    return PosResult.Success(
        BackendReservationWrite(
            tableId = tableId,
            customerName = name,
            customerPhone = phone.ifBlank { null },
            startTime = LocalDateTime.of(date, start).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            endTime = LocalDateTime.of(date, end).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            persons = personsInt,
            notes = buildReservationNotes(
                status = status,
                email = customerEmail,
                allergies = allergies,
                notes = notes,
            ),
        ),
    )
}

private fun buildReservationNotes(
    status: ReservationStatus,
    email: String,
    allergies: String,
    notes: String,
): String? {
    val lines = mutableListOf<String>()
    lines.add("STATUS: ${status.tag}")
    email.trim().takeIf { it.isNotBlank() }?.let { lines.add("EMAIL: $it") }
    allergies.trim().takeIf { it.isNotBlank() }?.let { lines.add("ALLERGIES: $it") }
    notes.trim().takeIf { it.isNotBlank() }?.let {
        lines.add("NOTES:")
        lines.add(it)
    }
    return lines.joinToString("\n").take(500).ifBlank { null }
}

private fun splitReservationNotes(raw: String): ReservationNoteParts {
    val statusPrefix = "STATUS:"
    val emailPrefix = "EMAIL:"
    val allergiesPrefix = "ALLERGIES:"
    val notesPrefix = "NOTES:"
    var status = ReservationStatus.BOOKED
    var email = ""
    var allergies = ""
    val notes = mutableListOf<String>()
    var inNotes = false
    raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith(statusPrefix, ignoreCase = true) -> {
                status = parseReservationStatus(trimmed.substringAfter(":", "").trim())
                inNotes = false
            }
            trimmed.startsWith(emailPrefix, ignoreCase = true) -> {
                email = trimmed.substringAfter(":", "").trim()
                inNotes = false
            }
            trimmed.startsWith(allergiesPrefix, ignoreCase = true) -> {
                allergies = trimmed.substringAfter(":", "").trim()
                inNotes = false
            }
            trimmed.startsWith(notesPrefix, ignoreCase = true) -> {
                val inline = trimmed.substringAfter(":", "").trim()
                if (inline.isNotBlank()) notes.add(inline)
                inNotes = true
            }
            inNotes -> notes.add(line)
            trimmed.isNotBlank() -> notes.add(line)
        }
    }
    return ReservationNoteParts(
        status = status,
        email = email,
        allergies = allergies,
        notes = notes.joinToString("\n").trim(),
    )
}

private fun parseReservationStatus(raw: String): ReservationStatus {
    return ReservationStatus.values().firstOrNull { it.tag.equals(raw.trim(), ignoreCase = true) }
        ?: ReservationStatus.BOOKED
}

private const val FullCircleRadians: Double = PI * 2.0

private fun normalizedClockAngleRadians(offset: Offset, center: Offset): Double {
    val raw = atan2(
        (offset.x - center.x).toDouble(),
        (center.y - offset.y).toDouble(),
    )
    return if (raw < 0.0) raw + FullCircleRadians else raw
}

private fun timeFromKeypadDigits(digits: String): LocalTime {
    val padded = digits.filter(Char::isDigit).padEnd(4, '0').take(4)
    val hour = padded.take(2).toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = padded.drop(2).take(2).toIntOrNull()?.coerceIn(0, 59) ?: 0
    return LocalTime.of(hour, minute)
}

private fun LocalDate.toDatePickerMillis(): Long {
    return atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun datePickerMillisToLocalDate(millis: Long): LocalDate {
    return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DateInputFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

private fun parseFormTime(raw: String): LocalTime? {
    return try {
        LocalTime.parse(raw.trim(), TimeFormatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun parseDateInput(raw: String): LocalDate? {
    return try {
        LocalDate.parse(raw.trim(), DateInputFormatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun durationMinutesBetween(startRaw: String, endRaw: String): Long? {
    val start = parseFormTime(startRaw) ?: return null
    val end = parseFormTime(endRaw) ?: return null
    if (!end.isAfter(start)) return null
    return Duration.between(start, end).toMinutes().takeIf { it > 0 }
}

private fun parseReservationDateTime(raw: String): LocalDateTime? {
    val value = raw.trim()
    if (value.isBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
}

private fun formatReservationTime(raw: String): String {
    return parseReservationDateTime(raw)?.toLocalTime()?.format(TimeFormatter) ?: raw
}

private fun formatReservationInterval(reservation: BackendReservation): String {
    val start = parseReservationDateTime(reservation.startTime)?.toLocalTime()
    val end = parseReservationDateTime(reservation.endTime)?.toLocalTime()
    return if (start != null && end != null) {
        "${start.format(TimeFormatter)}–${end.format(TimeFormatter)}"
    } else {
        formatReservationTime(reservation.startTime)
    }
}

private fun formatSelectedDateFinnish(date: LocalDate): String {
    val locale = Locale("fi", "FI")
    val raw = date.format(DateTimeFormatter.ofPattern("EEE d.M.yyyy", locale))
    return raw.take(1).uppercase(locale) + raw.drop(1)
}

private fun formatSelectedDate(date: LocalDate): String {
    val locale = Locale("fi", "FI")
    val raw = date.format(DateTimeFormatter.ofPattern("EEE d.M.yyyy", locale))
    return raw.take(1).uppercase(locale) + raw.drop(1)
}
