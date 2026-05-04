package com.airos.pos.feature.tablemap
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airos.pos.core.model.FloorMapArea
import com.airos.pos.core.model.FloorMapObject
import com.airos.pos.core.model.FloorPlanMarkerAnchor
import com.airos.pos.core.model.FloorPlanSofaStyle
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Surface theme tokens are still available for furniture/material work, but the
// viewport floor/backdrop is intentionally restored to the older stable renderer.
// The bad recent material passes painted the whole map beige/brown and made the
// floor dominate the view. Keep this outer canvas calm and dark.
private fun activeFloorPlanSurfaceTheme(): FloorPlanSurfaceTheme =
    FloorPlanSurfaceThemeHolder.current

private val FloorPlanViewportShape = RoundedCornerShape(26.dp)
private val FloorPlanBackgroundBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF060D14),
        TableMapVisualTokens.ShellColor,
        TableMapVisualTokens.PanelColor,
    ),
)
@Suppress("UNUSED_PARAMETER")
private fun floorPlanBackgroundBrush(theme: FloorPlanSurfaceTheme): Brush = FloorPlanBackgroundBrush
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
private const val FLOOR_PLAN_MAX_ZOOM = 8.0f
private const val FLOOR_PLAN_ZOOM_SENSITIVITY = 1.0f
private const val FLOOR_PLAN_DEBUG_MARKER = "FLOORPLAN RENDERER WORLD-TO-SCREEN V1"
private const val FLOOR_PLAN_CAMERA_SYMBOL_PX = 40f

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
    floorMapWidthPx: Float? = null,
    floorMapHeightPx: Float? = null,
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
                floorMapWidthPx = floorMapWidthPx,
                floorMapHeightPx = floorMapHeightPx,
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
                floorMapWidthPx = floorMapWidthPx,
                floorMapHeightPx = floorMapHeightPx,
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
    floorMapWidthPx: Float? = null,
    floorMapHeightPx: Float? = null,
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
    val surfaceTheme = activeFloorPlanSurfaceTheme()
    BoxWithConstraints(
        modifier = modifier
            .clip(FloorPlanViewportShape)
            .background(floorPlanBackgroundBrush(surfaceTheme))
            .border(1.dp, FloorPlanBorderColor, FloorPlanViewportShape),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val layoutModel = remember(tables, floorAreas, floorObjects, floorMapWidthPx, floorMapHeightPx, viewpoint) {
            buildFloorPlanLayoutModel(
                tables = tables,
                floorAreas = floorAreas,
                floorObjects = floorObjects,
                floorMapWidthPx = floorMapWidthPx,
                floorMapHeightPx = floorMapHeightPx,
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
        val defaultOffset = remember(viewportKey, contentWidthPx, contentHeightPx, zoomScale) {
            centerContentOffset(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                contentWidthPx = contentWidthPx * zoomScale,
                contentHeightPx = contentHeightPx * zoomScale,
            )
        }
        val initialPanOffset = remember(viewportKey) {
            floorPlanViewport.resolvedPanOffset(
                defaultOffset = defaultOffset,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
            )
        }
        var panOffsetX by rememberSaveable(viewportKey) { mutableStateOf(initialPanOffset.x) }
        var panOffsetY by rememberSaveable(viewportKey) { mutableStateOf(initialPanOffset.y) }
        val panOffset = Offset(panOffsetX, panOffsetY)

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
                val restoredPanOffset = floorPlanViewport.resolvedPanOffset(
                    defaultOffset = defaultOffset,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    contentWidthPx = contentWidthPx,
                    contentHeightPx = contentHeightPx,
                )
                panOffsetX = restoredPanOffset.x
                panOffsetY = restoredPanOffset.y
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
        val currentViewportWidthPx by rememberUpdatedState(viewportWidthPx)
        val currentViewportHeightPx by rememberUpdatedState(viewportHeightPx)
        val currentContentWidthPx by rememberUpdatedState(contentWidthPx)
        val currentContentHeightPx by rememberUpdatedState(contentHeightPx)
        val currentOnSelectTable by rememberUpdatedState(onSelectTable)
        val currentOnLongPressTable by rememberUpdatedState(onLongPressTable)
        val currentOnFloorPlanViewportChange by rememberUpdatedState(onFloorPlanViewportChange)

        LaunchedEffect(userChangedViewport, zoomScale, panOffsetX, panOffsetY) {
            if (userChangedViewport) {
                delay(250)
                val settledOffset = clampPanOffset(
                    offset = Offset(panOffsetX, panOffsetY),
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    contentWidthPx = contentWidthPx * zoomScale,
                    contentHeightPx = contentHeightPx * zoomScale,
                )
                currentOnFloorPlanViewportChange(
                    StaffFloorPlanViewportPreference(
                        zoomScale = zoomScale,
                        panX = settledOffset.x,
                        panY = settledOffset.y,
                    ),
                )
            }
        }

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
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val previousScale = currentZoomScale
                        val adjustedZoom = 1f + ((zoom - 1f) * FLOOR_PLAN_ZOOM_SENSITIVITY)
                        val nextScale = (previousScale * adjustedZoom).coerceIn(FLOOR_PLAN_MIN_ZOOM, FLOOR_PLAN_MAX_ZOOM)
                        val scaleChange = nextScale / previousScale
                        val transformedOffset = centroid + (currentClampedOffset - centroid) * scaleChange + pan
                        val nextOffset = clampPanOffset(
                            offset = transformedOffset,
                            viewportWidthPx = currentViewportWidthPx,
                            viewportHeightPx = currentViewportHeightPx,
                            contentWidthPx = currentContentWidthPx * nextScale,
                            contentHeightPx = currentContentHeightPx * nextScale,
                        )
                        zoomScale = nextScale
                        panOffsetX = nextOffset.x
                        panOffsetY = nextOffset.y
                        userChangedViewport = true
                    }
                },
        ) {
            FloorPlanBackdrop(
                panOffset = clampedOffset,
                zoom = zoomScale,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
            )
            FloorPlanAreasLayer(
                placements = layoutModel.areas,
                panOffset = clampedOffset,
                zoom = zoomScale,
            )
            layoutModel.zoneLabels.forEach { labelPlacement ->
                FloorPlanZoneLabel(
                    labelPlacement = labelPlacement,
                    panOffset = clampedOffset,
                    zoom = zoomScale,
                )
            }
            FloorPlanWorldObjectsLayer(
                placements = layoutModel.objects.filter { it.floorObject.shouldRenderInWorldObjectLayer() },
                panOffset = clampedOffset,
                zoom = zoomScale,
            )
            layoutModel.objects
                .filterNot { it.floorObject.shouldRenderInWorldObjectLayer() }
                .forEach { placement ->
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
private fun FloorPlanBackdrop(
    panOffset: Offset,
    zoom: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(brush = FloorPlanBackgroundBrush)

        val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
        val gridStepWorldPx = 120f
        val strokeWidth = 1.dp.toPx()

        var worldX = 0f
        while (worldX <= contentWidthPx) {
            val screenX = panOffset.x + worldX * safeZoom
            if (screenX >= -strokeWidth && screenX <= size.width + strokeWidth) {
                drawLine(
                    color = FloorPlanMapLineColor,
                    start = Offset(screenX, 0f),
                    end = Offset(screenX, size.height),
                    strokeWidth = strokeWidth,
                )
            }
            worldX += gridStepWorldPx
        }

        var worldY = 0f
        while (worldY <= contentHeightPx) {
            val screenY = panOffset.y + worldY * safeZoom
            if (screenY >= -strokeWidth && screenY <= size.height + strokeWidth) {
                drawLine(
                    color = FloorPlanMapLineColor,
                    start = Offset(0f, screenY),
                    end = Offset(size.width, screenY),
                    strokeWidth = strokeWidth,
                )
            }
            worldY += gridStepWorldPx
        }
    }
}

private fun DrawScope.drawFloorPlanOuterBackgroundGrid(
    gridColor: Color,
) {
    val gridStepPx = 64.dp.toPx()
    val stroke = max(0.45f, 0.55.dp.toPx())

    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = stroke,
        )
        x += gridStepPx
    }

    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
        )
        y += gridStepPx
    }
}

private fun DrawScope.drawFloorPlanFloorSurface(
    material: FloorPlanFloorSurfaceTokens,
    panOffset: Offset,
    zoom: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
) {
    val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val floorWidth = (contentWidthPx * safeZoom).coerceAtLeast(1f)
    val floorHeight = (contentHeightPx * safeZoom).coerceAtLeast(1f)
    val floorTopLeft = panOffset
    val floorSize = Size(floorWidth, floorHeight)

    // Floor base: intentionally restrained. This is the large map floor plane,
    // so it must not dominate the whole TableMap view. Material selection will
    // later come from editor/theme data; for now the preset stays dark and quiet.
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(material.baseTop, material.baseMiddle, material.baseBottom),
            start = floorTopLeft,
            end = Offset(floorTopLeft.x + floorWidth * 0.35f, floorTopLeft.y + floorHeight),
        ),
        topLeft = floorTopLeft,
        size = floorSize,
    )

    val visibleWorldLeft = ((0f - panOffset.x) / safeZoom).coerceIn(0f, contentWidthPx)
    val visibleWorldRight = ((size.width - panOffset.x) / safeZoom).coerceIn(0f, contentWidthPx)
    val visibleWorldTop = ((0f - panOffset.y) / safeZoom).coerceIn(0f, contentHeightPx)
    val visibleWorldBottom = ((size.height - panOffset.y) / safeZoom).coerceIn(0f, contentHeightPx)

    val tileWorldPx = 96f
    val seamStroke = max(0.35f, 0.45.dp.toPx())
    val highlightStroke = max(0.25f, 0.32.dp.toPx())
    val firstColumn = max(0, (visibleWorldLeft / tileWorldPx).toInt() - 1)
    val lastColumn = min((contentWidthPx / tileWorldPx).toInt() + 1, (visibleWorldRight / tileWorldPx).toInt() + 2)
    val firstRow = max(0, (visibleWorldTop / tileWorldPx).toInt() - 1)
    val lastRow = min((contentHeightPx / tileWorldPx).toInt() + 1, (visibleWorldBottom / tileWorldPx).toInt() + 2)

    // Subtle tile / laminate rhythm. The lines are deliberately low-contrast so
    // the furniture, table status and operational overlays remain the stars.
    for (column in firstColumn..lastColumn) {
        val screenX = panOffset.x + column * tileWorldPx * safeZoom
        if (screenX < floorTopLeft.x || screenX > floorTopLeft.x + floorWidth) continue
        drawLine(
            color = material.plankSeam,
            start = Offset(screenX, floorTopLeft.y),
            end = Offset(screenX, floorTopLeft.y + floorHeight),
            strokeWidth = seamStroke,
        )
        drawLine(
            color = material.plankHighlight,
            start = Offset(screenX + seamStroke, floorTopLeft.y),
            end = Offset(screenX + seamStroke, floorTopLeft.y + floorHeight),
            strokeWidth = highlightStroke,
        )
    }

    for (row in firstRow..lastRow) {
        val screenY = panOffset.y + row * tileWorldPx * safeZoom
        if (screenY < floorTopLeft.y || screenY > floorTopLeft.y + floorHeight) continue
        drawLine(
            color = material.plankSeam,
            start = Offset(floorTopLeft.x, screenY),
            end = Offset(floorTopLeft.x + floorWidth, screenY),
            strokeWidth = seamStroke,
        )
        drawLine(
            color = material.plankHighlight,
            start = Offset(floorTopLeft.x, screenY + seamStroke),
            end = Offset(floorTopLeft.x + floorWidth, screenY + seamStroke),
            strokeWidth = highlightStroke,
        )
    }

    val tileTintAlpha = 0.018f
    for (row in firstRow..lastRow) {
        for (column in firstColumn..lastColumn) {
            if ((row + column) % 4 != 0) continue
            val tileLeft = panOffset.x + column * tileWorldPx * safeZoom
            val tileTop = panOffset.y + row * tileWorldPx * safeZoom
            val tileSize = tileWorldPx * safeZoom
            val clippedLeft = tileLeft.coerceAtLeast(floorTopLeft.x)
            val clippedTop = tileTop.coerceAtLeast(floorTopLeft.y)
            val clippedRight = (tileLeft + tileSize).coerceAtMost(floorTopLeft.x + floorWidth)
            val clippedBottom = (tileTop + tileSize).coerceAtMost(floorTopLeft.y + floorHeight)
            if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) continue
            drawRect(
                color = material.plankHighlight.copy(alpha = tileTintAlpha),
                topLeft = Offset(clippedLeft, clippedTop),
                size = Size(clippedRight - clippedLeft, clippedBottom - clippedTop),
            )
        }
    }

    val grainStepWorldPx = 148f
    val firstGrainRow = max(0, (visibleWorldTop / grainStepWorldPx).toInt() - 1)
    val lastGrainRow = min((contentHeightPx / grainStepWorldPx).toInt() + 1, (visibleWorldBottom / grainStepWorldPx).toInt() + 2)
    for (row in firstGrainRow..lastGrainRow) {
        val worldY = row * grainStepWorldPx + if (row % 2 == 0) 29f else 67f
        val screenY = panOffset.y + worldY * safeZoom
        val wobble = if (row % 2 == 0) 3f * safeZoom else -2f * safeZoom
        drawLine(
            color = material.grain,
            start = Offset(floorTopLeft.x + 28f * safeZoom, screenY),
            end = Offset(floorTopLeft.x + floorWidth - 28f * safeZoom, screenY + wobble),
            strokeWidth = max(0.25f, 0.34.dp.toPx()),
        )
    }

    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, material.vignette),
            center = Offset(floorTopLeft.x + floorWidth * 0.50f, floorTopLeft.y + floorHeight * 0.50f),
            radius = max(floorWidth, floorHeight) * 0.78f,
        ),
        topLeft = floorTopLeft,
        size = floorSize,
    )
    drawRect(
        color = material.plankSeam.copy(alpha = 0.32f),
        topLeft = floorTopLeft,
        size = floorSize,
        style = Stroke(width = max(1f, 1.dp.toPx())),
    )
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
private fun FloorPlanAreasLayer(
    placements: List<FloorPlanAreaPlacement>,
    panOffset: Offset,
    zoom: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        placements.forEach { placement ->
            val screen = placement.rect.toScreenRect(panOffset, zoom)
            val areaWidthPx = screen.width.coerceAtLeast(1f)
            val areaHeightPx = screen.height.coerceAtLeast(1f)

            withTransform({
                translate(left = screen.left, top = screen.top)
                rotate(
                    degrees = placement.effectiveRotationDeg,
                    pivot = Offset(areaWidthPx / 2f, areaHeightPx / 2f),
                )
            }) {
                drawFloorPlanAreaShape(
                    area = placement.area,
                    targetSize = Size(areaWidthPx, areaHeightPx),
                )
            }
        }
    }
}

