package com.airos.pos.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.model.CameraPreviewState
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.device.camera.CameraPreviewService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraUiState(
    val previewState: CameraPreviewState = CameraPreviewState(isStreaming = false, sourceLabel = "Camera"),
)

class CameraViewModel(
    private val cameraPreviewService: CameraPreviewService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            cameraPreviewService.previewState.collect { previewState ->
                mutableState.update { it.copy(previewState = previewState) }
            }
        }
    }

    fun startPreview() {
        viewModelScope.launch { cameraPreviewService.startPreview() }
    }

    fun stopPreview() {
        viewModelScope.launch { cameraPreviewService.stopPreview() }
    }

    companion object {
        fun factory(cameraPreviewService: CameraPreviewService): ViewModelProvider.Factory = viewModelFactory {
            initializer { CameraViewModel(cameraPreviewService) }
        }
    }
}

@Composable
fun CameraScreen(
    state: CameraUiState,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    PosPane(
        title = "Camera preview",
        supportingText = "This placeholder surface is where CameraX or vendor preview rendering will attach.",
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.previewState.isStreaming) "Preview streaming" else "Preview idle")
        }
        KeyValueRow("Source", state.previewState.sourceLabel)
        KeyValueRow("Streaming", state.previewState.isStreaming.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStartPreview) { Text("Start preview") }
            Button(onClick = onStopPreview) { Text("Stop preview") }
        }
    }
}
