package com.airos.pos.feature.tablemap

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class TableMapViewMode {
    GRID,
    FLOOR_PLAN,
}

internal enum class FloorPlanVisualStyle {
    SIMPLE,
    RICH,
}

internal data class TableMapPalette(
    val shellColor: Color,
    val panelColor: Color,
    val panelAltColor: Color,
    val panelAccentColor: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentText: Color,
    val accentContrastText: Color,
    val availableColor: Color,
    val occupiedColor: Color,
    val dirtyColor: Color,
    val reservedColor: Color,
)

internal object TableMapVisualTokens {
    val Current = TableMapPalette(
        shellColor = Color(0xFF0D151E),
        panelColor = Color(0xFF131E29),
        panelAltColor = Color(0xFF182633),
        panelAccentColor = Color(0xFF153847),
        borderColor = Color(0x14FFFFFF),
        textPrimary = Color(0xFFFBFEFF),
        textSecondary = Color(0xFFE8F0F6),
        textMuted = Color(0xFFC0CCD6),
        accentText = Color(0xFF85F5E0),
        accentContrastText = Color(0xFF072127),
        availableColor = Color(0xFF51DBD4),
        occupiedColor = Color(0xFFFFB055),
        dirtyColor = Color(0xFFFF6D4A),
        reservedColor = Color(0xFF86A6FF),
    )

    val ShellColor = Current.shellColor
    val PanelColor = Current.panelColor
    val PanelAltColor = Current.panelAltColor
    val PanelAccentColor = Current.panelAccentColor
    val BorderColor = Current.borderColor
    val TextPrimary = Current.textPrimary
    val TextSecondary = Current.textSecondary
    val TextMuted = Current.textMuted
    val AccentText = Current.accentText
    val AccentContrastText = Current.accentContrastText
    val AvailableColor = Current.availableColor
    val OccupiedColor = Current.occupiedColor
    val DirtyColor = Current.dirtyColor
    val ReservedColor = Current.reservedColor
}

internal data class FloorPlanViewpoint(
    val stationId: String,
    val defaultCenterX: Float,
    val defaultCenterY: Float,
    val defaultRotationDeg: Float = 0f,
)

internal val DefaultFloorPlanViewpoint = FloorPlanViewpoint(
    stationId = "main-pos",
    defaultCenterX = 760f,
    defaultCenterY = 452f,
    defaultRotationDeg = 180f,
)

private data class FloorPlanContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private data class FloorPlanRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private data class FloorPlanSize(
    val width: Float,
    val height: Float,
)

private data class FloorPlanZoneLabelPlacement(
    val label: String,
    val xPx: Float,
    val yPx: Float,
)

private data class FloorPlanBarLayout(
    val horizontalRect: FloorPlanRect,
    val verticalRect: FloorPlanRect,
    val staffRect: FloorPlanRect,
    val titlePosition: Offset,
    val seatPositions: List<Offset>,
)

private data class FloorPlanTablePlacement(
    val table: RestaurantTable,
    val rect: FloorPlanRect,
)

private data class FloorPlanLayoutModel(
    val rawBounds: FloorPlanContentBounds,
    val normalizedViewpoint: FloorPlanViewpoint,
    val zoneLabels: List<FloorPlanZoneLabelPlacement>,
    val bar: FloorPlanBarLayout,
    val tables: List<FloorPlanTablePlacement>,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    val rotationQuarter: Int,
)

private val FloorPlanViewportShape = RoundedCornerShape(26.dp)
private val FloorPlanBackgroundBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF060D14),
        TableMapVisualTokens.ShellColor,
        TableMapVisualTokens.PanelColor,
    ),
)
private val FloorPlanBorderColor = TableMapVisualTokens.BorderColor
private val FloorPlanMapLineColor = Color(0x14D7E6F5)
private val FloorPlanZoneLabelColor = TableMapVisualTokens.TextMuted
private val FloorPlanHintSurface = TableMapVisualTokens.PanelColor.copy(alpha = 0.92f)
private val FloorPlanHintBorder = Color(0x22FFFFFF)
private val FloorPlanTableSurface = TableMapVisualTokens.PanelColor
private val FloorPlanTableCore = TableMapVisualTokens.PanelAltColor
private val FloorPlanBarSurface = Color(0xFF20323D)
private val FloorPlanBarBorder = Color(0x3348D6D8)
private val FloorPlanDebugBorder = Color(0xFFFF3BD5)
private val FloorPlanDebugBanner = Color(0xCC2A0B3A)
private val FloorPlanContentDebugBorder = Color(0xFF3EE7FF)
private val FloorPlanPanDebugSurface = Color(0xE0121922)
private val FloorPlanStaffZone = Color(0xFF0B1319)
private val FloorPlanSelectionColor = TableMapVisualTokens.AccentText
private val FloorPlanAvailableColor = TableMapVisualTokens.AvailableColor
private val FloorPlanOccupiedColor = TableMapVisualTokens.OccupiedColor
private val FloorPlanDirtyColor = TableMapVisualTokens.DirtyColor
private val FloorPlanReservedColor = TableMapVisualTokens.ReservedColor
private val FloorPlanBoundsPadding = 28f
private val FloorPlanBarBounds = listOf(
    FloorPlanRect(left = 1012f, top = 72f, right = 1400f, bottom = 168f),
    FloorPlanRect(left = 1304f, top = 168f, right = 1400f, bottom = 502f),
)
private val FloorPlanStaticBounds = FloorPlanBarBounds + listOf(
    FloorPlanRect(left = 96f, top = 40f, right = 228f, bottom = 70f),
    FloorPlanRect(left = 118f, top = 222f, right = 306f, bottom = 252f),
    FloorPlanRect(left = 102f, top = 656f, right = 236f, bottom = 686f),
    FloorPlanRect(left = 1016f, top = 930f, right = 1146f, bottom = 960f),
    FloorPlanRect(left = 1062f, top = 104f, right = 1140f, bottom = 138f),
)
private const val FLOOR_PLAN_DEBUG_TAG = "FloorPlanDebug"
private const val FLOOR_PLAN_MIN_ZOOM = 0.58f
private const val FLOOR_PLAN_MAX_ZOOM = 1.9f
private const val FLOOR_PLAN_ZOOM_SENSITIVITY = 1.0f
private const val FLOOR_PLAN_DEBUG_MARKER = "DEBUG FLOORPLAN RENDERER\nPAN-V6"

