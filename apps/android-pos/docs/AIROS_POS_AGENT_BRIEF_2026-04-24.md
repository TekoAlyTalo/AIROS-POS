# AIROS POS Agent Brief — 2026-04-24

**Purpose:** Practical “read this first” brief for Codex and Claude Code before touching AIROS Android POS.

This document is **not** the canonical truth source. It is a compact agent operating brief that points agents toward the current rules, locked state, safe commands, and stop conditions.

---

## 1. Prime AIROS rules

AIROS / AI Restaurant OS goal:

> Build the world’s best restaurant POS without cutting corners.

Working rules:

- Current files on disk are truth.
- Read sources before changing code.
- Do not guess.
- No fake truth.
- One truth source per thing.
- If two parts of the system can disagree about the same core fact, architecture is not ready.
- One package, one mission.
- Narrow, exact changes only.
- No side changes.
- Exact seam first, implementation second.
- Stop reading unrelated files once the exact seam is found.
- Before risky changes, create a git checkpoint.
- Explain in plain language.
- Return concrete validation commands and results.
- Do not silently change scope.
- Do not let Codex and Claude Code modify the same package in the same branch at the same time.

---

## 2. Agent working model

### Codex

Use Codex as the primary implementer for narrow, well-scoped code packages.

Codex must:

- read current files first
- report exact seam
- make only scoped changes
- validate with commands
- report files changed
- report what was intentionally not changed

### Claude Code

Use Claude Code sparingly, mainly for difficult bug analysis or exact seam discovery.

Claude Code should usually be **read-only first** unless explicitly given permission to edit.

### No parallel edits

Never allow Codex and Claude Code to modify the same package in the same branch at the same time.

If one agent is actively editing the Android POS working tree, the other agent may only do read-only review unless explicitly approved.

---

## 3. Repo roots

### Android POS

```powershell
C:\AIROS code clean\AIROS POS\apps\android-pos
```

### Backend

```powershell
C:\AIROS code clean\ravintola_backend
```

### Dashboard

```powershell
C:\AIROS code clean\ai-restaurant-demo-dashboard
```

---

## 4. Current locked Android POS state

### Branch

```text
checkpoint/pos-before-transfer-and-customer-display-fix
```

### Latest locked POS HEAD

```text
bf97d67 Fix CHECK add-product gate and Tables rail navigation
```

### Recent important POS commits

```text
bf97d67 Fix CHECK add-product gate and Tables rail navigation
bc1d7c2 Replace menu place picker popup with table map selection flow
fa1f19e Generalize POS open-bill context publishing by backend table mapping
93fb325 checkpoint before real POS open-bill context publish fix
5b279b2 Show backend review anchor in table map physical field
```

### Tracked working tree after bf97d67

Expected:

```text
nothing to commit (use -u to show untracked files)
```

Untracked files may exist. Do not stage them unless explicitly scoped.

---

## 5. Current locked backend state

### Branch

```text
checkpoint/backend-camera-authoritative-before-v1
```

### Locked backend HEAD

```text
c145a88 Configure SQLite pool settings in runtime DB path
```

### Runtime-authoritative DB path

```text
ravintola_backend\app\db\__init__.py
```

### Not authoritative for runtime DB

```text
ravintola_backend\app\db.py
```

Do not edit `app/db.py` thinking it affects the runtime DB path unless the task explicitly proves that seam.

---

## 6. Canonical docs

Use these as canonical background when needed:

```text
AIROS_CANONICAL_01_FOUNDATIONS_AND_PRODUCT_2026-04-19.md
AIROS_CANONICAL_02_IDENTITY_SHIFT_ATTENDANCE_AND_SELLER_ATTRIBUTION_2026-04-19.md
AIROS_CANONICAL_03_MENU_EDITOR_RECEIPTS_OPENSALE_AND_SYNC_2026-04-19.md
AIROS_CANONICAL_04_TABLEMAP_FLOORPLAN_EDITOR_SCANNER_AND_DEVICES_2026-04-19.md
AIROS_CANONICAL_05_CODEDATA_VOUCHER_LOYALTY_AND_IMAGES_2026-04-19.md
AIROS_CANONICAL_06_THEME_VISUAL_NORTH_STAR_2026-04-19.md
```

Important table-state spec:

```text
C:\AIROS code clean\AIROS POS\apps\android-pos\docs\AIROS_table_state_logic_spec_v1.md
```

Do not rewrite or reorganize canonical docs unless the task is explicitly a docs-structure package.

---

## 7. Current locked POS packages

### Receipt Opened At

Status: committed before current HEAD.

Truth source:

```text
PersistedOpenSale.createdAtEpochMillis
```

Behavior:

- Receipt pane shows `Opened at`.
- Applies to restored old bills and newly created bills.

### Menu place picker removal

Commit:

```text
bc1d7c2 Replace menu place picker popup with table map selection flow
```

Behavior:

- Old Menu/Receipt place picker popup removed.
- New UX:
  - `Uusi lasku`
  - `Lisää paikkaan`
  - `Vaihda paikkaan`
- Place is selected through TableMap/Grid/FloorPlan path, not popup list.

