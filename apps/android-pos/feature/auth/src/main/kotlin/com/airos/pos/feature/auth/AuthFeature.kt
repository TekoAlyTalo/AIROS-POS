package com.airos.pos.feature.auth

import android.util.Log
import android.graphics.Color.parseColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AuthSession
import com.airos.pos.core.model.ManagerOverrideGrant
import com.airos.pos.core.model.ManagerOverrideReason
import com.airos.pos.core.model.StaffMember
import com.airos.pos.core.model.StaffRole
import com.airos.pos.core.ui.NumericPinPad
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PosPinLength = 4
private const val NfcAuthLogTag = "AIROS_NFC"

data class ManagerOverrideUiState(
    val isVisible: Boolean = false,
    val managers: List<StaffMember> = emptyList(),
    val selectedManagerId: String? = null,
    val pin: String = "",
    val reason: ManagerOverrideReason = ManagerOverrideReason.SETTINGS_CHANGE,
    val isAuthorizing: Boolean = false,
    val errorMessage: String? = null,
    val lastGrant: ManagerOverrideGrant? = null,
) {
    val canSubmit: Boolean
        get() = selectedManagerId != null && pin.length == PosPinLength && !isAuthorizing
}

data class AuthUiState(
    val staff: List<StaffMember> = emptyList(),
    val selectedStaffId: String? = null,
    val pin: String = "",
    val isAuthenticating: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val authenticatedSession: AuthSession? = null,
    val managerOverride: ManagerOverrideUiState = ManagerOverrideUiState(),
) {
    val canSubmit: Boolean
        get() = selectedStaffId != null && pin.length == PosPinLength && !isAuthenticating
}

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.observeQuickSelectStaff().collect { staff ->
                mutableState.update { current ->
                    current.copy(
                        staff = staff,
                        selectedStaffId = current.selectedStaffId.takeIf { selectedId ->
                            staff.any { member -> member.id == selectedId && member.isEnabled }
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            authRepository.observeManagerQuickSelectStaff().collect { managers ->
                mutableState.update { current ->
                    current.copy(
                        managerOverride = current.managerOverride.copy(
                            managers = managers,
                            selectedManagerId = current.managerOverride.selectedManagerId.takeIf { selectedId ->
                                managers.any { manager -> manager.id == selectedId }
                            },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            authRepository.activeSession.collect { session ->
                mutableState.update { current ->
                    current.copy(
                        authenticatedSession = session,
                        isAuthenticating = false,
                        pin = if (session != null) "" else current.pin,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    fun selectStaff(staffId: String) {
        mutableState.update { current ->
            current.copy(
                selectedStaffId = staffId,
                pin = "",
                errorMessage = null,
                noticeMessage = null,
            )
        }
    }

    /**
     * Preselects a staff member identified by an NFC tag match and shows a notice.
     * PIN entry is still required — this is selection only, not sign-in.
     *
     * Called from [AirosPosApp] when an NFC match is detected while the auth screen
     * is visible. Takes only primitives so [feature.auth] stays decoupled from the
     * NFC types in the [app] package.
     */
    fun selectStaffByNfc(staffId: String, noticeMessage: String) {
        mutableState.update { current ->
            current.copy(
                selectedStaffId = staffId,
                pin = "",
                errorMessage = null,
                noticeMessage = noticeMessage,
            )
        }
    }

    /**
     * Direct NFC login path used only when terminal settings explicitly allow it.
     *
     * This stays inside the existing auth repository flow instead of creating a
     * UI-only session shortcut. Caller passes only resolved staff primitives.
     */
    fun signInWithNfc(staffId: String, noticeMessage: String) {
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    selectedStaffId = staffId,
                    pin = "",
                    isAuthenticating = true,
                    errorMessage = null,
                    noticeMessage = noticeMessage,
                )
            }
            Log.i(NfcAuthLogTag, "Direct NFC login requested | staffId=$staffId")
            when (val result = authRepository.signInWithNfc(staffId)) {
                is PosResult.Success -> {
                    Log.i(NfcAuthLogTag, "Direct NFC login success | staffId=$staffId")
                    mutableState.update {
                        it.copy(
                            authenticatedSession = result.value,
                            pin = "",
                            isAuthenticating = false,
                            errorMessage = null,
                            noticeMessage = noticeMessage,
                        )
                    }
                }

                is PosResult.Failure -> {
                    Log.w(NfcAuthLogTag, "Direct NFC login failure | staffId=$staffId reason=${result.message}")
                    mutableState.update {
                        it.copy(
                            pin = "",
                            isAuthenticating = false,
                            errorMessage = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Shows a notice for an unrecognized NFC tag without changing staff selection.
     * Called from [AirosPosApp] when an unknown tag is read on the auth screen.
     */
    fun showNfcUnknownTagNotice(message: String) {
        mutableState.update { it.copy(noticeMessage = message) }
    }

    fun appendPin(digit: String) {
        var nextState = mutableState.value
        mutableState.update { current ->
            nextState = if (current.pin.length >= PosPinLength) {
                current
            } else {
                current.copy(pin = current.pin + digit, errorMessage = null)
            }
            nextState
        }
        if (nextState.selectedStaffId != null && nextState.pin.length == PosPinLength && !nextState.isAuthenticating) {
            submitPin()
        }
    }

    fun removePinDigit() {
        mutableState.update { current -> current.copy(pin = current.pin.dropLast(1), errorMessage = null) }
    }

    fun clearPin() {
        mutableState.update { current -> current.copy(pin = "", errorMessage = null) }
    }

    fun submitPin() {
        val selectedStaffId = mutableState.value.selectedStaffId
        when {
            selectedStaffId == null -> {
                mutableState.update { it.copy(errorMessage = "Valitse työntekijä ennen kassaan siirtymistä.") }
                return
            }

            mutableState.value.pin.length != PosPinLength -> {
                mutableState.update { it.copy(errorMessage = "Syötä 4-numeroinen PIN.") }
                return
            }
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isAuthenticating = true, errorMessage = null) }
            when (val result = authRepository.signInWithPin(selectedStaffId, mutableState.value.pin)) {
                is PosResult.Success -> {
                    mutableState.update {
                        it.copy(
                            authenticatedSession = result.value,
                            pin = "",
                            isAuthenticating = false,
                            errorMessage = null,
                        )
                    }
                }

                is PosResult.Failure -> {
                    mutableState.update {
                        it.copy(
                            pin = "",
                            isAuthenticating = false,
                            errorMessage = result.message,
                        )
                    }
                }
            }
        }
    }

    fun showManagerOverrideDialog(reason: ManagerOverrideReason = ManagerOverrideReason.SETTINGS_CHANGE) {
        mutableState.update { current ->
            current.copy(
                managerOverride = current.managerOverride.copy(
                    isVisible = true,
                    reason = reason,
                    pin = "",
                    errorMessage = null,
                    isAuthorizing = false,
                ),
            )
        }
    }

    fun dismissManagerOverrideDialog() {
        mutableState.update { current ->
            current.copy(
                managerOverride = current.managerOverride.copy(
                    isVisible = false,
                    pin = "",
                    errorMessage = null,
                    isAuthorizing = false,
                ),
            )
        }
    }

    fun selectManager(managerId: String) {
        mutableState.update { current ->
            current.copy(
                managerOverride = current.managerOverride.copy(
                    selectedManagerId = managerId,
                    errorMessage = null,
                ),
            )
        }
    }

    fun appendManagerPin(digit: String) {
        mutableState.update { current ->
            val managerState = current.managerOverride
            if (managerState.pin.length >= PosPinLength) {
                current
            } else {
                current.copy(
                    managerOverride = managerState.copy(
                        pin = managerState.pin + digit,
                        errorMessage = null,
                    ),
                )
            }
        }
    }

    fun removeManagerPinDigit() {
        mutableState.update { current ->
            current.copy(
                managerOverride = current.managerOverride.copy(
                    pin = current.managerOverride.pin.dropLast(1),
                    errorMessage = null,
                ),
            )
        }
    }

    fun clearManagerPin() {
        mutableState.update { current ->
            current.copy(
                managerOverride = current.managerOverride.copy(
                    pin = "",
                    errorMessage = null,
                ),
            )
        }
    }

    fun confirmManagerOverride() {
        val managerState = mutableState.value.managerOverride
        val selectedManagerId = managerState.selectedManagerId
        when {
            selectedManagerId == null -> {
                mutableState.update { current ->
                    current.copy(
                        managerOverride = current.managerOverride.copy(
                            errorMessage = "Valitse päällikköprofiili hyväksyntää varten.",
                        ),
                    )
                }
                return
            }

            managerState.pin.length != PosPinLength -> {
                mutableState.update { current ->
                    current.copy(
                        managerOverride = current.managerOverride.copy(
                            errorMessage = "Syötä päällikön 4-numeroinen PIN.",
                        ),
                    )
                }
                return
            }
        }

        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    managerOverride = current.managerOverride.copy(
                        isAuthorizing = true,
                        errorMessage = null,
                    ),
                )
            }
            when (
                val result = authRepository.verifyManagerOverride(
                    managerStaffId = selectedManagerId,
                    pin = managerState.pin,
                    reason = managerState.reason,
                )
            ) {
                is PosResult.Success -> {
                    mutableState.update { current ->
                        current.copy(
                            noticeMessage = "${result.value.managerDisplayName} hyväksyi toiminnon: ${formatManagerOverrideReason(result.value.reason)}.",
                            managerOverride = current.managerOverride.copy(
                                isVisible = false,
                                pin = "",
                                errorMessage = null,
                                isAuthorizing = false,
                                lastGrant = result.value,
                            ),
                        )
                    }
                }

                is PosResult.Failure -> {
                    mutableState.update { current ->
                        current.copy(
                            managerOverride = current.managerOverride.copy(
                                pin = "",
                                isAuthorizing = false,
                                errorMessage = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(authRepository) }
        }
    }
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    onStaffSelected: (String) -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClearPin: () -> Unit,
    onSubmitPin: () -> Unit,
    onShowManagerOverride: () -> Unit,
    onManagerSelected: (String) -> Unit,
    onManagerDigit: (String) -> Unit,
    onManagerBackspace: () -> Unit,
    onClearManagerPin: () -> Unit,
    onConfirmManagerOverride: () -> Unit,
    onDismissManagerOverride: () -> Unit,
    staffPhotoPainter: @Composable (StaffMember) -> Painter? = { null },
) {
    val selectedStaff = state.staff.firstOrNull { it.id == state.selectedStaffId }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PosPane(
            title = "Henkilöstön pikavalinta",
            supportingText = "Valitse profiili ja avaa kassa 4-numeroisella POS-PINillä.",
            modifier = Modifier.weight(1.1f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(420.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(state.staff) { staff ->
                    StaffQuickSelectCard(
                        staff = staff,
                        selected = staff.id == state.selectedStaffId,
                        onSelect = { onStaffSelected(staff.id) },
                        photoPainter = staffPhotoPainter,
                    )
                }
            }

            state.noticeMessage?.let {
                StatusBanner(text = it, tint = MaterialTheme.colorScheme.secondary)
            }
        }

        PosPane(
            title = "Turvallinen kirjautuminen",
            modifier = Modifier.weight(0.95f),
        ) {
            SelectedStaffSummary(selectedStaff = selectedStaff, photoPainter = staffPhotoPainter)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "PIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = maskedPin(state.pin),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            state.errorMessage?.let {
                StatusBanner(text = it, tint = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClearPin) {
                    Text("Tyhjennä PIN")
                }
            }

            OutlinedButton(
                onClick = onShowManagerOverride,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Päällikön hyväksyntä")
            }

            NumericPinPad(
                onDigit = onDigit,
                onBackspace = onBackspace,
            )

            Button(
                onClick = onSubmitPin,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isAuthenticating) "Kirjaudutaan..." else "Avaa kassa")
            }
        }
    }

    if (state.managerOverride.isVisible) {
        ManagerOverrideDialog(
            state = state.managerOverride,
            onManagerSelected = onManagerSelected,
            onManagerDigit = onManagerDigit,
            onManagerBackspace = onManagerBackspace,
            onClearManagerPin = onClearManagerPin,
            onConfirmManagerOverride = onConfirmManagerOverride,
            onDismiss = onDismissManagerOverride,
            staffPhotoPainter = staffPhotoPainter,
        )
    }
}

@Composable
private fun StaffQuickSelectCard(
    staff: StaffMember,
    selected: Boolean,
    onSelect: () -> Unit,
    photoPainter: @Composable (StaffMember) -> Painter? = { null },
) {
    val accentColor = Color(parseColor(staff.quickColorHex).toLong())

    Surface(
        modifier = Modifier
            .height(136.dp)
            .clickable(enabled = staff.isEnabled, onClick = onSelect),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else accentColor.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StaffIdentityAvatar(
                    staff = staff,
                    painter = photoPainter(staff),
                    accentColor = accentColor,
                    modifier = Modifier.size(54.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = staff.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatStaffRole(staff.role),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = when {
                    !staff.isEnabled -> "Pois käytöstä"
                    staff.isManager -> "Päällikköoikeus"
                    else -> "POS-oikeus"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else accentColor,
            )
        }
    }
}

@Composable
private fun SelectedStaffSummary(
    selectedStaff: StaffMember?,
    photoPainter: @Composable (StaffMember) -> Painter? = { null },
) {
    if (selectedStaff == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = "Valitse työntekijä jatkaaksesi.",
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accentColor = Color(parseColor(selectedStaff.quickColorHex).toLong())
            StaffIdentityAvatar(
                staff = selectedStaff,
                painter = photoPainter(selectedStaff),
                accentColor = accentColor,
                modifier = Modifier.size(58.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = selectedStaff.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Rooli: ${formatStaffRole(selectedStaff.role)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (selectedStaff.isManager) "Oikeus: päällikkö" else "Oikeus: työntekijä",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StaffIdentityAvatar(
    staff: StaffMember,
    painter: Painter?,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = staff.displayName,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = staffInitials(staff.displayName),
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ManagerOverrideDialog(
    state: ManagerOverrideUiState,
    onManagerSelected: (String) -> Unit,
    onManagerDigit: (String) -> Unit,
    onManagerBackspace: () -> Unit,
    onClearManagerPin: () -> Unit,
    onConfirmManagerOverride: () -> Unit,
    onDismiss: () -> Unit,
    staffPhotoPainter: @Composable (StaffMember) -> Painter? = { null },
) {
    val selectedManager = state.managers.firstOrNull { it.id == state.selectedManagerId }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Päällikön hyväksyntä",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Hyväksyntä vaaditaan: ${formatManagerOverrideReason(state.reason)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    PosPane(
                        title = "Päällikön pikavalinta",
                        supportingText = "Vain käytössä olevat päällikköprofiilit voivat hyväksyä rajoitettuja toimintoja.",
                        modifier = Modifier.weight(1f),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.height(240.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            userScrollEnabled = false,
                        ) {
                            items(state.managers) { manager ->
                                StaffQuickSelectCard(
                                    staff = manager,
                                    selected = manager.id == state.selectedManagerId,
                                    onSelect = { onManagerSelected(manager.id) },
                                    photoPainter = staffPhotoPainter,
                                )
                            }
                        }
                    }

                    PosPane(
                        title = "Hyväksynnän PIN",
                        supportingText = "Valittu päällikkö hyväksyy toiminnon 4-numeroisella PIN-koodilla.",
                        modifier = Modifier.weight(1f),
                    ) {
                        SelectedStaffSummary(selectedStaff = selectedManager, photoPainter = staffPhotoPainter)

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Päällikön PIN",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = maskedPin(state.pin),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        state.errorMessage?.let {
                            StatusBanner(text = it, tint = MaterialTheme.colorScheme.error)
                        }

                        NumericPinPad(
                            onDigit = onManagerDigit,
                            onBackspace = onManagerBackspace,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onClearManagerPin,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Tyhjennä")
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Peruuta")
                            }
                            Button(
                                onClick = onConfirmManagerOverride,
                                enabled = state.canSubmit,
                                modifier = Modifier.weight(1.2f),
                            ) {
                                Text(if (state.isAuthorizing) "Hyväksytään..." else "Hyväksy")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun maskedPin(pin: String): String {
    return buildString {
        repeat(PosPinLength) { index ->
            append(if (index < pin.length) '•' else '○')
            if (index < PosPinLength - 1) {
                append(' ')
            }
        }
    }
}

private fun formatStaffRole(role: StaffRole): String {
    return when (role) {
        StaffRole.SERVER -> "Tarjoilija"
        StaffRole.MANAGER -> "Päällikkö"
        StaffRole.CASHIER -> "Kassa"
        StaffRole.KITCHEN -> "Keittiö"
        StaffRole.ADMIN -> "Ylläpito"
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

private fun formatManagerOverrideReason(reason: ManagerOverrideReason): String {
    return when (reason) {
        ManagerOverrideReason.REFUND -> "hyvitys"
        ManagerOverrideReason.VOID_TICKET -> "mitätöinti"
        ManagerOverrideReason.SHIFT_CLOSE -> "vuoron sulkeminen"
        ManagerOverrideReason.OPEN_CASH_DRAWER -> "kassalaatikon avaus"
        ManagerOverrideReason.SETTINGS_CHANGE -> "asetusten muutos"
    }
}
