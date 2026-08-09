# Schism Plus and Minimal Ads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship backend-authoritative Schism Plus subscriptions, three free hosted Live Splits per UTC month, and one consent-aware non-disruptive ad placement.

**Architecture:** The backend owns entitlements, quota consumption, and Google purchase verification behind a replaceable verifier interface. Play Android variants use Billing Library 9, Mobile Ads, and UMP; standalone uses the same backend entitlement/free quota but contains no purchase or ad SDK UI. Screens consume small entitlement/ad interfaces rather than Google SDK types.

**Tech Stack:** Go/Postgres/chi, Google Play Developer API, Kotlin/Compose/Hilt, Play Billing 9, Google Mobile Ads SDK, UMP SDK, DataStore, MockWebServer.

## Global Constraints

- Free accounts may successfully create three Live Splits per UTC calendar month; joining and every operation on an existing session remain free.
- Plus launch benefits are unlimited hosting, no ads, new Plus Insights, and CSV/PDF export; existing history and raw records remain free.
- Receipt OCR, SMS import, manual entry, invitations, balances, and settle-up are never gated.
- One ad placement only: inline adaptive banner after Spending/Insights summaries, eligible after account age seven days and three meaningful actions.
- No interstitial, app-open, rewarded, audio, notification, onboarding, transaction, receipt, group, balance, settlement, Live Split, or purchase ads.
- Billing/ad feature switches default off until authenticated backend configuration is available.
- Purchase tokens are encrypted at rest, never logged, and entitlements are never granted from client claims alone.

---

### Task 1: Entitlement, quota, and monetization configuration store

**Files:**
- Create: `schism-backend/internal/store/migrations/0013_monetization.up.sql`
- Create: `schism-backend/internal/store/migrations/0013_monetization.down.sql`
- Create: `schism-backend/internal/store/entitlements.go`
- Create: `schism-backend/internal/store/entitlements_test.go`
- Modify: `schism-backend/internal/config/config.go`
- Modify: `schism-backend/internal/config/config_test.go`

**Interfaces:**
- Produces: `EntitlementStatus(ctx,userID,now)`, `ConsumeLiveSplitAllowance(ctx,userID,idempotencyKey,now)`, `MonetizationConfig`, encrypted purchase records, and `ErrPlusRequired{Used,Limit,ResetsAt}`.
- Consumes: authenticated user IDs and deployment flags `PLUS_ENABLED`, `ADS_ENABLED`, `PURCHASES_ENABLED`, `PLAY_PACKAGE_NAME`, `BILLING_TOKEN_KEY`.

- [ ] **Step 1: Write failing migration/store/config tests**

Test active/expired/refunded entitlement, UTC month rollover, exactly three free consumes, idempotent retry, concurrent fourth consume, disabled gate, AES-GCM ciphertext not containing the token, invalid 32-byte key rejection, and all feature flags defaulting false.

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `cd schism-backend && go test ./internal/store ./internal/config -run 'Test.*(Entitlement|Allowance|Monetization|BillingToken)' -count=1`.

Expected: FAIL because migration, types, and configuration do not exist.

- [ ] **Step 3: Implement transactional store boundary**

Use `live_split_usage(user_id, month_start, count)` locked with `SELECT ... FOR UPDATE`; record idempotency keys separately so retries return the original decision. Define:

```go
type PlusRequired struct { Used, Limit int; ResetsAt time.Time }
type Entitlement struct { Active bool; ProductID string; ExpiresAt time.Time; AutoRenewing bool }
func (s *Store) ConsumeLiveSplitAllowance(ctx context.Context, userID, key string, now time.Time) (*PlusRequired, error)
```

Encrypt/decrypt purchase tokens with AES-256-GCM using `BILLING_TOKEN_KEY`; logs/errors expose only record ID and request ID.

- [ ] **Step 4: Verify store correctness and race safety**

Run: `cd schism-backend && gofmt -w internal && go test ./internal/store ./internal/config -count=1 && go test -race -p 1 ./internal/store -run 'Test.*(Allowance|Entitlement)'`.

