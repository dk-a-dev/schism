# CI, Release, and Rollback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every Schism v1.3.0 launch gate reproducible in CI, produce signed and independently verifiable Android artifacts, prove backend rollback, and publish the GitHub release only after all evidence passes.

**Architecture:** Separate least-privilege workflows validate backend, Android, security, and release artifacts. Pull requests use an ephemeral CI signing key and never receive production secrets. A protected `v1.3.0` tag job uses the production upload key, verifies the exact AAB/APK, creates checksums and provenance, and publishes a draft release that is promoted only after manual device, policy, and rollback gates are recorded.

**Tech Stack:** GitHub Actions, Go 1.26.2, Staticcheck 2026.1/v0.7.0, govulncheck, JDK 17, Gradle 8.13, Android SDK 36, apksigner/bundletool, GitHub CLI.

## Global Constraints

- CI action major versions are pinned: `actions/checkout@v6`, `actions/setup-go@v7`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v4`.
- Workflow permissions default to read-only and are widened only per job.
- Pull-request builds cannot access the production keystore, passwords, backend credentials, or Play service account.
- Release artifacts must be non-debuggable, production-signed, API 36-targeted, 16-KB compatible, and built from a clean tagged commit.
- A failed verification never creates or mutates a GitHub release.
- Play Console upload and staged rollout remain owner-controlled; this plan produces the upload-ready AAB and evidence.
- Backend production deployment is not performed automatically; the workflow proves the image, migration, health, and rollback commands against an isolated environment.

---

### Task 1: Backend CI and security gates

**Files:**
- Create: `.github/workflows/backend.yml`
- Create: `schism-backend/scripts/ci.sh`
- Create: `schism-backend/scripts/integration.sh`
- Modify: `schism-backend/Makefile`
- Create: `schism-backend/internal/api/health_contract_test.go`

**Interfaces:**
- Produces: deterministic `backend-ci` job covering format, unit/integration/race, vet, Staticcheck, vulnerability scan, migration round-trip, and health shutdown behavior.
- Consumes: backend security/model-delivery plan tests and Postgres 16 service container.

- [ ] **Step 1: Write failing CI-script contract tests**

Add shell-script checks for `set -euo pipefail`, repository-root resolution, pinned tool versions,
machine-readable JUnit/test logs, no production URL, and cleanup traps. Add Go tests asserting `/health`
returns valid JSON during normal service and non-ready status during graceful shutdown.

- [ ] **Step 2: Run the baseline and capture failures**

Run: `cd schism-backend && go test ./... -count=1 && go test -race ./... -count=1 && go vet ./... && go run honnef.co/go/tools/cmd/staticcheck@v0.7.0 ./... && go run golang.org/x/vuln/cmd/govulncheck@v1.6.0 ./...`.

Expected: existing tests expose any remaining health/static/security failures; both command versions
are reproducibly pinned while govulncheck still reads the current Go vulnerability database.

- [ ] **Step 3: Implement workflow and isolated database integration**

Use Go `1.26.2`, Postgres `16`, explicit health checks, module/build caches, and read-only workflow
permissions. `scripts/integration.sh` applies every migration, runs API integration tests, exercises
one down/up rollback step against disposable data, and confirms migration version/data invariants.
Upload test logs only on failure and exclude environment/database values.

- [ ] **Step 4: Verify locally and validate workflow syntax**

Run: `cd schism-backend && ./scripts/ci.sh && ./scripts/integration.sh` with a disposable local Postgres
database, then `actionlint .github/workflows/backend.yml`.

Expected: PASS with no warning suppressed and no secret printed.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/backend.yml schism-backend
git commit -m "ci(backend): gate security and migrations"
```

### Task 2: Android CI with release-equivalent validation

**Files:**
- Create: `.github/workflows/android.yml`
- Create: `schism-android/tools/create-ci-keystore.sh`
- Create: `schism-android/tools/ci.sh`
- Create: `schism-android/app/src/test/java/ai/schism/split/build/VariantIsolationTest.kt`
- Modify: `schism-android/app/build.gradle.kts`
- Modify: `schism-android/gradle.properties`

**Interfaces:**
- Produces: PR-safe unit/lint/R8/build gates for Play and standalone distributions plus archived reports.
- Consumes: API-36 build, OCR variant graph, SMS tests, and release packaging verifier from Android plans.

- [ ] **Step 1: Add failing variant-isolation checks**

Assert the CI validation build is minified and resource-shrunk but signed only by an ephemeral key;
production release still refuses absent release properties. Assert play base excludes OCR runtime/models,
standalone includes verified models, store fixtures are absent, and benchmark hooks are absent from
both production distributions.

- [ ] **Step 2: Run the release-equivalent baseline**

Run: `cd schism-android && ./gradlew test lintPlayRelease lintStandaloneRelease bundlePlayRelease assembleStandaloneRelease` with an ephemeral CI keystore generated in a temporary directory.

