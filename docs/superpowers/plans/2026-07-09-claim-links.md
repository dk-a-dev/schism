# Claim Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship "claim what you ate" — scan a bill, share a link, group members open the app and claim items by proportional weight (near-live via polling), and the creator locks the final split — as an opt-in alpha feature.

**Architecture:** New Postgres `claim_sessions` + `claims` tables and REST endpoints on the existing Go/chi backend; correctness comes from a `SELECT … FOR UPDATE` state machine (`status` + `version`), not from the client. The Android side adds a `ClaimSessionRepository` + `ClaimSessionViewModel` (polls `GET`, submits weights, maps 409s), a claim screen reusing the itemised weight-stepper, a creator finalize sheet, a `/c/{sid}` deep link, and a Settings › Labs toggle gating the entry point.

**Tech Stack:** Go (chi + pgx + Postgres, golang-migrate), Kotlin/Jetpack Compose (Material3 1.5-alpha, Hilt, Retrofit + kotlinx.serialization, DataStore), JUnit + Robolectric, Go `httptest`.

## Global Constraints

- Money is `int64`/`Long` **minor units** end-to-end; the only minor→string paths are `formatMinor` (Android) — never `String.format("%.2f", x/100.0)`.
- Weights are `NUMERIC(6,2)` in Postgres and `Double` in Kotlin; a weight of `0` means "no claim" (delete the row / omit).
- Every endpoint requires a bearer token for a participant **linked to the session's group's** — reuse the existing auth middleware (`withUser`) + a group-membership check.
- Backend migrations are `internal/store/migrations/000N_name.{up,down}.sql`, auto-applied on boot; new one is `0007_claim_sessions`.
- Go: `go build ./... && go test ./...` from `schism-backend/` must pass. Android: `./gradlew :app:testDebugUnitTest` from `schism-android/` must pass.
- Commit messages: **do NOT add any `Co-Authored-By` / Claude attribution** (repo owner's standing rule).
- Proportional split math must match the existing Android `buildItemizedExpenseRequest`: each item's cost splits by weight, the **last** sharer absorbs rounding so the item is exact; tax/fees/discount/roundoff (net charge pot) split proportionally to each person's claimed subtotal, last person absorbs remainder.
- Alpha gate: the entry point is hidden unless the Settings › Labs "Claim links (alpha)" DataStore flag is ON (default OFF). Backend endpoints are always live.
- All paths are relative to repo root `/Users/devkeshwani/Developer/schism/`.

---

# Phase 1 — Backend (Go)

### Task 1: Migration — claim_sessions + claims tables

**Files:**
- Create: `schism-backend/internal/store/migrations/0007_claim_sessions.up.sql`
- Create: `schism-backend/internal/store/migrations/0007_claim_sessions.down.sql`

**Interfaces:**
- Produces: tables `claim_sessions`, `claims` (schema below), used by every later backend task.

- [ ] **Step 1: Write the up migration** (`0007_claim_sessions.up.sql`):

```sql
CREATE TABLE claim_sessions (
    id                     TEXT PRIMARY KEY,
    group_id               TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    creator_participant_id TEXT NOT NULL,
    title                  TEXT NOT NULL DEFAULT '',
    currency               TEXT NOT NULL DEFAULT '',
    items                  JSONB NOT NULL,          -- [{idx,name,qty,amountMinor}]
    tax_minor              BIGINT NOT NULL DEFAULT 0,
    fees_minor             BIGINT NOT NULL DEFAULT 0,
    discount_minor         BIGINT NOT NULL DEFAULT 0,
    roundoff_minor         BIGINT NOT NULL DEFAULT 0,
    status                 TEXT NOT NULL DEFAULT 'open',   -- open | finalized | cancelled
    version                INT  NOT NULL DEFAULT 1,
    expense_id             TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claims (
    session_id     TEXT NOT NULL REFERENCES claim_sessions(id) ON DELETE CASCADE,
    item_idx       INT  NOT NULL,
    participant_id TEXT NOT NULL,
    weight         NUMERIC(6,2) NOT NULL CHECK (weight > 0),
    PRIMARY KEY (session_id, item_idx, participant_id)
);
CREATE INDEX claims_session_idx ON claims (session_id);
```

- [ ] **Step 2: Write the down migration** (`0007_claim_sessions.down.sql`):

```sql
DROP TABLE IF EXISTS claims;
DROP TABLE IF EXISTS claim_sessions;
```

- [ ] **Step 3: Apply + verify** — `cd schism-backend && docker compose up -d --build server` (migrations auto-run on boot). Then confirm the tables exist: `docker exec schism-backend-db-1 psql -U schism -d schism -c "\d claim_sessions"` shows the columns. Expected: table description prints with no error.

- [ ] **Step 4: Commit** — `git add schism-backend/internal/store/migrations && git commit -m "feat(backend): claim_sessions + claims tables (migration 0007)"`

---

### Task 2: Store — types, CreateClaimSession, GetClaimSession

**Files:**
- Create: `schism-backend/internal/store/claims.go`
- Test: `schism-backend/internal/store/claims_test.go`

**Interfaces:**
- Consumes: `store.Store` (has `pool *pgxpool.Pool`), `id.New()`.
- Produces:
  - `type ClaimItem struct { Idx int; Name string; Qty int; AmountMinor int64 }` (JSON tags `idx,name,qty,amountMinor`).
  - `type ClaimSessionInput struct { GroupID, CreatorParticipantID, Title, Currency string; Items []ClaimItem; TaxMinor, FeesMinor, DiscountMinor, RoundoffMinor int64 }`
  - `type Claim struct { ItemIdx int; ParticipantID string; Weight float64 }`
  - `type ClaimSession struct { ID, GroupID, CreatorParticipantID, Title, Currency, Status string; Items []ClaimItem; TaxMinor, FeesMinor, DiscountMinor, RoundoffMinor int64; Version int; ExpenseID *string; Claims []Claim }`
  - `func (s *Store) CreateClaimSession(ctx, in ClaimSessionInput) (ClaimSession, error)`
  - `func (s *Store) GetClaimSession(ctx, sid string) (*ClaimSession, error)` (nil,nil when absent)

- [ ] **Step 1: Write the failing test** (`claims_test.go`) — reuse the package's existing test DB harness (see `edge_test.go` for how it gets a `*Store`; mirror its setup: it creates a group + participants first). Then:

```go
func TestCreateAndGetClaimSession(t *testing.T) {
    st, cleanup := newTestStore(t)   // same helper the other store tests use
    defer cleanup()
    ctx := context.Background()
    g, _ := st.CreateGroup(ctx, store.GroupInput{Name: "Trip", Currency: "₹",
        Participants: []store.ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
    creator := g.Participants[0].ID

    cs, err := st.CreateClaimSession(ctx, store.ClaimSessionInput{
        GroupID: g.ID, CreatorParticipantID: creator, Title: "Dinner", Currency: "₹",
        Items:   []store.ClaimItem{{Idx: 0, Name: "Biryani", Qty: 2, AmountMinor: 30000}},
        TaxMinor: 1500,
    })
    if err != nil { t.Fatal(err) }
    if cs.Status != "open" || cs.Version != 1 { t.Fatalf("bad session %+v", cs) }

    got, err := st.GetClaimSession(ctx, cs.ID)
    if err != nil || got == nil { t.Fatalf("get: %v %v", got, err) }
    if len(got.Items) != 1 || got.Items[0].AmountMinor != 30000 { t.Fatalf("items %+v", got.Items) }
    if got.TaxMinor != 1500 { t.Fatalf("tax %d", got.TaxMinor) }
}
```

(If the package has no reusable `newTestStore`, add one mirroring `edge_test.go`'s DB setup.)

- [ ] **Step 2: Run** — `cd schism-backend && go test ./internal/store/ -run TestCreateAndGetClaimSession` → FAIL (undefined symbols).

- [ ] **Step 3: Implement `claims.go`** — the types above, plus `CreateClaimSession` (marshal `Items` to JSONB, `INSERT … RETURNING id, version, status, created_at`) and `GetClaimSession` (SELECT the session, unmarshal `items`, then `SELECT item_idx, participant_id, weight FROM claims WHERE session_id=$1` into `Claims`). Return `nil,nil` on `pgx.ErrNoRows`.

- [ ] **Step 4: Run** → PASS. Then `go build ./...`.

- [ ] **Step 5: Commit** — `feat(backend): claim-session store types + create/get`

---

### Task 3: Store — UpsertClaims (locked, version-guarded)

**Files:**
- Modify: `schism-backend/internal/store/claims.go`
- Test: `schism-backend/internal/store/claims_test.go`

**Interfaces:**
- Produces: sentinel errors `var ErrClaimLocked = errors.New("session locked")` and `var ErrClaimStale = errors.New("session version stale")`; `func (s *Store) UpsertClaims(ctx, sid, participantID string, expectedVersion int, weights map[int]float64) error` — replaces that participant's rows: inside a tx, `SELECT status, version … FOR UPDATE`; if `status != "open"` → `ErrClaimLocked`; if `version != expectedVersion` → `ErrClaimStale`; else `DELETE FROM claims WHERE session_id=$1 AND participant_id=$2`, then insert one row per `weight > 0`.

- [ ] **Step 1: Failing test**:

```go
func TestUpsertClaimsReplacesAndGuards(t *testing.T) {
    st, cleanup := newTestStore(t); defer cleanup()
    ctx := context.Background()
    g, _ := st.CreateGroup(ctx, store.GroupInput{Name: "T", Currency: "₹",
        Participants: []store.ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
    cs, _ := st.CreateClaimSession(ctx, store.ClaimSessionInput{GroupID: g.ID,
        CreatorParticipantID: g.Participants[0].ID, Items: []store.ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})
    ru := g.Participants[1].ID

    if err := st.UpsertClaims(ctx, cs.ID, ru, 1, map[int]float64{0: 2}); err != nil { t.Fatal(err) }
    got, _ := st.GetClaimSession(ctx, cs.ID)
    if len(got.Claims) != 1 || got.Claims[0].Weight != 2 { t.Fatalf("claims %+v", got.Claims) }

    // stale version rejected
    if err := st.UpsertClaims(ctx, cs.ID, ru, 99, map[int]float64{0: 1}); err != store.ErrClaimStale {
        t.Fatalf("want ErrClaimStale, got %v", err)
    }
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** `UpsertClaims` per the interface (tx + `FOR UPDATE` + guards + delete/insert). **Step 4: Run** → PASS + `go build ./...`. **Step 5: Commit** `feat(backend): UpsertClaims with row-lock + version/status guard`.

---

### Task 4: Store — EditItems (drops edited items' claims, bumps version)

**Files:** Modify `schism-backend/internal/store/claims.go`; Test `claims_test.go`.

**Interfaces:**
- Produces: `func (s *Store) EditItems(ctx, sid string, items []ClaimItem) (int, error)` — tx + `FOR UPDATE`; `ErrClaimLocked` if not open; compute which `item_idx` changed (name/qty/amount differ) or were removed vs the stored items; `DELETE FROM claims WHERE session_id=$1 AND item_idx = ANY($2)` for those; `UPDATE claim_sessions SET items=$new, version=version+1`; return the new version.

- [ ] **Step 1: Failing test** — create session + a claim on item 0, call `EditItems` changing item 0's amount, assert the claim on item 0 is gone and version is 2:

```go
func TestEditItemsDropsChangedItemClaims(t *testing.T) {
    st, cleanup := newTestStore(t); defer cleanup(); ctx := context.Background()
    g, _ := st.CreateGroup(ctx, store.GroupInput{Name: "T", Currency: "₹", Participants: []store.ParticipantInput{{Name: "Dev"}}})
    cs, _ := st.CreateClaimSession(ctx, store.ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: g.Participants[0].ID,
        Items: []store.ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})
    _ = st.UpsertClaims(ctx, cs.ID, g.Participants[0].ID, 1, map[int]float64{0: 1})

    v, err := st.EditItems(ctx, cs.ID, []store.ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 2000}})
    if err != nil || v != 2 { t.Fatalf("v=%d err=%v", v, err) }
    got, _ := st.GetClaimSession(ctx, cs.ID)
    if len(got.Claims) != 0 { t.Fatalf("claims should be dropped: %+v", got.Claims) }
}
```

- [ ] **Step 2–5:** run→FAIL, implement `EditItems`, run→PASS + build, commit `feat(backend): EditItems drops changed items' claims + bumps version`.

