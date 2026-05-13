package com.airos.pos.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.airos.pos.core.ui.NumericMoneyPad
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
        val cents = euroInputToCents(mutableState.value.countedCashInput)
        if (cents == null) {
            mutableState.update { it.copy(message = "Laskettu käteinen: syötä kelvollinen euromäärä, esimerkiksi 123,45.") }
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
    currentStaffName: String? = null,
    onOpeningFloatChanged: (String) -> Unit,
    onCountedCashChanged: (String) -> Unit,
    onOpenShift: (String) -> Unit,
    onCloseShift: () -> Unit,
    attendance: WorktimeAttendanceSnapshot = WorktimeAttendanceSnapshot(),
    isClockedIn: Boolean = false,
    myAttendanceEntry: AttendanceEntry? = null,
    attendanceStateLoading: Boolean = false,
    attendanceBusy: Boolean = false,
    attendanceNoticeMessage: String? = null,
    attendanceMessage: String? = null,
    onClockIn: () -> Unit = {},
    onClockOut: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left pane: cash entry via on-screen keypad + attendance management
        PosPane(
            title = "Vuoronhallinta",
            supportingText = "Avaa ja sulje kassavuoro paikallisiin kirjauksiin perustuen.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }

                if (state.currentShift == null) {
                    Text(
                        text = "Pohjakassa",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CashAmountDisplay(state.openingFloatInput)
                    NumericMoneyPad(
                        onDigit = { d -> onOpeningFloatChanged(appendShiftMoneyDigit(state.openingFloatInput, d)) },
                        onDecimal = { onOpeningFloatChanged(appendShiftMoneyDecimal(state.openingFloatInput)) },
                        onBackspace = { onOpeningFloatChanged(removeShiftMoneyChar(state.openingFloatInput)) },
                    )
                    Button(
                        onClick = { currentStaffId?.let(onOpenShift) },
                        enabled = !state.busy && currentStaffId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Avaa vuoro")
                    }
                } else {
                    Text(
                        text = "Laskettu käteinen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CashAmountDisplay(state.countedCashInput)
                    NumericMoneyPad(
                        onDigit = { d -> onCountedCashChanged(appendShiftMoneyDigit(state.countedCashInput, d)) },
                        onDecimal = { onCountedCashChanged(appendShiftMoneyDecimal(state.countedCashInput)) },
                        onBackspace = { onCountedCashChanged(removeShiftMoneyChar(state.countedCashInput)) },
                    )
                    Button(
                        onClick = onCloseShift,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sulje vuoro")
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Työaika",
                    style = MaterialTheme.typography.titleMedium,
                )
                attendanceNoticeMessage?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
                attendanceMessage?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.error) }
                if (attendanceStateLoading) {
                    Text(
                        text = if (attendanceMessage == null) "Tarkistetaan työaikatilaa..." else "Työaikatila ei ole saatavilla.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (isClockedIn) {
                    val duration = myAttendanceEntry?.let { entry ->
                        val hours = (entry.durationMinutes / 60).toInt()
                        val mins = (entry.durationMinutes % 60).toInt()
                        if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
                    }
                    Text(
                        text = duration?.let { "Olet kirjautunut työvuoroon ($it)" }
                            ?: "Olet kirjautunut työvuoroon.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onClockOut,
                        enabled = !attendanceBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Kirjaa ulos")
                    }
                } else {
                    Text(
                        text = "Et ole kirjautunut työvuoroon.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onClockIn,
                        enabled = !attendanceBusy && currentStaffId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Kirjaa sisään")
                    }
                }
            }
        }

        // Right pane: shift status + shift list + staff attendance
        PosPane(
            title = "Vuoron tila",
            supportingText = "Vuoron rahasummat tallennetaan sentteinä täsmällistä rahakirjanpitoa varten.",
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeyValueRow("Tila", formatShiftStatus(state.currentShift?.status?.name ?: "CLOSED"))
                state.currentShift?.let { shift ->
                    KeyValueRow("Avaaja", shift.openedByStaffId)
                    KeyValueRow("Pohjakassa", CentsFormatter.format(shift.openingFloatCents))
                    KeyValueRow("Odotettu käteinen", CentsFormatter.format(shift.expectedCashCents))
                    shift.countedCashCents?.let { counted ->
                        KeyValueRow("Laskettu käteinen", CentsFormatter.format(counted))
                    }
                }
                currentStaffName?.let {
                    KeyValueRow("Aktiivinen myyjä", it)
                }

                HorizontalDivider()

                Text(
                    text = "Kassavuorolista",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.currentShift != null) {
                    ShiftListRow(state.currentShift)
                } else {
                    Text(
                        text = "Ei vuoroja näkyvissä.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Text(
                    text = "Paikalla oleva henkilöstö",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (attendance.currentlyOnSite.isEmpty()) {
                    Text(
                        text = "Ei aktiivisia työvuoroja.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    attendance.currentlyOnSite.forEach { entry ->
                        AttendanceRow(entry)
                    }
                }
                if (attendance.clockedInToday.isNotEmpty()) {
                    Text(
                        text = "Tänään kirjautuneet",
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

private fun formatShiftStatus(status: String): String {
    return when (status.uppercase()) {
        "OPEN" -> "Avoin"
        "CLOSED" -> "Suljettu"
        else -> status
    }
}

private fun formatShiftOpenedAt(epochMillis: Long): String {
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    val local = instant.atZone(java.time.ZoneId.systemDefault())
    return local.format(java.time.format.DateTimeFormatter.ofPattern("d.M. HH:mm"))
}

private fun appendShiftMoneyDigit(current: String, digit: String): String {
    val s = current.replace('.', ',').trim()
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

@Composable
private fun CashAmountDisplay(value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = if (value.isBlank()) "0,00 €" else "${value.replace('.', ',')} €",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ShiftListRow(shift: PosShift) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyValueRow("Avattiin", formatShiftOpenedAt(shift.openedAtEpochMillis))
            KeyValueRow("Pohjakassa", CentsFormatter.format(shift.openingFloatCents))
            KeyValueRow("Tila", formatShiftStatus(shift.status.name))
        }
    }
}

@Composable
private fun AttendanceRow(entry: AttendanceEntry) {
    val hours = (entry.durationMinutes / 60).toInt()
    val mins = (entry.durationMinutes % 60).toInt()
    val duration = if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
    val statusLabel = when (entry.status) {
        "active" -> "töissä"
        "on_break" -> "tauolla"
        "completed" -> "valmis"
        else -> entry.status
    }
    KeyValueRow(entry.staffName, "$statusLabel · $duration")
}
