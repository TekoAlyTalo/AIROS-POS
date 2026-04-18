# AIROS POS Core v1 Readiness Audit

Date: 2026-04-18  
Primary repo: `C:\AIROS code clean\AIROS POS`  
Branch context: `backup/identity-users-rights-seller-attribution-before-codex-2026-04-11`  
Mode: read-heavy audit. Production code was not changed.

Two files were already modified in the working tree before this audit:

- `apps/android-pos/app/src/main/java/com/airos/pos/app/AirosPosApp.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/WorktimeAttendanceRepository.kt`

Those files were treated as current disk truth.

## Executive Verdict

AIROS POS Core v1 is not ready for a first paying customer yet. It is closer than a prototype in several important areas, but it still has invoice-blocking gaps in identity, durable sales sync, real payment handling, restaurant scope consistency, shift/accounting truth, and multi-terminal order state.

The right near-term target is a controlled pilot after a focused blocker package, not a commercial sale. The product can be demoed honestly if the demo scope is strict: POS flow, backend menu, local open bills, receipt/QR ledger while online, staff sign-in/NFC-assisted identity as foundation, and attendance continuity. It should not be demoed as a finished restaurant-grade payment, staff, cash-shift, or multi-terminal operating system.

## Readiness Board

| Area | Current evidence | Commercial state | Pilot gate | Paying-customer gate |
| --- | --- | --- | --- | --- |
| App shell and POS workflow | Auth-gated app shell, table map, menu, payment sheet, settings, scanner and shift tabs are wired in `AirosPosApp.kt`. | Credible foundation | Hardware smoke test | Stability, permissions, support path |
| Staff auth and seller identity | POS uses `FakeAuthRepository(SampleData.localAuthStaffRecords())`; backend has real identity staff endpoints. | Not commercially credible | Replace or strictly lock demo staff | Backend-authoritative staff, roles, PIN, NFC, restaurant assignment |
| Attendance/worktime | POS has Room journal/effective state; backend has scoped event ledger and `/api/worktime/events/sync`. | Strong foundation with one integration risk | Parse backend sync results and test offline/online reconciliation | Owner/restaurant/staff scope model and admin lifecycle |
| Menu/catalog | POS fetches and caches backend menu by `restaurant_key`; Dashboard has backend menu editor surface. | Pilot credible | Seed real menu and image URLs reachable from device | Operational menu governance and update observability |
| Open bills | `RoomOpenSaleRepository` gives durable local open sales and table-linked bills. | Single-terminal pilot credible | Device restart smoke test | Multi-terminal conflict model and backend order truth |
| Table/floor truth | Local sample tables plus backend overlay for hardcoded table 1 path. | Demo-only | Scope table claims tightly | Real floor plan, multi-table backend truth, conflict handling |
| Payments | `FakePaymentRepository` records tender choices and finalizes ledger payloads; no acquirer/card terminal integration. | Not sellable as real payment POS | Cash/test-mode only, stated honestly | Card/fiscal/payment-terminal/legal settlement path |
| Sales ledger and receipts | Backend POS ledger persists immutable sales, receipts, QR/public receipt, seller attribution when online. | Good online foundation | Send real restaurant scope; test ledger round trip | Durable offline outbox and report-grade restaurant scope |
| Offline sync | Attendance sync is real; general `SyncCoordinator.scheduleImmediateDrain()` is TODO and queue is in-memory. | Not commercially credible | Persist finalized sale outbox | Durable sync for sales, refunds, voids, shifts, table ops |
| Cash shift | `FakeShiftRepository` and local sample flow. | Not commercially credible | Avoid accounting claims | Backend day close, cash drawer audit, reports |
| Kitchen/order production | `FakeKitchenRepository`; order flow is local demo logic. | Demo-only | Avoid kitchen claims | Backend kitchen tickets and production state |
| NFC identity | Room-backed local NFC identity and backend binding client exist. Sync is best-effort and staff IDs may not match backend staff. | Foundation only | Use known test tags and real staff mapping | Backend-authoritative credential lifecycle and durable retry |
| Reports | Backend has POS reports from sales ledger; Dashboard reads day/seller/product/transaction reports. | Useful if ledger scope is correct | Verify report data from one full sale | Restaurant-scoped reports, Z/day close, tax correctness |
| Scope/tenancy | Attendance/menu use `restaurant_key`; POS ledger uses nullable `restaurant_id`; receipt settings default to `restaurant_id=default`; owner source is null. | Major architecture gap | Single pilot restaurant with explicit config | Owner, restaurant, terminal, staff membership model |
| Device/hardware | Sunmi scanner/printer/cash drawer/customer display abstractions and diagnostics exist. | Potentially credible | Run on target hardware | Device management, failure handling, support docs |

