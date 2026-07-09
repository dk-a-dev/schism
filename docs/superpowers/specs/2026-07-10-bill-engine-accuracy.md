# Bill Scanning — Accuracy Overhaul (OCR → deterministic engine → AI fallback)

Grounded in a code-verified investigation of the current pipeline (2026-07-10). Goal: make bill
scanning "crazy accurate" on real Indian thermal POS receipts (the Olive Street Cafe bill is the
canonical failing case), generalising — NOT overfitting to samples.

## Canonical failing bill (ground truth)

Olive Street Food Cafe, Bangalore. Header prints `No.Item  Qty.  PriceAmount` (Price+Amount fused,
no gap). Item names wrap DOWNWARD across 2-4 lines; the numeric triple sits on the FIRST line.

| # | Item | Qty | Unit Price | Amount |
|---|------|-----|-----------|--------|
| 1 | Egg & Sausage Blast Roll | 1 | 149.00 | 149.00 |
| 2 | Jumbo King Roll | 3 | 149.00 | 447.00 |
| 3 | Chicken Lahori Roll | 1 | 89.00 | 89.00 |
| 4 | Bombay Parotta Roll | 1 | 99.00 | 99.00 |
| 5 | Lime Juice | 3 | 35.00 | 105.00 |
| 6 | Water Melon | 1 | 55.00 | 55.00 |

Totals: `Total Qty: 10` | `Sub Total 944.00` | `SGST 2.5% 23.60` | `CGST 2.5% 23.60` |
`Round off -0.20` | `Grand Total ₹991.00`.
Self-checks (all must hold): Σamount=944.00=SubTotal · Σqty=10=TotalQty · qty×price=amount each row ·
SubTotal+SGST+CGST+Roundoff=GrandTotal.

## User-reported symptoms (all must be fixed)

- (a) SKIPS dishes — multi-line wrapped names.
- (b) SKIPS taxes sometimes.
- (c) `99.00` becomes `9900` (100× — decimal dropped).
- (d) Price & Amount columns too close → conflated/misassigned (PRIMARY defect).
- (e) Multi-line item-name grouping wrong.
- (f) QUANTITY missed and not shown properly (after OCR and after AI).
- (g) Tax breakdown not shown — real Indian bills have 2-4 tax lines (SGST/CGST/service charge/cess);
      each should be shown separately AND split across the people on it.
- (h) Per-item display: show `qty × unit price = amount`, not just a lump amount.
- (i) Claim expense detail shows no item list / who-ate-what preview.

## Pipeline (code-verified)

- OCR: **ML Kit** on-device Latin recognizer, `sms/receipt/ReceiptScanner.kt:32-48`. Bounding boxes
  ARE preserved: each ML Kit *line* → `Cell(text,left,right,centerY)` (line 41-43). **Uses line-level
  boxes, not word/element-level.**
- cells→rows: `groupIntoRows` `engine/Geometry.kt:13-25` clusters by yCenter within 0.6×medianHeight.
- Orchestration: `sms/itemized/BillScan.kt:50-80` (+ duplicate in `sms/inbox/InboxViewModel.kt:107`):
  runs `parseBill(rows)`; ONLY if `draft==null || !draft.verified` calls LLM
  `llmParser.parseReceipt(rows.map{it.text})` — **geometry discarded when handed to the LLM**.
- Engine: `engine/BillParser.kt:195 parseBill` = template-normalize → `detectColumns` → `segment` →
  `extractItems` → `readTotals` → `reconcile`. Layers: Geometry, Columns, Regions, Numbers,
  BillParser, Totals, Solver, Templates.
- LLM: `core/ai/LlmExpenseParser.kt:107-134` MediaPipe on-device, gated on `settings.aiEnabled`.
- UI: `sms/itemized/ItemizedSplitViewModel.kt:99-101` → `items=draft.lineItems`, tax distributed
  proportionally (lines 67-72). `sms/itemized/ItemizedSplitScreen.kt` item cards.

