# Schism — Claim Links ("claim what you ate") Design

Approved 2026-07-09. A collaborative bill-split flow: scan a bill, share one link, everyone opens
the app and claims the items they had (by proportional share), and the creator locks the final
split. Builds on the existing itemised engine, group/participant model, phone-identity linking, and
deep-link invites.

## Overview & flow

From the itemised split screen, a new **"Let everyone claim"** action (alongside today's
creator-assigns "Split by items") creates a **claim session** — a snapshot of the parsed items +
tax/fees + currency, tied to the group, with the creator as payer — and produces a share link
(`https://<backend>/c/<sid>`, deep-linking into the app like group invites). Everyone opens it;
because claimers are group members, the app auto-joins them to the group if needed (existing
join-by-link + phone-identity flow), then shows the **claim screen**: each item with a −/+/typed
**weight** (0.5 supported), a live "you owe ₹…", and a per-item "claimed by" row. The screen
**polls ~every 3s** so people see items fill up. The creator additionally sees **Finalize**; on
finalize they resolve any unclaimed items, and the session becomes a normal locked BY_AMOUNT expense
via the existing proportional weighted math.

## Decisions (from brainstorm)

- **Identity:** claimers are **group members**; each claims as their group participant. Non-members
  who open the link are auto-joined first (reusing join-by-link + phone linking); a person with no
  account registers (their phone links them to their participant).
- **Claim model:** **proportional share weight** per person per item (0, 0.5, 1, 2, …). An item's
  cost splits in proportion to everyone's weights. No scarce-unit "over-claim" is possible;
  "unclaimed" = an item with zero total weight. Matches the existing `buildItemizedExpenseRequest`
  math exactly.
- **Unclaimed at finalize:** the creator **resolves each unclaimed item** — assign to a person,
  split evenly among all, or "I'll cover it." Finalize is blocked until every item is resolved,
  with a one-tap "split the rest evenly."
- **Live updates:** **near-live via polling** (~3s); no websockets in v1.
- **Relationship to existing flow:** claim-links is an ADDITIONAL entry point beside the current
  single-device "Split by items" (creator assigns everyone). Both produce the same kind of
  BY_AMOUNT expense.

## Data model (Go + Postgres — migration `0007_claim_sessions`)

- **`claim_sessions`**: `id TEXT PK`, `group_id TEXT`, `creator_participant_id TEXT`, `title TEXT`,
  `currency TEXT`, `items JSONB` (snapshot `[{idx:int, name:string, qty:int, amountMinor:int}]`),
  `tax_minor BIGINT`, `fees_minor BIGINT`, `discount_minor BIGINT`, `roundoff_minor BIGINT`,
  `status TEXT` (`open`|`finalized`|`cancelled`), `version INT NOT NULL DEFAULT 1`,
  `expense_id TEXT NULL` (set on finalize), `created_at TIMESTAMPTZ`. The creator can edit items
  while `open`; **editing or removing an item drops all claims on that item** (its claimers see it
  cleared on the next poll and re-claim), and bumps `version`.
- **`claims`**: `session_id TEXT`, `item_idx INT`, `participant_id TEXT`, `weight NUMERIC(6,2)` (> 0;
  a 0 weight deletes the row). PK `(session_id, item_idx, participant_id)`. FK `session_id` →
  `claim_sessions(id)` ON DELETE CASCADE.

### Endpoints (all require a bearer token for a linked member of the session's group)
- `POST /v1/groups/{id}/claim-sessions` → create from an items+totals payload; returns `{sid, …}`.
- `GET /v1/claim-sessions/{sid}` → poll: session (items, totals, status, version, expense_id) + all
  claims (participant_id, item_idx, weight) + a computed per-participant "owes" preview.