---

### Task 5: Store — Finalize (the lock + expense build)

**Files:**
- Modify: `schism-backend/internal/store/claims.go`
- Modify: `schism-backend/internal/store/expenses.go` (extract a tx-accepting expense insert helper — read this file first; it already inserts expenses + paid_for)
- Test: `schism-backend/internal/store/claims_test.go`

**Interfaces:**
- Consumes: the existing expense-insert SQL (extract `func (s *Store) insertExpenseTx(ctx, tx pgx.Tx, e ExpenseInput) (string, error)` from the current `CreateExpense`, and make `CreateExpense` call it inside its own tx — pure refactor, keep `CreateExpense` behavior identical).
- Produces:
  - `type UnclaimedResolution struct { ItemIdx int; Mode string; ParticipantID string }` — `Mode` ∈ `assign|split|cover`; `ParticipantID` set for `assign`.
  - `func computeClaimSplit(items []ClaimItem, claims []Claim, tax, fees, discount, roundoff int64, resolutions []UnclaimedResolution, allParticipantIDs []string, creatorID string) map[string]int64` — **pure** (no DB): per item, apply resolutions to synthesize weights for unclaimed items (`assign`→weight 1 on that person; `split`→weight 1 on everyone; `cover`→weight 1 on creator), then split each item's amount by weight (last sharer absorbs rounding), then split the net charge pot `tax+fees-discount+roundoff` proportionally to each person's item subtotal (last person absorbs remainder). Returns participantID→owedMinor.
  - `func (s *Store) FinalizeClaimSession(ctx, sid string, expectedVersion int, resolutions []UnclaimedResolution) (string, error)` — tx + `FOR UPDATE`; if `status=="finalized"` return existing `expense_id`; if `cancelled` → error; if `version != expectedVersion` → `ErrClaimStale`; load claims + group participants; `owed := computeClaimSplit(...)`; build an `ExpenseInput` (splitMode `BY_AMOUNT`, `paidBy=creator`, `paidFor` from `owed`, amount = sum) and `insertExpenseTx`; `UPDATE claim_sessions SET status='finalized', expense_id=$eid`. Returns expense_id.

