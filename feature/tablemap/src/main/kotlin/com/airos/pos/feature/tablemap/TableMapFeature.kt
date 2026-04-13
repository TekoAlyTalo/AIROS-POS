package com.airos.pos.feature.tablemap

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
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
import com.airos.pos.core.model.CameraConnectionState
import com.airos.pos.core.model.CameraPreviewRequest
import com.airos.pos.core.model.CameraPreviewState
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import com.airos.pos.core.model.StaffTableMapViewPreference
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.device.camera.CameraPreviewService
import com.airos.pos.domain.OpenSaleRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.StaffUiPreferencesRepository
import com.airos.pos.domain.TableRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.net.URI
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset

private const val SIGNALING_PORT = 8000
private const val PREVIEW_TAG = "TableLivePreview"
private const val PREVIEW_SURFACE_ASPECT_RATIO = 4f / 3f
private const val AREA_FILTER_ALL = "All"

// Compose pointer event consumption helper.
// Our current Compose version does not expose PointerInputChange.consume(), so we provide a local no-op.
private fun PointerInputChange.consume() { /* no-op */ }

/**
 * Aggregated open-check info for a single service spot.
 * Each service spot can have one or more persisted open sales.
 */
data class OpenCheckSummary(val count: Int, val totalCents: Int)

enum class TableTransferStage {
    SELECTING_BILLS,
    PICKING_TARGET,
}



enum class TableSaleOpenSource {
    TABLE_TAP,
    BILL_ROW,
    NEW_SALE_BUTTON,
}
data class TableTransferState(
    val sourceSpotId: String,
    val sourceSpotLabel: String,
    val selectedSaleIds: Set<String> = emptySet(),
    val stage: TableTransferStage = TableTransferStage.SELECTING_BILLS,
)

private data class BillDragUiState(
    val active: Boolean = false,
    val positionInRoot: Offset = Offset.Zero,
    val saleCount: Int = 0,
    val sourceSpotId: String? = null,
    val hoveredTableId: String? = null,
)

data class TableLivePreviewTarget(
    val tableId: String,
    val tableLabel: String,
    val cameraId: String,
    val cameraLabel: String,
)

data class TableMapUiState(
    val floorMap: FloorMap? = null,
    val selectedTableId: String? = null,
    val viewMode: StaffTableMapViewPreference = StaffTableMapViewPreference.FLOOR_PLAN,
    val floorPlanViewport: StaffFloorPlanViewportPreference = StaffFloorPlanViewportPreference(),
    val busy: Boolean = false,
    val message: String? = null,
    val edgeBaseUrl: String? = null,
    val cameraPreviewState: CameraPreviewState = CameraPreviewState(),
    val livePreviewTarget: TableLivePreviewTarget? = null,
    val isLivePreviewDialogVisible: Boolean = false,
    /** Open-check summaries keyed by service spot id. Empty when no open sales exist. */
    val openChecksBySpotId: Map<String, OpenCheckSummary> = emptyMap(),
    /** Persisted open sales keyed by service spot id. This is the table map bill truth. */
    val openSalesBySpotId: Map<String, List<PersistedOpenSale>> = emptyMap(),
    val transferState: TableTransferState? = null,
)