- `PUT /v1/claim-sessions/{sid}/claims` → upsert the CALLER's weights `[{item_idx, weight}]`
  (idempotent full-replace of that caller's rows); body carries `expectedVersion`.
- `POST /v1/claim-sessions/{sid}/finalize` → creator only; body carries the resolution for each
  unclaimed item + `expectedVersion`; builds the expense server-side and locks (see below).
- `POST /v1/claim-sessions/{sid}/cancel` → creator only; sets `status='cancelled'`.
- `PATCH /v1/claim-sessions/{sid}/items` → creator only; edit the item snapshot (add/remove/rename/
  amount); drops claims on any edited/removed item, bumps `version`.

## Concurrency & locking (the core correctness story)

**Polling is display-only; correctness is a server-side transactional state machine.** The lock is
Postgres row-level, not application-level, and no claim is ever silently lost.

Finalize and claim both take `SELECT … FOR UPDATE` on the session row, so they serialize:

```
-- FINALIZE (creator)
BEGIN
  SELECT status, version FROM claim_sessions WHERE id=$sid FOR UPDATE
  if status = 'finalized': COMMIT; return existing expense_id      -- idempotent
  if status = 'cancelled': 409
  if body.expectedVersion != version: 409 VERSION_STALE
  read all claims; apply the creator's unclaimed-item resolutions
  build expense (weighted split + proportional tax/fees/discount/roundoff, exact rounding)
  INSERT expense
  UPDATE claim_sessions SET status='finalized', expense_id=$eid WHERE id=$sid
COMMIT

-- CLAIM (anyone) — PUT my weights
BEGIN
  SELECT status, version FROM claim_sessions WHERE id=$sid FOR UPDATE
  if status != 'open': 409 LOCKED
  if body.expectedVersion != version: 409 VERSION_STALE   -- an item changed under you
  upsert (delete+insert) THIS caller's claim rows
COMMIT

-- EDIT ITEMS (creator) — PATCH /items
BEGIN
  SELECT status FROM claim_sessions WHERE id=$sid FOR UPDATE
  if status != 'open': 409 LOCKED
  apply item edits; DELETE claims for any changed/removed item_idx
  UPDATE claim_sessions SET items=$new, version=version+1 WHERE id=$sid
COMMIT
```

Exactly one interleaving happens per the row lock:
- **Claim commits first** → finalize's "read all claims" includes it. Counted.
- **Finalize commits first** → the claim then sees `status='finalized'` → **409 LOCKED**, cleanly
  rejected. The claimer's screen flips to the read-only "creator locked this split" view.

`version` guards item edits: if the creator edits items between a claimer's read and their PUT, the
PUT is **409 VERSION_STALE** ("the bill changed — refresh"); the client refetches (seeing the edited
items + its dropped claims) and re-claims. Finalize is idempotent on `status`; a duplicate returns
the same `expense_id`.

## App components

- `ClaimSessionViewModel` — polls `GET` (~3s while the screen is resumed), holds the caller's local
  weights, submits via `PUT` (debounced), maps 409s to clear UI states (`Locked`, `Stale`).
- Claim screen — reuses the itemised item-card + weight-stepper (−/+/typed, 0.5) components; a live
  "you owe" total; a per-item "claimed by" avatars row; creator sees an extra **Finalize** button.
- Finalize sheet (creator) — lists still-unclaimed items with per-item resolve (assign / split
  evenly / I'll cover it) + "split the rest evenly"; on success navigates to the group (the new
  expense shows, breakdown via the `ExpenseDetailSheet` we built).
- Deep link `https://<backend>/c/{sid}` → `schism://claim/{sid}` (backend landing like `/g/`),
  routed to the claim screen; auto-join the group first if the caller isn't a member.

## Edge-case matrix

- **Claimer not in group / no app:** link auto-joins them (phone-identity); no-account users
  register first, then land on the claim screen.
- **Unclaimed items:** creator resolves each at finalize; finalize blocked until all resolved.
- **Over/under claim:** impossible (independent proportional weights, not scarce units).
- **Concurrent claims:** separate rows per person; a PUT only replaces the caller's own rows — no
  cross-user conflict. Finalize vs claim serialized by the row lock (above).
- **Creator edits items mid-session:** allowed via `PATCH …/items`; **editing or removing an item
  drops that item's claims** (its claimers re-claim), bumps `version`; a claim/finalize sent against
  the old version → 409 VERSION_STALE → client refetches + re-claims. Other clients see the change
  next poll.
- **Person claims then leaves the group before finalize:** their claims are dropped (participant
  removed); their weight no longer counts.
- **Tax/fees/discount/round-off:** split proportionally to each person's claimed item subtotal
  (existing `buildItemizedExpenseRequest` logic); last person absorbs rounding so the total is exact.
- **Double / stale finalize:** idempotent on `status` → returns existing `expense_id`.
- **Creator abandons:** session stays `open`; "Cancel session" discards it (no expense).
- **Offline claimer:** claiming is inherently collaborative → network-required; a clear "reconnect
  to claim" state, not offline-queued.
- **Empty session (nobody claimed anything):** creator can still finalize by resolving all items
  (assign/split/cover) — or cancel.

## Testing

- Backend httptest: create → claim (multiple participants, 0.5 weights) → poll → finalize; assert
  the built expense's per-person amounts + tax split; golden cases: all-unclaimed (creator
  resolves), single claimer, exact-rounding, weighted (2 vs 1).
- Backend concurrency test: interleave a `PUT /claims` and `POST /finalize` on the same session;
  assert either the claim is included OR it gets 409 LOCKED — never lost, and the expense is built
  exactly once.
- `version` guard test: creator edits an item (its claims dropped), then a stale claim/finalize →
  409 VERSION_STALE; a fresh claim after refetch succeeds.
- Pure-Kotlin test for the client-side "you owe" preview matching the server's math.

## Out of scope (v1)

Websockets/SSE realtime; web (non-app) claiming; offline claiming; session auto-expiry; editing a
*finalized* split; weighting a single unit fractionally beyond the proportional model.

## Release: alpha, opt-in

Ships as an **alpha** feature gated by a Settings › **Labs** toggle "Claim links (alpha)" (a
DataStore flag, default OFF). The "Let everyone claim" entry point appears on the itemised screen
only when the flag is on, and the claim screen carries a small **ALPHA** badge. This lets us ship it
in a normal release for opt-in testing without exposing it to everyone. Backend endpoints are live
regardless (harmless if unused). Graduating out of alpha = flip the default / drop the gate.
