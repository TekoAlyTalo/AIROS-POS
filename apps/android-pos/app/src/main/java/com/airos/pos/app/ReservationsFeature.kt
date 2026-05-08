package com.airos.pos.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
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
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

// Local product/demo step until Dashboard owner settings define reservation time precision.
private const val RESERVATION_TIME_STEP_MINUTES = 3L

private data class ReservationFormState(
    val editingReservationId: Int? = null,
    val customerName: String = "",
    val customerPhone: String = "",
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
    val tables: List<ReservationTableOption> = emptyList(),
    val form: ReservationFormState = ReservationFormState(),
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
            mutableState.update { it.copy(error = "Date must use YYYY-MM-DD.", message = null) }
            return
        }
        selectDate(parsed)
    }

    fun updateCustomerName(value: String) = updateForm { it.copy(customerName = value) }
    fun updateCustomerPhone(value: String) = updateForm { it.copy(customerPhone = value) }
    fun updatePersons(value: String) = updateForm { it.copy(persons = value.filter(Char::isDigit).take(3)) }
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
    fun selectTableFromPicker(serviceSpotId: String, fallbackLabel: String?) {
        val state = uiState.value
        val option = state.tables.firstOrNull { it.serviceSpotId == serviceSpotId }
        if (option == null) {
            mutableState.update {
                it.copy(
                    error = "Selected table was not found in current table truth.",
                    message = null,
                )
            }
            return
        }
        val backendTableId = option.backendTableId
        if (backendTableId == null) {
            mutableState.update {
                it.copy(
                    error = "Selected table has no backend table id.",
                    message = null,
                )
            }
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
        val parsedEnd = parseReservationDateTime(reservation.endTime)
        mutableState.update { state ->
            state.copy(
                selectedDate = parsedStart?.toLocalDate() ?: state.selectedDate,
                reservations = reservationsForDay(parsedStart?.toLocalDate() ?: state.selectedDate),
                form = ReservationFormState(
                    editingReservationId = reservation.id,
                    customerName = reservation.customerName,
                    customerPhone = reservation.customerPhone.orEmpty(),
                    persons = reservation.persons.toString(),
                    selectedTableId = reservation.tableId,
                    selectedTableLabel = state.tables.firstOrNull { it.backendTableId == reservation.tableId }?.label
                        ?: "Table ${reservation.tableId}",
                    startTime = parsedStart?.toLocalTime()?.format(TimeFormatter) ?: state.form.startTime,
                    durationMinutes = durationMinutesBetween(
                        parsedStart?.toLocalTime()?.format(TimeFormatter).orEmpty(),
                        parsedEnd?.toLocalTime()?.format(TimeFormatter).orEmpty(),
                    )?.toString() ?: state.form.durationMinutes,
                    endTime = parsedEnd?.toLocalTime()?.format(TimeFormatter) ?: state.form.endTime,
                    notes = reservation.notes.orEmpty(),
                ),
                message = null,
                error = null,
            )
        }
    }

    fun clearForm() {
        mutableState.update { state ->
            state.copy(
                form = ReservationFormState(),
                message = null,
                error = null,
            )
        }
    }

    fun submit() {
        val state = uiState.value
        val selectedOption = state.tables.firstOrNull { it.backendTableId == state.form.selectedTableId }
        val selectedAvailability = selectedOption?.availability(state)
        if (selectedAvailability?.isSelectable != true) {
            mutableState.update {
                it.copy(
                    error = selectedAvailability?.reason ?: "Select an available table.",
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
                            "Reservation created."
                        } else {
                            "Reservation updated."
                        },
                    )
                }
                is PosResult.Failure -> {
                    mutableState.update { it.copy(isSaving = false, error = result.message) }
                }
            }
        }
    }

    fun deleteReservation(reservation: BackendReservation) {
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, error = null, message = null) }
            when (val result = reservationsRepository.deleteReservation(reservation.id)) {
                is PosResult.Success -> refreshAfterMutation(message = "Reservation deleted.")
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
                        form = ReservationFormState(),
                        isSaving = false,
                        isLoading = false,
                        message = message,
                        error = null,
                    )
                }
            }
            is PosResult.Failure -> {
                mutableState.update {
                    it.copy(isSaving = false, isLoading = false, message = message, error = refreshed.message)
                }
            }
        }
    }

    private fun reservationsForDay(date: LocalDate): List<BackendReservation> {
        return allReservations
            .filter { parseReservationDateTime(it.startTime)?.toLocalDate() == date }
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
        onCustomerNameChange = viewModel::updateCustomerName,
        onCustomerPhoneChange = viewModel::updateCustomerPhone,
        onPersonsChange = viewModel::updatePersons,
        onOpenTablePicker = onOpenTablePicker,
        onStartTimeChange = viewModel::updateStartTime,
        onDurationMinutesChange = viewModel::updateDurationMinutes,
        onEndTimeChange = viewModel::updateEndTime,
        onNotesChange = viewModel::updateNotes,
        onSubmit = viewModel::submit,
        onEdit = viewModel::editReservation,
        onDelete = viewModel::deleteReservation,
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
    onCustomerNameChange: (String) -> Unit,
    onCustomerPhoneChange: (String) -> Unit,
    onPersonsChange: (String) -> Unit,
    onOpenTablePicker: () -> Unit,
    onStartTimeChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onEdit: (BackendReservation) -> Unit,
    onDelete: (BackendReservation) -> Unit,
    onClearForm: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

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

    PosPane(
        title = title,
        modifier = Modifier.fillMaxSize(),
    ) {
        state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
        state.error?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPreviousDay) { Text("Previous") }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = formatSelectedDate(state.selectedDate),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(onClick = { showDatePicker = true }) { Text("Kalenteri") }
            OutlinedButton(onClick = onToday) { Text("Today") }
            OutlinedButton(onClick = onNextDay) { Text("Next") }
            OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Refresh") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.dateInput,
                onValueChange = onDateInputChange,
                label = { Text("Date YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onApplyDateInput) { Text("Open date") }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (state.isLoading) "Loading reservations..." else "${state.reservations.size} reservations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!state.isLoading && state.reservations.isEmpty()) {
                    EmptyReservationList()
                }
                state.reservations.forEach { reservation ->
                    ReservationRow(
                        reservation = reservation,
                        tableLabel = state.tables.firstOrNull { it.backendTableId == reservation.tableId }?.label
                            ?: "Table ${reservation.tableId}",
                        onEdit = { onEdit(reservation) },
                        onDelete = { onDelete(reservation) },
                        enabled = !state.isSaving,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            ReservationForm(
                state = state,
                modifier = Modifier.weight(1f),
                selectTableLabel = selectTableLabel,
                noTableSelectedLabel = noTableSelectedLabel,
                onCustomerNameChange = onCustomerNameChange,
                onCustomerPhoneChange = onCustomerPhoneChange,
                onPersonsChange = onPersonsChange,
                onOpenTablePicker = onOpenTablePicker,
                onStartTimeChange = onStartTimeChange,
                onDurationMinutesChange = onDurationMinutesChange,
                onEndTimeChange = onEndTimeChange,
                onNotesChange = onNotesChange,
                onSubmit = onSubmit,
                onClearForm = onClearForm,
            )
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
            text = "No reservations for this day.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReservationRow(
    reservation: BackendReservation,
    tableLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = reservation.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatReservationTime(reservation.startTime)}-${formatReservationTime(reservation.endTime)} | $tableLabel | ${reservation.persons} persons",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reservation.customerPhone?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    reservation.notes?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
                    TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
                }
            }
        }
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
            text = if (editing) "Edit reservation" else "New reservation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = form.customerName,
            onValueChange = onCustomerNameChange,
            label = { Text("Customer name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.customerPhone,
            onValueChange = onCustomerPhoneChange,
            label = { Text("Customer phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.persons,
                onValueChange = onPersonsChange,
                label = { Text("Persons") },
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
                label = { Text("Duration min") },
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
            label = { Text("Notes") },
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
                Text(if (editing) "Save changes" else "Create reservation")
            }
            OutlinedButton(
                onClick = onClearForm,
                enabled = !state.isSaving,
            ) {
                Text("Clear")
            }
        }
    }
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

private fun ReservationTableOption.availability(state: ReservationsUiState): ReservationTableAvailability {
    val backendId = backendTableId
        ?: return ReservationTableAvailability(isSelectable = false, reason = "No backend table id")
    val persons = state.form.persons.toIntOrNull()
        ?: return ReservationTableAvailability(isSelectable = false, reason = "Set persons first")
    if (seats <= 0) {
        return ReservationTableAvailability(isSelectable = false, reason = "Capacity unavailable")
    }
    if (persons > seats) {
        return ReservationTableAvailability(isSelectable = false, reason = "Too small for $persons persons")
    }
    if (hasClientSideOverlap(backendId, state)) {
        return ReservationTableAvailability(isSelectable = false, reason = "Reserved in this time")
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

private fun ReservationFormState.toPayload(date: LocalDate): PosResult<BackendReservationWrite> {
    val tableId = selectedTableId ?: return PosResult.Failure("Select a table.")
    val name = customerName.trim()
    if (name.isBlank()) return PosResult.Failure("Customer name is required.")
    val personsInt = persons.toIntOrNull()?.takeIf { it > 0 } ?: return PosResult.Failure("Persons must be greater than zero.")
    val start = parseFormTime(startTime) ?: return PosResult.Failure("Start time must use HH:mm.")
    val end = parseFormTime(endTime) ?: return PosResult.Failure("End time must use HH:mm.")
    if (!end.isAfter(start)) return PosResult.Failure("End time must be after start time.")

    return PosResult.Success(
        BackendReservationWrite(
            tableId = tableId,
            customerName = name,
            customerPhone = customerPhone.trim().ifBlank { null },
            startTime = LocalDateTime.of(date, start).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            endTime = LocalDateTime.of(date, end).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            persons = personsInt,
            notes = notes.trim().ifBlank { null },
        ),
    )
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

private fun formatSelectedDate(date: LocalDate): String {
    val locale = Locale.getDefault()
    val raw = date.format(DateTimeFormatter.ofPattern("EEE d.M.yyyy", locale))
    return raw.take(1).uppercase(locale) + raw.drop(1)
}