- [ ] **Step 1: Failing test** for the pure math first (fast, no DB) — mirror the Android `ItemizedRequestTest` cases:

```go
func TestComputeClaimSplitWeightedWithTax(t *testing.T) {
    items := []store.ClaimItem{{Idx: 0, Name: "Dish", Qty: 3, AmountMinor: 30000}}
    claims := []store.Claim{{ItemIdx: 0, ParticipantID: "dev", Weight: 2}, {ItemIdx: 0, ParticipantID: "ru", Weight: 1}}
    owed := store.ComputeClaimSplit(items, claims, 3000, 0, 0, 0, nil, []string{"dev", "ru"}, "dev")
    // 30000 split 2:1 → dev 20000, ru 10000; tax 3000 split 2:1 → dev 2000, ru 1000
    if owed["dev"] != 22000 || owed["ru"] != 11000 { t.Fatalf("owed %+v", owed) }
}

func TestComputeClaimSplitUnclaimedSplitEvenly(t *testing.T) {
    items := []store.ClaimItem{{Idx: 0, Name: "Nobody", Qty: 1, AmountMinor: 10000}}
    owed := store.ComputeClaimSplit(items, nil, 0, 0, 0, 0,
        []store.UnclaimedResolution{{ItemIdx: 0, Mode: "split"}}, []string{"a", "b"}, "a")
    if owed["a"] != 5000 || owed["b"] != 5000 { t.Fatalf("owed %+v", owed) }
}
```