## Files Inspected

### AIROS POS

- `apps/android-pos/app/src/main/java/com/airos/pos/app/AirosPosApp.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/AppContainer.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/WorktimeAttendanceRepository.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/WorktimeAttendanceClient.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/FakeRepositories.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/SampleData.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/BackendMenuRepository.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/RoomOpenSaleRepository.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/BackendTruthTableRepository.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/NfcIdentityRepository.kt`
- `apps/android-pos/app/src/main/java/com/airos/pos/app/RestaurantReceiptSettingsClient.kt`
- `apps/android-pos/core/datastore/src/main/kotlin/com/airos/pos/core/datastore/TerminalPreferencesStore.kt`
- `apps/android-pos/domain/src/main/kotlin/com/airos/pos/domain/Repositories.kt`
- `apps/android-pos/domain/src/main/kotlin/com/airos/pos/domain/AirosPosLedgerContract.kt`
- `apps/android-pos/domain/src/main/kotlin/com/airos/pos/domain/AirosPosLedgerHttpClient.kt`
- `apps/android-pos/domain/src/main/kotlin/com/airos/pos/domain/NfcIdentitySyncClient.kt`
- `apps/android-pos/core/model/src/main/kotlin/com/airos/pos/core/model/Models.kt`
- `apps/android-pos/core/database/src/main/kotlin/com/airos/pos/core/database/AirosPosDatabase.kt`
- `apps/android-pos/core/database/src/main/kotlin/com/airos/pos/core/database/dao/LocalDaos.kt`
- `apps/android-pos/core/database/src/main/kotlin/com/airos/pos/core/database/entity/LocalEntities.kt`
- `apps/android-pos/sync/src/main/kotlin/com/airos/pos/sync/SyncFoundation.kt`
- `apps/android-pos/feature/auth/src/main/kotlin/com/airos/pos/feature/auth/AuthFeature.kt`
- `apps/android-pos/feature/settings/src/main/kotlin/com/airos/pos/feature/settings/SettingsFeature.kt`
- `apps/android-pos/feature/shift/src/main/kotlin/com/airos/pos/feature/shift/ShiftFeature.kt`
- `apps/android-pos/feature/payment/src/main/kotlin/com/airos/pos/feature/payment/PaymentFeature.kt`
- `apps/android-pos/feature/menu/src/main/kotlin/com/airos/pos/feature/menu/MenuFeature.kt`
- `apps/android-pos/feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/TableMapFeature.kt`
- `apps/android-pos/feature/scanner/src/main/kotlin/com/airos/pos/feature/scanner/ScannerFeature.kt`

### Backend

- `app/routers/worktime.py`
- `app/worktime_models.py`
- `app/worktime_service.py`
- `app/schemas.py`
- `app/models.py`
- `main.py`
- `app/routers/identity.py`
- `app/routers/nfc_bindings.py`
- `app/routers/menu_items.py`
- `app/routers/airos_pos_ledger_router.py`
- `app/airos_pos_ledger_service.py`
- `app/airos_pos_ledger_repository.py`
- `app/routers/reports_router.py`
- `app/routers/restaurant_settings_router.py`
- `app/restaurant_settings_service.py`
- `app/schemas/airos_pos_ledger_schema_v1.sql`
- `migrations/versions/20260418_0012_add_scoped_worktime_event_ledger.py`

