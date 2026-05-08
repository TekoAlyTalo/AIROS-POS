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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.airos.pos.core.model.FloorPlanChairStyle
import com.airos.pos.core.model.FloorPlanDeviceStyle
import com.airos.pos.core.model.FloorPlanMarkerAnchor
import com.airos.pos.core.model.FloorPlanPlantStyle
import com.airos.pos.core.model.FloorPlanSofaStyle
import com.airos.pos.core.model.RestaurantTable
import com.airos.pos.core.model.ServiceSpotType
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
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
private const val FLOOR_PLAN_DEFAULT_PLANK_WIDTH_MM = 120
private const val FLOOR_PLAN_DEFAULT_PLANK_LENGTH_MM = 4500
private const val FLOOR_PLAN_DEFAULT_PX_PER_METER = 20f
private const val FLOOR_PLAN_WEATHERED_PLANK_ASSET_ROW_COUNT = 6f
private const val FLOOR_PLAN_PREMIUM_STONE_ASSET_SIZE_MM = 2400
private const val FLOOR_PLAN_WORLD_UNIT_MM = 10f
private const val FLOOR_PLAN_ASSET_TILE_OVERLAP_PX = 1f
private const val FLOOR_PLAN_DEBUG_TAG = "FloorPlanDebug"
private const val FLOOR_PLAN_MIN_ZOOM = 0.20f
private const val FLOOR_PLAN_MAX_ZOOM = 8.0f
private val FloorPlanOpaqueWhiteMatteColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            -0.333f, -0.333f, -0.333f, 1f, 0f,
        ),
    ),
)
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
    val premiumStoneLightAsset = ImageBitmap.imageResource(id = R.drawable.floor_premium_stone_light_v1)
    val weatheredPlanksAsset = ImageBitmap.imageResource(id = R.drawable.floor_terrace_weathered_planks_120mm_v2)
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
                    premiumStoneLightAsset = premiumStoneLightAsset,
                    weatheredPlanksAsset = weatheredPlanksAsset,
                )
            }
        }
    }
}

