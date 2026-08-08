# Schism v1.3 — Launch Readiness, Security, and On-Demand OCR Design

Direction approved 2026-08-08. This design turns the current Android app and Go backend into a
release-candidate system for Google Play while preserving Schism's privacy-first behavior: bank SMS,
receipt images, OCR output, and local AI prompts stay on the device. The network distributes model
files and synchronizes deliberately shared group expenses; it does not process receipt images.

The work is split into release gates. A gate is complete only when its implementation, automated
tests, release-build verification, and required documentation all pass. Findings discovered by the
audit are fixed in the same gate rather than being recorded as launch debt when they affect security,
data integrity, crashes, Play compliance, or the primary user journeys.

---

## Product decisions

- **OCR architecture:** Google Play delivers OCR code and native libraries as an on-demand dynamic
  feature. Schism's backend is the control plane for model versions; official Hugging Face files are
  the initial byte store. No new S3-compatible service is required for launch.
- **Standalone distribution:** the GitHub APK remains an all-in-one build that can scan offline
  immediately. The Play build optimizes initial install size and downloads OCR on first use.
- **OCR choice:** PP-OCRv6 Tiny remains the default. Microsoft TrOCR is not shipped because it is a
  recognition-only transformer rather than the complete mobile detection + recognition pipeline the
  receipt flow requires, and its runtime/latency trade-off is worse for this app.
- **SMS import:** retained and improved. Schism will apply for the Google Play "SMS-based money
  management" permission exception and will include a clear in-app disclosure before permission is
  requested.
- **Invites:** no generic link silently adds a new participant. A secure invite can link the signed-in
  recipient only to a participant the organizer already created. All group data and mutations require
  authenticated membership.
- **Account delivery:** no new email/SMS delivery vendor is introduced in this release. Password
  reset/verification that requires outbound email is not claimed as available until a provider is
  configured. Existing authentication is hardened without inventing a non-functional recovery flow.
- **Target release:** v1.3.0 release candidate, followed by Play internal/closed testing before a
  production rollout. No production rollout is automatic.

## Explicit non-goals

- Uploading receipts, OCR text, bank SMS, or voice recordings to the backend.
- Downloading executable Android code or native libraries from Schism's backend. Play Feature
  Delivery is the only on-demand code-delivery mechanism; the backend downloads data/model files.
- Generic auto-join links that create arbitrary new members.
- Adding paid storage, email, SMS, analytics, crash-reporting, or advertising vendors without the
  owner's explicit credentials and approval.
- Claiming legal approval for the privacy policy or Play declarations. Drafts and accurate technical
  disclosures will be produced for owner/legal review.

---

## Gate 1 — Backend security and production hardening

### Authorization model

The current optional-auth model permits group and expense reads/writes to anyone who knows a group
ID. It is replaced with centralized authorization middleware and store queries:

- `/v1/auth/register` and `/v1/auth/login` remain public. `/health`, `/ping`, invite landing pages,
  and public model metadata/download redirects remain public and rate-limited.
- Every other `/v1` route requires a valid, unexpired bearer session.
- Group reads, lists, dashboard/stat/balance/activity reads, group edits, expense CRUD, and claim
  operations require the caller to be linked to a participant in that group.
- Expense `addedBy`, claim caller identity, and activity actor are derived server-side from the
  authenticated participant. Client-provided actor IDs cannot impersonate another participant.
- Group creation requires authentication and links the creator's participant to the caller inside
  the same transaction.
- Cross-group list/dashboard requests intersect requested IDs with authorized group IDs rather than
  trusting an `ids` query parameter.
- Unauthorized and non-member access returns a consistent `401` or `403`; missing objects return
  `404` without leaking database errors.

### Secure existing-participant invitations

Group IDs stop acting as bearer secrets. Organizers generate a participant-bound, one-time invite
token for an existing unlinked participant:

1. Backend stores only the SHA-256 hash, participant ID, group ID, creator ID, expiry, and redeemed
   timestamp.
