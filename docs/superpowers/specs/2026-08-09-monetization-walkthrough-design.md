# Schism Plus, Minimal Ads, and Guided Walkthrough Design

Date: 2026-08-09

## Purpose

Add sustainable revenue without interrupting expense entry, receipt scanning, settling, or group
collaboration. Monetization must feel optional and honest. A separate guided walkthrough must teach
the real app journey without creating server data, requiring permissions, downloading OCR, or showing
ads and paywalls.

This design has two independently testable systems:

1. Play Billing, backend entitlements, the Live Split allowance, and one restrained AdMob placement.
2. First-run education, an interactive local demo, contextual hints, and replay/reset controls.

## Product model

### Free

- Create and join groups, enter/edit expenses, view balances, settle up, import bank SMS, scan
  receipts, invite existing participants, and use core spending views.
- Join, claim, and view any Live Split for free.
- Host three Live Splits per UTC calendar month. Only a successfully created session consumes an
  allowance; retries with the same idempotency key do not. Unused sessions do not roll over.
- See at most one inline adaptive ad on the Spending/Insights feed under the eligibility rules below.

Receipt OCR remains free and unlimited because it runs on-device after download and is a core input
method. Data access, export of the user's raw records, settling, and accepting an invitation are never
paywalled.

### Schism Plus

- Host unlimited Live Splits.
- No ads.
- Plus Insights created for launch: month-over-month spending comparison, category trends, and
  per-group participant/category history beyond the existing free summaries.
- CSV and shareable PDF export of the user's groups and expenses. Existing on-screen history and
  access to raw records remain free.
- Future automation features may join Plus, but existing free capabilities will not silently move
  behind the paywall.

The Play product is one subscription, `schism_plus`, with monthly and discounted annual base plans.
Prices and localized trial/offer configuration live in Play Console, not app code. The three monthly
hosted sessions are the product demonstration; launch does not add a separate time-limited trial or
lifetime purchase.

## Live Split gating

The backend is authoritative. Before creating a claim session it transactionally checks the creator's
active entitlement or their successful-creation count for the current UTC month. It returns a stable
`402 PLUS_REQUIRED` response containing `used`, `limit`, and `resetsAt` when the free allowance is
exhausted. Existing sessions, joining, claiming, readiness, polling, cancellation, and finalization
remain available regardless of the creator's later subscription state.

The app shows remaining free sessions beside the host action. At exhaustion, a calm Plus sheet
explains the value and offers monthly, annual, Restore purchases, and Not now. It never blocks manual
item assignment. Purchase cancellation does not delete data; it only restores the host allowance
after the paid period ends.

## Billing and entitlement architecture

- Play builds use Google Play Billing Library 9. Standalone builds contain no purchase UI; signed-in
  users still receive backend entitlements previously purchased through Play and otherwise use the
  free allowance.
- Android queries product details, launches the Play purchase sheet, observes pending/purchased
  states, and sends `productId`, `purchaseToken`, and package name to an authenticated backend verify
  endpoint. The UI does not grant Plus from the client purchase object alone.
- The backend verifies the token through the Google Play Developer API, confirms package/product and
  purchased state, records expiry/auto-renew state, grants the user entitlement, and acknowledges new
  purchases. Purchase tokens are encrypted at rest with a deployment secret and never logged.
- Entitlements are refreshed on purchase, restore, login/app resume when stale, and before a gated
  creation. The first launch does not depend on Real-time Developer Notifications; RTDN is a later
  reliability enhancement, while stale server records are rechecked at most six hours apart and at
  the paid expiry boundary.
- Pending, declined, cancelled, expired, refunded, network-failed, already-owned, and account-switch
  states have explicit UI. Restore purchases is always available. Settings links to Play's manage
  subscription screen and shows the authoritative renewal/expiry date.

## Advertising rules

Use the official Google Mobile Ads SDK and User Messaging Platform SDK only in the Play build.
Request current consent information at each launch, show the required form, provide Settings >
Privacy choices whenever required, and never request personalized ads without the applicable consent.
Development, screenshots, automated tests, and internal builds use Google's test ad units.

The only launch placement is one clearly labelled inline adaptive banner in the scrollable personal
Spending/Insights feed, after the summary/insight cards. It has reserved dimensions, a non-clickable
visual separator, and generous distance from navigation or controls. It is eligible only when:

- the user is free and entitlement state is known;
- onboarding and the interactive demo are complete;
- the account is at least seven days old and has completed at least three meaningful actions;
- consent permits an ad request and the screen is foregrounded.

No ads appear in onboarding, authentication, group detail, balances, expense entry/editing, SMS,
receipt scanning, OCR download, invitation, Live Split, settle-up, purchase, error, or empty states.
There are no interstitial, app-open, rewarded, notification, or audio ads. The app makes no new ad
request more frequently than once per 60 seconds and destroys the ad view with its screen lifecycle.
No fill or consent denial leaves the app fully usable; Plus removes the slot entirely.