(Export it as `ComputeClaimSplit` so the test in-package or `store_test` can call it.)

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** `ComputeClaimSplit` (pure) using the exact weighted algorithm from `schism-android/.../sms/itemized/ItemizedRequest.kt` (read it and port: per-item weighted division with last-sharer remainder, then proportional charge-pot). **Step 4: Run** → PASS.
- [ ] **Step 6: Failing DB test** for `FinalizeClaimSession` — create session, add claims, finalize, assert an expense row exists with the right per-participant `shares` and session `status='finalized'` + `expense_id` set; a second finalize returns the same id. **Step 7:** refactor `insertExpenseTx` out of `CreateExpense` (keep its test green), implement `FinalizeClaimSession`. **Step 8:** `go test ./internal/store/...` green + `go build ./...`.
- [ ] **Step 9: Commit** `feat(backend): finalize claim session (locked expense build) + pure split math`.

---

### Task 6: Store — CancelClaimSession + membership helper

**Files:** Modify `schism-backend/internal/store/claims.go`; Test `claims_test.go`.

**Interfaces:**
- Produces: `func (s *Store) CancelClaimSession(ctx, sid string) error` (`UPDATE … SET status='cancelled' WHERE id=$1 AND status='open'`); `func (s *Store) ParticipantForUserInGroup(ctx, userID, groupID string) (string, error)` — returns the participant id linked to `userID` in `groupID` (for auth: a caller may act only as their own participant), `""` if none.

