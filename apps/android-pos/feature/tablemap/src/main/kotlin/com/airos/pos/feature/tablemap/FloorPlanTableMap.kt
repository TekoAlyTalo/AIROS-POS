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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
    BoxWithConstraints(
        modifier = modifier
            .clip(FloorPlanViewportShape)
            .background(FloorPlanBackgroundBrush)
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

        val gridStepWorldPx = 120f
        val strokeWidth = 1.dp.toPx()

        var worldX = 0f
        while (worldX <= contentWidthPx) {
            val screenX = panOffset.x + worldX * zoom
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
            val screenY = panOffset.y + worldY * zoom
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
    val fillTop = Color(0xFFCD9D4A).copy(alpha = 0.26f)
    val fillBottom = areaSurfaceColor(area).copy(alpha = 0.24f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.46f)
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
    val fillTop = Color(0xFFCD9D4A).copy(alpha = 0.26f)
    val fillBottom = areaSurfaceColor(area).copy(alpha = 0.24f)
    val borderColor = areaSurfaceBorderColor(area).copy(alpha = 0.46f)
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
            val radius = CornerRadius(4f, 4f)
            val fill = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF51290F).copy(alpha = 0.96f),
                    Color(0xFF2B1508).copy(alpha = 0.96f),
                ),
            )

            drawRoundRect(
                brush = fill,
                topLeft = Offset.Zero,
                size = targetSize,
                cornerRadius = radius,
            )
            drawRoundRect(
                color = Color(0xFFB8782A).copy(alpha = 0.78f),
                topLeft = Offset.Zero,
                size = targetSize,
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )

            val railInset = max(3f, min(targetSize.width, targetSize.height) * 0.10f)
            drawLine(
                color = Color(0xFFE6C47A).copy(alpha = 0.28f),
                start = Offset(railInset, railInset),
                end = Offset(targetSize.width - railInset, railInset),
                strokeWidth = stroke,
            )
            drawLine(
                color = Color(0xFF1B0D05).copy(alpha = 0.44f),
                start = Offset(railInset, targetSize.height - railInset),
                end = Offset(targetSize.width - railInset, targetSize.height - railInset),
                strokeWidth = stroke,
            )
        }
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
        else -> {
            FloorPlanLabeledObjectSurface(
                label = floorObject.label,
                widthDp = widthDp,
                heightDp = heightDp,
                screenWidthPx = objectWidthPx,
                modifier = baseModifier,
                objectType = objectType,
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
private fun FloorPlanLabeledObjectSurface(
    label: String,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    screenWidthPx: Float,
    modifier: Modifier,
    objectType: String,
    showLabel: Boolean = true,
) {
    val normalizedType = objectType.lowercase()
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
                    val radius = CornerRadius(4f, 4f)
                    val fill = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF51290F).copy(alpha = 0.96f),
                            Color(0xFF2B1508).copy(alpha = 0.96f),
                        ),
                    )
                    drawRoundRect(
                        brush = fill,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color(0xFFB8782A).copy(alpha = 0.78f),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = radius,
                        style = Stroke(width = stroke),
                    )
                    val railInset = max(3f, min(size.width, size.height) * 0.10f)
                    drawLine(
                        color = Color(0xFFE6C47A).copy(alpha = 0.28f),
                        start = Offset(railInset, railInset),
                        end = Offset(size.width - railInset, railInset),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Color(0xFF1B0D05).copy(alpha = 0.44f),
                        start = Offset(railInset, size.height - railInset),
                        end = Offset(size.width - railInset, size.height - railInset),
                        strokeWidth = stroke,
                    )
                }
                "sofa", "couch" -> {
                    drawFloorPlanSofaSurface(
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
        openSaleTotalLabels.isNotEmpty()
    val showGuestCount = bodyWidthPx >= 58f && bodyHeightPx >= 48f
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
                topLeft = bodyTopLeft,
                targetSize = bodySize,
                borderColor = bodyStroke,
                borderWidthPx = borderWidthPx,
            )
        }

        val chipSpecs = buildList {
            if (showStatusTick) {
                add(
                    FloorPlanChipSpec(
                        label = statusTick.label,
                        kind = FloorPlanChipKind.STATUS,
                        tint = attentionTint ?: accent,
                    )
                )
            }
            if (showOpenChips) {
                openSaleTotalLabels.filter { it.isNotBlank() }.forEach { amountLabel ->
                    add(FloorPlanChipSpec(label = amountLabel, kind = FloorPlanChipKind.AMOUNT))
                }
            }
            if (showGuestCount) {
                add(FloorPlanChipSpec(label = table.floorPlanCustomerLabel(displayStatus), kind = FloorPlanChipKind.META))
                add(FloorPlanChipSpec(label = table.floorPlanSeatsLabel(), kind = FloorPlanChipKind.META))
            }
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
                if (chipSpecs.isNotEmpty()) {
                    FloorPlanAdaptiveChipGrid(
                        chips = chipSpecs,
                        bodyWidthPx = bodyWidthPx,
                        bodyHeightPx = bodyHeightPx,
                        isRound = isRound,
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

private enum class FloorPlanChipKind { STATUS, AMOUNT, META }

private data class FloorPlanChipSpec(
    val label: String,
    val kind: FloorPlanChipKind,
    val tint: Color? = null,
)

private data class FloorPlanChipMetrics(
    val widthPx: Float,
    val heightPx: Float,
)

private fun RestaurantTable.floorPlanCustomerLabel(displayStatus: TableDisplayStatus): String {
    val truthfulCount = guestCount.coerceAtLeast(0)
    val displayCount = when {
        truthfulCount > 0 -> truthfulCount
        displayStatus.kind == TableDisplayStatusKind.OCCUPIED ||
            displayStatus.kind == TableDisplayStatusKind.OPEN_BILL ||
            displayStatus.kind == TableDisplayStatusKind.RESERVED_WITH_OPEN_BILL -> 1
        else -> 0
    }
    val noun = if (displayCount == 1) "customer" else "customers"
    return "$displayCount $noun"
}

private fun RestaurantTable.floorPlanSeatsLabel(): String {
    val count = seats.coerceAtLeast(0)
    val noun = if (count == 1) "seat" else "seats"
    return "$count $noun"
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
    val rows = arrangeFloorPlanChipRows(
        chips = chips,
        chipMetrics = chipMetrics,
        availableWidthPx = effectiveWidthPx.coerceAtLeast(1f),
        gapPx = horizontalGapPx,
    )
    val estimatedHeightPx = rows.sumOf { row -> row.maxOf { chip -> chipMetrics.getValue(chip).heightPx }.toDouble() }.toFloat() +
        (rows.size - 1).coerceAtLeast(0) * verticalGapPx
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
    val widthPx = chip.label.length * fontPx * 0.58f + with(density) { (horizontalPaddingDp.dp * 2).toPx() }
    val heightPx = fontPx * 1.25f + with(density) { (verticalPaddingDp.dp * 2).toPx() }
    return FloorPlanChipMetrics(widthPx = widthPx, heightPx = heightPx)
}

@Composable
private fun FloorPlanChip(
    chip: FloorPlanChipSpec,
    bodyWidthPx: Float,
    bodyHeightPx: Float,
    compact: Boolean,
) {
    when (chip.kind) {
        FloorPlanChipKind.STATUS -> {
            val tickTint = chip.tint ?: FloorPlanAvailableColor
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = tickTint.copy(alpha = if (chip.tint != null) 0.30f else 0.16f),
                border = BorderStroke(1.dp, tickTint.copy(alpha = 0.34f)),
            ) {
                Text(
                    text = chip.label,
                    modifier = Modifier.padding(horizontal = if (compact) 8.dp else 9.dp, vertical = if (compact) 2.dp else 3.dp),
                    style = floorPlanTableStatusLabelStyle(bodyWidthPx, bodyHeightPx),
                    color = tickTint.copy(alpha = 0.96f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        FloorPlanChipKind.AMOUNT -> {
            Surface(
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
    topLeft: Offset,
    targetSize: Size,
    borderColor: Color,
    borderWidthPx: Float,
) {
    val horizontalGrain = targetSize.width >= targetSize.height
    val baseBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF6A3213).copy(alpha = 0.99f),
            Color(0xFF3A1808).copy(alpha = 0.99f),
            Color(0xFF180904).copy(alpha = 0.99f),
        ),
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
            Color(0xFFFFD08A).copy(alpha = 0.105f),
            Color.Transparent,
        ),
        start = if (horizontalGrain) topLeft else Offset(topLeft.x + targetSize.width, topLeft.y),
        end = if (horizontalGrain) {
            Offset(topLeft.x, topLeft.y + targetSize.height)
        } else {
            Offset(topLeft.x, topLeft.y)
        },
    )

    if (isRound) {
        drawOval(brush = baseBrush, topLeft = topLeft, size = targetSize)
        drawOval(brush = sheenBrush, topLeft = topLeft, size = targetSize)
    } else {
        drawRect(brush = baseBrush, topLeft = topLeft, size = targetSize)
        drawRect(brush = sheenBrush, topLeft = topLeft, size = targetSize)
    }

    drawFloorPlanWoodGrainLines(
        topLeft = topLeft,
        targetSize = targetSize,
        horizontal = horizontalGrain,
        subtle = isRound,
    )

    if (isRound) {
        drawOval(
            color = borderColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = borderWidthPx),
        )
    } else {
        drawRect(
            color = borderColor,
            topLeft = topLeft,
            size = targetSize,
            style = Stroke(width = borderWidthPx),
        )
    }
}

private fun DrawScope.drawFloorPlanWoodGrainLines(
    topLeft: Offset,
    targetSize: Size,
    horizontal: Boolean,
    subtle: Boolean,
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
                color = Color(0xFFFFC07A).copy(alpha = alpha),
                start = Offset(topLeft.x + inset, y),
                end = Offset(topLeft.x + longLength - inset, y + wobble),
                strokeWidth = max(0.55f, 0.45.dp.toPx()),
            )
            drawLine(
                color = Color(0xFF120603).copy(alpha = alpha * 0.86f),
                start = Offset(topLeft.x + inset, y + 1.4f),
                end = Offset(topLeft.x + longLength - inset, y + wobble + 1.4f),
                strokeWidth = max(0.45f, 0.35.dp.toPx()),
            )
        } else {
            val x = topLeft.x + offset
            drawLine(
                color = Color(0xFFFFC07A).copy(alpha = alpha),
                start = Offset(x, topLeft.y + inset),
                end = Offset(x + wobble, topLeft.y + longLength - inset),
                strokeWidth = max(0.55f, 0.45.dp.toPx()),
            )
            drawLine(
                color = Color(0xFF120603).copy(alpha = alpha * 0.86f),
                start = Offset(x + 1.4f, topLeft.y + inset),
                end = Offset(x + wobble + 1.4f, topLeft.y + longLength - inset),
                strokeWidth = max(0.45f, 0.35.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawFloorPlanSofaSurface(
    targetSize: Size,
    stroke: Float,
) {
    val radius = CornerRadius(
        x = min(10f, targetSize.width * 0.16f),
        y = min(10f, targetSize.height * 0.22f),
    )
    val fill = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF6A747C).copy(alpha = 0.97f),
            Color(0xFF3C454E).copy(alpha = 0.98f),
            Color(0xFF1E252C).copy(alpha = 0.99f),
        ),
    )
    drawRoundRect(
        brush = fill,
        topLeft = Offset.Zero,
        size = targetSize,
        cornerRadius = radius,
    )
    drawRoundRect(
        color = Color(0xFFC4CDD4).copy(alpha = 0.66f),
        topLeft = Offset.Zero,
        size = targetSize,
        cornerRadius = radius,
        style = Stroke(width = stroke),
    )
    val backHeight = targetSize.height * 0.30f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF7B858D).copy(alpha = 0.58f),
                Color(0xFF2F3840).copy(alpha = 0.38f),
            ),
        ),
        topLeft = Offset(0f, 0f),
        size = Size(targetSize.width, backHeight),
        cornerRadius = radius,
    )
    val cushionCount = if (targetSize.width >= 120f) 3 else 2
    val step = targetSize.width / cushionCount
    for (index in 1 until cushionCount) {
        val x = step * index
        drawLine(
            color = Color(0xFFE9F0F6).copy(alpha = 0.26f),
            start = Offset(x, backHeight),
            end = Offset(x, targetSize.height - stroke),
            strokeWidth = max(stroke, 0.8.dp.toPx()),
        )
        drawLine(
            color = Color(0xFF10151A).copy(alpha = 0.28f),
            start = Offset(x + stroke, backHeight),
            end = Offset(x + stroke, targetSize.height - stroke),
            strokeWidth = max(0.5f, 0.35.dp.toPx()),
        )
    }
}