### CHECK add-product gate + warning + Menu ack

Commit:

```text
bf97d67 Fix CHECK add-product gate and Tables rail navigation
```

Behavior:

- If bill/table is in CHECK state, products cannot be added before CHECK acknowledgement.
- Menu/Receipt warning text:

```text
Kuitti on CHECK-tilassa. Kuittaa CHECK ennen tuotteiden lisäämistä.
```

- Button text:

```text
Kuittaa CHECK
```

- CHECK acknowledgement uses existing backend/POS acknowledge path.
- User stays in Menu/Receipt after acknowledgement.
- After acknowledgement, product add works.

### Tables rail navigation restore

Commit:

```text
bf97d67 Fix CHECK add-product gate and Tables rail navigation
```

Behavior:

- Left rail `Tables` button must always open normal TableMap.
- It should restore previous Grid/FloorPlan/viewport as well as possible.
- If perfect restore conflicts with working navigation, working TableMap navigation wins.

---

## 8. Do-not-touch zones unless explicitly scoped

Do not touch these areas unless the package explicitly includes them:

- backend
- camera/WebRTC
- dashboard
- theme
- docs structure
- canonical docs
- FloorMapEditor lifecycle
- CHECK backend truth
- Tables rail navigation
- place-flow
- transfer UI, unless the task explicitly says transfer UI

---

## 9. Safe start commands

Run these first in Android POS packages:

```powershell
cd "C:\AIROS code clean\AIROS POS\apps\android-pos"

git status --untracked-files=no
git diff --name-only
git log --oneline -n 5
```

Before risky changes, create a checkpoint. Use a descriptive name:

```powershell
git tag checkpoint-before-<short-package-name>-20260424
```

or, if a commit checkpoint is required:

```powershell
git add <scoped-files>
git commit -m "checkpoint before <package name>"
```

Only checkpoint scoped files. Do not accidentally stage unrelated noise.

---

## 10. Safe validation commands

### Diff validation

```powershell
git diff --name-only
git diff --check
```

### Build

```powershell
$env:GRADLE_USER_HOME='C:\AIROS code clean\AIROS POS\apps\android-pos\.gradle-user-home'
.\gradlew.bat assembleDebug
```

### Install + launch

ADB serial:

```text
192.168.8.196:41211
```

Commands:

```powershell
adb -s 192.168.8.196:41211 install -r -d "app\build\outputs\apk\debug\app-debug.apk"

adb -s 192.168.8.196:41211 shell am force-stop com.airos.pos.app
adb -s 192.168.8.196:41211 shell monkey -p com.airos.pos.app -c android.intent.category.LAUNCHER 1
```

---

## 11. Required return format for agents

Every implementation or analysis pass should return:

1. Files read
2. Files changed
3. Exact seam
4. Truth source
5. What changed
6. What was intentionally not changed
7. Validation commands and results
8. Runtime checklist
9. Commit hash, if committed

For read-only analysis, also explicitly state:

```text
No files were modified.
```

---

## 12. Stop rules

Stop and report instead of continuing when:

- exact seam is not found
- scope expands beyond the requested package
- backend truth would be guessed locally
- UI would invent business truth
- another agent is already modifying the same package in the same branch
- a change would touch backend, camera, dashboard, theme, or docs without explicit scope
- runtime behavior contradicts source-level assumptions
- validation fails

Plain language stop message should include:

- what was read
- what was found
- why continuing would be unsafe
- what exact decision or file is needed next

---

## 13. Known future packages

These are separate packages. Do not combine them unless explicitly approved.

### TableMap right panel transfer UI cleanup

Goal:

- reduce heavy purple `Siirtotila` UI
- keep transfer semantics
- use selected row highlighting directly
- preserve drag/drop and target-table behavior
- make CHECK + two open bills fit better

Likely file:

```text
feature/tablemap/src/main/kotlin/com/airos/pos/feature/tablemap/TableMapFeature.kt
```

### Docs organization

Potential future structure:

```text
docs/canonical
docs/specs
docs/runbooks
docs/handoffs
docs/adr
docs/generated
docs/index
```

Do not start this unless explicitly scoped.

### FloorMapEditor lifecycle

Separate architecture package.

Locked direction:

- map created != map in use
- backend authoritative floor-map catalog
- explicit `in use / not in use`
- delete/archive policy
- display order
- floor level
- POS caches only real persisted in-use maps
- no sample maps in operational runtime

### Camera/WebRTC

Separate package.

Do not touch while working on CHECK, TableMap transfer UI, Menu/Receipt, or docs briefs unless explicitly scoped.

---

## 14. Current practical rule for TableMap/CHECK

CHECK is backend-authoritative.

POS/UI must not calculate CHECK itself. POS/UI may:

- publish open-bill context
- call existing acknowledge path
- render backend truth
- block UI actions based on backend-derived state

POS/UI must not invent fake CHECK truth locally.

---

## 15. Short agent checklist before editing

Before editing, answer:

```text
What is the exact package?
What files are allowed to change?
What files are forbidden?
What is the truth source?
What is the exact seam?
What validation will prove this?
What runtime test must the user do?
```

If these are unclear, do not edit yet.
