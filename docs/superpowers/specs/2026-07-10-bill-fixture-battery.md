# Bill Reader — Rigorous Fixture Battery ("best reader" hardening)

A brutal, diverse corpus of synthetic bills to prove the deterministic engine GENERALISES (no
overfitting). Each fixture is a `rowsOf(dump)` cell dump (`text|xLeft|xRight|yCenter`, blank line =
new visual row) encoded as ML Kit would emit it, run through `parseBill`, asserting exact items
(name, qty, unitPriceMinor, amountMinor), tax lines, roundoff, grand total, and `verified=true`.
Harden the engine until EVERY fixture passes. Do NOT hardcode any fixture's values in the engine.

## Diversity matrix (cover the cross-product; ≥20 fixtures)

### Column layouts
- `No. Item Qty Rate Amount` (5-col, index column present)
- `Item Qty Rate Amount` (4-col)
- `Item Qty Amount` (no rate — qty×? unknown; amount only)
- `Item Amount` (2-col, no qty → qty defaults 1)
- `Qty Item Rate Amount` (qty first)
- Rate/Amount SWAPPED order (amount left of rate) — engine must use qty×rate=amount to fix
- Right-aligned vs left-aligned numeric columns (varying xLeft/xRight)

### Fused / ambiguous headers
- `PriceAmount` (Olive) · `RateAmount` · `QtyRate` · `QtyRateAmount` (all fused) · header ALL-CAPS ·
  header abbreviations `QTY` `RATE` `AMT` `AMORT` `PRICE` · no header row at all (structural only)

### Close / colliding money columns
- Rate & Amount x-clusters within the gap threshold (must NOT merge)
- Equal rate==amount rows (qty 1) interleaved with qty>1 rows (447 vs 149) in the same bill
- Three money columns close together (rate, amount, and a per-unit tax column)

### Multi-line item names
- Wrap DOWNWARD (thermal, numeric on first line) — 2, 3, 4 lines
- Wrap UPWARD (numeric on last line)
- Name containing digits (`7 Up`, `Item 2pc`, `500ml Water`) — must not be read as qty/amount
- Name with `&`, `/`, `-`, `(` `)`, parentheses, `%` in the name text
- Two single-line items adjacent (no wrap) to ensure no false merging

### Number / decimal styles
- Dropped decimal `9900` meaning `99.00` (solver must rescale) — one per bill AND two in one bill
- Comma thousands `1,234.00` · bare `₹149` · `149/-` · `149.00` · `Rs.149` · `149·00` (mid-dot)
- Zero-amount free item `0.00` · very large legit catering line `12500.00`

### Quantity styles
- Integer `3` · `x3` / `X3` · `3 Nos` · `3.000` (grocery) · fractional `1.5` (kg/weight) · qty missing

### Tax / totals
- No tax · single `GST 5% 47.20` · `SGST 2.5%`+`CGST 2.5%` · `CGST`+`SGST`+`CESS` ·
  `Service Charge 10%` + GST · `VAT 14.5%` · tax-INCLUSIVE (prices include tax, only grand total) ·
  round off `+0.30` and `-0.20` · discount line (bill-level) · item-level MRP strikethrough
  (`left MRP > right selling`, Templates) · packing/delivery fee (Swiggy/Zomato/Blinkit style)
- Totals labels vary: `Sub Total` `Subtotal` `Total` `Grand Total` `Net Amount` `Bill Amount`
  `Amount Payable` `TOTAL DUE` · `Total Qty: N` present vs absent

### Sources / noise
- Swiggy/Zomato/Blinkit screenshot layout (item, qty badge, price) · paper thermal POS ·
  grocery weight-based · restaurant dine-in
- Header/footer junk that must be ignored: GSTIN, phone `M: 9940415250`, address with numbers,
  `Bill No.: 391`, `Table No: 12`, date `09/07/26`, `Thank You & Visit Again`, separator `------`,
  `Paid via ...`
- Currency: `₹` `Rs` `INR` `$`

### Adversarial / stress
- Single item bill · 20+ item bill · item whose amount == subtotal · all items qty 1 ·
  a row that is pure garble (OCR noise) between two real items · phone number that looks like a
  price (must be rejected, ≥8 digits no decimal) · two bills where only the solver-corrector saves it

## Assertions per fixture
- exact item count; each item name (trimmed, wrapped lines joined), qty, unitPriceMinor, amountMinor
- amount == qty × unitPrice for every item (where rate known)
- each tax line captured with correct label + amountMinor
- roundoff sign+magnitude; grand total; `verified == true`
- Σitem amounts == subtotal; subtotal + taxes − discount + roundoff == grand total

## Also add flat-string fixtures
A handful via `parseReceipt(List<String>)` (`ReceiptParserTest`) for the pre-geometry / LLM-string
route, covering the same decimal-drop and tax cases.

## Optional: real images for on-device validation
Render 3-4 realistic receipt PNGs (thermal + Swiggy-style + grocery) to `docs/.../bill-samples/`
for the user to scan live on the emulator/device — the fixtures prove the logic, the images prove
ML Kit + the pipeline end-to-end.