2. `/i/{token}` is the HTTPS landing link and opens `schism://invite/{token}`.
3. A signed-in recipient previews only the group name and intended participant name, then confirms.
4. Redemption atomically verifies expiry/unredeemed status and links that participant to the caller.
5. It never creates a participant and cannot reassign an already-linked participant.

Legacy `/g/{groupID}` links show a safe upgrade message and never expose group content. Existing
phone-based participant claiming is disabled unless phone ownership is verifiable; an unverified
phone string must not grant access to financial data.

### Authentication hardening

- Remove or disable the legacy unauthenticated `POST /v1/users` registration path in production.
- Normalize email consistently, validate bounded name/email/phone/password sizes, and reject unknown
  JSON fields and trailing JSON documents.
- Keep bcrypt password hashing, raise the minimum password length to a reasonable launch baseline,
  and apply rate limits to register/login by IP and normalized email.
- Add session creation/last-used/expiry fields, expiry enforcement, and indexed token lookup. Raw
  bearer tokens remain device-only; only hashes are stored.
- Logout revokes the current session; account deletion revokes all sessions and deletes/unlinks data
  transactionally according to the published retention policy.

### HTTP and API safety

- Replace `http.ListenAndServe` with a configured `http.Server`: header/read/write/idle timeouts,
  maximum header size, graceful SIGTERM shutdown, and bounded database shutdown.
- Bound JSON bodies before decoding and bound lists/strings at validation. Reject unsupported content
  types where a JSON body is required.
- Stop returning raw internal/store errors. Log a request ID and sanitized structured context; return
  stable client error codes.
- Add recovery, request ID, security headers, and appropriate no-store/cache policies. Android APIs do
  not need permissive browser CORS.
- Apply conservative code-side rate limits for auth, invite redemption, and model metadata. Document
  the equivalent global limit required at the Istio ingress for multi-replica correctness.
- Fix `/health` to return valid JSON with `application/json`; keep liveness separate from database
  readiness. Do not disclose dependency details publicly.
- Configure pgx pool limits/lifetimes and context deadlines; close migration resources.
- Retain parameterized SQL and add authorization-aware queries and indexes needed by the new access
  checks.

### Backend verification

- Unit/API/store suites, `go vet`, race detector, and a Go-1.26-compatible Staticcheck.
- Authorization matrix tests for every group-scoped route: anonymous, member, non-member, missing,
  and spoofed actor.
- Invite tests: valid redemption, replay, expiry, wrong/linked participant, concurrent redemption.
- Auth throttling/expiry tests, request-size tests, malformed/unknown JSON tests, sanitized-error
  tests, graceful-shutdown tests, and migration up/down tests.
- Basic load test for health/model metadata and a concurrency test for expense idempotency and claim
  finalization.

---

## Gate 2 — Versioned OCR model delivery

### Backend control plane

Add a public, cacheable endpoint such as `GET /v1/models/ocr/manifest` returning a schema-versioned,
allowlisted manifest:

```json
{
  "schemaVersion": 1,
  "modelId": "pp-ocrv6-tiny",
  "version": "2026.06",
  "minAppVersionCode": 10300,
  "files": [
    {"name": "det.onnx", "size": 0, "sha256": "...", "downloadPath": "..."},
    {"name": "rec.onnx", "size": 0, "sha256": "...", "downloadPath": "..."},
    {"name": "rec.yml",  "size": 0, "sha256": "...", "downloadPath": "..."}
  ]
}
```

The committed implementation uses the actual measured sizes and checksums. Download paths map only
to server-owned manifest entries and issue `302/307` redirects to revision-pinned official
Hugging Face URLs. The backend is not an arbitrary URL proxy. Responses provide stable ETag and
cache headers; `HEAD` works. A guarded proxy mode may remain for gated models, but it forwards Range,
Content-Range, ETag, and cancellation and uses a bounded upstream client.

### Android model installer