- [ ] **Step 1–5:** failing test (cancel flips status; a claim after cancel → `ErrClaimLocked`; `ParticipantForUserInGroup` returns the linked id) → implement → green → commit `feat(backend): cancel session + participant-for-user lookup`.

---

### Task 7: API — handlers + routes

**Files:**
- Create: `schism-backend/internal/api/claims.go`
- Modify: `schism-backend/internal/api/router.go` (add routes)
- Modify: `schism-backend/internal/api/dto.go` (claim DTOs)
- Test: `schism-backend/internal/api/claims_test.go`

**Interfaces:**
- Consumes: everything from Tasks 2–6; the existing auth middleware `withUser` (puts the user in context — see `auth.go`), `writeJSON`, `writeErr`, `userFromContext`.
- Produces routes (all under the authed group):
  - `POST /v1/groups/{id}/claim-sessions` → body `{title,currency,items:[{idx,name,qty,amountMinor}],taxMinor,feesMinor,discountMinor,roundoffMinor}`; creator = the caller's participant in that group (`ParticipantForUserInGroup`; 403 if not a member); returns the session JSON.
  - `GET /v1/claim-sessions/{sid}` → session + claims + `owesPreview` (call `ComputeClaimSplit` with the *current* claims and no resolutions, over all participants); 403 if caller isn't a member of the session's group.
  - `PUT /v1/claim-sessions/{sid}/claims` → body `{expectedVersion,weights:[{itemIdx,weight}]}`; caller's participant only; maps `ErrClaimLocked`→409 `LOCKED`, `ErrClaimStale`→409 `VERSION_STALE`.
  - `POST /v1/claim-sessions/{sid}/finalize` → creator only (403 otherwise); body `{expectedVersion,resolutions:[{itemIdx,mode,participantId}]}`; returns `{expenseId}`.
  - `POST /v1/claim-sessions/{sid}/cancel` → creator only.
  - `PATCH /v1/claim-sessions/{sid}/items` → creator only; body `{items:[…]}`; returns `{version}`.

- [ ] **Step 1: Failing httptest** — spin the router with a test store (mirror `expenses_test.go`), register a user + group, then: create session (201), GET (200 with items), PUT claims (200), GET shows the claim + owesPreview, finalize (200 with expenseId), PUT after finalize → 409 LOCKED.
- [ ] **Step 2–4:** run→FAIL, implement handlers + wire routes + DTOs, run→PASS.
- [ ] **Step 5: Commit** `feat(backend): claim-session API (create/get/claim/finalize/cancel/edit-items)`.