private fun DrawScope.drawFloorPlanAreaShape(
    area: FloorMapArea,
    targetSize: Size,
    premiumStoneLightAsset: ImageBitmap,
    weatheredPlanksAsset: ImageBitmap,
) {
    val floor = activeFloorPlanSurfaceTheme().floor
    val stroke = max(1f, 1.dp.toPx())
    val floorAssetMaterial = area.floorAssetMaterial()
    val woodPlankMaterial = area.woodPlankMaterial()
    val stoneMaterial = area.stoneFloorMaterial()
    val fillTop = floor.baseMiddle.copy(alpha = 0.06f)
    val fillBottom = areaSurfaceColor(area).copy(alpha = 0.08f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.24f)
    val fillBrush = Brush.verticalGradient(
        colors = listOf(fillTop, fillBottom),
        startY = 0f,
        endY = targetSize.height,
    )

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

            if (floorAssetMaterial != null) {
                clipPath(path) {
                    drawFloorPlanAssetAreaSurface(
                        area = area,
                        targetSize = targetSize,
                        material = floorAssetMaterial,
                        premiumStoneLightAsset = premiumStoneLightAsset,
                        weatheredPlanksAsset = weatheredPlanksAsset,
                    )
                }
            } else if (woodPlankMaterial != null) {
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            woodPlankMaterial.baseTop,
                            woodPlankMaterial.baseBottom,
                        ),
                        startY = 0f,
                        endY = targetSize.height,
                    ),
                )
            } else if (stoneMaterial != null) {
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(stoneMaterial.baseTop, stoneMaterial.baseMiddle, stoneMaterial.baseBottom),
                        start = Offset.Zero,
                        end = Offset(targetSize.width * 0.8f, targetSize.height),
                    ),
                )
            } else {
                drawPath(path = path, brush = fillBrush)
            }
            drawPath(path = path, color = borderColor, style = Stroke(width = stroke))
        }

        "circle", "ellipse" -> {
            if (floorAssetMaterial != null) {
                val path = Path().apply {
                    addOval(Rect(Offset.Zero, targetSize))
                }
                clipPath(path) {
                    drawFloorPlanAssetAreaSurface(
                        area = area,
                        targetSize = targetSize,
                        material = floorAssetMaterial,
                        premiumStoneLightAsset = premiumStoneLightAsset,
                        weatheredPlanksAsset = weatheredPlanksAsset,
                    )
                }
            } else if (woodPlankMaterial != null) {
                val wood = woodPlankMaterial
                drawOval(
                    brush = Brush.verticalGradient(
                        colors = listOf(wood.baseTop, wood.baseMiddle, wood.baseBottom),
                        startY = 0f,
                        endY = targetSize.height,
                    ),
                    topLeft = Offset.Zero,
                    size = targetSize,
                )
            } else if (stoneMaterial != null) {
                drawOval(
                    brush = Brush.linearGradient(
                        colors = listOf(stoneMaterial.baseTop, stoneMaterial.baseMiddle, stoneMaterial.baseBottom),
                        start = Offset.Zero,
                        end = Offset(targetSize.width * 0.8f, targetSize.height),
                    ),
                    topLeft = Offset.Zero,
                    size = targetSize,
                )
            } else {
                drawOval(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = targetSize,
                )
            }
            drawOval(
                color = borderColor,
                topLeft = Offset.Zero,
                size = targetSize,
                style = Stroke(width = stroke),
            )
        }

        "roundedrectangle", "rounded-rectangle", "rounded_rect" -> {
            if (floorAssetMaterial != null) {
                val cornerRadius = CornerRadius(14f, 14f)
                val path = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, targetSize), cornerRadius))
                }
                clipPath(path) {
                    drawFloorPlanAssetAreaSurface(
                        area = area,
                        targetSize = targetSize,
                        material = floorAssetMaterial,
                        premiumStoneLightAsset = premiumStoneLightAsset,
                        weatheredPlanksAsset = weatheredPlanksAsset,
                    )
                }
            } else if (woodPlankMaterial != null) {
                drawFloorPlanWoodPlankAreaSurface(
                    area = area,
                    targetSize = targetSize,
                    material = woodPlankMaterial,
                    stroke = stroke,
                    verticalPlanks = area.hasVerticalPlanks(),
                )
            } else if (stoneMaterial != null) {
                drawFloorPlanStoneAreaSurface(
                    targetSize = targetSize,
                    material = stoneMaterial,
                    stroke = stroke,
                    cornerRadius = CornerRadius(14f, 14f),
                )
            } else {
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = targetSize,
                    cornerRadius = CornerRadius(14f, 14f),
                )
            }
            drawRoundRect(
                color = borderColor,
                topLeft = Offset.Zero,
                size = targetSize,
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = stroke),
            )
        }

        else -> {
            if (floorAssetMaterial != null) {
                clipRect(left = 0f, top = 0f, right = targetSize.width, bottom = targetSize.height) {
                    drawFloorPlanAssetAreaSurface(
                        area = area,
                        targetSize = targetSize,
                        material = floorAssetMaterial,
                        premiumStoneLightAsset = premiumStoneLightAsset,
                        weatheredPlanksAsset = weatheredPlanksAsset,
                    )
                }
            } else if (woodPlankMaterial != null) {
                drawFloorPlanWoodPlankAreaSurface(
                    area = area,
                    targetSize = targetSize,
                    material = woodPlankMaterial,
                    stroke = stroke,
                    verticalPlanks = area.hasVerticalPlanks(),
                )
            } else if (stoneMaterial != null) {
                drawFloorPlanStoneAreaSurface(
                    targetSize = targetSize,
                    material = stoneMaterial,
                    stroke = stroke,
                )
            } else {
                drawRect(
                    brush = fillBrush,
                    topLeft = Offset.Zero,
                    size = targetSize,
                )
            }
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

private fun FloorMapArea.woodPlankMaterial(): FloorPlanLightWoodPlankSurfaceTokens? {
    val value = surfaceMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
        ?: return null
    return when (value) {
        "LIGHT_WOOD_PLANKS" -> activeFloorPlanSurfaceTheme().lightWoodPlanks
        else -> null
    }
}

private fun FloorMapArea.stoneFloorMaterial(): FloorPlanFloorSurfaceTokens? {
    val value = surfaceMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
        ?: return null
    val theme = activeFloorPlanSurfaceTheme()
    return when (value) {
        "PREMIUM_STONE", "DARK_WOOD_PLANKS" -> theme.floor
        "GREY_STONE" -> theme.greyStoneFloor
        else -> null
    }
}

private enum class FloorPlanFloorAssetMaterial {
    PREMIUM_STONE_LIGHT,
    WEATHERED_120MM_PLANKS,
}

private fun FloorMapArea.floorAssetMaterial(): FloorPlanFloorAssetMaterial? {
    val value = surfaceMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
        ?: return null
    return when (value) {
        "PREMIUM_STONE", "PREMIUM_STONE_LIGHT" -> FloorPlanFloorAssetMaterial.PREMIUM_STONE_LIGHT
        "LIGHT_WOOD_PLANKS", "WEATHERED_120MM_PLANKS" -> FloorPlanFloorAssetMaterial.WEATHERED_120MM_PLANKS
        else -> null
    }
}

private fun FloorMapArea.hasVerticalPlanks(): Boolean {
    return plankDirection?.trim()?.lowercase() == "vertical"
}

private fun DrawScope.drawFloorPlanAssetAreaSurface(
    area: FloorMapArea,
    targetSize: Size,
    material: FloorPlanFloorAssetMaterial,
    premiumStoneLightAsset: ImageBitmap,
    weatheredPlanksAsset: ImageBitmap,
) {
    val image = when (material) {
        FloorPlanFloorAssetMaterial.PREMIUM_STONE_LIGHT -> premiumStoneLightAsset
        FloorPlanFloorAssetMaterial.WEATHERED_120MM_PLANKS -> weatheredPlanksAsset
    }

    if (material == FloorPlanFloorAssetMaterial.WEATHERED_120MM_PLANKS) {
        drawFloorPlanWeatheredPlankAssetAreaSurface(
            area = area,
            targetSize = targetSize,
            weatheredPlanksAsset = weatheredPlanksAsset,
        )
        drawRect(
            color = Color(0xFF7A6355).copy(alpha = 0.12f),
            topLeft = Offset.Zero,
            size = targetSize,
        )
        val weatheredTint = parseFloorPlanObjectColor(area.surfaceTint)
        if (weatheredTint != null) {
            drawRect(
                color = weatheredTint.copy(alpha = 0.14f),
                topLeft = Offset.Zero,
                size = targetSize,
            )
        }
        return
    }

    val pxPerWorldX = targetSize.width / area.widthPx.coerceAtLeast(1f)
    val pxPerWorldY = targetSize.height / area.heightPx.coerceAtLeast(1f)
    val pxPerMeter = area.pxPerMeter?.takeIf { it > 0f } ?: FLOOR_PLAN_DEFAULT_PX_PER_METER
    val tileWorldSize = when (material) {
        FloorPlanFloorAssetMaterial.WEATHERED_120MM_PLANKS -> {
            val plankWidthWorld = ((area.plankWidthMm ?: FLOOR_PLAN_DEFAULT_PLANK_WIDTH_MM)
                .coerceAtLeast(1)).toFloat() / 1000f * pxPerMeter
            val plankLengthWorld = ((area.plankLengthMm ?: FLOOR_PLAN_DEFAULT_PLANK_LENGTH_MM)
                .coerceAtLeast(1)).toFloat() / 1000f * pxPerMeter
            val plankBandWorld = plankWidthWorld * FLOOR_PLAN_WEATHERED_PLANK_ASSET_ROW_COUNT
            if (area.hasVerticalPlanks()) {
                FloorPlanAssetTileWorldSize(width = plankBandWorld, height = plankLengthWorld, rotateAssetQuarterTurn = true)
            } else {
                FloorPlanAssetTileWorldSize(width = plankLengthWorld, height = plankBandWorld, rotateAssetQuarterTurn = false)
            }
        }
        FloorPlanFloorAssetMaterial.PREMIUM_STONE_LIGHT -> {
            val stoneWorld = FLOOR_PLAN_PREMIUM_STONE_ASSET_SIZE_MM / 1000f * pxPerMeter
            FloorPlanAssetTileWorldSize(width = stoneWorld, height = stoneWorld, rotateAssetQuarterTurn = false)
        }
    }
    val tileWidthPx = (tileWorldSize.width * pxPerWorldX).coerceAtLeast(1f)
    val tileHeightPx = (tileWorldSize.height * pxPerWorldY).coerceAtLeast(1f)
    val startX = -floorPlanPositiveModulo(area.xPx, tileWorldSize.width) * pxPerWorldX
    val startY = -floorPlanPositiveModulo(area.yPx, tileWorldSize.height) * pxPerWorldY
    drawFloorPlanTiledImage(
        image = image,
        targetSize = targetSize,
        tileWidthPx = tileWidthPx,
        tileHeightPx = tileHeightPx,
        startX = startX,
        startY = startY,
        rotateAssetQuarterTurn = tileWorldSize.rotateAssetQuarterTurn,
    )

    if (material == FloorPlanFloorAssetMaterial.WEATHERED_120MM_PLANKS) {
        drawRect(
            color = Color(0xFF7A6355).copy(alpha = 0.12f),
            topLeft = Offset.Zero,
            size = targetSize,
        )
    }

    val tint = parseFloorPlanObjectColor(area.surfaceTint)
    if (tint != null) {
        drawRect(
            color = tint.copy(alpha = 0.14f),
            topLeft = Offset.Zero,
            size = targetSize,
        )
    }
}


private fun DrawScope.drawFloorPlanWeatheredPlankAssetAreaSurface(
    area: FloorMapArea,
    targetSize: Size,
    weatheredPlanksAsset: ImageBitmap,
) {
    val pxPerWorldX = targetSize.width / area.widthPx.coerceAtLeast(1f)
    val pxPerWorldY = targetSize.height / area.heightPx.coerceAtLeast(1f)
    val pxPerMeter = area.pxPerMeter?.takeIf { it > 0f } ?: FLOOR_PLAN_DEFAULT_PX_PER_METER
    val plankWidthWorld = ((area.plankWidthMm ?: FLOOR_PLAN_DEFAULT_PLANK_WIDTH_MM)
        .coerceAtLeast(1)).toFloat() / 1000f * pxPerMeter
    val plankLengthWorld = ((area.plankLengthMm ?: FLOOR_PLAN_DEFAULT_PLANK_LENGTH_MM)
        .coerceAtLeast(1)).toFloat() / 1000f * pxPerMeter
    if (area.hasVerticalPlanks()) {
        drawFloorPlanVerticalWeatheredPlankAssetRows(
            targetSize = targetSize,
            image = weatheredPlanksAsset,
            areaPrimaryWorld = area.yPx,
            areaCrossWorld = area.xPx,
            pxPerPrimaryWorld = pxPerWorldY,
            pxPerCrossWorld = pxPerWorldX,
            plankWidthWorld = plankWidthWorld,
            plankLengthWorld = plankLengthWorld,
        )
    } else {
        drawFloorPlanHorizontalWeatheredPlankAssetRows(
            targetSize = targetSize,
            image = weatheredPlanksAsset,
            areaPrimaryWorld = area.xPx,
            areaCrossWorld = area.yPx,
            pxPerPrimaryWorld = pxPerWorldX,
            pxPerCrossWorld = pxPerWorldY,
            plankWidthWorld = plankWidthWorld,
            plankLengthWorld = plankLengthWorld,
        )
    }
}

private fun DrawScope.drawFloorPlanHorizontalWeatheredPlankAssetRows(
    targetSize: Size,
    image: ImageBitmap,
    areaPrimaryWorld: Float,
    areaCrossWorld: Float,
    pxPerPrimaryWorld: Float,
    pxPerCrossWorld: Float,
    plankWidthWorld: Float,
    plankLengthWorld: Float,
) {
    val plankWidthPx = (plankWidthWorld * pxPerCrossWorld).coerceAtLeast(1f)
    val plankLengthPx = (plankLengthWorld * pxPerPrimaryWorld).coerceAtLeast(1f)
    val firstRowIndex = floor((areaCrossWorld / plankWidthWorld).toDouble()).toInt() - 1
    val lastRowIndex = floor(((areaCrossWorld + targetSize.height / pxPerCrossWorld) / plankWidthWorld).toDouble()).toInt() + 1
    for (rowIndex in firstRowIndex..lastRowIndex) {
        val rowTop = (rowIndex * plankWidthWorld - areaCrossWorld) * pxPerCrossWorld
        val rowBottom = rowTop + plankWidthPx
        if (rowBottom <= 0f || rowTop >= targetSize.height) continue
        val staggerWorld = floorPlanPlankStaggerFraction(rowIndex) * plankLengthWorld
        val firstSegmentIndex = floor(((areaPrimaryWorld + staggerWorld) / plankLengthWorld).toDouble()).toInt() - 1
        val lastSegmentIndex = floor(((areaPrimaryWorld + targetSize.width / pxPerPrimaryWorld + staggerWorld) / plankLengthWorld).toDouble()).toInt() + 1
        for (segmentIndex in firstSegmentIndex..lastSegmentIndex) {
            val segmentLeft = (segmentIndex * plankLengthWorld - staggerWorld - areaPrimaryWorld) * pxPerPrimaryWorld
            val segmentRight = segmentLeft + plankLengthPx
            if (segmentRight <= 0f || segmentLeft >= targetSize.width) continue
            val clipLeft = segmentLeft.coerceAtLeast(0f)
            val clipRight = segmentRight.coerceAtMost(targetSize.width)
            val clipTop = rowTop.coerceAtLeast(0f)
            val clipBottom = rowBottom.coerceAtMost(targetSize.height)
            if (clipRight <= clipLeft || clipBottom <= clipTop) continue
            val sourceRowIndex = floorPlanPositiveModuloInt(rowIndex * 5 + segmentIndex * 3, FLOOR_PLAN_WEATHERED_PLANK_ASSET_ROW_COUNT.roundToInt())
            clipRect(left = clipLeft, top = clipTop, right = clipRight, bottom = clipBottom) {
                drawFloorPlanWeatheredPlankAssetStrip(
                    image = image,
                    sourceRowIndex = sourceRowIndex,
                    dstOffset = Offset(segmentLeft, rowTop),
                    dstSize = Size(plankLengthPx + FLOOR_PLAN_ASSET_TILE_OVERLAP_PX, plankWidthPx + FLOOR_PLAN_ASSET_TILE_OVERLAP_PX),
                    rotateAssetQuarterTurn = false,
                )
            }
            drawWeatheredPlankButtSeam(
                start = Offset(segmentLeft, clipTop),
                end = Offset(segmentLeft, clipBottom),
            )
        }
    }
}

private fun DrawScope.drawFloorPlanVerticalWeatheredPlankAssetRows(
    targetSize: Size,
    image: ImageBitmap,
    areaPrimaryWorld: Float,
    areaCrossWorld: Float,
    pxPerPrimaryWorld: Float,
    pxPerCrossWorld: Float,
    plankWidthWorld: Float,
    plankLengthWorld: Float,
) {
    val plankWidthPx = (plankWidthWorld * pxPerCrossWorld).coerceAtLeast(1f)
    val plankLengthPx = (plankLengthWorld * pxPerPrimaryWorld).coerceAtLeast(1f)
    val firstColumnIndex = floor((areaCrossWorld / plankWidthWorld).toDouble()).toInt() - 1
    val lastColumnIndex = floor(((areaCrossWorld + targetSize.width / pxPerCrossWorld) / plankWidthWorld).toDouble()).toInt() + 1
    for (columnIndex in firstColumnIndex..lastColumnIndex) {
        val columnLeft = (columnIndex * plankWidthWorld - areaCrossWorld) * pxPerCrossWorld
        val columnRight = columnLeft + plankWidthPx
        if (columnRight <= 0f || columnLeft >= targetSize.width) continue
        val staggerWorld = floorPlanPlankStaggerFraction(columnIndex) * plankLengthWorld
        val firstSegmentIndex = floor(((areaPrimaryWorld + staggerWorld) / plankLengthWorld).toDouble()).toInt() - 1
        val lastSegmentIndex = floor(((areaPrimaryWorld + targetSize.height / pxPerPrimaryWorld + staggerWorld) / plankLengthWorld).toDouble()).toInt() + 1
        for (segmentIndex in firstSegmentIndex..lastSegmentIndex) {
            val segmentTop = (segmentIndex * plankLengthWorld - staggerWorld - areaPrimaryWorld) * pxPerPrimaryWorld
            val segmentBottom = segmentTop + plankLengthPx
            if (segmentBottom <= 0f || segmentTop >= targetSize.height) continue
            val clipLeft = columnLeft.coerceAtLeast(0f)
            val clipRight = columnRight.coerceAtMost(targetSize.width)
            val clipTop = segmentTop.coerceAtLeast(0f)
            val clipBottom = segmentBottom.coerceAtMost(targetSize.height)
            if (clipRight <= clipLeft || clipBottom <= clipTop) continue
            val sourceRowIndex = floorPlanPositiveModuloInt(columnIndex * 5 + segmentIndex * 3, FLOOR_PLAN_WEATHERED_PLANK_ASSET_ROW_COUNT.roundToInt())
            clipRect(left = clipLeft, top = clipTop, right = clipRight, bottom = clipBottom) {
                drawFloorPlanWeatheredPlankAssetStrip(
                    image = image,
                    sourceRowIndex = sourceRowIndex,
                    dstOffset = Offset(columnLeft, segmentTop),
                    dstSize = Size(plankWidthPx + FLOOR_PLAN_ASSET_TILE_OVERLAP_PX, plankLengthPx + FLOOR_PLAN_ASSET_TILE_OVERLAP_PX),
                    rotateAssetQuarterTurn = true,
                )
            }
            drawWeatheredPlankButtSeam(
                start = Offset(clipLeft, segmentTop),
                end = Offset(clipRight, segmentTop),
            )
        }
    }
}

private fun DrawScope.drawFloorPlanWeatheredPlankAssetStrip(
    image: ImageBitmap,
    sourceRowIndex: Int,
    dstOffset: Offset,
    dstSize: Size,
    rotateAssetQuarterTurn: Boolean,
) {
    val sourceRowCount = FLOOR_PLAN_WEATHERED_PLANK_ASSET_ROW_COUNT.roundToInt().coerceAtLeast(1)
    val safeSourceRowIndex = floorPlanPositiveModuloInt(sourceRowIndex, sourceRowCount)
    val sourceTop = (safeSourceRowIndex * image.height) / sourceRowCount
    val sourceBottom = ((safeSourceRowIndex + 1) * image.height) / sourceRowCount
    val sourceHeight = (sourceBottom - sourceTop).coerceAtLeast(1)
    val drawWidth = dstSize.width.roundToInt().coerceAtLeast(1)
    val drawHeight = dstSize.height.roundToInt().coerceAtLeast(1)
    if (rotateAssetQuarterTurn) {
        withTransform({
            translate(left = dstOffset.x, top = dstOffset.y)
            rotate(
                degrees = 90f,
                pivot = Offset(drawWidth / 2f, drawHeight / 2f),
            )
        }) {
            drawImage(
                image = image,
                srcOffset = IntOffset(0, sourceTop),
                srcSize = IntSize(image.width, sourceHeight),
                dstOffset = IntOffset(((drawWidth - drawHeight) / 2f).roundToInt(), ((drawHeight - drawWidth) / 2f).roundToInt()),
                dstSize = IntSize(drawHeight, drawWidth),
                filterQuality = FilterQuality.High,
            )
        }
    } else {
        drawImage(
            image = image,
            srcOffset = IntOffset(0, sourceTop),
            srcSize = IntSize(image.width, sourceHeight),
            dstOffset = IntOffset(dstOffset.x.roundToInt(), dstOffset.y.roundToInt()),
            dstSize = IntSize(drawWidth, drawHeight),
            filterQuality = FilterQuality.High,
        )
    }
}

private fun DrawScope.drawWeatheredPlankButtSeam(
    start: Offset,
    end: Offset,
) {
    if (start.x == end.x && (start.x <= 0f || start.x >= size.width)) return
    if (start.y == end.y && (start.y <= 0f || start.y >= size.height)) return
    val seamWidth = max(0.32f, 0.46.dp.toPx())
    drawLine(
        color = Color(0xFF1F1A17).copy(alpha = 0.26f),
        start = start,
        end = end,
        strokeWidth = seamWidth,
    )
    val highlightOffset = if (start.x == end.x) Offset(seamWidth, 0f) else Offset(0f, seamWidth)
    drawLine(
        color = Color.White.copy(alpha = 0.035f),
        start = start + highlightOffset,
        end = end + highlightOffset,
        strokeWidth = max(0.22f, seamWidth * 0.55f),
    )
}

private fun floorPlanPositiveModuloInt(value: Int, period: Int): Int {
    val safePeriod = period.takeIf { it > 0 } ?: return 0
    val result = value % safePeriod
    return if (result < 0) result + safePeriod else result
}

private data class FloorPlanAssetTileWorldSize(
    val width: Float,
    val height: Float,
    val rotateAssetQuarterTurn: Boolean,
)

private fun floorPlanPositiveModulo(value: Float, period: Float): Float {
    val safePeriod = period.takeIf { it > 0f } ?: return 0f
    val result = value % safePeriod
    return if (result < 0f) result + safePeriod else result
}

private fun DrawScope.drawFloorPlanTiledImage(
    image: ImageBitmap,
    targetSize: Size,
    tileWidthPx: Float,
    tileHeightPx: Float,
    startX: Float,
    startY: Float,
    rotateAssetQuarterTurn: Boolean,
) {
    val drawWidth = tileWidthPx.roundToInt().coerceAtLeast(1) + 1
    val drawHeight = tileHeightPx.roundToInt().coerceAtLeast(1) + 1
    val stepX = (tileWidthPx - FLOOR_PLAN_ASSET_TILE_OVERLAP_PX).coerceAtLeast(1f)
    val stepY = (tileHeightPx - FLOOR_PLAN_ASSET_TILE_OVERLAP_PX).coerceAtLeast(1f)
    var y = startY
    while (y < targetSize.height) {
        var x = startX
        while (x < targetSize.width) {
            if (rotateAssetQuarterTurn) {
                withTransform({
                    translate(left = x, top = y)
                    rotate(
                        degrees = 90f,
                        pivot = Offset(drawWidth / 2f, drawHeight / 2f),
                    )
                }) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(0, 0),
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(((drawWidth - drawHeight) / 2f).roundToInt(), ((drawHeight - drawWidth) / 2f).roundToInt()),
                        dstSize = IntSize(drawHeight, drawWidth),
                        filterQuality = FilterQuality.High,
                    )
                }
            } else {
                withTransform({ translate(left = x, top = y) }) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(0, 0),
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(drawWidth, drawHeight),
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            x += stepX
        }
        y += stepY
    }
}

