# AIROS UI Reference (POS v1) — Based on Provided Screenshots

This reference anchors the POS UI to the existing AIROS Dashboard look seen in the screenshots.
It is intentionally prescriptive so a one-shot Codex build can match the style without extra back-and-forth.

## 1) Global look & feel
- **Dark navy / near-black** app background with subtle top-to-bottom gradient.
- **Soft “glass” cards**: slightly lighter navy surfaces with low-contrast borders.
- **Rounded shapes everywhere** (sidebar items, cards, pills, inputs): 12–18px radius.
- **Subtle glow accents** (thin outline + faint outer glow) used sparingly for “active/OK/AI” states.
- Typography: clean sans (system or Inter-like), moderate letter spacing, no heavy bolding.

## 2) Layout structure
### Left sidebar (persistent)
- Logo + product title at top (“AI Restaurant OS — Dashboard” style).
- Section headings in small caps / muted text (e.g., NAVIGATION, OPERATIO).
- Navigation items as **pill buttons**:
  - default: dark pill, subtle border
  - active: brighter outline/glow (teal/green)
- Keep spacing generous; avoid dense lists.

### Top action strip (right side of header)
- Small **pill toggles** aligned to the right (like “Opas: Päällä”, “Ääni: Pois”, etc.).
- Language switch as two small pills (“FI”, “EN”) at far right.
- Optional slider controls should be compact and not dominate the header.

## 3) Cards & panels
- Cards use:
  - muted title + small subtitle
  - main KPI number large
  - tiny helper labels (“LIVE TREND”, “LAST UPDATE”)
- Charts inside cards: minimal axes, thin lines, muted grid.
- Card header action icons (settings/refresh) in top-right, minimal contrast.

### Accent usage (match screenshot vibe)
- **Teal/green**: success/OK/active selection.
- **Purple**: AI/assistive features badge or toggle.
- **Blue**: neutral interactive highlights.
- Avoid bright reds except for alerts; use muted red line for “reference/threshold” (as in chart).

## 4) Controls patterns (as shown)
- Filter rows as a sequence of **small rounded chips** (ALL, 1Y, 1M, 1W, 24H, CUSTOM).
- Source selectors (Google Reviews, Instagram, Facebook, TikTok, X, Threads) as icon + label chips.
- Primary CTA buttons are compact, rounded, and low-saturation (not neon).

## 5) Staff POS IA (mapping to this style)
### Primary screen: Table Map
- Floor selector (chip or compact dropdown).
- Tables rendered as rounded rectangles/cards on a canvas-like area.
- Table tile shows:
  - label (T1)
  - status badge (FREE/OCCUPIED/DIRTY/RESERVED)
  - open checks count + open total (gross)
- Selection uses teal outline/glow.

### Right-side detail column (optional, matches “Social Buzz” layout)
- Similar to the screenshot’s right column:
  - **Top**: “Payment / Finalize” panel (with CASH/CARD_EXTERNAL)
  - **Middle**: “Check items” panel
  - **Bottom**: “Insights” placeholder (non-AI in v1; keep empty or hidden)
- Keep right column panels stacked with same card styling.

## 6) Components & tech guidance (if UI is implemented)
- React + Tailwind + shadcn/ui is acceptable; implement a **dark theme** matching above.
- Use a **single layout shell**:
  - Sidebar + header + main content grid.
- Keep UI deterministic: render from API state; do not generate UI via AI.
- Prefer:
  - cards (KPI, charts)
  - chip filters
  - right-column panels
  - minimal modals (use 1 modal max at a time)

## 7) Non-goals (v1)
- Full AIROS dashboard parity (all widgets)
- Dense admin screens
- Complex theming system