### Dashboard And Strategic Context

- `src/api/usersRights.ts`
- `src/api/worktime.ts`
- `src/api/reports.ts`
- `src/domain/restaurantSettingsStore.ts`
- `src/components/worktime/WorktimeView.tsx`
- `src/components/reports/ReportsView.tsx`
- `src/components/settings/ReceiptEditorView.tsx`
- `src/components/settings/MenuEditorView.tsx`
- `C:\TekoAlyTalo\AIROS Documentation\AIROS_POS_NFC_Worktime_SaleReadiness_Handoff_2026-04-11.md`
- `C:\TekoAlyTalo\AIROS - AI Restaurant Operating Sys.txt`

## What Is Already Commercially Credible

The strongest parts are not fake. They are real foundations that can support a serious pilot if the product claims are narrow.

1. POS navigation and daily flow have a usable shape. The app has sign-in, table map, menu, payment, shift, scanner, and settings surfaces in one Android POS shell.

2. Menu consumption is materially beyond mock-only. `BackendMenuRepository` fetches backend menu items by restaurant key, caches them in Room, and exposes cache/fresh/no-data states. Dashboard `MenuEditorView.tsx` edits backend menu items and sends `x-airos-restaurant-key`.

3. Open bill continuity is credible for a single terminal. `RoomOpenSaleRepository` persists open sales and lines locally, so table bills are not just memory state.

4. Attendance has a strong offline-first design. The POS has local attendance events, active-session effective state, sync metadata, terminal sequence numbers, and reconciliation states. The backend has a scoped event ledger, terminal sync state, idempotent event handling, and restaurant-scoped active sessions.

5. Backend sales ledger has serious pieces. It can persist finalized sales, receipt snapshots, payments, seller attribution, QR/public receipt tokens, and report data when online.

6. Seller attribution is wired into the sales payload. `FakePaymentRepository.finalizeTablePayment` requires a signed-in staff identity and maps staff/session/auth metadata into the POS ledger request.

7. Receipt customization is not just UI fantasy. The dashboard restaurant settings store uses backend persistence, and the backend exposes POS receipt settings. The POS has a receipt settings client with cache fallback.

8. Device readiness has real work behind it. Scanner, printer, cash drawer, customer display, and NFC paths are represented with services and diagnostics instead of being only marketing copy.

## What Is Technically Working But Not Yet Commercially Credible

1. Staff identity is still local demo truth in the POS. `AppContainer.kt` wires `FakeAuthRepository(SampleData.localAuthStaffRecords())`. `SampleData.kt` contains hardcoded staff and PINs. Backend identity exists, but POS login does not consume it.

2. PIN/security is not production-grade. POS-side staff PINs are plain local sample values. Backend has hashed PIN creation, but POS is not wired to a synced or server-backed credential model.

3. NFC identity is a local foundation, not a commercial credential system. Local NFC enrollments and events are Room-backed, but backend binding sync is best-effort and not durably retried. Current POS sample staff IDs can differ from backend seeded staff IDs.

4. General offline sync is not real yet. `InMemorySyncQueueRepository` loses queued items on process death, and `SyncCoordinator.scheduleImmediateDrain()` still contains a TODO. That matters especially for sales, payments, shifts, table changes, and kitchen events.

5. Sales finalization is only online-grade. If ledger submission fails with a network error, payment finalization can continue locally and enqueue `ledger_finalize`, but that queue is in-memory. This is not acceptable for a sellable offline-first POS.

6. Payments are simulated tender selection, not payment processing. Card/cash/voucher buttons record the chosen tender and amounts. There is no card terminal/acquirer/fiscal/legal integration in the inspected POS path.

7. Restaurant scope is inconsistent across domains. Attendance and menu use `restaurant_key`; the POS ledger request currently gets `restaurantIdProvider = { null }`; receipt settings default to `restaurant_id=default`; owner account source is explicitly null in `AppContainer.kt`.