private fun DrawScope.drawFloorPlanAreaShape(
    area: FloorMapArea,
    targetSize: Size,
) {
    val floor = activeFloorPlanSurfaceTheme().floor
    val fillTop = floor.baseMiddle.copy(alpha = 0.06f)
    val fillBottom = areaSurfaceColor(area).copy(alpha = 0.08f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.24f)
    val fillBrush = Brush.verticalGradient(
        colors = listOf(fillTop, fillBottom),
        startY = 0f,
        endY = targetSize.height,
    )
    val stroke = max(1f, 1.dp.toPx())

    when (area.shape.lowercase()) {
        "triangle" -> {
            val p1x = (area.p1XPercent ?: 0f) / 100f
            val p1y = (area.p1YPercent ?: 100f) / 100f
            val p2x = (area.p2XPercent ?: 100f) / 100f
            val p2y = (area.p2YPercent ?: 100f) / 100f
            val p3x = (area.p3XPercent
                ?: area.apexXPercent
                ?: 50f) / 100f
            val p3y = (area.p3YPercent ?: 0f) / 100f

            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(targetSize.width * p1x, targetSize.height * p1y)
                lineTo(targetSize.width * p2x, targetSize.height * p2y)
                lineTo(targetSize.width * p3x, targetSize.height * p3y)
                close()
            }

            drawPath(path = path, brush = fillBrush)
            drawPath(path = path, color = borderColor, style = Stroke(width = stroke))
        }

        "circle", "ellipse" -> {
            drawOval(
                brush = fillBrush,
                topLeft = Offset.Zero,
                size = targetSize,
            )
            drawOval(
                color = borderColor,
                topLeft = Offset.Zero,
                size = targetSize,
                style = Stroke(width = stroke),
            )
        }

        "roundedrectangle", "rounded-rectangle", "rounded_rect" -> {
            drawRoundRect(
                brush = fillBrush,
                topLeft = Offset.Zero,
                size = targetSize,
                cornerRadius = CornerRadius(14f, 14f),
            )
            drawRoundRect(
                color = borderColor,
                topLeft = Offset.Zero,
                size = targetSize,
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = stroke),
            )
        }

        else -> {
            drawRect(
                brush = fillBrush,
                topLeft = Offset.Zero,
                size = targetSize,
            )
            drawRect(
                color = borderColor,
                topLeft = Offset.Zero,
                size = targetSize,
                style = Stroke(width = stroke),
            )
        }
    }
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
    val modifier = Modifier
        .graphicsLayer {
            translationX = screen.left
            translationY = screen.top
            rotationZ = placement.effectiveRotationDeg
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
        .requiredSize(widthDp, heightDp)
    val floor = activeFloorPlanSurfaceTheme().floor
    val fillTop = floor.baseMiddle.copy(alpha = 0.06f)
    val fillBottom = areaSurfaceColor(area).copy(alpha = 0.08f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.24f)
    Canvas(modifier = modifier) {
        val fillBrush = Brush.verticalGradient(
            colors = listOf(fillTop, fillBottom),
            startY = 0f,
            endY = size.height,
        )
        val stroke = max(1f, 1.dp.toPx())
        when (area.shape.lowercase()) {
            "triangle" -> {
                val p1x = (area.p1XPercent ?: 0f) / 100f
                val p1y = (area.p1YPercent ?: 100f) / 100f
                val p2x = (area.p2XPercent ?: 100f) / 100f
                val p2y = (area.p2YPercent ?: 100f) / 100f
                val p3x = (area.p3XPercent
                    ?: area.apexXPercent
                    ?: 50f) / 100f
                val p3y = (area.p3YPercent ?: 0f) / 100f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * p1x, size.height * p1y)
                    lineTo(size.width * p2x, size.height * p2y)
                    lineTo(size.width * p3x, size.height * p3y)
                    close()
                }
                drawPath(path = path, brush = fillBrush)
                drawPath(path = path, color = borderColor, style = Stroke(width = stroke))
            }
            "circle", "ellipse" -> {
                drawOval(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = size,
                )
                drawOval(
                    color = borderColor,
                    topLeft = Offset.Zero,
                    size = size,
                    style = Stroke(width = stroke),
                )
            }
            "roundedrectangle", "rounded-rectangle", "rounded_rect" -> {
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(14f, 14f),
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = stroke),
                )
            }
            else -> {
                drawRect(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = size,
                )
                drawRect(
                    color = borderColor,
                    topLeft = Offset.Zero,
                    size = size,
                    style = Stroke(width = stroke),
                )
            }
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

private fun FloorMapObject.editorVisualWidthPx(worldScreen: FloorPlanRect): Float {
    return when {
        type.equals("camera", ignoreCase = true) -> FLOOR_PLAN_CAMERA_SYMBOL_PX
        else -> worldScreen.width.coerceAtLeast(1f)
    }
}

private fun FloorMapObject.editorVisualHeightPx(worldScreen: FloorPlanRect): Float {
    return when {
        type.equals("camera", ignoreCase = true) -> FLOOR_PLAN_CAMERA_SYMBOL_PX
        else -> worldScreen.height.coerceAtLeast(1f)
    }
}

private fun FloorMapObject.shouldRenderInWorldObjectLayer(): Boolean {
    return when (type.lowercase()) {
        "wall", "bar-counter" -> true
        else -> false
    }
}
private fun FloorMapObject.visualScreenRectForObject(worldScreen: FloorPlanRect): FloorPlanRect {
    return worldScreen.withTopLeftVisualSize(
        widthPx = editorVisualWidthPx(worldScreen),
        heightPx = editorVisualHeightPx(worldScreen),
    )
}

@Composable
private fun FloorPlanWorldObjectsLayer(
    placements: List<FloorPlanObjectPlacement>,
    panOffset: Offset,
    zoom: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        placements.forEach { placement ->
            val floorObject = placement.floorObject
            val worldScreen = placement.rect.toScreenRect(panOffset, zoom)
            val screen = floorObject.visualScreenRectForObject(worldScreen)
            val objectWidthPx = screen.width.coerceAtLeast(1f)
            val objectHeightPx = screen.height.coerceAtLeast(1f)

            withTransform({
                translate(left = screen.left, top = screen.top)
                rotate(
                    degrees = placement.effectiveRotationDeg,
                    pivot = Offset(objectWidthPx / 2f, objectHeightPx / 2f),
                )
            }) {
                drawFloorPlanWorldObjectShape(
                    floorObject = floorObject,
                    targetSize = Size(objectWidthPx, objectHeightPx),
                )
            }
        }
    }
}

private fun DrawScope.drawFloorPlanWorldObjectShape(
    floorObject: FloorMapObject,
    targetSize: Size,
) {
    val stroke = max(1f, 1.dp.toPx())

    when (floorObject.type.lowercase()) {
        "wall" -> {
            drawRect(
                color = Color(0xFF3A1E0E).copy(alpha = 0.96f),
                topLeft = Offset.Zero,
                size = targetSize,
            )
            drawRect(
                color = Color(0x99E0A64A),
                topLeft = Offset.Zero,
                size = targetSize,
                style = Stroke(width = stroke),
            )
        }

        "bar-counter" -> {
            drawFloorPlanBarCounterSurface(
                topLeft = Offset.Zero,
                targetSize = targetSize,
                stroke = stroke,
            )
        }
    }
}

