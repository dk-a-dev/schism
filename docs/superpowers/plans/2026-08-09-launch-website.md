# Schism Launch Website Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship a fast, accessible one-page Schism marketing site and Play-policy pages from the existing backend deployment.

**Architecture:** A new embedded `internal/web` package owns templates and fingerprinted static assets. The existing chi router mounts its public handler, while configuration supplies only validated public/support/Play URLs. The site contains no JavaScript, analytics, cookies, or separate runtime.

**Tech Stack:** Go `embed`/`html/template`, chi, semantic HTML, plain CSS/SVG, Go tests, Playwright/browser checks, Lighthouse.

## Global constraints

- Implement the approved `2026-08-09-launch-website-design.md` exactly.
- Use real synthetic-fixture app screenshots only; code-native illustration is allowed, fabricated UI is not.
- Missing `SCHISM_PLAY_URL` produces a truthful non-link coming-soon state.
- `SCHISM_PUBLIC_URL`, when set, must be absolute HTTPS without userinfo/query/fragment.
- The site adds no cookies, analytics, JavaScript, remote fonts, or remote assets.
- Owner/legal approval and the real support address/Play listing are release gates, not hard-coded placeholders.

---

### Task 1: Embedded web package and validated public configuration

**Files:**
- Create: `schism-backend/internal/web/site.go`
- Create: `schism-backend/internal/web/site_test.go`
- Create: `schism-backend/internal/web/templates/home.html`
- Create: `schism-backend/internal/web/templates/layout.html`
- Create: `schism-backend/internal/web/static/site.css`
- Create: `schism-backend/internal/web/static/split-coin.svg`
- Modify: `schism-backend/internal/config/config.go`
- Modify: `schism-backend/internal/config/config_test.go`
- Modify: `schism-backend/internal/api/router.go`
- Modify: `schism-backend/cmd/server/main.go`

**Interfaces:**
- Produces: `web.New(web.Config) (http.Handler, error)` and embedded `/`, `/assets/site/*` resources.
- Consumes: validated `Config.SupportEmail`, `Config.PublicURL`, and optional `Config.PlayURL`.

- [ ] **Step 1: Write failing config and route tests**

Assert invalid public/Play URLs fail config loading, empty Play URL is accepted, template values are
escaped, `/` is UTF-8 semantic HTML, assets have allowlisted MIME types and cache/ETag headers, paths
cannot traverse the embed tree, and the handler contains no external requests/scripts/cookies.

- [ ] **Step 2: Run tests and confirm RED**

Run: `cd schism-backend && go test ./internal/config ./internal/web ./internal/api -run 'Test.*(PublicURL|PlayURL|Site|Home|Asset)' -count=1`.

Expected: FAIL because configuration and web package do not exist.

- [ ] **Step 3: Implement the package and route mount**

Parse templates once at startup, derive a strict view model, serve static content through an exact
allowlist, and mount the handler before `/v1`. Render the Play CTA as a link only for configured HTTPS.
Keep security headers at the router boundary and set HTML to no-cache while fingerprintable assets are
immutable. Copy the existing launcher split-coin geometry into accessible SVG source.

- [ ] **Step 4: Verify Go boundaries**

Run: `cd schism-backend && gofmt -w cmd internal && go test ./... -count=1 && go test -race ./... -count=1 && go vet ./...`.

Expected: PASS with `/v1`, invite/deep-link, and model routes unchanged.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/web schism-backend/internal/config schism-backend/internal/api/router.go schism-backend/cmd/server/main.go
git commit -m "feat(web): add embedded Schism launch site"
```

### Task 2: Responsive marketing composition and truthful content

**Files:**
- Modify: `schism-backend/internal/web/templates/home.html`
- Modify: `schism-backend/internal/web/templates/layout.html`
- Modify: `schism-backend/internal/web/static/site.css`
- Create: `schism-backend/internal/web/home_contract_test.go`
- Create: `docs/release/v1.3/site-copy.md`

**Interfaces:**
- Produces: header, hero, Capture/Review/Split, privacy, Live Split, gallery, final CTA, and footer.
- Consumes: approved site design and factual product/data-flow documents.

- [ ] **Step 1: Write failing content/accessibility contract tests**

Assert a single H1, ordered headings, skip link, landmarks, keyboard-visible CTA, alt text, reduced
motion rule, minimum content claims, all four policy links, no unqualified accuracy/AI/bank claims,
no prices/reviews, and the exact configured/coming-soon CTA variants.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-backend && go test ./internal/web -run 'Test.*(HomeContract|Accessibility|Copy)' -count=1`.