private fun DrawScope.drawFloorPlanArmchairSurface(
    topLeft: Offset,
    targetSize: Size,
    radius: CornerRadius,
    stroke: Float,
) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF68737C).copy(alpha = 0.97f),
                Color(0xFF3B444D).copy(alpha = 0.98f),
                Color(0xFF1F262D).copy(alpha = 0.99f),
            ),
        ),
        topLeft = topLeft,
        size = targetSize,
        cornerRadius = radius,
    )
    drawRoundRect(
        color = Color(0xFFC4CDD4).copy(alpha = 0.64f),
        topLeft = topLeft,
        size = targetSize,
        cornerRadius = radius,
        style = Stroke(width = stroke),
    )
    val backHeight = targetSize.height * 0.24f
    drawRoundRect(
        color = Color(0xFF7A858E).copy(alpha = 0.46f),
        topLeft = topLeft,
        size = Size(targetSize.width, backHeight),
        cornerRadius = radius,
    )
    val armWidth = max(stroke * 2f, targetSize.width * 0.13f)
    val armColor = Color(0xFF87929C).copy(alpha = 0.40f)
    drawRoundRect(
        color = armColor,
        topLeft = Offset(topLeft.x, topLeft.y + backHeight * 0.55f),
        size = Size(armWidth, targetSize.height - backHeight * 0.55f),
        cornerRadius = CornerRadius(armWidth, armWidth),
    )
    drawRoundRect(
        color = armColor,
        topLeft = Offset(topLeft.x + targetSize.width - armWidth, topLeft.y + backHeight * 0.55f),
        size = Size(armWidth, targetSize.height - backHeight * 0.55f),
        cornerRadius = CornerRadius(armWidth, armWidth),
    )
    drawLine(
        color = Color(0xFFE9F0F6).copy(alpha = 0.24f),
        start = Offset(topLeft.x + armWidth, topLeft.y + targetSize.height * 0.58f),
        end = Offset(topLeft.x + targetSize.width - armWidth, topLeft.y + targetSize.height * 0.58f),
        strokeWidth = stroke,
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


