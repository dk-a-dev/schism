# Design: Sub-project 3 — SMS tracking → split bridge

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Depends on:** SP1 (Go split backend), SP2 (Android Groups client)
**Parent design:** `2026-07-05-groups-splitting-android-design.md`

---

## 1. Goal

Turn auto-detected bank SMS transactions into either a **personal** record (stays on-device) or a
**group expense** (pushed to the cloud group via the Go backend). This is the app's signature
feature: "a transaction arrives → you drop it on a group, or it's yours."

## 2. Privacy line (unchanged)

SMS content, parsing, and the local transaction ledger **never leave the device**. Only an expense
the user *explicitly* pushes to a group is sent to the Go backend, and only the fields needed for
that expense (amount, title/merchant, date) — not the raw SMS.

## 3. Reuse

- **`parser-core`** from pennywise (55 bank parsers, zero Android deps) is consumed as a library
  (source include for v1; published artifact later). `BankParserFactory.parse(smsBody, sender,
  timestamp)` → `ParsedTransaction`.
- pennywise's SMS scanning approach (WorkManager + `SmsRepository`) is used as the reference
  implementation and adapted; we do not depend on the whole pennywise app module.

## 4. Components

### 4.1 SMS ingestion (on-device)
- Runtime `READ_SMS` / `RECEIVE_SMS` permission with a clear rationale screen.
- `SmsReceiver` (BroadcastReceiver) for live messages + a WorkManager `SmsScanWorker` for an
  initial/backfill scan and periodic catch-up.
- Each message → `BankParserFactory` → `ParsedTransaction` (or dropped if unparseable).

### 4.2 Local transaction store (Room)
- `TransactionEntity` (id, amount, currency, merchant, bankName, direction debit/credit,
  timestamp, rawSender, status). `status ∈ {UNASSIGNED, PERSONAL, PUSHED}`.
- Dedup: a stable hash of (sender, timestamp, amount, body) prevents double-inserts on rescans.

### 4.3 Transaction inbox (UI)
- A new bottom-nav destination "Inbox": list of `UNASSIGNED` debit transactions (newest first),
  each card showing merchant, amount, bank, date.
- Per transaction, two primary actions: **Keep personal** (sets `PERSONAL`) and **Split to
  group** (opens the push flow). Swipe or long-press for quick keep-personal.

### 4.4 Push-to-split flow
- Reuses SP2's Add-Expense form, prefilled from the transaction: amount, title = merchant,
  expenseDate = txn timestamp.
- User picks the **group** (from SP2's cloud groups), confirms paid-by (defaults to the device
  profile participant), paid-for participants, and split mode.
- On submit → SP2's API client `createExpense` against the Go backend. On success, the local
  transaction is set `PUSHED` and linked to the returned `expenseId`
  (`TransactionEntity.remoteExpenseId`).

### 4.5 Idempotency & consistency
- The push carries a client-generated idempotency key = transaction dedup hash, so a retry after a
  flaky network never creates a duplicate cloud expense. (Backend SP1 accepts an optional
  `Idempotency-Key` header on expense create — added to SP1's plan.)
- If a pushed transaction's expense is later deleted in the group, the local link is cleared and
  the transaction returns to `UNASSIGNED` on next reconciliation.

## 5. Out of scope (later)
- Auto-categorization / auto-suggesting a group via AI (that's SP4 territory).
- Editing/splitting credit (income) transactions.
- Non-SMS sources (notifications, email).

## 6. Testing
- Parser integration: feed known bank SMS fixtures → assert `ParsedTransaction` fields (reuse
  `parser-core` fixtures).
- Dedup hash stability and collision behavior.
- Inbox ViewModel state (unassigned/empty/error); status transitions
  UNASSIGNED→PERSONAL and UNASSIGNED→PUSHED.
- Push idempotency: two submits with the same key → one cloud expense (mocked API).
