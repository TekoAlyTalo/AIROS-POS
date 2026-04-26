# AIROS POS FloorPlan Graphics Translation Notes

Date: 2026-04-27

## Purpose

This document defines how Dashboard FloorPlan visual primitives are translated directly into Android POS Kotlin/Compose Canvas drawing.

This is NOT a runtime intermediate language.
This is NOT a new renderer product.
This is a developer/AI translation guide:

Dashboard/React/CSS says what to draw.
Android POS/Kotlin Canvas draws the same thing with the equivalent draw call.

## Absolute rule

Dashboard editor is the visual geometry truth.

POS renderer must not invent its own object geometry or object style.

If Dashboard object has a visual property, POS model must preserve it even if the renderer does not use it yet.

## Translation model

Example mental model:

Dashboard:
box(x, y, w, h).fill(red).border(1).rounded(14)

Kotlin Canvas:
drawRoundRect(
    color = fill,
    topLeft = Offset(screenX, screenY),
    size = Size(screenW, screenH),
    cornerRadius = CornerRadius(14f)
)

## Coordinate rule

World to screen:

screenX = panX + worldX * zoom
screenY = panY + worldY * zoom
screenW = worldW * zoom
screenH = worldH * zoom

Rotation:

Dashboard:
transform: rotate(deg)

Kotlin:
withTransform({
    rotate(degrees = deg, pivot = center)
}) {
    draw...
}

## Primitive translations

### Rectangle

Dashboard/CSS:
absolute left/top width/height, border-radius 0

Kotlin Canvas:
drawRect(...)
drawRect(..., style = Stroke(...))

### Rounded rectangle

Dashboard/CSS:
border-radius 14px

Kotlin Canvas:
drawRoundRect(..., cornerRadius = CornerRadius(14f))

### Ellipse / round table

Dashboard/CSS:
border-radius 9999px

Kotlin Canvas:
drawOval(...)

### Triangle

Dashboard/CSS:
clip-path polygon(p1 p2 p3)

Kotlin Canvas:
Path:
moveTo(x + width * p1X, y + height * p1Y)
lineTo(x + width * p2X, y + height * p2Y)
lineTo(x + width * p3X, y + height * p3Y)
close()
drawPath(...)

### Door

Dashboard:
DoorPlanSymbol SVG with threshold, swing arc, open leaf, hinge marker

Kotlin Canvas:
draw threshold line
draw swing arc/path
draw open leaf line
draw hinge marker rect

### Camera

Dashboard:
CAMERA_SYMBOL_PX = 40
FixedCameraIcon SVG

Kotlin Canvas:
camera is a fixed-size 40px marker/icon at editor object x/y.
Do not draw camera as a giant coverage circle.
Coverage may be an optional overlay later, but it is not the camera body.

### Chairs / seats

Dashboard functions to port:
getDefaultChairLayout
spreadSeatPositions
getRectFourCenteredSeatMarkers
getTableSeatMarkers

Kotlin:
use the same algorithm and draw seat markers around table body.

## Rendering priority

1. Tables
2. Chairs / chairLayout
3. Camera marker
4. Wall
5. Door
6. Sofa / chair / armchair / bar-counter
7. Floor tiles
8. Labels
9. POS enriched overlays

POS enriched data must be overlay only.
It must not mutate physical editor geometry.

## Gesture rule

Map pan must work.

Tap, drag/pan and pinch zoom must not block each other.

If tap detector and transform detector conflict, split gesture responsibilities so:
- one-finger drag pans the map
- pinch zoom zooms the map
- tap hit testing still selects tables
- overlays do not block pan
