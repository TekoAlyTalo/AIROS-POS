package com.airos.pos.feature.tablemap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.airos.pos.core.model.FloorMapArea
import com.airos.pos.core.model.FloorMapObject
import com.airos.pos.core.model.RestaurantTable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FloorPlanContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class FloorPlanRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class FloorPlanSize(
    val width: Float,
    val height: Float,
)

internal data class FloorPlanZoneLabelPlacement(
    val label: String,
    val xPx: Float,
    val yPx: Float,
)

internal data class FloorPlanTableSeatMarker(
    val leftPx: Float,
    val topPx: Float,
)

internal data class FloorPlanAreaPlacement(
    val area: FloorMapArea,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

internal data class FloorPlanTablePlacement(
    val table: RestaurantTable,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

internal data class FloorPlanObjectPlacement(
    val floorObject: FloorMapObject,
    val rect: FloorPlanRect,
    val effectiveRotationDeg: Float,
)

internal data class FloorPlanLayoutModel(
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

internal fun FloorPlanRect.toScreenRect(panOffset: Offset, zoom: Float): FloorPlanRect {
    return FloorPlanRect(
        left = panOffset.x + left * zoom,
        top = panOffset.y + top * zoom,
        right = panOffset.x + right * zoom,
        bottom = panOffset.y + bottom * zoom,
    )
}

internal fun floorPlanCanvasBoundsOrNull(
    widthPx: Float?,
    heightPx: Float?,
): FloorPlanContentBounds? {
    val safeWidth = widthPx?.takeIf { it > 0f } ?: return null
    val safeHeight = heightPx?.takeIf { it > 0f } ?: return null
    return FloorPlanContentBounds(
        left = 0f,
        top = 0f,
        right = safeWidth,
        bottom = safeHeight,
    )
}

internal fun FloorPlanRect.withTopLeftVisualSize(widthPx: Float, heightPx: Float): FloorPlanRect {
    return FloorPlanRect(
        left = left,
        top = top,
        right = left + widthPx,
        bottom = top + heightPx,
    )
}

internal fun Float.debugPx(): String = roundToInt().toString()

internal fun Float.debugCoord(): String = roundToInt().toString()

internal fun FloorPlanRect.intersectionArea(other: FloorPlanRect): Float {
    val overlapLeft = max(left, other.left)
    val overlapTop = max(top, other.top)
    val overlapRight = min(right, other.right)
    val overlapBottom = min(bottom, other.bottom)
    val width = (overlapRight - overlapLeft).coerceAtLeast(0f)
    val height = (overlapBottom - overlapTop).coerceAtLeast(0f)
    return width * height
}

internal fun Float.toDp(density: Density) = with(density) { this@toDp.toDp() }