private fun DrawScope.drawFloorPlanStoneAreaSurface(
    targetSize: Size,
    material: FloorPlanFloorSurfaceTokens,
    stroke: Float,
    cornerRadius: CornerRadius? = null,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val baseBrush = Brush.linearGradient(
        colors = listOf(material.baseTop, material.baseMiddle, material.baseBottom),
        start = Offset.Zero,
        end = Offset(width * 0.72f, height),
    )
    if (cornerRadius != null) {
        drawRoundRect(brush = baseBrush, topLeft = Offset.Zero, size = targetSize, cornerRadius = cornerRadius)
    } else {
        drawRect(brush = baseBrush, topLeft = Offset.Zero, size = targetSize)
    }

    fun drawStoneOverlay(brush: Brush) {
        if (cornerRadius != null) {
            drawRoundRect(brush = brush, topLeft = Offset.Zero, size = targetSize, cornerRadius = cornerRadius)
        } else {
            drawRect(brush = brush, topLeft = Offset.Zero, size = targetSize)
        }
    }

    val maxDim = max(width, height)
    drawStoneOverlay(
        Brush.radialGradient(
            colors = listOf(material.plankHighlight.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(width * 0.18f, height * 0.24f),
            radius = maxDim * 0.52f,
        ),
    )
    drawStoneOverlay(
        Brush.radialGradient(
            colors = listOf(material.plankShadow.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(width * 0.78f, height * 0.62f),
            radius = maxDim * 0.58f,
        ),
    )
    drawStoneOverlay(
        Brush.radialGradient(
            colors = listOf(material.grain.copy(alpha = material.grain.alpha * 0.78f), Color.Transparent),
            center = Offset(width * 0.48f, height * 0.86f),
            radius = maxDim * 0.46f,
        ),
    )

    val veinStroke = max(0.28f, min(stroke * 0.46f, 0.58.dp.toPx()))
    fun drawStoneVein(
        start: Offset,
        control: Offset,
        end: Offset,
        color: Color,
        strokeWidth: Float,
    ) {
        val path = Path().apply {
            moveTo(start.x, start.y)
            quadraticBezierTo(control.x, control.y, end.x, end.y)
        }
        drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
    }

    drawStoneVein(
        start = Offset(-width * 0.08f, height * 0.25f),
        control = Offset(width * 0.34f, height * 0.10f),
        end = Offset(width * 1.05f, height * 0.42f),
        color = material.grain.copy(alpha = material.grain.alpha * 0.72f),
        strokeWidth = veinStroke,
    )
    drawStoneVein(
        start = Offset(width * 0.08f, height * 0.78f),
        control = Offset(width * 0.42f, height * 0.64f),
        end = Offset(width * 0.92f, height * 0.92f),
        color = material.plankSeam.copy(alpha = material.plankSeam.alpha * 0.42f),
        strokeWidth = max(0.24f, veinStroke * 0.82f),
    )
    drawStoneVein(
        start = Offset(width * 0.62f, -height * 0.05f),
        control = Offset(width * 0.70f, height * 0.34f),
        end = Offset(width * 0.42f, height * 1.04f),
        color = material.plankHighlight.copy(alpha = material.plankHighlight.alpha * 0.36f),
        strokeWidth = max(0.22f, veinStroke * 0.65f),
    )

    if (cornerRadius != null) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, material.vignette),
                center = Offset(width * 0.50f, height * 0.50f),
                radius = max(width, height) * 0.78f,
            ),
            topLeft = Offset.Zero,
            size = targetSize,
            cornerRadius = cornerRadius,
        )
    } else {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, material.vignette),
                center = Offset(width * 0.50f, height * 0.50f),
                radius = max(width, height) * 0.78f,
            ),
            topLeft = Offset.Zero,
            size = targetSize,
        )
    }
}