## Root causes (file:line)

- **(d) PRIMARY:** header token `PriceAmount` is one fused OCR token; `keywordRole` uses `\b`-anchored
  `RATE_KEYWORDS`/`AMOUNT_KEYWORDS` (`Columns.kt:11-14`) so neither matches inside `PriceAmount` →
  no role. Gap clusterer `gapThreshold=maxX/25` (`Columns.kt:62-73`) merges the two close money
  columns → structural fallback labels the single merged cluster AMOUNT (`Columns.kt:131-137`); no
  RATE column. `Row.cellIn` returns leftmost (`Columns.kt:172`, rows sorted by xLeft
  `Geometry.kt:24`) → for qty>1 returns the PRICE (149) not AMOUNT (447). `resolveQtyAndAmount`
  self-correction needs all 3 of {qty,rate,amount}; with RATE gone it can't fire (`BillParser.kt:91`).
  → Σitems≈576 vs 944 → fails reconcile → LLM. **The qty×price=amount invariant that would catch
  this is unreachable because the RATE column was destroyed.**
- **(a)/(e)/(f):** `extractItems` (`BillParser.kt:142-162`) only accumulates PRECEDING letters-only
  rows (`wrappedNamePrefix`) and assumes name-ABOVE-price. Thermal bills put the numeric triple on
  the FIRST line and wrap name DOWNWARD → trailing fragments prepend to the NEXT dish, corrupting
  names and dropping rows via `!looksLikeName`/`amountMinor<=0` guards (`BillParser.kt:155,159`).
  No mechanism to attach FOLLOWING name lines to the just-emitted item.
- **(c):** OCR drops faint thermal decimal → token `"9900"`; `parseMinor` (`Numbers.kt:30-36`) sees
  no `.`, treats as whole rupees → ×100. Deliberate (doc `Numbers.kt:20-24`). Nothing corrects after.
- **(b):** mostly a consequence of (d) failing the whole bill to the LLM (which loses geometry).
  Latent: `parseMinor("2.5%")`=250 (`cleanNumeric` strips `%`, `Numbers.kt:12-13,26-37`) so a `%`
  token counts as money in `Regions`/`Columns`, mis-shaping percentage tax rows.
- **Solver only VERIFIES:** `reconcile` (`Solver.kt:44-64`) checks invariants, sets `verified`
  boolean (`Solver.kt:61`); never mutates amounts. Only corrector is per-row `resolveQtyAndAmount`
  which needs all 3 cells.

## Fix plan (ranked; deterministic unless noted)

1. **Restore Price/Amount split from a fused header token** (`Columns.kt`). Split a fused header cell
   (substring-scan for both `price` AND `amount`, not `\b`) and assign RATE+AMOUNT to the two nearest
   data clusters; when the header is ambiguous, seed two money columns from the **two rightmost money
   cells per data row**. Fixes (d); unblocks the qty×price invariant; stops cascade into (a)/(b)/(f).
2. **Read amount by the arithmetic invariant, not leftmost `cellIn`** (`BillParser.kt:135-156` +
   `Columns.kt:172`). When a row has ≥2 money cells near the amount band, pick the pair where
   `qty×price=amount` (reuse `invariantHolds` `BillParser.kt:32`) instead of `firstOrNull`. Directly
   fixes Jumbo King / Lime Juice undercount and locks QTY (f) even if columns stay merged.
3. **Upgrade `reconcile` into a CORRECTOR** (`Solver.kt`): (i) rescale a single ~100× outlier when
   `amount/100` makes Σitems≈subtotal → fixes (c); (ii) detect missing/undercounted rows via
   `subtotal−Σitems` shortfall AND cross-check `Σqty` vs `Total Qty` (currently read but unused as a
   constraint) → surfaces (a)/(d)/(f); (iii) price-vs-amount disambiguation via `qty×price=amount`.
   The safety net that catches whatever slips through columns.