8. Table truth is partly hardcoded. `BackendTruthTableRepository` bridges backend truth for the hardcoded backend table id `1` path, while the rest of the floor map is local sample truth.

9. Cash shift is not commercial accounting truth. `FakeShiftRepository` is still used. That is fine for UI flow but not for cash/day-close accountability.

10. Kitchen/order production state is not commercial truth. `FakeKitchenRepository` remains local demo behavior.

11. Android attendance sync does not yet inspect primary sync result semantics deeply enough. The backend `/events/sync` returns per-event result statuses, but the Android client treats any 2xx primary response as delivered. A backend-rejected stale/duplicate sequence could be marked synced locally unless the client parses the response.

12. Dashboard worktime is legacy online flow. `WorktimeView.tsx` still calls direct clock-in/clock-out/break endpoints and polls every 30 seconds. That does not block POS Core by itself, but it is not the same offline-first event-ledger UX the POS now has.

## Pilot Blockers

These are must-have before a serious first pilot where the user can stand beside the product without explaining away core behavior.

1. Replace or hard-lock sample staff identity. A pilot cannot rely on visible hardcoded demo staff/PINs as the operational staff source.

2. Make finalized sale offline sync durable. The POS must persist unsent finalized sale ledger events across app restart and retry idempotently. Attendance already has the right pattern; sales do not.

3. Wire restaurant scope consistently for POS Core. At minimum, use the configured terminal restaurant scope coherently for menu, attendance, sales ledger, receipt settings, and reports in a single-restaurant pilot.

4. Parse backend attendance sync results in Android. A 2xx response with a rejected event must not be treated as a fully synced event.

5. Define the pilot payment story honestly. If there is no real card terminal integration, pilot must be cash/test tender only and sold as such.

6. Run a full hardware smoke test on the actual target terminal. Required path: sign in, NFC scan if used, menu load, open bill, edit quantity/discount if in scope, finalize payment, receipt print/QR, report visibility, clock in/out offline and online.

7. Scope table/AI claims tightly. The current backend table truth path is not a complete multi-table commercial feature.

8. Protect terminal settings enough for a pilot. Backend URL, restaurant key, direct NFC login, and local enrollment controls should not be casually editable by any staff member during a real pilot.

## Paying-Customer Blockers

These are first-invoice blockers. Without them, the product may be interesting but not commercially credible as a restaurant POS.

1. Real payment integration and legal settlement story. The inspected POS does not provide a production card terminal/acquirer/fiscal path.

2. Durable, idempotent sync for all money-affecting operations. Sales, refunds, voids, discounts, cash drawer events, shifts, and day close need durable local outbox plus backend reconciliation.

3. Backend-authoritative staff identity in POS. Staff creation, roles, enabled/disabled state, permissions, PIN lifecycle, NFC lifecycle, and session attribution must be one domain truth.

4. Restaurant/owner/terminal scope model. Current backend has useful `restaurant_key` fields, but no complete owner/account/multi-restaurant membership model is wired into POS. Staff are not scoped to restaurants in the inspected identity model.

5. Multi-terminal order and open-bill truth. A sellable restaurant POS cannot assume one terminal per restaurant or one local open-bill writer.

6. Cash shift/day close. Cash drawer totals, expected cash, corrections, staff handover, Z/day reports, and audit trail must be backend-authoritative.

7. Refunds, voids, corrections, and receipt compliance. The inspected path is strongest on positive sale finalization; commercial operation needs the negative/correction paths too.

8. Report-grade restaurant scoping. Backend POS reports operate over the sales ledger, but the ledger receives nullable restaurant id from Android today. Reports cannot become commercial management truth until scope is correct.

9. Security posture. Settings access, local PIN storage, device enrollment/revocation, audit logs, and offline auth policy need production decisions.

10. Operational support basics. Update/migration path, backups, logs, failure diagnostics, and recovery playbooks are needed before a customer depends on it.

## Deferrable Later Modules And Features

These are valuable, but they should not distract from POS Core v1 blockers.

1. Break/resume worktime depth beyond basic attendance.

