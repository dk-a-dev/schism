# Play Policy Surfaces and Launch Assets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce truthful, functional Play policy surfaces, store metadata, real release-candidate screenshots, and a coherent launch/promo asset kit for Schism v1.3.0.

**Architecture:** Backend-hosted legal/support pages are generated from one factual data-flow document and a required support email. Store worksheets and reviewer instructions mirror those facts. Real app screens are captured from deterministic safe fixtures; the existing split-coin mark and brand palette drive exported raster assets, with image generation limited to supporting non-product artwork.

**Tech Stack:** Go `html/template`, Compose screenshot instrumentation/adb, SVG/vector drawables, Pillow/ImageMagick-compatible validation, imagegen for a text-free supporting background.

## Global Constraints

- Brand palette: green `#14874F`, mint `#B6ECCE`, cream `#FBFAF4`, charcoal `#1A1A16`, terracotta `#BC5533`, amber `#9A7A2E`.
- Art direction: quiet-luxury paper ledger with the split-coin seam; no generic neon AI, fake dashboards, gradients, or fabricated claims.
- Store screenshots are captured from the verified release candidate with synthetic fixture data.
- Generated imagery may support feature/promo graphics but may not imitate an in-app screen.
- A production build/deployment requires non-empty `SCHISM_SUPPORT_EMAIL`; no fake contact is committed.
- Owner/legal review and Play Console submission remain explicit owner-controlled gates.

---

### Task 1: Factual privacy, terms, support, and deletion pages

**Files:**
- Create: `schism-backend/internal/web/legal.go`
- Create: `schism-backend/internal/web/legal_test.go`
- Create: `schism-backend/internal/web/templates/privacy.html`
- Create: `schism-backend/internal/web/templates/terms.html`
- Create: `schism-backend/internal/web/templates/support.html`
- Create: `schism-backend/internal/web/templates/account-deletion.html`
- Modify: `schism-backend/internal/config/config.go`
- Modify: `schism-backend/internal/config/config_test.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/cmd/server/main.go`
- Create: `docs/release/v1.3/data-flow.md`

**Interfaces:**
- Produces: public `/privacy`, `/terms`, `/support`, `/account-deletion` HTML and required
  `Config.SupportEmail`.
- Consumes: actual app/backend behavior from the completed security/Android/OCR plans.

- [ ] **Step 1: Write failing legal-page contract tests**

Assert every page returns UTF-8 HTML, mobile viewport, CSP/no-sniff/referrer headers, escaped support
email, no cache for deletion support, visible Schism name/contact/effective date, and working links
between all four pages. Privacy must distinguish on-device SMS/receipts/OCR from account/shared-group
data transmitted to backend. Deletion must describe in-app deletion plus a functional `mailto:`
request path usable after uninstall. Production config with empty support email must fail startup.

- [ ] **Step 2: Run tests and verify failure**

Run: `cd schism-backend && go test ./internal/web ./internal/config ./internal/api -run 'Test.*(Legal|Support|Privacy|Deletion)' -count=1`.

Expected: FAIL because package/routes/config do not exist.

- [ ] **Step 3: Implement one factual source and accessible templates**

Write `data-flow.md` first: account name/email/optional phone, session data, group/expense/claim data,
on-device-only SMS/receipt/OCR/audio, model-download request metadata, purposes, encryption in transit,
retention, deletion, and no advertising/sale. Render concise responsive pages with semantic HTML,
visible focus, cream/green brand tokens, system fonts, no cookies/analytics/scripts, and support email
injected by config. State that financial records may be retained only when legally required and the
owner must supply the actual jurisdictional wording during legal review.

- [ ] **Step 4: Verify pages locally and over deployed-like HTTPS proxy**

Run: `cd schism-backend && gofmt -w cmd internal && go test ./... -count=1`; the legal route integration tests start an `httptest.Server` with `SUPPORT_EMAIL=owner@example.test` and fetch all four pages to validate links/headers. The `.test` value is local verification only and is never a production default.

Expected: pages pass tests and startup rejects an absent production support email.

- [ ] **Step 5: Commit**

```bash
git add schism-backend docs/release/v1.3/data-flow.md
git commit -m "feat(web): add Play policy and support pages"
```

### Task 2: In-app disclosure and policy access

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/OnboardingScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/inbox/InboxScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/BackendUrlProvider.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/core/ui/PolicyLinks.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/ui/PolicyLinksTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/settings/SettingsInstrumentedTest.kt`
- Modify: `schism-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: discoverable privacy/terms/support/account-deletion links and a prominent SMS disclosure
  immediately before permission request.
- Consumes: Task 1 backend URLs and Android SMS opt-in state.

- [ ] **Step 1: Write failing copy/URL/UI-state tests**

Assert policy URLs are fixed backend-relative HTTPS paths, no user-controlled URL, settings exposes
all four, onboarding links privacy/terms before account creation, deletion remains visible while
logged out, and SMS disclosure states: transaction SMS read automatically; parsing stays on device;
only expenses the user chooses to share are uploaded; denial leaves manual/receipt entry available.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*PolicyLinksTest' --tests '*Settings*' --tests '*Inbox*'`.

