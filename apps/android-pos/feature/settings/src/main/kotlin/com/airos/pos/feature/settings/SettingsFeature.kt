package com.airos.pos.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.model.DeviceProfile
import com.airos.pos.core.model.NfcIdentityEvent
import com.airos.pos.core.model.NfcIdentityRecord
import com.airos.pos.core.model.NfcReceiptHandoffRecord
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.device.platform.DeviceInfoService
import com.airos.pos.domain.AuthRepository
import com.airos.pos.domain.NfcIdentityRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.SyncQueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsNfcStaffRow(
    val staff: StaffMember,
    val enrollment: NfcIdentityRecord? = null,
)

data class SettingsUiState(
    val settings: TerminalSettings? = null,
    val terminalNameInput: String = "",
    val edgeBaseUrlInput: String = "",
    val restaurantKeyInput: String = "",
    val defaultOpeningFloatInput: String = "",
    val queueDepth: Int = 0,
    val deviceProfile: DeviceProfile? = null,
    val nfcStaffRows: List<SettingsNfcStaffRow> = emptyList(),
    val nfcCustomerEnrollments: List<NfcIdentityRecord> = emptyList(),
    val recentNfcEvents: List<NfcIdentityEvent> = emptyList(),
    val recentReceiptHandoffs: List<NfcReceiptHandoffRecord> = emptyList(),
    val pendingNfcEnrollmentStaffId: String? = null,
    val pendingNfcEnrollmentStaffName: String? = null,
    val pendingNfcCustomerEnrollmentLabel: String? = null,
    val customerEnrollmentLabelInput: String = "Guest customer",
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val hasPendingNfcEnrollment: Boolean
        get() = pendingNfcEnrollmentStaffId != null || pendingNfcCustomerEnrollmentLabel != null
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val syncQueueRepository: SyncQueueRepository,
    private val authRepository: AuthRepository,
    private val nfcIdentityRepository: NfcIdentityRepository,
    deviceInfoService: DeviceInfoService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            deviceProfile = deviceInfoService.currentProfile(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    private var latestStaff: List<StaffMember> = emptyList()
    private var latestStaffEnrollments: List<NfcIdentityRecord> = emptyList()
    private var lastHandledEnrollmentKey: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                mutableState.update { current ->
                    current.copy(
                        settings = settings,
                        terminalNameInput = if (current.terminalNameInput.isBlank()) settings.terminalName else current.terminalNameInput,
                        edgeBaseUrlInput = if (current.edgeBaseUrlInput.isBlank()) settings.edgeBaseUrl else current.edgeBaseUrlInput,
                        restaurantKeyInput = if (current.restaurantKeyInput.isBlank()) settings.restaurantKey else current.restaurantKeyInput,
                        defaultOpeningFloatInput = if (current.defaultOpeningFloatInput.isBlank()) {
                            centsToEuroInput(settings.defaultOpeningFloatCents)
                        } else {
                            current.defaultOpeningFloatInput
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            syncQueueRepository.observeQueue().collect { queue ->
                mutableState.update { it.copy(queueDepth = queue.size) }
            }
        }
        viewModelScope.launch {
            authRepository.observeQuickSelectStaff().collect { staff ->
                latestStaff = staff.sortedBy { it.displayName.lowercase() }
                rebuildStaffRows()
            }
        }
        viewModelScope.launch {
            nfcIdentityRepository.observeStaffEnrollments().collect { enrollments ->
                latestStaffEnrollments = enrollments
                rebuildStaffRows()
            }
        }
        viewModelScope.launch {
            nfcIdentityRepository.observeCustomerEnrollments().collect { enrollments ->
                mutableState.update { it.copy(nfcCustomerEnrollments = enrollments) }
            }
        }
        viewModelScope.launch {
            nfcIdentityRepository.observeRecentEvents(limit = 8).collect { events ->
                mutableState.update { it.copy(recentNfcEvents = events) }
            }
        }
        viewModelScope.launch {
            nfcIdentityRepository.observeRecentReceiptHandoffs(limit = 5).collect { handoffs ->
                mutableState.update { it.copy(recentReceiptHandoffs = handoffs) }
            }
        }
    }

    fun updateTerminalNameInput(value: String) {
        mutableState.update { it.copy(terminalNameInput = value) }
    }

    fun updateEdgeBaseUrlInput(value: String) {
        mutableState.update { it.copy(edgeBaseUrlInput = value) }
    }

    fun updateRestaurantKeyInput(value: String) {
        mutableState.update { it.copy(restaurantKeyInput = value) }
    }

    fun updateDefaultOpeningFloatInput(value: String) {
        mutableState.update { it.copy(defaultOpeningFloatInput = value) }
    }

    fun saveSettings() {
        val floatCents = euroInputToCents(mutableState.value.defaultOpeningFloatInput)
        if (floatCents == null) {
            mutableState.update {
                it.copy(
                    message = "Default opening float: enter a valid euro amount (e.g. 50,00).",
                    messageIsError = true,
                )
            }
            return
        }
        viewModelScope.launch {
            settingsRepository.updateTerminalName(mutableState.value.terminalNameInput)
            settingsRepository.updateEdgeBaseUrl(mutableState.value.edgeBaseUrlInput)
            settingsRepository.updateRestaurantKey(mutableState.value.restaurantKeyInput)
            settingsRepository.updateDefaultOpeningFloatCents(floatCents)
            mutableState.update {
                it.copy(
                    message = "Settings saved locally.",
                    messageIsError = false,
                )
            }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOfflineMode(enabled)
            mutableState.update {
                it.copy(
                    message = "Offline mode ${if (enabled) "enabled" else "disabled"}.",
                    messageIsError = false,
                )
            }
        }
    }

    fun setNfcDirectLoginEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNfcDirectLoginEnabled(enabled)
            mutableState.update {
                it.copy(
                    message = if (enabled) {
                        "NFC direct login enabled."
                    } else {
                        "NFC direct login disabled."
                    },
                    messageIsError = false,
                )
            }
        }
    }

    fun beginNfcEnrollment(staffId: String) {
        val row = mutableState.value.nfcStaffRows.firstOrNull { it.staff.id == staffId } ?: return
        mutableState.update {
            it.copy(
                pendingNfcEnrollmentStaffId = row.staff.id,
                pendingNfcEnrollmentStaffName = row.staff.displayName,
                pendingNfcCustomerEnrollmentLabel = null,
                message = "Waiting for next NFC tag for ${row.staff.displayName}.",
                messageIsError = false,
            )
        }
    }

    fun cancelNfcEnrollment() {
        mutableState.update {
            it.copy(
                pendingNfcEnrollmentStaffId = null,
                pendingNfcEnrollmentStaffName = null,
                pendingNfcCustomerEnrollmentLabel = null,
                message = "NFC enrollment cancelled.",
                messageIsError = false,
            )
        }
    }

    fun handlePendingNfcTag(canonicalUid: String, detectedAtEpochMillis: Long) {
        val state = mutableState.value
        val pendingKey = state.pendingNfcEnrollmentStaffId
            ?: state.pendingNfcCustomerEnrollmentLabel
            ?: return
        val eventKey = "$pendingKey|$canonicalUid|$detectedAtEpochMillis"
        if (lastHandledEnrollmentKey == eventKey) {
            return
        }
        lastHandledEnrollmentKey = eventKey
        currentPendingStaff()?.let { staff ->
            completeEnrollment(staff = staff, canonicalUid = canonicalUid)
            return
        }
        state.pendingNfcCustomerEnrollmentLabel?.let { label ->
            completeCustomerEnrollment(displayLabel = label, canonicalUid = canonicalUid)
        }
    }

    fun useLastSeenNfcTag(canonicalUid: String) {
        currentPendingStaff()?.let { staff ->
            completeEnrollment(staff = staff, canonicalUid = canonicalUid)
            return
        }
        mutableState.value.pendingNfcCustomerEnrollmentLabel?.let { label ->
            completeCustomerEnrollment(displayLabel = label, canonicalUid = canonicalUid)
        }
    }

    fun removeNfcEnrollment(staffId: String) {
        viewModelScope.launch {
            when (val result = nfcIdentityRepository.removeStaffTag(staffId)) {
                is com.airos.pos.core.common.PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            message = "NFC tag removed from the staff profile.",
                            messageIsError = false,
                        )
                    }
                }

                is com.airos.pos.core.common.PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            message = result.message,
                            messageIsError = true,
                        )
                    }
                }
            }
        }
    }

    fun updateCustomerEnrollmentLabelInput(value: String) {
        mutableState.update { it.copy(customerEnrollmentLabelInput = value) }
    }

    fun beginCustomerEnrollment() {
        val label = mutableState.value.customerEnrollmentLabelInput.trim().ifBlank { "Guest customer" }
        mutableState.update {
            it.copy(
                pendingNfcEnrollmentStaffId = null,
                pendingNfcEnrollmentStaffName = null,
                pendingNfcCustomerEnrollmentLabel = label,
                customerEnrollmentLabelInput = label,
                message = "Waiting for next NFC tag for customer identity $label.",
                messageIsError = false,
            )
        }
    }

    fun removeCustomerEnrollment(canonicalUid: String) {
        viewModelScope.launch {
            when (val result = nfcIdentityRepository.removeCustomerTag(canonicalUid)) {
                is com.airos.pos.core.common.PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            message = "Customer NFC tag removed.",
                            messageIsError = false,
                        )
                    }
                }

                is com.airos.pos.core.common.PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            message = result.message,
                            messageIsError = true,
                        )
                    }
                }
            }
        }
    }

    private fun currentPendingStaff(): StaffMember? {
        val pendingStaffId = mutableState.value.pendingNfcEnrollmentStaffId ?: return null
        return mutableState.value.nfcStaffRows.firstOrNull { it.staff.id == pendingStaffId }?.staff
    }

    private fun completeEnrollment(staff: StaffMember, canonicalUid: String) {
        viewModelScope.launch {
            when (val result = nfcIdentityRepository.enrollStaffTag(staff, canonicalUid)) {
                is com.airos.pos.core.common.PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            pendingNfcEnrollmentStaffId = null,
                            pendingNfcEnrollmentStaffName = null,
                            pendingNfcCustomerEnrollmentLabel = null,
                            message = "NFC tag ${result.value.canonicalUid} is now linked to ${staff.displayName}.",
                            messageIsError = false,
                        )
                    }
                }

                is com.airos.pos.core.common.PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            message = result.message,
                            messageIsError = true,
                        )
                    }
                }
            }
        }
    }

    private fun completeCustomerEnrollment(displayLabel: String, canonicalUid: String) {
        viewModelScope.launch {
            when (val result = nfcIdentityRepository.enrollCustomerTag(canonicalUid, displayLabel)) {
                is com.airos.pos.core.common.PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            pendingNfcCustomerEnrollmentLabel = null,
                            message = "NFC tag ${result.value.canonicalUid} is now linked to ${result.value.entityDisplayLabel}.",
                            messageIsError = false,
                        )
                    }
                }

                is com.airos.pos.core.common.PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            message = result.message,
                            messageIsError = true,
                        )
                    }
                }
            }
        }
    }

    private fun rebuildStaffRows() {
        val enrollmentByStaffId = latestStaffEnrollments.associateBy { it.entityId }
        val rows = latestStaff.map { staff ->
            SettingsNfcStaffRow(
                staff = staff,
                enrollment = enrollmentByStaffId[staff.id],
            )
        }

        mutableState.update { current ->
            val pendingStillPresent = current.pendingNfcEnrollmentStaffId?.let { pendingId ->
                rows.any { it.staff.id == pendingId }
            } == true
            current.copy(
                nfcStaffRows = rows,
                pendingNfcEnrollmentStaffId = current.pendingNfcEnrollmentStaffId?.takeIf { pendingStillPresent },
                pendingNfcEnrollmentStaffName = current.pendingNfcEnrollmentStaffName?.takeIf { pendingStillPresent },
            )
        }
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            syncQueueRepository: SyncQueueRepository,
            authRepository: AuthRepository,
            nfcIdentityRepository: NfcIdentityRepository,
            deviceInfoService: DeviceInfoService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    syncQueueRepository = syncQueueRepository,
                    authRepository = authRepository,
                    nfcIdentityRepository = nfcIdentityRepository,
                    deviceInfoService = deviceInfoService,
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onTerminalNameChanged: (String) -> Unit,
    onEdgeBaseUrlChanged: (String) -> Unit,
    onRestaurantKeyChanged: (String) -> Unit,
    onDefaultOpeningFloatChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onOfflineModeChanged: (Boolean) -> Unit,
    onNfcDirectLoginChanged: (Boolean) -> Unit,
    onBeginNfcEnrollment: (String) -> Unit,
    onCancelNfcEnrollment: () -> Unit,
    onUseLastSeenNfcTag: () -> Unit,
    onRemoveNfcEnrollment: (String) -> Unit,
    onCustomerEnrollmentLabelChanged: (String) -> Unit,
    onBeginCustomerEnrollment: () -> Unit,
    onRemoveCustomerEnrollment: (String) -> Unit,
    lastNfcTagSummary: String?,
    lastNfcTagUid: String?,
) {
    PosPane(
        title = "Settings",
        supportingText = "Terminal config, device diagnostics, sync visibility, and local NFC identity enrollment live here.",
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let {
                StatusBanner(
                    text = it,
                    tint = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            OutlinedTextField(
                value = state.terminalNameInput,
                onValueChange = onTerminalNameChanged,
                label = { Text("Terminal name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.edgeBaseUrlInput,
                onValueChange = onEdgeBaseUrlChanged,
                label = { Text("Edge base URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.restaurantKeyInput,
                onValueChange = onRestaurantKeyChanged,
                label = { Text("Restaurant scope key") },
                supportingText = { Text("Must match the backend restaurant identifier. Default: ravintola_default") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.defaultOpeningFloatInput,
                onValueChange = onDefaultOpeningFloatChanged,
                label = { Text("Default opening float (\u20AC)") },
                supportingText = { Text("Prefilled on Shift when opening a new shift. Staff can still change the actual amount.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Offline mode")
                Switch(
                    checked = state.settings?.offlineModeEnabled ?: false,
                    onCheckedChange = onOfflineModeChanged,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("NFC direct login")
                Switch(
                    checked = state.settings?.nfcDirectLoginEnabled ?: false,
                    onCheckedChange = onNfcDirectLoginChanged,
                )
            }
            Button(onClick = onSaveSettings) {
                Text("Save settings")
            }
            KeyValueRow("Queued writes", state.queueDepth.toString())
            KeyValueRow("Device", state.deviceProfile?.model ?: "-")
            KeyValueRow("Vendor", state.deviceProfile?.vendor?.name ?: "-")
            KeyValueRow("Printer", state.deviceProfile?.hasBuiltInPrinter?.toString() ?: "-")
            KeyValueRow("Scanner", state.deviceProfile?.hasScanner?.toString() ?: "-")

            Text(
                text = "NFC identity & enrollment",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Staff tags are now stored locally on the POS instead of in a hardcoded app map. This foundation can later be synced to backend-managed NFC identity policy.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.hasPendingNfcEnrollment) {
                StatusBanner(
                    text = state.pendingNfcEnrollmentStaffName?.let { staffName ->
                        "Waiting for next NFC tap for $staffName."
                    } ?: "Waiting for next NFC tap for customer identity ${state.pendingNfcCustomerEnrollmentLabel}.",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onCancelNfcEnrollment,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel waiting")
                    }
                    OutlinedButton(
                        onClick = onUseLastSeenNfcTag,
                        enabled = lastNfcTagUid != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Use last scanned tag")
                    }
                }
            }

            KeyValueRow("Last scanned tag", lastNfcTagSummary ?: "No NFC tag scanned yet.")

            state.nfcStaffRows.forEach { row ->
                StaffNfcEnrollmentCard(
                    row = row,
                    isPending = state.pendingNfcEnrollmentStaffId == row.staff.id,
                    onBeginEnrollment = { onBeginNfcEnrollment(row.staff.id) },
                    onRemoveEnrollment = { onRemoveNfcEnrollment(row.staff.id) },
                )
            }

            Text(
                text = "Customer NFC touchpoints",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Customer tags use the same identity storage but are linked as loyalty/customer identities, not staff identities. Receipt NFC handoffs are recorded separately.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.customerEnrollmentLabelInput,
                onValueChange = onCustomerEnrollmentLabelChanged,
                label = { Text("Customer label") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onBeginCustomerEnrollment,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.pendingNfcCustomerEnrollmentLabel != null) {
                        "Waiting for customer tap..."
                    } else {
                        "Enroll customer tag"
                    },
                )
            }

            if (state.nfcCustomerEnrollments.isEmpty()) {
                Text(
                    text = "No customer NFC identities enrolled yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.nfcCustomerEnrollments.forEach { enrollment ->
                    CustomerNfcEnrollmentCard(
                        enrollment = enrollment,
                        onRemoveEnrollment = { onRemoveCustomerEnrollment(enrollment.canonicalUid) },
                    )
                }
            }

            if (state.recentReceiptHandoffs.isNotEmpty()) {
                Text(
                    text = "Recent receipt NFC handoffs",
                    style = MaterialTheme.typography.titleMedium,
                )
                state.recentReceiptHandoffs.forEach { handoff ->
                    Text(
                        text = "• ${handoff.receiptNumber} -> ${handoff.linkedCustomerDisplayLabel ?: handoff.canonicalUid}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = "Recent NFC events",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.recentNfcEvents.isEmpty()) {
                Text(
                    text = "No NFC identity events yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.recentNfcEvents.forEach { event ->
                    Text(
                        text = "• ${event.message}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StaffNfcEnrollmentCard(
    row: SettingsNfcStaffRow,
    isPending: Boolean,
    onBeginEnrollment: () -> Unit,
    onRemoveEnrollment: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = row.staff.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Role: ${row.staff.role.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = row.enrollment?.let {
                    "Enrolled tag: ${it.canonicalUid}"
                } ?: "No NFC tag enrolled for this staff profile.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onBeginEnrollment,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (isPending) {
                            "Waiting for tap…"
                        } else if (row.enrollment != null) {
                            "Replace tag"
                        } else {
                            "Enroll tag"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onRemoveEnrollment,
                    enabled = row.enrollment != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remove tag")
                }
            }
        }
    }
}

@Composable
private fun CustomerNfcEnrollmentCard(
    enrollment: NfcIdentityRecord,
    onRemoveEnrollment: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = enrollment.entityDisplayLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Customer identity: ${enrollment.entityId}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Enrolled tag: ${enrollment.canonicalUid}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRemoveEnrollment,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove customer tag")
            }
        }
    }
}

private fun euroInputToCents(input: String): Int? {
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

private fun centsToEuroInput(cents: Int): String {
    val euros = cents / 100
    val remainder = cents % 100
    return "$euros,${remainder.toString().padStart(2, '0')}"
}