2. Advanced AI table occupancy, queue prediction, and camera automation beyond a constrained demo.

3. Full loyalty/customer NFC beyond receipt handoff foundation.

4. Inventory automation beyond ledger outbox/reporting hooks.

5. Mobile ordering, reservations, customer chat, voice, social buzz, energy, cold devices, and autopilot.

6. Deep analytics beyond day/seller/product reports needed to validate first POS operation.

7. Multi-country compliance until Finland-first POS Core is stable.

## Strongest Justified Product Claims Today

These are safe internal/external claims if worded carefully and demoed within the real scope.

1. AIROS POS Core has an Android POS shell with staff sign-in, table/open-bill workflow, backend menu sync/cache, scanner support, payment flow, receipt generation, QR/e-receipt path, and attendance.

2. Attendance is built on an offline-first event journal with terminal sequence metadata and backend reconciliation.

3. Menu data can be managed in backend/dashboard and consumed by POS through restaurant-scoped menu fetch with local cache fallback.

4. Online sale finalization can write seller-attributed receipts into a backend POS ledger and expose reports from that ledger.

5. The terminal has persistent installation identity and configurable backend/restaurant settings.

6. NFC is a meaningful identity and receipt-handoff foundation, not a payment transport.

Claims to avoid today:

- Do not claim production-ready real card payments.
- Do not claim full multi-terminal order truth.
- Do not claim backend-authoritative staff identity in POS.
- Do not claim owner-scoped multi-restaurant tenancy.
- Do not claim complete offline-first sales sync.
- Do not claim cash/accounting/day-close readiness.

## Recommended Wiring Path

1. Staff identity first. Wire POS auth to backend staff profiles or a durable synced staff cache with hashed/local offline policy. Keep active seller separate from attendance/work session.

2. Sales outbox next. Reuse the attendance journal pattern for finalized sales: event id, terminal sequence, restaurant scope, status, retry, per-terminal metadata, backend idempotency by event id/receipt number.

3. Scope unification. Decide and wire a single pilot scope mapping: terminal settings restaurant key to backend restaurant id/key for menu, attendance, receipt settings, sales ledger, and reports. Do not invent owner account truth.

4. Attendance result parsing. Make Android parse `/api/worktime/events/sync` per-event results and keep rejected/stale events visible as blocked instead of silently synced.

5. Payment boundary. Either integrate a real payment terminal path or explicitly mark POS Core v1 pilot as cash/test tender only. Do not blur that line.

6. Cash shift and reports. Turn fake shift state into backend day/cash truth before asking a customer to trust daily totals.

## What Should Not Be Changed

1. Do not turn POS Core v1 into the full AIROS restaurant operating system launch. The first commercial product should be POS-first.

2. Do not broaden into TableMap AI, inventory, energy, customer chat, social buzz, or autopilot before the POS money/identity/sync blockers are closed.

3. Do not treat active seller as attendance/work session. These are separate domain concepts.

4. Do not invent `owner_account_id` locally. Owner scope needs a real backend/domain source.

5. Do not claim offline-first for sales while the sale outbox is in-memory.

6. Do not sell table truth as multi-terminal/multi-table complete while backend integration is hardcoded around one table path.

7. Do not remove or refactor dead code just to make the repo look cleaner during launch readiness work.

## Validation Notes

No build or test suite was run for this audit because the requested task was read-heavy analysis and the only intended repository change was this markdown artifact. The current audit conclusion is based on source inspection, not a fresh runtime validation.

## Plain Language Summary

AIROS POS has real product value already: the daily POS flow is visible, menu and receipts have backend paths, open bills persist locally, attendance has a serious offline-first architecture, and seller attribution exists in the sales ledger path.

The first invoice should wait. The risky parts are exactly the parts a restaurant will depend on every day: real staff identity, real payment handling, durable offline sales sync, cash/day close, coherent restaurant scope, and multi-terminal truth. Fix those in a focused sequence and AIROS POS Core v1 can become a credible first commercial product instead of a strong demo with too many explanations.

