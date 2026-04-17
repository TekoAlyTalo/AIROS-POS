package com.airos.pos.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.ShiftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        mutableState.update { it.copy(openingFloatInput = value) }
    }

    fun updateCountedCash(value: String) {
        mutableState.update { it.copy(countedCashInput = value) }
    }

    fun openShift(staffId: String) {
        val cents = euroInputToCents(mutableState.value.openingFloatInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Opening float: enter a valid euro amount (e.g. 50,00).") }
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
        val cents = euroInputToCents(mutableState.value.countedCashInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Counted cash: enter a valid euro amount (e.g. 123,45).") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = shiftRepository.closeShift(cents)) {
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
            // Normalize: replace comma with period for parsing
            val normalized = trimmed.replace(',', '.')
            // Reject multiple dots, leading dots without digit, etc.
            if (normalized.count { it == '.' } > 1) return null
            val parts = normalized.split('.')
            val integerPart = parts[0].toLongOrNull() ?: return null
            if (integerPart < 0) return null
            val fractionCents = if (parts.size == 2) {
                val frac = parts[1]
                if (frac.length > 2 || frac.isEmpty()) return null
                // "5" → 50 cents, "50" → 50 cents, "03" → 3 cents
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
    onOpeningFloatChanged: (String) -> Unit,
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String) -> Unit,
    onCloseShift: () -> Unit,
    attendance: WorktimeAttendanceSnapshot = WorktimeAttendanceSnapshot(),
    isClockedIn: Boolean = false,
    myAttendanceEntry: AttendanceEntry? = null,
    attendanceBusy: Boolean = false,
    attendanceMessage: String? = null,
    onClockIn: () -> Unit = {},
    onClockOut: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PosPane(
            title = "Shift control",
            supportingText = "Open and close the cash shift with deterministic local records.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = state.openingFloatInput,
                    onValueChange = onOpeningFloatChanged,
                    label = { Text("Opening float (\u20AC)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { currentStaffId?.let(onOpenShift) },
                    enabled = !state.busy && currentStaffId != null,
                ) {
                    Text("Open shift")
                }
                OutlinedTextField(
                    value = state.countedCashInput,
                    onValueChange = onCountedCashChanged,
                    label = { Text("Counted cash (\u20AC)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onCloseShift, enabled = !state.busy && state.currentShift != null) {
                    Text("Close shift")
                }

                HorizontalDivider()

                Text(
                    text = "Attendance",
                    style = MaterialTheme.typography.titleMedium,
                )
                attendanceMessage?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }
                if (isClockedIn && myAttendanceEntry != null) {
                    val hours = (myAttendanceEntry.durationMinutes / 60).toInt()
                    val mins = (myAttendanceEntry.durationMinutes % 60).toInt()
                    val duration = if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
                    Text(
                        text = "You are clocked in ($duration)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onClockOut,
                        enabled = !attendanceBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clock out")
                    }
                } else {
                    Text(
                        text = "You are not clocked in.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onClockIn,
                        enabled = !attendanceBusy && currentStaffId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clock in")
                    }
                }
            }
        }

        PosPane(
            title = "Shift status",
            supportingText = "Shift metrics are stored in cents for deterministic money integrity.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeyValueRow("Status", state.currentShift?.status?.name ?: "CLOSED")
                state.currentShift?.let { shift ->
                    KeyValueRow("Opened by", shift.openedByStaffId)
                    KeyValueRow("Opening float", CentsFormatter.format(shift.openingFloatCents))
                    KeyValueRow("Expected cash", CentsFormatter.format(shift.expectedCashCents))
                    shift.countedCashCents?.let { counted ->
                        KeyValueRow("Counted cash", CentsFormatter.format(counted))
                    }
                }

                Text(
                    text = "Staff on site",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (attendance.currentlyOnSite.isEmpty()) {
                    Text(
                        text = "No active work sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    attendance.currentlyOnSite.forEach { entry ->
                        AttendanceRow(entry)
                    }
                }

                if (attendance.clockedInToday.isNotEmpty()) {
                    Text(
                        text = "Clocked in today",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    attendance.clockedInToday.forEach { entry ->
                        AttendanceRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceRow(entry: AttendanceEntry) {
    val hours = (entry.durationMinutes / 60).toInt()
    val mins = (entry.durationMinutes % 60).toInt()
    val duration = if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
    val statusLabel = when (entry.status) {
        "active" -> "working"
        "on_break" -> "on break"
        "completed" -> "done"
        else -> entry.status
    }
    KeyValueRow(entry.staffName, "$statusLabel \u00B7 $duration")
}