class TableMapViewModel(
    private val currentStaffId: String,
    private val tableRepository: TableRepository,
    private val settingsRepository: SettingsRepository,
    private val cameraPreviewService: CameraPreviewService,
    private val openSaleRepository: OpenSaleRepository,
    private val staffUiPreferencesRepository: StaffUiPreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TableMapUiState())
    val uiState: StateFlow<TableMapUiState> = mutableState.asStateFlow()
    private var floorPlanViewportSaveJob: Job? = null

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
            staffUiPreferencesRepository.observeStaffUiPreferences(currentStaffId).collect { preferences ->
                mutableState.update {
                    it.copy(
                        viewMode = preferences.tableMapViewMode,
                        floorPlanViewport = preferences.floorPlanViewport,
                    )
                }
            }
        }

        viewModelScope.launch {
            openSaleRepository.observeOpenSales().collect { sales ->
                val salesBySpotId = sales
                    .filter { it.serviceSpotId != null }
                    .groupBy { it.serviceSpotId!! }
                    .mapValues { (_, spotSales) -> spotSales.sortedBy { it.createdAtEpochMillis } }
                val bySpotId = salesBySpotId
                    .mapValues { (_, spotSales) ->
                        OpenCheckSummary(
                            count = spotSales.size,
                            totalCents = spotSales.sumOf { it.openSaleTotalCents() },
                        )
                    }
                mutableState.update { current ->
                    val transferState = current.transferState
                    val prunedTransferState = transferState?.let { transfer ->
                        val sourceSaleIds = salesBySpotId[transfer.sourceSpotId].orEmpty().map { it.saleId }.toSet()
                        val selected = transfer.selectedSaleIds.intersect(sourceSaleIds)
                        when {
                            sourceSaleIds.isEmpty() -> null
                            selected == transfer.selectedSaleIds -> transfer
                            else -> transfer.copy(
                                selectedSaleIds = selected,
                                stage = if (selected.isEmpty()) TableTransferStage.SELECTING_BILLS else transfer.stage,
                            )
                        }
                    }
                    current.copy(
                        openChecksBySpotId = bySpotId,
                        openSalesBySpotId = salesBySpotId,
                        transferState = prunedTransferState,
                    )
                }
            }
        }
    }

    fun selectTable(tableId: String) {
        mutableState.update { current ->
            if (current.transferState?.stage == TableTransferStage.PICKING_TARGET) {
                current
            } else {
                current.copy(selectedTableId = tableId, message = null)
            }
        }
    }

    fun startTransferMode(tableId: String) {
        val state = mutableState.value
        val table = state.floorMap?.tables?.firstOrNull { it.id == tableId } ?: return
        val openSales = state.openSalesBySpotId[tableId].orEmpty()
        if (openSales.isEmpty()) {
            mutableState.update {
                it.copy(
                    selectedTableId = tableId,
                    message = "No open bills to transfer from ${table.label}.",
                    transferState = null,
                )
            }
            return
        }
        val autoSelectedSaleIds = if (openSales.size == 1) {
            setOf(openSales.single().saleId)
        } else {
            emptySet()
        }
        val initialStage = if (openSales.size == 1) {
            TableTransferStage.PICKING_TARGET
        } else {
            TableTransferStage.SELECTING_BILLS
        }
        mutableState.update {
            it.copy(
                selectedTableId = tableId,
                message = if (initialStage == TableTransferStage.PICKING_TARGET) {
                    "Select target table for 1 bill from ${table.label}."
                } else {
                    null
                },
                transferState = TableTransferState(
                    sourceSpotId = tableId,
                    sourceSpotLabel = table.label,
                    selectedSaleIds = autoSelectedSaleIds,
                    stage = initialStage,
                ),
            )
        }
    }

    fun startTransferModeForSale(tableId: String, saleId: String) {
        val state = mutableState.value
        val table = state.floorMap?.tables?.firstOrNull { it.id == tableId } ?: return
        val openSales = state.openSalesBySpotId[tableId].orEmpty()
        if (openSales.isEmpty()) {
            mutableState.update {
                it.copy(
                    selectedTableId = tableId,
                    message = "No open bills to transfer from ${table.label}.",
                    transferState = null,
                )
            }
            return
        }
        if (openSales.none { it.saleId == saleId }) {
            startTransferMode(tableId)
            return
        }

        val initialStage = if (openSales.size == 1) {
            TableTransferStage.PICKING_TARGET
        } else {
            TableTransferStage.SELECTING_BILLS
        }
        mutableState.update {
            it.copy(
                selectedTableId = tableId,
                message = when (initialStage) {
                    TableTransferStage.PICKING_TARGET -> "Select target table for 1 bill from ${table.label}."
                    TableTransferStage.SELECTING_BILLS -> "Bill selected from ${table.label}. Tap more bills or start transfer."
                },
                transferState = TableTransferState(
                    sourceSpotId = tableId,
                    sourceSpotLabel = table.label,
                    selectedSaleIds = setOf(saleId),
                    stage = initialStage,
                ),
            )
        }
    }

    fun toggleTransferSale(saleId: String) {
        mutableState.update { current ->
            val transfer = current.transferState ?: return@update current
            if (transfer.stage != TableTransferStage.SELECTING_BILLS) return@update current
            current.copy(
                transferState = transfer.copy(
                    selectedSaleIds = if (saleId in transfer.selectedSaleIds) {
                        transfer.selectedSaleIds - saleId
                    } else {
                        transfer.selectedSaleIds + saleId
                    },
                ),
                message = null,
            )
        }
    }

    fun beginTransferTargetSelection() {
        mutableState.update { current ->
            val transfer = current.transferState ?: return@update current
            if (transfer.selectedSaleIds.isEmpty()) {
                current.copy(message = "Select at least one bill to transfer.")
            } else {
                current.copy(
                    transferState = transfer.copy(stage = TableTransferStage.PICKING_TARGET),
                    message = "Select target table for ${transfer.selectedSaleIds.size} bill(s).",
                )
            }
        }
    }

    fun cancelTransferMode() {
        mutableState.update { it.copy(transferState = null, message = null) }
    }

    fun transferSelectedBillsTo(targetSpotId: String) {
        val state = mutableState.value
        val transfer = state.transferState ?: return
        val targetTable = state.floorMap?.tables?.firstOrNull { it.id == targetSpotId } ?: return
        if (targetSpotId == transfer.sourceSpotId) {
            mutableState.update {
                it.copy(message = "Cannot transfer selected bills to the same table.")
            }
            return
        }
        if (transfer.selectedSaleIds.isEmpty()) {
            mutableState.update { it.copy(message = "Select at least one bill to transfer.") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            runCatching {
                transfer.selectedSaleIds.forEach { saleId ->
                    openSaleRepository.assignServiceSpot(
                        saleId = saleId,
                        serviceSpotId = targetSpotId,
                        serviceSpotLabel = targetTable.label,
                    )
                }
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        busy = false,
                        selectedTableId = targetSpotId,
                        transferState = null,
                        message = "Transferred ${transfer.selectedSaleIds.size} bill(s) to ${targetTable.label}.",
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = error.message ?: "Bill transfer failed.",
                    )
                }
            }
        }
    }

    fun setViewMode(viewMode: StaffTableMapViewPreference) {
        mutableState.update { it.copy(viewMode = viewMode) }
        viewModelScope.launch {
            staffUiPreferencesRepository.setTableMapViewMode(
                staffId = currentStaffId,
                mode = viewMode,
            )
        }
    }

    fun setFloorPlanViewport(viewport: StaffFloorPlanViewportPreference) {
        mutableState.update { it.copy(floorPlanViewport = viewport) }
        floorPlanViewportSaveJob?.cancel()
        floorPlanViewportSaveJob = viewModelScope.launch {
            delay(250)
            staffUiPreferencesRepository.setFloorPlanViewport(
                staffId = currentStaffId,
                viewport = viewport,
            )
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
            currentStaffId: String,
            tableRepository: TableRepository,
            settingsRepository: SettingsRepository,
            cameraPreviewService: CameraPreviewService,
            openSaleRepository: OpenSaleRepository,
            staffUiPreferencesRepository: StaffUiPreferencesRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TableMapViewModel(
                    currentStaffId = currentStaffId,
                    tableRepository = tableRepository,
                    settingsRepository = settingsRepository,
                    cameraPreviewService = cameraPreviewService,
                    openSaleRepository = openSaleRepository,
                    staffUiPreferencesRepository = staffUiPreferencesRepository,
                )
            }
        }
    }
}