private fun DrawScope.drawFloorPlanBarCounterSurface(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
) {
    val material = activeFloorPlanSurfaceTheme().barCounter
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    val radiusValue = floorPlanClamp(minDim * 0.045f, 3f, 10f)
    val radius = CornerRadius(radiusValue, radiusValue)
    val horizontalGrain = width >= height

    val frontBandFraction = 0.34f
    val frontBandHeight = if (horizontalGrain) height * frontBandFraction else 0f
    val frontBandWidth = if (horizontalGrain) 0f else width * frontBandFraction
    val topBandSize = if (horizontalGrain) {
        Size(width, (height - frontBandHeight).coerceAtLeast(1f))
    } else {
        Size((width - frontBandWidth).coerceAtLeast(1f), height)
    }

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(material.top, material.middle),
            start = topLeft,
            end = if (horizontalGrain) {
                Offset(topLeft.x + width, topLeft.y + topBandSize.height)
            } else {
                Offset(topLeft.x + topBandSize.width, topLeft.y + height)
            },
        ),
        topLeft = topLeft,
        size = topBandSize,
        cornerRadius = radius,
    )

    val frontBandTopLeft = if (horizontalGrain) {
        Offset(topLeft.x, topLeft.y + topBandSize.height)
    } else {
        Offset(topLeft.x + topBandSize.width, topLeft.y)
    }
    val frontBandSize = if (horizontalGrain) {
        Size(width, frontBandHeight.coerceAtLeast(1f))
    } else {
        Size(frontBandWidth.coerceAtLeast(1f), height)
    }
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(material.frontPanelTop, material.frontPanelBottom, material.bottom),
            start = frontBandTopLeft,
            end = if (horizontalGrain) {
                Offset(frontBandTopLeft.x + width * 0.45f, frontBandTopLeft.y + frontBandSize.height)
            } else {
                Offset(frontBandTopLeft.x + frontBandSize.width, frontBandTopLeft.y + height * 0.55f)
            },
        ),
        topLeft = frontBandTopLeft,
        size = frontBandSize,
        cornerRadius = radius,
    )

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, material.highlight, Color.Transparent),
            start = topLeft,
            end = Offset(topLeft.x + width, topLeft.y + height),
        ),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = radius,
    )

    drawFloorPlanWoodGrainLines(
        topLeft = topLeft,
        targetSize = topBandSize,
        horizontal = horizontalGrain,
        subtle = false,
        grainLight = material.grainLight,
        grainDark = material.grainDark,
    )
    drawFloorPlanWoodGrainLines(
        topLeft = frontBandTopLeft,
        targetSize = frontBandSize,
        horizontal = horizontalGrain,
        subtle = true,
        grainLight = material.grainLight,
        grainDark = material.grainDark,
    )

    val seamStroke = max(stroke, minDim * 0.020f)
    if (horizontalGrain) {
        val seamY = topLeft.y + topBandSize.height
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(topLeft.x, seamY),
            end = Offset(topLeft.x + width, seamY),
            strokeWidth = seamStroke,
        )
        drawLine(
            color = material.edge.copy(alpha = 0.85f),
            start = Offset(topLeft.x, seamY - seamStroke * 0.55f),
            end = Offset(topLeft.x + width, seamY - seamStroke * 0.55f),
            strokeWidth = max(0.85f, seamStroke * 0.65f),
        )
    } else {
        val seamX = topLeft.x + topBandSize.width
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(seamX, topLeft.y),
            end = Offset(seamX, topLeft.y + height),
            strokeWidth = seamStroke,
        )
        drawLine(
            color = material.edge.copy(alpha = 0.85f),
            start = Offset(seamX - seamStroke * 0.55f, topLeft.y),
            end = Offset(seamX - seamStroke * 0.55f, topLeft.y + height),
            strokeWidth = max(0.85f, seamStroke * 0.65f),
        )
    }

    val railInset = max(3f, minDim * 0.09f)
    val railStroke = max(stroke, minDim * 0.030f)
    drawLine(
        color = material.railLight,
        start = Offset(topLeft.x + railInset, topLeft.y + railInset),
        end = if (horizontalGrain) {
            Offset(topLeft.x + width - railInset, topLeft.y + railInset)
        } else {
            Offset(topLeft.x + railInset, topLeft.y + height - railInset)
        },
        strokeWidth = railStroke,
    )
    drawLine(
        color = material.railDark,
        start = if (horizontalGrain) {
            Offset(topLeft.x + railInset, topLeft.y + height - railInset)
        } else {
            Offset(topLeft.x + width - railInset, topLeft.y + railInset)
        },
        end = Offset(topLeft.x + width - railInset, topLeft.y + height - railInset),
        strokeWidth = railStroke,
    )
    drawRoundRect(
        color = material.edge,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = radius,
        style = Stroke(width = max(stroke, minDim * 0.018f)),
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
    val worldScreen = placement.rect.toScreenRect(panOffset, zoom)
    val objectType = floorObject.type.lowercase()
    val isCamera = objectType == "camera"
    val visualScreen = floorObject.visualScreenRectForObject(worldScreen)
    val objectWidthPx = visualScreen.width
    val objectHeightPx = visualScreen.height
    val widthDp = objectWidthPx.toDp(density)
    val heightDp = objectHeightPx.toDp(density)
    val baseModifier = Modifier.graphicsLayer {
        translationX = visualScreen.left
        translationY = visualScreen.top
        rotationZ = if (isCamera) {
            0f
        } else {
            placement.effectiveRotationDeg
        }
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
    when (objectType) {
        "door" -> {
            FloorPlanDoorObjectNode(
                floorObject = floorObject,
                widthDp = widthDp,
                heightDp = heightDp,
                modifier = baseModifier,
            )
        }
        "camera" -> {
            FloorPlanCameraSymbol(
                modifier = baseModifier.requiredSize(widthDp, heightDp).graphicsLayer {
                    rotationZ = placement.effectiveRotationDeg
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
                label = floorObject.label,
                screenWidthPx = objectWidthPx,
            )
        }
        "sofa", "couch" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                FloorPlanSofaSurface(
                    sofaStyle = floorObject.sofaStyle,
                    backrestDirection = floorObject.backrestDirection,
                    armrestMode = floorObject.armrestMode,
                    seatCount = floorObject.capacity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        else -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = objectWidthPx,
                modifier = baseModifier,
                objectType = objectType,
                objectColorHex = floorObject.color,
                objectBackrestDirection = floorObject.backrestDirection,
                objectArmrestMode = floorObject.armrestMode,
                showLabel = objectType != "wall" && objectType != "door" &&
                    objectType != "sofa" &&
                    objectType != "couch" &&
                    objectType != "chair" &&
                    objectType != "armchair",
            )
        }
    }
}

@Composable
private fun FloorPlanSofaSurface(
    sofaStyle: FloorPlanSofaStyle?,
    backrestDirection: String?,
    armrestMode: String?,
    seatCount: Int?,
    modifier: Modifier = Modifier,
) {
    val resolvedSofaStyle = sofaStyle ?: FloorPlanSofaStyle.PREMIUM_LEATHER
    val normalizedArmrestMode = normalizeFloorPlanSofaArmrestMode(armrestMode)
    when (resolvedSofaStyle) {
        FloorPlanSofaStyle.PREMIUM_LEATHER -> {
            FloorPlanLeatherSofaAssetSurface(
                backrestDirection = backrestDirection,
                modifier = modifier,
            )
        }
        FloorPlanSofaStyle.TERRACE_POLY_RATTAN -> {
            FloorPlanTerracePolyRattanSofaSurface(
                backrestDirection = backrestDirection,
                armrestMode = normalizedArmrestMode,
                seatCount = seatCount,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FloorPlanLeatherSofaAssetSurface(
    backrestDirection: String?,
    modifier: Modifier = Modifier,
) {
    val sofaAsset = ImageBitmap.imageResource(id = R.drawable.sofa2_premium_topdown_asset_v2)
    Canvas(modifier = modifier) {
        val localRotationDeg = sofaAssetLocalRotationDeg(backrestDirection)
        val canvasWidth = size.width.coerceAtLeast(1f)
        val canvasHeight = size.height.coerceAtLeast(1f)
        val swapsAxes = localRotationDeg == 90f || localRotationDeg == 270f
        val drawWidth = if (swapsAxes) canvasHeight else canvasWidth
        val drawHeight = if (swapsAxes) canvasWidth else canvasHeight
        val drawOffset = IntOffset(
            x = ((canvasWidth - drawWidth) / 2f).roundToInt(),
            y = ((canvasHeight - drawHeight) / 2f).roundToInt(),
        )
        val drawSize = IntSize(
            width = drawWidth.roundToInt().coerceAtLeast(1),
            height = drawHeight.roundToInt().coerceAtLeast(1),
        )

        withTransform({
            if (localRotationDeg != 0f) {
                rotate(
                    degrees = localRotationDeg,
                    pivot = Offset(canvasWidth / 2f, canvasHeight / 2f),
                )
            }
        }) {
            drawImage(
                image = sofaAsset,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(sofaAsset.width, sofaAsset.height),
                dstOffset = drawOffset,
                dstSize = drawSize,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

@Composable
private fun FloorPlanTerracePolyRattanSofaSurface(
    backrestDirection: String?,
    armrestMode: String,
    seatCount: Int?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawFloorPlanTerracePolyRattanSofa(
            topLeft = Offset.Zero,
            targetSize = size,
            stroke = max(1f, 1.dp.toPx()),
            seatCount = seatCount?.coerceIn(1, 4) ?: estimateFloorPlanSofaSeatCount(size),
            backrestDirection = backrestDirection,
            armrestMode = armrestMode,
        )
    }
}

private fun normalizeFloorPlanSofaArmrestMode(armrestMode: String?): String {
    return when (armrestMode) {
        "both", "none", "left-only", "right-only" -> armrestMode
        else -> "both"
    }
}

private fun sofaAssetLocalRotationDeg(backrestDirection: String?): Float {
    return when (backrestDirection) {
        "right" -> 90f
        "bottom" -> 180f
        "left" -> 270f
        else -> 0f
    }
}

private fun DrawScope.drawFloorPlanTerracePolyRattanSofa(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
    seatCount: Int,
    backrestDirection: String?,
    armrestMode: String?,
) {
    val safeSize = floorPlanSafeSize(targetSize)
    val localRotationDeg = sofaAssetLocalRotationDeg(backrestDirection)
    val center = Offset(topLeft.x + safeSize.width / 2f, topLeft.y + safeSize.height / 2f)
    val normalizedArmrestMode = normalizeFloorPlanSofaArmrestMode(armrestMode)

    withTransform({
        if (localRotationDeg != 0f) {
            rotate(degrees = localRotationDeg, pivot = center)
        }
    }) {
        drawFloorPlanTerracePolyRattanSofaOriented(
            topLeft = topLeft,
            targetSize = safeSize,
            stroke = stroke,
            seatCount = seatCount,
            armrestMode = normalizedArmrestMode,
        )
    }
}

private fun DrawScope.drawFloorPlanTerracePolyRattanSofaOriented(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
    seatCount: Int,
    armrestMode: String,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    val outlineStroke = max(0.8f, min(stroke, minDim * 0.052f))

    val frameTop = Color(0xFF46545B)
    val frameBottom = Color(0xFF232C31)
    val frameBorder = Color(0xFF151B1F).copy(alpha = 0.72f)
    val weaveLight = Color(0xFF68787F).copy(alpha = 0.26f)
    val weaveDark = Color(0xFF151B1F).copy(alpha = 0.32f)
    val cushionTop = Color(0xFFDAD5C8)
    val cushionBottom = Color(0xFFB8B1A3)
    val cushionBorder = Color(0xFF6D665D).copy(alpha = 0.48f)
    val cushionHighlight = Color(0xFFF3EEE0).copy(alpha = 0.22f)

    val outerInset = floorPlanClamp(minDim * 0.030f, 1.2f, 5.0f)
    val frameTopLeft = Offset(topLeft.x + outerInset, topLeft.y + outerInset)
    val frameSize = Size(
        width = (width - outerInset * 2f).coerceAtLeast(1f),
        height = (height - outerInset * 2f).coerceAtLeast(1f),
    )
    val frameRadiusValue = floorPlanClamp(minDim * 0.095f, 3.5f, 12f)
    val frameRadius = CornerRadius(frameRadiusValue, frameRadiusValue)

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(frameTop, frameBottom),
            startY = frameTopLeft.y,
            endY = frameTopLeft.y + frameSize.height,
        ),
        topLeft = frameTopLeft,
        size = frameSize,
        cornerRadius = frameRadius,
    )
    drawFloorPlanRattanWeave(
        topLeft = frameTopLeft,
        targetSize = frameSize,
        light = weaveLight,
        dark = weaveDark,
        stroke = outlineStroke,
    )
    drawRoundRect(
        color = frameBorder,
        topLeft = frameTopLeft,
        size = frameSize,
        cornerRadius = frameRadius,
        style = Stroke(width = outlineStroke),
    )

    val backDepth = floorPlanClamp(frameSize.height * 0.235f, 2f, frameSize.height * 0.33f)
    val armThickness = floorPlanClamp(frameSize.width * 0.132f, 2f, frameSize.width * 0.22f)
    val sideInset = frameSize.width * 0.040f
    val backTopLeft = Offset(frameTopLeft.x + sideInset, frameTopLeft.y + frameSize.height * 0.038f)
    val backSize = Size((frameSize.width - sideInset * 2f).coerceAtLeast(1f), backDepth)
    val backRadius = CornerRadius(
        x = floorPlanClamp(minDim * 0.050f, 2.0f, 8.0f),
        y = floorPlanClamp(minDim * 0.050f, 2.0f, 8.0f),
    )
    drawRoundRect(
        color = frameBottom.copy(alpha = 0.52f),
        topLeft = backTopLeft,
        size = backSize,
        cornerRadius = backRadius,
    )
    drawFloorPlanRattanWeave(
        topLeft = backTopLeft,
        targetSize = backSize,
        light = weaveLight.copy(alpha = 0.82f),
        dark = weaveDark.copy(alpha = 0.92f),
        stroke = outlineStroke * 0.86f,
    )

    val armTop = frameTopLeft.y + backDepth * 0.60f
    val armBottom = frameTopLeft.y + frameSize.height - frameSize.height * 0.070f
    val armHeight = (armBottom - armTop).coerceAtLeast(1f)
    val leftArmTopLeft = Offset(frameTopLeft.x + frameSize.width * 0.034f, armTop)
    val rightArmTopLeft = Offset(frameTopLeft.x + frameSize.width - frameSize.width * 0.034f - armThickness, armTop)
    val armSize = Size(armThickness, armHeight)
    val armRadius = CornerRadius(
        x = floorPlanClamp(minDim * 0.060f, 2.2f, 8.4f),
        y = floorPlanClamp(minDim * 0.060f, 2.2f, 8.4f),
    )
    val showFirstArm = armrestMode == "both" || armrestMode == "left-only"
    val showSecondArm = armrestMode == "both" || armrestMode == "right-only"
    listOfNotNull(
        leftArmTopLeft.takeIf { showFirstArm },
        rightArmTopLeft.takeIf { showSecondArm },
    ).forEach { armTopLeft ->
        drawRoundRect(
            color = frameBottom.copy(alpha = 0.62f),
            topLeft = armTopLeft,
            size = armSize,
            cornerRadius = armRadius,
        )
        drawFloorPlanRattanWeave(
            topLeft = armTopLeft,
            targetSize = armSize,
            light = weaveLight.copy(alpha = 0.88f),
            dark = weaveDark.copy(alpha = 0.95f),
            stroke = outlineStroke * 0.82f,
        )
    }

    val seatGap = floorPlanClamp(minDim * 0.030f, 1.2f, 4.2f)
    var seatLeft = frameTopLeft.x + frameSize.width * 0.070f
    var seatRight = frameTopLeft.x + frameSize.width * 0.930f
    if (showFirstArm) seatLeft = leftArmTopLeft.x + armSize.width + seatGap
    if (showSecondArm) seatRight = rightArmTopLeft.x - seatGap
    val seatTop = backTopLeft.y + backSize.height + seatGap
    val seatBottom = frameTopLeft.y + frameSize.height - frameSize.height * 0.090f
    val seatSize = Size(
        width = (seatRight - seatLeft).coerceAtLeast(1f),
        height = (seatBottom - seatTop).coerceAtLeast(1f),
    )
    drawFloorPlanTerraceSofaCushions(
        topLeft = Offset(seatLeft, seatTop),
        targetSize = seatSize,
        seatCount = seatCount.coerceIn(1, 4),
        topColor = cushionTop,
        bottomColor = cushionBottom,
        borderColor = cushionBorder,
        highlightColor = cushionHighlight,
        stroke = outlineStroke,
    )
}

private fun DrawScope.drawFloorPlanRattanWeave(
    topLeft: Offset,
    targetSize: Size,
    light: Color,
    dark: Color,
    stroke: Float,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    val weaveStep = floorPlanClamp(minDim * 0.16f, 3.5f, 12f)
    val first = topLeft.x - height
    val last = topLeft.x + width

    var offset = first
    while (offset <= last) {
        drawLine(
            color = light,
            start = Offset(offset, topLeft.y),
            end = Offset(offset + height, topLeft.y + height),
            strokeWidth = max(0.45f, stroke * 0.38f),
        )
        drawLine(
            color = dark,
            start = Offset(offset + weaveStep * 0.48f, topLeft.y),
            end = Offset(offset + weaveStep * 0.48f + height, topLeft.y + height),
            strokeWidth = max(0.4f, stroke * 0.32f),
        )
        offset += weaveStep
    }
}

private fun DrawScope.drawFloorPlanTerraceSofaCushions(
    topLeft: Offset,
    targetSize: Size,
    seatCount: Int,
    topColor: Color,
    bottomColor: Color,
    borderColor: Color,
    highlightColor: Color,
    stroke: Float,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val gap = floorPlanClamp(min(width, height) * 0.050f, 1.0f, 5f)
    val cushionWidth = ((width - gap * (seatCount - 1)) / seatCount).coerceAtLeast(1f)
    repeat(seatCount) { index ->
        val cushionTopLeft = Offset(topLeft.x + index * (cushionWidth + gap), topLeft.y)
        val cushionSize = Size(cushionWidth, height)
        val radiusValue = floorPlanClamp(min(cushionWidth, height) * 0.10f, 1.8f, 8f)
        val radius = CornerRadius(radiusValue, radiusValue)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(topColor, bottomColor),
                startY = cushionTopLeft.y,
                endY = cushionTopLeft.y + cushionSize.height,
            ),
            topLeft = cushionTopLeft,
            size = cushionSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = highlightColor,
            topLeft = Offset(cushionTopLeft.x + cushionSize.width * 0.08f, cushionTopLeft.y + cushionSize.height * 0.08f),
            size = Size(cushionSize.width * 0.84f, cushionSize.height * 0.24f),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = borderColor,
            topLeft = cushionTopLeft,
            size = cushionSize,
            cornerRadius = radius,
            style = Stroke(width = max(0.55f, stroke * 0.50f)),
        )
    }
}

@Composable
private fun FloorPlanLabeledObjectSurface(
    label: String,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    screenWidthPx: Float,
    modifier: Modifier,
    objectType: String,
    objectColorHex: String? = null,
    objectBackrestDirection: String? = null,
    objectArmrestMode: String? = null,
    showLabel: Boolean = true,
) {
    val normalizedType = objectType.lowercase()
    val objectColor = parseFloorPlanObjectColor(objectColorHex)
    Box(modifier = modifier.requiredSize(widthDp, heightDp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = max(1f, 1.dp.toPx())
            when (normalizedType) {
                "wall" -> {
                    drawRect(
                        color = Color(0xFF3A1E0E).copy(alpha = 0.96f),
                        topLeft = Offset.Zero,
                        size = size,
                    )
                    drawRect(
                        color = Color(0x99E0A64A),
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = stroke),
                    )
                }
                "bar-counter" -> {
                    drawFloorPlanBarCounterSurface(
                        topLeft = Offset.Zero,
                        targetSize = size,
                        stroke = stroke,
                    )
                }
                "chair", "armchair" -> {
                    val isArmchair = normalizedType == "armchair"
                    if (isArmchair) {
                        val inset = size.minDimension * 0.10f
                        val bodyTopLeft = Offset(inset, inset)
                        val bodySize = Size(
                            width = (size.width - inset * 2f).coerceAtLeast(1f),
                            height = (size.height - inset * 2f).coerceAtLeast(1f),
                        )
                        val radius = CornerRadius(
                            x = min(9f, bodySize.width * 0.18f),
                            y = min(9f, bodySize.height * 0.18f),
                        )
                        drawFloorPlanArmchairSurface(
                            topLeft = bodyTopLeft,
                            targetSize = bodySize,
                            radius = radius,
                            stroke = stroke,
                            baseColor = objectColor,
                            backrestDirection = objectBackrestDirection,
                            armrestMode = objectArmrestMode,
                        )
                    } else {
                        val bodyInset = size.minDimension * 0.14f
                        val bodyTopLeft = Offset(bodyInset, bodyInset)
                        val bodySize = Size(
                            width = (size.width - bodyInset * 2f).coerceAtLeast(1f),
                            height = (size.height - bodyInset * 2f).coerceAtLeast(1f),
                        )
                        drawFloorPlanChairSurface(
                            topLeft = bodyTopLeft,
                            targetSize = bodySize,
                            stroke = stroke,
                            backOnTop = size.height < size.width,
                        )
                    }
                }

                "pos-marker", "text-label" -> {
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset.Zero,
                        size = size,
                    )
                    drawRect(
                        color = FloorPlanSelectionColor.copy(alpha = 0.48f),
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = stroke),
                    )
                }
                else -> {
                    drawRoundRect(
                        color = Color(0xFF25303A).copy(alpha = 0.88f),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                    drawRoundRect(
                        color = Color(0x4466F6E8),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(2f, 2f),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }

        if (showLabel && label.isNotBlank() && screenWidthPx >= 36f) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = label,
                    modifier = Modifier
                        .background(FloorPlanHintSurface, RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
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
            val isVertical = size.height >= size.width
            val lengthPx = max(1f, if (isVertical) size.height else size.width)
            val thicknessPx = max(1f, if (isVertical) size.width else size.height)
            val hingeIsEnd = floorObject.doorHingeSide?.equals("right", ignoreCase = true) == true
            val swingSign = if (floorObject.doorSwingDirection?.equals("in", ignoreCase = true) == true) -1f else 1f
            val leafStroke = max(2f, min(6f, thicknessPx * 0.72f))
            val thresholdStroke = max(1f, min(3f, thicknessPx * 0.32f))
            val hingeMarkerSize = max(4f, min(8f, leafStroke + 2f))
            val arcStroke = max(1f, min(2f, leafStroke * 0.45f))

            val hingeX = if (isVertical) size.width / 2f else if (hingeIsEnd) size.width else 0f
            val hingeY = if (isVertical) {
                if (hingeIsEnd) size.height else 0f
            } else {
                size.height / 2f
            }
            val closedEndX = if (isVertical) {
                hingeX
            } else if (hingeIsEnd) {
                hingeX - lengthPx
            } else {
                hingeX + lengthPx
            }
            val closedEndY = if (isVertical) {
                if (hingeIsEnd) hingeY - lengthPx else hingeY + lengthPx
            } else {
                hingeY
            }
            val openEndX = if (isVertical) hingeX + swingSign * lengthPx else hingeX
            val openEndY = if (isVertical) hingeY else hingeY + swingSign * lengthPx

            // The floor-plan editor stores the door rectangle as the swing envelope.
            // In the POS renderer the visual roles were inverted: the pale open leaf was
            // drawn on the frame/closed guide side and the guide was drawn where the leaf
            // should be. Keep the data contract intact and fix only the symbol semantics:
            // thin muted guide = closed/reference edge, bright thick line = door leaf.
            val guideEndX = openEndX
            val guideEndY = openEndY
            val leafEndX = closedEndX
            val leafEndY = closedEndY

            drawLine(
                color = Color(0xFFF1DC9C).copy(alpha = 0.72f),
                start = Offset(hingeX, hingeY),
                end = Offset(guideEndX, guideEndY),
                strokeWidth = thresholdStroke,
            )

            // Draw the swing path between the closed door leaf and the reference edge.
            // Use an explicit quadratic path so the curve is anchored to the hinge and
            // cannot flip to the wrong quadrant when the door object is rotated/resized.
            val swingControl = Offset(
                x = if (leafEndX != hingeX) leafEndX else guideEndX,
                y = if (leafEndY != hingeY) leafEndY else guideEndY,
            )
            val swingPath = Path().apply {
                moveTo(guideEndX, guideEndY)
                quadraticBezierTo(
                    swingControl.x,
                    swingControl.y,
                    leafEndX,
                    leafEndY,
                )
            }
            drawPath(
                path = swingPath,
                color = Color(0xFFF1DC9C).copy(alpha = 0.56f),
                style = Stroke(width = arcStroke),
            )

            drawLine(
                color = Color(0xFFFFF2D4).copy(alpha = 0.95f),
                start = Offset(hingeX, hingeY),
                end = Offset(leafEndX, leafEndY),
                strokeWidth = leafStroke,
            )
            drawRect(
                color = Color(0xFF201108),
                topLeft = Offset(hingeX - hingeMarkerSize / 2f, hingeY - hingeMarkerSize / 2f),
                size = Size(hingeMarkerSize, hingeMarkerSize),
            )
            drawRect(
                color = Color(0xFFFFF2D4).copy(alpha = 0.90f),
                topLeft = Offset(hingeX - hingeMarkerSize / 2f, hingeY - hingeMarkerSize / 2f),
                size = Size(hingeMarkerSize, hingeMarkerSize),
                style = Stroke(width = max(1f, 1.dp.toPx())),
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
    val statusChipAnchor = table.resolveStatusChipAnchor()
    val seatMarkerAnchor = table.resolveSeatMarkerAnchor()
    val tableShapeRaw = table.floorPlanShape?.lowercase()
    val isRound = when (tableShapeRaw) {
        "round", "circle", "ellipse" -> true
        "square", "rectangle" -> false
        else -> !isMerged && abs(rect.width - rect.height) <= 18f
    }
    val seatMarkers = remember(
        table.id,
        table.seats,
        table.chairLayout,
        table.floorPlanShape,
        table.spotType,
        seatMarkerAnchor,
        bodyWidthPx,
        bodyHeightPx,
    ) {
        getTableSeatMarkers(
            table = table,
            widthPx = bodyWidthPx,
            heightPx = bodyHeightPx,
            markerAnchor = seatMarkerAnchor,
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
    val pulseTransition = rememberInfiniteTransition()
    val attentionPulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val isBarSeat = table.spotType == ServiceSpotType.BAR_SEAT
    val anchoredStatusOutside = statusChipAnchor != null && statusChipAnchor != FloorPlanMarkerAnchor.CENTER
    val anchoredBarSeatVisible = isBarSeat && anchoredStatusOutside && bodyWidthPx >= 22f && bodyHeightPx >= 22f
    val showLabel = bodyWidthPx >= 30f && bodyHeightPx >= 22f
    val showStatusTick = if (anchoredBarSeatVisible) {
        true
    } else {
        bodyWidthPx >= 60f && bodyHeightPx >= 50f
    }
    val showOpenChips = openSaleTotalLabels.isNotEmpty() && (
        if (anchoredBarSeatVisible) {
            true
        } else {
            bodyWidthPx >= 70f && bodyHeightPx >= 64f
        }
    )
    val showGuestCount = if (anchoredBarSeatVisible) {
        true
    } else {
        bodyWidthPx >= 58f && bodyHeightPx >= 48f
    }

    val chipSpecs = buildList {
        if (showStatusTick) {
            val rawStatusChipLabel = statusTick.label
            add(
                FloorPlanChipSpec(
                    label = rawStatusChipLabel.toFinnishFloorPlanStatusChipLabel(),
                    kind = FloorPlanChipKind.STATUS,
                    tint = attentionTint ?: accent,
                    pulse = rawStatusChipLabel.shouldPulseFloorPlanStatusChip(),
                )
            )
        }
        if (showOpenChips) {
            openSaleTotalLabels.filter { it.isNotBlank() }.forEach { amountLabel ->
                add(FloorPlanChipSpec(label = amountLabel, kind = FloorPlanChipKind.AMOUNT))
            }
        }
        if (showGuestCount) {
            table.floorPlanCustomerLabel()?.let { customerLabel ->
                add(FloorPlanChipSpec(label = customerLabel, kind = FloorPlanChipKind.META))
            }
            table.floorPlanSeatsLabel()?.let { seatsLabel ->
                add(FloorPlanChipSpec(label = seatsLabel, kind = FloorPlanChipKind.META))
            }
        }
    }
    val renderAnchoredChipStack = anchoredStatusOutside && chipSpecs.isNotEmpty()
    val chipStackVerticalGapDp = if (renderAnchoredChipStack && isBarSeat) 1.dp else 4.dp
    val chipStackVerticalGapPx = with(density) { chipStackVerticalGapDp.toPx() }
    val anchoredChipStackMetrics = if (renderAnchoredChipStack) {
        estimateFloorPlanChipStackMetrics(
            chips = chipSpecs,
            density = density,
            bodyWidthPx = bodyWidthPx,
            bodyHeightPx = bodyHeightPx,
            verticalGapPx = chipStackVerticalGapPx,
        )
    } else {
        FloorPlanChipMetrics(widthPx = 0f, heightPx = 0f)
    }
    val anchorGapPx = if (renderAnchoredChipStack) {
        if (isBarSeat) {
            max(
                with(density) { 1.dp.toPx() },
                min(bodyWidthPx, bodyHeightPx) * 0.02f,
            )
        } else {
            max(
                with(density) { 4.dp.toPx() },
                min(bodyWidthPx, bodyHeightPx) * 0.08f,
            )
        }
    } else {
        0f
    }
    val anchoredChipOutsideFactor = if (renderAnchoredChipStack) {
        floorPlanAnchoredChipOutsideFactor(
            bodyWidthPx = bodyWidthPx,
            bodyHeightPx = bodyHeightPx,
            isBarSeat = isBarSeat,
        )
    } else {
        1f
    }
    val chipStackTopLeft = if (renderAnchoredChipStack && statusChipAnchor != null) {
        floorPlanAnchoredChipStackTopLeft(
            anchor = statusChipAnchor,
            bodyWidthPx = bodyWidthPx,
            bodyHeightPx = bodyHeightPx,
            stackWidthPx = anchoredChipStackMetrics.widthPx,
            stackHeightPx = anchoredChipStackMetrics.heightPx,
            gapPx = anchorGapPx,
            outsideFactor = anchoredChipOutsideFactor,
        )
    } else {
        Offset.Zero
    }

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
    val chipLeftOverflow = if (renderAnchoredChipStack) min(0f, chipStackTopLeft.x) else 0f
    val chipTopOverflow = if (renderAnchoredChipStack) min(0f, chipStackTopLeft.y) else 0f
    val chipRightOverflow = if (renderAnchoredChipStack) {
        max(0f, chipStackTopLeft.x + anchoredChipStackMetrics.widthPx - bodyWidthPx)
    } else {
        0f
    }
    val chipBottomOverflow = if (renderAnchoredChipStack) {
        max(0f, chipStackTopLeft.y + anchoredChipStackMetrics.heightPx - bodyHeightPx)
    } else {
        0f
    }
    val combinedLeftOverflow = min(markerLeftOverflow, chipLeftOverflow)
    val combinedTopOverflow = min(markerTopOverflow, chipTopOverflow)
    val combinedRightOverflow = max(markerRightOverflow, chipRightOverflow)
    val combinedBottomOverflow = max(markerBottomOverflow, chipBottomOverflow)
    val markerPadPx = max(
        0f,
        max(
            max(-combinedLeftOverflow, -combinedTopOverflow),
            max(combinedRightOverflow, combinedBottomOverflow),
        ) + 3f,
    )
    val nodeWidthPx = bodyWidthPx + markerPadPx * 2f
    val nodeHeightPx = bodyHeightPx + markerPadPx * 2f
    val nodeWidthDp = nodeWidthPx.toDp(density)
    val nodeHeightDp = nodeHeightPx.toDp(density)
    val hiddenStatusPulseTint = when {
        showStatusTick -> null
        attentionTint != null -> attentionTint
        displayStatus.kind == TableDisplayStatusKind.AVAILABLE -> null
        displayStatus.kind == TableDisplayStatusKind.DIRTY -> FloorPlanDirtyColor
        else -> FloorPlanOccupiedColor
    }
    val hiddenStatusPulse = if (hiddenStatusPulseTint != null) attentionPulse else 0f
    val borderWidthPx = when {
        dropHovered -> with(density) { 3.dp.toPx() }
        hiddenStatusPulseTint != null -> with(density) { (2f + hiddenStatusPulse * 1.6f).dp.toPx() }
        attentionTint != null -> with(density) { 2.dp.toPx() }
        selected -> with(density) { 2.dp.toPx() }
        else -> with(density) { 1.dp.toPx() }
    }
    val borderColor = when {
        hiddenStatusPulseTint != null -> hiddenStatusPulseTint.copy(alpha = 0.58f + hiddenStatusPulse * 0.38f)
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
            val bodyStroke = borderColor
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

            drawFloorPlanTableSurface(
                isRound = isRound,
                isBarSeat = isBarSeat,
                topLeft = bodyTopLeft,
                targetSize = bodySize,
                borderColor = bodyStroke,
                borderWidthPx = borderWidthPx,
                baseColor = parseFloorPlanObjectColor(table.color),
                backrestDirection = table.backrestDirection,
                backrestMode = table.backrestMode,
            )
        }

        Box(
            modifier = Modifier
                .offset(x = markerPadPx.toDp(density), y = markerPadPx.toDp(density))
                .requiredSize(bodyWidthPx.toDp(density), bodyHeightPx.toDp(density))
                .graphicsLayer {
                    rotationZ = -rotationDeg
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
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
                if (!renderAnchoredChipStack && chipSpecs.isNotEmpty()) {
                    FloorPlanAdaptiveChipGrid(
                        chips = chipSpecs,
                        bodyWidthPx = bodyWidthPx,
                        bodyHeightPx = bodyHeightPx,
                        isRound = isRound,
                    )
                }
            }
        }
        if (renderAnchoredChipStack) {
            FloorPlanAnchoredChipStack(
                chips = chipSpecs,
                bodyWidthPx = bodyWidthPx,
                bodyHeightPx = bodyHeightPx,
                verticalGapDp = chipStackVerticalGapDp,
                modifier = Modifier
                    .offset(
                        x = (markerPadPx + chipStackTopLeft.x).toDp(density),
                        y = (markerPadPx + chipStackTopLeft.y).toDp(density),
                    )
                    .graphicsLayer {
                        rotationZ = -rotationDeg
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
            )
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
        short >= 110f -> if (emphasize) 18f else 16f
        short >= 80f -> if (emphasize) 16f else 14f
        short >= 60f -> 13f
        short >= 44f -> 12f
        else -> 11f
    }
    return androidx.compose.ui.text.TextStyle(fontSize = baseSp.sp)
}

private fun floorPlanTableStatusFontSp(
    screenWidthPx: Float,
    screenHeightPx: Float,
): Float {
    val short = min(screenWidthPx, screenHeightPx)
    return when {
        short >= 110f -> 16f
        short >= 80f -> 14f
        short >= 60f -> 13f
        else -> 12f
    }
}

private fun floorPlanTableStatusLabelStyle(
    screenWidthPx: Float,
    screenHeightPx: Float,
): androidx.compose.ui.text.TextStyle {
    return androidx.compose.ui.text.TextStyle(fontSize = floorPlanTableStatusFontSp(screenWidthPx, screenHeightPx).sp)
}

private fun floorPlanTableMetaFontSp(): Float = 14f

private fun floorPlanTableMetaLabelStyle(): androidx.compose.ui.text.TextStyle {
    return androidx.compose.ui.text.TextStyle(fontSize = floorPlanTableMetaFontSp().sp)
}

private fun RestaurantTable.resolveStatusChipAnchor(): FloorPlanMarkerAnchor? {
    return statusChipAnchor ?: if (spotType == ServiceSpotType.BAR_SEAT) FloorPlanMarkerAnchor.RIGHT else null
}

private fun RestaurantTable.resolveSeatMarkerAnchor(): FloorPlanMarkerAnchor? {
    return seatMarkerAnchor ?: if (spotType == ServiceSpotType.BAR_SEAT) FloorPlanMarkerAnchor.LEFT else null
}

private enum class FloorPlanChipKind { STATUS, AMOUNT, META }

private data class FloorPlanChipSpec(
    val label: String,
    val kind: FloorPlanChipKind,
    val tint: Color? = null,
    val pulse: Boolean = false,
)

private data class FloorPlanChipMetrics(
    val widthPx: Float,
    val heightPx: Float,
)

private const val FLOOR_PLAN_STATUS_STABLE_WIDTH_LABEL = "Tarjoile"


private fun String.toFinnishFloorPlanStatusChipLabel(): String {
    return when (trim().lowercase()) {
        "free" -> "Vapaa"
        "occupied" -> "Varattu"
        "needs cleaning", "dirty" -> "Siivoa"
        "reserved" -> "Varaus"
        "open bill", "bill" -> "Lasku"
        "serve" -> "Tarjoile"
        "check" -> "Lasku"
        else -> this
    }
}

private fun String.shouldPulseFloorPlanStatusChip(): Boolean {
    return equals("SERVE", ignoreCase = true) || equals("CHECK", ignoreCase = true)
}

private fun RestaurantTable.floorPlanCustomerLabel(): String? {
    val count = guestCount.coerceAtLeast(0)
    return if (count > 0) "${count}C" else null
}

private fun RestaurantTable.floorPlanSeatsLabel(): String? {
    val count = seats.coerceAtLeast(0)
    return if (count > 1) "${count}S" else null
}

@Composable
private fun FloorPlanAdaptiveChipGrid(
    chips: List<FloorPlanChipSpec>,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    isRound: Boolean,
) {
    if (chips.isEmpty()) return

    val density = LocalDensity.current
    val horizontalGapDp = 4.dp
    val verticalGapDp = 4.dp
    val horizontalGapPx = with(density) { horizontalGapDp.toPx() }
    val verticalGapPx = with(density) { verticalGapDp.toPx() }
    val effectiveWidthPx = (bodyWidthPx - with(density) { 8.dp.toPx() }) * if (isRound) 0.76f else 1f
    val effectiveHeightPx = bodyHeightPx - with(density) { 18.dp.toPx() }
    val chipMetrics = chips.associateWith { chip ->
        estimateFloorPlanChipMetrics(
            chip = chip,
            density = density,
            bodyWidthPx = bodyWidthPx,
            bodyHeightPx = bodyHeightPx,
        )
    }
    val sequentialRows = arrangeFloorPlanChipRows(
        chips = chips,
        chipMetrics = chipMetrics,
        availableWidthPx = effectiveWidthPx.coerceAtLeast(1f),
        gapPx = horizontalGapPx,
    )
    val preferredRows = arrangeFloorPlanPreferredChipRows(
        chips = chips,
        chipMetrics = chipMetrics,
        availableWidthPx = effectiveWidthPx.coerceAtLeast(1f),
        gapPx = horizontalGapPx,
    )
    val preferredHeightPx = estimateFloorPlanChipRowsHeight(
        rows = preferredRows,
        chipMetrics = chipMetrics,
        verticalGapPx = verticalGapPx,
    )
    val rows = if (preferredRows.size > sequentialRows.size && preferredHeightPx <= effectiveHeightPx) {
        preferredRows
    } else {
        sequentialRows
    }
    val estimatedHeightPx = estimateFloorPlanChipRowsHeight(
        rows = rows,
        chipMetrics = chipMetrics,
        verticalGapPx = verticalGapPx,
    )
    val compact = estimatedHeightPx > effectiveHeightPx

    Column(
        modifier = Modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else verticalGapDp),
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else horizontalGapDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { chip ->
                    FloorPlanChip(
                        chip = chip,
                        bodyWidthPx = bodyWidthPx,
                        bodyHeightPx = bodyHeightPx,
                        compact = compact,
                    )
                }
            }
        }
    }
}

private fun arrangeFloorPlanChipRows(
    chips: List<FloorPlanChipSpec>,
    chipMetrics: Map<FloorPlanChipSpec, FloorPlanChipMetrics>,
    availableWidthPx: Float,
    gapPx: Float,
): List<List<FloorPlanChipSpec>> {
    if (chips.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<FloorPlanChipSpec>>()
    var currentRow = mutableListOf<FloorPlanChipSpec>()
    var currentWidth = 0f
    chips.forEach { chip ->
        val chipWidth = chipMetrics.getValue(chip).widthPx
        val requiredWidth = if (currentRow.isEmpty()) chipWidth else currentWidth + gapPx + chipWidth
        if (currentRow.isNotEmpty() && requiredWidth > availableWidthPx) {
            rows += currentRow
            currentRow = mutableListOf(chip)
            currentWidth = chipWidth
        } else {
            currentRow += chip
            currentWidth = requiredWidth
        }
    }
    if (currentRow.isNotEmpty()) rows += currentRow
    return rows
}

private fun arrangeFloorPlanPreferredChipRows(
    chips: List<FloorPlanChipSpec>,
    chipMetrics: Map<FloorPlanChipSpec, FloorPlanChipMetrics>,
    availableWidthPx: Float,
    gapPx: Float,
): List<List<FloorPlanChipSpec>> {
    if (chips.isEmpty()) return emptyList()
    val buckets = listOf(
        chips.filter { it.kind == FloorPlanChipKind.STATUS },
        chips.filter { it.kind == FloorPlanChipKind.AMOUNT },
        chips.filter { it.kind == FloorPlanChipKind.META },
    ).filter { it.isNotEmpty() }
    return buckets.flatMap { bucket ->
        arrangeFloorPlanChipRows(
            chips = bucket,
            chipMetrics = chipMetrics,
            availableWidthPx = availableWidthPx,
            gapPx = gapPx,
        )
    }
}

private fun estimateFloorPlanChipRowsHeight(
    rows: List<List<FloorPlanChipSpec>>,
    chipMetrics: Map<FloorPlanChipSpec, FloorPlanChipMetrics>,
    verticalGapPx: Float,
): Float {
    if (rows.isEmpty()) return 0f
    return rows.sumOf { row ->
        row.maxOf { chip -> chipMetrics.getValue(chip).heightPx }.toDouble()
    }.toFloat() + (rows.size - 1).coerceAtLeast(0) * verticalGapPx
}

private fun estimateFloorPlanChipMetrics(
    chip: FloorPlanChipSpec,
    density: Density,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
): FloorPlanChipMetrics {
    val fontSp = when (chip.kind) {
        FloorPlanChipKind.STATUS -> floorPlanTableStatusFontSp(bodyWidthPx, bodyHeightPx)
        FloorPlanChipKind.AMOUNT -> 12f
        FloorPlanChipKind.META -> floorPlanTableMetaFontSp()
    }
    val horizontalPaddingDp = when (chip.kind) {
        FloorPlanChipKind.STATUS -> 9f
        FloorPlanChipKind.AMOUNT -> 7f
        FloorPlanChipKind.META -> 7f
    }
    val verticalPaddingDp = when (chip.kind) {
        FloorPlanChipKind.STATUS -> 3f
        FloorPlanChipKind.AMOUNT -> 2f
        FloorPlanChipKind.META -> 3f
    }
    val fontPx = with(density) { fontSp.sp.toPx() }
    val measuredLabelLength = when (chip.kind) {
        FloorPlanChipKind.STATUS -> max(chip.label.length, FLOOR_PLAN_STATUS_STABLE_WIDTH_LABEL.length)
        else -> chip.label.length
    }
    val widthPx = measuredLabelLength * fontPx * 0.58f + with(density) { (horizontalPaddingDp.dp * 2).toPx() }
    val heightPx = fontPx * 1.25f + with(density) { (verticalPaddingDp.dp * 2).toPx() }
    return FloorPlanChipMetrics(widthPx = widthPx, heightPx = heightPx)
}

private fun estimateFloorPlanStatusChipMinWidth(
    density: Density,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
): androidx.compose.ui.unit.Dp {
    val fontPx = with(density) { floorPlanTableStatusFontSp(bodyWidthPx, bodyHeightPx).sp.toPx() }
    val horizontalPaddingPx = with(density) { (9.dp * 2).toPx() }
    val widthPx = FLOOR_PLAN_STATUS_STABLE_WIDTH_LABEL.length * fontPx * 0.58f + horizontalPaddingPx
    return widthPx.toDp(density)
}

private fun estimateFloorPlanChipStackMetrics(
    chips: List<FloorPlanChipSpec>,
    density: Density,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    verticalGapPx: Float,
): FloorPlanChipMetrics {
    if (chips.isEmpty()) return FloorPlanChipMetrics(widthPx = 0f, heightPx = 0f)

    val chipMetrics = chips.map { chip ->
        estimateFloorPlanChipMetrics(
            chip = chip,
            density = density,
            bodyWidthPx = bodyWidthPx,
            bodyHeightPx = bodyHeightPx,
        )
    }
    val widthPx = chipMetrics.maxOf { it.widthPx }
    val heightPx = chipMetrics.sumOf { it.heightPx.toDouble() }.toFloat() +
        (chips.size - 1).coerceAtLeast(0) * verticalGapPx
    return FloorPlanChipMetrics(widthPx = widthPx, heightPx = heightPx)
}

private fun floorPlanAnchoredChipOutsideFactor(
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    isBarSeat: Boolean,
): Float {
    val minDim = min(bodyWidthPx, bodyHeightPx)
    return when {
        isBarSeat && minDim <= 32f -> 0.38f
        isBarSeat && minDim <= 42f -> 0.52f
        isBarSeat && minDim <= 56f -> 0.72f
        !isBarSeat && minDim <= 52f -> 0.84f
        else -> 1f
    }
}

private fun floorPlanAnchoredChipStackTopLeft(
    anchor: FloorPlanMarkerAnchor,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    stackWidthPx: Float,
    stackHeightPx: Float,
    gapPx: Float,
    outsideFactor: Float,
): Offset {
    val safeOutsideFactor = outsideFactor.coerceIn(0f, 1f)
    val centerX = (bodyWidthPx - stackWidthPx) / 2f
    val centerY = (bodyHeightPx - stackHeightPx) / 2f
    val leftX = -(stackWidthPx * safeOutsideFactor) - gapPx
    val rightX = bodyWidthPx - (stackWidthPx * (1f - safeOutsideFactor)) + gapPx
    val topY = -(stackHeightPx * safeOutsideFactor) - gapPx
    val bottomY = bodyHeightPx - (stackHeightPx * (1f - safeOutsideFactor)) + gapPx
    return when (anchor) {
        FloorPlanMarkerAnchor.TOP_LEFT -> Offset(leftX, topY)
        FloorPlanMarkerAnchor.TOP -> Offset(centerX, topY)
        FloorPlanMarkerAnchor.TOP_RIGHT -> Offset(rightX, topY)
        FloorPlanMarkerAnchor.LEFT -> Offset(leftX, centerY)
        FloorPlanMarkerAnchor.CENTER -> Offset(centerX, centerY)
        FloorPlanMarkerAnchor.RIGHT -> Offset(rightX, centerY)
        FloorPlanMarkerAnchor.BOTTOM_LEFT -> Offset(leftX, bottomY)
        FloorPlanMarkerAnchor.BOTTOM -> Offset(centerX, bottomY)
        FloorPlanMarkerAnchor.BOTTOM_RIGHT -> Offset(rightX, bottomY)
    }
}

@Composable
private fun FloorPlanAnchoredChipStack(
    chips: List<FloorPlanChipSpec>,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    verticalGapDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return

    Column(
        modifier = modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(verticalGapDp),
    ) {
        chips.forEach { chip ->
            FloorPlanChip(
                chip = chip,
                bodyWidthPx = bodyWidthPx,
                bodyHeightPx = bodyHeightPx,
                compact = true,
            )
        }
    }
}

@Composable
private fun FloorPlanChip(
    chip: FloorPlanChipSpec,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    when (chip.kind) {
        FloorPlanChipKind.STATUS -> {
            val density = LocalDensity.current
            val tickTint = chip.tint ?: FloorPlanAvailableColor
            val pulseTransition = rememberInfiniteTransition()
            val pulseValue by pulseTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 780),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            val pulse = if (chip.pulse) pulseValue else 0f
            val minWidthDp = estimateFloorPlanStatusChipMinWidth(
                density = density,
                bodyWidthPx = bodyWidthPx,
                bodyHeightPx = bodyHeightPx,
            )
            Surface(
                modifier = modifier.widthIn(min = minWidthDp),
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFF101820).copy(alpha = 0.94f + pulse * 0.04f),
                border = BorderStroke(
                    width = if (chip.pulse) (1.1f + pulse * 0.9f).dp else 1.dp,
                    color = tickTint.copy(alpha = 0.58f + pulse * 0.36f),
                ),
            ) {
                Text(
                    text = chip.label,
                    modifier = Modifier.padding(horizontal = if (compact) 8.dp else 9.dp, vertical = if (compact) 2.dp else 3.dp),
                    style = floorPlanTableStatusLabelStyle(bodyWidthPx, bodyHeightPx),
                    color = tickTint.copy(alpha = 1f),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        FloorPlanChipKind.AMOUNT -> {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(999.dp),
                color = TableMapVisualTokens.ReservedColor.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, TableMapVisualTokens.ReservedColor.copy(alpha = 0.40f)),
            ) {
                Text(
                    text = chip.label,
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp),
                    style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    color = TableMapVisualTokens.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        FloorPlanChipKind.META -> {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(999.dp),
                color = TableMapVisualTokens.PanelAltColor.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, TableMapVisualTokens.BorderColor.copy(alpha = 0.60f)),
            ) {
                Text(
                    text = chip.label,
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 7.dp, vertical = if (compact) 2.dp else 3.dp),
                    style = floorPlanTableMetaLabelStyle(),
                    color = TableMapVisualTokens.TextSecondary.copy(alpha = 0.98f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun tableBodyFillColor(): Color {
    return Color(0xFF3B1D0D)
}

private fun DrawScope.drawFloorPlanTableSurface(
    isRound: Boolean,
    isBarSeat: Boolean,
    topLeft: Offset,
    targetSize: Size,
    borderColor: Color,
    borderWidthPx: Float,
    baseColor: Color?,
    backrestDirection: String?,
    backrestMode: String?,
) {
    if (isBarSeat) {
        drawFloorPlanBarStoolSurface(
            isRound = isRound,
            topLeft = topLeft,
            targetSize = targetSize,
            borderColor = borderColor,
            borderWidthPx = borderWidthPx,
            baseColor = baseColor,
            backrestDirection = backrestDirection,
            backrestMode = backrestMode,
        )
        return
    }

    val material = activeFloorPlanSurfaceTheme().table
    val resolvedTop = baseColor?.mixWith(Color.White, 0.05f) ?: material.top
    val resolvedMiddle = baseColor?.mixWith(material.middle, 0.55f) ?: material.middle
    val resolvedBottom = baseColor?.mixWith(material.bottom, 0.62f) ?: material.bottom
    val horizontalGrain = targetSize.width >= targetSize.height
    val minDim = min(targetSize.width, targetSize.height).coerceAtLeast(1f)
    val baseBrush = Brush.linearGradient(
        colors = listOf(resolvedTop.copy(alpha = 0.99f), resolvedMiddle.copy(alpha = 0.99f), resolvedBottom.copy(alpha = 0.99f)),
        start = topLeft,
        end = if (horizontalGrain) {
            Offset(topLeft.x + targetSize.width, topLeft.y + targetSize.height * 0.45f)
        } else {
            Offset(topLeft.x + targetSize.width * 0.42f, topLeft.y + targetSize.height)
        },
    )
    val sheenBrush = Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            material.highlight,
            Color.Transparent,
        ),
        start = if (horizontalGrain) topLeft else Offset(topLeft.x + targetSize.width, topLeft.y),
        end = if (horizontalGrain) {
            Offset(topLeft.x, topLeft.y + targetSize.height)
        } else {
            Offset(topLeft.x, topLeft.y)
        },
    )
    val edgeBrush = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            material.edgeDark.copy(alpha = 0.20f),
        ),
        startY = topLeft.y + targetSize.height * 0.60f,
        endY = topLeft.y + targetSize.height,
    )

    val topLightBrush = Brush.radialGradient(
        colors = listOf(
            material.highlight.copy(alpha = 0.55f),
            Color.Transparent,
        ),
        center = Offset(topLeft.x + targetSize.width * 0.40f, topLeft.y + targetSize.height * 0.30f),
        radius = max(targetSize.width, targetSize.height) * 0.65f,
    )
    val bottomDeepenBrush = Brush.radialGradient(
        colors = listOf(
            Color.Transparent,
            material.edgeDark.copy(alpha = 0.42f),
        ),
        center = Offset(topLeft.x + targetSize.width * 0.55f, topLeft.y + targetSize.height * 0.50f),
        radius = max(targetSize.width, targetSize.height) * 0.62f,
    )

    if (isRound) {
        drawOval(brush = baseBrush, topLeft = topLeft, size = targetSize)
        drawOval(brush = bottomDeepenBrush, topLeft = topLeft, size = targetSize)
        drawOval(brush = topLightBrush, topLeft = topLeft, size = targetSize)
        drawOval(brush = sheenBrush, topLeft = topLeft, size = targetSize)
        drawOval(brush = edgeBrush, topLeft = topLeft, size = targetSize)
    } else {
        drawRect(brush = baseBrush, topLeft = topLeft, size = targetSize)
        drawRect(brush = bottomDeepenBrush, topLeft = topLeft, size = targetSize)
        drawRect(brush = topLightBrush, topLeft = topLeft, size = targetSize)
        drawRect(brush = sheenBrush, topLeft = topLeft, size = targetSize)
        drawRect(brush = edgeBrush, topLeft = topLeft, size = targetSize)
    }

    drawFloorPlanWoodGrainLines(
        topLeft = topLeft,
        targetSize = targetSize,
        horizontal = horizontalGrain,
        subtle = isRound,
        grainLight = material.grainLight,
        grainDark = material.grainDark,
    )

    val insetStroke = max(0.65f, min(borderWidthPx, minDim * 0.035f))
    val innerInset = max(2f, minDim * 0.055f)
    val innerSize = Size(
        width = (targetSize.width - innerInset * 2f).coerceAtLeast(1f),
        height = (targetSize.height - innerInset * 2f).coerceAtLeast(1f),
    )
    val rimDarkColor = material.edgeDark.copy(alpha = 0.62f)
    val rimWidth = max(borderWidthPx, minDim * 0.045f)
    if (isRound) {
        drawOval(
            color = rimDarkColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = rimWidth),
        )
        drawOval(
            color = material.edgeWarm.copy(alpha = 0.32f),
            topLeft = Offset(topLeft.x + innerInset, topLeft.y + innerInset),
            size = innerSize,
            style = Stroke(width = insetStroke),
        )
        drawOval(
            color = borderColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = borderWidthPx),
        )
    } else {
        drawRect(
            color = rimDarkColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = rimWidth),
        )
        drawRect(
            color = material.edgeWarm.copy(alpha = 0.28f),
            topLeft = Offset(topLeft.x + innerInset, topLeft.y + innerInset),
            size = innerSize,
            style = Stroke(width = insetStroke),
        )
        drawRect(
            color = borderColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = borderWidthPx),
        )
    }
}


private enum class FloorPlanFurnitureDetailLevel {
    SILHOUETTE,
    STRUCTURE,
    FULL,
}

private fun floorPlanSafeFloat(value: Float, fallback: Float = 1f): Float {
    return if (value.isNaN() || value.isInfinite()) fallback else value
}

private fun floorPlanSafeSize(size: Size): Size {
    return Size(
        width = floorPlanSafeFloat(size.width).coerceAtLeast(1f),
        height = floorPlanSafeFloat(size.height).coerceAtLeast(1f),
    )
}

private fun floorPlanClamp(value: Float, minValue: Float, maxValue: Float): Float {
    val safeMin = floorPlanSafeFloat(minValue)
    val safeMax = max(safeMin, floorPlanSafeFloat(maxValue, safeMin))
    return floorPlanSafeFloat(value, safeMin).coerceIn(safeMin, safeMax)
}

private fun floorPlanFurnitureDetailLevel(minDim: Float): FloorPlanFurnitureDetailLevel {
    return when {
        minDim < 7f -> FloorPlanFurnitureDetailLevel.SILHOUETTE
        minDim < 18f -> FloorPlanFurnitureDetailLevel.STRUCTURE
        else -> FloorPlanFurnitureDetailLevel.FULL
    }
}

private fun DrawScope.drawFloorPlanBarStoolSurface(
    isRound: Boolean,
    topLeft: Offset,
    targetSize: Size,
    borderColor: Color,
    borderWidthPx: Float,
    baseColor: Color?,
    backrestDirection: String?,
    backrestMode: String?,
) {
    val safeTargetSize = floorPlanSafeSize(targetSize)
    val width = safeTargetSize.width
    val height = safeTargetSize.height
    val minDim = min(width, height)
    if (minDim < 3f) return

    val detailLevel = floorPlanFurnitureDetailLevel(minDim)
    val base = baseColor ?: Color(0xFF51483F)
    val warmHighlight = Color(0xFFFFE3B0)
    val deepShadow = Color(0xFF120804)
    val outline = base.mixWith(deepShadow, 0.58f).copy(alpha = 0.86f)
    val seatTop = base.mixWith(warmHighlight, 0.12f).copy(alpha = 0.98f)
    val seatMid = base.copy(alpha = 0.98f)
    val seatBottom = base.mixWith(deepShadow, 0.26f).copy(alpha = 0.99f)
    val center = Offset(topLeft.x + width / 2f, topLeft.y + height / 2f)

    val seatSide = minDim * if (isRound) 0.70f else 0.66f
    val seatWidth = if (isRound) seatSide else min(seatSide, width * 0.70f)
    val seatHeight = if (isRound) seatSide else min(seatSide, height * 0.70f)
    val seatTopLeft = Offset(center.x - seatWidth / 2f, center.y - seatHeight / 2f)
    val seatSize = Size(width = seatWidth.coerceAtLeast(1f), height = seatHeight.coerceAtLeast(1f))
    val stoolStroke = max(0.85f, min(borderWidthPx, minDim * 0.055f))

    drawOval(
        color = Color(0x55110805),
        topLeft = Offset(seatTopLeft.x + minDim * 0.05f, seatTopLeft.y + seatSize.height * 0.58f),
        size = Size(seatSize.width * 0.92f, seatSize.height * 0.34f),
    )

    if (detailLevel != FloorPlanFurnitureDetailLevel.SILHOUETTE) {
        val supportColor = deepShadow.copy(alpha = 0.42f)
        val supportStroke = floorPlanClamp(minDim * 0.026f, 0.75f, 2.1f)
        val stemStart = Offset(center.x, seatTopLeft.y + seatSize.height * 0.55f)
        val stemEnd = Offset(center.x, topLeft.y + height * 0.83f)
        drawLine(
            color = supportColor,
            start = stemStart,
            end = stemEnd,
            strokeWidth = supportStroke,
        )
        if (isRound) {
            val legStartY = seatTopLeft.y + seatSize.height * 0.60f
            val legEndY = topLeft.y + height * 0.88f
            listOf(0.30f, 0.50f, 0.70f).forEach { fraction ->
                val legEndX = topLeft.x + width * fraction
                drawLine(
                    color = supportColor.copy(alpha = 0.34f),
                    start = Offset(center.x, legStartY),
                    end = Offset(legEndX, legEndY),
                    strokeWidth = supportStroke,
                )
            }
            drawOval(
                color = deepShadow.copy(alpha = 0.22f),
                topLeft = Offset(center.x - seatSize.width * 0.40f, center.y - seatSize.height * 0.18f),
                size = Size(seatSize.width * 0.80f, seatSize.height * 0.80f),
                style = Stroke(width = max(0.75f, supportStroke * 0.8f)),
            )
        } else {
            val footY = topLeft.y + height * 0.82f
            drawLine(
                color = supportColor.copy(alpha = 0.42f),
                start = Offset(center.x - seatSize.width * 0.30f, footY),
                end = Offset(center.x + seatSize.width * 0.30f, footY),
                strokeWidth = supportStroke,
            )
            drawLine(
                color = supportColor.copy(alpha = 0.30f),
                start = Offset(center.x - seatSize.width * 0.20f, topLeft.y + height * 0.72f),
                end = Offset(center.x + seatSize.width * 0.20f, topLeft.y + height * 0.88f),
                strokeWidth = max(0.65f, supportStroke * 0.8f),
            )
        }
    }

    if (backrestMode == "backrest" && detailLevel != FloorPlanFurnitureDetailLevel.SILHOUETTE) {
        drawFloorPlanBarStoolBackrest(
            topLeft = topLeft,
            targetSize = safeTargetSize,
            seatTopLeft = seatTopLeft,
            seatSize = seatSize,
            direction = backrestDirection,
            color = base.mixWith(deepShadow, 0.22f),
            borderColor = outline,
            strokeWidth = max(0.75f, stoolStroke * 0.72f),
        )
    }

    val seatBrush = Brush.radialGradient(
        colors = listOf(seatTop, seatMid, seatBottom),
        center = Offset(seatTopLeft.x + seatSize.width * 0.38f, seatTopLeft.y + seatSize.height * 0.30f),
        radius = max(seatSize.width, seatSize.height) * 0.92f,
    )

    if (isRound) {
        drawOval(brush = seatBrush, topLeft = seatTopLeft, size = seatSize)
        drawOval(color = outline, topLeft = seatTopLeft, size = seatSize, style = Stroke(width = stoolStroke))
    } else {
        val radiusValue = floorPlanClamp(minDim * 0.055f, 1.5f, 5f)
        val seatRadius = CornerRadius(radiusValue, radiusValue)
        drawRoundRect(brush = seatBrush, topLeft = seatTopLeft, size = seatSize, cornerRadius = seatRadius)
        drawRoundRect(color = outline, topLeft = seatTopLeft, size = seatSize, cornerRadius = seatRadius, style = Stroke(width = stoolStroke))
    }

    if (detailLevel == FloorPlanFurnitureDetailLevel.FULL) {
        drawCircle(
            color = warmHighlight.copy(alpha = 0.18f),
            radius = minDim * 0.065f,
            center = Offset(seatTopLeft.x + seatSize.width * 0.36f, seatTopLeft.y + seatSize.height * 0.30f),
        )
        val inset = minDim * 0.13f
        val innerTopLeft = Offset(seatTopLeft.x + inset, seatTopLeft.y + inset)
        val innerSize = Size(
            width = (seatSize.width - inset * 2f).coerceAtLeast(1f),
            height = (seatSize.height - inset * 2f).coerceAtLeast(1f),
        )
        if (isRound) {
            drawOval(
                color = deepShadow.copy(alpha = 0.12f),
                topLeft = innerTopLeft,
                size = innerSize,
                style = Stroke(width = max(0.65f, stoolStroke * 0.55f)),
            )
        } else {
            val innerRadiusValue = floorPlanClamp(minDim * 0.035f, 1f, 3.5f)
            val innerRadius = CornerRadius(innerRadiusValue, innerRadiusValue)
            drawRoundRect(
                color = deepShadow.copy(alpha = 0.11f),
                topLeft = innerTopLeft,
                size = innerSize,
                cornerRadius = innerRadius,
                style = Stroke(width = max(0.65f, stoolStroke * 0.50f)),
            )
        }
    }
}


private fun DrawScope.drawFloorPlanBarStoolBackrest(
    topLeft: Offset,
    targetSize: Size,
    seatTopLeft: Offset,
    seatSize: Size,
    direction: String?,
    color: Color,
    borderColor: Color,
    strokeWidth: Float,
) {
    val minDim = min(targetSize.width, targetSize.height).coerceAtLeast(1f)
    val thickness = floorPlanClamp(minDim * 0.10f, 2f, 5.5f)
    val resolvedDirection = when (direction) {
        "top", "right", "bottom", "left" -> direction
        else -> "top"
    }
    val center = Offset(seatTopLeft.x + seatSize.width / 2f, seatTopLeft.y + seatSize.height / 2f)
    val rectTopLeft: Offset
    val rectSize: Size
    val supportAStart: Offset
    val supportAEnd: Offset
    val supportBStart: Offset
    val supportBEnd: Offset
    val supportInset = minDim * 0.18f

    when (resolvedDirection) {
        "bottom" -> {
            rectSize = Size(seatSize.width * 0.82f, thickness)
            rectTopLeft = Offset(center.x - rectSize.width / 2f, min(topLeft.y + targetSize.height - thickness, seatTopLeft.y + seatSize.height + minDim * 0.08f))
            supportAStart = Offset(center.x - supportInset, rectTopLeft.y)
            supportAEnd = Offset(center.x - supportInset * 0.62f, seatTopLeft.y + seatSize.height * 0.78f)
            supportBStart = Offset(center.x + supportInset, rectTopLeft.y)
            supportBEnd = Offset(center.x + supportInset * 0.62f, seatTopLeft.y + seatSize.height * 0.78f)
        }
        "left" -> {
            rectSize = Size(thickness, seatSize.height * 0.82f)
            rectTopLeft = Offset(max(topLeft.x, seatTopLeft.x - minDim * 0.12f - thickness), center.y - rectSize.height / 2f)
            supportAStart = Offset(rectTopLeft.x + rectSize.width, center.y - supportInset)
            supportAEnd = Offset(seatTopLeft.x + seatSize.width * 0.22f, center.y - supportInset * 0.62f)
            supportBStart = Offset(rectTopLeft.x + rectSize.width, center.y + supportInset)
            supportBEnd = Offset(seatTopLeft.x + seatSize.width * 0.22f, center.y + supportInset * 0.62f)
        }
        "right" -> {
            rectSize = Size(thickness, seatSize.height * 0.82f)
            rectTopLeft = Offset(min(topLeft.x + targetSize.width - thickness, seatTopLeft.x + seatSize.width + minDim * 0.12f), center.y - rectSize.height / 2f)
            supportAStart = Offset(rectTopLeft.x, center.y - supportInset)
            supportAEnd = Offset(seatTopLeft.x + seatSize.width * 0.78f, center.y - supportInset * 0.62f)
            supportBStart = Offset(rectTopLeft.x, center.y + supportInset)
            supportBEnd = Offset(seatTopLeft.x + seatSize.width * 0.78f, center.y + supportInset * 0.62f)
        }
        else -> {
            rectSize = Size(seatSize.width * 0.82f, thickness)
            rectTopLeft = Offset(center.x - rectSize.width / 2f, max(topLeft.y, seatTopLeft.y - minDim * 0.12f - thickness))
            supportAStart = Offset(center.x - supportInset, rectTopLeft.y + rectSize.height)
            supportAEnd = Offset(center.x - supportInset * 0.62f, seatTopLeft.y + seatSize.height * 0.22f)
            supportBStart = Offset(center.x + supportInset, rectTopLeft.y + rectSize.height)
            supportBEnd = Offset(center.x + supportInset * 0.62f, seatTopLeft.y + seatSize.height * 0.22f)
        }
    }

    drawLine(
        color = borderColor.copy(alpha = 0.38f),
        start = supportAStart,
        end = supportAEnd,
        strokeWidth = max(0.65f, strokeWidth * 0.68f),
    )
    drawLine(
        color = borderColor.copy(alpha = 0.38f),
        start = supportBStart,
        end = supportBEnd,
        strokeWidth = max(0.65f, strokeWidth * 0.68f),
    )

    val radius = floorPlanClamp(minDim * 0.045f, 1.5f, 4.5f)
    val backrestBrush = if (resolvedDirection == "left" || resolvedDirection == "right") {
        Brush.horizontalGradient(
            colors = listOf(color.mixWith(Color.White, 0.06f).copy(alpha = 0.94f), color.mixWith(Color.Black, 0.20f).copy(alpha = 0.96f)),
            startX = rectTopLeft.x,
            endX = rectTopLeft.x + rectSize.width,
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(color.mixWith(Color.White, 0.06f).copy(alpha = 0.94f), color.mixWith(Color.Black, 0.20f).copy(alpha = 0.96f)),
            startY = rectTopLeft.y,
            endY = rectTopLeft.y + rectSize.height,
        )
    }
    drawRoundRect(
        brush = backrestBrush,
        topLeft = rectTopLeft,
        size = rectSize,
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = borderColor.copy(alpha = 0.72f),
        topLeft = rectTopLeft,
        size = rectSize,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = strokeWidth),
    )
}


private fun DrawScope.drawFloorPlanWoodGrainLines(
    topLeft: Offset,
    targetSize: Size,
    horizontal: Boolean,
    subtle: Boolean,
    grainLight: Color,
    grainDark: Color,
) {
    val crossLength = if (horizontal) targetSize.height else targetSize.width
    val longLength = if (horizontal) targetSize.width else targetSize.height
    val count = max(3, min(7, (crossLength / 15f).roundToInt()))
    val inset = max(3f, min(targetSize.width, targetSize.height) * 0.08f)
    val alpha = if (subtle) 0.060f else 0.105f
    for (index in 0 until count) {
        val fraction = (index + 1f) / (count + 1f)
        val offset = crossLength * fraction
        val wobble = if (index % 2 == 0) crossLength * 0.018f else -crossLength * 0.012f
        if (horizontal) {
            val y = topLeft.y + offset
            drawLine(
                color = grainLight.copy(alpha = alpha),
                start = Offset(topLeft.x + inset, y),
                end = Offset(topLeft.x + longLength - inset, y + wobble),
                strokeWidth = max(0.55f, 0.45.dp.toPx()),
            )
            drawLine(
                color = grainDark.copy(alpha = alpha * 0.86f),
                start = Offset(topLeft.x + inset, y + 1.4f),
                end = Offset(topLeft.x + longLength - inset, y + wobble + 1.4f),
                strokeWidth = max(0.45f, 0.35.dp.toPx()),
            )
        } else {
            val x = topLeft.x + offset
            drawLine(
                color = grainLight.copy(alpha = alpha),
                start = Offset(x, topLeft.y + inset),
                end = Offset(x + wobble, topLeft.y + longLength - inset),
                strokeWidth = max(0.55f, 0.45.dp.toPx()),
            )
            drawLine(
                color = grainDark.copy(alpha = alpha * 0.86f),
                start = Offset(x + 1.4f, topLeft.y + inset),
                end = Offset(x + wobble + 1.4f, topLeft.y + longLength - inset),
                strokeWidth = max(0.45f, 0.35.dp.toPx()),
            )
        }
    }
}

private fun parseFloorPlanObjectColor(raw: String?): Color? {
    val value = raw?.trim() ?: return null
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(value)) return null

    val rgb = value.substring(1).toLongOrNull(16) ?: return null
    val red = (((rgb shr 16) and 0xFFL).toFloat()) / 255f
    val green = (((rgb shr 8) and 0xFFL).toFloat()) / 255f
    val blue = ((rgb and 0xFFL).toFloat()) / 255f
    return Color(red = red, green = green, blue = blue, alpha = 1f)
}

private fun Color.mixWith(target: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * t,
        green = green + (target.green - green) * t,
        blue = blue + (target.blue - blue) * t,
        alpha = alpha + (target.alpha - alpha) * t,
    )
}

private fun estimateFloorPlanSofaSeatCount(targetSize: Size): Int {
    val minDim = min(targetSize.width, targetSize.height).coerceAtLeast(1f)
    val maxDim = max(targetSize.width, targetSize.height)
    val aspectRatio = maxDim / minDim
    return when {
        aspectRatio < 1.25f -> 1
        aspectRatio < 1.85f -> 2
        aspectRatio < 2.55f -> 3
        else -> 4
    }
}

private fun DrawScope.drawFloorPlanArmchairSurface(
    topLeft: Offset,
    targetSize: Size,
    radius: CornerRadius,
    stroke: Float,
    baseColor: Color?,
    backrestDirection: String?,
    armrestMode: String?,
) {
    val safeSize = floorPlanSafeSize(targetSize)
    val width = safeSize.width.coerceAtLeast(1f)
    val height = safeSize.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    if (minDim < 3f) return

    val base = baseColor ?: Color(0xFF4A5360)
    val deepShadow = Color(0xFF11161B)
    val warmHighlight = Color(0xFFE7D3A5)
    val bodyTop = base.mixWith(Color.White, 0.07f)
    val bodyBottom = base.mixWith(deepShadow, 0.22f)
    val border = base.mixWith(deepShadow, 0.58f).copy(alpha = 0.82f)
    val highlight = warmHighlight.copy(alpha = 0.14f)
    val shadowOffset = floorPlanClamp(minDim * 0.055f, 1.2f, 5.0f)
    val outlineStroke = max(0.75f, min(stroke, minDim * 0.060f))
    val innerGap = floorPlanClamp(minDim * 0.055f, 1.0f, 4.5f)
    val backDepth = floorPlanClamp(minDim * 0.22f, 2.0f, minDim * 0.34f)
    val armDepth = floorPlanClamp(minDim * 0.18f, 2.0f, minDim * 0.30f)
    val resolvedBackrestDirection = when (backrestDirection) {
        "top", "right", "bottom", "left" -> backrestDirection
        else -> "top"
    }
    val normalizedArmrestMode = normalizeFloorPlanSofaArmrestMode(armrestMode)
    val showFirstArm = normalizedArmrestMode == "both" || normalizedArmrestMode == "left-only"
    val showSecondArm = normalizedArmrestMode == "both" || normalizedArmrestMode == "right-only"
    val bodySize = Size(width, height)

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.18f),
        topLeft = Offset(topLeft.x + shadowOffset, topLeft.y + shadowOffset),
        size = bodySize,
        cornerRadius = radius,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(bodyTop, bodyBottom),
            startY = topLeft.y,
            endY = topLeft.y + height,
        ),
        topLeft = topLeft,
        size = bodySize,
        cornerRadius = radius,
    )

    val backRect = when (resolvedBackrestDirection) {
        "right" -> Offset(topLeft.x + width - backDepth, topLeft.y + innerGap) to
            Size(backDepth, (height - innerGap * 2f).coerceAtLeast(1f))
        "bottom" -> Offset(topLeft.x + innerGap, topLeft.y + height - backDepth) to
            Size((width - innerGap * 2f).coerceAtLeast(1f), backDepth)
        "left" -> Offset(topLeft.x, topLeft.y + innerGap) to
            Size(backDepth, (height - innerGap * 2f).coerceAtLeast(1f))
        else -> Offset(topLeft.x + innerGap, topLeft.y) to
            Size((width - innerGap * 2f).coerceAtLeast(1f), backDepth)
    }
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(base.mixWith(warmHighlight, 0.10f), base.mixWith(deepShadow, 0.30f)),
            startY = backRect.first.y,
            endY = backRect.first.y + backRect.second.height,
        ),
        topLeft = backRect.first,
        size = backRect.second,
        cornerRadius = CornerRadius(
            x = floorPlanClamp(minDim * 0.08f, 2f, 8f),
            y = floorPlanClamp(minDim * 0.08f, 2f, 8f),
        ),
    )

    val seatLeft = when (resolvedBackrestDirection) {
        "left" -> topLeft.x + backDepth + innerGap
        else -> topLeft.x + innerGap + if (showFirstArm && resolvedBackrestDirection in setOf("top", "bottom")) armDepth else 0f
    }
    val seatTop = when (resolvedBackrestDirection) {
        "top" -> topLeft.y + backDepth + innerGap
        else -> topLeft.y + innerGap + if (showFirstArm && resolvedBackrestDirection in setOf("left", "right")) armDepth else 0f
    }
    val seatRight = when (resolvedBackrestDirection) {
        "right" -> topLeft.x + width - backDepth - innerGap
        else -> topLeft.x + width - innerGap - if (showSecondArm && resolvedBackrestDirection in setOf("top", "bottom")) armDepth else 0f
    }
    val seatBottom = when (resolvedBackrestDirection) {
        "bottom" -> topLeft.y + height - backDepth - innerGap
        else -> topLeft.y + height - innerGap - if (showSecondArm && resolvedBackrestDirection in setOf("left", "right")) armDepth else 0f
    }
    val seatTopLeft = Offset(seatLeft, seatTop)
    val seatSize = Size(
        width = (seatRight - seatLeft).coerceAtLeast(1f),
        height = (seatBottom - seatTop).coerceAtLeast(1f),
    )
    val seatRadius = CornerRadius(
        x = floorPlanClamp(min(seatSize.width, seatSize.height) * 0.12f, 2f, 7f),
        y = floorPlanClamp(min(seatSize.width, seatSize.height) * 0.12f, 2f, 7f),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(base.mixWith(warmHighlight, 0.14f), base.mixWith(deepShadow, 0.18f)),
            startY = seatTopLeft.y,
            endY = seatTopLeft.y + seatSize.height,
        ),
        topLeft = seatTopLeft,
        size = seatSize,
        cornerRadius = seatRadius,
    )
    drawRoundRect(
        color = highlight,
        topLeft = Offset(seatTopLeft.x + seatSize.width * 0.12f, seatTopLeft.y + seatSize.height * 0.12f),
        size = Size(seatSize.width * 0.76f, seatSize.height * 0.22f),
        cornerRadius = seatRadius,
    )
    drawRoundRect(
        color = border,
        topLeft = topLeft,
        size = bodySize,
        cornerRadius = radius,
        style = Stroke(width = outlineStroke),
    )
}