Expected: PASS; concurrent callers never receive more than three free successful decisions.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/store schism-backend/internal/config
git commit -m "feat(backend): add Plus entitlements and Live Split allowance"
```

### Task 2: Replaceable Google purchase verification service

**Files:**
- Create: `schism-backend/internal/billing/verifier.go`
- Create: `schism-backend/internal/billing/google.go`
- Create: `schism-backend/internal/billing/verifier_test.go`
- Create: `schism-backend/internal/api/billing.go`
- Create: `schism-backend/internal/api/billing_test.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/go.mod`
- Modify: `schism-backend/go.sum`

**Interfaces:**
- Produces: authenticated `POST /v1/billing/verify`, `POST /v1/billing/restore`, `GET /v1/entitlement`, and `GET /v1/monetization/config`.
- Consumes: `billing.Verifier.Verify(ctx, packageName, productID, purchaseToken) (VerifiedPurchase,error)` and Task 1 store.

- [ ] **Step 1: Write failure-first verifier/API tests**

Use a fake verifier to cover package/product/account mismatch, pending, purchased-unacknowledged, active, cancelled-but-active-until-expiry, expired, refunded, transient Google failure, replay to another Schism user, restore, sanitized errors, and no token in logs/responses.

- [ ] **Step 2: Run tests and confirm RED**

Run: `cd schism-backend && go test ./internal/billing ./internal/api -run 'Test.*(Billing|Purchase|Entitlement|MonetizationConfig)' -count=1`.

Expected: FAIL because the service/endpoints do not exist.

- [ ] **Step 3: Implement verification and acknowledgement**

Define:

```go
type VerifiedPurchase struct { ProductID string; State State; ExpiresAt time.Time; AutoRenewing, Acknowledged bool }
type Verifier interface { Verify(context.Context,string,string,string) (VerifiedPurchase,error); Acknowledge(context.Context,string,string) error }
```

Accept only package `ai.schism.split` and product `schism_plus`; grant only `PURCHASED`; acknowledge server-side with bounded exponential retry; refresh stale records at six hours and at expiry.

- [ ] **Step 4: Verify API and dependency hygiene**

Run: `cd schism-backend && gofmt -w internal && go test ./... -count=1 && go test -race -p 1 ./internal/billing ./internal/api && go vet ./...`.

Expected: PASS with no client-controlled entitlement fields.

- [ ] **Step 5: Commit**

```bash
git add schism-backend
git commit -m "feat(backend): verify Play subscriptions server-side"
```

### Task 3: Gate only Live Split creation

**Files:**
- Modify: `schism-backend/internal/api/claims.go`
- Modify: `schism-backend/internal/api/claims_test.go`
- Modify: `schism-backend/internal/store/claims.go`
- Modify: `schism-backend/internal/api/dto.go`

**Interfaces:**
- Produces: `402 {"error":"PLUS_REQUIRED","used":3,"limit":3,"resetsAt":"..."}` only from create-session.
- Consumes: Task 1 allowance and client `Idempotency-Key`.

- [ ] **Step 1: Add failing route matrix**

Assert creates 1–3 succeed, idempotent replay does not consume, fourth returns the exact 402 body, Plus bypasses, gate-disabled bypasses, concurrent fourth has one outcome, and GET/claim/ready/finalize/cancel/edit never calls the gate.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-backend && go test ./internal/api ./internal/store -run 'Test.*LiveSplitAllowance' -count=1`.

Expected: FAIL because create-session is ungated.

- [ ] **Step 3: Implement atomic create plus consume**

Require a bounded idempotency header, make allowance decision and claim-session insert one transaction, and map only `ErrPlusRequired` to 402. Never invalidate sessions after expiry.

- [ ] **Step 4: Run complete backend verification**

Run: `cd schism-backend && gofmt -w internal && go test ./... -count=1 && go test -race -p 1 ./... && go vet ./...`.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal
git commit -m "feat(live-split): enforce free hosting allowance"
```

### Task 4: Android entitlement and Play Billing boundary

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/monetization/Entitlement.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/monetization/EntitlementRepository.kt`
- Create: `schism-android/app/src/play/java/ai/schism/split/monetization/PlayBillingRepository.kt`
- Create: `schism-android/app/src/standalone/java/ai/schism/split/monetization/StandaloneBillingRepository.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/monetization/EntitlementRepositoryTest.kt`
- Create: `schism-android/app/src/playTest/java/ai/schism/split/monetization/PlayBillingRepositoryTest.kt`
- Modify: `schism-android/gradle/libs.versions.toml`
- Modify: `schism-android/app/build.gradle.kts`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/ApiService.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/Dto.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/di/AppModule.kt`

**Interfaces:**
- Produces: `StateFlow<EntitlementState>`, product details, `purchase(Activity,BasePlan)`, `restore()`, and `manageSubscriptionIntent()`.
- Consumes: backend endpoints from Task 2 and Billing Library 9 only in `play` sources.

- [ ] **Step 1: Write failing state-machine tests**

Cover unavailable, loading, free quota, pending, verification, Plus active, cancelled-active, expired, declined, offline cached display, restore after reinstall, Play account switch, duplicate callbacks, and standalone no-purchase behavior.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest :app:testStandaloneDebugUnitTest --tests 'ai.schism.split.monetization.*'`.

Expected: FAIL because flavors/billing boundary are absent.

- [ ] **Step 3: Implement repository and backend verification handoff**

Use product `schism_plus` and base plans `monthly`/`annual`; query SUBS and owned purchases on connection; never emit Active until backend verification succeeds. Persist only display-safe entitlement/expiry, not purchase token.

