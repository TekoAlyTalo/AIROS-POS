# AIROS POS Graphics Translation: Dashboard React/CSS -> Android Kotlin Canvas

Date: 2026-04-27
Purpose: translate Dashboard FloorPlan Editor rendering commands into Android POS Kotlin Canvas rendering without inventing a separate runtime language.

## 0. Scope

This is not a new runtime architecture and not a separate program.

It is a developer translation document:

```text
Dashboard render meaning -> equivalent Kotlin Canvas draw call
```

The goal is that POS floor plan rendering matches Dashboard editor rendering as closely as possible, excluding temporary theme/color differences only when explicitly accepted.

## 1. Prime rule

Every Dashboard floor plan object property must exist in POS data model, whether POS uses it immediately or not.

```text
Dashboard property exists -> POS property exists
```

No silent flattening. No skipped geometry. No fake fallback. No sample/static scaffold.

## 2. Mental model

The user-level idea is exactly this:

```text
box(1, 1, 20, 20).color(red).border(1).rounded(14)
```

Dashboard may express it as:

```tsx
<div
  style={{
    left: x * zoom,
    top: y * zoom,
    width: width * zoom,
    height: height * zoom,
    borderRadius: "14px",
    background: fill,
    border: "1px solid ...",
    transform: `rotate(${rotation}deg)`,
  }}
/>
```

Android POS must express the same thing as:

```kotlin
withTransform({ rotate(degrees = rotation, pivot = center) }) {
    drawRoundRect(
        color = fill,
        topLeft = Offset(x * zoom + panX, y * zoom + panY),
        size = Size(width * zoom, height * zoom),
        cornerRadius = CornerRadius(14f),
    )
    drawRoundRect(
        color = stroke,
        topLeft = Offset(x * zoom + panX, y * zoom + panY),
        size = Size(width * zoom, height * zoom),
        cornerRadius = CornerRadius(14f),
        style = Stroke(width = 1.dp.toPx()),
    )
}
```

Important: geometry scales with zoom. Text and stroke thickness usually should not balloon unless Dashboard does so.

## 3. Coordinate translation

Dashboard editor uses map/world coordinates. Android POS should preserve the same world coordinates.

### React/CSS

```tsx
screenX = viewport.panX + worldX * viewport.zoom
screenY = viewport.panY + worldY * viewport.zoom
screenW = worldW * viewport.zoom
screenH = worldH * viewport.zoom
```

### Kotlin Canvas

```kotlin
fun sx(x: Float) = pan.x + x * zoom
fun sy(y: Float) = pan.y + y * zoom
fun sw(width: Float) = width * zoom
fun sh(height: Float) = height * zoom
```

### Do not do

```text
Do not normalize to content bbox and lose editor map coordinates unless only viewport fitting needs it.
Do not round Float coordinates to Int for rendering.
Do not interpret camera/objects as new POS objects with new dimensions.
```

## 4. Rotation translation

### React/CSS

```tsx
style={{ transform: `rotate(${object.rotation}deg)` }}
```

Usually transform origin is center by default or explicit center behavior is intended.

### Kotlin Canvas

```kotlin
val center = Offset(sx(x + width / 2f), sy(y + height / 2f))
withTransform({ rotate(degrees = rotation, pivot = center) }) {
    // draw object using screen rect
}
```

Rule: rotate around object center unless Dashboard explicitly uses another origin.

## 5. Fill, border, opacity

### React/CSS

```tsx
backgroundColor: "rgba(...)"
border: "1px solid rgba(...)"
opacity: 0.42
```

### Kotlin Canvas

```kotlin
drawRect(color = fill.copy(alpha = 0.42f), ...)
drawRect(color = stroke.copy(alpha = 0.70f), style = Stroke(width = 1.dp.toPx()), ...)
```

Rule: stroke width is screen-space unless Dashboard scales it. Most editor borders are CSS 1px, so Kotlin should use `1.dp.toPx()` or a clamped screen pixel stroke, not `1 * zoom`.

## 6. Shape translation table