private fun DrawScope.drawFloorPlanChairSurface(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
    backOnTop: Boolean,
) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF46525C).copy(alpha = 0.92f),
                Color(0xFF26313A).copy(alpha = 0.95f),
            ),
        ),
        topLeft = topLeft,
        size = targetSize,
        cornerRadius = CornerRadius(999f, 999f),
    )
    drawRoundRect(
        color = Color(0xFF9AA6AF).copy(alpha = 0.54f),
        topLeft = topLeft,
        size = targetSize,
        cornerRadius = CornerRadius(999f, 999f),
        style = Stroke(width = stroke),
    )
    val backY = if (backOnTop) topLeft.y + targetSize.height * 0.18f else topLeft.y
    drawLine(
        color = Color(0xFFE9D2A0).copy(alpha = 0.34f),
        start = Offset(topLeft.x + targetSize.width * 0.22f, backY),
        end = Offset(topLeft.x + targetSize.width * 0.78f, backY),
        strokeWidth = max(stroke, 1.2.dp.toPx()),
    )
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

private fun floorPlanSeatMarkerOffsetPx(
    widthPx: Float,
    heightPx: Float,
): Float {
    val minDim = min(widthPx, heightPx)
    // Keep larger tables close to legacy spacing, but pull markers in on small tops.
    return if (minDim >= 64f) {
        max(8f, min(16f, (minDim * 0.28f).roundToInt().toFloat()))
    } else {
        max(5f, min(12f, (minDim * 0.20f).roundToInt().toFloat()))
    }
}

