# Design: Native Android Split App — Program design + Go backend (SP1) & Groups client (SP2)

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Author:** brainstorming session

---

## 1. Context & overall vision

We are building a **brand-new native Android app** that combines features from two existing
codebases in this workspace:

- **pennywiseai-tracker** — mature native Android app (Kotlin, Jetpack Compose, Material 3, Hilt,
  Room, WorkManager). Provides on-device SMS parsing (`parser-core`, 55 bank parsers, no Android
  deps) and an on-device LLM (MediaPipe / Qwen 2.5).
- **spliit** — web Splitwise alternative (Next.js, Prisma, PostgreSQL, tRPC). Provides the
  splitting domain: groups, participants, expenses, split modes, balances, reimbursements,
  categories, activity feed.

### Whole-project architecture (3 layers)

| Layer | Responsibility | Source |
|---|---|---|
| **On-device (private)** | SMS read → bank parse → local transactions; on-device AI (chat + image); local Room DB | Reuse pennywise `parser-core` as a library + reuse MediaPipe/Qwen wiring |
| **Cloud (shared)** | Groups, participants, expenses, balances, split modes, categories, reimbursements, activity | **New scalable Go service** that ports spliit's split domain (schema + `balances.ts`/`api.ts` logic) behavior-for-behavior; Postgres |
| **Bridge** | Parsed SMS transaction → "keep personal" (local) or "push to group split" (cloud expense) | New code — the app's signature feature |

**Privacy line:** SMS content and AI inference never leave the device. Only expenses the user
explicitly pushes to a group are sent to the cloud.

### Decomposition & build order

1. **Sub-project 1 — Go split backend.** Ports spliit's split domain onto a scalable Go + Postgres
   service with a REST API. Prerequisite for the client. **This is what we plan/build first.**
2. **Sub-project 2 — App skeleton + Groups & splitting client** (detailed in §5–§10). Native
   Android client against the Go backend. Depends on #1.
3. **Sub-project 3 — SMS tracking → split bridge.** Depends on #2.
4. **Sub-project 4 — On-device AI (chat + image).** Enhancement on top of #2 and #3.

Each sub-project is independently shippable and gets its own spec → plan → build cycle.

---

## 2. Scope

This document covers the **program architecture** plus two sub-projects:

- **SP1 — Go split backend** (§8): the scalable Go + Postgres service that ports spliit's split
  domain and exposes the REST API. **Built first; its own implementation plan.**
- **SP2 — Android Groups client** (§5–§10 client details): a native Android Splitwise-style client
  against the Go backend — groups, participants, expenses (all split modes),
  balances/reimbursements, activity feed, join by link/ID/QR, lightweight device profile,
  read-cache offline viewing, Material 3 theme.

**Explicitly OUT of scope (later sub-projects):** SMS reading, `parser-core`, on-device AI,
transaction→split bridge, full offline write sync, real accounts/auth.

---

## 3. Tech baseline

- Kotlin, Jetpack Compose, **Material 3**, Hilt (DI), Room (local cache + profile), Navigation
  Compose, WorkManager (later), Retrofit + OkHttp + kotlinx.serialization (REST client),
  Coil (images), ZXing/ML-Kit (QR scan).
- Same proven stack as pennywise, for speed.

---

## 4. Decisions locked during brainstorming

| Topic | Decision |
|---|---|
| Base app | Brand-new native Android app; reuse pennywise modules where possible |
| Sync model | Cloud-synced groups |
| Backend | **New scalable Go service** (Go + Postgres) that ports spliit's split logic verbatim in behavior; exposes a REST API for the Android client |
| Identity | **Lightweight device profile** — one local profile (name); auto-suggests "you" as the matching participant per group; no login |
| Join flow | **Link + group ID + QR** (paste link/ID or scan QR; share via link + QR) |
| Offline | **Read cache (Room), online writes** — view cached data offline; create/edit requires network |
| Theme | Material 3, **fixed brand color scheme** (light/dark); dynamic Material-You color off by default |

---

## 5. Screens & navigation

Bottom-nav / stack navigation (Compose):

- **Groups list** — user's groups, favorites pinned; pull-to-refresh; FAB → Create; "Join group".
- **Create group** — name, currency, initial participants; on success navigate into the group.
- **Join group** — paste spliit link/ID **or** scan QR. "Share" sheet (link + QR) available from
  any group.
- **Group detail** — tabs:
  - *Expenses* — chronological list, tap to edit.
  - *Balances* — who owes whom + suggested reimbursements.
  - *Activity* — activity feed.
- **Add/Edit expense** — title, amount, category, paid-by, paid-for participants,
  **split mode: EVENLY / BY_SHARES / BY_PERCENTAGE / BY_AMOUNT**, reimbursement toggle, notes.
- **Settings** — device profile (name), backend base URL, theme toggle.

---

## 6. Identity (lightweight device profile)