| Dashboard shape/property | Meaning | Kotlin Canvas equivalent |
|---|---|---|
| `rectangle` | hard-corner rect | `drawRect(...)` |
| `roundedRectangle`, `borderRadius: 14px` | rounded rect | `drawRoundRect(..., cornerRadius = CornerRadius(14f))` |
| `ellipse` | ellipse in bounding box | `drawOval(...)` |
| `round` table | round/oval table body | `drawOval(...)` |
| `square` table | square body | `drawRect(...)` or `drawRoundRect` only if editor rounds it |
| `clip-path: polygon(...)` | triangle/polygon | `Path`, `drawPath(...)` |
| `svg path/line/rect` | icon/symbol | Canvas `Path`, `drawLine`, `drawRect`, or prebuilt vector path |

## 7. Floor tile translation

Dashboard tile fields that POS must preserve:

```text
id, label, x, y, width, height, shape, rotation, locked, hidden,
areaType, surfaceMaterial,
p1XPercent, p1YPercent, p2XPercent, p2YPercent, p3XPercent, p3YPercent, apexXPercent
```

### Rectangle tile

```kotlin
drawRect(
    color = tileFill,
    topLeft = Offset(sx(tile.x), sy(tile.y)),
    size = Size(sw(tile.width), sh(tile.height)),
)
drawRect(
    color = tileBorder,
    topLeft = Offset(sx(tile.x), sy(tile.y)),
    size = Size(sw(tile.width), sh(tile.height)),
    style = Stroke(width = 1.dp.toPx()),
)
```

### Rounded rectangle tile

```kotlin
drawRoundRect(
    color = tileFill,
    topLeft = Offset(sx(tile.x), sy(tile.y)),
    size = Size(sw(tile.width), sh(tile.height)),
    cornerRadius = CornerRadius(14f),
)
```

### Ellipse tile

```kotlin
drawOval(
    color = tileFill,
    topLeft = Offset(sx(tile.x), sy(tile.y)),
    size = Size(sw(tile.width), sh(tile.height)),
)
```

### Triangle tile

React:

```tsx
clipPath: `polygon(${p1.xPercent}% ${p1.yPercent}%, ${p2.xPercent}% ${p2.yPercent}%, ${p3.xPercent}% ${p3.yPercent}%)`
```

Kotlin:

```kotlin
val path = Path().apply {
    moveTo(sx(tile.x + tile.width * p1x / 100f), sy(tile.y + tile.height * p1y / 100f))
    lineTo(sx(tile.x + tile.width * p2x / 100f), sy(tile.y + tile.height * p2y / 100f))
    lineTo(sx(tile.x + tile.width * p3x / 100f), sy(tile.y + tile.height * p3y / 100f))
    close()
}
drawPath(path, tileFill)
drawPath(path, tileBorder, style = Stroke(width = 1.dp.toPx()))
```

## 8. Table body translation

Dashboard table fields POS must preserve and use:

```text
id, label, tableNumber, x, y, width, height, rotation, shape, capacity, chairLayout
```

### Dashboard behavior

- `shape === "round"` -> border radius `9999px` -> oval/circle table.
- otherwise rect/square table body.
- label size depends on table width: roughly 11px / 10px / 9px in Dashboard.
- chairs are separate small markers around the table, not part of the table body.

### Kotlin body

```kotlin
val rect = Rect(
    left = sx(table.x),
    top = sy(table.y),
    right = sx(table.x + table.width),
    bottom = sy(table.y + table.height),
)

withTransform({ rotate(table.rotation, rect.center) }) {
    if (table.shape == "round") {
        drawOval(color = tableFill, topLeft = rect.topLeft, size = rect.size)
        drawOval(color = tableBorder, topLeft = rect.topLeft, size = rect.size, style = Stroke(1.dp.toPx()))
    } else {
        drawRect(color = tableFill, topLeft = rect.topLeft, size = rect.size)
        drawRect(color = tableBorder, topLeft = rect.topLeft, size = rect.size, style = Stroke(1.dp.toPx()))
    }
}
```

### Status overlays

POS status can add an overlay/badge, but must not change physical geometry:

```text
Needs Cleaning, Free, bill count, selected outline = overlay
Table shape, size, chair layout = editor truth
```

## 9. Chair / seat marker translation

Dashboard functions to port directly:

```text
getDefaultChairLayout
spreadSeatPositions
getRectFourCenteredSeatMarkers
getTableSeatMarkers
```

### Default chair layout logic

