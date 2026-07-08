# Phase A bill-reading — real-device findings (2026-07-09)

Captured from user testing of 1.0.3's parser. These become golden fixtures + hard requirements for
the Phase A harness. Motto holds: **small model, optimize at every level, OCR is the eyes, math
verifies — never trust the model's number.**

## Confirmed bugs (current parser, both regex-only and AI paths)

1. **Rate/Qty/Amount column confusion.** On "Name … Rate Qty Amount" layouts (Anandha Bhavan,
   GRT Bhopal) the parser swaps columns: `ROAST` (rate 50, qty 3, amount 150) shows as
   **`ROAST ×150 ₹50.00`** — qty=amount, amount=rate. Also `POORI MASAL ×100 ₹50.00`,
   `TEA ×25 ₹25.00`, `GHEE PONGAL ×50 ₹50.00`, `VADAI ×60 ₹20.00`. Column roles must be detected by
   header ("Rate"/"Qty"/"Amount"/"Price") AND by arithmetic (rate×qty≈amount), not by position.
2. **Total taken from the parsed "Grand Total" string, not computed from items.** User: "it is
   getting final also from the parse rather than calc from items." The per-person + Total must be
   derived from the (verified) item lines + tax, and cross-checked against the printed total — not
   copied from it.
3. **Tax lines become items.** `CasT 2.5% ₹32.88` (CGST) shown as a splittable item; CGST/SGST/
   round-off must be classified as tax/adjustment, never items. Conversely real taxes are sometimes
   missed entirely (only round-off ₹0.26 captured instead of CGST+SGST).
4. **Wrapped names split into two items / garbled.** "BAKED VEG WITH" + "MUSHROOM MATTA",
   "Cheese Corn Kebab", merchant "AÑ¦ANDHA BHAVAN A/C", "VEG SWEET CcORN". Multi-line item names and
   OCR noise in headers.
5. **App CRASHES while AI is processing a bill.** Must reproduce + fix (likely OOM/timeout or a
   MediaPipe exception on-device not caught). Bill scanning must never crash the app.
6. **AI path is slow (30–40s) and hallucinates heavily.** Unacceptable latency + made-up items.

## Hard requirements for Phase A

- **Never crash**: all OCR/LLM/inference wrapped; failures degrade to editable manual entry.
- **Speed target**: well under ~10s on-device for a typical bill; show progress; cancellable.
- **Numbers are OCR-only + arithmetic-verified**: the model classifies/labels/cleans names; it
  never emits amounts or the total. Items must sum to subtotal; subtotal+tax−discount≈grand total,
  else the constraint solver reconciles from candidates.
- **Column-aware extraction**: detect and correctly map Rate/Qty/Amount/Price columns; sanity via
  rate×qty≈amount.
- **Split ANY bill**: paper POS (Vraj, Anandha Bhavan, GRT), grocery GST invoices (Daily Basket
  POS-2049 with per-item HSN + multi-slab GST breakup), and later Swiggy/Zomato/Blinkit app
  screenshots. "server logic OK but on-device processing" — user is fine with a server-assisted
  path as long as on-device works; flagship stays on-device.

## Golden fixtures to add (OCR text → expected draft)

- **Vraj Restaurant** (No/Item/Qty/Price/Amount): Manchow Soup 2×159=318, Paneer Tufani 1×299=299,
  Butter Roti 6×39=234, Cheese Corn Kebab 1×289=289, Masala Papad 1×49=49, Buttermilk 2×30=60;
  Sub Total 1249, Grand Total 1249; no tax. Total Qty 13.
- **Anandha Bhavan** (Name/Rate/Qty/Amount): Ghee Pongal 50×1=50, Vadai 20×3=60, Roast 50×3=150,
  Poori Masal 50×2=100, Tea 25×1=25; subtotal 385; CGST 2.5% 9.63, SGST 2.5% 9.63, Round −0.26;
  total 404.
- **GRT Bhopal** (Description/Qty/Amount): Veg Manchow So 1×119, Veg Sweet Corn 1×119, Dal Tadka
  1×215, Jeera Rice 1×145, Plain Papad 2×80, Baked Veg With Mushroom Matta 1×270 (wrapped),
  … Butter Tandoor 27, Missi Roti 30, Garlic Naan 45; Total Amount 1315; CGST 2.5% 32.88, SGST 2.5%
  32.88; Bill Amount 1381.
- **Daily Basket grocery POS-2049** (Item/Qty/Rate/Amt + HSN + multi-slab GST): Basmati Rice 2×68=136
  (5%), Whole Wheat Bread 1×52=52 (5%), Soft Drink 3×110=330 (18%), Face Wash 1×220=220 (18%);
  Subtotal 738, Discount 0; CGST 2.5%=4.70, SGST 2.5%=4.70, CGST 9%=49.50, SGST 9%=49.50; Grand
  846.40, Round −0.40, Rounded 846. (HSN lines must NOT be items; multi-slab tax sums to 108.40.)

Status: NOT started. Begins after 1.0.4 (Phase D+C) ships. Will get its own plan
(`docs/superpowers/plans/…-v1.1-phase-a.md`).
