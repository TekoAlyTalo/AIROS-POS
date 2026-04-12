package com.airos.pos.feature.tablemap

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.airos.pos.domain.OpenSaleRepository
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
private const val PREVIEW_SURFACE_ASPECT_RATIO = 4f / 3f
private const val AREA_FILTER_ALL = "All"

/**
 * Aggregated open-check info for a single service spot.
 * Phase-1 assumption: one open sale per spot. Structure supports multiple in phase 2.
 */
data class OpenCheckSummary(val count: Int, val totalCents: Int)

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
    val isLivePreviewDialogVisible: Boolean = false,
    /** Open-check summaries keyed by service spot id. Empty when no open sales exist. */
    val openChecksBySpotId: Map<String, OpenCheckSummary> = emptyMap(),
)

class TableMapViewModel(
    private val tableRepository: TableRepository,
    private val settingsRepository: SettingsRepository,
    private val cameraPreviewService: CameraPreviewService,
    private val openSaleRepository: OpenSaleRepository,
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
                mutableState.update { current ->
                    current.copy(
                        cameraPreviewState = previewState,
                        livePreviewTarget = previewState.activePreviewTarget()
                            ?: if (previewState.connectionState == CameraConnectionState.IDLE) null else current.livePreviewTarget,
                    )
                }
            }
        }
        viewModelScope.launch {
            openSaleRepository.observeOpenSales().collect { sales ->
                val bySpotId = sales
                    .filter { it.serviceSpotId != null }
                    .groupBy { it.serviceSpotId!! }
                    .mapValues { (_, spotSales) ->
                        OpenCheckSummary(
                            count = spotSales.size,
                            totalCents = spotSales.sumOf { sale ->
                                sale.lines.sumOf { it.quantity * it.unitPriceCents }
                            },
                        )
                    }
                mutableState.update { it.copy(openChecksBySpotId = bySpotId) }
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
                isLivePreviewDialogVisible = true,
                message = null,
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
        mutableState.update { it.copy(isLivePreviewDialogVisible = false) }
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
            openSaleRepository: OpenSaleRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TableMapViewModel(tableRepository, settingsRepository, cameraPreviewService, openSaleRepository) }
        }
    }
}

