# Backend Security and OCR Model Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Go backend safe for production group data and expose a versioned, checksum-pinned PaddleOCR download control plane.

**Architecture:** Central middleware resolves required sessions while a single membership guard protects every group resource. One-time participant invite tokens replace group IDs as capabilities. Public model metadata maps a compile-time allowlist to revision-pinned Hugging Face redirects; the API never accepts an upstream URL from clients.

**Tech Stack:** Go 1.26.2, chi v5, pgx v5/Postgres 16, bcrypt, `golang.org/x/time/rate`, httptest, testify.

## Global Constraints

- Preserve `/health`, `/ping`, `/v1/auth/register`, `/v1/auth/login`, invite landing, and model downloads as the only public surfaces.
- Every group/expense/claim/dashboard route requires authenticated membership.
- Existing migrations stay immutable; add migrations `0011` and `0012`.
- Raw tokens and invite tokens are never stored or logged.
- Model files are exactly 6,298,800 bytes across the three verified Paddle artifacts.
- All client errors are stable/sanitized; internal errors are request-ID-correlated server logs.

---

### Task 1: Safe HTTP server and JSON boundary

**Files:**
- Create: `schism-backend/internal/api/request.go`
- Create: `schism-backend/internal/api/request_test.go`
- Create: `schism-backend/internal/api/security.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/internal/api/errors.go`
- Modify: `schism-backend/internal/api/ping.go`
- Modify: `schism-backend/cmd/server/main.go`
- Test: `schism-backend/internal/api/edge_test.go`

**Interfaces:**
- Produces: `decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool`, `requestID(r *http.Request) string`, and `newHTTPServer(addr string, handler http.Handler) *http.Server`.
- Consumes: existing chi router and `writeJSON`/`writeErr` response helpers.

- [ ] **Step 1: Write failing request-boundary and health tests**

Add tests that send a body larger than 1 MiB, an unknown JSON field, two JSON documents, a non-JSON
content type, and `GET /health`. Assert `413`, `400`, `400`, `415`, and valid
`application/json` `{"status":"ok"}` respectively. Add a test ensuring a handler's synthetic store
error becomes `{"error":"internal_error"}` and not the store message.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `cd schism-backend && go test ./internal/api -run 'TestDecodeJSON|TestHealth|TestSanitizedError' -count=1`

Expected: FAIL because the decoder helper, valid health body, and sanitized internal error path do
not exist.

- [ ] **Step 3: Implement the boundary and server configuration**

Implement `decodeJSON` with `http.MaxBytesReader(..., 1<<20)`, content-type validation,
`DisallowUnknownFields`, and a second decode requiring `io.EOF`. Add request ID and headers
`X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and API `Cache-Control: no-store`.
Configure `http.Server` with `ReadHeaderTimeout: 5s`, `ReadTimeout: 15s`, `WriteTimeout: 30s`,
`IdleTimeout: 60s`, and `MaxHeaderBytes: 1<<20`; handle SIGINT/SIGTERM with a 10-second shutdown
context. Return valid health JSON and log internal errors with request ID.

- [ ] **Step 4: Replace direct JSON decoders and verify**

Use `decodeJSON` in `users.go`, `groups.go`, `expenses.go`, and `claims.go`. Run:
`cd schism-backend && gofmt -w cmd internal && go test ./internal/api ./internal/config -count=1 && go vet ./...`.

Expected: PASS with no raw decoder remaining in production handlers.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/cmd schism-backend/internal/api
git commit -m "fix(backend): harden HTTP and JSON boundaries"
```

### Task 2: Expiring sessions and authentication throttling

**Files:**
- Create: `schism-backend/internal/store/migrations/0011_session_expiry.up.sql`
- Create: `schism-backend/internal/store/migrations/0011_session_expiry.down.sql`
- Create: `schism-backend/internal/api/ratelimit.go`
- Create: `schism-backend/internal/api/ratelimit_test.go`
- Modify: `schism-backend/internal/store/auth.go`
- Modify: `schism-backend/internal/store/users.go`
- Modify: `schism-backend/internal/store/auth_test.go`
- Modify: `schism-backend/internal/api/users.go`
- Modify: `schism-backend/internal/api/auth.go`
- Modify: `schism-backend/internal/api/auth_test.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/go.mod`
- Modify: `schism-backend/go.sum`

