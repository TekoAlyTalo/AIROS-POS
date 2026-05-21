package com.airos.pos.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
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
import com.airos.pos.core.model.StaffUiLanguage
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

private val SettingsPanelAltColor = Color(0xFF182633)
private val SettingsTextPrimary = Color(0xFFFBFEFF)
private val SettingsTextMuted = Color(0xFFC0CCD6)

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
    val customerEnrollmentLabelInput: String = "Asiakas",
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
                    message = "Oletuspohjakassa: syötä kelvollinen euromäärä, esimerkiksi 50,00.",
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
                    message = "Asetukset tallennettu paikallisesti.",
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
                    message = "Offline-tila ${if (enabled) "käytössä" else "pois käytöstä"}.",
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
                        "NFC-suorakirjautuminen käytössä."
                    } else {
                        "NFC-suorakirjautuminen pois käytöstä."
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
                message = "Odotetaan seuraavaa NFC-tunnistetta: ${row.staff.displayName}.",
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
                message = "NFC-liitos peruttu.",
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
                            message = "NFC-tunniste poistettu työntekijäprofiilista.",
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
        val label = mutableState.value.customerEnrollmentLabelInput.trim().ifBlank { "Asiakas" }
        mutableState.update {
            it.copy(
                pendingNfcEnrollmentStaffId = null,
                pendingNfcEnrollmentStaffName = null,
                pendingNfcCustomerEnrollmentLabel = label,
                customerEnrollmentLabelInput = label,
                message = "Odotetaan seuraavaa NFC-tunnistetta asiakkaalle: $label.",
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
                            message = "Asiakkaan NFC-tunniste poistettu.",
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
                            message = "NFC-tunniste ${result.value.canonicalUid} liitetty työntekijään ${staff.displayName}.",
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
                            message = "NFC-tunniste ${result.value.canonicalUid} liitetty kohteeseen ${result.value.entityDisplayLabel}.",
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
    currentStaffName: String?,
    staffUiLanguage: StaffUiLanguage,
    onTerminalNameChanged: (String) -> Unit,
    onEdgeBaseUrlChanged: (String) -> Unit,
    onRestaurantKeyChanged: (String) -> Unit,
    onDefaultOpeningFloatChanged: (String) -> Unit,
    onStaffUiLanguageChanged: (StaffUiLanguage) -> Unit,
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
        title = "Asetukset",
        supportingText = "Päätteen asetukset, laitetiedot, synkronoinnin tila ja paikalliset NFC-tunnisteet.",
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

            StaffLanguagePreferenceCard(
                currentStaffName = currentStaffName,
                selectedLanguage = staffUiLanguage,
                onLanguageSelected = onStaffUiLanguageChanged,
            )

            OutlinedTextField(
                value = state.terminalNameInput,
                onValueChange = onTerminalNameChanged,
                keyboardOptions = staffTextKeyboardOptions(
                    language = staffUiLanguage,
                    capitalization = KeyboardCapitalization.Words,
                ),
                label = { Text("Päätteen nimi") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.edgeBaseUrlInput,
                onValueChange = onEdgeBaseUrlChanged,
                label = { Text("Edge-palvelimen URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.restaurantKeyInput,
                onValueChange = onRestaurantKeyChanged,
                label = { Text("Ravintolan tunniste") },
                supportingText = { Text("Täytyy vastata backendin ravintolatunnistetta. Oletus: ravintola_default") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.defaultOpeningFloatInput,
                onValueChange = onDefaultOpeningFloatChanged,
                label = { Text("Oletuspohjakassa (€)") },
                supportingText = { Text("Täytetään Vuoro-sivulle uuden vuoron avauksessa. Henkilöstö voi vielä muuttaa todellisen summan.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Offline-tila")
                Switch(
                    checked = state.settings?.offlineModeEnabled ?: false,
                    onCheckedChange = onOfflineModeChanged,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("NFC-suorakirjautuminen")
                Switch(
                    checked = state.settings?.nfcDirectLoginEnabled ?: false,
                    onCheckedChange = onNfcDirectLoginChanged,
                )
            }
            Button(onClick = onSaveSettings) {
                Text("Tallenna asetukset")
            }
            KeyValueRow("Jonossa olevat kirjoitukset", state.queueDepth.toString())
            KeyValueRow("Laite", state.deviceProfile?.model ?: "-")
            KeyValueRow("Valmistaja", state.deviceProfile?.vendor?.name ?: "-")
            KeyValueRow("Tulostin", state.deviceProfile?.hasBuiltInPrinter?.toString() ?: "-")
            KeyValueRow("Skanneri", state.deviceProfile?.hasScanner?.toString() ?: "-")

            Text(
                text = "NFC-tunnisteet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Henkilöstön NFC-tunnisteet tallennetaan paikallisesti kassaan. Myöhemmin tämä voidaan synkronoida backendin hallitsemaan tunnistepolitiikkaan.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.hasPendingNfcEnrollment) {
                StatusBanner(
                    text = state.pendingNfcEnrollmentStaffName?.let { staffName ->
                        "Odotetaan seuraavaa NFC-lukua: $staffName."
                    } ?: "Odotetaan seuraavaa NFC-lukua asiakkaalle: ${state.pendingNfcCustomerEnrollmentLabel}.",
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
                        Text("Peru odotus")
                    }
                    OutlinedButton(
                        onClick = onUseLastSeenNfcTag,
                        enabled = lastNfcTagUid != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Käytä viimeksi luettua tunnistetta")
                    }
                }
            }

            KeyValueRow("Viimeksi luettu tunniste", lastNfcTagSummary ?: "NFC-tunnistetta ei ole vielä luettu.")

            state.nfcStaffRows.forEach { row ->
                StaffNfcEnrollmentCard(
                    row = row,
                    isPending = state.pendingNfcEnrollmentStaffId == row.staff.id,
                    onBeginEnrollment = { onBeginNfcEnrollment(row.staff.id) },
                    onRemoveEnrollment = { onRemoveNfcEnrollment(row.staff.id) },
                )
            }

            Text(
                text = "Asiakkaan NFC-tunnisteet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Asiakkaan tunnisteet käyttävät samaa tunnistevarastoa, mutta ne liitetään asiakas- tai kanta-asiakastietoihin, ei henkilöstöön. Kuittien NFC-luovutukset tallennetaan erikseen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.customerEnrollmentLabelInput,
                onValueChange = onCustomerEnrollmentLabelChanged,
                keyboardOptions = staffTextKeyboardOptions(
                    language = staffUiLanguage,
                    capitalization = KeyboardCapitalization.Words,
                ),
                label = { Text("Asiakkaan nimi") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onBeginCustomerEnrollment,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.pendingNfcCustomerEnrollmentLabel != null) {
                        "Odotetaan asiakkaan NFC-lukua..."
                    } else {
                        "Liitä asiakkaan tunniste"
                    },
                )
            }

            if (state.nfcCustomerEnrollments.isEmpty()) {
                Text(
                    text = "Asiakkaan NFC-tunnisteita ei ole vielä liitetty.",
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
                    text = "Viimeisimmät kuittien NFC-luovutukset",
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
                text = "Viimeisimmät NFC-tapahtumat",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.recentNfcEvents.isEmpty()) {
                Text(
                    text = "NFC-tapahtumia ei ole vielä.",
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
private fun StaffLanguagePreferenceCard(
    currentStaffName: String?,
    selectedLanguage: StaffUiLanguage,
    onLanguageSelected: (StaffUiLanguage) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = SettingsPanelAltColor,
        contentColor = SettingsTextPrimary,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Oma kieli",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = currentStaffName?.takeIf { it.isNotBlank() }?.let { staff ->
                    "Käyttöliittymän kieli henkilölle $staff. Ei muuta kuitin tai ravintolan kieltä."
                } ?: "Käyttöliittymän kieli aktiiviselle myyjälle. Ei muuta kuitin tai ravintolan kieltä.",
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsTextMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StaffLanguageOptionButton(
                    label = "Suomi",
                    selected = selectedLanguage == StaffUiLanguage.FI,
                    onClick = { onLanguageSelected(StaffUiLanguage.FI) },
                    modifier = Modifier.weight(1f),
                )
                StaffLanguageOptionButton(
                    label = "English",
                    selected = selectedLanguage == StaffUiLanguage.EN,
                    onClick = { onLanguageSelected(StaffUiLanguage.EN) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StaffLanguageOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
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
        color = SettingsPanelAltColor,
        contentColor = SettingsTextPrimary,
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
                text = "Rooli: ${row.staff.role.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = row.enrollment?.let {
                    "Liitetty tunniste: ${it.canonicalUid}"
                } ?: "Tälle työntekijäprofiilille ei ole liitetty NFC-tunnistetta.",
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
                            "Odotetaan lukua…"
                        } else if (row.enrollment != null) {
                            "Vaihda tunniste"
                        } else {
                            "Liitä tunniste"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onRemoveEnrollment,
                    enabled = row.enrollment != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Poista tunniste")
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
        color = SettingsPanelAltColor,
        contentColor = SettingsTextPrimary,
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
                text = "Asiakastunniste: ${enrollment.entityId}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Liitetty tunniste: ${enrollment.canonicalUid}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRemoveEnrollment,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Poista asiakkaan tunniste")
            }
        }
    }
}

private fun staffTextKeyboardOptions(
    language: StaffUiLanguage,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
): KeyboardOptions {
    return KeyboardOptions(
        capitalization = capitalization,
        keyboardType = keyboardType,
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
