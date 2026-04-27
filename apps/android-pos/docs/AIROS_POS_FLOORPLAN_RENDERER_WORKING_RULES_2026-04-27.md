# AIROS POS FloorPlan Renderer Working Rules

Date: 2026-04-27

## POS UI = render only

Android POS UI must be render-only.

The UI must not:
- invent data
- repair backend truth silently
- create fallback floor maps
- create sample/static/fake layouts
- decide business truth
- mutate physical editor geometry based on POS status

The UI may:
- render authoritative floor map model
- render POS enrichment as overlays
- show explicit unavailable/error state when authoritative data is missing or invalid

POS enrichment must be overlay-only:
- status
- selected state
- open bill labels
- cleaning/check flags
- camera preview indicators

Enrichment must not change physical editor geometry.

## Scale rule

POS must preserve Dashboard/editor scale.

Editor/backend map coordinates and pxPerMeter are the coordinate truth.

Zoom is only a screen magnifier.

Correct order:

1. editor/backend geometry
2. scale / pxPerMeter interpretation
3. world coordinates
4. pan + zoom to screen
5. draw

Do not guess object sizes in the renderer.

## File modification rule

Before modifying a source file:

1. Read the whole file.
2. Identify current architecture in the file.
3. If old or conflicting architecture is found, report it before patching.
4. Decide whether to fix now or record as tech debt.
5. Patch only after the scope is understood.

## Current renderer direction

Renderer v2 must translate Dashboard visual primitives directly to Kotlin/Compose Canvas equivalents.

Examples:
- CSS rectangle -> drawRect
- CSS rounded rectangle -> drawRoundRect
- CSS ellipse / round table -> drawOval
- CSS clip-path polygon -> Path + drawPath
- CSS rotate -> withTransform / rotate
- Dashboard chairLayout -> same seat marker algorithm in Kotlin
- Dashboard camera marker -> small fixed-size camera marker, not a giant coverage circle