@Composable
fun TableMapScreen(
    state: TableMapUiState,
    currentStaffId: String?,
    preferRichFloorPlanStyle: Boolean = false,
    cameraPreviewService: CameraPreviewService,
    onSelectTable: (String) -> Unit,
    onOpenSelectedTable: (String) -> Unit,
    onJoinTables: (String) -> Unit,
    onOpenLivePreview: () -> Unit,
    onRetryLivePreview: () -> Unit,
    onCloseLivePreview: () -> Unit,
) {
    var viewModeName by rememberSaveable { mutableStateOf(TableMapViewMode.GRID.name) }
    val viewMode = TableMapViewMode.valueOf(viewModeName)
    val floorPlanStyle = if (preferRichFloorPlanStyle) FloorPlanVisualStyle.RICH else FloorPlanVisualStyle.SIMPLE
    var floorPlanRotationDeg by rememberSaveable { mutableStateOf(DefaultFloorPlanViewpoint.defaultRotationDeg) }
    val floorPlanViewpoint = remember(floorPlanRotationDeg) {
        DefaultFloorPlanViewpoint.copy(defaultRotationDeg = floorPlanRotationDeg)
    }
    val allTables = state.floorMap?.tables.orEmpty()
    val availableAreas = remember(allTables) { buildAreaFilterOptions(allTables) }
    var selectedAreaName by rememberSaveable { mutableStateOf(AREA_FILTER_ALL) }
    val activeAreaName = selectedAreaName.takeIf { it in availableAreas } ?: AREA_FILTER_ALL
    val visibleTables = remember(allTables, activeAreaName) {
        filterTablesForArea(
            tables = allTables,
            selectedAreaName = activeAreaName,
        )
    }
    val selectedTable = visibleTables.firstOrNull { it.id == state.selectedTableId } ?: visibleTables.firstOrNull()
    val desiredPreviewTarget = selectedTable?.previewTarget()
    val selectedPreviewTarget = state.livePreviewTarget?.takeIf { it.tableId == selectedTable?.id }

    var joinSourceTableId by rememberSaveable { mutableStateOf<String?>(null) }
    var joinSelectedTableIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    val isJoinMode = joinSourceTableId != null
    val joinableTableIds = remember(visibleTables, joinSourceTableId) {
        val sourceId = joinSourceTableId
        if (sourceId == null) {
            emptySet()
        } else {
            visibleTables
                .filter { table -> table.id != sourceId }
                .map { it.id }
                .toSet()
        }
    }
    val joinSourceTable = visibleTables.firstOrNull { it.id == joinSourceTableId }


    LaunchedEffect(
        desiredPreviewTarget?.tableId,
        desiredPreviewTarget?.cameraId,
        state.edgeBaseUrl,
        state.cameraPreviewState.connectionState,
        state.cameraPreviewState.tableId,
        state.cameraPreviewState.cameraId,
        state.isLivePreviewDialogVisible,
    ) {
        if (state.isLivePreviewDialogVisible) {
            return@LaunchedEffect
        }
        val target = desiredPreviewTarget ?: return@LaunchedEffect
        val edgeBaseUrl = state.edgeBaseUrl?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (!state.cameraPreviewState.shouldStartPreviewFor(target)) {
            return@LaunchedEffect
        }
        cameraPreviewService.startPreview(
            CameraPreviewRequest(
                cameraId = target.cameraId,
                signalingBaseUrl = edgeBaseUrl.toAutoStartSignalingBaseUrl(),
                tableId = target.tableId,
                tableLabel = target.tableLabel,
                sourceLabel = target.cameraLabel,
            ),
        )
    }

    LaunchedEffect(joinSourceTableId, visibleTables) {
        val sourceId = joinSourceTableId ?: return@LaunchedEffect
        if (visibleTables.none { it.id == sourceId }) {
            joinSourceTableId = null
            joinSelectedTableIds = emptySet()
        } else {
            joinSelectedTableIds = joinSelectedTableIds.intersect(joinableTableIds)
        }
    }

    LaunchedEffect(activeAreaName, allTables) {
        if (activeAreaName == AREA_FILTER_ALL) {
            return@LaunchedEffect
        }
        if (state.selectedTableId != null && visibleTables.any { it.id == state.selectedTableId }) {
            return@LaunchedEffect
        }
        visibleTables.firstOrNull()?.id?.let(onSelectTable)
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1.5f),
            shape = RoundedCornerShape(28.dp),
            color = TableMapVisualTokens.ShellColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, TableMapVisualTokens.BorderColor),
            contentColor = TableMapVisualTokens.TextPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.message?.let {
                    StatusBanner(
                        text = it,
                        tint = if (it.contains("opened", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }



                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = state.floorMap?.name ?: "Table map",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TableMapVisualTokens.TextPrimary,
                    )
                    TableMapViewModeToggle(
                        viewMode = viewMode,
                        onViewModeChange = { viewModeName = it.name },
                    )
                }

                if (availableAreas.size > 1) {
                    TableMapAreaSelector(
                        areas = availableAreas,
                        selectedArea = activeAreaName,
                        onAreaSelected = { areaName ->
                            selectedAreaName = areaName
                            val areaTables = filterTablesForArea(
                                tables = allTables,
                                selectedAreaName = areaName,
                            )
                            when {
                                areaTables.isEmpty() -> Unit
                                state.selectedTableId != null && areaTables.any { it.id == state.selectedTableId } -> Unit
                                else -> onSelectTable(areaTables.first().id)
                            }
                        },
                    )
                }

                when (viewMode) {
                    TableMapViewMode.GRID -> {
                        LazyVerticalGrid(
                            modifier = Modifier.weight(1f, fill = true),
                            columns = GridCells.Adaptive(minSize = 170.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visibleTables, key = { it.id }) { table ->
                                val isJoinSource = table.id == joinSourceTableId
                                val isJoinSelected = table.id in joinSelectedTableIds
                                val isJoinSelectable = table.id in joinableTableIds
                                TableGridCard(
                                    table = table,
                                    selected = table.id == selectedTable?.id,
                                    joinMode = isJoinMode,
                                    joinSource = isJoinSource,
                                    joinSelected = isJoinSelected,
                                    joinSelectable = isJoinSelectable,
                                    openCheckSummary = state.openChecksBySpotId[table.id],
                                    onClick = {
                                        onSelectTable(table.id)
                                        if (isJoinMode && !isJoinSource && isJoinSelectable) {
                                            joinSelectedTableIds = if (table.id in joinSelectedTableIds) {
                                                joinSelectedTableIds - table.id
                                            } else {
                                                joinSelectedTableIds + table.id
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    TableMapViewMode.FLOOR_PLAN -> {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth(),
                        ) {
                            FloorPlanTableMap(
                                tables = visibleTables,
                                selectedTableId = selectedTable?.id,
                                onSelectTable = { tableId ->
                                    onSelectTable(tableId)
                                    if (isJoinMode && tableId != joinSourceTableId && tableId in joinableTableIds) {
                                        joinSelectedTableIds = if (tableId in joinSelectedTableIds) {
                                            joinSelectedTableIds - tableId
                                        } else {
                                            joinSelectedTableIds + tableId
                                        }
                                    }
                                },
                                style = floorPlanStyle,
                                viewpoint = floorPlanViewpoint,
                                modifier = Modifier.fillMaxSize(),
                            )

                            TextButton(
                                onClick = {
                                    floorPlanRotationDeg = ((floorPlanRotationDeg + 90f) % 360f)
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                            ) {
                                Text("Rotate 90°")
                            }
                        }
                    }
                }
            }
        }

        PosPane(
            title = selectedTable?.label ?: "Table details",
            supportingText = "",
            modifier = Modifier
                .weight(0.67f)
                .fillMaxHeight(),
        ) {
            if (selectedTable == null) {
                Text("Select a table to continue.")
            } else {
                TableDetailsContent(
                    table = selectedTable,
                    currentStaffId = currentStaffId,
                    previewState = state.cameraPreviewState,
                    previewTarget = selectedPreviewTarget,
                    isLivePreviewDialogVisible = state.isLivePreviewDialogVisible,
                    cameraPreviewService = cameraPreviewService,
                    canOpenLivePreview = !selectedTable.cameraId.isNullOrBlank() && !state.edgeBaseUrl.isNullOrBlank(),
                    joinMode = isJoinMode,
                    openCheckSummary = state.openChecksBySpotId[selectedTable.id],
                    joinSourceTableLabel = joinSourceTable?.label,
                    joinSelectedTableLabels = visibleTables.filter { it.id in joinSelectedTableIds }.map { it.label },
                    canConfirmJoin = joinSelectedTableIds.isNotEmpty(),
                    onOpenSelectedTable = onOpenSelectedTable,
                    onJoinTables = {
                        joinSourceTableId = selectedTable.id
                        joinSelectedTableIds = emptySet()
                        onSelectTable(selectedTable.id)
                    },
                    onCancelJoin = {
                        joinSourceTableId = null
                        joinSelectedTableIds = emptySet()
                    },
                    onConfirmJoin = {
                        joinSourceTableId?.let(onJoinTables)
                        joinSourceTableId = null
                        joinSelectedTableIds = emptySet()
                    },
                    onOpenLivePreview = onOpenLivePreview,
                )
            }
        }
    }

    if (state.isLivePreviewDialogVisible) {
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
}


@Composable
private fun TableDetailsContent(
    table: RestaurantTable,
    currentStaffId: String?,
    previewState: CameraPreviewState,
    previewTarget: TableLivePreviewTarget?,
    isLivePreviewDialogVisible: Boolean,
    cameraPreviewService: CameraPreviewService,
    canOpenLivePreview: Boolean,
    joinMode: Boolean,
    openCheckSummary: OpenCheckSummary?,
    joinSourceTableLabel: String?,
    joinSelectedTableLabels: List<String>,
    canConfirmJoin: Boolean,
    onOpenSelectedTable: (String) -> Unit,
    onJoinTables: (String) -> Unit,
    onCancelJoin: () -> Unit,
    onConfirmJoin: () -> Unit,
    onOpenLivePreview: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyValueRow("Area", table.areaName)
        KeyValueRow("Status", table.status.name)
        KeyValueRow("Seats", table.seats.toString())
        KeyValueRow("Guests", table.guestCount.toString())
        KeyValueRow("Camera", table.cameraLabel ?: "Not assigned")
        if (openCheckSummary != null) {
            KeyValueRow("Open checks", openCheckSummary.count.toString())
            KeyValueRow("Open total", formatOpenTotal(openCheckSummary.totalCents))
        }

        val mergedHint = mergedHintFor(table)
        if (mergedHint != null) {
            KeyValueRow("Merged", mergedHint)
        }

        if (joinMode) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Join mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = joinModeSummary(joinSourceTableLabel, joinSelectedTableLabels),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onConfirmJoin,
                            enabled = canConfirmJoin,
                        ) {
                            Text("Confirm join")
                        }
                        OutlinedButton(onClick = onCancelJoin) {
                            Text("Cancel")
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { currentStaffId?.let(onOpenSelectedTable) },
                    enabled = currentStaffId != null,
                ) {
                    Text("Open table")
                }
                Button(onClick = { onJoinTables(table.id) }) {
                    Text("Join tables")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
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

                val isMiniVisibleOwner = previewTarget != null && !isLivePreviewDialogVisible
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(PREVIEW_SURFACE_ASPECT_RATIO)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isMiniVisibleOwner) {
                            LiveVideoSurface(
                                cameraPreviewService = cameraPreviewService,
                                ownerKey = "mini:${previewTarget.tableId}:${previewTarget.cameraId}",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (!canOpenLivePreview || !previewState.isStreaming || !isMiniVisibleOwner) {
                            val overlayText = when {
                                !canOpenLivePreview -> "Assign a camera and configure edge URL to enable live preview."
                                previewTarget == null -> "Starting live preview..."
                                isLivePreviewDialogVisible -> "Live preview is open in the enlarged view."
                                previewState.errorMessage?.isNotBlank() == true -> previewState.errorMessage ?: previewState.detailMessage
                                else -> previewState.detailMessage
                            }
                            Text(
                                text = overlayText,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (previewState.connectionState == CameraConnectionState.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = onOpenLivePreview,
                    enabled = canOpenLivePreview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open live view")
                }
            }
        }
    }
}


private fun buildAreaFilterOptions(tables: List<RestaurantTable>): List<String> {
    val areaNames = tables
        .map { it.areaName.trim().ifBlank { "Unassigned" } }
        .distinct()
        .sorted()

    return if (areaNames.isEmpty()) {
        listOf(AREA_FILTER_ALL)
    } else {
        buildList {
            add(AREA_FILTER_ALL)
            addAll(areaNames)
        }
    }
}

private fun filterTablesForArea(
    tables: List<RestaurantTable>,
    selectedAreaName: String,
): List<RestaurantTable> {
    if (selectedAreaName == AREA_FILTER_ALL) {
        return tables
    }
    return tables.filter { it.areaName.trim().ifBlank { "Unassigned" } == selectedAreaName }
}

@Composable
private fun TableMapAreaSelector(
    areas: List<String>,
    selectedArea: String,
    onAreaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        areas.forEach { areaName ->
            val selected = areaName == selectedArea
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)),
                shape = RoundedCornerShape(999.dp),
                color = if (selected) TableMapVisualTokens.PanelAccentColor else TableMapVisualTokens.PanelAltColor,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) TableMapVisualTokens.AccentText.copy(alpha = 0.55f) else TableMapVisualTokens.BorderColor,
                ),
                onClick = { onAreaSelected(areaName) },
            ) {
                Text(
                    text = areaName,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (selected) TableMapVisualTokens.AccentText else TableMapVisualTokens.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}


@Composable
private fun TableGridCard(
    table: RestaurantTable,
    selected: Boolean,
    joinMode: Boolean,
    joinSource: Boolean,
    joinSelected: Boolean,
    joinSelectable: Boolean,
    openCheckSummary: OpenCheckSummary?,
    onClick: () -> Unit,
) {
    val mergedHint = mergedHintFor(table)
    val accent = when (table.status.name) {
        "OCCUPIED" -> MaterialTheme.colorScheme.primary
        "DIRTY" -> MaterialTheme.colorScheme.error
        "RESERVED" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    val cardColor = when {
        joinSource -> MaterialTheme.colorScheme.primaryContainer
        joinSelected -> MaterialTheme.colorScheme.secondaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        joinMode && !joinSelectable -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .height(164.dp)
            .alpha(if (joinMode && !joinSource && !joinSelected && !joinSelectable) 0.42f else 1f)
            .border(
                width = if (joinSource || joinSelected) 2.dp else 0.dp,
                color = when {
                    joinSource -> MaterialTheme.colorScheme.primary
                    joinSelected -> MaterialTheme.colorScheme.secondary
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
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
                if (openCheckSummary != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStatusChip(
                            label = "${openCheckSummary.count} open",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        MiniStatusChip(
                            label = formatOpenTotal(openCheckSummary.totalCents),
                            tint = MaterialTheme.colorScheme.tertiary,
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

private fun joinModeSummary(
    sourceLabel: String?,
    selectedLabels: List<String>,
): String {
    val primary = sourceLabel ?: "No source table"
    return if (selectedLabels.isEmpty()) {
        "Primary: $primary. Select tables from the map."
    } else {
        "Primary: $primary. Selected: ${selectedLabels.joinToString(", ")}"
    }
}

private fun formatOpenTotal(cents: Int): String {
    val major = cents / 100
    val minor = cents % 100
    return "$major,${minor.toString().padStart(2, '0')} €"
}

fun mergedHintFor(table: RestaurantTable): String? {
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
                .fillMaxWidth(0.94f)
                .sizeIn(maxWidth = 1100.dp, maxHeight = 760.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.width(248.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(560.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(PREVIEW_SURFACE_ASPECT_RATIO, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveVideoSurface(
                            cameraPreviewService = cameraPreviewService,
                            ownerKey = "dialog:${target.tableId}:${target.cameraId}",
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
                }
            }
        }
    }
}

@Composable
private fun LiveVideoSurface(
    cameraPreviewService: CameraPreviewService,
    ownerKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sharedContext = cameraPreviewService.eglBaseContext
    val renderer = remember(context, sharedContext, ownerKey) {
        if (sharedContext == null) {
            Log.w(PREVIEW_TAG, "Skipping renderer init because shared EGL context is null owner=$ownerKey")
            null
        } else {
            createPreviewRenderer(context, sharedContext)
        }
    }
    renderer?.let { currentRenderer ->
        AndroidView(
            modifier = modifier,
            factory = { currentRenderer },
            update = {
                Log.i(PREVIEW_TAG, "Renderer attached renderer=${it.logLabel()} owner=$ownerKey")
            },
        )
    }
    DisposableEffect(cameraPreviewService, renderer, ownerKey) {
        renderer?.let { currentRenderer ->
            Log.i(
                PREVIEW_TAG,
                "Attaching renderer sink renderer=${currentRenderer.logLabel()} owner=$ownerKey sharedContext=${sharedContext?.javaClass?.name ?: "null"}",
            )
            cameraPreviewService.attachVideoSink(currentRenderer)
        }
        onDispose {
            renderer?.let { currentRenderer ->
                Log.i(PREVIEW_TAG, "Detaching renderer sink renderer=${currentRenderer.logLabel()} owner=$ownerKey")
                cameraPreviewService.detachVideoSink(currentRenderer)
                Log.i(PREVIEW_TAG, "Releasing renderer renderer=${currentRenderer.logLabel()} owner=$ownerKey")
                currentRenderer.release()
            }
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

private fun CameraPreviewState.activePreviewTarget(): TableLivePreviewTarget? {
    if (connectionState == CameraConnectionState.IDLE) {
        return null
    }
    val resolvedTableId = tableId ?: return null
    val resolvedCameraId = cameraId ?: return null
    return TableLivePreviewTarget(
        tableId = resolvedTableId,
        tableLabel = tableLabel ?: resolvedTableId,
        cameraId = resolvedCameraId,
        cameraLabel = sourceLabel.ifBlank { resolvedCameraId },
    )
}

private fun RestaurantTable.previewTarget(): TableLivePreviewTarget? {
    val resolvedCameraId = cameraId ?: return null
    return TableLivePreviewTarget(
        tableId = id,
        tableLabel = label,
        cameraId = resolvedCameraId,
        cameraLabel = cameraLabel ?: resolvedCameraId,
    )
}

private fun CameraPreviewState.shouldStartPreviewFor(target: TableLivePreviewTarget): Boolean {
    return connectionState == CameraConnectionState.IDLE ||
        connectionState == CameraConnectionState.ERROR ||
        tableId != target.tableId ||
        cameraId != target.cameraId
}

private fun String.toAutoStartSignalingBaseUrl(): String {
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