@Composable
internal fun TableMapViewModeToggle(
    viewMode: TableMapViewMode,
    onViewModeChange: (TableMapViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TableMapViewMode.entries.forEach { mode ->
            val selected = mode == viewMode
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)),
                shape = RoundedCornerShape(999.dp),
                color = if (selected) TableMapVisualTokens.PanelAccentColor else TableMapVisualTokens.PanelAltColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) FloorPlanSelectionColor.copy(alpha = 0.7f) else TableMapVisualTokens.BorderColor,
                ),
                onClick = { onViewModeChange(mode) },
            ) {
                Text(
                    text = if (mode == TableMapViewMode.GRID) "Grid" else "Floor Plan",
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
internal fun FloorPlanVisualStyleToggle(
    style: FloorPlanVisualStyle,
    onStyleChange: (FloorPlanVisualStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FloorPlanVisualStyle.entries.forEach { item ->
            val selected = item == style
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)),
                shape = RoundedCornerShape(999.dp),
                color = if (selected) TableMapVisualTokens.PanelAccentColor else TableMapVisualTokens.PanelAltColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) FloorPlanSelectionColor.copy(alpha = 0.62f) else TableMapVisualTokens.BorderColor,
                ),
                onClick = { onStyleChange(item) },
            ) {
                Text(
                    text = if (item == FloorPlanVisualStyle.SIMPLE) "Simple" else "Rich",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (selected) TableMapVisualTokens.AccentText else TableMapVisualTokens.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun FloorPlanTableMap(
    tables: List<RestaurantTable>,
    selectedTableId: String?,
    onSelectTable: (String) -> Unit,
    onLongPressTable: (String) -> Unit = {},
    style: FloorPlanVisualStyle,
    viewpoint: FloorPlanViewpoint = DefaultFloorPlanViewpoint,
    floorPlanViewport: StaffFloorPlanViewportPreference = StaffFloorPlanViewportPreference(),
    onFloorPlanViewportChange: (StaffFloorPlanViewportPreference) -> Unit = {},
    openTotalLabelsByTableId: Map<String, String> = emptyMap(),
    openBillCountsByTableId: Map<String, Int> = emptyMap(),
    externalDragPosition: Offset? = null,
    externalDragSourceTableId: String? = null,
    onExternalDragHoverTableId: (String?) -> Unit = {},
    onRotate90: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        Log.i(FLOOR_PLAN_DEBUG_TAG, "DEBUG FLOORPLAN RENDERER PAN-V6")
    }
    when (style) {
        FloorPlanVisualStyle.SIMPLE -> {
            SimpleFloorPlanTableMap(
                tables = tables,
                selectedTableId = selectedTableId,
                onSelectTable = onSelectTable,
                onLongPressTable = onLongPressTable,
                viewpoint = viewpoint,
                floorPlanViewport = floorPlanViewport,
                onFloorPlanViewportChange = onFloorPlanViewportChange,
                openTotalLabelsByTableId = openTotalLabelsByTableId,
                openBillCountsByTableId = openBillCountsByTableId,
                externalDragPosition = externalDragPosition,
                externalDragSourceTableId = externalDragSourceTableId,
                onExternalDragHoverTableId = onExternalDragHoverTableId,
                onRotate90 = onRotate90,
                modifier = modifier,
            )
        }

        FloorPlanVisualStyle.RICH -> {
            SimpleFloorPlanTableMap(
                tables = tables,
                selectedTableId = selectedTableId,
                onSelectTable = onSelectTable,
                onLongPressTable = onLongPressTable,
                viewpoint = viewpoint,
                floorPlanViewport = floorPlanViewport,
                onFloorPlanViewportChange = onFloorPlanViewportChange,
                openTotalLabelsByTableId = openTotalLabelsByTableId,
                openBillCountsByTableId = openBillCountsByTableId,
                externalDragPosition = externalDragPosition,
                externalDragSourceTableId = externalDragSourceTableId,
                onExternalDragHoverTableId = onExternalDragHoverTableId,
                onRotate90 = onRotate90,
                modifier = modifier,
                overlayNote = "Rich visual style scaffold is wired. Simple renderer is active for now.",
            )
        }
    }
}

@Composable
private fun SimpleFloorPlanTableMap(
    tables: List<RestaurantTable>,
    selectedTableId: String?,
    onSelectTable: (String) -> Unit,
    onLongPressTable: (String) -> Unit,
    viewpoint: FloorPlanViewpoint,
    floorPlanViewport: StaffFloorPlanViewportPreference,
    onFloorPlanViewportChange: (StaffFloorPlanViewportPreference) -> Unit,
    openTotalLabelsByTableId: Map<String, String>,
    openBillCountsByTableId: Map<String, Int>,
    externalDragPosition: Offset? = null,
    externalDragSourceTableId: String? = null,
    onExternalDragHoverTableId: (String?) -> Unit = {},
    onRotate90: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    overlayNote: String? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(FloorPlanViewportShape)
            .background(FloorPlanBackgroundBrush)
            .border(1.dp, FloorPlanBorderColor, FloorPlanViewportShape),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val layoutModel = remember(tables, viewpoint) {
            buildFloorPlanLayoutModel(
                tables = tables,
                viewpoint = viewpoint,
            )
        }
        val contentWidthPx = layoutModel.contentWidthPx
        val contentHeightPx = layoutModel.contentHeightPx
        val contentWidthDp = remember(contentWidthPx, density) { contentWidthPx.toDp(density) }
        val contentHeightDp = remember(contentHeightPx, density) { contentHeightPx.toDp(density) }
        val viewportKey = constraints.maxWidth to constraints.maxHeight
        val defaultOffset = remember(viewportKey, layoutModel, density) {
            defaultFloorPlanOffset(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
                viewpoint = layoutModel.normalizedViewpoint,
            )
        }
        var userChangedViewport by rememberSaveable(viewportKey) { mutableStateOf(false) }
        var zoomScale by rememberSaveable(viewportKey) {
            mutableStateOf(floorPlanViewport.resolvedZoomScale())
        }
        var panOffset by remember(viewportKey, layoutModel, density) {
            mutableStateOf(
                floorPlanViewport.resolvedPanOffset(
                    defaultOffset = defaultOffset,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    contentWidthPx = contentWidthPx,
                    contentHeightPx = contentHeightPx,
                ),
            )
        }
        val scaledContentWidthPx = contentWidthPx * zoomScale
        val scaledContentHeightPx = contentHeightPx * zoomScale
        val xRange = remember(viewportWidthPx, scaledContentWidthPx) {
            panRange(
                viewportSizePx = viewportWidthPx,
                contentSizePx = scaledContentWidthPx,
            )
        }
        val yRange = remember(viewportHeightPx, scaledContentHeightPx) {
            panRange(
                viewportSizePx = viewportHeightPx,
                contentSizePx = scaledContentHeightPx,
            )
        }
        LaunchedEffect(
            viewportKey,
            layoutModel,
            floorPlanViewport.zoomScale,
            floorPlanViewport.panX,
            floorPlanViewport.panY,
        ) {
            if (!userChangedViewport && floorPlanViewport.hasCompleteViewport()) {
                val restoredZoom = floorPlanViewport.resolvedZoomScale()
                zoomScale = restoredZoom
                panOffset = floorPlanViewport.resolvedPanOffset(
                    defaultOffset = defaultOffset,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    contentWidthPx = contentWidthPx,
                    contentHeightPx = contentHeightPx,
                )
            }
        }
        val clampedOffset = clampPanOffset(
            offset = panOffset,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            contentWidthPx = scaledContentWidthPx,
            contentHeightPx = scaledContentHeightPx,
        )
        val viewportRect = remember(clampedOffset, viewportWidthPx, viewportHeightPx, zoomScale) {
            FloorPlanRect(
                left = -clampedOffset.x / zoomScale,
                top = -clampedOffset.y / zoomScale,
                right = (-clampedOffset.x + viewportWidthPx) / zoomScale,
                bottom = (-clampedOffset.y + viewportHeightPx) / zoomScale,
            )
        }
        val samplePlacement = remember(layoutModel.tables, viewportRect) {
            layoutModel.tables
                .maxWithOrNull(
                    compareBy<FloorPlanTablePlacement> { placement -> placement.rect.intersectionArea(viewportRect) }
                        .thenByDescending { placement -> placement.rect.top }
                        .thenByDescending { placement -> placement.rect.left },
                )
                ?: layoutModel.tables.minWithOrNull(compareBy<FloorPlanTablePlacement> { it.rect.top }.thenBy { it.rect.left })
        }
        val sampleTable = samplePlacement?.table
        val sampleTableRectPx = remember(samplePlacement) {
            samplePlacement?.let { placement ->
                "sampleTableRectPx=(${placement.table.label},${placement.rect.left.debugPx()},${placement.rect.top.debugPx()},${placement.rect.right.debugPx()},${placement.rect.bottom.debugPx()})"
            }
        }
        val debugLogLine = remember(
            viewportWidthPx,
            viewportHeightPx,
            contentWidthPx,
            contentHeightPx,
            zoomScale,
            scaledContentWidthPx,
            scaledContentHeightPx,
            clampedOffset,
            xRange,
            yRange,
            layoutModel.rawBounds,
            sampleTableRectPx,
        ) {
            "viewport=(${viewportWidthPx.debugPx()},${viewportHeightPx.debugPx()}) " +
                "content=(${contentWidthPx.debugPx()},${contentHeightPx.debugPx()}) " +
                "scaled=(${scaledContentWidthPx.debugPx()},${scaledContentHeightPx.debugPx()}) " +
                "zoom=${"%.2f".format(zoomScale)} " +
                "rot=${layoutModel.rotationQuarter * 90} " +
                "pan=(${clampedOffset.x.debugPx()},${clampedOffset.y.debugPx()}) " +
                "clampX=(${xRange.first.debugPx()},${xRange.second.debugPx()}) " +
                "clampY=(${yRange.first.debugPx()},${yRange.second.debugPx()}) " +
                "bbox=(${layoutModel.rawBounds.left.debugCoord()},${layoutModel.rawBounds.top.debugCoord()},${layoutModel.rawBounds.right.debugCoord()},${layoutModel.rawBounds.bottom.debugCoord()}) " +
                "contentRectPx=(0,0,${contentWidthPx.debugPx()},${contentHeightPx.debugPx()}) " +
                "viewportRectPx=(0,0,${viewportWidthPx.debugPx()},${viewportHeightPx.debugPx()})" +
                (sampleTableRectPx?.let { " $it" } ?: "")
        }
        LaunchedEffect(debugLogLine) {
            Log.i(FLOOR_PLAN_DEBUG_TAG, debugLogLine)
        }
        val tableHitTargets = remember(layoutModel.tables) {
            layoutModel.tables.map { placement ->
                FloorPlanTableHitTarget(
                    tableId = placement.table.id,
                    left = placement.rect.left,
                    top = placement.rect.top,
                    right = placement.rect.right,
                    bottom = placement.rect.bottom,
                )
            }
        }
        val currentTableHitTargets by rememberUpdatedState(tableHitTargets)
        val currentClampedOffset by rememberUpdatedState(clampedOffset)
        val currentZoomScale by rememberUpdatedState(zoomScale)
        val currentOnSelectTable by rememberUpdatedState(onSelectTable)
        val currentOnLongPressTable by rememberUpdatedState(onLongPressTable)
        val currentOnFloorPlanViewportChange by rememberUpdatedState(onFloorPlanViewportChange)
        var externalHoverTableId by remember { mutableStateOf<String?>(null) }
        val currentOnExternalDragHoverTableId by rememberUpdatedState(onExternalDragHoverTableId)
        val currentExternalDragSourceTableId by rememberUpdatedState(externalDragSourceTableId)

        LaunchedEffect(externalDragPosition, currentClampedOffset, currentZoomScale, currentTableHitTargets, currentExternalDragSourceTableId) {
            val hovered = externalDragPosition?.let { pos ->
                val mapPosition = (pos - currentClampedOffset) / currentZoomScale
                currentTableHitTargets.lastOrNull { it.contains(mapPosition) }?.tableId
            }?.takeIf { it != currentExternalDragSourceTableId }
            externalHoverTableId = hovered
            currentOnExternalDragHoverTableId(hovered)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tapPosition ->
                            val mapPosition = (tapPosition - currentClampedOffset) / currentZoomScale
                            currentTableHitTargets.lastOrNull { it.contains(mapPosition) }?.let { hit ->
                                currentOnSelectTable(hit.tableId)
                            }
                        },
                        onLongPress = { tapPosition ->
                            val mapPosition = (tapPosition - currentClampedOffset) / currentZoomScale
                            currentTableHitTargets.lastOrNull { it.contains(mapPosition) }?.let { hit ->
                                currentOnLongPressTable(hit.tableId)
                            }
                        },
                    )
                }
                .pointerInput(viewportWidthPx, viewportHeightPx, contentWidthPx, contentHeightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val previousScale = zoomScale
                        val adjustedZoom = 1f + ((zoom - 1f) * FLOOR_PLAN_ZOOM_SENSITIVITY)
                        val nextScale = (previousScale * adjustedZoom).coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
                        val scaleChange = nextScale / previousScale
                        val transformedOffset = centroid + (panOffset - centroid) * scaleChange + pan
                        val nextOffset = clampPanOffset(
                            offset = transformedOffset,
                            viewportWidthPx = viewportWidthPx,
                            viewportHeightPx = viewportHeightPx,
                            contentWidthPx = contentWidthPx * nextScale,
                            contentHeightPx = contentHeightPx * nextScale,
                        )
                        zoomScale = nextScale
                        panOffset = nextOffset
                        userChangedViewport = true
                        currentOnFloorPlanViewportChange(
                            StaffFloorPlanViewportPreference(
                                zoomScale = nextScale,
                                panX = nextOffset.x,
                                panY = nextOffset.y,
                            ),
                        )
                    }
                },
        ) {
            Layout(
                content = {
                    Box(
                        modifier = Modifier.requiredSize(contentWidthDp, contentHeightDp),
                    ) {
                        FloorPlanBackdrop()
                        layoutModel.zoneLabels.forEach { labelPlacement ->
                            FloorPlanZoneLabel(labelPlacement)
                        }
                        FloorPlanBarCounter(layoutModel.bar)
                        layoutModel.tables.forEach { placement ->
                            FloorPlanTableNode(
                                table = placement.table,
                                rect = placement.rect,
                                selected = placement.table.id == selectedTableId,
                                dropHovered = placement.table.id == externalHoverTableId,
                                openTotalLabel = openTotalLabelsByTableId[placement.table.id],
                                openBillCount = openBillCountsByTableId[placement.table.id] ?: 0,
                                sample = false,
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxSize(),
            ) { measurables, constraints ->
                val contentPlaceable = measurables.first().measure(
                    Constraints.fixed(
                        width = contentWidthPx.roundToInt(),
                        height = contentHeightPx.roundToInt(),
                    ),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    contentPlaceable.placeWithLayer(
                        x = clampedOffset.x.roundToInt(),
                        y = clampedOffset.y.roundToInt(),
                    ) {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }

            onRotate90?.let { onRotate ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = FloorPlanHintSurface,
                    border = BorderStroke(1.dp, FloorPlanHintBorder),
                    onClick = onRotate,
                ) {
                    Text(
                        text = "Rotate 90°",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = TableMapVisualTokens.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            overlayNote?.let { note ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = FloorPlanHintSurface,
                    border = BorderStroke(1.dp, FloorPlanHintBorder),
                ) {
                    Text(
                        text = note,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = TableMapVisualTokens.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloorPlanBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(brush = FloorPlanBackgroundBrush)

        val gridStep = 120f
        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = FloorPlanMapLineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            x += gridStep
        }

        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = FloorPlanMapLineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            y += gridStep
        }
    }
}

@Composable
private fun FloorPlanZoneLabel(labelPlacement: FloorPlanZoneLabelPlacement) {
    Text(
        text = labelPlacement.label,
        modifier = Modifier.graphicsLayer {
            translationX = labelPlacement.xPx
            translationY = labelPlacement.yPx
        },
        style = MaterialTheme.typography.labelLarge,
        color = FloorPlanZoneLabelColor,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FloorPlanBarCounter(bar: FloorPlanBarLayout) {
    Box(modifier = Modifier.fillMaxSize()) {
        FloorPlanBarSurfaceNode(
            rect = bar.horizontalRect,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
        )
        FloorPlanBarSurfaceNode(
            rect = bar.verticalRect,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 24.dp, bottomStart = 24.dp),
        )
        FloorPlanStaffZoneNode(rect = bar.staffRect)

        Text(
            text = "BAR",
            modifier = Modifier.graphicsLayer {
                translationX = bar.titlePosition.x
                translationY = bar.titlePosition.y
            },
            style = MaterialTheme.typography.titleMedium,
            color = TableMapVisualTokens.TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        bar.seatPositions.forEach { seatPosition ->
            FloorPlanBarSeat(position = seatPosition)
        }
    }
}

@Composable
private fun FloorPlanBarSurfaceNode(
    rect: FloorPlanRect,
    shape: Shape,
) {
    val density = LocalDensity.current
    Surface(
        modifier = Modifier
            .graphicsLayer {
                translationX = rect.left
                translationY = rect.top
            }
            .requiredSize(
                width = rect.width.toDp(density),
                height = rect.height.toDp(density),
            ),
        shape = shape,
        color = FloorPlanBarSurface,
        border = BorderStroke(1.dp, FloorPlanBarBorder),
    ) {}
}

@Composable
private fun FloorPlanStaffZoneNode(rect: FloorPlanRect) {
    val density = LocalDensity.current
    Surface(
        modifier = Modifier
            .graphicsLayer {
                translationX = rect.left
                translationY = rect.top
            }
            .requiredSize(
                width = rect.width.toDp(density),
                height = rect.height.toDp(density),
            ),
        shape = RoundedCornerShape(24.dp),
        color = FloorPlanStaffZone,
        border = BorderStroke(1.dp, Color(0x20FFFFFF)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "STAFF ONLY",
                style = MaterialTheme.typography.labelLarge,
                color = TableMapVisualTokens.TextSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FloorPlanBarSeat(position: Offset) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = position.x
                translationY = position.y
            }
            .size(18f.toDp(density))
            .clip(CircleShape)
            .background(FloorPlanAvailableColor.copy(alpha = 0.82f))
            .border(1.dp, Color(0x88071013), CircleShape),
    )
}

@Composable
private fun FloorPlanTableNode(
    table: RestaurantTable,
    rect: FloorPlanRect,
    selected: Boolean,
    dropHovered: Boolean = false,
    openTotalLabel: String?,
    openBillCount: Int,
    sample: Boolean = false,
) {
    val density = LocalDensity.current
    val isMerged = "+" in table.label
    val isRound = !isMerged && abs(rect.width - rect.height) <= 18f
    val displayStatus = resolveTableDisplayStatus(
        physicalStatus = table.status,
        openBillCount = openBillCount,
    )
    val accent = displayStatus.floorPlanAccent()
    val statusLabel = displayStatus.label
    val shape: Shape = if (isRound) CircleShape else RoundedCornerShape(if (isMerged) 28.dp else 22.dp)

    Surface(
        modifier = Modifier
            .graphicsLayer {
                translationX = rect.left
                translationY = rect.top
            }
            .requiredSize(
                width = rect.width.toDp(density),
                height = rect.height.toDp(density),
            ),
        shape = shape,
        color = if (selected) Color(0xFF16272C) else FloorPlanTableSurface,
        border = BorderStroke(
            width = when {
                dropHovered -> 3.dp
                selected -> 2.dp
                else -> 1.dp
            },
            color = when {
                dropHovered -> FloorPlanSelectionColor
                selected -> FloorPlanSelectionColor
                else -> accent.copy(alpha = 0.56f)
            },
        ),
        shadowElevation = when {
            dropHovered -> 10.dp
            selected -> 6.dp
            else -> 1.dp
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.24f),
                            FloorPlanTableCore.copy(alpha = 0.98f),
                        ),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = table.label,
                    style = if (isRound || isMerged) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = TableMapVisualTokens.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )


                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = if (statusTick.attentionVisible) 0.26f else 0.16f),
                ) {
                    Text(
                        text = statusTick.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (openTotalLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = FloorPlanSelectionColor.copy(alpha = 0.13f),
                        ) {
                            Text(
                                text = openTotalLabel,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = FloorPlanSelectionColor,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (isMerged) {
                        Text(
                            text = "Merged",
                            style = MaterialTheme.typography.labelSmall,
                            color = TableMapVisualTokens.TextMuted,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = "${table.guestCount}G · ${table.seats}S",
                        style = MaterialTheme.typography.labelMedium,
                        color = TableMapVisualTokens.TextSecondary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun clampPanOffset(
    offset: Offset,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
): Offset {
    val xRange = panRange(
        viewportSizePx = viewportWidthPx,
        contentSizePx = contentWidthPx,
    )
    val yRange = panRange(
        viewportSizePx = viewportHeightPx,
        contentSizePx = contentHeightPx,
    )
    return Offset(
        x = offset.x.coerceIn(xRange.first, xRange.second),
        y = offset.y.coerceIn(yRange.first, yRange.second),
    )
}

private fun defaultFloorPlanOffset(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
    viewpoint: FloorPlanViewpoint,
): Offset {
    val boundedCenterX = viewpoint.defaultCenterX.coerceIn(0f, contentWidthPx)
    val boundedCenterY = viewpoint.defaultCenterY.coerceIn(0f, contentHeightPx)
    return clampPanOffset(
        offset = Offset(
            x = (viewportWidthPx / 2f) - boundedCenterX,
            y = (viewportHeightPx / 2f) - boundedCenterY,
        ),
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
    )
}

private fun panRange(
    viewportSizePx: Float,
    contentSizePx: Float,
): Pair<Float, Float> {
    if (contentSizePx <= viewportSizePx) {
        val centeredOffset = (viewportSizePx - contentSizePx) / 2f
        return centeredOffset to centeredOffset
    }
    return (viewportSizePx - contentSizePx) to 0f
}

private fun StaffFloorPlanViewportPreference.hasCompleteViewport(): Boolean {
    return zoomScale?.isFinite() == true &&
        panX?.isFinite() == true &&
        panY?.isFinite() == true
}

private fun StaffFloorPlanViewportPreference.resolvedZoomScale(): Float {
    return zoomScale
        ?.takeIf { it.isFinite() }
        ?.coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
        ?: 1f
}

private fun StaffFloorPlanViewportPreference.resolvedPanOffset(
    defaultOffset: Offset,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
): Offset {
    val resolvedPanX = panX
    val resolvedPanY = panY
    if (resolvedPanX?.isFinite() != true || resolvedPanY?.isFinite() != true) {
        return defaultOffset
    }
    val resolvedZoomScale = resolvedZoomScale()
    return clampPanOffset(
        offset = Offset(resolvedPanX, resolvedPanY),
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        contentWidthPx = contentWidthPx * resolvedZoomScale,
        contentHeightPx = contentHeightPx * resolvedZoomScale,
    )
}


private fun normalizedQuarterRotation(rotationDeg: Float): Int {
    return ((((rotationDeg / 90f).roundToInt() % 4) + 4) % 4)
}

private fun rotatedFloorPlanSize(
    width: Float,
    height: Float,
    rotationQuarter: Int,
): FloorPlanSize {
    return when (rotationQuarter and 3) {
        1, 3 -> FloorPlanSize(width = height, height = width)
        else -> FloorPlanSize(width = width, height = height)
    }
}

private fun rotateFloorPlanPoint(
    point: Offset,
    width: Float,
    height: Float,
    rotationQuarter: Int,
): Offset {
    return when (rotationQuarter and 3) {
        0 -> point
        1 -> Offset(x = height - point.y, y = point.x)
        2 -> Offset(x = width - point.x, y = height - point.y)
        3 -> Offset(x = point.y, y = width - point.x)
        else -> point
    }
}

private fun rotateFloorPlanRect(
    rect: FloorPlanRect,
    width: Float,
    height: Float,
    rotationQuarter: Int,
): FloorPlanRect {
    val points = listOf(
        rotateFloorPlanPoint(Offset(rect.left, rect.top), width, height, rotationQuarter),
        rotateFloorPlanPoint(Offset(rect.right, rect.top), width, height, rotationQuarter),
        rotateFloorPlanPoint(Offset(rect.left, rect.bottom), width, height, rotationQuarter),
        rotateFloorPlanPoint(Offset(rect.right, rect.bottom), width, height, rotationQuarter),
    )
    return FloorPlanRect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y },
    )
}

private fun buildFloorPlanLayoutModel(
    tables: List<RestaurantTable>,
    viewpoint: FloorPlanViewpoint,
): FloorPlanLayoutModel {
    val tablePlacementsSource = tables.map { table ->
        table to rawFloorPlanRectForTable(table)
    }
    val rawBounds = buildRawFloorPlanBounds(
        tableRects = tablePlacementsSource.map { (_, rect) -> rect },
    )
    val canonicalContentWidth = rawBounds.width
    val canonicalContentHeight = rawBounds.height
    val rotationQuarter = normalizedQuarterRotation(viewpoint.defaultRotationDeg)

    fun normalizeX(rawX: Float): Float = rawX - rawBounds.left
    fun normalizeY(rawY: Float): Float = rawY - rawBounds.top
    fun normalizeRect(rawRect: FloorPlanRect): FloorPlanRect {
        return FloorPlanRect(
            left = normalizeX(rawRect.left),
            top = normalizeY(rawRect.top),
            right = normalizeX(rawRect.right),
            bottom = normalizeY(rawRect.bottom),
        )
    }

    val canonicalZoneLabels = listOf(
        FloorPlanZoneLabelPlacement("WINDOW", normalizeX(96f), normalizeY(40f)),
        FloorPlanZoneLabelPlacement("MAIN DINING", normalizeX(118f), normalizeY(222f)),
        FloorPlanZoneLabelPlacement("TERRACE", normalizeX(102f), normalizeY(656f)),
        FloorPlanZoneLabelPlacement("LOUNGE", normalizeX(1016f), normalizeY(930f)),
    )

    val canonicalBar = FloorPlanBarLayout(
        horizontalRect = normalizeRect(FloorPlanRect(left = 1012f, top = 72f, right = 1400f, bottom = 168f)),
        verticalRect = normalizeRect(FloorPlanRect(left = 1304f, top = 168f, right = 1400f, bottom = 502f)),
        staffRect = normalizeRect(FloorPlanRect(left = 1056f, top = 168f, right = 1304f, bottom = 398f)),
        titlePosition = Offset(x = normalizeX(1062f), y = normalizeY(104f)),
        seatPositions = buildList {
            repeat(7) { index ->
                add(Offset(x = normalizeX(1048f + (index * 48f)), y = normalizeY(178f)))
            }
            repeat(5) { index ->
                add(Offset(x = normalizeX(1262f), y = normalizeY(206f + (index * 54f))))
            }
        },
    )

    val canonicalTablePlacements = tablePlacementsSource.map { (table, rawRect) ->
        FloorPlanTablePlacement(
            table = table,
            rect = normalizeRect(rawRect),
        )
    }

    val rotatedSize = rotatedFloorPlanSize(
        width = canonicalContentWidth,
        height = canonicalContentHeight,
        rotationQuarter = rotationQuarter,
    )

    val rotatedViewpointCenter = rotateFloorPlanPoint(
        point = Offset(
            x = normalizeX(viewpoint.defaultCenterX),
            y = normalizeY(viewpoint.defaultCenterY),
        ),
        width = canonicalContentWidth,
        height = canonicalContentHeight,
        rotationQuarter = rotationQuarter,
    )

    val zoneLabels = canonicalZoneLabels.map { placement ->
        val rotatedPoint = rotateFloorPlanPoint(
            point = Offset(placement.xPx, placement.yPx),
            width = canonicalContentWidth,
            height = canonicalContentHeight,
            rotationQuarter = rotationQuarter,
        )
        placement.copy(
            xPx = rotatedPoint.x,
            yPx = rotatedPoint.y,
        )
    }

    val bar = canonicalBar.copy(
        horizontalRect = rotateFloorPlanRect(
            rect = canonicalBar.horizontalRect,
            width = canonicalContentWidth,
            height = canonicalContentHeight,
            rotationQuarter = rotationQuarter,
        ),
        verticalRect = rotateFloorPlanRect(
            rect = canonicalBar.verticalRect,
            width = canonicalContentWidth,
            height = canonicalContentHeight,
            rotationQuarter = rotationQuarter,
        ),
        staffRect = rotateFloorPlanRect(
            rect = canonicalBar.staffRect,
            width = canonicalContentWidth,
            height = canonicalContentHeight,
            rotationQuarter = rotationQuarter,
        ),
        titlePosition = rotateFloorPlanPoint(
            point = canonicalBar.titlePosition,
            width = canonicalContentWidth,
            height = canonicalContentHeight,
            rotationQuarter = rotationQuarter,
        ),
        seatPositions = canonicalBar.seatPositions.map { position ->
            rotateFloorPlanPoint(
                point = position,
                width = canonicalContentWidth,
                height = canonicalContentHeight,
                rotationQuarter = rotationQuarter,
            )
        },
    )

    val tablePlacements = canonicalTablePlacements.map { placement ->
        placement.copy(
            rect = rotateFloorPlanRect(
                rect = placement.rect,
                width = canonicalContentWidth,
                height = canonicalContentHeight,
                rotationQuarter = rotationQuarter,
            ),
        )
    }

    return FloorPlanLayoutModel(
        rawBounds = rawBounds,
        normalizedViewpoint = viewpoint.copy(
            defaultCenterX = rotatedViewpointCenter.x,
            defaultCenterY = rotatedViewpointCenter.y,
        ),
        zoneLabels = zoneLabels,
        bar = bar,
        tables = tablePlacements,
        contentWidthPx = rotatedSize.width,
        contentHeightPx = rotatedSize.height,
        rotationQuarter = rotationQuarter,
    )
}

private fun buildRawFloorPlanBounds(
    tableRects: List<FloorPlanRect>,
): FloorPlanContentBounds {
    val allRects = tableRects + FloorPlanStaticBounds
    val left = allRects.minOfOrNull { it.left } ?: 0f
    val top = allRects.minOfOrNull { it.top } ?: 0f
    val right = allRects.maxOfOrNull { it.right } ?: 0f
    val bottom = allRects.maxOfOrNull { it.bottom } ?: 0f
    return FloorPlanContentBounds(
        left = (left - FloorPlanBoundsPadding).coerceAtLeast(0f),
        top = (top - FloorPlanBoundsPadding).coerceAtLeast(0f),
        right = right + FloorPlanBoundsPadding,
        bottom = bottom + FloorPlanBoundsPadding,
    )
}

private fun rawFloorPlanRectForTable(
    table: RestaurantTable,
): FloorPlanRect {
    return FloorPlanRect(
        left = table.position.x.toFloat(),
        top = table.position.y.toFloat(),
        right = (table.position.x + table.position.width).toFloat(),
        bottom = (table.position.y + table.position.height).toFloat(),
    )
}

private fun Float.debugPx(): String = roundToInt().toString()

private fun Float.debugCoord(): String = roundToInt().toString()

private fun FloorPlanRect.intersectionArea(other: FloorPlanRect): Float {
    val overlapLeft = max(left, other.left)
    val overlapTop = max(top, other.top)
    val overlapRight = min(right, other.right)
    val overlapBottom = min(bottom, other.bottom)
    val width = (overlapRight - overlapLeft).coerceAtLeast(0f)
    val height = (overlapBottom - overlapTop).coerceAtLeast(0f)
    return width * height
}

@Composable
private fun ViewportContentBoundsOverlay(
    contentWidthPx: Float,
    contentHeightPx: Float,
) {
    val density = LocalDensity.current
    val markerWidthPx = with(density) { 40.dp.toPx() }
    val markerHeightPx = with(density) { 30.dp.toPx() }
    val markerInsetPx = with(density) { 8.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            color = FloorPlanContentDebugBorder,
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(contentWidthPx, contentHeightPx),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ContentCornerMarker(
            label = "TL",
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = markerInsetPx
                    translationY = markerInsetPx
                },
        )
        ContentCornerMarker(
            label = "TR",
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = contentWidthPx - markerWidthPx - markerInsetPx
                    translationY = markerInsetPx
                },
        )
        ContentCornerMarker(
            label = "BL",
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = markerInsetPx
                    translationY = contentHeightPx - markerHeightPx - markerInsetPx
                },
        )
        ContentCornerMarker(
            label = "BR",
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = contentWidthPx - markerWidthPx - markerInsetPx
                    translationY = contentHeightPx - markerHeightPx - markerInsetPx
                },
        )
    }
}

@Composable
private fun ContentCornerMarker(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = FloorPlanPanDebugSurface,
        border = BorderStroke(2.dp, FloorPlanContentDebugBorder),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = FloorPlanContentDebugBorder,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun Float.toDp(density: Density) = with(density) { this@toDp.toDp() }

private fun TableDisplayStatus.floorPlanAccent(): Color {
    return when (kind) {
        TableDisplayStatusKind.AVAILABLE -> FloorPlanAvailableColor
        TableDisplayStatusKind.OPEN_BILL,
        TableDisplayStatusKind.OCCUPIED,
        -> FloorPlanOccupiedColor

        TableDisplayStatusKind.DIRTY -> FloorPlanDirtyColor
        TableDisplayStatusKind.RESERVED,
        TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL,
        -> FloorPlanReservedColor
    }
}

private data class FloorPlanTableHitTarget(
    val tableId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(position: Offset): Boolean {
        return position.x in left..right && position.y in top..bottom
    }
}