**Interfaces:**
- Produces: `requireUser(http.Handler) http.Handler`, session rows with `created_at`, `last_used_at`,
  `expires_at`, and `newKeyedLimiter(rate.Limit, burst int, ttl time.Duration)`.
- Consumes: `rawTokenFromRequest`, `TokenHash`, and `UserByToken`.

- [ ] **Step 1: Write failing auth lifecycle tests**

Test missing/invalid/expired sessions as `401`, existing migrated tokens as initially valid, logout
revocation, normalized case-insensitive email registration conflicts, 8-character minimum password,
bounded 120-character email/100-character name, and the sixth rapid login attempt from one key as
`429` with `Retry-After`.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd schism-backend && go test ./internal/api ./internal/store -run 'Test.*(Session|Login|Register|Rate)' -count=1`

Expected: FAIL on expiry, validation, and throttling assertions.

- [ ] **Step 3: Add migration and minimal implementation**

Add non-null timestamps to `tokens`, set existing rows to a 90-day expiry from migration time, and
index `(token_hash, expires_at)`. Make new sessions expire after 90 days and update `last_used_at` at
most once per hour. Add `requireUser`. Normalize emails with `strings.ToLower(strings.TrimSpace())`.
Delete the legacy public `POST /v1/users` route while keeping its store function only until Android
migration tests prove it unused. Add `golang.org/x/time/rate` and keyed in-memory limiters: register
3/minute/IP, login 5/minute/IP+email, bounded entries evicted after 15 minutes.

- [ ] **Step 4: Verify auth and migration behavior**

Run: `cd schism-backend && gofmt -w internal && go test ./internal/api ./internal/store -count=1 && go test -race ./internal/api ./internal/store`.

Expected: PASS; no auth response or log contains password/token hashes.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/go.mod schism-backend/go.sum schism-backend/internal/api schism-backend/internal/store
git commit -m "feat(backend): expire and throttle authenticated sessions"
```

### Task 3: Central group membership authorization

**Files:**
- Create: `schism-backend/internal/api/authorize.go`
- Create: `schism-backend/internal/api/authorize_test.go`
- Modify: `schism-backend/internal/store/groups.go`
- Modify: `schism-backend/internal/store/groups_test.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/internal/api/groups.go`
- Modify: `schism-backend/internal/api/expenses.go`
- Modify: `schism-backend/internal/api/balances.go`
- Modify: `schism-backend/internal/api/activities.go`
- Modify: `schism-backend/internal/api/stats.go`
- Modify: `schism-backend/internal/api/dashboard.go`
- Modify: `schism-backend/internal/api/claims.go`
- Modify: `schism-backend/internal/api/claims_test.go`
- Modify: `schism-backend/internal/api/e2e_test.go`
- Modify: `schism-backend/internal/api/edge_test.go`
- Modify: `schism-backend/internal/api/expenses_test.go`
- Modify: `schism-backend/internal/api/groups_test.go`
- Modify: `schism-backend/internal/api/users_test.go`

**Interfaces:**
- Produces: `memberParticipant(w, r, groupID) (participantID string, ok bool)` and
  `authorizedGroupIDs(ctx, userID string, requested []string) ([]string, error)`.
- Consumes: required user context from Task 2 and `ParticipantForUserInGroup`.

- [ ] **Step 1: Add a table-driven route authorization matrix**

For every group-scoped method/path, exercise anonymous, valid member, valid non-member, and spoofed
`addedBy` callers. Assert `401`, success, `403`, and server-derived actor. Add tests proving list and
personal-dashboard IDs are intersected with membership.

- [ ] **Step 2: Run matrix and confirm insecure cases fail**

