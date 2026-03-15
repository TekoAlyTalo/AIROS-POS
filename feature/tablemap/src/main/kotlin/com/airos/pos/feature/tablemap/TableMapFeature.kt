package com.airos.pos.feature.tablemap

import android.content.Context
import android.util.Log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.CameraConnectionState
import com.airos.pos.core.model.CameraPreviewRequest
import com.airos.pos.core.model.CameraPreviewState
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.device.camera.CameraPreviewService
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.TableRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.net.URI

private const val SIGNALING_PORT = 8000
private const val PREVIEW_TAG = "TableLivePreview"

data class TableLivePreviewTarget(
    val tableId: String,
    val tableLabel: String,
    val cameraId: String,
    val cameraLabel: String,
)

data class TableMapUiState(
    val floorMap: FloorMap? = null,
    val selectedTableId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val edgeBaseUrl: String? = null,
    val cameraPreviewState: CameraPreviewState = CameraPreviewState(),
    val livePreviewTarget: TableLivePreviewTarget? = null,
)

class TableMapViewModel(
    private val tableRepository: TableRepository,
    private val settingsRepository: SettingsRepository,
    private val cameraPreviewService: CameraPreviewService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TableMapUiState())
    val uiState: StateFlow<TableMapUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            tableRepository.observeFloorMap().collect { floorMap ->
                mutableState.update { current ->
                    current.copy(
                        floorMap = floorMap,
                        selectedTableId = current.selectedTableId ?: floorMap.tables.firstOrNull()?.id,
                        busy = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                mutableState.update { it.copy(edgeBaseUrl = settings.edgeBaseUrl) }
            }
        }
        viewModelScope.launch {
            cameraPreviewService.previewState.collect { previewState ->
                mutableState.update { it.copy(cameraPreviewState = previewState) }
            }
        }
    }

    fun selectTable(tableId: String) {
        mutableState.update { it.copy(selectedTableId = tableId, message = null) }
    }

    fun openSelectedTable(staffId: String) {
        val tableId = mutableState.value.selectedTableId ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = tableRepository.openTable(tableId, guestCount = 2, openedByStaffId = staffId)) {
                is PosResult.Success -> mutableState.update { it.copy(busy = false, message = "${result.value.label} opened.") }
                is PosResult.Failure -> mutableState.update { it.copy(busy = false, message = result.message) }
            }
        }
    }

    fun openLivePreview() {
        val current = mutableState.value
        val table = current.floorMap?.tables?.firstOrNull { it.id == current.selectedTableId } ?: return
        val cameraId = table.cameraId
        if (cameraId.isNullOrBlank()) {
            mutableState.update { it.copy(message = "No live camera is assigned to ${table.label}.") }
            return
        }
        val edgeBaseUrl = current.edgeBaseUrl
        if (edgeBaseUrl.isNullOrBlank()) {
            mutableState.update { it.copy(message = "Edge signaling URL is not configured yet.") }
            return
        }

        val target = TableLivePreviewTarget(
            tableId = table.id,
            tableLabel = table.label,
            cameraId = cameraId,
            cameraLabel = table.cameraLabel ?: cameraId,
        )
        mutableState.update {
            it.copy(
                livePreviewTarget = target,
                message = "Starting live preview for ${target.cameraLabel}...",
            )
        }

        startPreview(target = target, edgeBaseUrl = edgeBaseUrl)
    }

    fun retryLivePreview() {
        val current = mutableState.value
        val target = current.livePreviewTarget ?: return
        val edgeBaseUrl = current.edgeBaseUrl
        if (edgeBaseUrl.isNullOrBlank()) {
            mutableState.update { it.copy(message = "Edge signaling URL is not configured yet.") }
            return
        }

        mutableState.update {
            it.copy(message = "Retrying live preview for ${target.cameraLabel}...")
        }

        startPreview(target = target, edgeBaseUrl = edgeBaseUrl)
    }

    fun closeLivePreview() {
        viewModelScope.launch {
            cameraPreviewService.stopPreview()
            mutableState.update { it.copy(livePreviewTarget = null) }
        }
    }

    private fun startPreview(target: TableLivePreviewTarget, edgeBaseUrl: String) {
        viewModelScope.launch {
            cameraPreviewService.startPreview(
                CameraPreviewRequest(
                    cameraId = target.cameraId,
                    signalingBaseUrl = edgeBaseUrl.toSignalingBaseUrl(),
                    tableId = target.tableId,
                    tableLabel = target.tableLabel,
                    sourceLabel = target.cameraLabel,
                ),
            )
        }
    }

    private fun String.toSignalingBaseUrl(): String {
        val normalized = trim()
        if (normalized.isBlank()) {
            return normalized
        }

        return try {
            val uri = URI(normalized)
            val host = uri.host ?: return normalized
            val scheme = uri.scheme ?: return normalized
            URI(
                scheme,
                uri.userInfo,
                host,
                SIGNALING_PORT,
                null,
                null,
                null,
            ).toString()
        } catch (_: Exception) {
            normalized
        }
    }

    companion object {
        fun factory(
            tableRepository: TableRepository,
            settingsRepository: SettingsRepository,
            cameraPreviewService: CameraPreviewService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TableMapViewModel(tableRepository, settingsRepository, cameraPreviewService) }
        }
    }
}

