package com.airos.pos.feature.tablemap
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airos.pos.core.model.FloorMapArea
import com.airos.pos.core.model.FloorMapObject
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
    defaultRotationDeg = 0f,
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

private data class FloorPlanTableSeatMarker(
    val leftPx: Float,
    val topPx: Float,
)

private data class FloorPlanAreaPlacement(
    val area: FloorMapArea,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

private data class FloorPlanTablePlacement(
    val table: RestaurantTable,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

private data class FloorPlanObjectPlacement(
    val floorObject: FloorMapObject,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

private data class FloorPlanLayoutModel(
    val rawBounds: FloorPlanContentBounds,
    val normalizedViewpoint: FloorPlanViewpoint,
    val zoneLabels: List<FloorPlanZoneLabelPlacement>,
    val areas: List<FloorPlanAreaPlacement>,
    val tables: List<FloorPlanTablePlacement>,
    val objects: List<FloorPlanObjectPlacement>,
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
private val FloorPlanContentDebugBorder = Color(0xFF3EE7FF)
private val FloorPlanPanDebugSurface = Color(0xE0121922)
private val FloorPlanSelectionColor = TableMapVisualTokens.AccentText
private val FloorPlanAvailableColor = TableMapVisualTokens.AvailableColor
private val FloorPlanOccupiedColor = TableMapVisualTokens.OccupiedColor
private val FloorPlanDirtyColor = TableMapVisualTokens.DirtyColor
private val FloorPlanReservedColor = TableMapVisualTokens.ReservedColor
private val FloorPlanBoundsPadding = 48f
private const val FLOOR_PLAN_DEBUG_TAG = "FloorPlanDebug"
private const val FLOOR_PLAN_MIN_ZOOM = 0.20f
private const val FLOOR_PLAN_MAX_ZOOM = 4.0f
private const val FLOOR_PLAN_ZOOM_SENSITIVITY = 1.0f
private const val FLOOR_PLAN_DEBUG_MARKER = "FLOORPLAN RENDERER WORLD-TO-SCREEN V1"

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
    floorAreas: List<FloorMapArea> = emptyList(),
    floorObjects: List<FloorMapObject> = emptyList(),
    selectedTableId: String?,
    onSelectTable: (String) -> Unit,
    onLongPressTable: (String) -> Unit = {},
    style: FloorPlanVisualStyle,
    viewpoint: FloorPlanViewpoint = DefaultFloorPlanViewpoint,
    floorPlanViewport: StaffFloorPlanViewportPreference = StaffFloorPlanViewportPreference(),
    onFloorPlanViewportChange: (StaffFloorPlanViewportPreference) -> Unit = {},
    openTotalLabelsByTableId: Map<String, String> = emptyMap(),
    openSaleTotalLabelsByTableId: Map<String, List<String>> = emptyMap(),
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
                floorAreas = floorAreas,
                floorObjects = floorObjects,
                selectedTableId = selectedTableId,
                onSelectTable = onSelectTable,
                onLongPressTable = onLongPressTable,
                viewpoint = viewpoint,
                floorPlanViewport = floorPlanViewport,
                onFloorPlanViewportChange = onFloorPlanViewportChange,
                openTotalLabelsByTableId = openTotalLabelsByTableId,
                openSaleTotalLabelsByTableId = openSaleTotalLabelsByTableId,
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
                floorAreas = floorAreas,
                floorObjects = floorObjects,
                selectedTableId = selectedTableId,
                onSelectTable = onSelectTable,
                onLongPressTable = onLongPressTable,
                viewpoint = viewpoint,
                floorPlanViewport = floorPlanViewport,
                onFloorPlanViewportChange = onFloorPlanViewportChange,
                openTotalLabelsByTableId = openTotalLabelsByTableId,
                openSaleTotalLabelsByTableId = openSaleTotalLabelsByTableId,
                openBillCountsByTableId = openBillCountsByTableId,
                externalDragPosition = externalDragPosition,
                externalDragSourceTableId = externalDragSourceTableId,
                onExternalDragHoverTableId = onExternalDragHoverTableId,
                onRotate90 = onRotate90,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun SimpleFloorPlanTableMap(
    tables: List<RestaurantTable>,
    floorAreas: List<FloorMapArea>,
    floorObjects: List<FloorMapObject>,
    selectedTableId: String?,
    onSelectTable: (String) -> Unit,
    onLongPressTable: (String) -> Unit,
    viewpoint: FloorPlanViewpoint,
    floorPlanViewport: StaffFloorPlanViewportPreference,
    onFloorPlanViewportChange: (StaffFloorPlanViewportPreference) -> Unit,
    openTotalLabelsByTableId: Map<String, String>,
    openSaleTotalLabelsByTableId: Map<String, List<String>>,
    openBillCountsByTableId: Map<String, Int>,
    externalDragPosition: Offset? = null,
    externalDragSourceTableId: String? = null,
    onExternalDragHoverTableId: (String?) -> Unit = {},
    onRotate90: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
        val layoutModel = remember(tables, floorAreas, floorObjects, viewpoint) {
            buildFloorPlanLayoutModel(
                tables = tables,
                floorAreas = floorAreas,
                floorObjects = floorObjects,
                viewpoint = viewpoint,
            )
        }
        val contentWidthPx = layoutModel.contentWidthPx
        val contentHeightPx = layoutModel.contentHeightPx
        val viewportKey = constraints.maxWidth to constraints.maxHeight
        val fitZoom = remember(viewportKey, layoutModel) {
            computeFitZoom(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
            )
        }
        var userChangedViewport by rememberSaveable(viewportKey) { mutableStateOf(false) }
        var zoomScale by rememberSaveable(viewportKey) {
            mutableStateOf(floorPlanViewport.resolvedZoomScale(fitZoom))
        }
        val defaultOffset = remember(viewportKey, layoutModel, zoomScale) {
            centerContentOffset(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                contentWidthPx = contentWidthPx * zoomScale,
                contentHeightPx = contentHeightPx * zoomScale,
            )
        }
        var panOffset by remember(viewportKey, layoutModel) {
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
        LaunchedEffect(
            viewportKey,
            layoutModel,
            floorPlanViewport.zoomScale,
            floorPlanViewport.panX,
            floorPlanViewport.panY,
            fitZoom,
        ) {
            if (!userChangedViewport && floorPlanViewport.hasCompleteViewport()) {
                val restoredZoom = floorPlanViewport.resolvedZoomScale(fitZoom)
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
        val debugLogLine = remember(
            viewportWidthPx,
            viewportHeightPx,
            contentWidthPx,
            contentHeightPx,
            zoomScale,
            clampedOffset,
            layoutModel.rawBounds,
            layoutModel.tables.size,
            layoutModel.areas.size,
            layoutModel.objects.size,
        ) {
            "viewport=(${viewportWidthPx.debugPx()},${viewportHeightPx.debugPx()}) " +
                "content=(${contentWidthPx.debugPx()},${contentHeightPx.debugPx()}) " +
                "zoom=${"%.2f".format(zoomScale)} fit=${"%.2f".format(fitZoom)} " +
                "rot=${layoutModel.rotationQuarter * 90} " +
                "pan=(${clampedOffset.x.debugPx()},${clampedOffset.y.debugPx()}) " +
                "tables=${layoutModel.tables.size} areas=${layoutModel.areas.size} objects=${layoutModel.objects.size}"
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
                .pointerInput("floor-plan-drag-pan-v2", viewportWidthPx, viewportHeightPx, contentWidthPx, contentHeightPx, zoomScale) {

                    detectDragGestures { change, dragAmount ->

                        change.consume()

                        val nextOffset = clampPanOffset(

                            offset = currentClampedOffset + dragAmount,

                            viewportWidthPx = viewportWidthPx,

                            viewportHeightPx = viewportHeightPx,

                            contentWidthPx = contentWidthPx * currentZoomScale,

                            contentHeightPx = contentHeightPx * currentZoomScale,

                        )

                        panOffset = nextOffset

                        userChangedViewport = true

                        currentOnFloorPlanViewportChange(

                            StaffFloorPlanViewportPreference(

                                zoomScale = currentZoomScale,

                                panX = nextOffset.x,

                                panY = nextOffset.y,

                            ),

                        )

                    }

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
            FloorPlanBackdrop()

            layoutModel.areas.forEach { areaPlacement ->
                FloorPlanAreaSurface(
                    placement = areaPlacement,
                    panOffset = clampedOffset,
                    zoom = zoomScale,
                )
            }
            layoutModel.zoneLabels.forEach { labelPlacement ->
                FloorPlanZoneLabel(
                    labelPlacement = labelPlacement,
                    panOffset = clampedOffset,
                    zoom = zoomScale,
                )
            }
            layoutModel.objects.forEach { placement ->
                FloorPlanObjectNode(
                    placement = placement,
                    panOffset = clampedOffset,
                    zoom = zoomScale,
                )
            }
            layoutModel.tables.forEach { placement ->
                FloorPlanTableNode(
                    table = placement.table,
                    rect = placement.rect,
                    rotationDeg = placement.effectiveRotationDeg,
                    panOffset = clampedOffset,
                    zoom = zoomScale,
                    selected = placement.table.id == selectedTableId,
                    dropHovered = placement.table.id == externalHoverTableId,
                    openTotalLabel = openTotalLabelsByTableId[placement.table.id],
                    openSaleTotalLabels = openSaleTotalLabelsByTableId[placement.table.id].orEmpty(),
                    openBillCount = openBillCountsByTableId[placement.table.id] ?: 0,
                )
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
                        text = "Rotate 90Â°",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = TableMapVisualTokens.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
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
private fun FloorPlanZoneLabel(
    labelPlacement: FloorPlanZoneLabelPlacement,
    panOffset: Offset,
    zoom: Float,
) {
    val screenX = panOffset.x + labelPlacement.xPx * zoom
    val screenY = panOffset.y + labelPlacement.yPx * zoom
    Text(
        text = labelPlacement.label,
        modifier = Modifier
            .graphicsLayer {
                translationX = screenX
                translationY = screenY
            }
            .background(FloorPlanHintSurface, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = FloorPlanZoneLabelColor,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun FloorPlanAreaSurface(
    placement: FloorPlanAreaPlacement,
    panOffset: Offset,
    zoom: Float,
) {
    val density = LocalDensity.current
    val area = placement.area
    val screen = placement.rect.toScreenRect(panOffset, zoom)
    val widthDp = screen.width.coerceAtLeast(1f).toDp(density)
    val heightDp = screen.height.coerceAtLeast(1f).toDp(density)
    val baseModifier = Modifier
        .graphicsLayer {
            translationX = screen.left
            translationY = screen.top
            rotationZ = placement.effectiveRotationDeg
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
        .requiredSize(widthDp, heightDp)
    val fillColor = areaSurfaceColor(area).copy(alpha = 0.42f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.70f)
    when (area.shape.lowercase()) {
        "triangle" -> {
            FloorPlanTriangleAreaSurface(
                modifier = baseModifier,
                area = area,
                fillColor = fillColor,
                borderColor = borderColor,
            )
        }
        "circle", "ellipse" -> {
            Surface(
                modifier = baseModifier,
                shape = CircleShape,
                color = fillColor,
                border = BorderStroke(1.dp, borderColor),
            ) {}
        }
        "roundedrectangle", "rounded-rectangle", "rounded_rect" -> {
            Surface(
                modifier = baseModifier,
                shape = RoundedCornerShape(8.dp),
                color = fillColor,
                border = BorderStroke(1.dp, borderColor),
            ) {}
        }
        else -> {
            Surface(
                modifier = baseModifier,
                shape = RoundedCornerShape(0.dp),
                color = fillColor,
                border = BorderStroke(1.dp, borderColor),
            ) {}
        }
    }
}

private fun areaSurfaceColor(area: FloorMapArea): Color {
    return when (area.areaType?.lowercase()) {
        "kitchen" -> Color(0xFF3A4A2D)
        "bar" -> Color(0xFF4A2D2D)
        "terrace", "outdoor" -> Color(0xFF2D4A3A)
        "lounge" -> Color(0xFF3A2D4A)
        "restroom", "wc" -> Color(0xFF4A4A2D)
        else -> Color(0xFF55613B)
    }
}

private fun areaSurfaceBorderColor(area: FloorMapArea): Color {
    return when (area.areaType?.lowercase()) {
        "kitchen" -> Color(0xFFB6922F)
        "bar" -> Color(0xFFC45A2E)
        "terrace", "outdoor" -> Color(0xFF2EC495)
        "lounge" -> Color(0xFF8A5AC4)
        "restroom", "wc" -> Color(0xFFC4C45A)
        else -> Color(0xFFB6922F)
    }
}

@Composable
private fun FloorPlanTriangleAreaSurface(
    modifier: Modifier,
    area: FloorMapArea,
    fillColor: Color,
    borderColor: Color,
) {
    val p1x = (area.p1XPercent ?: 0f) / 100f
    val p1y = (area.p1YPercent ?: 100f) / 100f
    val p2x = (area.p2XPercent ?: 100f) / 100f
    val p2y = (area.p2YPercent ?: 100f) / 100f
    val p3x = (area.p3XPercent
        ?: area.apexXPercent
        ?: 50f) / 100f
    val p3y = (area.p3YPercent ?: 0f) / 100f
    Canvas(modifier = modifier) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * p1x, size.height * p1y)
            lineTo(size.width * p2x, size.height * p2y)
            lineTo(size.width * p3x, size.height * p3y)
            close()
        }
        drawPath(path = path, color = fillColor)
        drawPath(path = path, color = borderColor, style = Stroke(width = 1.dp.toPx()))
    }
}

private fun FloorPlanRect.toScreenRect(panOffset: Offset, zoom: Float): FloorPlanRect {
    return FloorPlanRect(
        left = panOffset.x + left * zoom,
        top = panOffset.y + top * zoom,
        right = panOffset.x + right * zoom,
        bottom = panOffset.y + bottom * zoom,
    )
}

@Composable
private fun FloorPlanObjectNode(
    placement: FloorPlanObjectPlacement,
    panOffset: Offset,
    zoom: Float,
) {
    val density = LocalDensity.current
    val floorObject = placement.floorObject
    val screen = placement.rect.toScreenRect(panOffset, zoom)
    val widthDp = screen.width.coerceAtLeast(1f).toDp(density)
    val heightDp = screen.height.coerceAtLeast(1f).toDp(density)
    val baseModifier = Modifier.graphicsLayer {
        translationX = screen.left
        translationY = screen.top
        rotationZ = placement.effectiveRotationDeg
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
    when (floorObject.type.lowercase()) {
        "wall" -> {
            Surface(
                modifier = baseModifier.requiredSize(widthDp, heightDp),
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF4A2415),
                border = BorderStroke(1.dp, Color(0x995B2A13)),
            ) {}
        }
        "door" -> {
            FloorPlanDoorObjectNode(
                floorObject = floorObject,
                widthDp = widthDp,
                heightDp = heightDp,
                modifier = baseModifier,
            )
        }
        "bar-counter" -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = screen.width,
                modifier = baseModifier,
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF3B1C10),
                borderColor = Color(0xFF8A4A1E),
            )
        }
        "sofa" -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = screen.width,
                modifier = baseModifier,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF3A2117),
                borderColor = Color(0xFF7A3D18),
            )
        }
        "chair", "armchair" -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = screen.width,
                modifier = baseModifier,
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFF29353D),
                borderColor = FloorPlanAvailableColor.copy(alpha = 0.72f),
                showLabel = screen.width >= 36f && screen.height >= 24f,
            )
        }
        "camera" -> {
            val cameraSymbolPx = 40f
            val cameraSymbolDp = cameraSymbolPx.toDp(density)
            FloorPlanCameraSymbol(
                modifier = baseModifier.requiredSize(cameraSymbolDp, cameraSymbolDp),
                label = floorObject.label,
                screenWidthPx = cameraSymbolPx,
            )
        }
        "pos-marker", "text-label" -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = screen.width,
                modifier = baseModifier,
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF24313A),
                borderColor = FloorPlanSelectionColor.copy(alpha = 0.48f),
            )
        }
        else -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = screen.width,
                modifier = baseModifier,
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF25303A),
                borderColor = Color(0x4466F6E8),
            )
        }
    }
}