- [ ] **Step 4: Verify dependency isolation**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest :app:testStandaloneDebugUnitTest :app:assembleStandaloneDebug` and inspect the standalone dependency report for `billingclient` absence.

Expected: both state suites pass and standalone has no Billing SDK.

- [ ] **Step 5: Commit**

```bash
git add schism-android
git commit -m "feat(android): add verified Play Billing entitlements"
```

### Task 5: Plus sheet, Settings, and Live Split UX

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/monetization/PlusSheet.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/monetization/PlusViewModel.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/monetization/PlusViewModelTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/ItemizedSplitScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/claim/ClaimSessionRepository.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`
- Modify: `schism-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: calm monthly/annual Plus purchase sheet, remaining allowance UI, restore/manage actions.
- Consumes: Task 4 state and backend 402 response.

- [ ] **Step 1: Write failing ViewModel/copy/UI tests**

Assert allowance is visible before hosting, manual split remains available at exhaustion, invitees never see paywall, Not now/Restore are visible, exact localized Play prices are used, annual is not preselected, pending survives recreation, and expiry never hides data.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest --tests '*Plus*' --tests '*ClaimSession*'`.

Expected: FAIL because Plus UI does not exist.

- [ ] **Step 3: Implement honest purchase UX**

Map only backend `PLUS_REQUIRED` to the sheet, leave parsing/manual assignment intact, expose `Restore purchases` and `Manage subscription` in Settings, and display Play-provided renewal terms/prices.

- [ ] **Step 4: Run UI/unit verification**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug`.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(android): add Schism Plus purchase experience"
```

### Task 6: Consent-aware minimal advertising

**Files:**
- Create: `schism-android/app/src/play/java/ai/schism/split/ads/PlayConsentManager.kt`
- Create: `schism-android/app/src/play/java/ai/schism/split/ads/InlineAdaptiveAd.kt`
- Create: `schism-android/app/src/play/java/ai/schism/split/ads/AdEligibility.kt`
- Create: `schism-android/app/src/standalone/java/ai/schism/split/ads/InlineAdaptiveAd.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/ads/AdEligibilityTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/SchismApp.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/finance/SpendingScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`
- Modify: `schism-android/app/src/main/AndroidManifest.xml`
- Modify: `schism-android/gradle/libs.versions.toml`
- Modify: `schism-android/app/build.gradle.kts`

**Interfaces:**
- Produces: `AdEligibility.evaluate(...)`, launch consent refresh, Privacy choices entry, one lifecycle-safe banner.
- Consumes: known free entitlement, backend ads flag, account age/actions, UMP `canRequestAds`, release-configured AdMob IDs.

- [ ] **Step 1: Write the full failing eligibility matrix**

Assert Plus, unknown entitlement, disabled flag, consent unavailable/denied, age under seven days, fewer than three actions, onboarding/demo, prohibited routes, background state, and no-fill all render no ad; only eligible Spending renders one test-ID slot and destroys it on disposal.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest --tests 'ai.schism.split.ads.*'`.

Expected: FAIL because ad boundary does not exist.

- [ ] **Step 3: Implement UMP and one inline adaptive banner**

Refresh UMP every launch, show the required form before requesting ads, expose Privacy choices when required, reserve a separated inline slot after insight cards, enforce at least 60 seconds between requests, use official test IDs outside signed production, and ship no interstitial/rewarded/app-open code.

- [ ] **Step 4: Verify manifests, SDK isolation, and UI**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest :app:processPlayReleaseMainManifest :app:assembleStandaloneRelease` and assert standalone has no Mobile Ads/UMP classes.

Expected: PASS; screenshots/tests never request production ads.

- [ ] **Step 5: Commit**

```bash
git add schism-android
git commit -m "feat(android): add one consent-aware insights ad"
```

### Task 7: Plus Insights, exports, and monetization release evidence

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/finance/PlusInsights.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/export/ExpenseCsvExporter.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/export/ExpensePdfExporter.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/export/ExportTest.kt`
- Create: `docs/release/v1.3/monetization.md`
- Modify: `schism-android/app/src/main/java/ai/schism/split/finance/SpendingScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`

**Interfaces:**
- Produces: new gated trend/history views and user-initiated CSV/PDF shares without backend storage.
- Consumes: Task 4 entitlement and existing expense/group repositories.

- [ ] **Step 1: Write failing insight/export tests**

Test month comparison/category/participant aggregation, currency isolation, stable CSV escaping/UTF-8, readable PDF pagination, no tokens/private debug fields, free preview vs Plus export, and ACTION_SEND user choice.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest --tests '*PlusInsights*' --tests '*Export*'`.

Expected: FAIL because launch Plus value features do not exist.

- [ ] **Step 3: Implement local-only insights and exports**

Compute from authorized local/backend records, bucket every currency separately, write app-cache files with FileProvider, revoke/clean temporary files, and never upload export contents.

- [ ] **Step 4: Run monetization release verification**

Run unit/instrumentation suites, Play internal-test purchase/pending/restore/cancel/refund/account-switch checklist, AdMob test-unit/UMP geography checks, manifest/dependency audit, backend race suite, and record product/base-plan IDs plus artifact hashes in `monetization.md` without secrets.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/monetization.md
git commit -m "feat(plus): add insights exports and release evidence"
```