private fun floorPlanMaterialTone(index: Int): Float {
    val bucket = ((index * 37 + 11) % 17).toFloat() / 16f
    return bucket * 2f - 1f
}

private fun floorPlanPlankSegmentScale(index: Int): Float {
    val bucket = ((index * 29 + 7) % 9).toFloat() / 8f
    return 0.84f + bucket * 0.28f
}

private fun floorPlanPlankStaggerFraction(index: Int): Float {
    return ((index * 43 + 17) % 100).toFloat() / 100f
}

private fun DrawScope.drawFloorPlanWoodPlankAreaSurface(
    area: FloorMapArea,
    targetSize: Size,
    material: FloorPlanLightWoodPlankSurfaceTokens,
    stroke: Float,
    verticalPlanks: Boolean = false,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val worldWidth = area.widthPx.coerceAtLeast(1f)
    val worldHeight = area.heightPx.coerceAtLeast(1f)
    val pxPerWorldX = width / worldWidth
    val pxPerWorldY = height / worldHeight
    val plankWorldWidth = ((area.plankWidthMm ?: FLOOR_PLAN_DEFAULT_PLANK_WIDTH_MM)
        .coerceAtLeast(1)).toFloat() / FLOOR_PLAN_WORLD_UNIT_MM
    val plankWorldLength = ((area.plankLengthMm ?: FLOOR_PLAN_DEFAULT_PLANK_LENGTH_MM)
        .coerceAtLeast(1)).toFloat() / FLOOR_PLAN_WORLD_UNIT_MM
    val plankHeight = floorPlanClamp(plankWorldWidth * pxPerWorldY, 5f, 34f)
    val plankLength = floorPlanClamp(plankWorldLength * pxPerWorldX, 48f, width.coerceAtLeast(48f))
    val seamStroke = max(0.65f, min(stroke, 1.45.dp.toPx()))
    val highlightStroke = max(0.35f, min(stroke * 0.60f, 0.80.dp.toPx()))

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(material.baseTop, material.baseMiddle, material.baseBottom),
            startY = 0f,
            endY = height,
        ),
        topLeft = Offset.Zero,
        size = targetSize,
    )

    if (verticalPlanks) {
        val plankColWidth = floorPlanClamp(plankWorldWidth * pxPerWorldX, 5f, 34f)
        val plankColLength = floorPlanClamp(plankWorldLength * pxPerWorldY, 48f, height.coerceAtLeast(48f))
        var colLeft = 0f
        var colIndex = 0
        while (colLeft < width) {
            val colRight = (colLeft + plankColWidth).coerceAtMost(width)
            val tone = floorPlanMaterialTone(colIndex)
            val plankTint = if (tone >= 0f) {
                material.grainLight.copy(alpha = 0.030f + tone * 0.040f)
            } else {
                material.grainDark.copy(alpha = 0.018f + -tone * 0.028f)
            }
            drawRect(
                color = plankTint,
                topLeft = Offset(colLeft, 0f),
                size = Size(colRight - colLeft, height),
            )
            if (colLeft > 0f) {
                drawLine(
                    color = material.seam,
                    start = Offset(colLeft, 0f),
                    end = Offset(colLeft, height),
                    strokeWidth = seamStroke,
                )
                drawLine(
                    color = material.seamHighlight,
                    start = Offset(colLeft + seamStroke, 0f),
                    end = Offset(colLeft + seamStroke, height),
                    strokeWidth = highlightStroke,
                )
            }
            val segmentLength = plankColLength * floorPlanPlankSegmentScale(colIndex)
            val stagger = -segmentLength * floorPlanPlankStaggerFraction(colIndex)
            var y = stagger
            while (y < height) {
                if (y > 0f) {
                    drawLine(
                        color = material.seam.copy(alpha = material.seam.alpha * 0.78f),
                        start = Offset(colLeft, y),
                        end = Offset(colRight, y),
                        strokeWidth = seamStroke,
                    )
                    drawLine(
                        color = material.seamHighlight.copy(alpha = material.seamHighlight.alpha * 0.76f),
                        start = Offset(colLeft, y + seamStroke),
                        end = Offset(colRight, y + seamStroke),
                        strokeWidth = highlightStroke,
                    )
                }
                y += segmentLength
            }
            val grainX = colLeft + (colRight - colLeft) * 0.55f
            drawLine(
                color = material.grainDark.copy(alpha = material.grainDark.alpha * (0.70f + abs(tone) * 0.30f)),
                start = Offset(grainX, 12f),
                end = Offset(grainX + tone * 1.8f, height - 12f),
                strokeWidth = max(0.35f, 0.42.dp.toPx()),
            )
            drawLine(
                color = material.grainLight.copy(alpha = 0.075f + max(tone, 0f) * 0.030f),
                start = Offset(colLeft + (colRight - colLeft) * 0.28f, 8f),
                end = Offset(colLeft + (colRight - colLeft) * (0.34f + tone * 0.035f), height - 10f),
                strokeWidth = max(0.25f, 0.30.dp.toPx()),
            )
            colLeft += plankColWidth
            colIndex += 1
        }
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, material.edge.copy(alpha = material.edge.alpha * 0.34f)),
                center = Offset(width * 0.5f, height * 0.5f),
                radius = max(width, height) * 0.72f,
            ),
            topLeft = Offset.Zero,
            size = targetSize,
        )
        return
    }

    var rowTop = 0f
    var rowIndex = 0
    while (rowTop < height) {
        val rowBottom = (rowTop + plankHeight).coerceAtMost(height)
        val tone = floorPlanMaterialTone(rowIndex)
        val plankTint = if (tone >= 0f) {
            material.grainLight.copy(alpha = 0.030f + tone * 0.040f)
        } else {
            material.grainDark.copy(alpha = 0.018f + -tone * 0.028f)
        }
        drawRect(
            color = plankTint,
            topLeft = Offset(0f, rowTop),
            size = Size(width, rowBottom - rowTop),
        )

        if (rowTop > 0f) {
            drawLine(
                color = material.seam,
                start = Offset(0f, rowTop),
                end = Offset(width, rowTop),
                strokeWidth = seamStroke,
            )
            drawLine(
                color = material.seamHighlight,
                start = Offset(0f, rowTop + seamStroke),
                end = Offset(width, rowTop + seamStroke),
                strokeWidth = highlightStroke,
            )
        }

        val segmentLength = plankLength * floorPlanPlankSegmentScale(rowIndex)
        val stagger = -segmentLength * floorPlanPlankStaggerFraction(rowIndex)
        var x = stagger
        while (x < width) {
            if (x > 0f) {
                drawLine(
                    color = material.seam.copy(alpha = material.seam.alpha * 0.78f),
                    start = Offset(x, rowTop),
                    end = Offset(x, rowBottom),
                    strokeWidth = seamStroke,
                )
                drawLine(
                    color = material.seamHighlight.copy(alpha = material.seamHighlight.alpha * 0.76f),
                    start = Offset(x + seamStroke, rowTop),
                    end = Offset(x + seamStroke, rowBottom),
                    strokeWidth = highlightStroke,
                )
            }
            x += segmentLength
        }

        val grainY = rowTop + (rowBottom - rowTop) * 0.55f
        drawLine(
            color = material.grainDark.copy(alpha = material.grainDark.alpha * (0.70f + abs(tone) * 0.30f)),
            start = Offset(12f, grainY),
            end = Offset(width - 12f, grainY + tone * 1.8f),
            strokeWidth = max(0.35f, 0.42.dp.toPx()),
        )
        drawLine(
            color = material.grainLight.copy(alpha = 0.075f + max(tone, 0f) * 0.030f),
            start = Offset(8f, rowTop + (rowBottom - rowTop) * 0.30f),
            end = Offset(width - 10f, rowTop + (rowBottom - rowTop) * (0.36f + tone * 0.035f)),
            strokeWidth = max(0.25f, 0.30.dp.toPx()),
        )

        rowTop += plankHeight
        rowIndex += 1
    }

    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, material.edge.copy(alpha = material.edge.alpha * 0.30f)),
            center = Offset(width * 0.5f, height * 0.5f),
            radius = max(width, height) * 0.74f,
        ),
        topLeft = Offset.Zero,
        size = targetSize,
    )

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
        "wall" -> true
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
                barDeskMaterial = floorObject.barDeskMaterial,
                barDeskGrainRotationDeg = floorObject.barDeskGrainRotationDeg,
            )
        }
    }
}

private fun resolveFloorPlanBarCounterMaterial(barDeskMaterial: String?): FloorPlanBarCounterSurfaceTokens {
    val value = normalizedFloorPlanBarDeskMaterial(barDeskMaterial)
    val theme = activeFloorPlanSurfaceTheme()
    return when (value) {
        "DARK_STONE" -> theme.darkStoneBarCounter
        else -> theme.barCounter
    }
}

private fun normalizedFloorPlanBarDeskMaterial(barDeskMaterial: String?): String? {
    return barDeskMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
}

private fun DrawScope.drawFloorPlanBarCounterSurface(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
    barDeskMaterial: String? = null,
    barDeskGrainRotationDeg: Float? = null,
) {
    val material = resolveFloorPlanBarCounterMaterial(barDeskMaterial)
    val isStone = normalizedFloorPlanBarDeskMaterial(barDeskMaterial) == "DARK_STONE"
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

    if (isStone) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(material.grainLight.copy(alpha = 0.09f), Color.Transparent),
                center = if (horizontalGrain) {
                    Offset(topLeft.x + topBandSize.width * 0.22f, topLeft.y + topBandSize.height * 0.30f)
                } else {
                    Offset(topLeft.x + topBandSize.width * 0.30f, topLeft.y + topBandSize.height * 0.22f)
                },
                radius = max(topBandSize.width, topBandSize.height) * 0.62f,
            ),
            topLeft = topLeft,
            size = topBandSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(material.grainDark.copy(alpha = 0.20f), Color.Transparent),
                center = if (horizontalGrain) {
                    Offset(topLeft.x + topBandSize.width * 0.86f, topLeft.y + topBandSize.height * 0.70f)
                } else {
                    Offset(topLeft.x + topBandSize.width * 0.70f, topLeft.y + topBandSize.height * 0.86f)
                },
                radius = max(topBandSize.width, topBandSize.height) * 0.58f,
            ),
            topLeft = topLeft,
            size = topBandSize,
            cornerRadius = radius,
        )
        drawFloorPlanCounterStoneVeins(
            topLeft = topLeft,
            targetSize = topBandSize,
            horizontal = horizontalGrain,
            veinLight = material.grainLight,
            veinDark = material.grainDark,
        )
        drawFloorPlanCounterStoneVeins(
            topLeft = frontBandTopLeft,
            targetSize = frontBandSize,
            horizontal = horizontalGrain,
            veinLight = material.grainLight.copy(alpha = material.grainLight.alpha * 0.55f),
            veinDark = material.grainDark.copy(alpha = 0.42f),
        )
    } else {
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
    }

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
}