Run: `cd schism-backend && go test ./internal/api -run 'TestAuthorizationMatrix|TestActorCannotBeSpoofed' -count=1`

Expected: FAIL because current ID-only access succeeds.

- [ ] **Step 3: Implement membership guards and server-derived actors**

Mount authenticated routes under `requireUser`; resolve the caller's participant before store work.
Create groups only when authenticated and force the creator's participant `user_id` to the caller.
Ignore client `addedBy`; set it from membership. Return `403` for an existing group where caller is
not a member and `404` when absent. Filter all multi-ID queries in the store.

- [ ] **Step 4: Run all backend correctness tests**

Run: `cd schism-backend && gofmt -w internal && go test ./... -count=1 && go test -race ./...`

Expected: PASS; existing tests use authenticated member fixtures rather than weakening guards.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/api schism-backend/internal/store
git commit -m "fix(backend): require membership for group data"
```

### Task 4: One-time existing-participant invite redemption

**Files:**
- Create: `schism-backend/internal/store/migrations/0012_participant_invites.up.sql`
- Create: `schism-backend/internal/store/migrations/0012_participant_invites.down.sql`
- Create: `schism-backend/internal/store/invites.go`
- Create: `schism-backend/internal/store/invites_test.go`
- Create: `schism-backend/internal/api/invites.go`
- Create: `schism-backend/internal/api/invites_test.go`
- Modify: `schism-backend/internal/api/invite.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/internal/store/auth.go`

**Interfaces:**
- Produces: `CreateParticipantInvite(ctx, groupID, participantID, creatorUserID string) (raw string, expiresAt time.Time, error)`, `PreviewParticipantInvite(ctx, raw string) (*InvitePreview, error)`, and `RedeemParticipantInvite(ctx, raw, userID string) (groupID string, error)`.
- Consumes: Task 3 membership checks; outputs Android contracts `POST /v1/groups/{groupID}/participants/{participantID}/invite`, `GET /v1/invites/{token}`, and `POST /v1/invites/{token}/redeem`.

- [ ] **Step 1: Write failing store/API invite tests**

Test 32-byte random tokens, hash-only storage, seven-day expiry, organizer membership, participant
must be unlinked, safe preview fields only, authenticated redemption, replay conflict, expiry,
concurrent double redemption, and inability to replace a linked participant.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd schism-backend && go test ./internal/store ./internal/api -run 'Test.*ParticipantInvite' -count=1`

Expected: FAIL because tables and endpoints do not exist.

- [ ] **Step 3: Implement transactional token lifecycle and landing page**

Create `participant_invites` with hashed-token uniqueness and foreign keys. Lock the invite and
participant rows during redemption, then set participant `user_id` and `redeemed_at` atomically.
Serve `/i/{token}` with an escaped `schism://invite/{token}` link. Change legacy `/g/{groupID}` to a
non-data-leaking upgrade page. Remove unverified `ClaimParticipantsByPhone` calls from register/login.

- [ ] **Step 4: Verify invite concurrency and full suite**

Run: `cd schism-backend && gofmt -w internal && go test ./... -count=1 && go test -race ./internal/store ./internal/api`.

Expected: PASS; exactly one concurrent redemption succeeds.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/api schism-backend/internal/store
git commit -m "feat(backend): add secure participant invitations"
```

### Task 5: Pinned OCR manifest and redirects

**Files:**
- Create: `schism-backend/internal/modelcatalog/catalog.go`
- Create: `schism-backend/internal/modelcatalog/catalog_test.go`
- Create: `schism-backend/internal/api/ocr_model.go`
- Create: `schism-backend/internal/api/ocr_model_test.go`
- Modify: `schism-backend/internal/api/model.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/README.md`
- Modify: `schism-backend/docs/api-contract.md`

**Interfaces:**
- Produces: `modelcatalog.OCRManifest`, `GET /v1/models/ocr/manifest`,
  `GET|HEAD /v1/models/ocr/2026.06/{det.onnx|rec.onnx|rec.yml}`.
- Consumes: exact sizes/checksums and official revision-pinned URLs below.

- [ ] **Step 1: Write catalog and HTTP contract tests**

Assert manifest version `2026.06`, minimum app code `10300`, total bytes `6298800`, stable ETag,
public caching, HEAD parity, unknown version/file `404`, no caller-supplied URL, and redirects to:

```text
https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/2ba1506c0380b8f0b03dd142459aac66d4421f6c/inference.onnx
https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30/inference.onnx
https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30/inference.yml
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `cd schism-backend && go test ./internal/modelcatalog ./internal/api -run 'Test.*OCR' -count=1`