Expected: FAIL because policy link component and final disclosure do not exist.

- [ ] **Step 3: Implement policy links and disclosure UX**

Open HTTPS pages with a browsable ACTION_VIEW intent and handle no-browser failure. Use a modal or
full sheet for SMS disclosure with Not now/Enable automatic import; never obscure/auto-advance the
copy. Settings shows current permission state and revoke instructions. Avoid promising guaranteed OCR
accuracy or that all financial SMS formats are supported.

- [ ] **Step 4: Run UI-state/accessibility tests**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.schism.split.settings.SettingsInstrumentedTest`.

Expected: PASS with accessible link roles and disclosure focus order.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(android): add privacy and SMS disclosure surfaces"
```

### Task 3: Play listing and declaration package

**Files:**
- Create: `store/play/en-US/listing.md`
- Create: `store/play/en-US/release-notes/10300.txt`
- Create: `store/play/data-safety.md`
- Create: `store/play/sms-permission-declaration.md`
- Create: `store/play/financial-features-declaration.md`
- Create: `store/play/content-rating.md`
- Create: `store/play/reviewer-instructions.md`
- Create: `store/play/owner-checklist.md`
- Create: `tools/store/validate_copy.py`
- Create: `tools/store/test_validate_copy.py`

**Interfaces:**
- Produces: Play Console-ready factual copy and reviewer steps tied to v1.3.0 behavior.
- Consumes: `data-flow.md`, actual permissions/dependencies, model delivery, and account deletion.

- [ ] **Step 1: Write failing metadata validation tests**

Assert app name ≤30 characters, short description ≤80, full description ≤4000, release notes ≤500,
no ranking/price/testimonial claims, required support/privacy/deletion URL keys, SMS declaration names
exact permissions and core use, Data safety accounts for account/shared expense data and third-party
SDK review, and reviewer instructions contain a safe test account/fixture setup without committed
credentials.

- [ ] **Step 2: Run validator tests and confirm failure**

Run: `python3 -m unittest tools/store/test_validate_copy.py`.

Expected: FAIL because files and validator do not exist.

- [ ] **Step 3: Write truthful launch copy**

Use title `Schism: Split Expenses` and short description `Scan bills, track spending and split shared expenses privately.` Full copy leads with scan/review/split, on-device SMS/OCR privacy, group balances,
and manual fallback; it does not claim universal accuracy or bank affiliation. The SMS declaration
maps `READ_SMS`/`RECEIVE_SMS` to automatic money-management import, includes the opt-in walkthrough,
and says raw SMS never reaches Schism servers. Data safety lists account identifiers and user-shared
expense/group data transmitted encrypted in transit, and explicitly reviews SDK behavior.

- [ ] **Step 4: Validate copy and cross-check the release manifest**

Run: `python3 tools/store/validate_copy.py store/play && cd schism-android && ./gradlew :app:processPlayReleaseMainManifest`.

Expected: copy passes limits and every declared permission/data type matches the merged manifest and
completed data-flow audit.

- [ ] **Step 5: Commit**

```bash
git add store/play tools/store
git commit -m "docs(play): prepare v1.3 store declarations"
```

### Task 4: Deterministic safe screenshot state and real captures

**Files:**
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/store/StoreScreenshotTest.kt`
- Create: `schism-android/app/src/store/java/ai/schism/split/store/StoreFixtureSeeder.kt`
- Modify: `schism-android/app/build.gradle.kts`
- Create: `tools/store/capture_screenshots.sh`
- Create: `store/play/assets/source/screenshot-manifest.json`
- Create outputs: `store/play/assets/phone/01-inbox.png` through `06-privacy.png`

**Interfaces:**
- Produces: six 1080×1920 release-UI PNGs: private inbox, group balances, receipt scan readiness,
  itemized split, spending overview, and privacy/settings.
- Consumes: verified release candidate UI and local synthetic fixture data only.

- [ ] **Step 1: Add failing screenshot-state tests**

Assert a `store` build type/flavor is non-networked except model fixture, debuggable only for capture,
uses names Asha/Rohan/Mira and synthetic merchants/items/amounts, contains no `example.com`, real phone,
email, token, GSTIN, UPI, or receipt image, fixes locale `en-IN`, font scale 1.0, light theme, 24-hour
clock off, and disables animation for deterministic capture.

- [ ] **Step 2: Run screenshot tests and confirm failure**

Run: `cd schism-android && ./gradlew :app:connectedStoreDebugAndroidTest`.

Expected: FAIL because store fixture variant and capture flows do not exist.

- [ ] **Step 3: Implement seeding/capture without production backdoors**

Compile seeder/fixture navigation only into the store source set. Capture actual Compose screens with
status/navigation bars normalized; include visible OCR optional-download/privacy messaging where it
is the real UI. Write source commit, emulator/API/resolution, route, fixture ID, and image SHA-256 to
the manifest. Exclude store-only code from play/standalone release variants.

- [ ] **Step 4: Capture and visually inspect all six images**

Run: `tools/store/capture_screenshots.sh` on a 1080×1920 API-36 emulator, then inspect every PNG for
clipping, PII, inconsistent status bars, loading states, and text legibility.

Expected: six complete real screens; no generated/fake UI and no personal data.

- [ ] **Step 5: Commit screenshots and capture provenance**

```bash
git add schism-android/app tools/store store/play/assets
git commit -m "assets(play): capture real Schism store screens"
```

### Task 5: Icon, feature graphic, and promo/ad exports

**Files:**
- Create: `store/play/assets/source/split-coin.svg`
- Create: `store/play/assets/source/feature-layout.svg`
- Create: `store/play/assets/source/generated-paper-ledger.png`
- Create: `store/play/assets/icon-512.png`
- Create: `store/play/assets/feature-1024x500.png`
- Create: `store/promo/square-1080.png`
- Create: `store/promo/portrait-1080x1350.png`
- Create: `store/promo/landscape-1200x628.png`
- Create: `store/promo/story-1080x1920.png`
- Create: `store/play/assets/source/generation-log.md`
- Create: `tools/store/export_assets.py`
- Create: `tools/store/test_assets.py`
- Modify: `schism-android/app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `schism-android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `schism-android/app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Modify: `schism-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `schism-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