private fun DrawScope.drawFloorPlanCounterStoneVeins(
    topLeft: Offset,
    targetSize: Size,
    horizontal: Boolean,
    veinLight: Color,
    veinDark: Color,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val longSide = if (horizontal) width else height
    val shortSide = if (horizontal) height else width
    val veinCount = max(3, (longSide / 86f).toInt())
    for (index in 0..veinCount) {
        val progress = index.toFloat() / veinCount.toFloat()
        val offsetFraction = 0.18f + (((index * 31 + 9) % 59).toFloat() / 100f)
        val offset = offsetFraction * shortSide
        val wobble = (((index * 17 + 5) % 21).toFloat() - 10f) * 0.42f
        val color = if (index % 3 == 0) {
            veinLight.copy(alpha = veinLight.alpha * 0.58f)
        } else {
            veinDark.copy(alpha = 0.26f)
        }
        val strokeWidth = max(0.28f, 0.42.dp.toPx())
        val path = Path()
        if (horizontal) {
            val startX = topLeft.x + width * progress - 18f
            path.moveTo(startX, topLeft.y + offset)
            path.quadraticBezierTo(
                startX + width * 0.17f,
                topLeft.y + offset + wobble,
                startX + width * 0.42f,
                topLeft.y + offset - wobble * 0.62f,
            )
        } else {
            val startY = topLeft.y + height * progress - 18f
            path.moveTo(topLeft.x + offset, startY)
            path.quadraticBezierTo(
                topLeft.x + offset + wobble,
                startY + height * 0.17f,
                topLeft.x + offset - wobble * 0.62f,
                startY + height * 0.42f,
            )
        }
        drawPath(path = path, color = veinDark.copy(alpha = 0.16f), style = Stroke(width = strokeWidth * 1.8f))
        drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
    }
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
        "armchair" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                FloorPlanPremiumArmchairAssetSurface(
                    backrestDirection = floorObject.backrestDirection,
                    objectColor = parseFloorPlanObjectColor(floorObject.color),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        "chair" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                if (floorObject.chairStyle == FloorPlanChairStyle.TERRACE_POLY_RATTAN) {
                    FloorPlanPolyRattanDarkChairAssetSurface(
                        backrestDirection = floorObject.backrestDirection,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    FloorPlanLabeledObjectSurface(
                        label = floorObject.label,
                        widthDp = widthDp,
                        heightDp = heightDp,
                        screenWidthPx = objectWidthPx,
                        modifier = Modifier,
                        objectType = objectType,
                        objectColorHex = floorObject.color,
                        objectBackrestDirection = floorObject.backrestDirection,
                        objectArmrestMode = floorObject.armrestMode,
                        showLabel = false,
                    )
                }
            }
        }
        "sofa", "couch" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                FloorPlanSofaSurface(
                    sofaStyle = floorObject.sofaStyle,
                    backrestDirection = floorObject.backrestDirection,
                    armrestMode = floorObject.armrestMode,
                    seatCount = floorObject.capacity,
                    objectColorHex = floorObject.color,
                    cushionColorHex = floorObject.cushionColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        "plant" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                FloorPlanPlantAssetSurface(
                    plantStyle = floorObject.plantStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        "pos-terminal" -> {
            Box(modifier = baseModifier.requiredSize(widthDp, heightDp)) {
                FloorPlanDeviceAssetSurface(
                    deviceStyle = floorObject.deviceStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        "bar-counter" -> {
            FloorPlanBarCounterObjectSurface(
                widthDp = widthDp,
                heightDp = heightDp,
                modifier = baseModifier,
                barDeskMaterial = floorObject.barDeskMaterial,
                barDeskGrainRotationDeg = floorObject.barDeskGrainRotationDeg,
                barDeskSegmentType = floorObject.barDeskSegmentType,
            )
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
                objectBarDeskMaterial = floorObject.barDeskMaterial,
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
    objectColorHex: String?,
    cushionColorHex: String?,
    modifier: Modifier = Modifier,
) {
    val resolvedSofaStyle = sofaStyle ?: FloorPlanSofaStyle.PREMIUM_LEATHER
    val normalizedArmrestMode = normalizeFloorPlanSofaArmrestMode(armrestMode)
    val objectColor = parseFloorPlanObjectColor(objectColorHex)
    val cushionColor = parseFloorPlanObjectColor(cushionColorHex)
    when (resolvedSofaStyle) {
        FloorPlanSofaStyle.PREMIUM_LEATHER -> {
            FloorPlanLeatherSofaAssetSurface(
                backrestDirection = backrestDirection,
                armrestMode = normalizedArmrestMode,
                seatCount = seatCount,
                objectColor = objectColor,
                cushionColor = cushionColor,
                modifier = modifier,
            )
        }
        FloorPlanSofaStyle.TERRACE_POLY_RATTAN -> {
            FloorPlanTerracePolyRattanSofaSurface(
                backrestDirection = backrestDirection,
                armrestMode = normalizedArmrestMode,
                seatCount = seatCount,
                objectColor = objectColor,
                cushionColor = cushionColor,
                modifier = modifier,
            )
        }
        FloorPlanSofaStyle.BOOTH_STRAIGHT_2SEAT_NO_ARMS -> {
            FloorPlanBoothSofaAssetSurface(
                drawableId = R.drawable.sofa_booth_straight_2seat_no_arms_topdown_v1,
                backrestDirection = backrestDirection,
                modifier = modifier,
            )
        }
        FloorPlanSofaStyle.BOOTH_CURVED_NO_ARMS -> {
            FloorPlanBoothSofaAssetSurface(
                drawableId = R.drawable.sofa_booth_curved_no_arms_topdown_v1,
                backrestDirection = backrestDirection,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FloorPlanLeatherSofaAssetSurface(
    backrestDirection: String?,
    armrestMode: String,
    seatCount: Int?,
    objectColor: Color?,
    cushionColor: Color?,
    modifier: Modifier = Modifier,
) {
    val premiumAsset = premiumLeatherSofaAssetSpec(
        seatCount = seatCount,
        armrestMode = armrestMode,
    )
    val sofaAsset = ImageBitmap.imageResource(id = premiumAsset.drawableId)
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
            fun drawPremiumAsset(
                colorFilter: ColorFilter? = null,
                alpha: Float = 1f,
            ) {
                drawFloorPlanSeatingAssetImage(
                    image = sofaAsset,
                    assetSpec = premiumAsset,
                    dstOffset = drawOffset,
                    dstSize = drawSize,
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }

            drawPremiumAsset()

            if (objectColor != null && !premiumAsset.hasOpaqueWhiteMatte) {
                drawPremiumAsset(
                    colorFilter = ColorFilter.tint(objectColor, BlendMode.SrcIn),
                    alpha = 0.30f,
                )
            }

            if (cushionColor != null && !premiumAsset.hasOpaqueWhiteMatte) {
                val left = drawOffset.x + drawWidth * 0.18f
                val top = drawOffset.y + drawHeight * 0.40f
                val right = drawOffset.x + drawWidth * 0.82f
                val bottom = drawOffset.y + drawHeight * 0.84f
                clipRect(left = left, top = top, right = right, bottom = bottom) {
                    drawPremiumAsset(
                        colorFilter = ColorFilter.tint(cushionColor, BlendMode.SrcIn),
                        alpha = 0.46f,
                    )
                }
            }
        }
    }
}

private data class FloorPlanSeatingAssetSpec(
    val drawableId: Int,
    val sourceOffset: IntOffset = IntOffset(0, 0),
    val sourceSize: IntSize? = null,
    val hasOpaqueWhiteMatte: Boolean = false,
)

private fun premiumLeatherSofaAssetSpec(
    seatCount: Int?,
    armrestMode: String,
): FloorPlanSeatingAssetSpec {
    val normalizedSeatCount = (seatCount ?: 2).coerceAtLeast(1)
    return if (normalizedSeatCount <= 2) {
        when (armrestMode) {
            "left-only" -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_2seat_topdown_left_v1,
                sourceOffset = IntOffset(201, 376),
                sourceSize = IntSize(824, 516),
                hasOpaqueWhiteMatte = true,
            )
            "right-only" -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_2seat_topdown_right_v1,
                sourceOffset = IntOffset(0, 0),
                sourceSize = IntSize(1095, 876),
                hasOpaqueWhiteMatte = true,
            )
            "none" -> FloorPlanSeatingAssetSpec(R.drawable.sofa2_premium_topdown_asset_v2)
            else -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_2seat_topdown_both_v1,
                sourceOffset = IntOffset(170, 337),
                sourceSize = IntSize(926, 512),
                hasOpaqueWhiteMatte = true,
            )
        }
    } else {
        when (armrestMode) {
            "left-only" -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_3seat_topdown_left_v1,
                sourceOffset = IntOffset(71, 366),
                sourceSize = IntSize(1106, 503),
                hasOpaqueWhiteMatte = true,
            )
            "right-only" -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_3seat_topdown_right_v1,
                sourceOffset = IntOffset(101, 383),
                sourceSize = IntSize(1063, 484),
                hasOpaqueWhiteMatte = true,
            )
            "none" -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_3seat_topdown_none_v1,
                sourceOffset = IntOffset(149, 372),
                sourceSize = IntSize(974, 486),
                hasOpaqueWhiteMatte = true,
            )
            else -> FloorPlanSeatingAssetSpec(
                drawableId = R.drawable.sofa_premium_3seat_topdown_both_v1,
                sourceOffset = IntOffset(127, 425),
                sourceSize = IntSize(994, 416),
                hasOpaqueWhiteMatte = true,
            )
        }
    }
}

@Composable
private fun FloorPlanPremiumArmchairAssetSurface(
    backrestDirection: String?,
    objectColor: Color?,
    modifier: Modifier = Modifier,
) {
    val armchairAssetSpec = FloorPlanSeatingAssetSpec(
        drawableId = R.drawable.armchair_premium_leather_topdown_asset_v1,
        sourceOffset = IntOffset(225, 222),
        sourceSize = IntSize(801, 811),
        hasOpaqueWhiteMatte = true,
    )
    val armchairAsset = ImageBitmap.imageResource(id = armchairAssetSpec.drawableId)
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
            fun drawArmchairAsset(
                colorFilter: ColorFilter? = null,
                alpha: Float = 1f,
            ) {
                drawFloorPlanSeatingAssetImage(
                    image = armchairAsset,
                    assetSpec = armchairAssetSpec,
                    dstOffset = drawOffset,
                    dstSize = drawSize,
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }

            drawArmchairAsset()
            if (objectColor != null && !armchairAssetSpec.hasOpaqueWhiteMatte) {
                drawArmchairAsset(
                    colorFilter = ColorFilter.tint(objectColor, BlendMode.SrcIn),
                    alpha = 0.24f,
                )
            }
        }
    }
}

@Composable
private fun FloorPlanPolyRattanDarkChairAssetSurface(
    backrestDirection: String?,
    modifier: Modifier = Modifier,
) {
    val chairAsset = ImageBitmap.imageResource(id = R.drawable.chair_poly_rattan_dark_topdown_v1)
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
                image = chairAsset,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(chairAsset.width, chairAsset.height),
                dstOffset = drawOffset,
                dstSize = drawSize,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

@Composable
private fun FloorPlanBoothSofaAssetSurface(
    drawableId: Int,
    backrestDirection: String?,
    modifier: Modifier = Modifier,
) {
    val boothAssetSpec = boothSofaAssetSpec(drawableId)
    val boothAsset = ImageBitmap.imageResource(id = boothAssetSpec.drawableId)
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
            drawFloorPlanSeatingAssetImage(
                image = boothAsset,
                assetSpec = boothAssetSpec,
                dstOffset = drawOffset,
                dstSize = drawSize,
            )
        }
    }
}

private fun boothSofaAssetSpec(drawableId: Int): FloorPlanSeatingAssetSpec {
    return when (drawableId) {
        R.drawable.sofa_booth_straight_2seat_no_arms_topdown_v1 -> FloorPlanSeatingAssetSpec(
            drawableId = drawableId,
            sourceOffset = IntOffset(203, 191),
            sourceSize = IntSize(1126, 632),
            hasOpaqueWhiteMatte = true,
        )
        R.drawable.sofa_booth_curved_no_arms_topdown_v1 -> FloorPlanSeatingAssetSpec(
            drawableId = drawableId,
            sourceOffset = IntOffset(25, 293),
            sourceSize = IntSize(1203, 603),
            hasOpaqueWhiteMatte = true,
        )
        else -> FloorPlanSeatingAssetSpec(drawableId = drawableId)
    }
}

private fun DrawScope.drawFloorPlanSeatingAssetImage(
    image: ImageBitmap,
    assetSpec: FloorPlanSeatingAssetSpec,
    dstOffset: IntOffset,
    dstSize: IntSize,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    drawImage(
        image = image,
        srcOffset = assetSpec.sourceOffset,
        srcSize = assetSpec.sourceSize ?: IntSize(image.width, image.height),
        dstOffset = dstOffset,
        dstSize = dstSize,
        alpha = alpha,
        colorFilter = colorFilter ?: if (assetSpec.hasOpaqueWhiteMatte) {
            FloorPlanOpaqueWhiteMatteColorFilter
        } else {
            null
        },
        filterQuality = FilterQuality.Medium,
    )
}

private data class FloorPlanObjectAssetSpec(
    val drawableId: Int,
    val sourceOffset: IntOffset = IntOffset(0, 0),
    val sourceSize: IntSize? = null,
)

@Composable
private fun FloorPlanPlantAssetSurface(
    plantStyle: FloorPlanPlantStyle?,
    modifier: Modifier = Modifier,
) {
    val assetSpec = when (plantStyle) {
        FloorPlanPlantStyle.TERRACE_FLOWERING_SHRUB -> FloorPlanObjectAssetSpec(
            drawableId = R.drawable.plant_terrace_flowering_round_topdown_v1,
            sourceOffset = IntOffset(82, 108),
            sourceSize = IntSize(1088, 1024),
        )
        else -> FloorPlanObjectAssetSpec(
            drawableId = R.drawable.plant_terrace_tuja_topdown_v1,
            sourceOffset = IntOffset(170, 148),
            sourceSize = IntSize(930, 930),
        )
    }
    val asset = ImageBitmap.imageResource(id = assetSpec.drawableId)
    Canvas(modifier = modifier) {
        drawFloorPlanObjectAssetImage(asset = asset, assetSpec = assetSpec)
    }
}

@Composable
private fun FloorPlanDeviceAssetSurface(
    deviceStyle: FloorPlanDeviceStyle?,
    modifier: Modifier = Modifier,
) {
    val assetSpec = when (deviceStyle) {
        FloorPlanDeviceStyle.SUNMI_D3_MINI,
        null -> FloorPlanObjectAssetSpec(
            drawableId = R.drawable.sunmi_d3_mini_transparent,
        )
    }
    val asset = ImageBitmap.imageResource(id = assetSpec.drawableId)
    Canvas(modifier = modifier) {
        drawFloorPlanObjectAssetImage(asset = asset, assetSpec = assetSpec)
    }
}

private fun DrawScope.drawFloorPlanObjectAssetImage(
    asset: ImageBitmap,
    assetSpec: FloorPlanObjectAssetSpec,
) {
    drawImage(
        image = asset,
        srcOffset = assetSpec.sourceOffset,
        srcSize = assetSpec.sourceSize ?: IntSize(asset.width, asset.height),
        dstOffset = IntOffset(0, 0),
        dstSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.Medium,
    )
}

@Composable
private fun FloorPlanTerracePolyRattanSofaSurface(
    backrestDirection: String?,
    armrestMode: String,
    seatCount: Int?,
    objectColor: Color?,
    cushionColor: Color?,
    modifier: Modifier = Modifier,
) {
    val rattanAsset = when (armrestMode) {
        "none" -> FloorPlanRattanSofaAssetSpec(
            drawableId = R.drawable.sofa2_poly_rattan_topdown_none_v1,
            sourceOffset = IntOffset(169, 136),
            sourceSize = IntSize(1435, 589),
        )
        "left-only" -> FloorPlanRattanSofaAssetSpec(
            drawableId = R.drawable.sofa2_poly_rattan_topdown_right_v1,
            sourceOffset = IntOffset(186, 186),
            sourceSize = IntSize(1442, 517),
        )
        "right-only" -> FloorPlanRattanSofaAssetSpec(
            drawableId = R.drawable.sofa2_poly_rattan_topdown_left_v1,
            sourceOffset = IntOffset(153, 129),
            sourceSize = IntSize(1452, 602),
        )
        else -> FloorPlanRattanSofaAssetSpec(
            drawableId = R.drawable.sofa2_poly_rattan_topdown_asset_v1,
            sourceOffset = IntOffset(154, 170),
            sourceSize = IntSize(1466, 543),
        )
    }
    val sofaAsset = ImageBitmap.imageResource(id = rattanAsset.drawableId)
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
                srcOffset = rattanAsset.sourceOffset,
                srcSize = rattanAsset.sourceSize,
                dstOffset = drawOffset,
                dstSize = drawSize,
                filterQuality = FilterQuality.Medium,
                blendMode = BlendMode.Darken,
            )
        }
    }
}