Expected: FAIL because catalog/endpoints do not exist.

- [ ] **Step 3: Implement immutable allowlisted catalog**

Hard-code artifact metadata as typed values: det `1780590` /
`193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`, rec `4462639` /
`9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6`, YAML `55571` /
`66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1`. Return relative download
paths, `Cache-Control: public, max-age=300` for manifest and `public, max-age=31536000, immutable`
for versioned redirects. Keep legacy `/model` only for the LLM and give its HTTP client explicit
timeouts plus forwarded Range/ETag headers in proxy mode.

- [ ] **Step 4: Verify catalog against local assets and live redirect headers**

Run:

```bash
cd schism-backend && go test ./internal/modelcatalog ./internal/api -count=1
cd .. && sha256sum schism-android/ppocr-sdk/src/main/assets/models/{det/inference.onnx,rec/inference.onnx,rec/inference.yml}
```

Expected: tests pass and hashes exactly match the catalog.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/modelcatalog schism-backend/internal/api schism-backend/README.md schism-backend/docs/api-contract.md
git commit -m "feat(backend): serve a pinned OCR model catalog"
```

### Task 6: Pool safety, final audit, and backend evidence

**Files:**
- Modify: `schism-backend/internal/config/config.go`
- Modify: `schism-backend/internal/config/config_test.go`
- Modify: `schism-backend/internal/store/pool.go`
- Create: `schism-backend/internal/api/load_test.go`
- Modify: `schism-backend/README.md`
- Create: `docs/release/v1.3/backend-audit.md`

**Interfaces:**
- Produces: documented production env/timeouts/pool limits and an audit resolution table.
- Consumes: every previous backend task.

- [ ] **Step 1: Add failing configuration tests**

Test defaults `DB_MAX_CONNS=20`, `DB_MIN_CONNS=2`, `DB_MAX_CONN_LIFETIME=30m`, invalid/negative env
rejection, and a 5-second startup ping deadline. Add bounded concurrency tests that issue 500 health/
model-manifest requests, 100 duplicate expense creates with one idempotency key, and simultaneous
claim updates without data races, leaked bodies, or unbounded goroutine growth.

- [ ] **Step 2: Run configuration tests and confirm failure**

Run: `cd schism-backend && go test ./internal/config ./internal/store ./internal/api -run 'Test.*(Pool|Load|Concurrent)' -count=1`

Expected: FAIL because pool options are not parsed.

- [ ] **Step 3: Implement pgxpool configuration and document ingress requirements**

Use `pgxpool.ParseConfig`, set the tested values, ping during startup, and close migration handles.
Document Istio global limits: auth 5/minute/key, invite redemption 10/minute/IP, manifest 60/minute/IP,
request body 1 MiB, and upstream/downstream timeouts aligned with the server.

- [ ] **Step 4: Run complete backend release verification**

Run:

```bash
cd schism-backend
gofmt -w cmd internal
go test ./... -count=1
go test -race ./...
go vet ./...
go run honnef.co/go/tools/cmd/staticcheck@v0.7.0 ./...
docker build -t schism-backend:v1.3-rc .
```

Expected: every command passes using a Staticcheck binary built for Go 1.26; record versions and
outputs in `docs/release/v1.3/backend-audit.md` together with each original finding and resolution.

- [ ] **Step 5: Commit**

```bash
git add schism-backend docs/release/v1.3/backend-audit.md
git commit -m "chore(backend): complete production readiness audit"
```
