# Design: Sub-project 4 — On-device AI (chat + image)

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Depends on:** SP2 (Groups client), SP3 (SMS bridge / local transactions)
**Parent design:** `2026-07-05-groups-splitting-android-design.md`

---

## 1. Goal

A private, **on-device** AI assistant with two capabilities:
1. **Chat** grounded in the user's own spending/transactions and groups.
2. **Image** understanding — snap a receipt → draft an expense/split.

All inference runs on-device; no financial data leaves the phone.

## 2. Reuse

- pennywise's **MediaPipe LLM** wiring (Qwen 2.5 on-device) is the reference for model loading,
  session management, and streaming tokens. We adapt its `LLMService`/inference layer rather than
  depend on the whole pennywise app.

## 3. Chat

### 3.1 Grounding (tool-calling over local data, not raw dump)
The model is given a small set of **local tools** it can call, so answers stay grounded and the
context window stays small:
- `queryTransactions(filter)` → summarized rows from the Room transaction store (SP3).
- `getSpendingSummary(period)` → totals by period/merchant/category (computed locally).
- `listGroups()` / `getGroupBalance(groupId)` → from SP2's cached cloud data.
The assistant answers questions like "how much did I spend on food this month?" or "what do I owe
in the Goa trip?" by calling tools, never by ingesting the full ledger.

### 3.2 Actions
Chat can **draft** actions that route into existing flows (it does not silently mutate data):
- "Split last night's dinner with Sam and me" → opens SP2's Add-Expense form prefilled; user
  confirms.
- "Mark the ₹1,200 Amazon txn as personal" → opens the SP3 inbox action for confirmation.

### 3.3 UI
- A chat destination: streaming responses, message history in Room (local only), a "clear chat"
  action. Suggested prompts on empty state.

## 4. Image (receipt → expense draft)

**On-device pipeline (no cloud vision):**
1. Capture/pick a receipt image (Coil + CameraX / photo picker).
2. **ML Kit on-device OCR** extracts raw text from the receipt.
3. The on-device LLM structures the OCR text → `{ merchant, date, total, lineItems[] }`.
4. Result opens SP2's Add-Expense form prefilled (total as amount, merchant as title), ready to
   split.

Rationale: OCR + text-LLM keeps everything on-device and avoids requiring a multimodal model; it
reuses the same Qwen text model already loaded for chat.

## 5. Model & performance
- Reuse pennywise's model download/management (model stored on-device, first-run download with
  progress; feature gracefully disabled until the model is present).
- Inference on a background dispatcher; streaming tokens to the UI; hard cap on context size via
  the tool-calling design above.

## 6. Out of scope (later)
- Multimodal (direct image-to-model) inference.
- Cloud fallback for the LLM.
- Proactive/auto suggestions (e.g., auto-assigning a transaction to a group without confirmation).

## 7. Testing
- Tool-call routing: given a prompt, assert the correct tool + args are produced (mocked model).
- OCR→structure mapping: given fixture OCR text, assert parsed `{merchant,date,total}`.
- Chat history persistence round-trip (Room).
- Feature-gating when the model is absent (UI shows download prompt, no crash).