---

### Task 8: Concurrency test — claim vs finalize never loses a claim

**Files:** Test `schism-backend/internal/store/claims_test.go`.

- [ ] **Step 1: Write the test** — create a session with one item; launch `UpsertClaims` (Ru weights item 0) and `FinalizeClaimSession` concurrently (two goroutines, `sync.WaitGroup`); assert: no panic; the finalize produced exactly one expense; and EITHER Ru's claim is in the finalized expense OR the `UpsertClaims` returned `ErrClaimLocked` — never a silent loss. Run it with `-race`.

```go
func TestClaimVsFinalizeRace(t *testing.T) {
    // …setup session + participants dev(creator), ru…
    var wg sync.WaitGroup; wg.Add(2)
    var claimErr error
    go func() { defer wg.Done(); claimErr = st.UpsertClaims(ctx, sid, ru, 1, map[int]float64{0: 1}) }()
    go func() { defer wg.Done(); _, _ = st.FinalizeClaimSession(ctx, sid, 1, nil /*or resolve*/) }()
    wg.Wait()
    // assert: claimErr == nil (claim counted) XOR claimErr == store.ErrClaimLocked
    if claimErr != nil && claimErr != store.ErrClaimLocked { t.Fatalf("unexpected: %v", claimErr) }
    // assert exactly one expense exists for the group
}
```

- [ ] **Step 2:** `go test ./internal/store/ -run TestClaimVsFinalizeRace -race` → PASS. **Step 3: Commit** `test(backend): claim-vs-finalize race is safe`.

---

### Task 9: `/c/{sid}` deep-link landing

**Files:** Modify `schism-backend/internal/api/invite.go` (or create `claims_link.go`); Modify `router.go`.

**Interfaces:**
- Produces: `GET /c/{sid}` → an HTML landing (mirror the existing `/g/{id}` group-invite landing) with an "Open in Schism" button linking to `schism://claim/{sid}`, so a shared https link bounces into the app.

- [ ] **Step 1–4:** mirror `/g/{id}`'s handler + route; a quick `curl -s localhost:8080/c/test | grep -o 'schism://claim'` returns the deep link. **Step 5: Commit** `feat(backend): /c/{sid} claim deep-link landing`.

> **Deploy checkpoint:** the user redeploys the backend (migrations auto-run) before the Android side can talk to it.

---

# Phase 2 — Android

### Task 10: DTOs + ApiService endpoints

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/Dto.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/ApiService.kt`

**Interfaces:**
- Produces (`@Serializable` DTOs, camelCase to match backend JSON): `ClaimItemDto(idx:Int,name:String,qty:Int,amountMinor:Long)`, `ClaimDto(itemIdx:Int,participantId:String,weight:Double)`, `ClaimSessionDto(id,groupId,creatorParticipantId,title,currency,status:String, items:List<ClaimItemDto>, taxMinor,feesMinor,discountMinor,roundoffMinor:Long, version:Int, expenseId:String?, claims:List<ClaimDto>, owesPreview:Map<String,Long>)`, `CreateClaimSessionRequest(...)`, `PutClaimsRequest(expectedVersion:Int, weights:List<ClaimWeightDto>)`, `ClaimWeightDto(itemIdx:Int,weight:Double)`, `FinalizeRequest(expectedVersion:Int, resolutions:List<ResolutionDto>)`, `ResolutionDto(itemIdx:Int,mode:String,participantId:String?)`, `FinalizeResponse(expenseId:String)`, `EditItemsRequest(items:List<ClaimItemDto>)`, `VersionResponse(version:Int)`.
- Produces `ApiService` suspend funs: `createClaimSession(@Path("id") groupId, @Body): ClaimSessionDto`, `getClaimSession(@Path("sid")): ClaimSessionDto`, `putClaims(@Path("sid"), @Body): retrofit2.Response<Unit>` (raw Response so the VM reads the 409 code), `finalizeClaimSession(@Path("sid"), @Body): FinalizeResponse`, `cancelClaimSession(@Path("sid"))`, `editClaimItems(@Path("sid"), @Body): VersionResponse`.

- [ ] **Step 1:** Add the DTOs + endpoints. **Step 2:** `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL. **Step 3: Commit** `feat(android): claim-session DTOs + API`.