```kotlin
fun defaultChairLayout(shape: String?, chairLayout: String?, capacity: Int): String {
    if (!chairLayout.isNullOrBlank()) return chairLayout
    return when (shape) {
        "round" -> "round_even"
        "square" -> "square_even"
        else -> if (capacity >= 6) "rectangle_sides_only" else "rectangle_ends_and_sides"
    }
}
```

### Spread positions

```kotlin
fun spreadSeatPositions(count: Int, startPx: Float, endPx: Float): List<Float> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf((startPx + endPx) / 2f)
    val step = (endPx - startPx) / (count - 1)
    return List(count) { index -> startPx + step * index }
}
```

### Round table chairs

```kotlin
val radiusX = width / 2f + seatOffset
val radiusY = height / 2f + seatOffset
val centerX = width / 2f
val centerY = height / 2f
for (index in 0 until capacity) {
    val angle = (Math.PI * 2.0 * index / capacity) - Math.PI / 2.0
    val chairX = centerX + cos(angle).toFloat() * radiusX
    val chairY = centerY + sin(angle).toFloat() * radiusY
}
```

### Draw chair marker

Dashboard uses small circular markers. Kotlin:

```kotlin
drawCircle(
    color = chairFill,
    radius = chairRadiusPx,
    center = rotatedWorldPointToScreen(table.x + chairX, table.y + chairY, table.rotation, tableCenter),
)
drawCircle(
    color = chairStroke,
    radius = chairRadiusPx,
    center = ...,
    style = Stroke(width = 1.dp.toPx()),
)
```

Seat marker radius should be fixed or gently clamped in screen pixels, not huge with zoom.

## 10. Text translation

Dashboard object text often uses CSS classes such as `text-[11px]`, `text-[10px]`, `text-[9px]`.

### Rule

Geometry scales with zoom. Text size should be fixed/clamped like CSS text, not multiplied blindly by zoom.

### Kotlin

```kotlin
drawContext.canvas.nativeCanvas.drawText(
    label,
    screenX,
    screenY,
    Paint().apply {
        color = textColor.toArgb()
        textSize = 11.sp.toPx() // or px equivalent
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
)
```

### Width-aware text size

Dashboard:

```text
width >= 170 -> 11px
width >= 110 -> 10px
else -> 9px
```

Kotlin:

```kotlin
fun tableLabelTextSizePx(worldWidth: Float): Float = when {
    worldWidth >= 170f -> 11.sp.toPx()
    worldWidth >= 110f -> 10.sp.toPx()
    else -> 9.sp.toPx()
}
```

Do not enlarge text to 40px merely because zoom is high.

## 11. Camera translation

Dashboard camera truth:

```text
CAMERA_SYMBOL_PX = 40
FixedCameraIcon SVG viewBox 0 0 24 24
```

### Important

Camera object is a marker/icon. It is not a giant physical coverage circle.

Current bad POS behavior:

```text
camera rendered as huge circle / coverage object, visually over 2 meters wide
```

Correct POS behavior:

```kotlin
val size = 40f // screen px or editor-equivalent marker size, matching Dashboard
val left = sx(camera.x)
val top = sy(camera.y)
// draw camera icon path inside 40x40 marker
```

If coverage is later needed, render it as optional overlay, not as camera symbol.

### Camera icon implementation options

Option A: draw simplified camera symbol with Canvas paths/lines.

Option B: use Compose VectorPainter / ImageVector matching Dashboard SVG path.

For parity, Option B is better eventually. Option A is acceptable for first v2 if dimensions and placement match.

## 12. Door translation

Dashboard door uses SVG geometry:

```text
threshold line
swing arc
open leaf line
hinge marker rectangle
```

Kotlin translation:

```kotlin
val hinge = ...
val closedEnd = ...
val openEnd = ...

drawLine(color = thresholdColor, start = hinge, end = closedEnd, strokeWidth = thresholdStroke)
drawArc(color = arcColor, ..., useCenter = false, style = Stroke(arcStroke))
drawLine(color = leafColor, start = hinge, end = openEnd, strokeWidth = leafStroke)
drawRect(color = hingeFill, topLeft = hingeMarkerTopLeft, size = hingeMarkerSize)
```