private data class FloorPlanRattanSofaAssetSpec(
    val drawableId: Int,
    val sourceOffset: IntOffset,
    val sourceSize: IntSize,
)

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
    objectColor: Color?,
    cushionColor: Color?,
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
            objectColor = objectColor,
            cushionColor = cushionColor,
        )
    }
}

private fun DrawScope.drawFloorPlanTerracePolyRattanSofaOriented(
    topLeft: Offset,
    targetSize: Size,
    stroke: Float,
    seatCount: Int,
    armrestMode: String,
    objectColor: Color?,
    cushionColor: Color?,
) {
    val width = targetSize.width.coerceAtLeast(1f)
    val height = targetSize.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    val outlineStroke = max(0.8f, min(stroke, minDim * 0.052f))

    val frameBase = objectColor ?: Color(0xFF3F4447)
    val cushionBase = cushionColor ?: Color(0xFFB8A17A)
    val frameTop = frameBase.mixWith(Color.White, 0.11f)
    val frameBottom = frameBase.mixWith(Color.Black, 0.48f)
    val frameBorder = frameBase.mixWith(Color.Black, 0.72f).copy(alpha = 0.82f)
    val weaveLight = frameBase.mixWith(Color.White, 0.34f).copy(alpha = 0.34f)
    val weaveDark = frameBase.mixWith(Color.Black, 0.62f).copy(alpha = 0.38f)
    val cushionTop = cushionBase.mixWith(Color.White, 0.16f)
    val cushionBottom = cushionBase.mixWith(Color.Black, 0.22f)
    val cushionBorder = cushionBase.mixWith(Color.Black, 0.58f).copy(alpha = 0.54f)
    val cushionHighlight = cushionBase.mixWith(Color.White, 0.42f).copy(alpha = 0.22f)

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

    offset = first
    while (offset <= last) {
        drawLine(
            color = dark.copy(alpha = dark.alpha * 0.62f),
            start = Offset(offset, topLeft.y + height),
            end = Offset(offset + height, topLeft.y),
            strokeWidth = max(0.35f, stroke * 0.26f),
        )
        drawLine(
            color = light.copy(alpha = light.alpha * 0.46f),
            start = Offset(offset + weaveStep * 0.50f, topLeft.y + height),
            end = Offset(offset + weaveStep * 0.50f + height, topLeft.y),
            strokeWidth = max(0.30f, stroke * 0.22f),
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
private fun FloorPlanBarCounterObjectSurface(
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    barDeskMaterial: String?,
    barDeskGrainRotationDeg: Float?,
    barDeskSegmentType: String? = null,
) {
    val normalizedMaterial = normalizedFloorPlanBarDeskMaterial(barDeskMaterial)
    val useDarkWoodAsset = shouldUseFloorPlanDarkWoodBarCounterAsset(normalizedMaterial)
    val materialAsset = ImageBitmap.imageResource(
        id = if (useDarkWoodAsset) {
            resolveFloorPlanDarkWoodBarCounterSegmentDrawableId(widthDp.value, heightDp.value, barDeskSegmentType)
        } else {
            resolveFloorPlanBarCounterMaterialDrawableId(normalizedMaterial)
        },
    )
    Box(modifier = modifier.requiredSize(widthDp, heightDp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = max(1f, 1.dp.toPx())
            if (useDarkWoodAsset) {
                drawFloorPlanBarCounterSegmentAsset(asset = materialAsset)
            } else {
                drawFloorPlanAssetBarCounterSurface(
                    asset = materialAsset,
                    material = normalizedMaterial,
                    grainRotationDeg = barDeskGrainRotationDeg,
                    stroke = stroke,
                )
            }
        }
    }
}

private fun shouldUseFloorPlanDarkWoodBarCounterAsset(material: String?): Boolean {
    return material == null || material == "DARK_WOOD"
}

private fun resolveFloorPlanDarkWoodBarCounterSegmentDrawableId(
    widthDp: Float,
    heightDp: Float,
    segmentType: String? = null,
): Int {
    when (segmentType?.trim()?.lowercase()) {
        "miter_45"   -> return R.drawable.bar_counter_miter_45_dark_wood_topdown_v1
        "straight"   -> return R.drawable.bar_counter_straight_dark_wood_topdown_v1
        "vertical"   -> return R.drawable.bar_counter_vertical_end_dark_wood_topdown_v1
        "square_end" -> return R.drawable.bar_counter_square_end_dark_wood_topdown_v1
    }

    // fallback: ratio-based for objects without explicit segmentType
    val width = widthDp.coerceAtLeast(1f)
    val height = heightDp.coerceAtLeast(1f)
    val ratio = width / height

    return when {
        ratio >= 1.25f -> R.drawable.bar_counter_straight_dark_wood_topdown_v1
        ratio <= 0.80f -> R.drawable.bar_counter_vertical_end_dark_wood_topdown_v1
        else           -> R.drawable.bar_counter_square_end_dark_wood_topdown_v1
    }
}

private fun resolveFloorPlanBarCounterMaterialDrawableId(material: String?): Int {
    return when (material) {
        "LIGHT_WOOD" -> R.drawable.bar_counter_surface_light_wood_topdown_v1
        "LIGHT_STONE" -> R.drawable.bar_counter_surface_light_stone_topdown_v1
        "DARK_STONE" -> R.drawable.bar_counter_surface_dark_stone_topdown_v1
        else -> R.drawable.bar_counter_surface_dark_wood_topdown_v1
    }
}

private fun DrawScope.drawFloorPlanBarCounterSegmentAsset(asset: ImageBitmap) {
    val width = size.width.roundToInt().coerceAtLeast(1)
    val height = size.height.roundToInt().coerceAtLeast(1)
    drawImage(
        image = asset,
        srcOffset = IntOffset(0, 0),
        srcSize = IntSize(asset.width, asset.height),
        dstOffset = IntOffset(0, 0),
        dstSize = IntSize(width, height),
        filterQuality = FilterQuality.High,
    )
}

private data class FloorPlanBarCounterPalette(
    val baseStart: Color,
    val baseEnd: Color,
    val materialWash: Color,
    val serviceStart: Color,
    val serviceEnd: Color,
    val workStart: Color,
    val workEnd: Color,
    val ledgeShadow: Color,
    val ledgeHighlight: Color,
    val separator: Color,
    val edgeStroke: Color,
    val edgeHighlight: Color,
    val bottomShadow: Color,
    val serviceHighlight: Color,
    val ambientHighlight: Color,
    val assetOpacity: Float,
)

private fun resolveFloorPlanBarCounterPalette(material: String?): FloorPlanBarCounterPalette {
    val normalizedMaterial = normalizedFloorPlanBarDeskMaterial(material)
    val isStone = normalizedMaterial == "LIGHT_STONE" || normalizedMaterial == "DARK_STONE"
    val isLight = normalizedMaterial == "LIGHT_WOOD" || normalizedMaterial == "LIGHT_STONE"

    return if (isStone) {
        if (isLight) {
            FloorPlanBarCounterPalette(
                baseStart = Color(0xFFAEB7B3),
                baseEnd = Color(0xFF596461),
                materialWash = Color(0xFF4E5653).copy(alpha = 0.42f),
                serviceStart = Color.White.copy(alpha = 0.10f),
                serviceEnd = Color.White.copy(alpha = 0.02f),
                workStart = Color.Black.copy(alpha = 0.04f),
                workEnd = Color.Black.copy(alpha = 0.22f),
                ledgeShadow = Color.Black.copy(alpha = 0.32f),
                ledgeHighlight = Color.White.copy(alpha = 0.18f),
                separator = Color(0xFF121616).copy(alpha = 0.46f),
                edgeStroke = Color(0xFF181D1D).copy(alpha = 0.78f),
                edgeHighlight = Color.White.copy(alpha = 0.16f),
                bottomShadow = Color.Black.copy(alpha = 0.30f),
                serviceHighlight = Color.White.copy(alpha = 0.20f),
                ambientHighlight = Color.White.copy(alpha = 0.06f),
                assetOpacity = 0.50f,
            )
        } else {
            FloorPlanBarCounterPalette(
                baseStart = Color(0xFF30383A),
                baseEnd = Color(0xFF111719),
                materialWash = Color(0xFF040708).copy(alpha = 0.28f),
                serviceStart = Color.White.copy(alpha = 0.08f),
                serviceEnd = Color.White.copy(alpha = 0.01f),
                workStart = Color.Black.copy(alpha = 0.06f),
                workEnd = Color.Black.copy(alpha = 0.28f),
                ledgeShadow = Color.Black.copy(alpha = 0.38f),
                ledgeHighlight = Color(0xFFF1F5F4).copy(alpha = 0.13f),
                separator = Color.Black.copy(alpha = 0.58f),
                edgeStroke = Color.Black.copy(alpha = 0.78f),
                edgeHighlight = Color(0xFFF1F5F4).copy(alpha = 0.10f),
                bottomShadow = Color.Black.copy(alpha = 0.36f),
                serviceHighlight = Color(0xFFF1F5F4).copy(alpha = 0.15f),
                ambientHighlight = Color.White.copy(alpha = 0.04f),
                assetOpacity = 0.48f,
            )
        }
    } else {
        if (isLight) {
            FloorPlanBarCounterPalette(
                baseStart = Color(0xFFB77A3F),
                baseEnd = Color(0xFF633719),
                materialWash = Color(0xFF5A2C0D).copy(alpha = 0.30f),
                serviceStart = Color(0xFFFFD698).copy(alpha = 0.13f),
                serviceEnd = Color(0xFFFFD698).copy(alpha = 0.03f),
                workStart = Color(0xFF3A1907).copy(alpha = 0.06f),
                workEnd = Color(0xFF1E0C04).copy(alpha = 0.26f),
                ledgeShadow = Color(0xFF230E04).copy(alpha = 0.36f),
                ledgeHighlight = Color(0xFFFFE8BE).copy(alpha = 0.22f),
                separator = Color(0xFF2B1407).copy(alpha = 0.52f),
                edgeStroke = Color(0xFF261005).copy(alpha = 0.72f),
                edgeHighlight = Color(0xFFFFE5B8).copy(alpha = 0.20f),
                bottomShadow = Color(0xFF140802).copy(alpha = 0.34f),
                serviceHighlight = Color(0xFFFFEECD).copy(alpha = 0.24f),
                ambientHighlight = Color(0xFFFFDCAA).copy(alpha = 0.07f),
                assetOpacity = 0.42f,
            )
        } else {
            FloorPlanBarCounterPalette(
                baseStart = Color(0xFF6C3A18),
                baseEnd = Color(0xFF251006),
                materialWash = Color(0xFF180903).copy(alpha = 0.26f),
                serviceStart = Color(0xFFCD8243).copy(alpha = 0.13f),
                serviceEnd = Color(0xFFCD8243).copy(alpha = 0.03f),
                workStart = Color.Black.copy(alpha = 0.05f),
                workEnd = Color.Black.copy(alpha = 0.30f),
                ledgeShadow = Color(0xFF0A0401).copy(alpha = 0.42f),
                ledgeHighlight = Color(0xFFE2A867).copy(alpha = 0.17f),
                separator = Color(0xFF0A0401).copy(alpha = 0.58f),
                edgeStroke = Color(0xFF0A0401).copy(alpha = 0.78f),
                edgeHighlight = Color(0xFFE2A867).copy(alpha = 0.14f),
                bottomShadow = Color.Black.copy(alpha = 0.38f),
                serviceHighlight = Color(0xFFE8B67D).copy(alpha = 0.18f),
                ambientHighlight = Color(0xFFFFCF96).copy(alpha = 0.045f),
                assetOpacity = 0.46f,
            )
        }
    }
}

private fun DrawScope.drawFloorPlanAssetBarCounterSurface(
    asset: ImageBitmap,
    material: String?,
    grainRotationDeg: Float?,
    stroke: Float,
) {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val minDim = min(width, height)
    val servingTopHeight = height * 0.35f
    val workingTopHeight = (height - servingTopHeight).coerceAtLeast(1f)
    val palette = resolveFloorPlanBarCounterPalette(material)
    val ledgeShadowHeight = floorPlanClamp(minDim * 0.12f, 4f, 14f)
    val edgeStrokeWidth = max(stroke, minDim * 0.018f)
    val highlightStrokeWidth = max(1f, minDim * 0.006f)

    clipRect(left = 0f, top = 0f, right = width, bottom = height) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.baseStart, palette.baseEnd),
                startY = 0f,
                endY = height,
            ),
            topLeft = Offset.Zero,
            size = Size(width, height),
        )
        drawFloorPlanBarCounterMaterialFill(
            asset = asset,
            material = material,
            grainRotationDeg = grainRotationDeg,
            alpha = palette.assetOpacity,
        )
        drawRect(
            color = palette.materialWash,
            topLeft = Offset.Zero,
            size = Size(width, height),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.serviceStart, palette.serviceEnd),
                startY = 0f,
                endY = servingTopHeight,
            ),
            topLeft = Offset.Zero,
            size = Size(width, servingTopHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.workStart, palette.workEnd),
                startY = servingTopHeight,
                endY = height,
            ),
            topLeft = Offset(0f, servingTopHeight),
            size = Size(width, workingTopHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.ledgeShadow, Color.Transparent),
                startY = servingTopHeight,
                endY = servingTopHeight + ledgeShadowHeight,
            ),
            topLeft = Offset(0f, servingTopHeight),
            size = Size(width, ledgeShadowHeight),
        )
        drawLine(
            color = palette.ledgeHighlight,
            start = Offset(0f, servingTopHeight - highlightStrokeWidth * 0.65f),
            end = Offset(width, servingTopHeight - highlightStrokeWidth * 0.65f),
            strokeWidth = highlightStrokeWidth,
        )
        drawLine(
            color = palette.separator,
            start = Offset(0f, servingTopHeight),
            end = Offset(width, servingTopHeight),
            strokeWidth = max(stroke, minDim * 0.010f),
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(palette.ambientHighlight, Color.Transparent),
                start = Offset.Zero,
                end = Offset(width, height),
            ),
            topLeft = Offset.Zero,
            size = Size(width, height),
        )
    }

    drawRect(
        color = palette.edgeStroke,
        topLeft = Offset.Zero,
        size = Size(width, height),
        style = Stroke(width = edgeStrokeWidth),
    )
    drawLine(
        color = palette.edgeHighlight,
        start = Offset(width * 0.03f, edgeStrokeWidth * 0.65f),
        end = Offset(width * 0.97f, edgeStrokeWidth * 0.65f),
        strokeWidth = highlightStrokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = palette.bottomShadow,
        start = Offset(width * 0.02f, height - edgeStrokeWidth * 0.65f),
        end = Offset(width * 0.98f, height - edgeStrokeWidth * 0.65f),
        strokeWidth = max(stroke, edgeStrokeWidth),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = palette.serviceHighlight,
        start = Offset(width * 0.05f, height * 0.07f),
        end = Offset(width * 0.95f, height * 0.07f),
        strokeWidth = highlightStrokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun isFloorPlanWoodBarCounterMaterial(material: String?): Boolean {
    return material == "LIGHT_WOOD" || material == "DARK_WOOD"
}

private fun normalizeFloorPlanGrainRotationDeg(value: Float?): Float {
    value ?: return 0f
    return ((value % 360f) + 360f) % 360f
}

private fun DrawScope.drawFloorPlanBarCounterMaterialFill(
    asset: ImageBitmap,
    material: String?,
    grainRotationDeg: Float?,
    alpha: Float,
) {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val rotatesGrain = isFloorPlanWoodBarCounterMaterial(material)
    val rotation = if (rotatesGrain) normalizeFloorPlanGrainRotationDeg(grainRotationDeg) else 0f
    val drawScale = 1.52f
    val drawWidth = width * drawScale
    val drawHeight = height * drawScale
    val drawTopLeft = Offset(
        x = -(drawWidth - width) / 2f,
        y = -(drawHeight - height) / 2f,
    )
    val center = Offset(width / 2f, height / 2f)

    withTransform({
        if (rotatesGrain) {
            rotate(degrees = rotation, pivot = center)
        }
    }) {
        drawImage(
            image = asset,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(asset.width, asset.height),
            dstOffset = IntOffset(drawTopLeft.x.roundToInt(), drawTopLeft.y.roundToInt()),
            dstSize = IntSize(
                width = drawWidth.roundToInt().coerceAtLeast(1),
                height = drawHeight.roundToInt().coerceAtLeast(1),
            ),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun DrawScope.drawFloorPlanWoodBrassBarCounterAsset(
    asset: ImageBitmap,
    stroke: Float,
) {
    val width = size.width.coerceAtLeast(1f)
    val height = size.height.coerceAtLeast(1f)
    val radiusValue = floorPlanClamp(min(width, height) * 0.10f, 4f, 18f)
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, Size(width, height)), CornerRadius(radiusValue, radiusValue)))
    }

    clipPath(path) {
        drawImage(
            image = asset,
            srcOffset = IntOffset(44, 386),
            srcSize = IntSize(1360, 350),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.18f),
            topLeft = Offset.Zero,
            size = Size(width, height),
        )
    }
    drawRoundRect(
        color = Color(0xFFE0AE6A).copy(alpha = 0.72f),
        topLeft = Offset.Zero,
        size = Size(width, height),
        cornerRadius = CornerRadius(radiusValue, radiusValue),
        style = Stroke(width = max(stroke, min(width, height) * 0.018f)),
    )
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
    objectBarDeskMaterial: String? = null,
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
                        barDeskMaterial = objectBarDeskMaterial,
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
    val compositeTableAsset = ImageBitmap.imageResource(id = R.drawable.floor_terrace_weathered_planks_120mm_v2)
    val greyStoneTableAsset = ImageBitmap.imageResource(id = R.drawable.floor_premium_stone_light_v1)
    val terraceTransparentTableAsset = ImageBitmap.imageResource(id = R.drawable.table_terrace_transparent)
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
                tableMaterial = table.tableMaterial,
                compositeTableAsset = compositeTableAsset,
                greyStoneTableAsset = greyStoneTableAsset,
                terraceTransparentTableAsset = terraceTransparentTableAsset,
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