- A single local `DeviceProfile { name }` stored in Room/DataStore.
- On opening a group, auto-suggest the participant whose name matches the profile as "you"; the
  user can override. The chosen `activeParticipantId` is persisted **per group**.
- No accounts, no server-side identity.

---

## 7. Data model & caching (Room)

Room mirrors spliit's schema shape, plus local-only tables.

- Cloud-mirrored: `Group`, `Participant`, `Expense`, `ExpensePaidFor`, `Category`, `Activity`.
- Local-only: `DeviceProfile`, per-group `activeParticipantId` (on the group row or a small table),
  `favorite` flag.
- **Read path:** fetch from backend → upsert into Room → UI observes Room via Flow
  (offline-viewable).
- **Write path:** call backend mutation → on success, refetch affected entities into Room →
  UI updates. Writes require network; offline write attempts surface a clear error (no local queue
  in v1).
- Money stored as integer minor units (matches spliit's `amount Int`); format with currency on
  display.

---

## 8. Backend (new scalable Go service — Sub-project 1)

A standalone **Go + Postgres** service that reimplements spliit's split domain and exposes a REST
API for the Android client. It does **not** deploy spliit's Next.js app; it ports the logic.

**What is ported verbatim in behavior (from spliit):**
- **Schema** — the Prisma models (`Group`, `Participant`, `Category`, `Expense`, `ExpensePaidFor`,
  `Activity`, `ExpenseDocument`) become SQL migrations. Money stays integer minor units.
- **Split math** — `lib/balances.ts`: `getBalances` (per-participant paid/paidFor/total, with the
  same last-participant remainder assignment and rounding rules), `getSuggestedReimbursements`
  (greedy two-pointer with the `compareBalancesForReimbursements` stable comparator), and
  `getPublicBalances`. Split modes: `EVENLY`, `BY_SHARES`, `BY_PERCENTAGE`, `BY_AMOUNT` (shares
  scaled ×100 for non-amount modes, exactly as spliit does).
- **Validation** — expense form rules from `lib/schemas.ts` (amount ≠ 0, ≤ 10,000,000.00; BY_AMOUNT
  shares must sum to amount; BY_PERCENTAGE must sum to 100%; ≥1 paidFor; share > 0).

**Stack:** Go, `chi` router, `pgx` + `sqlc` (typed SQL) over Postgres, `golang-migrate` migrations,
structured logging, OpenAPI doc. Stateless service (horizontally scalable behind a load balancer);
Redis caching is a later optimization, not v1.

**REST endpoints (v1):**
- Groups: `POST /v1/groups`, `GET /v1/groups?ids=`, `GET /v1/groups/{id}`,
  `GET /v1/groups/{id}/details`, `PUT /v1/groups/{id}`
- Categories: `GET /v1/categories`
- Expenses: `GET /v1/groups/{id}/expenses`, `GET /v1/groups/{id}/expenses/{expenseId}`,
  `POST /v1/groups/{id}/expenses`, `PUT /v1/groups/{id}/expenses/{expenseId}`,
  `DELETE /v1/groups/{id}/expenses/{expenseId}`
- Balances: `GET /v1/groups/{id}/balances` (balances + suggested reimbursements)
- Activities: `GET /v1/groups/{id}/activities`
- Stats: `GET /v1/groups/{id}/stats`

**Access model:** matches spliit — no auth; a group is reached by its ID. (Rate limiting / abuse
protection is a later hardening item.)

**Parity guarantee:** golden-file tests feed identical expense fixtures to the Go `getBalances`/
reimbursement code and assert byte-equal results against captured spliit outputs, so the client
never disagrees with the reference implementation.

Android side (Sub-project 2): Retrofit interface + DTOs mirroring this JSON, mapped to Room
entities; backend base URL configurable in Settings.

---

## 9. Theme

- Material 3 with a **fixed brand `ColorScheme`** for light and dark; 8dp grid; M3 type scale.
- Dynamic Material-You color **off by default** (a toggle can be added later).
- All screens tested in light and dark.

---

## 10. Error handling & testing

- Sealed `Result`/`UiState` for load / empty / error; snackbars + retry actions; network errors
  distinguished from validation errors.
- No optimistic updates in v1 — refetch after writes for correctness.
- Tests:
  - **Split-math parity** — golden cases checked against spliit's `getBalances`/reimbursement
    output, so the client never disagrees with the server.
  - DTO ↔ Room entity mapping round-trips.
  - ViewModel state transitions for load / empty / error.

---

## 11. Open items deferred to the implementation plan

- Exact REST payload shapes (derive from existing tRPC procedure inputs/outputs).
- spliit deployment/hosting choice (self-host vs Vercel + Postgres) — a config detail.
- Module layout of the new Android app (single `app` module vs feature modules).
- How `parser-core` is consumed later (source include vs published artifact) — belongs to
  Sub-project 2.
