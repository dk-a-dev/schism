# Schism Design System

A warm, editorial "quiet luxury" system: a cream paper canvas, a deep-green
brand with a mint accent, terracotta for money owed and amber for highlights.
Serif display type over a grotesk body, hairline dividers instead of heavy
borders, soft pill/rounded shapes, and **full light/dark parity** — every token
below has a value for both modes.

This document is the source of truth for the visual language. It maps onto the
Compose theme layer at `schism-android/app/src/main/java/ai/schism/split/core/theme/`
(`Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`) so implementation is a
token-for-token translation.

---

## 1. Principles

1. **Quiet luxury, both ways.** Restrained, warm neutrals; color earns its place.
   Light and dark are equal first-class modes, not an afterthought.
2. **Hairlines over borders.** Separate content with 1px hairline dividers and
   surface elevation, not boxy outlines. Cards are defined by fill + radius +
   the faintest shadow, not strokes.
3. **Type does the work.** A serif display face carries hierarchy and
   personality; the body face stays neutral and legible. Big numbers (balances)
   are the hero of most screens.
4. **Money has color meaning.** Green = you're owed / positive / primary action.
   Terracotta = you owe / negative. Amber = suggestion / attention.
5. **Soft geometry.** Pills for actions, circles for avatars, large 30px radii
   for cards. Nothing sharp.

---

## 2. Color

Values are the raw palette. Names are semantic roles — use the role, not the hex,
in code.

### Light mode

| Role | Hex | Use |
|------|-----|-----|
| `canvas` | `#FBFAF4` | App background (warm off-white paper) |
| `surface` | `#F1EFE7` | Cards, sheets, raised containers |
| `surfaceMuted` | `#ECEBE6` | Recessed fills, input backgrounds, chips |
| `hairline` | `#E3E0D5` | Dividers, 1px separators |
| `hairlineSoft` | `#EAE7DC` | Even fainter dividers on `surface` |
| `inkPrimary` | `#1A1A16` | Primary text, headings |
| `inkStrong` | `#0F0F0E` | Max-contrast text / display numerals |
| `inkSecondary` | `#605F58` | Secondary text, supporting copy |
| `inkTertiary` | `#8C8B84` | Muted labels, timestamps |
| `inkFaint` | `#9A998E` | Placeholders, disabled text |
| `white` | `#FFFFFF` | On-accent text, elevated surfaces |

### Dark mode

| Role | Hex | Use |
|------|-----|-----|
| `canvas` | `#0F0F0E` | App background |
| `canvasDeep` | `#0C0C0A` | Behind sheets / scrims |
| `surface` | `#1A1A16` | Cards, sheets |
| `surfaceMuted` | `#201F1D` | Recessed fills, inputs |
| `surfaceRaised` | `#242320` / `#2A2926` | Elevated / hovered surfaces |
| `hairline` | `rgba(255,255,255,0.09)` | Dividers |
| `hairlineStrong` | `rgba(255,255,255,0.16)` | Emphasised dividers |
| `inkPrimary` | `#ECEBE6` | Primary text |
| `inkSecondary` | `#B7B6AC` | Secondary text |
| `inkTertiary` | `#8C8B84` | Muted labels |

### Brand & semantic accents (shared, tuned per mode)

| Role | Light | Dark | Use |
|------|-------|------|-----|
| `primary` (green) | `#14874F` | `#6FE0A6` | Primary buttons, brand, "you're owed" |
| `primaryBright` (mint) | `#6FE0A6` | `#6FE0A6` | Accent fills, active states, chips |
| `primaryDim` | `#3E8F6A` / `#4FA97E` | `#5FC493` | Pressed / secondary green |
| `primaryTint` (surface) | `#DCEFE2` | `rgba(111,224,166,0.12)` | Positive-balance surfaces, badges |
| `negative` (terracotta) | `#BC5533` | `#E8987A` | "You owe", errors, destructive |
| `negativeTint` | `#F6E1D7` | `#2A1215` / `#5C2B2E` | You-owe surfaces / error containers |
| `attention` (amber) | `#9A7A2E` | `#D8BC84` | Suggestions, warnings, highlights |
| `attentionTint` | `#F0E6CE` | `rgba(154,122,46,0.20)` | Suggestion surfaces |

