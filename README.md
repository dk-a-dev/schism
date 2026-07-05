# Schism

A **Splitwise-style group expense splitter** with an on-device **personal-finance** side: bank-SMS
and receipts are parsed entirely on your phone, then kept personal or pushed into a shared group
expense. Native Android client, self-hosted Go backend.

> Monorepo. The two things you build and run are **`schism-backend/`** (Go + Postgres) and
> **`schism-android/`** (Kotlin / Jetpack Compose). The rest are references.

---

## Repository layout

| Path | What it is |
|------|------------|
| **`schism-backend/`** | Go REST API (chi + pgx + Postgres). Groups, participants, expenses (all split modes), balances/reimbursements, activity, dashboards, categories, and token-based user identity. Migrations in `internal/store/migrations`. |
| **`schism-android/`** | The Android app (`:app`) + **`:parser-core`** — a vendored pure-Kotlin module of on-device bank-SMS parsers. MVVM + Compose (Material 3, expressive), Hilt, Retrofit + kotlinx.serialization, Room, WorkManager, ML Kit (OCR). |
| **`docs/superpowers/`** | Design **`specs/`** and implementation **`plans/`** (the source of truth for what's being built and why). |

**Credits / inspiration** (external projects, not included in this repo): **pennywiseai-tracker** — its
bank-SMS parsers (~148 issuers) are vendored into `schism-android/parser-core`; **spliit** — reference
web splitter for design/behaviour.

---

## Architecture

**Client → server, cache-first.**

```
Compose UI ──▶ ViewModel (StateFlow/UiState) ──▶ Repository ──┬─▶ Retrofit ApiService ──▶ Go backend ──▶ Postgres
                                                              └─▶ Room cache (offline-viewable reads)
```

- **Money** is `Long` minor units end-to-end; formatted for display only (`core/money`).
- **Reads observe Room** (offline-viewable); **writes hit the API then refresh** the cache. Expense
  creates carry an **idempotency key** so retries never double-post.
- **Identity / auth:** onboarding registers a user (`POST /v1/users`) and gets a **bearer token**
  (stored hashed server-side); the client sends `Authorization: Bearer …` on every request. Groups
  stay reachable by id (the invite model); the token only authenticates *who you are* so participant
  ↔ user linking can't be spoofed.
- **On-device, private:** bank SMS parsing (`:parser-core`), the transaction ledger (Room), receipt
  OCR (ML Kit), voice quick-add, and spending insights all run on the phone. Only an expense you
  explicitly push to a group leaves the device — and only its amount/title/date.
- **Backend base URL** is build/env config, not a user setting: `SCHISM_BACKEND_URL` env var or
  `-Pschism.backendUrl=…` (defaults to the emulator loopback `http://10.0.2.2:8080`).

---

## Running it

### Backend
```bash
cd schism-backend
make up          # docker compose: Postgres + the Go server on :8080 (auto-migrates)
make test        # go test ./...  (uses the running Postgres)
make down
```

### Android
```bash
cd schism-android
# Emulator reaches the host backend at 10.0.2.2:8080 (the default). For a LAN/device backend:
./gradlew :app:installDebug -Pschism.backendUrl=http://<host-ip>:8080
./gradlew :app:testDebugUnitTest       # unit + Robolectric tests
```
The debug build permits cleartext HTTP to the dev backend (release is loopback-only).

---

## Feature list

**Onboarding** — an animated first-run **walkthrough** (illustrated pages + a feature-discovery grid)
then identity capture (name / email / phone). ✅

**Groups & expenses** — create / join / **edit** / delete groups, participants (incl. from contacts),
expenses in all four split modes (evenly / shares / percentage / amount) with local validation
mirroring the backend, balances + suggested reimbursements, activity feed (who did what), group &
cross-group dashboards. Editing is gated to whoever *added* an expense. ✅

**Invites** — share/open `schism://group/<id>` deep links; QR generate + scan. ✅

**On-device SMS → split** — bank SMS parsed on-device (`:parser-core`, ~148 issuers) into an
**Inbox**; keep personal or **push to a group** as a shared expense. ✅

**On-device capture / AI** — receipt **OCR → itemised AI split** (ML Kit; reachable from the Groups
home and the Inbox) and **voice quick-add** ("paid 800 for dinner, split with Riya and Sam") parsed
offline. ✅

**Personal finance** — a **Spending** tab with monthly totals, by-merchant, and trend, computed
locally from the transaction ledger. ✅

**Settle up** — record a settlement (reimbursement) and/or open a UPI app prefilled. ✅

**Design system** — Material 3 **Expressive** in the Schism palette (cream / deep-green / mint /
terracotta), full light/dark parity, split-coin logo + adaptive icon, wavy "squiggle" loaders. See
[`schism-android/docs/design.md`](schism-android/docs/design.md). ✅

See `docs/superpowers/plans` and `docs/superpowers/specs` for the full designs (SP1 backend, SP2
Android client, SP3 SMS bridge, SP4 on-device AI).

---

## Conventions

- Every backend endpoint mirrors `schism-backend/docs/api-contract.md`.
- Android: one `:app` module organized by feature package (`groups/`, `expense/`, `dashboard/`,
  `sms/`, `finance/`, `onboarding/`, `settings/`, `core/`); shared design system in `core/theme`
  (expressive Material 3, light/dark/system) and `core/ui`.
- Tests: pure logic as plain JUnit; Room/DataStore/API flows via Robolectric + in-memory Room +
  MockWebServer.
