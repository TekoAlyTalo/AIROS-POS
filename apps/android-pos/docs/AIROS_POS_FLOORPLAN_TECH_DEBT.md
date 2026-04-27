# AIROS POS FloorPlan Tech Debt

Date: 2026-04-27

## Rules

This file records old or conflicting architecture found during renderer work.

If old architecture is known and not fixed immediately, record it here.

## Open items

- [ ] FloorPlan renderer visual parity
      File: feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/FloorPlanTableMap.kt
      Problem: Renderer v1 is functional but visually not editor-parity.
      Why it matters: POS must render Dashboard/editor map in the same scale and geometry.
      Blocks current patch: Yes, for renderer v2.
      Proposed fix: Translate Dashboard visual primitives directly to Kotlin Canvas draw calls.

- [ ] POS enriched data separation
      File: feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/FloorPlanTableMap.kt
      Problem: POS overlays and physical geometry are not cleanly separated everywhere.
      Why it matters: status, bills and selection must not mutate editor geometry.
      Blocks current patch: Partially.
      Proposed fix: Draw physical floorplan first, then draw POS overlays.

- [ ] Camera marker parity
      File: feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/FloorPlanTableMap.kt
      Problem: Camera objects render too large / wrong style compared to Dashboard editor.
      Why it matters: camera marker is an editor object, not a coverage blob.
      Blocks current patch: Yes.
      Proposed fix: Render camera as fixed-size editor-style marker using object x/y/rotation.

- [ ] Chair layout parity
      File: feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/FloorPlanTableMap.kt
      Problem: Dashboard chairLayout algorithm is not fully ported to POS.
      Why it matters: tables must preserve editor seating model.
      Blocks current patch: Yes.
      Proposed fix: Port getDefaultChairLayout, spreadSeatPositions, getRectFourCenteredSeatMarkers and getTableSeatMarkers to Kotlin.