### Overlays & scrims

- Pressed/hover on light: `rgba(0,0,0,0.04)` → `0.06` → `0.08`.
- Pressed/hover on dark: `rgba(255,255,255,0.07)` → `0.09` → `0.12`.
- Modal scrim: `rgba(0,0,0,0.35)`.

---

## 3. Typography

Four families plus an icon font. Load `Instrument Serif`, `Hanken Grotesk`,
`Space Grotesk`, `Space Mono`, and `Material Symbols Rounded`. Fallbacks:
system-ui / sans-serif / serif / monospace.

| Family | Role |
|--------|------|
| **Instrument Serif** | Display & hero — screen titles, big balance numerals, empty-state headings. |
| **Hanken Grotesk** | Body & UI — paragraphs, list rows, buttons, supporting text. Weights 400/500/700. |
| **Space Grotesk** | Labels & eyebrows — small all-caps section labels, nav labels, tab labels. |
| **Space Mono** | Numeric & code — inline amounts in dense lists, IDs, invite codes. |
| **Material Symbols Rounded** | All iconography. |

### Type scale

Sizes present in the kit: `10, 11, 12, 13, 14, 15, 16, 18, 21, 22, 24, 26, 30, 34, 48, 64, 68`px.
Suggested named roles:

| Token | Font | Size / Weight | Letter-spacing | Use |
|-------|------|---------------|----------------|-----|
| `displayHero` | Instrument Serif | 64–68 / 400 | −2px | Hero balance on dashboard |
| `displayLarge` | Instrument Serif | 48 / 400 | −1px | Big screen numbers / totals |
| `displayMedium` | Instrument Serif | 30–34 / 400 | −1px | Screen titles, empty-state headings |
| `titleLarge` | Hanken Grotesk | 24 / 700 | −0.5px | Section headers, group names |
| `titleMedium` | Hanken Grotesk | 21–22 / 600 | −0.5px | Card titles, row headlines |
| `bodyLarge` | Hanken Grotesk | 16 / 500 | normal | Primary body |
| `bodyMedium` | Hanken Grotesk | 14–15 / 400 | normal | Supporting body |
| `bodySmall` | Hanken Grotesk | 13 / 400 | normal | Captions |
| `label` | Space Grotesk | 12 / 600 | +1px, UPPERCASE | Section eyebrows, nav |
| `labelSmall` | Space Grotesk | 10–11 / 600 | +2px, UPPERCASE | Micro labels, tags |
| `mono` | Space Mono | 13–14 / 400 | normal | Inline amounts, codes |

Weights in use: 400 (regular), 500 (medium), 600 (semibold), 700 (bold).
Rule of thumb: **negative letter-spacing scales up with display size; positive
letter-spacing on small caps labels.**

---

## 4. Shape (corner scale)

| Token | Radius | Use |
|-------|--------|-----|
| `xs` | 6–8px | Inputs, small chips, inline tags |
| `sm` | 12px | Compact controls |
| `md` | 16–18px | Buttons (non-pill), small cards, list items |
| `lg` | 22–26px | Standard cards, sheets |
| `xl` | 30px | Hero cards, bottom sheets, primary containers |
| `pill` | 100px | Pill buttons, segmented controls, chips |
| `circle` | 50% | Avatars, icon buttons, FAB |

Default card radius is **30px** (`xl`); actions are **pill** (100px).

---

## 5. Elevation & shadows

Soft, low-contrast shadows only — never harsh drop shadows.

| Token | Value | Use |
|-------|-------|-----|
| `hairlineShadow` | `0 1px 4px rgba(0,0,0,0.08–0.12)` | Sticky bars, nav bar top edge |
| `raised` | `0 4px 14px rgba(0,0,0,0.04)` | Resting cards |
| `card` | `0 6px 20px rgba(0,0,0,0.08)` | Emphasised cards |
| `primaryGlow` | `0 10px 24px rgba(20,135,79,0.22)` | Primary green button/FAB |
| `modal` | `0 30px 70px rgba(0,0,0,0.35)` | Dialogs, bottom sheets |

In dark mode, lean on **surface steps** (`surface` → `surfaceRaised`) more than
shadow for elevation.

---

## 6. Spacing