Expected: current configuration fails until CI signing is isolated from protected production signing
and all release gates are enabled.

- [ ] **Step 3: Implement Android workflow**

Use JDK 17, SDK 36, wrapper validation, Gradle basic cache, concurrency cancellation, and read-only
permissions. Generate a fresh CI RSA-4096 keystore into the runner temp directory, pass its path only
to validation variants, run parser/app/library unit suites, release lint, R8 bundles/APKs, packaging
checks, and unit-level manifest/variant isolation. Upload lint/test reports on failure and unsigned or
CI-signed artifacts only as short-retention diagnostic artifacts clearly named `not-for-release`.

- [ ] **Step 4: Verify script and workflow locally**

Run: `cd schism-android && tools/ci.sh`, then `actionlint .github/workflows/android.yml`.

Expected: PASS; `git status --short` contains no generated keystore or build artifact.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/android.yml schism-android
git commit -m "ci(android): verify release-equivalent variants"
```

### Task 3: Dependency and code-scanning automation

**Files:**
- Create: `.github/workflows/security.yml`
- Create: `.github/dependabot.yml`
- Create: `docs/security/dependency-policy.md`
- Create: `.github/CODEOWNERS`

**Interfaces:**
- Produces: weekly dependency review, Go/Gradle dependency submissions, CodeQL for Go/Kotlin/Java, secret scanning guidance, and owned security-sensitive paths.
- Consumes: backend and Android dependency graphs.

- [ ] **Step 1: Add policy assertions**

Document severity/response windows, review requirements for auth/crypto/manifest/model-source changes,
allowed dependency sources, and the rule that automated updates never auto-merge release-signing,
Play Core, ONNX, OpenCV, database, or authentication changes.

- [ ] **Step 2: Add least-privilege scanners**

Configure CodeQL `v4` for Go and Java/Kotlin, `actions/dependency-review-action@v4` for pull requests,
Gradle dependency submission, and weekly Dependabot groups separated by backend, Android, and GitHub
Actions. Grant `security-events: write` only to CodeQL and `contents: write` only to dependency
submission on trusted branches.

- [ ] **Step 3: Validate configuration**

Run: `actionlint .github/workflows/security.yml` and parse `.github/dependabot.yml` with Ruby's standard
YAML loader.

Expected: PASS; no workflow uses broad write-all permission or unpinned branch refs.

- [ ] **Step 4: Commit**

```bash
git add .github docs/security
git commit -m "ci: add dependency and code security gates"
```

### Task 4: Reproducible artifact verification and checksums

**Files:**
- Create: `tools/release/verify_android_artifacts.sh`
- Create: `tools/release/check_16kb.sh`
- Create: `tools/release/generate_checksums.sh`
- Create: `tools/release/test_release_tools.sh`
- Create: `docs/release/v1.3/artifact-manifest.schema.json`
- Create: `docs/release/v1.3/release-checklist.md`

**Interfaces:**
- Produces: verified AAB/APK metadata, signer digest, model inclusion/exclusion, native alignment evidence, SHA-256 file, and machine-readable artifact manifest.
- Consumes: `bundlePlayRelease`, `assembleStandaloneRelease`, Android SDK build tools, and bundletool.

- [ ] **Step 1: Write failure-first release-tool fixtures**

Test rejection of debug signer/debuggable APK, wrong application ID/version/API, missing standalone
model, model leaked into Play base, wrong model hash, unaligned native library, absent provenance,
duplicate artifact name, and checksum mismatch. Use tiny synthetic ZIP fixtures; never copy a release
key into tests.

- [ ] **Step 2: Run tests and confirm failure**

Run: `tools/release/test_release_tools.sh`.

Expected: FAIL because verification tools do not exist.

- [ ] **Step 3: Implement strict verifiers**

Use `bundletool`, `apkanalyzer`, `aapt2`, `apksigner`, `zipalign`, `readelf`, `unzip`, and `shasum -a 256`.
Require package `ai.schism.split`, version code `10300`, target SDK `36`, non-debuggable manifest,
expected production signer digest supplied through protected CI configuration, Play-base OCR absence,
standalone model hashes, and 16-KB LOAD-segment alignment for every arm64 native library. Write stable
JSON provenance containing source commit/tag, tool versions, signer digest, artifact sizes/hashes, and
test evidence links.

- [ ] **Step 4: Verify real release artifacts**

Run: `tools/release/verify_android_artifacts.sh schism-android/app/build/outputs/bundle/playRelease/app-play-release.aab schism-android/app/build/outputs/apk/standalone/release/app-standalone-release.apk` followed by `tools/release/generate_checksums.sh dist/v1.3.0`.

Expected: PASS and deterministic `dist/v1.3.0/SHA256SUMS` plus artifact manifest.

- [ ] **Step 5: Commit tools and checklist**

```bash
git add tools/release docs/release/v1.3
git commit -m "build(release): verify signed Android artifacts"
```

### Task 5: Manual device matrix and backend rollback drill

**Files:**
- Create: `docs/release/v1.3/device-matrix.md`
- Create: `docs/release/v1.3/backend-rollback.md`
- Create: `schism-backend/scripts/rollback-drill.sh`
- Modify: `docs/release/v1.3/release-checklist.md`

**Interfaces:**
- Produces: dated evidence for API 26/30/35/36, 16-KB arm64, permission/network interruption, upgrade from v1.2.2, and isolated backend rollback.
- Consumes: final signed standalone APK, Play internal-testing AAB, v1.2.2 APK, migrations, and safe fixture account/data.

- [ ] **Step 1: Implement isolated rollback drill**

Create a disposable database, apply migrations through the v1.3 schema, seed non-sensitive group,
participant, invite, expense, and model-manifest records, run one supported migration rollback, reapply,
then assert record counts/constraints and API health. The script refuses a database host not equal to
localhost/127.0.0.1 or a database name without the `schism_rollback_` prefix.

- [ ] **Step 2: Execute the Android matrix**

On API 26, 30, 35, and 36 devices/emulators test clean install, v1.2.2 upgrade, login/logout, deny/grant/
revoke SMS, duplicate multipart import, offline/manual entry, OCR download cancel/resume/hash recovery,
scan/itemized split, invite redeem/replay, account deletion, process death, rotation, dark theme, large
font, TalkBack focus, and airplane-mode recovery. Run the 16-KB image on the API-36 arm64 target.

- [ ] **Step 3: Record evidence and defects**

For every row record device fingerprint, API, page size, artifact SHA-256, pass/fail, timestamp, and
issue link. No unchecked row is converted to pass by prose; fix the defect and rerun the affected row.

- [ ] **Step 4: Run rollback and final matrix check**

Run: `cd schism-backend && ./scripts/rollback-drill.sh`, then validate every required table row with
`python3 tools/release/validate_matrix.py docs/release/v1.3/device-matrix.md`.

Expected: PASS with zero unresolved release-blocking rows.

- [ ] **Step 5: Commit evidence**

```bash
git add schism-backend/scripts docs/release/v1.3
git commit -m "test(release): record device and rollback evidence"
```

### Task 6: Protected tag build and GitHub release

**Files:**
- Create: `.github/workflows/release.yml`
- Create: `tools/release/prepare_release.sh`
- Create: `docs/release/v1.3/release-notes.md`
- Create: `CHANGELOG.md`
- Modify: `docs/release/v1.3/release-checklist.md`

**Interfaces:**
- Produces: protected-tag production build, `v1.3.0` annotated tag, GitHub release with AAB, standalone APK, checksums, provenance, OCR report, and release notes.
- Consumes: all six roadmap checkpoints, production keystore secrets, support/legal approval, and final commit.

- [ ] **Step 1: Add release preflight tests**

`prepare_release.sh --check` must reject a dirty tree, wrong branch/version, missing checklist evidence,
failed CI, absent support/legal approval, unreviewed Play declarations, missing 100-receipt report,
missing device/rollback result, duplicate tag, or artifact signer/hash mismatch.

- [ ] **Step 2: Implement protected release workflow**

Trigger only on `v1.3.0` or manual dry-run. Decode production keystore into runner temp storage, build
from a clean checkout with JDK 17/API 36, run release lint/tests and artifact verifiers, delete key
material in an always-run cleanup step, then upload immutable artifacts. Give `contents: write` only to
the final publish job. A dry-run never tags or creates a release.

- [ ] **Step 3: Run local dry-run and protected CI dry-run**

Run: `tools/release/prepare_release.sh --check --version 1.3.0`, then dispatch the release workflow with
`dry_run=true` and confirm downloaded artifacts match its published SHA-256 manifest.

Expected: PASS; no GitHub release or tag exists after dry-run.

- [ ] **Step 4: Create tag and publish only after all gates pass**

Run: `git tag -s v1.3.0 -m "Schism v1.3.0"`, push the exact tag, wait for protected workflow success,
then create the GitHub release from `docs/release/v1.3/release-notes.md` with the verified AAB,
standalone APK, `SHA256SUMS`, provenance JSON, and OCR benchmark report. Mark it as a prerelease until
the owner confirms Play internal-test installation; then promote the same immutable release.

- [ ] **Step 5: Verify published release and record identifiers**

Download every GitHub release asset into a fresh temporary directory, verify `SHA256SUMS`, signer,
version, and source tag, and record the release URL/workflow run/artifact hashes in the checklist.

- [ ] **Step 6: Commit workflow before tagging**

```bash
git add .github/workflows/release.yml tools/release docs/release/v1.3 CHANGELOG.md
git commit -m "release: prepare Schism v1.3.0"
```