- Generalize the existing WorkManager model downloader into a versioned artifact installer shared by
  LLM and OCR downloads.
- Download into app-private versioned `.part` files. Resume with HTTP Range when supported; restart
  safely when the upstream ignores the range or the ETag changes.
- Validate expected length and SHA-256 for every file before it becomes visible to the OCR engine.
- Install atomically by promoting a complete version directory/current marker. Never delete the
  working version before the replacement loads successfully; preserve rollback.
- Surface explicit states: feature required, waiting for network, downloading module, downloading
  model, verifying, ready, retryable failure, incompatible version, and insufficient storage.
- Allow Wi-Fi or cellular after an explicit first-use confirmation; show total download size and a
  cancel/retry action. Once installed, OCR works offline.
- No downloaded path comes from user input, and model files are non-executable app-private data.

### Paddle SDK changes

- Add file-backed model/config loading while retaining asset-backed loading for standalone builds and
  tests.
- Stream/map model files rather than reading both ONNX models into duplicate byte arrays where the
  ONNX API permits it.
- Validate config/model compatibility and produce actionable typed failures instead of generic
  crashes.
- Release sessions deterministically and test repeated install/load/delete/reload cycles.

---

## Gate 3 — Android modularization, size, performance, and reliability

### Build variants and feature modules

- Keep OCR behind a small base-module contract. Put the concrete Paddle/OpenCV/ONNX implementation
  in a reusable Android library that the base app does not reference directly.
- `play` distribution: an on-demand `:ocr-feature` wraps that implementation library and exposes its
  entry point to the base through the contract after SplitInstall succeeds. OCR models are downloaded
  as data through Gate 2. The base must handle Play unavailable/installation failure without loading
  a class from the absent split.
- `standalone` distribution: a standalone implementation binds the same contract and links the OCR
  implementation library plus verified models directly into one signed GitHub APK. It does not rely
  on Play Feature Delivery.
- Measure the AAB with bundletool and report base/feature download and installed sizes per ABI. A
  universal APK is not used as the Play size metric.
- Audit the optional MediaPipe LLM runtime. If it materially inflates the base build, move it to a
  separate on-demand AI feature or remove it from the base while preserving deterministic parsing.

### Release build correctness

- Upgrade compile/target SDK to API 36 and update the Android Gradle/plugin/dependency toolchain to a
  mutually supported stable set.
- Re-enable release lint after resolving the current tool mismatch; `abortOnError` and release checks
  become true.
- Enable R8 optimization and resource shrinking with tested keep rules for Room, Retrofit,
  serialization, Hilt, WorkManager, MediaPipe, ONNX, and OpenCV.
- Production release builds fail when release signing is absent; they never fall back to the debug
  key. Produce an AAB for Play App Signing and a separately signed standalone APK.
- Verify every packaged native library and the generated AAB/APKs for 16 KB page alignment and run
  OCR on a 16 KB emulator.
- Review dependencies and remove unused/redundant libraries and oversized resources, including
  replacing broad icon packs where a small local vector set suffices.

### Privacy and platform safety

- Keep HTTPS-only production traffic and debug-only localhost exceptions.
- Disable broad Android backup or define extraction/backup rules that exclude tokens, SMS-derived
  data, receipt caches, databases, and downloaded models.
- Protect bearer tokens with Android Keystore-backed encryption and ensure logs never contain tokens,
  SMS bodies, OCR text, or receipt paths.
- Audit exported activities/receivers/providers, deep-link validation, pending intents, notification
  permission behavior, foreground service declarations, URI grants, and image decoding limits.
- Retain `READ_SMS`/`RECEIVE_SMS` only for the declared core money-management feature.

### Runtime quality

- Generate Baseline and Startup Profiles for cold start, home/dashboard, group open, receipt scan,
  and itemized review. Benchmark before/after on a physical mid-range device when available.
- Remove main-thread disk/network work, bound image memory, handle low-storage and process-death
  recovery, and make downloads/expense uploads idempotent.
