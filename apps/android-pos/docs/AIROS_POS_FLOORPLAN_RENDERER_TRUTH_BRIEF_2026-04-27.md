# AIROS POS FloorPlan Renderer Truth Brief

Date: 2026-04-27

## Current state

Android POS repo:

C:\AIROS code clean\AIROS POS\apps\android-pos

Current branch:

checkpoint/pos-before-transfer-and-customer-display-fix

Current base commit:

2b55bf7 Add POS in-use floor plan viewer

There is currently an uncommitted Patch 1A applied in the working tree.

Patch 1A changed only:

- apps/android-pos/app/src/main/java/com/airos/pos/app/BackendTruthTableRepository.kt
- apps/android-pos/core/model/src/main/kotlin/com/airos/pos/core/model/Models.kt

Patch 1A builds, but runtime renderer is not visually improved.

Runtime observation after Patch 1A:

- app starts
- Table Map opens
- in-use editor/backend map appears
- no sample/static scaffold is visible
- no crash
- renderer is still bad
- zoom is bad
- labels become huge at max zoom
- floor area labels and table/object labels scale badly
- panning/world behavior still feels wrong

## Important reference commits

Latest confirmed good Android POS renderer feel:

ea84146 checkpoint before scanner polish after custom preview success

Current Android POS in-use floor plan viewer:

2b55bf7 Add POS in-use floor plan viewer

Dashboard editor truth:

C:\AIROS code clean\ai-restaurant-demo-dashboard

169f116 checkpoint-after-floorplan-structure-primitives-20260426

Backend truth:

C:\AIROS code clean\ravintola_backend

3a7023b checkpoint-after-floorplan-door-symbol-schema-20260426

0a58cdd checkpoint-after-floorplan-tile-locked-persist-20260426

## Absolute product rule

AIROS POS is truth-based software.

POS must never render:

- SampleData
- fake data
- placeholder truth
- demo restaurant layout
- static scaffold
- invented tables
- invented areas
- fallback floor plans

If authoritative editor/backend in-use floor map is missing or invalid, POS must show an explicit unavailable/error state.

Do not invent a map.

## Main goal

Android POS FloorPlan must render the authoritative floor map created in Dashboard editor.

Editor/backend floor map is the truth.

POS renderer must adapt to editor truth, not the other way around.

## Important mental model

This must be binary:

- authoritative truth exists and is valid -> render it
- authoritative truth missing or invalid -> show explicit error/unavailable state

No soft fake fallback.
No "maybe this is good enough" data.
No silent data loss.

## What is currently wrong

The renderer is not just suffering from a bad zoom constant.

The current renderer still behaves like it is trying to fit editor/backend floor plan data into an old sample/static renderer machine.

Current symptoms:

- zoom feels wrong
- max zoom is not useful
- labels/text become huge blobs at high zoom
- area labels scale badly
- table boxes look crude
- table labels do not fit naturally
- pan can expose too much empty universe
- visual result feels like bitmap zoom / wrong world transform

## Likely root cause

The renderer scales a composed layout subtree.

That makes text, labels, badges and strokes scale with geometry.

Correct renderer architecture should separate:

1. world-space geometry
   - floor tiles
   - walls
   - doors
   - objects
   - table bodies

2. screen-space overlays
   - readable table labels
   - area labels
   - status badges
   - bill labels
   - selection indicators

Geometry may scale with zoom.

Text and strokes must be zoom-aware.

Labels must not become giant when zoomed in.

Lines must not become absurdly thick.

## Data model requirements

POS must preserve editor/backend truth.

Required floor map data:

- map id
- map name
- width
- height
- pxPerMeter
- scaleStatus if available

Required floor tile data:

- id
- label
- x
- y
- width
- height
- shape
- rotation
- locked
- hidden
- areaType
- surfaceMaterial
- triangle point data where present

Required object data:

- id
- type
- label
- x
- y
- width
- height
- rotation
- locked
- hidden

Required table object data:

- tableNumber
- shape
- chairLayout
- capacity
- rotation
- width
- height
- label

Required camera object data as data only:

- cameraId
- coverageType
- linkedTargetType
- linkedTargetId

Do not touch camera preview mechanics.

Required door object data:

- doorHingeSide
- doorSwingDirection

## Parser rules

Parser must not flatten truth.

Do not:

- skip non-rectangle floor tiles
- round Float geometry to Int unless there is a clearly named legacy compatibility field
- lose pxPerMeter
- lose tile shape
- lose tile rotation
- lose locked/hidden
- lose table shape
- lose chairLayout
- lose table rotation
- invent fallback tables
- invent fallback areas
- invent fallback map names as truth

Parser may expose error state if authoritative map is invalid.

## Renderer rules

Remove or fully disconnect runtime use of:

- FloorPlanStaticBounds
- FloorPlanBarBounds
- showStaticScaffold
- FloorPlanBarCounter as fake/sample world
- Rich visual style scaffold note
- demo/fake/static layout machinery

Renderer must use authoritative editor/backend map only.

If no valid map exists, show error/unavailable UI.

## World and navigation rules

The renderer world must be based on editor/backend floor map coordinates.

Do not guess unit conversion.

Inspect Dashboard editor code first:

- FloorPlanCanvas.tsx
- floorPlanTypes.ts
- floorPlanApi.ts

If editor stores x/y/width/height as editor pixels with pxPerMeter metadata, preserve that.

If some values represent meters/cm through API conversion, make conversion explicit and named.

Navigation/default viewport:

- focus real visible content bounds
- do not center into empty canvas universe
- do not allow the map to pan far past last real object
- do not crop real content
- use sane margin around real content

## Good renderer reference

Use ea84146 only as a feel reference.

Borrow only if useful:

- pan feel
- zoom/drag feel
- transform gesture handling
- pan clamp principle
- hit testing principle
- viewport persistence pattern

Do not borrow:

- sample data
- static scaffold
- fake bar/staff zones
- demo layout
- fake table placement

## Do not touch

Do not touch these unless a build-only adapter is absolutely necessary:

- camera preview mechanics
- CHECK flow
- cleaning acknowledgement flow
- transfer flow
- customer display
- scanner
- shift
- seller
- attendance
- ledger
- open bill business logic

## Likely files

Likely Android POS files:

- apps/android-pos/core/model/src/main/kotlin/com/airos/pos/core/model/Models.kt
- apps/android-pos/app/src/main/java/com/airos/pos/app/BackendTruthTableRepository.kt
- apps/android-pos/feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/FloorPlanTableMap.kt
- apps/android-pos/feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/TableMapFeature.kt

Dashboard reference files:

- C:\AIROS code clean\ai-restaurant-demo-dashboard\src\components\editors\floor-plan\FloorPlanCanvas.tsx
- C:\AIROS code clean\ai-restaurant-demo-dashboard\src\components\editors\floor-plan\floorPlanTypes.ts
- C:\AIROS code clean\ai-restaurant-demo-dashboard\src\components\editors\floor-plan\floorPlanApi.ts

Backend reference:

- C:\AIROS code clean\ravintola_backend\app\schemas.py

## Success criteria

Build passes:

.\gradlew.bat :app:assembleDebug

Runtime:

- POS starts
- FloorPlan view uses only authoritative editor/backend in-use floor map
- no sample/static/fake runtime path remains
- zoom feels natural
- labels/text do not become giant at max zoom
- table/object text is readable or intentionally clamped/hidden
- pan/drag works naturally
- map cannot disappear into empty universe
- existing business features remain intact