**Interfaces:**
- Produces: Play icon/feature graphic and four truthful launch promo sizes from editable source.
- Consumes: existing adaptive split-coin geometry and brand palette; one provenance-recorded imagegen output.

- [ ] **Step 1: Write failing asset validation tests**

Assert exact pixel dimensions, PNG RGB/RGBA, ≤1 MiB Play icon, non-transparent feature/promo canvases,
sRGB profile, icon safe-zone occupancy, no screenshot stretching, and SHA/provenance entries for all
outputs.

- [ ] **Step 2: Run tests and confirm failure**

Run: `python3 -m unittest tools/store/test_assets.py`.

Expected: FAIL because source/exports do not exist.

- [ ] **Step 3: Create branded source and supporting image**

Reconstruct the split coin exactly from launcher vector paths on a green circular field. Generate one
text-free supporting raster with this prompt: `Editorial top-down still life of a warm ivory paper receipt gently divided by one precise emerald seam, subtle embossed coin shapes and soft natural shadow, quiet-luxury financial stationery, restrained mint and amber accents, generous negative space, tactile paper grain, no words, no letters, no numbers, no logos, no phone mockup, no gradient, landscape composition.` Move the returned project-bound image into the declared source path and record the full prompt/tool/date. Keep real app screenshots separate; generated imagery never represents product UI.

- [ ] **Step 4: Compose crisp text/vector layouts and export**

Use the app mark, real screenshots, and short factual lines: `Split expenses. Keep the context.` and
`Private receipt and SMS understanding, on your phone.` Keep text as editable vector/source and within
feature/promo safe areas. Run `python3 tools/store/export_assets.py` and the validator; visually inspect
original resolution for aliasing, crops, and contrast.

Expected: every output passes validation and matches the existing app brand without fabricated UI.

- [ ] **Step 5: Commit source, outputs, and generation log**

```bash
git add store/play/assets store/promo tools/store
git commit -m "assets: create Schism launch campaign kit"
```

### Task 6: Final policy/asset consistency review

**Files:**
- Modify: `store/play/owner-checklist.md`
- Create: `docs/release/v1.3/play-readiness.md`

**Interfaces:**
- Produces: one evidence-backed Play submission checklist with owner-controlled gaps explicit.
- Consumes: all prior tasks plus final AAB permission/dependency analysis.

- [ ] **Step 1: Generate merged-manifest, dependency, and URL evidence**

Run Android dependency/manifest reports, fetch all policy URLs over HTTPS, validate copy/assets, and
record file hashes. Compare actual transmitted fields and SDKs to Data safety line-by-line.

- [ ] **Step 2: Perform reviewer walkthrough from a clean install**

Follow `reviewer-instructions.md`: register/login, deny then grant SMS, create/redeem participant
invite, download OCR, scan safe fixture, split expense, and delete account. Record deviations as
release blockers.

- [ ] **Step 3: Fix every factual mismatch**

Change product behavior or disclosure so permission, privacy, Data safety, deletion, screenshots,
and listing all describe the same shipped app. Never solve a mismatch by hiding relevant collection
or capability.

- [ ] **Step 4: Mark only owner-controlled submission items open**

The remaining unchecked rows may only be: actual support email/domain deployment, owner/legal policy
approval, Play Console form submission, content rating confirmation, developer verification, tester
enrollment, and staged production approval. If the Play account is a personal account created after
2023-11-13, the checklist requires at least 12 opted-in testers for 14 continuous days before the
production-access gate. Record exact links/artifact hashes in `play-readiness.md`.

- [ ] **Step 5: Commit**

```bash
git add store docs/release/v1.3/play-readiness.md
git commit -m "docs(play): complete submission readiness review"
```