---

### Task 11: Pure client-side "you owe" preview + test

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/ClaimMath.kt`
- Test: `schism-android/app/src/test/java/ai/schism/split/claim/ClaimMathTest.kt`

**Interfaces:**
- Produces: `fun previewOwes(items: List<ClaimItemDto>, claims: List<ClaimDto>, taxMinor, feesMinor, discountMinor, roundoffMinor: Long): Map<String, Long>` — the same weighted + charge-pot algorithm as the server's `ComputeClaimSplit` (no resolutions; only existing claims). Used for the live "you owe ₹…" without waiting for a poll.

- [ ] **Step 1: Failing test** (plain JUnit4):

```kotlin
@Test fun weightedWithTax() {
    val items = listOf(ClaimItemDto(0, "Dish", 3, 30000))
    val claims = listOf(ClaimDto(0, "dev", 2.0), ClaimDto(0, "ru", 1.0))
    val owes = previewOwes(items, claims, taxMinor = 3000, 0, 0, 0)
    assertEquals(22000L, owes["dev"]); assertEquals(11000L, owes["ru"])
}
```

- [ ] **Step 2–5:** run→FAIL, implement `previewOwes` (port `buildItemizedExpenseRequest`'s math), run→PASS, commit `feat(android): client-side claim owes preview`.

---

### Task 12: ClaimSessionRepository + ClaimSessionViewModel (poll, claim, 409)

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/ClaimSessionRepository.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/ClaimSessionViewModel.kt`
- Test: `schism-android/app/src/test/java/ai/schism/split/claim/ClaimSessionViewModelTest.kt`

