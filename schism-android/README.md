# schism-android

Native Android client for **Schism** — a Splitwise-style group expense splitter with an on-device
personal-finance side. Kotlin + Jetpack Compose (Material 3 Expressive), MVVM, offline-first.

> Two Gradle modules: **`:app`** (the client) and **`:parser-core`** (a pure-Kotlin, JVM-only module
> of on-device bank-SMS parsers, ~148 issuers). Backend base URL is build/env config, never a user
> setting.

---

## Feature list

### Onboarding & identity
- **Animated first-run walkthrough** — swipeable pages with original, hand-drawn Compose
  illustrations (split / snap-a-bill / voice), parallax-scaling on swipe, an animated pill page
  indicator, and a **Skip** on every page.
- **Feature-discovery grid** — an "Everything in one place" page surfacing all 8 capabilities so
  nothing stays hidden.
- **Identity capture** — name / email / phone; registers a backend user and stores a bearer token.

### Groups & expenses
- Create, join, list, **edit** (name / note / currency / participants, id-based server reconcile),
  and delete groups.
- **Add people from contacts** while creating a group.
- Expenses in **all four split modes** — evenly, shares, percentage, exact amount — with local
  validation mirroring the backend.
- **Itemised receipt split** — assign each line item to specific people, live per-person totals.
- **Balances + suggested reimbursements**; **activity feed** showing who did what.
- **Ownership distinction** — you can only edit an expense you added (Splitwise-style).
- **Group & cross-group dashboards / insights.**

### Invites
- Share / open **`schism://group/<id>`** deep links (opens straight into the group).
- **QR invites** — generate a group QR (ZXing) and **scan** to join (ML Kit code scanner).

### On-device capture & AI
- **Bank-SMS → split bridge** — incoming bank SMS parsed entirely on-device (`:parser-core`) into an
  **Inbox**; keep personal or **push into a group** as a shared expense.
- **Receipt OCR** — photograph a bill, ML Kit reads the line items, hand off to the itemised split.
  Reachable from the **Groups home ("Scan a bill")** and the Inbox.
- **Voice quick-add** — "paid 800 for dinner, split with Riya and Sam", transcribed by the on-device
  `SpeechRecognizer` and parsed by an offline NLP parser into an expense draft.

### Personal finance
- **Spending** tab — monthly totals, by-merchant breakdown, and trend, computed locally from the
  transaction ledger.

### Settle up
- Record a settlement (reimbursement) and/or **launch a UPI app prefilled** (`upi://pay`).

### Settings
- Full **profile** (name / email / phone), **default currency** (₹ INR default), **theme**
  (Light / Dark / System), About (version, groups joined, identity), and **reset app data**.

### Design system
- **Material 3 Expressive** in the Schism palette (warm cream canvas, deep-green primary, mint
  accent, terracotta) with **full light/dark parity** — see [`docs/design.md`](docs/design.md).
- Custom **split-coin logo** + adaptive launcher icon, **wavy/"squiggle" progress** loaders,
  generous rounded shapes, hairline dividers, deterministic colored avatars, compact top bars.

---

## Stack

Jetpack Compose (BOM 2024.12.01, Material 3 + expressive patterns) · Hilt DI · Retrofit +
kotlinx.serialization · Room (offline cache) · DataStore (settings) · WorkManager (SMS scan) ·
Navigation Compose · ML Kit text-recognition (OCR) + code-scanner (QR) · ZXing (QR gen) ·
`SpeechRecognizer` (voice) · ContactsContract (contacts).

## Module & package layout

```
:app  ai/schism/split/
  core/           theme (design system), ui, nav, net (Retrofit/DTOs), db (Room), settings, money, di
  onboarding/     walkthrough, illustrations, identity capture
  groups/         list · create · edit · join (link/QR/deep-link) · detail (expenses/balances/activity) · qr
  expense/        add/edit expense, split modes, data
  sms/            inbox · ingest (receiver/worker) · receipt (OCR) · itemized (AI split) · split (push-to-group)
  finance/        spending insights
  dashboard/      personal + per-group
  settings/
:parser-core      pure-Kotlin bank-SMS parsers (no Android deps, JVM-testable)
```

## Architecture

Cache-first MVVM. `Compose UI → ViewModel (StateFlow/UiState) → Repository → { Retrofit → backend, Room cache }`.
Money is `Long` minor units end-to-end. Reads observe Room (offline-viewable); writes hit the API
then refresh the cache. Expense creates carry an **idempotency key**. Every request sends
`Authorization: Bearer …`.

## Running

```bash
# Emulator reaches the host backend at 10.0.2.2:8080 (the default). For a LAN/device backend:
./gradlew :app:installDebug -Pschism.backendUrl=http://<host-ip>:8080
./gradlew :app:testDebugUnitTest        # unit + Robolectric tests
```

The debug build permits cleartext HTTP to the dev backend; release is loopback-only. Override the
backend with the `SCHISM_BACKEND_URL` env var or `-Pschism.backendUrl=…`.

## Testing

Pure logic (parsers, split builders, aggregators) as plain JUnit; Room/DataStore/API flows via
Robolectric + in-memory Room + MockWebServer.