@Composable
private fun FloorPlanLabeledObjectSurface(
    label: String,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    screenWidthPx: Float,
    modifier: Modifier,
    shape: Shape,
    color: Color,
    borderColor: Color,
    showLabel: Boolean = true,
) {
    Surface(
        modifier = modifier.requiredSize(widthDp, heightDp),
        shape = shape,
        color = color.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, borderColor),
    ) {
        if (showLabel && label.isNotBlank() && screenWidthPx >= 36f) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = floorPlanLabelStyle(screenWidthPx),
                    color = TableMapVisualTokens.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FloorPlanCameraSymbol(
    modifier: Modifier,
    label: String,
    screenWidthPx: Float,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.55.dp.toPx()
            val bodyLeft = size.width * 0.12f
            val bodyTop = size.height * 0.43f
            val bodyWidth = size.width * 0.53f
            val bodyHeight = size.height * 0.28f
            val bodyRadius = CornerRadius(size.width * 0.055f, size.height * 0.055f)
            val lensCenter = Offset(
                x = bodyLeft + bodyWidth * 0.50f,
                y = bodyTop + bodyHeight * 0.52f,
            )
            val lensRadius = min(bodyWidth, bodyHeight) * 0.20f
            val hornPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(bodyLeft + bodyWidth, bodyTop + bodyHeight * 0.30f)
                lineTo(size.width * 0.90f, size.height * 0.31f)
                lineTo(size.width * 0.90f, size.height * 0.78f)
                lineTo(bodyLeft + bodyWidth, bodyTop + bodyHeight * 0.70f)
                close()
            }
            val cameraColor = Color(0xFFF9E8B5)
            val cameraFill = Color(0xFF201108).copy(alpha = 0.92f)
            val legColor = cameraColor.copy(alpha = 0.72f)

            drawRoundRect(
                color = cameraFill,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = bodyRadius,
            )
            drawRoundRect(
                color = cameraColor,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = bodyRadius,
                style = Stroke(width = stroke),
            )
            drawPath(
                path = hornPath,
                color = cameraFill,
            )
            drawPath(
                path = hornPath,
                color = cameraColor,
                style = Stroke(width = stroke),
            )
            drawCircle(
                color = cameraColor,
                radius = lensRadius,
                center = lensCenter,
                style = Stroke(width = max(1f, stroke * 0.80f)),
            )
            drawLine(
                color = legColor,
                start = Offset(size.width * 0.30f, size.height * 0.71f),
                end = Offset(size.width * 0.24f, size.height * 0.88f),
                strokeWidth = max(1f, stroke * 0.75f),
            )
            drawLine(
                color = legColor,
                start = Offset(size.width * 0.54f, size.height * 0.71f),
                end = Offset(size.width * 0.61f, size.height * 0.88f),
                strokeWidth = max(1f, stroke * 0.75f),
            )
            drawLine(
                color = legColor,
                start = Offset(size.width * 0.31f, size.height * 0.35f),
                end = Offset(size.width * 0.37f, size.height * 0.24f),
                strokeWidth = max(1f, stroke * 0.65f),
            )
            drawLine(
                color = legColor,
                start = Offset(size.width * 0.57f, size.height * 0.35f),
                end = Offset(size.width * 0.51f, size.height * 0.24f),
                strokeWidth = max(1f, stroke * 0.65f),
            )
        }
        if (label.isNotBlank() && screenWidthPx >= 40f) {
            Text(
                text = label,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 14.dp)
                    .background(FloorPlanHintSurface, RoundedCornerShape(999.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = floorPlanLabelStyle(screenWidthPx),
                color = TableMapVisualTokens.TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FloorPlanDoorObjectNode(
    floorObject: FloorMapObject,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    Box(modifier = modifier.requiredSize(widthDp, heightDp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.4.dp.toPx()
            val hingeLeft = floorObject.doorHingeSide?.equals("right", ignoreCase = true) != true
            val swingOut = floorObject.doorSwingDirection?.equals("out", ignoreCase = true) == true
            val hingeX = if (hingeLeft) 0f else size.width
            val leafEndX = if (hingeLeft) size.width else 0f
            val baseY = if (swingOut) size.height * 0.85f else size.height * 0.15f
            val arcRadius = min(size.width, size.height * 1.2f)
            val arcTop = if (swingOut) baseY - arcRadius else baseY
            drawLine(
                color = FloorPlanSelectionColor.copy(alpha = 0.92f),
                start = Offset(hingeX, baseY),
                end = Offset(leafEndX, baseY),
                strokeWidth = stroke,
            )
            drawRect(
                color = FloorPlanSelectionColor.copy(alpha = 0.92f),
                topLeft = Offset(hingeX - 2.dp.toPx(), baseY - 2.dp.toPx()),
                size = Size(4.dp.toPx(), 4.dp.toPx()),
            )
            drawArc(
                color = FloorPlanSelectionColor.copy(alpha = 0.48f),
                startAngle = if (hingeLeft) if (swingOut) 270f else 0f else if (swingOut) 180f else 90f,
                sweepAngle = if (hingeLeft) 90f else -90f,
                useCenter = false,
                topLeft = Offset(if (hingeLeft) hingeX else hingeX - arcRadius, arcTop),
                size = Size(arcRadius, arcRadius),
                style = Stroke(width = stroke),
            )
        }
    }
}

private fun floorPlanLabelStyle(screenWidthPx: Float): androidx.compose.ui.text.TextStyle {
    return when {
        screenWidthPx >= 120f -> androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        screenWidthPx >= 80f -> androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
        screenWidthPx >= 50f -> androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
        else -> androidx.compose.ui.text.TextStyle(fontSize = 9.sp)
    }
}

@Composable
private fun FloorPlanTableNode(
    table: RestaurantTable,
    rect: FloorPlanRect,
    rotationDeg: Float,
    panOffset: Offset,
    zoom: Float,
    selected: Boolean,
    dropHovered: Boolean = false,
    openTotalLabel: String?,
    openSaleTotalLabels: List<String>,
    openBillCount: Int,
) {
    val density = LocalDensity.current
    val screen = rect.toScreenRect(panOffset, zoom)
    val bodyWidthPx = screen.width.coerceAtLeast(1f)
    val bodyHeightPx = screen.height.coerceAtLeast(1f)
    val isMerged = "+" in table.label
    val tableShapeRaw = table.floorPlanShape?.lowercase()
    val isRound = when (tableShapeRaw) {
        "round", "circle", "ellipse" -> true
        "square", "rectangle" -> false
        else -> !isMerged && abs(rect.width - rect.height) <= 18f
    }
    val seatMarkers = remember(table.id, table.seats, table.chairLayout, table.floorPlanShape, bodyWidthPx, bodyHeightPx) {
        getTableSeatMarkers(
            table = table,
            widthPx = bodyWidthPx,
            heightPx = bodyHeightPx,
        )
    }
    val displayStatus = resolveTableDisplayStatus(
        physicalStatus = table.status,
        openBillCount = openBillCount,
        attentionFlag = table.attentionFlag,
    )
    val statusTick = rememberStatusTickPresentation(displayStatus)
    val attentionTint = displayStatus.attentionVisualTint()
    val accent = displayStatus.floorPlanAccent()
    val selectedTint = attentionTint ?: accent
    val seatMarkerDiameterPx = if (seatMarkers.isEmpty()) {
        0f
    } else {
        max(5f, min(8f, min(bodyWidthPx, bodyHeightPx) * 0.16f))
    }
    val seatMarkerRadiusPx = seatMarkerDiameterPx / 2f
    val markerLeftOverflow = seatMarkers.minOfOrNull { it.leftPx - seatMarkerRadiusPx }?.let { min(0f, it) } ?: 0f
    val markerTopOverflow = seatMarkers.minOfOrNull { it.topPx - seatMarkerRadiusPx }?.let { min(0f, it) } ?: 0f
    val markerRightOverflow = seatMarkers.maxOfOrNull { it.leftPx + seatMarkerRadiusPx }?.let { max(0f, it - bodyWidthPx) } ?: 0f
    val markerBottomOverflow = seatMarkers.maxOfOrNull { it.topPx + seatMarkerRadiusPx }?.let { max(0f, it - bodyHeightPx) } ?: 0f
    val markerPadPx = max(
        0f,
        max(
            max(-markerLeftOverflow, -markerTopOverflow),
            max(markerRightOverflow, markerBottomOverflow),
        ) + 3f,
    )
    val nodeWidthPx = bodyWidthPx + markerPadPx * 2f
    val nodeHeightPx = bodyHeightPx + markerPadPx * 2f
    val nodeWidthDp = nodeWidthPx.toDp(density)
    val nodeHeightDp = nodeHeightPx.toDp(density)
    val showLabel = bodyWidthPx >= 30f && bodyHeightPx >= 22f
    val showStatusTick = bodyWidthPx >= 60f && bodyHeightPx >= 50f
    val showOpenChips = bodyWidthPx >= 70f && bodyHeightPx >= 64f &&
        (openSaleTotalLabels.isNotEmpty() || openTotalLabel != null)
    val borderWidthPx = when {
        dropHovered -> with(density) { 3.dp.toPx() }
        attentionTint != null -> with(density) { 2.dp.toPx() }
        selected -> with(density) { 2.dp.toPx() }
        else -> with(density) { 1.dp.toPx() }
    }
    val borderColor = when {
        attentionTint != null -> attentionTint
        selected -> selectedTint
        dropHovered -> FloorPlanSelectionColor
        else -> Color(0xFFC6942E).copy(alpha = 0.78f)
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = screen.left - markerPadPx
                translationY = screen.top - markerPadPx
                rotationZ = rotationDeg
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .requiredSize(nodeWidthDp, nodeHeightDp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bodyTopLeft = Offset(markerPadPx, markerPadPx)
            val bodySize = Size(bodyWidthPx, bodyHeightPx)
            val bodyFill = tableBodyFillColor()
            val bodyStroke = borderColor
            val bodyGradient = Brush.verticalGradient(
                colors = listOf(
                    bodyFill.copy(alpha = 0.98f),
                    Color(0xFF3E220C).copy(alpha = 0.98f),
                ),
                startY = markerPadPx,
                endY = markerPadPx + bodyHeightPx,
            )
            seatMarkers.forEach { marker ->
                val center = Offset(
                    x = markerPadPx + marker.leftPx,
                    y = markerPadPx + marker.topPx,
                )
                drawCircle(
                    color = Color(0xFF1C120A),
                    radius = seatMarkerRadiusPx,
                    center = center,
                )
                drawCircle(
                    color = Color(0xFFEFBD5B).copy(alpha = 0.72f),
                    radius = seatMarkerRadiusPx,
                    center = center,
                    style = Stroke(width = max(1f, 1.dp.toPx())),
                )
            }

            if (isRound) {
                drawOval(
                    brush = bodyGradient,
                    topLeft = bodyTopLeft,
                    size = bodySize,
                )
                drawOval(
                    color = bodyStroke,
                    topLeft = bodyTopLeft,
                    size = bodySize,
                    style = Stroke(width = borderWidthPx),
                )
            } else {
                drawRect(
                    brush = bodyGradient,
                    topLeft = bodyTopLeft,
                    size = bodySize,
                )
                drawRect(
                    color = bodyStroke,
                    topLeft = bodyTopLeft,
                    size = bodySize,
                    style = Stroke(width = borderWidthPx),
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(x = markerPadPx.toDp(density), y = markerPadPx.toDp(density))
                .requiredSize(bodyWidthPx.toDp(density), bodyHeightPx.toDp(density))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = if (showStatusTick || showOpenChips) {
                    Arrangement.SpaceBetween
                } else {
                    Arrangement.Center
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showLabel) {
                    Text(
                        text = table.label,
                        style = floorPlanTableLabelStyle(bodyWidthPx, bodyHeightPx, isRound || isMerged),
                        color = TableMapVisualTokens.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }

                if (showStatusTick) {
                    val tickTint = attentionTint ?: accent
                    val tickTextColor = attentionTint ?: accent
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = tickTint.copy(
                            alpha = if (attentionTint != null) 0.30f else 0.16f
                        ),
                        border = if (attentionTint != null) {
                            BorderStroke(
                                1.dp,
                                tickTint.copy(alpha = 0.34f),
                            )
                        } else {
                            null
                        },
                    ) {
                        Text(
                            text = statusTick.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                            color = tickTextColor.copy(alpha = 0.96f),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (showOpenChips) {
                    FloorPlanOpenSaleAmountChips(
                        labels = openSaleTotalLabels.ifEmpty { listOfNotNull(openTotalLabel) },
                        maxVisible = 1,
                    )
                }
            }
        }
    }
}

private fun floorPlanTableLabelStyle(
    screenWidthPx: Float,
    screenHeightPx: Float,
    emphasize: Boolean,
): androidx.compose.ui.text.TextStyle {
    val short = min(screenWidthPx, screenHeightPx)
    val baseSp = when {
        short >= 110f -> if (emphasize) 16f else 14f
        short >= 80f -> if (emphasize) 14f else 12f
        short >= 60f -> 11f
        short >= 44f -> 10f
        else -> 9f
    }
    return androidx.compose.ui.text.TextStyle(fontSize = baseSp.sp)
}

private fun tableBodyFillColor(): Color {
    return Color(0xFF5F3A18)
}

private fun getDefaultChairLayout(table: RestaurantTable): String {
    table.chairLayout?.takeIf { it.isNotBlank() }?.let { return it }
    return when (table.floorPlanShape?.lowercase()) {
        "round", "circle", "ellipse" -> "round_even"
        "square" -> "square_even"
        "rectangle" -> if (table.seats >= 6) "rectangle_sides_only" else "rectangle_ends_and_sides"
        else -> if (table.seats >= 6) "rectangle_sides_only" else "rectangle_ends_and_sides"
    }
}

private fun spreadSeatPositions(
    count: Int,
    startPx: Float,
    endPx: Float,
): List<Float> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf((startPx + endPx) / 2f)
    val step = (endPx - startPx) / (count - 1)
    return List(count) { index -> startPx + step * index }
}

private fun getRectFourCenteredSeatMarkers(
    widthPx: Float,
    heightPx: Float,
    seatOffsetPx: Float,
): List<FloorPlanTableSeatMarker> {
    val centerX = widthPx / 2f
    val centerY = heightPx / 2f
    return listOf(
        FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
        FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY),
        FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
        FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY),
    )
}

private fun getTableSeatMarkers(
    table: RestaurantTable,
    widthPx: Float,
    heightPx: Float,
): List<FloorPlanTableSeatMarker> {
    val seatCount = table.seats.coerceAtLeast(0)
    if (seatCount == 0) return emptyList()

    val layout = getDefaultChairLayout(table)
    val seatOffsetPx = max(8f, min(16f, (min(widthPx, heightPx) * 0.28f).roundToInt().toFloat()))
    val horizontalInsetPx = max(12f, (widthPx * 0.18f).roundToInt().toFloat())
    val centerX = widthPx / 2f
    val centerY = heightPx / 2f

    if (layout == "round_even") {
        val radiusX = widthPx / 2f + seatOffsetPx
        val radiusY = heightPx / 2f + seatOffsetPx
        return List(seatCount) { index ->
            val angle = (Math.PI * 2.0 * index / seatCount) - (Math.PI / 2.0)
            FloorPlanTableSeatMarker(
                leftPx = (centerX + kotlin.math.cos(angle).toFloat() * radiusX),
                topPx = (centerY + kotlin.math.sin(angle).toFloat() * radiusY),
            )
        }
    }

    if (layout == "square_even") {
        if (seatCount == 3) {
            return listOf(
                FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY),
                FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY),
            )
        }
        if (seatCount <= 2) {
            return listOf(
                FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
            ).take(seatCount)
        }
        return getRectFourCenteredSeatMarkers(widthPx, heightPx, seatOffsetPx).take(seatCount)
    }

    if (layout == "rectangle_sides_only") {
        val topCount = (seatCount + 1) / 2
        val bottomCount = seatCount / 2
        return spreadSeatPositions(
            count = topCount,
            startPx = horizontalInsetPx,
            endPx = widthPx - horizontalInsetPx,
        ).map { leftPx ->
            FloorPlanTableSeatMarker(leftPx = leftPx, topPx = -seatOffsetPx)
        } + spreadSeatPositions(
            count = bottomCount,
            startPx = horizontalInsetPx,
            endPx = widthPx - horizontalInsetPx,
        ).map { leftPx ->
            FloorPlanTableSeatMarker(leftPx = leftPx, topPx = heightPx + seatOffsetPx)
        }
    }

    if (layout == "rectangle_wall_side_empty") {
        val outerLongSideCount = max(1, seatCount - 2)
        val sideSeatCount = max(0, seatCount - outerLongSideCount)
        val markers = mutableListOf<FloorPlanTableSeatMarker>()
        spreadSeatPositions(
            count = outerLongSideCount,
            startPx = horizontalInsetPx,
            endPx = widthPx - horizontalInsetPx,
        ).forEach { leftPx ->
            markers += FloorPlanTableSeatMarker(leftPx = leftPx, topPx = heightPx + seatOffsetPx)
        }
        if (sideSeatCount >= 1) {
            markers += FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY)
        }
        if (sideSeatCount >= 2) {
            markers += FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY)
        }
        return markers
    }

    if (seatCount == 4) {
        return getRectFourCenteredSeatMarkers(widthPx, heightPx, seatOffsetPx)
    }

    if (seatCount <= 2) {
        return listOf(
            FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
            FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
        ).take(seatCount)
    }

    if (seatCount == 3) {
        return listOf(
            FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
            FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY),
            FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
        )
    }

    val topCount = (seatCount - 1) / 2
    val bottomCount = (seatCount - 2) / 2

    return listOf(FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY)) +
        spreadSeatPositions(
            count = topCount,
            startPx = horizontalInsetPx,
            endPx = widthPx - horizontalInsetPx,
        ).map { leftPx ->
            FloorPlanTableSeatMarker(leftPx = leftPx, topPx = -seatOffsetPx)
        } +
        listOf(FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY)) +
        spreadSeatPositions(
            count = bottomCount,
            startPx = horizontalInsetPx,
            endPx = widthPx - horizontalInsetPx,
        ).map { leftPx ->
            FloorPlanTableSeatMarker(leftPx = leftPx, topPx = heightPx + seatOffsetPx)
        }
}