Door must use `doorHingeSide` and `doorSwingDirection` exactly.

## 13. Wall translation

Dashboard wall/structure is a rectangular object with width/height and rotation.

Kotlin:

```kotlin
withTransform({ rotate(wall.rotation, center) }) {
    drawRect(color = wallFill, topLeft = topLeft, size = size)
    drawRect(color = wallStroke, topLeft = topLeft, size = size, style = Stroke(1.dp.toPx()))
}
```

Wall default thickness from editor/backend is about 10 cm where applicable. Do not inflate it in POS.

## 14. Sofa / bar / chair object translation

Do not render all objects as same generic brown box.

Each object type has its own visual recipe:

```text
sofa -> rounded rectangular furniture with sofa label or label hidden by zoom
bar-counter -> rectangular counter, darker fill, correct label placement
chair/armchair -> small circular/rounded furniture marker
wall -> thin structure
camera -> fixed icon marker
```

All use editor x/y/width/height/rotation.

## 15. Labels and overlays

Labels should be screen-space overlays anchored to world geometry.

```kotlin
screenAnchor = worldToScreen(anchor)
textSize = fixed/clamped
```

Do not use a giant Compose Text inside a scaled object box.

Recommended layering:

```text
Layer 1: background/grid
Layer 2: floor tiles
Layer 3: structures/walls/doors
Layer 4: furniture/tables/chairs/cameras
Layer 5: editor labels
Layer 6: POS overlays: status, bills, selection, dirty/check badges
```

## 16. Grid translation

Dashboard grid depends on `pxPerMeter` and zoom:

```text
zoom >= 1.35 -> 0.25 m
zoom >= 0.85 -> 0.5 m
else -> 1 m
```

Kotlin:

```kotlin
fun metricGridStepMeters(zoom: Float): Float = when {
    zoom >= 1.35f -> 0.25f
    zoom >= 0.85f -> 0.5f
    else -> 1f
}

val gridStepPx = activeMap.pxPerMeter * metricGridStepMeters(zoom) * zoom
```

Grid is screen/world visual aid. It must align with editor map origin.

## 17. Pan, drag, zoom

Current regression: map cannot be dragged naturally after renderer v1.

Correct model:

```text
single-finger drag on background -> pan
pinch -> zoom around centroid
tap on table/object -> hit test
screen overlays must not block pan unless intentionally interactive
```

Dashboard pan logic:

```text
pointer down stores originPanX/Y and startClientX/Y
pointer move sets panX = originPanX + deltaX, panY = originPanY + deltaY
threshold avoids accidental click
```

Kotlin Compose equivalent:

```kotlin
detectTransformGestures { centroid, pan, zoomChange, _ ->
    val oldZoom = zoom
    val newZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
    val scaleChange = newZoom / oldZoom
    panOffset = centroid + (panOffset - centroid) * scaleChange + pan
    panOffset = clampPan(panOffset, newZoom)
    zoom = newZoom
}
```

If single-finger panning is unreliable with `detectTransformGestures`, use a dedicated pointer detector or ensure overlays do not consume events.

## 18. Hit testing

Hit testing must use the same world geometry as rendering.

```kotlin
val worldPoint = screenToWorld(tap)
val hit = tables.lastOrNull { rotatedRectContains(it.rect, it.rotation, worldPoint) }
```

If object is rotated, inverse-rotate the tap point around object center before rectangle containment.

## 19. What not to do

```text
Do not create a fake runtime language.
Do not create sample/scaffold fallback.
Do not use old static bar/staff bounds.
Do not draw camera coverage as camera symbol.
Do not let POS status modify table geometry.
Do not round coordinates for rendering.
Do not scale text/strokes like bitmap zoom unless Dashboard does.
Do not use Compose Surface/Text boxes as the primary map renderer.
```

## 20. Implementation target for next patch

Patch name:

```text
checkpoint after POS floorplan renderer v2 editor primitive parity
```

Minimum target:

```text
1. Preserve all Dashboard properties in POS model or raw metadata.
2. Replace table rendering with Canvas table body + chairs + label.
3. Replace camera rendering with fixed 40px marker/icon in editor position.
4. Keep POS status/bill info as overlay only.
5. Restore natural pan/drag.
6. Keep no sample/static/fake fallback.
```