- Standardize loading/empty/error/offline states and accessibility labels; verify dark theme,
  font scaling, keyboard/insets, RTL resilience, and TalkBack order on primary flows.

---

## Gate 4 — SMS and receipt-engine quality

### SMS import improvements

- Preserve fully on-device parsing and document that behavior in the permission rationale.
- Test multipart assembly, duplicate broadcasts, sender normalization, replay after process death,
  malformed/very long messages, locale/decimal variations, debit/refund/reversal, card/UPI/cash-like
  wording, pending vs completed alerts, and date/year boundaries.
- Maintain an idempotent message fingerprint so a broadcast and later inbox scan cannot create the
  same transaction twice.
- Expand parser fixtures for major Indian banks/UPI formats while redacting all real PII.
- Ask for SMS permission only immediately before enabling automatic import, support denial and
  "don't ask again," and keep manual/receipt entry fully usable without it.

### Deterministic 100-receipt OCR evaluation

- Add a reproducible script that downloads a pinned subset of 100 images/annotations from the CORD
  dataset (CC BY 4.0), verifies archive hashes, and keeps the dataset out of Git history.
- Record dataset version, deterministic sample IDs/seed, attribution, and license. Do not scrape
  random copyrighted bill images.
- Run Paddle end-to-end on an Android target, not only desktop ONNX. Record cold load, warm latency,
  peak memory, detected-line count, normalized character error rate, crashes/timeouts, and parser
  extraction results.
- Report item-name and amount precision/recall, total/tax exact-match, arithmetic verification rate,
  and examples by failure category. CORD is Indonesian, so it is a regression corpus rather than a
  claim of Indian-bill representativeness.
- Add synthetic Indian-format fixtures and a clearly separated consented-device set when the owner
  supplies real bills. No personal receipt corpus is committed or uploaded.
- Initial release gates: 100/100 scans complete without crash/OOM; no material accuracy regression
  from the accepted Paddle baseline; total-field and item-amount metrics meet thresholds established
  and committed after the first reproducible baseline run. Any threshold change requires an
  explained benchmark diff, never silent golden-output rewriting.

---

## Gate 5 — Play Store, legal surfaces, and launch assets

### Store and policy package

- Produce `bundleRelease` AAB, mapping/native symbols required for diagnostics, dependency/license
  notices, and a release provenance/checksum file.
- Draft store title, short/full descriptions, category/tags, release notes, support contact checklist,
  countries/pricing checklist, content-rating answers, Data safety worksheet, financial-features
  declaration notes, and the SMS permission declaration with a reviewer walkthrough.
- Add backend-hosted privacy policy, terms, support, and account-deletion information pages. The app
  includes discoverable privacy/support/delete-account links. Final owner/legal review remains a
  launch gate.
- If the Play developer account is a personal account created after 2023-11-13, plan a closed test
  with at least 12 continuously opted-in testers for at least 14 days before production access.

### Visual assets

- Preserve the real Schism UI and brand rather than fabricating product capabilities.
- Produce a 512×512 Play icon, 1024×500 feature graphic, adaptive/monochrome launcher assets,
  correctly sized phone screenshots, optional tablet screenshots only if tablet UX passes, and a
  coherent promo/ad asset set.
- Capture screenshots from the release candidate with safe fixture data. Image generation may create
  supporting graphic backgrounds/illustrations, but not fake in-app screens.
- Validate crops, text safe areas, contrast, localization, file types, and Play metadata rules. Keep
  editable source assets and an export manifest in the repository.

---

## Gate 6 — CI, full audit, and release process

### Continuous verification

- GitHub Actions jobs for backend format/vet/test/race/static analysis, Android unit tests, lint,
  debug instrumentation where available, release AAB/APK builds, dependency/license checks, and
  artifact checksums.
- CI uses pinned action/tool major versions, least-privilege permissions, no secrets on pull requests,
  dependency caching keyed by lockfiles, and cancellation of superseded runs.