@Composable
fun TableMapScreen(
    state: TableMapUiState,
    currentStaffId: String?,
    cameraPreviewService: CameraPreviewService,
    onSelectTable: (String) -> Unit,
    onOpenSelectedTable: (String) -> Unit,
    onOpenTicket: (String) -> Unit,
    onOpenLivePreview: () -> Unit,
    onRetryLivePreview: () -> Unit,
    onCloseLivePreview: () -> Unit,
) {
    val selectedTable = state.floorMap?.tables?.firstOrNull { it.id == state.selectedTableId }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PosPane(
            title = state.floorMap?.name ?: "Table map",
            supportingText = "Fast table selection with seats, status, and on-demand live table view.",
            modifier = Modifier.weight(1.5f),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Text(
                    text = "AIROS POS",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            state.message?.let {
                StatusBanner(
                    text = it,
                    tint = if (it.contains("opened", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.floorMap?.tables.orEmpty()) { table ->
                    TableGridCard(
                        table = table,
                        selected = table.id == state.selectedTableId,
                        onClick = { onSelectTable(table.id) },
                    )
                }
            }
        }

        PosPane(
            title = selectedTable?.label ?: "Table details",
            supportingText = "Open table, jump to ticket, and inspect the live camera without leaving this view.",
            modifier = Modifier.weight(1f),
        ) {
            if (selectedTable == null) {
                Text("Select a table to continue.")
            } else {
                TableDetailsContent(
                    table = selectedTable,
                    currentStaffId = currentStaffId,
                    previewState = state.cameraPreviewState,
                    cameraPreviewService = cameraPreviewService,
                    canOpenLivePreview = !selectedTable.cameraId.isNullOrBlank() && !state.edgeBaseUrl.isNullOrBlank(),
                    onOpenSelectedTable = onOpenSelectedTable,
                    onOpenTicket = onOpenTicket,
                    onOpenLivePreview = onOpenLivePreview,
                )
            }
        }
    }

    state.livePreviewTarget?.let { target ->
        TableLivePreviewDialog(
            target = target,
            previewState = state.cameraPreviewState,
            cameraPreviewService = cameraPreviewService,
            onRetry = onRetryLivePreview,
            onDismiss = onCloseLivePreview,
        )
    }
}


@Composable
private fun TableDetailsContent(
    table: RestaurantTable,
    currentStaffId: String?,
    previewState: CameraPreviewState,
    cameraPreviewService: CameraPreviewService,
    canOpenLivePreview: Boolean,
    onOpenSelectedTable: (String) -> Unit,
    onOpenTicket: (String) -> Unit,
    onOpenLivePreview: () -> Unit,
) {
    KeyValueRow("Area", table.areaName)
    KeyValueRow("Status", table.status.name)
    KeyValueRow("Seats", table.seats.toString())
    KeyValueRow("Guests", table.guestCount.toString())
    KeyValueRow("Camera", table.cameraLabel ?: "Not assigned")

    val mergedHint = mergedHintFor(table)
    if (mergedHint != null) {
        KeyValueRow("Merged", mergedHint)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { currentStaffId?.let(onOpenSelectedTable) },
            enabled = currentStaffId != null,
        ) {
            Text("Open table")
        }
        Button(onClick = { onOpenTicket(table.id) }) {
            Text("Open ticket")
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Mini live preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = table.cameraLabel ?: table.cameraId ?: "No assigned camera",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewStatusPill(previewState.connectionState)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (canOpenLivePreview) {
                    LiveVideoSurface(
                        cameraPreviewService = cameraPreviewService,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!canOpenLivePreview || !previewState.isStreaming) {
                    val overlayText = when {
                        !canOpenLivePreview -> "Assign a camera and configure edge URL to enable live preview."
                        previewState.errorMessage?.isNotBlank() == true -> previewState.errorMessage ?: previewState.detailMessage
                        else -> previewState.detailMessage
                    }
                    Text(
                        text = overlayText,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (previewState.connectionState == CameraConnectionState.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Button(
                onClick = onOpenLivePreview,
                enabled = canOpenLivePreview,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enlarge live view")
            }
        }
    }
}



@Composable
private fun TableGridCard(
    table: RestaurantTable,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val mergedHint = mergedHintFor(table)
    val accent = when (table.status.name) {
        "OCCUPIED" -> MaterialTheme.colorScheme.primary
        "DIRTY" -> MaterialTheme.colorScheme.error
        "RESERVED" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = Modifier
            .height(164.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = table.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = table.areaName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mergedHint != null) {
                    MiniStatusChip(label = mergedHint, tint = accent)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatusChip(label = table.status.name, tint = accent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    ) {
                        Text(
                            text = "${table.seats} seats",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    ) {
                        Text(
                            text = "${table.guestCount} guests",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStatusChip(label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun mergedHintFor(table: RestaurantTable): String? {
    val normalized = table.label.uppercase()
    return when {
        "+" in normalized -> normalized
        "&" in normalized -> normalized
        "MERGE" in normalized -> "Merged"
        else -> null
    }
}

@Composable
private fun TableLivePreviewDialog(
    target: TableLivePreviewTarget,
    previewState: CameraPreviewState,
    cameraPreviewService: CameraPreviewService,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val errorMessage = previewState.errorMessage
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .sizeIn(maxWidth = 820.dp, maxHeight = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = target.tableLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = target.cameraLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PreviewStatusPill(previewState.connectionState)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveVideoSurface(
                        cameraPreviewService = cameraPreviewService,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!previewState.isStreaming) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = previewState.detailMessage,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!errorMessage.isNullOrBlank()) {
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                KeyValueRow("Table", target.tableLabel)
                KeyValueRow("Resolved camera id", target.cameraId)
                KeyValueRow("State", previewState.connectionState.name)

                if (!errorMessage.isNullOrBlank() && previewState.connectionState == CameraConnectionState.ERROR) {
                    StatusBanner(
                        text = errorMessage,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (previewState.connectionState == CameraConnectionState.ERROR) {
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveVideoSurface(
    cameraPreviewService: CameraPreviewService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sharedContext = cameraPreviewService.eglBaseContext
    val rendererState = remember(context, sharedContext) {
        if (sharedContext == null) {
            Log.w(PREVIEW_TAG, "Skipping renderer init because shared EGL context is null")
            mutableStateOf<SurfaceViewRenderer?>(null)
        } else {
            mutableStateOf(createPreviewRenderer(context, sharedContext))
        }
    }
    rendererState.value?.let { renderer ->
        AndroidView(
            modifier = modifier,
            factory = { renderer },
            update = { currentRenderer ->
                Log.i(PREVIEW_TAG, "Renderer attached renderer=${currentRenderer.logLabel()}")
            },
        )
    }
    DisposableEffect(cameraPreviewService, rendererState.value) {
        rendererState.value?.let { renderer ->
            Log.i(
                PREVIEW_TAG,
                "Attaching renderer sink renderer=${renderer.logLabel()} sharedContext=${sharedContext?.javaClass?.name ?: "null"}",
            )
            cameraPreviewService.attachVideoSink(renderer)
        }
        onDispose {
            rendererState.value?.let { renderer ->
                Log.i(PREVIEW_TAG, "Detaching renderer sink renderer=${renderer.logLabel()}")
                cameraPreviewService.detachVideoSink(renderer)
                Log.i(PREVIEW_TAG, "Releasing renderer renderer=${renderer.logLabel()}")
                renderer.release()
            }
            rendererState.value = null
        }
    }
}

private fun createPreviewRenderer(
    context: Context,
    sharedContext: EglBase.Context,
): SurfaceViewRenderer? {
    return tryCreateRenderer(
        context = context,
        sharedContext = sharedContext,
        initLabel = "shared",
    )
}

private fun tryCreateRenderer(
    context: Context,
    sharedContext: EglBase.Context,
    initLabel: String,
): SurfaceViewRenderer? {
    val renderer = SurfaceViewRenderer(context).apply {
        setMirror(false)
        setEnableHardwareScaler(true)
    }
    Log.i(
        PREVIEW_TAG,
        "Initializing renderer mode=$initLabel renderer=${renderer.logLabel()} sharedContext=${sharedContext?.javaClass?.name ?: "null"}",
    )
    return try {
        renderer.init(sharedContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer
    } catch (error: RuntimeException) {
        Log.e(
            PREVIEW_TAG,
            "Renderer init failed mode=$initLabel renderer=${renderer.logLabel()} sharedContext=${sharedContext.javaClass.name}",
            error,
        )
        try {
            renderer.release()
        } catch (_: RuntimeException) {
            Unit
        }
        null
    }
}

private fun SurfaceViewRenderer.logLabel(): String {
    return "SurfaceViewRenderer@${Integer.toHexString(hashCode())}"
}

@Composable
private fun PreviewStatusPill(connectionState: CameraConnectionState) {
    val tint = when (connectionState) {
        CameraConnectionState.CONNECTING,
        CameraConnectionState.WAITING_FOR_VIDEO,
        CameraConnectionState.RECONNECTING,
        -> MaterialTheme.colorScheme.tertiary

        CameraConnectionState.LIVE -> MaterialTheme.colorScheme.primary
        CameraConnectionState.ERROR -> MaterialTheme.colorScheme.error
        CameraConnectionState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = when (connectionState) {
                CameraConnectionState.CONNECTING -> "Connecting"
                CameraConnectionState.WAITING_FOR_VIDEO -> "Waiting for video"
                CameraConnectionState.LIVE -> "Live"
                CameraConnectionState.RECONNECTING -> "Reconnecting"
                CameraConnectionState.ERROR -> "Error"
                CameraConnectionState.IDLE -> "Idle"
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