## Walkthrough experience

### Stage 1: concise value tour

Retain the current illustrated pager but correct outdated claims and reduce it to four focused pages:
groups and balances, receipt/SMS capture, Live Split collaboration, and privacy/on-device behavior.
Skip is always visible. Account forms require the backend's real eight-character password rule and
must not claim that phone numbers automatically link participants.

### Stage 2: interactive local demo

After authentication, offer `Take the 2-minute tour` or `Explore myself`. The tour opens an isolated,
in-memory sample called `Weekend dinner`; it never writes Room or backend records. A deterministic
sample receipt avoids camera permission and OCR download. The user performs five guided actions:

1. Open the sample group and identify participants.
2. Add the sample receipt and see detected line items.
3. Assign items and understand tax/total verification.
4. View balances and a suggested settle-up.
5. Preview hosting a Live Split and learn that invitees join free.

Each step has one short instruction, highlights a real Compose target, accepts the real gesture, and
offers Back, Skip tour, and Continue when accessibility requires it. The demo shows neither ads nor a
purchase prompt. Completion returns to the empty/real account state and removes all demo state.

### Stage 3: contextual discovery

Versioned, one-time hints introduce real features only when relevant: bank-SMS permission and import,
first OCR model download, participant invites, UPI settle-up, and the first Live Split host action.
Hints are dismissible, never stack, do not obscure system permission explanations, and can be reset.
Settings provides `Replay app tour`, `Reset feature tips`, and normal Privacy choices/Plus controls.

Progress is stored per account with a walkthrough schema version. Process death resumes the current
demo step; sign-out clears transient demo state but retains no financial demo data. A future material
walkthrough change increments the version and may offer—not force—the new tour.

## Accessibility and UX quality

- TalkBack announces the highlighted target, instruction, step count, and available actions in a
  stable order; focus moves deliberately and returns to the triggering control when dismissed.
- Coach marks support font scaling, portrait/landscape, edge-to-edge insets, RTL, dark theme, and
  reduced motion. Color is never the sole cue and targets remain at least 48 dp.
- Billing and ad loading never delay app startup or primary content. Ads cannot cause layout shifts.
- Premium copy states the exact allowance and renewal terms without countdowns, fake urgency,
  preselected annual coercion, or hiding dismissal/restore controls.

## Components and boundaries

- Android `billing`: `BillingRepository`, purchase state machine, product UI, restore/manage actions.
- Backend `entitlements`: verified Play purchase records and `EntitlementService` consumed by claim
  creation. Group/claim code does not call Google directly.
- Android `ads`: consent manager and a single reusable lifecycle-aware Compose banner host. Screens
  consume only `AdEligibility`; they do not know SDK details.
- Android `walkthrough`: pure step definitions/state reducer, target registry, overlay renderer, and
  isolated `DemoRepository`. Production repositories are not available inside demo scope.

An authenticated backend monetization-config response, backed by deployment environment flags, can
disable purchases, the Plus gate, or ads independently without an app update. The client caches the
last signed-in response and defaults every switch off when it has never received one. A disabled or
indeterminate monetization service preserves core use and does not show an ad or claim an entitlement
that was not verified; the server still applies the ordinary free Live Split allowance.

## Testing and release evidence

- Backend unit/integration/race tests: monthly boundary, idempotent creation, Plus bypass, concurrent
  fourth creation, expiry/refund, token replay/account mismatch, encrypted token/log redaction, and
  Google API failures.
- Billing tests use Play Billing fakes and licensed internal-test-track accounts: purchase, pending,
  acknowledge, restore after reinstall, account switch, cancel/expire/refund, offline cache, and
  standalone behavior.
- Ad tests assert the exact placement/eligibility matrix, UMP denial/error, Plus removal, fixed space,
  lifecycle cleanup, test IDs, and zero ad SDK references in prohibited screens.
- Compose/instrumentation tests exercise every demo step, skip/resume/process death, no persistent
  records/network/permissions, replay/reset, TalkBack semantics, large fonts, RTL, rotation, and dark
  theme.
- Manual launch QA uses the now-running Pixel 9 Pro XL emulator, Docker backend, AdMob test units,
  and Play internal testing. Production ad IDs and purchasable products are supplied only through
  release configuration/Play Console.

## Rollout

Ship walkthrough improvements first. Deploy backend entitlement tables/endpoints dark, then a Play
internal-test Billing build. Enable Plus gating only after purchase/restore/refund evidence passes.
Enable the single test-verified ad placement last, initially for a small remote-config cohort. Revenue,
retention, Live Split conversion, ad impressions, dismissals, crashes, and support complaints are
measured without logging receipt text, SMS content, group names, participant names, or purchase tokens.