- Add secret scanning and a dependency/vulnerability review using tools compatible with Go 1.26 and
  the selected Android toolchain.

### Manual/device matrix

At minimum, verify Android 8 (min SDK behavior), a commonly used mid-range Android version, Android
15 on 4 KB and 16 KB environments, and Android 16/API 36 behavior. Primary journeys:

1. Fresh install, registration/login/logout/session expiry/account deletion.
2. Create group, participant-bound invite redemption, non-member denial, edit group.
3. Add/edit/delete/offline-retry expenses and confirm balances/activity consistency.
4. SMS permission grant/deny/revoke and duplicate-safe transaction import.
5. First OCR use: feature/model download, cancel/resume, checksum failure, low storage, scan offline.
6. Itemized split/claim concurrency and finalized expense correctness.
7. Upgrade from v1.2.2 with existing Room/DataStore/backend data preserved.

### Release and rollback

- Ship v1.3.0 first to Play internal testing, then closed testing, then a staged production rollout.
  Promotion requires zero known P0/P1 issues, all automated gates green, policy pages/declarations
  accepted, and manual matrix sign-off.
- Backend changes are backward-compatible during the mobile rollout or are protected by explicit
  minimum-version errors. Database migrations are additive first; destructive cleanup is deferred.
- OCR manifest supports immediate rollback to the previous known-good model without an app release.
  The Android installer retains the previous verified model until the new one loads.
- Preserve the v1.2.2 tag/artifacts. A new GitHub tag/release is cut only from the verified release
  commit, with signed artifacts, checksums, release notes, and known limitations.

---

## Evidence already collected

- Backend `go test ./...`, `go vet ./...`, and `go test -race ./...` pass on Go 1.26.2.
- The installed Staticcheck is built with Go 1.25 and cannot analyze this Go 1.26.2 module; CI/tooling
  must install a compatible build before Staticcheck can be counted as passing.
- The deployed API is HTTPS behind Istio/Envoy. `/model` currently returns a basic Hugging Face
  redirect. `/health` currently returns an escaped, invalid-JSON body.
- The Android release currently targets API 35, has R8 and release lint disabled, and permits debug
  signing fallback. The manifest requests SMS permissions and allows backup.
- PP-OCRv6 Tiny inference and the app receipt-scanner instrumentation path passed on the existing
  16 KB emulator reference test. The full 100-receipt corpus and physical-device matrix have not yet
  been run.

## Completion definition

"Release ready" means the implemented gates above have evidence, not merely that an APK builds. The
handoff includes: audit findings and resolutions, before/after size and performance reports, backend
and Android test results, the OCR benchmark report, signed release-candidate artifacts/checksums,
Play submission materials/assets, deployment and rollback instructions, and an explicit list of any
remaining owner-controlled steps such as Play Console forms, tester enrollment, credentials, and
legal approval.

## Authoritative references

- Google Play target API requirements:
  <https://support.google.com/googleplay/android-developer/answer/11926878>
- Android 16 KB page-size compatibility:
  <https://developer.android.com/guide/practices/page-sizes>
- Play Feature Delivery:
  <https://developer.android.com/guide/playcore/feature-delivery>
- Google Play SMS/Call Log permission policy:
  <https://support.google.com/googleplay/android-developer/answer/10208820>
- Data safety and account deletion requirements:
  <https://support.google.com/googleplay/android-developer/answer/10787469> and
  <https://support.google.com/googleplay/android-developer/answer/13327111>
- Play preview-asset requirements:
  <https://support.google.com/googleplay/android-developer/answer/9866151>
- Android Baseline Profiles:
  <https://developer.android.com/topic/performance/baselineprofiles/overview>
- Official PaddleOCR Android deployment and PP-OCRv6 documentation:
  <https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html>
  and <https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html>
- CORD dataset and CC BY 4.0 license: <https://github.com/clovaai/cord>