Base grid ≈ 2px; the kit uses `6, 8, 10, 12, 14, 16, 18, 22, 24, 26, 32, 36, 40, 48`.
Named scale:

| Token | px |
|-------|----|
| `space1` | 6 |
| `space2` | 8 |
| `space3` | 12 |
| `space4` | 16 |
| `space5` | 24 |
| `space6` | 32 |
| `space7` | 40 |
| `space8` | 48 |

- Screen edge padding: **16px** (compact) to **36–40px** (hero/onboarding).
- Card inner padding: **18–24px**.
- Row gap in lists: **6–14px**.

---

## 7. Components

### Buttons

Three tiers, all **pill** (100px), default padding `12px 24px` (`~11px 23px` at rest):

- **Primary** — `primary` green fill, `white` label, `primaryGlow` shadow. The
  single most important action per screen (Add expense, Get started, Settle up).
- **Secondary** — `surfaceMuted` tonal fill, `inkPrimary` label, no shadow.
- **Text** — no fill, `primary` or `inkSecondary` label. Low-emphasis / inline.

Disabled: drop to `surfaceMuted` fill + `inkFaint` label (see onboarding
"Get started" resting state).

### Cards

`surface` fill, `xl` (30px) radius, `raised`/`card` shadow, `18–24px` inner
padding. No border. Group content with hairline dividers between rows.

### Nav bar (bottom)

Icons + Space Grotesk labels for the top-level tabs. Active item uses `primary`;
inactive uses `inkTertiary`. Sits on `canvas` with a `hairlineShadow` top edge.
Tabs seen in the kit: home / groups / inbox / settings / assistant.

### List rows (expenses, activity, balances)

- Leading circular avatar (initials, deterministic color).
- Title in `titleMedium`; supporting meta (`bankName · date`) in `bodyMedium`
  `inkSecondary`.
- Trailing amount in `mono` or `titleMedium`, colored: `primary` when you're
  owed, `negative` when you owe, `inkPrimary` when neutral.
- Hairline divider between rows, not per-row cards, in dense lists.

### Balance / hero number

Instrument Serif `displayHero`, colored by sign (`primary` positive,
`negative` owed), with a small `label` eyebrow above ("WHAT DO I OWE?",
"YOU OWE", "GET BACK").

### Chips / tags

`pill` radius, `surfaceMuted` or tinted (`primaryTint` / `negativeTint` /
`attentionTint`) fill, `labelSmall` text.

### Avatars

Circle, deterministic tint from name/id (already implemented as
`core/ui/InitialAvatar`). Reuse across groups, participants, activity.

---

## 8. Iconography

Material Symbols Rounded throughout (rounded, filled for active states). Keep
optical sizes consistent within a surface. Icon-only buttons are circular with
a transparent-to-`surfaceMuted` pressed state.

---

## 9. Compose implementation map

When implementing (after the AI features land), translate token-for-token:

- **`Color.kt`** — define two palettes (light/dark) with the semantic role names
  from §2. Wire them into `lightColorScheme`/`darkColorScheme`, mapping:
  `primary`→primary, `primaryTint`→primaryContainer, `negative`→error,
  `canvas`→background, `surface`/`surfaceMuted`→surface/surfaceVariant,
  `ink*`→onBackground/onSurface/onSurfaceVariant, `hairline`→outlineVariant.
  Keep the extra roles (`attention`, `primaryBright`, tints) as a companion
  `SchismColors` object exposed via a `CompositionLocal`, since M3's scheme
  doesn't have slots for all of them.
- **`Type.kt`** — register the four font families (bundle the TTFs under
  `res/font/`); map the §3 roles onto `Typography` (display*/title*/body*/label*)
  and expose `mono` + `displayHero` as extras.
- **`Shape.kt`** — set `Shapes(small=8, medium=16, large=30)`; add `pill` (100)
  and `circle` helpers as constants.
- **`Theme.kt`** — select light/dark via existing `ThemeMode`; provide the
  `SchismColors` local; set shadows via `Modifier.shadow`/tonal elevation per §5.
- Prefer hairline `Divider(color = hairline)` over `Card` borders; default cards
  to 30px radius + `surface` container + soft shadow.

Design principle to preserve in code: **light and dark must reach the same level
of polish** — test every screen in both before considering it done.