private fun resolveFloorPlanTableMaterial(tableMaterial: String?): FloorPlanTableSurfaceTokens {
    val value = tableMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
    val theme = activeFloorPlanSurfaceTheme()
    return when (value) {
        "LIGHT_WOOD" -> theme.lightWoodTable
        "DARK_COMPOSITE" -> FloorPlanTableSurfaceTokens(
            top = Color(0xFF545B55),
            middle = Color(0xFF343A36),
            bottom = Color(0xFF151918),
            highlight = Color(0xFFBBC5BD).copy(alpha = 0.16f),
            grainLight = Color(0xFFC0B8A4).copy(alpha = 0.55f),
            grainDark = Color(0xFF060807),
            edgeDark = Color(0xFF070909),
            edgeWarm = Color(0xFF81846F).copy(alpha = 0.60f),
        )
        "GREY_STONE" -> FloorPlanTableSurfaceTokens(
            top = Color(0xFF6E7778),
            middle = Color(0xFF4C5556),
            bottom = Color(0xFF202627),
            highlight = Color(0xFFD8E4E3).copy(alpha = 0.18f),
            grainLight = Color(0xFFD0DDDC).copy(alpha = 0.44f),
            grainDark = Color(0xFF0B0F10),
            edgeDark = Color(0xFF111617),
            edgeWarm = Color(0xFF94A0A0).copy(alpha = 0.42f),
        )
        else -> theme.table
    }
}