4. **Bidirectional multi-line name grouping** (`extractItems` `BillParser.kt:134-163`). Anchor = the
   row bearing the numeric triple; attach BOTH preceding AND following letters-only, moneyless rows
   (until the next anchor) to that item's name. Needs next-anchor look-ahead. Fixes (a)/(e).
5. **Percentage-token hardening** (`Numbers.kt:12`): make `cleanNumeric`/`parseMinor` reject a
   trailing `%` (return null) so `isMoneyToken("2.5%")` is false everywhere. Removes latent tax
   confusion (b).
6. **OCR+AI handoff** (`BillScan.kt:60`, `LlmExpenseParser`): feed the LLM the COLUMN-STRUCTURED text
   / the `parseBill` partial draft (items it did get + which totals are missing) instead of raw
   `rows.map{it.text}`, so on genuinely-blurry decimals (c) the AI repairs against structure. Modes
   (a),(b),(d),(e),(f) must be fixed DETERMINISTICALLY and not depend on the LLM.

## Data-model / product changes

- **Per-item unit price + qty** (h/f): the engine `LineItem` must carry `qty`, `unitPriceMinor`,
  `amountMinor` (amount=qty×unitPrice). Surface `qty × unit price = amount` in the item list
  (`ItemizedSplitScreen` cards) and the claim screen.
- **Multi-tax breakdown** (g): model taxes as an ordered named list `[{label, amountMinor}]`
  (SGST/CGST/service charge/cess/roundoff) instead of one `taxMinor`. `readTotals` already buckets
  SGST/CGST — extend to capture each labelled line. Carry the list through the claim session
  (backend `claim_sessions` currently has single tax/fees/discount/roundoff — extend to a taxes JSON
  or keep the scalar sum for the split but store the labelled breakdown for display), the expense
  notes, and show each line + who it's split across in the detail sheet and claim UI. Split each tax
  proportionally to each person's claimed subtotal (same math as today, just itemised for display).
- **Item list in claim detail** (i): claim finalize must write the per-item "who ate what" breakdown
  into the expense (notes format parsed by `ExpenseDetailSheet`) so the detail sheet shows the list.

## Test harness (regression)

- Engine integration: `app/src/test/java/ai/schism/split/sms/receipt/engine/ParseBillIntegrationTest.kt`.
  `rowsOf(dump)` (lines 17-25) parses a `text|xLeft|xRight|yCenter` cell dump (blank line = new visual
  row) into engine `Row`s and calls `parseBill`. **Add the Olive bill fixture here** — encode wrapped
  names as multiple rows and the `PriceAmount` header as ONE fused cell to reproduce (d)+(a)+(f), then
  assert 6 items with correct qty & unit price, Σamount=94400, tax lines SGST=2360 CGST=2360,
  roundoff=−20, total=99100, verified=true.
- Per-behavior: `BillParserItemsTest.kt` (`c(text,xL,xR,y)` builder; `wrappedNameSurvivesInterveningGarbleRow`
  at :102 covers only backward/name-above — ADD a thermal name-below case), `ColumnsTest`,
  `RegionsTest`, `NumbersTest`, `SolverTest`, `TotalsTest`, `GeometryTest`.
- Injection points: geometry-level `parseBill(rows)` (`BillParser.kt:195`); flat-string
  `parseReceipt(lines)` (`ReceiptParser.kt:82`); live boundary `ReceiptScanner.recognizeCells`.

## Non-negotiables

- Generalise — do NOT hardcode Olive values or sample-specific strings. Fixtures test behaviour.
- Money = `Long`/`int64` minor units; display only via `formatMinor`.
- Deterministic-first: the engine must fix (a)(b)(d)(e)(f) without the LLM; AI only aids (c) blur.
- No `Co-Authored-By` trailer on any commit.