private fun floorPlanAnchoredSeatMarker(
    anchor: FloorPlanMarkerAnchor,
    widthPx: Float,
    heightPx: Float,
    seatOffsetPx: Float,
): FloorPlanTableSeatMarker {
    return when (anchor) {
        FloorPlanMarkerAnchor.TOP_LEFT -> FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = -seatOffsetPx)
        FloorPlanMarkerAnchor.TOP -> FloorPlanTableSeatMarker(leftPx = widthPx / 2f, topPx = -seatOffsetPx)
        FloorPlanMarkerAnchor.TOP_RIGHT -> FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = -seatOffsetPx)
        FloorPlanMarkerAnchor.LEFT -> FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = heightPx / 2f)
        FloorPlanMarkerAnchor.CENTER -> FloorPlanTableSeatMarker(leftPx = widthPx / 2f, topPx = heightPx / 2f)
        FloorPlanMarkerAnchor.RIGHT -> FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = heightPx / 2f)
        FloorPlanMarkerAnchor.BOTTOM_LEFT -> FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = heightPx + seatOffsetPx)
        FloorPlanMarkerAnchor.BOTTOM -> FloorPlanTableSeatMarker(leftPx = widthPx / 2f, topPx = heightPx + seatOffsetPx)
        FloorPlanMarkerAnchor.BOTTOM_RIGHT -> FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = heightPx + seatOffsetPx)
    }
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
    markerAnchor: FloorPlanMarkerAnchor? = null,
): List<FloorPlanTableSeatMarker> {
    val seatCount = table.seats.coerceAtLeast(0)
    if (seatCount == 0) return emptyList()

    val seatOffsetPx = floorPlanSeatMarkerOffsetPx(widthPx, heightPx)
    if (table.spotType == ServiceSpotType.BAR_SEAT) {
        val anchor = markerAnchor ?: FloorPlanMarkerAnchor.LEFT
        return listOf(
            floorPlanAnchoredSeatMarker(
                anchor = anchor,
                widthPx = widthPx,
                heightPx = heightPx,
                seatOffsetPx = seatOffsetPx,
            ),
        )
    }

    val layout = getDefaultChairLayout(table)
    val horizontalInsetPx = max(12f, (widthPx * 0.18f).roundToInt().toFloat())
    val centerX = widthPx / 2f
    val centerY = heightPx / 2f

    return when (layout) {
        "round_even" -> {
            val radiusX = widthPx / 2f + seatOffsetPx
            val radiusY = heightPx / 2f + seatOffsetPx
            List(seatCount) { index ->
                val angle = (Math.PI * 2.0 * index / seatCount) - (Math.PI / 2.0)
                FloorPlanTableSeatMarker(
                    leftPx = (centerX + kotlin.math.cos(angle).toFloat() * radiusX),
                    topPx = (centerY + kotlin.math.sin(angle).toFloat() * radiusY),
                )
            }
        }

        "square_even" -> {
            if (seatCount == 3) {
                listOf(
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                    FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY),
                    FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY),
                )
            } else if (seatCount <= 2) {
                listOf(
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
                ).take(seatCount)
            } else {
                getRectFourCenteredSeatMarkers(widthPx, heightPx, seatOffsetPx).take(seatCount)
            }
        }

        "rectangle_sides_only" -> {
            val topCount = (seatCount + 1) / 2
            val bottomCount = seatCount / 2
            spreadSeatPositions(
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

        "rectangle_wall_side_empty" -> {
            val outerLongSideCount = max(1, seatCount - 2)
            val sideSeatCount = max(0, seatCount - outerLongSideCount)
            val list = mutableListOf<FloorPlanTableSeatMarker>()
            spreadSeatPositions(
                count = outerLongSideCount,
                startPx = horizontalInsetPx,
                endPx = widthPx - horizontalInsetPx,
            ).forEach { leftPx ->
                list += FloorPlanTableSeatMarker(leftPx = leftPx, topPx = heightPx + seatOffsetPx)
            }
            if (sideSeatCount >= 1) {
                list += FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY)
            }
            if (sideSeatCount >= 2) {
                list += FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY)
            }
            list
        }

        else -> {
            if (seatCount == 4) {
                getRectFourCenteredSeatMarkers(widthPx, heightPx, seatOffsetPx)
            } else if (seatCount <= 2) {
                listOf(
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
                ).take(seatCount)
            } else if (seatCount == 3) {
                listOf(
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = -seatOffsetPx),
                    FloorPlanTableSeatMarker(leftPx = widthPx + seatOffsetPx, topPx = centerY),
                    FloorPlanTableSeatMarker(leftPx = centerX, topPx = heightPx + seatOffsetPx),
                )
            } else {
                val topCount = (seatCount - 1) / 2
                val bottomCount = (seatCount - 2) / 2
                listOf(FloorPlanTableSeatMarker(leftPx = -seatOffsetPx, topPx = centerY)) +
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
    floorMapWidthPx: Float? = null,
    floorMapHeightPx: Float? = null,
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
    val derivedRawBounds = buildRawFloorPlanBounds(
        tableRects = tablePlacementsSource.map { it.second },
        areaRects = areaPlacementsSource.map { it.second },
        objectRects = objectPlacementsSource.map { (floorObject, rawRect, _) ->
            rawFloorPlanLayoutBoundsForObject(floorObject, rawRect)
        },
    )
    val rawBounds = floorPlanCanvasBoundsOrNull(
        widthPx = floorMapWidthPx,
        heightPx = floorMapHeightPx,
    ) ?: derivedRawBounds
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

private fun rawFloorPlanLayoutBoundsForObject(
    floorObject: FloorMapObject,
    rawRect: FloorPlanRect,
): FloorPlanRect {
    return when {
        floorObject.type.equals("camera", ignoreCase = true) -> {
            rawRect.withTopLeftVisualSize(
                widthPx = FLOOR_PLAN_CAMERA_SYMBOL_PX,
                heightPx = FLOOR_PLAN_CAMERA_SYMBOL_PX,
            )
        }
        else -> rawRect
    }
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