@Composable
private fun FloorPlanOpenSaleAmountChips(
    labels: List<String>,
    maxVisible: Int,
) {
    val visibleLabels = labels.take(maxVisible)
    val overflowCount = (labels.size - visibleLabels.size).coerceAtLeast(0)

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleLabels.forEach { label ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = TableMapVisualTokens.ReservedColor.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, TableMapVisualTokens.ReservedColor.copy(alpha = 0.40f)),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TableMapVisualTokens.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (overflowCount > 0) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = TableMapVisualTokens.PanelAltColor.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, TableMapVisualTokens.BorderColor.copy(alpha = 0.95f)),
            ) {
                Text(
                    text = "..more..",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TableMapVisualTokens.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
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

private fun centerContentOffset(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
): Offset {
    return Offset(
        x = ((viewportWidthPx - contentWidthPx) / 2f),
        y = ((viewportHeightPx - contentHeightPx) / 2f),
    )
}

private fun computeFitZoom(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
): Float {
    if (contentWidthPx <= 0f || contentHeightPx <= 0f ||
        viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
        return 1f
    }
    val sx = viewportWidthPx / contentWidthPx
    val sy = viewportHeightPx / contentHeightPx
    return min(sx, sy).coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
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

private fun StaffFloorPlanViewportPreference.resolvedZoomScale(fallback: Float = 1f): Float {
    return zoomScale
        ?.takeIf { it.isFinite() }
        ?.coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
        ?: fallback.coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
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
    floorAreas: List<FloorMapArea>,
    floorObjects: List<FloorMapObject>,
    viewpoint: FloorPlanViewpoint,
): FloorPlanLayoutModel {
    val tablePlacementsSource = tables.map { table ->
        Triple(table, rawFloorPlanRectForTable(table), table.floorPlanRotation)
    }
    val objectPlacementsSource = floorObjects
        .filterNot { it.hidden }
        .map { floorObject -> Triple(floorObject, rawFloorPlanRectForObject(floorObject), floorObject.rotation) }
    val areaPlacementsSource = floorAreas
        .filterNot { it.hidden }
        .map { area -> Triple(area, rawFloorPlanRectForArea(area), area.rotation) }
    val rawBounds = buildRawFloorPlanBounds(
        tableRects = tablePlacementsSource.map { it.second },
        areaRects = areaPlacementsSource.map { it.second },
        objectRects = objectPlacementsSource.map { it.second },
    )
    val canonicalContentWidth = rawBounds.width
    val canonicalContentHeight = rawBounds.height
    val rotationQuarter = normalizedQuarterRotation(viewpoint.defaultRotationDeg)
    val viewportRotationDeg = (rotationQuarter * 90f) % 360f

    fun normalizeRect(rawRect: FloorPlanRect): FloorPlanRect {
        return FloorPlanRect(
            left = rawRect.left - rawBounds.left,
            top = rawRect.top - rawBounds.top,
            right = rawRect.right - rawBounds.left,
            bottom = rawRect.bottom - rawBounds.top,
        )
    }

    val canonicalZoneLabels = areaPlacementsSource.map { (area, rect, _) ->
        val normalized = normalizeRect(rect)
        FloorPlanZoneLabelPlacement(
            label = area.label.uppercase(),
            xPx = normalized.left + 8f,
            yPx = normalized.top + 6f,
        )
    }

    val canonicalAreaPlacements = areaPlacementsSource.map { (area, rawRect, rotation) ->
        FloorPlanAreaPlacement(
            area = area,
            rect = normalizeRect(rawRect),
            effectiveRotationDeg = rotation,
        )
    }

    val canonicalTablePlacements = tablePlacementsSource.map { (table, rawRect, rotation) ->
        FloorPlanTablePlacement(
            table = table,
            rect = normalizeRect(rawRect),
            effectiveRotationDeg = rotation,
        )
    }

    val canonicalObjectPlacements = objectPlacementsSource.map { (floorObject, rawRect, rotation) ->
        FloorPlanObjectPlacement(
            floorObject = floorObject,
            rect = normalizeRect(rawRect),
            effectiveRotationDeg = rotation,
        )
    }

    val rotatedSize = rotatedFloorPlanSize(
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

    val areaPlacements = canonicalAreaPlacements.map { placement ->
        placement.copy(
            rect = rotateFloorPlanRect(
                rect = placement.rect,
                width = canonicalContentWidth,
                height = canonicalContentHeight,
                rotationQuarter = rotationQuarter,
            ),
            effectiveRotationDeg = (placement.effectiveRotationDeg + viewportRotationDeg) % 360f,
        )
    }

    val tablePlacements = canonicalTablePlacements.map { placement ->
        placement.copy(
            rect = rotateFloorPlanRect(
                rect = placement.rect,
                width = canonicalContentWidth,
                height = canonicalContentHeight,
                rotationQuarter = rotationQuarter,
            ),
            effectiveRotationDeg = (placement.effectiveRotationDeg + viewportRotationDeg) % 360f,
        )
    }

    val objectPlacements = canonicalObjectPlacements.map { placement ->
        placement.copy(
            rect = rotateFloorPlanRect(
                rect = placement.rect,
                width = canonicalContentWidth,
                height = canonicalContentHeight,
                rotationQuarter = rotationQuarter,
            ),
            effectiveRotationDeg = (placement.effectiveRotationDeg + viewportRotationDeg) % 360f,
        )
    }

    return FloorPlanLayoutModel(
        rawBounds = rawBounds,
        normalizedViewpoint = viewpoint,
        zoneLabels = zoneLabels,
        areas = areaPlacements,
        tables = tablePlacements,
        objects = objectPlacements,
        contentWidthPx = rotatedSize.width,
        contentHeightPx = rotatedSize.height,
        rotationQuarter = rotationQuarter,
    )
}

private fun buildRawFloorPlanBounds(
    tableRects: List<FloorPlanRect>,
    areaRects: List<FloorPlanRect>,
    objectRects: List<FloorPlanRect>,
): FloorPlanContentBounds {
    val allRects = tableRects + areaRects + objectRects
    if (allRects.isEmpty()) {
        return FloorPlanContentBounds(left = 0f, top = 0f, right = 600f, bottom = 400f)
    }
    val left = allRects.minOf { it.left }
    val top = allRects.minOf { it.top }
    val right = allRects.maxOf { it.right }
    val bottom = allRects.maxOf { it.bottom }
    return FloorPlanContentBounds(
        left = left - FloorPlanBoundsPadding,
        top = top - FloorPlanBoundsPadding,
        right = right + FloorPlanBoundsPadding,
        bottom = bottom + FloorPlanBoundsPadding,
    )
}

private fun rawFloorPlanRectForArea(
    area: FloorMapArea,
): FloorPlanRect {
    return FloorPlanRect(
        left = area.xPx,
        top = area.yPx,
        right = area.xPx + area.widthPx,
        bottom = area.yPx + area.heightPx,
    )
}

private fun rawFloorPlanRectForTable(
    table: RestaurantTable,
): FloorPlanRect {
    val left = table.floorPlanX ?: table.position.x.toFloat()
    val top = table.floorPlanY ?: table.position.y.toFloat()
    val width = table.floorPlanWidth ?: table.position.width.toFloat()
    val height = table.floorPlanHeight ?: table.position.height.toFloat()
    return FloorPlanRect(
        left = left,
        top = top,
        right = left + width,
        bottom = top + height,
    )
}

private fun rawFloorPlanRectForObject(
    floorObject: FloorMapObject,
): FloorPlanRect {
    return FloorPlanRect(
        left = floorObject.xPx,
        top = floorObject.yPx,
        right = floorObject.xPx + floorObject.widthPx,
        bottom = floorObject.yPx + floorObject.heightPx,
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