private fun StaffTableMapViewPreference.toTableMapViewMode(): TableMapViewMode {
    return when (this) {
        StaffTableMapViewPreference.FLOOR_PLAN -> TableMapViewMode.FLOOR_PLAN
        StaffTableMapViewPreference.GRID -> TableMapViewMode.GRID
    }
}

private fun TableMapViewMode.toStaffPreference(): StaffTableMapViewPreference {
    return when (this) {
        TableMapViewMode.FLOOR_PLAN -> StaffTableMapViewPreference.FLOOR_PLAN
        TableMapViewMode.GRID -> StaffTableMapViewPreference.GRID
    }
}

@Composable
fun TableMapScreen(
    state: TableMapUiState,
    currentStaffId: String?,
    preferRichFloorPlanStyle: Boolean = false,
    cameraPreviewService: CameraPreviewService,
    onSelectTable: (String) -> Unit,
    onViewModeChange: (StaffTableMapViewPreference) -> Unit,
    onFloorPlanViewportChange: (StaffFloorPlanViewportPreference) -> Unit,
    onOpenTableSale: (tableId: String, tableLabel: String, saleId: String?, source: TableSaleOpenSource) -> Unit,
    onStartTransferMode: (String) -> Unit,
    onStartTransferModeForSale: (tableId: String, saleId: String) -> Unit,
    onToggleTransferSale: (String) -> Unit,
    onBeginTransferTargetSelection: () -> Unit,
    onTransferTargetSelected: (String) -> Unit,
    onCancelTransferMode: () -> Unit,
    onOpenLivePreview: () -> Unit,
    onRetryLivePreview: () -> Unit,
    onCloseLivePreview: () -> Unit,
) {
    val viewMode = state.viewMode.toTableMapViewMode()
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
    val openTotalLabelsBySpotId = remember(state.openChecksBySpotId) {
        state.openChecksBySpotId.mapNotNull { (spotId, summary) ->
            summary.totalCents
                .takeIf { it > 0 }
                ?.let { totalCents -> spotId to formatOpenTotal(totalCents) }
        }.toMap()
    }
    val openBillCountsBySpotId = remember(state.openChecksBySpotId) {
        state.openChecksBySpotId.mapValues { (_, summary) -> summary.count }
    }
    val selectedTable = visibleTables.firstOrNull { it.id == state.selectedTableId } ?: visibleTables.firstOrNull()
    val desiredPreviewTarget = selectedTable?.previewTarget()
    val selectedPreviewTarget = state.livePreviewTarget?.takeIf { it.tableId == selectedTable?.id }
    val transferState = state.transferState
    val isPickingTransferTarget = transferState?.stage == TableTransferStage.PICKING_TARGET

    var tableMapBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var floorPlanBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var billDrag by remember { mutableStateOf(BillDragUiState()) }

    fun updateBillDragHover(tableId: String?) {
        if (billDrag.hoveredTableId != tableId) {
            billDrag = billDrag.copy(hoveredTableId = tableId)
        }
    }

    fun endBillDragAndMaybeTransfer() {
        val hovered = billDrag.hoveredTableId
        val sourceSpotId = billDrag.sourceSpotId ?: transferState?.sourceSpotId
        if (billDrag.active && hovered != null && hovered != sourceSpotId) {
            onTransferTargetSelected(hovered)
        }
        billDrag = BillDragUiState()
    }

    val externalDragPositionForFloorPlan: Offset? = run {
        val bounds = floorPlanBoundsInRoot ?: return@run null
        if (!billDrag.active) return@run null
        val p = billDrag.positionInRoot
        if (p.x < bounds.left || p.x > bounds.right || p.y < bounds.top || p.y > bounds.bottom) {
            null
        } else {
            Offset(p.x - bounds.left, p.y - bounds.top)
        }
    }

    fun handleTableTap(table: RestaurantTable) {
        if (isPickingTransferTarget) {
            onTransferTargetSelected(table.id)
            return
        }

        // In transfer SELECTING_BILLS stage, allow direct target selection by tapping any other table
        // as soon as at least one bill is selected. This avoids extra intermediate buttons.
        transferState?.let { transfer ->
            if (
                transfer.stage == TableTransferStage.SELECTING_BILLS &&
                transfer.selectedSaleIds.isNotEmpty() &&
                table.id != transfer.sourceSpotId
            ) {
                onTransferTargetSelected(table.id)
                return
            }
        }

        onSelectTable(table.id)
    }
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

    LaunchedEffect(activeAreaName, allTables) {
        if (activeAreaName == AREA_FILTER_ALL) {
            return@LaunchedEffect
        }
        if (state.selectedTableId != null && visibleTables.any { it.id == state.selectedTableId }) {
            return@LaunchedEffect
        }
        visibleTables.firstOrNull()?.id?.let(onSelectTable)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> tableMapBoundsInRoot = coords.boundsInRoot() },
    ) {
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
                        tint = if (
                            it.contains("opened", ignoreCase = true) ||
                            it.contains("transferred", ignoreCase = true) ||
                            it.contains("select target", ignoreCase = true)
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
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
                        onViewModeChange = { onViewModeChange(it.toStaffPreference()) },
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
                                val isTransferSource = table.id == transferState?.sourceSpotId
                                TableGridCard(
                                    table = table,
                                    selected = table.id == selectedTable?.id,
                                    transferMode = transferState != null,
                                    transferSource = isTransferSource,
                                    transferTargetMode = isPickingTransferTarget && !isTransferSource,
                                    openCheckSummary = state.openChecksBySpotId[table.id],
                                    onClick = { handleTableTap(table) },
                                    onLongPress = {
                                        if (!isPickingTransferTarget) {
                                            onStartTransferMode(table.id)
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
                                .fillMaxWidth()
                                .onGloballyPositioned { coords -> floorPlanBoundsInRoot = coords.boundsInRoot() },
                        ) {
                            FloorPlanTableMap(
                                tables = visibleTables,
                                selectedTableId = selectedTable?.id,
                                onSelectTable = { tableId ->
                                    visibleTables.firstOrNull { it.id == tableId }
                                        ?.let(::handleTableTap)
                                        ?: onSelectTable(tableId)
                                },
                                onLongPressTable = { tableId ->
                                    if (!isPickingTransferTarget) {
                                        onStartTransferMode(tableId)
                                    }
                                },
                                style = floorPlanStyle,
                                viewpoint = floorPlanViewpoint,
                                floorPlanViewport = state.floorPlanViewport,
                                onFloorPlanViewportChange = onFloorPlanViewportChange,
                                openTotalLabelsByTableId = openTotalLabelsBySpotId,
                                openBillCountsByTableId = openBillCountsBySpotId,
                                externalDragPosition = externalDragPositionForFloorPlan,
                                externalDragSourceTableId = billDrag.sourceSpotId ?: transferState?.sourceSpotId,
                                onExternalDragHoverTableId = { updateBillDragHover(it) },
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
                    previewState = state.cameraPreviewState,
                    previewTarget = selectedPreviewTarget,
                    isLivePreviewDialogVisible = state.isLivePreviewDialogVisible,
                    cameraPreviewService = cameraPreviewService,
                    canOpenLivePreview = !selectedTable.cameraId.isNullOrBlank() && !state.edgeBaseUrl.isNullOrBlank(),
                    openCheckSummary = state.openChecksBySpotId[selectedTable.id],
                    openSales = state.openSalesBySpotId[selectedTable.id].orEmpty(),
                    transferState = transferState,
                    onOpenSale = { saleId -> onOpenTableSale(selectedTable.id, selectedTable.label, saleId, TableSaleOpenSource.BILL_ROW) },
                    onOpenNewSale = { onOpenTableSale(selectedTable.id, selectedTable.label, null, TableSaleOpenSource.NEW_SALE_BUTTON) },
                    onStartTransfer = { onStartTransferMode(selectedTable.id) },
                    onStartTransferForSale = { saleId -> onStartTransferModeForSale(selectedTable.id, saleId) },
                    onToggleTransferSale = onToggleTransferSale,
                    onBeginTransferTargetSelection = onBeginTransferTargetSelection,
                    onCancelTransferMode = onCancelTransferMode,
                    onOpenLivePreview = onOpenLivePreview,
                    onBillDragStartInRoot = { saleCount, positionInRoot ->
                        billDrag = BillDragUiState(
                            active = true,
                            positionInRoot = positionInRoot,
                            saleCount = saleCount,
                            sourceSpotId = selectedTable.id,
                            hoveredTableId = null,
                        )
                    },
                    onBillDragMoveInRoot = { positionInRoot ->
                        if (billDrag.active) {
                            billDrag = billDrag.copy(positionInRoot = positionInRoot)
                        }
                    },
                    onBillDragEnd = { endBillDragAndMaybeTransfer() },
                )
            }
        }
    }
if (billDrag.active) {
    val hoveredLabel = billDrag.hoveredTableId?.let { hoveredId ->
        state.floorMap?.tables?.firstOrNull { it.id == hoveredId }?.label
    }
    val density = LocalDensity.current
    val overlayBounds = tableMapBoundsInRoot
    val finger = overlayBounds?.let { bounds ->
        Offset(
            x = billDrag.positionInRoot.x - bounds.left,
            y = billDrag.positionInRoot.y - bounds.top,
        )
    } ?: billDrag.positionInRoot
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 10.dp.toPx() }

    // Visual anchor at the real drop point (finger).
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(finger.x.roundToInt() - 4, finger.y.roundToInt() - 4) }
            .size(8.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                shape = RoundedCornerShape(999.dp),
            )
    )

    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .onGloballyPositioned { coords -> badgeSize = coords.size }
            .offset {
                val x = (finger.x - (badgeSize.width / 2f)).roundToInt()
                val y = (finger.y - badgeSize.height - gapPx).roundToInt()
                IntOffset(x, y)
            },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${billDrag.saleCount} laskua",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            if (hoveredLabel != null) {
                Text(
                    text = "Pudota: $hoveredLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
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


}
@Composable
private fun TableDetailsContent(
    table: RestaurantTable,
    previewState: CameraPreviewState,
    previewTarget: TableLivePreviewTarget?,
    isLivePreviewDialogVisible: Boolean,
    cameraPreviewService: CameraPreviewService,
    canOpenLivePreview: Boolean,
    openCheckSummary: OpenCheckSummary?,
    openSales: List<PersistedOpenSale>,
    transferState: TableTransferState?,
    onOpenSale: (String) -> Unit,
    onOpenNewSale: () -> Unit,
    onStartTransfer: () -> Unit,
    onStartTransferForSale: (String) -> Unit,
    onToggleTransferSale: (String) -> Unit,
    onBeginTransferTargetSelection: () -> Unit,
    onCancelTransferMode: () -> Unit,
    onOpenLivePreview: () -> Unit,
    onBillDragStartInRoot: (selectedSaleCount: Int, positionInRoot: Offset) -> Unit = { _, _ -> },
    onBillDragMoveInRoot: (positionInRoot: Offset) -> Unit = {},
    onBillDragEnd: () -> Unit = {},
) {
    val displayStatus = resolveTableDisplayStatus(
        physicalStatus = table.status,
        openBillCount = openCheckSummary?.count ?: 0,
    )
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyValueRow("Area", table.areaName)
        KeyValueRow("Status", displayStatus.label)
        if (displayStatus.differsFromPhysical) {
            KeyValueRow("Physical status", displayStatus.physicalLabel)
        }
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

        val transferForThisTable = transferState?.takeIf { it.sourceSpotId == table.id }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = if (transferForThisTable != null) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (transferForThisTable != null) {
                    Text(
                        text = "Siirtotila",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (transferForThisTable.stage == TableTransferStage.PICKING_TARGET) {
                            "Valitse kohdepöytä gridistä tai floor planista."
                        } else {
                            "Napauta siirrettävät laskut. Kun valinta on tehty, napauta kohdepöytää gridistä tai floor planista."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Avoimet laskut",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (openSales.isEmpty() && transferForThisTable == null) {
                    Text(
                        text = "Ei avoimia laskuja.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenNewSale) {
                        Text("Avaa uusi lasku")
                    }
                } else {
                    openSales.forEach { sale ->
                        val selectedSaleIds = transferForThisTable?.selectedSaleIds.orEmpty()
                        val selectedForTransfer = sale.saleId in selectedSaleIds
                        val selectionEnabled = transferForThisTable?.stage == TableTransferStage.SELECTING_BILLS
                        val selectedSaleCount = if (selectedForTransfer) {
                            selectedSaleIds.size
                        } else {
                            (selectedSaleIds + sale.saleId).size
                        }.coerceAtLeast(1)
                        val dragImmediately = transferForThisTable == null || selectedForTransfer || selectionEnabled
                        OpenSaleActionRow(
                            sale = sale,
                            actionLabel = when {
                                transferForThisTable == null -> "Napauta avataksesi"
                                selectedForTransfer -> "Valittu"
                                selectionEnabled -> "Napauta valitaksesi"
                                else -> "Valittu"
                            },
                            selected = selectedForTransfer,
                            dragImmediately = dragImmediately,
                            onOpen = {
                                if (transferForThisTable == null) {
                                    onOpenSale(sale.saleId)
                                } else if (selectionEnabled) {
                                    onToggleTransferSale(sale.saleId)
                                }
                            },
                            onLongPress = {
                                if (transferForThisTable == null) {
                                    onStartTransferForSale(sale.saleId)
                                } else if (selectionEnabled && !selectedForTransfer) {
                                    onToggleTransferSale(sale.saleId)
                                }
                            },
                            onDragStartInRoot = { pos -> onBillDragStartInRoot(selectedSaleCount, pos) },
                            onDragInRoot = onBillDragMoveInRoot,
                            onDragEnd = onBillDragEnd,
                        )
                    }
                }

                if (transferForThisTable != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onCancelTransferMode) {
                            Text("Peruuta")
                        }
                    }
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

@Composable
private fun OpenSaleActionRow(
    sale: PersistedOpenSale,
    actionLabel: String,
    selected: Boolean = false,
    dragImmediately: Boolean = false,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDragStartInRoot: (Offset) -> Unit = {},
    onDragInRoot: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    val currentOriginInRoot by rememberUpdatedState(originInRoot)
    val currentDragImmediately by rememberUpdatedState(dragImmediately)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnDragStartInRoot by rememberUpdatedState(onDragStartInRoot)
    val currentOnDragInRoot by rememberUpdatedState(onDragInRoot)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> originInRoot = coords.positionInRoot() }
            .pointerInput(sale.saleId) {
                // Tap stays a click; movement past touch slop prepares transfer and starts drag.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (currentDragImmediately) {
                        var slopChange: PointerInputChange? = null
                        val movedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            val change = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (change == null) {
                                false
                            } else {
                                slopChange = change
                                true
                            }
                        }

                        when (movedBeforeLongPress) {
                            true -> currentOnLongPress()
                            false -> return@awaitEachGesture
                            null -> {
                                currentOnLongPress()
                                slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                } ?: return@awaitEachGesture
                            }
                        }

                        currentOnDragStartInRoot(currentOriginInRoot + slopChange!!.position)
                        drag(down.id) { change ->
                            change.consume()
                            currentOnDragInRoot(currentOriginInRoot + change.position)
                        }
                        currentOnDragEnd()
                        return@awaitEachGesture
                    }

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) return@awaitEachGesture

                    // Enter transfer mode immediately on long-press.
                    currentOnLongPress()

                    // If the user moves (touch slop) after the long press, start a drag using the finger position.
                    val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    } ?: return@awaitEachGesture

                    currentOnDragStartInRoot(currentOriginInRoot + slopChange.position)
                    drag(down.id) { change ->
                        change.consume()
                        currentOnDragInRoot(currentOriginInRoot + change.position)
                    }
                    currentOnDragEnd()
                }
            },
        onClick = currentOnOpen,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenSaleSummaryColumn(
                sale = sale,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatOpenTotal(sale.openSaleTotalCents()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OpenSaleSummaryColumn(
    sale: PersistedOpenSale,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = sale.openSaleLabel(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${sale.openSaleItemCount()} items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    transferMode: Boolean,
    transferSource: Boolean,
    transferTargetMode: Boolean,
    openCheckSummary: OpenCheckSummary?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val mergedHint = mergedHintFor(table)
    val openTotalLabel = openCheckSummary?.totalCents
        ?.takeIf { it > 0 }
        ?.let(::formatOpenTotal)
    val displayStatus = resolveTableDisplayStatus(
        physicalStatus = table.status,
        openBillCount = openCheckSummary?.count ?: 0,
    )
    val accent = when (displayStatus.kind) {
        TableDisplayStatusKind.OCCUPIED,
        TableDisplayStatusKind.OPEN_BILL,
        -> MaterialTheme.colorScheme.primary

        TableDisplayStatusKind.DIRTY -> MaterialTheme.colorScheme.error
        TableDisplayStatusKind.RESERVED,
        TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL,
        -> MaterialTheme.colorScheme.tertiary

        TableDisplayStatusKind.AVAILABLE -> MaterialTheme.colorScheme.secondary
    }

    val cardColor = when {
        transferSource -> MaterialTheme.colorScheme.primaryContainer
        transferTargetMode -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
        selected -> MaterialTheme.colorScheme.primaryContainer
        transferMode && !transferSource && !transferTargetMode -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .height(172.dp)
            .alpha(if (transferMode && !transferSource && !transferTargetMode) 0.76f else 1f)
            .border(
                width = if (transferSource || transferTargetMode) 2.dp else 0.dp,
                color = when {
                    transferSource -> MaterialTheme.colorScheme.primary
                    transferTargetMode -> MaterialTheme.colorScheme.secondary
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(24.dp),
            )
            .pointerInput(onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
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
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = table.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = table.areaName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (mergedHint != null) {
                    MiniStatusChip(label = mergedHint, tint = accent)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatusChip(label = displayStatus.label, tint = accent)
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
                if (openTotalLabel != null) {
                    MiniStatusChip(
                        label = openTotalLabel,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
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

private fun formatOpenTotal(cents: Int): String {
    val major = cents / 100
    val minor = cents % 100
    return "$major,${minor.toString().padStart(2, '0')} €"
}

private fun PersistedOpenSale.openSaleTotalCents(): Int = lines.sumOf { it.openLineTotalCents() }

private fun PersistedOpenSale.openSaleItemCount(): Int = lines.sumOf { it.quantity }

private fun PersistedOpenSale.openSaleLabel(): String {
    val shortId = saleId.takeLast(6).uppercase()
    return "Lasku $shortId"
}

private fun PersistedOpenSaleLine.openLineTotalCents(): Int {
    val subtotal = quantity * unitPriceCents
    val percent = discountPercent
    val amountCents = discountAmountCents
    val discount = when {
        percent != null -> ((subtotal * percent.coerceIn(0, 100)) / 100).coerceIn(0, subtotal)
        amountCents != null -> amountCents.coerceIn(0, subtotal)
        else -> 0
    }
    return (subtotal - discount).coerceAtLeast(0)
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