**Interfaces:**
- Consumes: `ApiService` (Task 10), `previewOwes` (Task 11), `SettingsRepository` (for the caller's userId → participant).
- Produces:
  - `ClaimSessionRepository` — thin wrapper over `ApiService` returning `Result<…>`; `putClaims` maps HTTP 409 body code to `ClaimError.Locked` / `ClaimError.Stale`.
  - `ClaimSessionViewModel(@Assisted sid)` (or reads `sid` from `SavedStateHandle`): `state: StateFlow<ClaimUiState>` with `session`, `myWeights: Map<Int,Double>`, `myOwes: Long`, `status`, `error`. A polling loop (`while active` `delay(3000)` `getClaimSession`) that stops when `status != open`. `setWeight(itemIdx, weight)` (updates local + debounced `putClaims(expectedVersion)`), `adjustWeight(itemIdx, delta)`. On `Stale` → refetch + keep the user's still-valid weights; on `Locked` → set `status=finalized` and stop polling.

- [ ] **Step 1: Failing test** (Robolectric, `@Config(sdk=[33])`, MockWebServer like `GroupDetailViewModelTest`) — enqueue a session GET, assert `state.myOwes` reflects the caller's weights after `setWeight`; enqueue a 409 LOCKED on `putClaims`, assert `state.status` becomes finalized and polling stops.
- [ ] **Step 2–5:** run→FAIL, implement repo + VM (poll/debounce/409 mapping), run→PASS, commit `feat(android): claim-session repository + view model with polling and 409 handling`.

---

### Task 13: Claim screen (reuses the weight stepper)

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/ClaimScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/nav/{Routes.kt,AppNav.kt}`

**Interfaces:**
- Consumes: `ClaimSessionViewModel`. Produces: `ClaimScreen(onBack, onFinalized)`; route `Routes.CLAIM = "claim/{sid}"` + `fun claim(sid)`, and a `deepLinks` entry `schism://claim/{sid}` on the composable.

- [ ] **Step 1:** Build the screen: an **ALPHA** badge; a title + "₹total · date"; per-item cards (reuse the itemised item-card layout) each with a −/+/typed weight stepper bound to `viewModel.adjustWeight/setWeight` and a small "claimed by" avatars row (from `session.claims`); a sticky "You owe ₹…" footer; when `status==finalized` a read-only "🔒 The creator locked this split" state + `onFinalized`. Creator (their participant == `creatorParticipantId`) additionally sees a **Finalize** button opening the sheet from Task 14. Add the route + deep link in `AppNav`/`Routes`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL. **Step 3: Commit** `feat(android): claim screen + deep link`.

---

### Task 14: Finalize sheet (creator resolves unclaimed items)

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/FinalizeSheet.kt`
- Modify: `ClaimSessionViewModel.kt` (add `finalize(resolutions, onDone)`)

**Interfaces:**
- Produces: `FinalizeSheet(session, onResolveFinalize: (List<ResolutionDto>) -> Unit, onDismiss)` — lists items with zero total weight; each gets a choice chip (Assign→participant picker / Split evenly / I'll cover it); a "Split the rest evenly" shortcut; a Finalize button enabled only when every unclaimed item has a resolution. `viewModel.finalize` calls `finalizeClaimSession`, on success navigates via `onFinalized`.

- [ ] **Step 1:** Build the sheet + VM method (`ResolutionDto(itemIdx, mode, participantId)`; mode `assign|split|cover`). **Step 2:** suite green. **Step 3: Commit** `feat(android): creator finalize sheet with unclaimed-item resolution`.

---

### Task 15: Settings › Labs alpha toggle + entry point

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/settings/SettingsRepository.kt` (a `claimLinksAlpha` DataStore boolean, default false, + `setClaimLinksAlpha`)
- Modify: `settings/SettingsViewModel.kt` + `settings/SettingsScreen.kt` (a "Labs" section with a Switch)
- Modify: `sms/itemized/ItemizedSplitScreen.kt` (show a "Let everyone claim" button — only when the flag is on — that creates a session via a new `ItemizedSplitViewModel.startClaimSession(onCreated: (sid) -> Unit)` and navigates to the claim screen)
- Modify: `sms/itemized/ItemizedSplitViewModel.kt` (add `startClaimSession` — builds `CreateClaimSessionRequest` from the current items + tax and calls `createClaimSession`, returns the sid)

**Interfaces:**
- Consumes: `SettingsRepository.claimLinksAlpha: Flow<Boolean>`, `ApiService.createClaimSession`.

- [ ] **Step 1:** Add the DataStore flag + Labs toggle UI. **Step 2:** Add `startClaimSession` to `ItemizedSplitViewModel` and the gated "Let everyone claim" button on `ItemizedSplitScreen` that navigates to `Routes.claim(sid)`. **Step 3:** suite green. **Step 4: Commit** `feat(android): alpha Labs toggle gating claim-links entry point`.

---

### Task 16: Release (alpha)

**Files:** Modify `schism-android/app/build.gradle.kts` (bump version).

- [ ] **Step 1:** Bump `versionCode`/`versionName` (next patch). **Step 2:** full suite + signed `assembleRelease`; `apksigner verify`. **Step 3:** commit `release: Schism X.Y.Z — claim links (alpha)`, tag, push, `gh release create` with the APK; release notes call it an opt-in alpha (enable in Settings › Labs). **Step 4:** user redeploys backend if not already, enables the Labs toggle, and runs the create→share→claim→finalize flow on a device.

## Self-review notes
- Spec coverage: tables+endpoints (T1–T9), locking/version (T3–T5, T8), pure split math parity (T5 backend, T11 android), poll+409 (T12), claim UI (T13), finalize+unclaimed (T14), alpha gate (T15), deep link (T9,T13). All spec sections mapped.
- The proportional math is implemented **twice** (Go `ComputeClaimSplit`, Kotlin `previewOwes`) by design — server is authoritative, client is preview; both are unit-tested against the same golden cases (weighted 2:1 with tax, unclaimed split-evenly, exact rounding) to keep them in lockstep.
- Money stays `int64`/`Long` minor units throughout; weights are `NUMERIC(6,2)`/`Double`.