private fun normalizedFloorPlanTableMaterial(tableMaterial: String?): String? {
    return tableMaterial
        ?.trim()
        ?.replace('-', '_')
        ?.uppercase()
}

private fun DrawScope.drawFloorPlanTableSurface(
    isRound: Boolean,
    isBarSeat: Boolean,
    topLeft: Offset,
    targetSize: Size,
    borderColor: Color,
    borderWidthPx: Float,
    baseColor: Color?,
    tableMaterial: String?,
    compositeTableAsset: ImageBitmap,
    greyStoneTableAsset: ImageBitmap,
    terraceTransparentTableAsset: ImageBitmap,
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

    val normalizedMaterial = normalizedFloorPlanTableMaterial(tableMaterial)
    if (
        normalizedMaterial == "DARK_COMPOSITE" ||
        normalizedMaterial == "GREY_STONE" ||
        normalizedMaterial == "TERRACE_TRANSPARENT"
    ) {
        drawFloorPlanTexturedTableAsset(
            isRound = isRound,
            topLeft = topLeft,
            targetSize = targetSize,
            borderColor = borderColor,
            borderWidthPx = borderWidthPx,
            material = normalizedMaterial,
            asset = when (normalizedMaterial) {
                "GREY_STONE" -> greyStoneTableAsset
                "TERRACE_TRANSPARENT" -> terraceTransparentTableAsset
                else -> compositeTableAsset
            },
        )
        return
    }

    val material = resolveFloorPlanTableMaterial(tableMaterial)
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

private fun DrawScope.drawFloorPlanTexturedTableAsset(
    isRound: Boolean,
    topLeft: Offset,
    targetSize: Size,
    borderColor: Color,
    borderWidthPx: Float,
    material: String,
    asset: ImageBitmap,
) {
    val minDim = min(targetSize.width, targetSize.height).coerceAtLeast(1f)
    val path = Path().apply {
        if (isRound) {
            addOval(Rect(topLeft, targetSize))
        } else {
            addRect(Rect(topLeft, targetSize))
        }
    }
    clipPath(path) {
        drawImage(
            image = asset,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(asset.width, asset.height),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(
                width = targetSize.width.roundToInt().coerceAtLeast(1),
                height = targetSize.height.roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Medium,
        )
        if (material != "TERRACE_TRANSPARENT") {
            drawRect(
                color = if (material == "DARK_COMPOSITE") {
                    Color(0xFF101313).copy(alpha = 0.46f)
                } else {
                    Color(0xFF283033).copy(alpha = 0.34f)
                },
                topLeft = topLeft,
                size = targetSize,
            )
        }
    }

    if (material == "DARK_COMPOSITE") {
        val plankCount = 4
        val step = targetSize.height / plankCount
        for (index in 1 until plankCount) {
            val y = topLeft.y + step * index
            drawLine(
                color = Color.Black.copy(alpha = 0.42f),
                start = Offset(topLeft.x, y),
                end = Offset(topLeft.x + targetSize.width, y),
                strokeWidth = max(0.75f, borderWidthPx * 0.75f),
            )
        }
    } else if (material == "GREY_STONE") {
        drawFloorPlanCounterStoneVeins(
            topLeft = topLeft,
            targetSize = targetSize,
            horizontal = targetSize.width >= targetSize.height,
            veinLight = Color(0xFFD3DEDE).copy(alpha = 0.18f),
            veinDark = Color(0xFF0B0F10).copy(alpha = 0.24f),
        )
    }

    val rimWidth = max(borderWidthPx, minDim * 0.045f)
    if (isRound) {
        drawOval(
            color = Color.Black.copy(alpha = 0.46f),
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = rimWidth),
        )
        drawOval(
            color = borderColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = borderWidthPx),
        )
    } else {
        drawRect(
            color = Color.Black.copy(alpha = 0.46f),
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = rimWidth),
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
