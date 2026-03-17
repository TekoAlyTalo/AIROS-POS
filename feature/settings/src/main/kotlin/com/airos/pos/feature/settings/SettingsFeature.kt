package com.airos.pos.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.device.platform.DeviceInfoService
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.SyncQueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: TerminalSettings? = null,
    val terminalNameInput: String = "",
    val edgeBaseUrlInput: String = "",
    val queueDepth: Int = 0,
    val deviceProfile: DeviceProfile? = null,
    val message: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val syncQueueRepository: SyncQueueRepository,
    deviceInfoService: DeviceInfoService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            deviceProfile = deviceInfoService.currentProfile(),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                mutableState.update { current ->
                    current.copy(
                        settings = settings,
                        terminalNameInput = if (current.terminalNameInput.isBlank()) settings.terminalName else current.terminalNameInput,
                        edgeBaseUrlInput = if (current.edgeBaseUrlInput.isBlank()) settings.edgeBaseUrl else current.edgeBaseUrlInput,
                    )
                }
            }
        }
        viewModelScope.launch {
            syncQueueRepository.observeQueue().collect { queue ->
                mutableState.update { it.copy(queueDepth = queue.size) }
            }
        }
    }

    fun updateTerminalNameInput(value: String) {
        mutableState.update { it.copy(terminalNameInput = value) }
    }

    fun updateEdgeBaseUrlInput(value: String) {
        mutableState.update { it.copy(edgeBaseUrlInput = value) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            settingsRepository.updateTerminalName(mutableState.value.terminalNameInput)
            settingsRepository.updateEdgeBaseUrl(mutableState.value.edgeBaseUrlInput)
            mutableState.update { it.copy(message = "Settings saved locally.") }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOfflineMode(enabled)
            mutableState.update { it.copy(message = "Offline mode ${if (enabled) "enabled" else "disabled"}.") }
        }
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            syncQueueRepository: SyncQueueRepository,
            deviceInfoService: DeviceInfoService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(settingsRepository, syncQueueRepository, deviceInfoService) }
        }
    }
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onTerminalNameChanged: (String) -> Unit,
    onEdgeBaseUrlChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onOfflineModeChanged: (Boolean) -> Unit,
) {
    PosPane(
        title = "Settings",
        supportingText = "Terminal config, device diagnostics, and sync visibility live here.",
        modifier = Modifier.fillMaxSize(),
    ) {
        state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
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
        Button(onClick = onSaveSettings) {
            Text("Save settings")
        }
        KeyValueRow("Queued writes", state.queueDepth.toString())
        KeyValueRow("Device", state.deviceProfile?.model ?: "-")
        KeyValueRow("Vendor", state.deviceProfile?.vendor?.name ?: "-")
        KeyValueRow("Printer", state.deviceProfile?.hasBuiltInPrinter?.toString() ?: "-")
        KeyValueRow("Scanner", state.deviceProfile?.hasScanner?.toString() ?: "-")
    }
}