Expected: FAIL because the complete composition is absent.

- [ ] **Step 3: Implement the approved visual system**

Use the palette and typography rhythm from the design, CSS-only ledger lines and split seams, fluid
type via bounded `clamp`, responsive grids without horizontal overflow, visible focus, and a motionless
reduced-motion path. Write final factual copy to `site-copy.md` for cross-checking with Play listing and
privacy disclosure.

- [ ] **Step 4: Run tests and format checks**

Run: `cd schism-backend && go test ./internal/web -count=1 && gofmt -w internal/web`.

Expected: PASS with no remote runtime dependency.

- [ ] **Step 5: Commit**

```bash
git add schism-backend/internal/web docs/release/v1.3/site-copy.md
git commit -m "feat(web): build responsive launch experience"
```

### Task 3: Legal/support pages and account-deletion route

**Files:**
- Create: `schism-backend/internal/web/legal.go`
- Create: `schism-backend/internal/web/legal_test.go`
- Create: `schism-backend/internal/web/templates/privacy.html`
- Create: `schism-backend/internal/web/templates/terms.html`
- Create: `schism-backend/internal/web/templates/support.html`
- Create: `schism-backend/internal/web/templates/account-deletion.html`
- Create: `docs/release/v1.3/data-flow.md`

**Interfaces:**
- Produces: `/privacy`, `/terms`, `/support`, `/account-deletion` in the shared site layout.
- Consumes: completed Android/backend/monetization data-flow audit and configured support email.

- [ ] **Step 1: Execute Play policy plan Task 1 test-first**

Use the exact factual/privacy requirements in `2026-08-09-play-assets-policy.md`, including on-device
OCR/SMS, explicit SMS opt-in, shared backend data, Billing purchase verification, Mobile Ads/UMP SDK
disclosures, separate subscription cancellation/account deletion, and post-uninstall deletion request.

- [ ] **Step 2: Cross-link and verify all public pages**

Run: `cd schism-backend && go test ./internal/web ./internal/config ./internal/api -count=1`.

Expected: all five public pages share accessible navigation and factual support contact.

- [ ] **Step 3: Commit**

```bash
git add schism-backend/internal/web docs/release/v1.3/data-flow.md
git commit -m "feat(web): publish launch policy surfaces"
```

### Task 4: Real product imagery, social metadata, and visual verification

**Files:**
- Create: `schism-backend/internal/web/static/screen-inbox.webp`
- Create: `schism-backend/internal/web/static/screen-split.webp`
- Create: `schism-backend/internal/web/static/screen-insights.webp`
- Create: `schism-backend/internal/web/static/social-1200x630.png`
- Modify: `schism-backend/internal/web/templates/home.html`
- Modify: `schism-backend/internal/web/templates/layout.html`
- Create: `tools/site/build_assets.py`
- Create: `tools/site/test_assets.py`
- Create: `docs/release/v1.3/site-readiness.md`

**Interfaces:**
- Produces: optimized real screenshot crops and configured canonical/Open Graph metadata.
- Consumes: validated outputs from Play screenshot/launch-asset tasks; no generated UI.

- [ ] **Step 1: Write failing deterministic asset tests**

Assert exact dimensions/modes, bounded byte sizes, sRGB, no EXIF/PII, source SHA mapping, responsive
width/height attributes, configured canonical URL behavior, and Open Graph/Twitter metadata.

- [ ] **Step 2: Build from validated launch sources**

Run `python3 tools/site/build_assets.py` against committed Play screenshots and feature source. Preserve
aspect ratio, strip metadata, and fail rather than upscaling or using absent placeholders.

- [ ] **Step 3: Verify site in browser and Lighthouse**

Run the release backend locally and inspect 360×800, 412×915, 768×1024, and 1440×900. Exercise keyboard,
large text, reduced motion, dark-system preference, broken/image-disabled rendering, and every CTA/legal
link. Run Lighthouse and require Performance/Accessibility/Best Practices/SEO ≥95; record commit,
commands, report hashes, viewport screenshots, and remaining owner gates in `site-readiness.md`.

- [ ] **Step 4: Commit**

```bash
git add schism-backend/internal/web tools/site docs/release/v1.3/site-readiness.md
git commit -m "assets(web): finish launch site verification"
```

