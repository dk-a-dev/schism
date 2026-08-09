# Android Launch Quality and SMS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the complete Android app compliant, secure, optimized, accessible, and stable enough for a v1.3.0 Play release candidate while retaining improved on-device SMS import.

**Architecture:** Upgrade the build first so lint/R8/API-36 feedback is trustworthy. Then protect local identity and data, migrate the client to membership/invite contracts, harden SMS ingestion, and measure critical journeys with Baseline Profiles. Release verification exercises both Play and standalone distributions from the OCR plan.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13, Android API 36, Compose, Hilt, Room, DataStore, Android Keystore, WorkManager, Macrobenchmark/Baseline Profiles.

## Global Constraints

- `minSdk=26`, `compileSdk=36`, `targetSdk=36`, version code `10300`, version name `1.3.0`.
- Release lint, R8 optimization, resource shrinking, and release signing are mandatory.
- Preserve Room/DataStore state from v1.2.2; never use destructive migration for schema version 5+.
- SMS content stays on-device and manual/receipt entry works when permission is denied.
- Release traffic is HTTPS-only; backup excludes all sensitive/local financial data.
- No token, SMS body, OCR text, receipt path, password, or model credential appears in logs.

---

### Task 1: API 36 and supported build toolchain

**Files:**
- Modify: `schism-android/gradle/libs.versions.toml`
- Modify: `schism-android/gradle/wrapper/gradle-wrapper.properties`
- Modify: `schism-android/app/build.gradle.kts`
- Modify: `schism-android/parser-core/build.gradle.kts`
- Modify: `schism-android/ppocr-sdk/build.gradle.kts`
- Modify: `schism-android/ocr-contract/build.gradle.kts`
- Modify: `schism-android/ocr-impl/build.gradle.kts`
- Modify: `schism-android/ocr-feature/build.gradle.kts`
- Modify: `schism-android/gradle.properties`
- Test: all module compile/test tasks

**Interfaces:**
- Produces: AGP `8.13.2`, Gradle `8.13`, API 36 builds, v1.3.0 metadata.
- Consumes: module graph created by the OCR delivery plan.

- [ ] **Step 1: Record the current failing API/tool baseline**

Run: `cd schism-android && ./gradlew clean :app:compilePlayReleaseKotlin :app:lintPlayRelease`.

Expected: current project is API 35 and release lint is disabled/misconfigured; preserve the output in
the task notes.

- [ ] **Step 2: Upgrade wrapper/plugin and SDK values**

Set wrapper SHA-validated Gradle 8.13 distribution, AGP 8.13.2, compile/target 36, build tools 35+
default, version `10300`/`1.3.0`, and JDK 17. Keep Kotlin 2.0.21 for the first upgrade to avoid
combining an unnecessary language migration with AGP; update only dependencies whose current version
fails API-36/lint/16-KB verification.

- [ ] **Step 3: Run dependency and compile checks**

Run: `cd schism-android && ./gradlew --version :app:dependencies :app:compilePlayReleaseKotlin :app:compileStandaloneReleaseKotlin`.

Expected: Gradle 8.13, JDK 17, and both variants compile without unsupported compileSdk warnings.

- [ ] **Step 4: Run all unit tests after tool migration**

Run: `cd schism-android && ./gradlew test --continue`.

Expected: PASS; failures are fixed at source rather than silenced with Gradle flags.

- [ ] **Step 5: Commit**

```bash
git add schism-android
git commit -m "build(android): target API 36 for v1.3"
```

### Task 2: Enforce lint, optimized shrinking, and real signing

**Files:**
- Modify: `schism-android/app/build.gradle.kts`
- Modify: `schism-android/app/proguard-rules.pro`
- Modify: `schism-android/ppocr-sdk/proguard-rules.pro`
- Create: `schism-android/app/src/test/java/ai/schism/split/build/ReleaseConfigTest.kt`
- Create: `schism-android/tools/verify-release-packaging.sh`
- Modify: `.gitignore`

**Interfaces:**
- Produces: minified/resource-shrunk release variants that require `keystore.properties` and a
  packaging verifier for signing certificate, debuggable flag, models, and native payload.
- Consumes: API-36 toolchain and Play/standalone variants.

- [ ] **Step 1: Add failing release configuration assertions**

Assert release `debuggable=false`, `minifyEnabled=true`, optimized resource shrinking true, lint
abort/check release true, and missing signing properties fail `bundlePlayRelease` with an explicit
message rather than using the debug keystore.

- [ ] **Step 2: Run assertions and confirm failure**

Run: `cd schism-android && ./gradlew :app:testPlayDebugUnitTest --tests '*ReleaseConfigTest' :app:lintPlayRelease`.

Expected: FAIL because current release disables shrinking/lint and allows debug signing fallback.

- [ ] **Step 3: Enable production checks and focused keep rules**

Enable optimized ProGuard/R8 plus resource shrinking. Add narrow keep rules for Kotlin serialization
DTO serializers, Retrofit annotations, Room entities/DAOs, Hilt/WorkManager generated factories,
MediaPipe task bindings, ONNX, OpenCV, and the reflectively loaded Play OCR provider class. Remove
the lint suppression block after updating dependencies causing the old lint crash. Throw during
release configuration when signing keys are absent; debug builds remain unaffected.

- [ ] **Step 4: Build and smoke-test minified artifacts**

Run: `cd schism-android && ./gradlew :app:lintPlayRelease :app:bundlePlayRelease :app:assembleStandaloneRelease :app:connectedStandaloneDebugAndroidTest` with release signing configured for the release artifacts.

Expected: PASS; login, Room, Retrofit, WorkManager, OCR, and claim reflection paths work minified.

- [ ] **Step 5: Commit**

```bash
git add .gitignore schism-android
git commit -m "build(android): enforce optimized signed releases"
```

### Task 3: Protect tokens, backup, networking, and Room upgrades

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/core/security/SecureTokenStore.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/security/SecureTokenStoreTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/settings/SettingsRepository.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/AuthTokenProvider.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/SchismApp.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/di/DbModule.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/db/SchismDb.kt`
- Create: `schism-android/app/schemas/ai.schism.split.core.db.SchismDb/5.json`
- Modify: `schism-android/app/src/main/AndroidManifest.xml`
- Create: `schism-android/app/src/main/res/xml/data_extraction_rules.xml`
- Create: `schism-android/app/src/main/res/xml/backup_rules.xml`
- Modify: `schism-android/app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Produces: `SecureTokenStore.read/write/clear`, one-time plaintext-token migration, non-destructive
  Room v5 behavior, and sensitive-data backup exclusion.
- Consumes: existing settings flows and synchronous `AuthTokenProvider` interceptor behavior.

- [ ] **Step 1: Write failing security/migration tests**

Test AES-256-GCM Keystore alias `schism.auth.v1`, unique IV per write, tamper clears token safely,
legacy DataStore token migrates once then is erased, logout clears encrypted value, backup XML excludes
`schism.db`, DataStore, model directory, and receipt cache, and Room opens a v5 fixture without
destructive fallback.

- [ ] **Step 2: Run tests and verify failure**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*SecureTokenStoreTest' --tests '*SettingsRepositoryTest' --tests '*GroupDaoTest'`.

Expected: FAIL because tokens are plaintext and Room uses destructive fallback.

- [ ] **Step 3: Implement secure storage and platform configuration**

Store ciphertext/IV in private SharedPreferences using a non-exportable AndroidKeyStore AES/GCM key;
serialize access with a mutex. Migrate `auth_token` during app initialization before network use.
Set `allowBackup=false` plus explicit exclusion rules for OEM/device-transfer clarity. Export Room
schema and remove `.fallbackToDestructiveMigration()`; no schema migration is needed from v5 when
entities remain unchanged. Make main/release network config `base-config cleartextTrafficPermitted=false`;
keep the permissive override only in `src/debug`.

- [ ] **Step 4: Run privacy, database, and network tests**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest :app:lintPlayRelease`.

Expected: PASS; manifest merger shows no release cleartext or backup path.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app
git commit -m "fix(android): protect local identity and financial data"
```

### Task 4: Consume secured membership and participant invites

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/ApiService.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/Dto.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/groups/invite/ParticipantInviteRepository.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/groups/invite/RedeemInviteViewModel.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/groups/invite/RedeemInviteScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/groups/qr/InviteQrViewModel.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/groups/qr/InviteQrScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/groups/ContactInvite.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/nav/AppNav.kt`
- Modify: `schism-android/app/src/main/AndroidManifest.xml`
- Create: `schism-android/app/src/test/java/ai/schism/split/groups/invite/ParticipantInviteRepositoryTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/groups/invite/RedeemInviteViewModelTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/groups/invite/InviteNavigationTest.kt`
- Modify: `schism-android/app/src/test/java/ai/schism/split/groups/data/GroupRepositoryTest.kt`

**Interfaces:**
- Produces: organizer participant selection → invite creation/share and `schism://invite/{token}`
  preview/confirm/redeem flow.
- Consumes: backend Task 4 invite DTOs and backend Task 3 membership errors.

- [ ] **Step 1: Write failing invite-flow tests**

Test organizer chooses an unlinked participant, shares `/i/{token}`, recipient deep-link waits for
login when necessary, preview reveals only group/participant names, confirm redeems then stores group
ID, replay/expired/linked errors are explicit, and non-member `403` removes stale known-group cache
without displaying group data.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*Invite*' --tests '*JoinGroup*'`.

Expected: FAIL because current links contain group IDs and join by unrestricted GET.

- [ ] **Step 3: Implement participant-bound UX and remove ID joining**

Replace paste/raw-group-ID join with invite-token redemption. In QR/share UI require choosing one
unlinked participant; request a fresh token and share the HTTPS landing. Add invite deep link and
post-login continuation. Retain a friendly legacy-link message but never cache a group from a raw ID.
Map `401` to the existing session-expired gate and `403` to "You are not a member of this group."

- [ ] **Step 4: Verify navigation and backend contract integration**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*Invite*' --tests '*GroupRepository*' --tests '*ApiService*'`.

Expected: PASS; no production path calls unrestricted group join.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(android): redeem secure participant invitations"
```

### Task 5: Make SMS ingestion duplicate-safe and permission-transparent

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/sms/ingest/SmsEnvelope.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/sms/ingest/SmsReceiverTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/sms/ingest/SmsIngestWorkerTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/ingest/SmsReceiver.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/ingest/SmsIngestWorker.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/ingest/SmsScanWorker.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/data/SmsRepository.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/data/TransactionDao.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/inbox/InboxScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/OnboardingScreen.kt`
- Modify: `schism-android/app/src/main/res/values/strings.xml`
- Create: `schism-android/parser-core/src/test/resources/sms/debit.txt`
- Create: `schism-android/parser-core/src/test/resources/sms/refund.txt`
- Create: `schism-android/parser-core/src/test/resources/sms/reversal.txt`
- Create: `schism-android/parser-core/src/test/resources/sms/pending.txt`
- Create: `schism-android/parser-core/src/test/resources/sms/upi.txt`
- Create: `schism-android/parser-core/src/test/resources/sms/card.txt`

**Interfaces:**
- Produces: `SmsEnvelope(sender, body, timestamp, fingerprint)`, unique work name built as
  `"sms_ingest_${fingerprint}"`, and an explicit SMS import opt-in/rationale state.
- Consumes: existing stable parser transaction IDs and Room `INSERT IGNORE` as the second dedup layer.

- [ ] **Step 1: Add failing SMS edge-case tests**

Cover multipart segments grouped by sender and timestamp, two senders in one intent retaining their
own timestamps, duplicate broadcast+inbox scan, reordered multipart segments, empty origin/body,
8-KiB body limit, process retry, permission deny/revoke/don't-ask-again, and representative redacted
debit/refund/reversal/pending/UPI/card fixtures across major Indian formats.

- [ ] **Step 2: Run tests and confirm failures**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*SmsReceiverTest' --tests '*SmsIngestWorkerTest' :parser-core:test`.

Expected: FAIL on per-sender timestamps, unique work, and permission-state behavior.

- [ ] **Step 3: Implement two-layer dedup and permission flow**

Build the envelope fingerprint with SHA-256 of normalized sender, exact body, and timestamp bucket;
enqueue unique work with `ExistingWorkPolicy.KEEP`; keep parser transaction ID plus DAO `IGNORE` as
database idempotency. Reject oversize worker data before WorkManager's 10-KiB limit. Do not request
SMS during onboarding: show an inbox enable card, explain local-only parsing and shared-expense data,
then request immediately after user taps Enable. Keep manual/receipt paths visible after denial.

- [ ] **Step 4: Run all parser/SMS/UI state tests**

Run: `cd schism-android && ./gradlew :parser-core:test :app:testStandaloneDebugUnitTest --tests '*Sms*' --tests '*Inbox*'`.

Expected: PASS with no raw SMS logged or persisted outside parsed fields.

- [ ] **Step 5: Commit**

```bash
git add schism-android/parser-core schism-android/app/src
git commit -m "fix(sms): improve private duplicate-safe import"
```

### Task 6: Move optional MediaPipe AI out of the Play base

**Files:**
- Create: `schism-android/ai-contract/build.gradle.kts`
- Create: `schism-android/ai-contract/src/main/AndroidManifest.xml`
- Create: `schism-android/ai-contract/src/main/java/ai/schism/split/ai/api/ExpenseAiParser.kt`
- Create: `schism-android/ai-impl/build.gradle.kts`
- Create: `schism-android/ai-impl/src/main/AndroidManifest.xml`
- Create: `schism-android/ai-impl/src/main/java/ai/schism/split/ai/impl/MediaPipeExpenseAiParser.kt`
- Create: `schism-android/ai-feature/build.gradle.kts`
- Create: `schism-android/ai-feature/src/main/AndroidManifest.xml`
- Create: `schism-android/ai-feature/src/main/java/ai/schism/split/ai/feature/PlayExpenseAiParser.kt`
- Create: `schism-android/app/src/play/java/ai/schism/split/core/ai/PlayAiFeatureInstaller.kt`
- Create: `schism-android/app/src/standalone/java/ai/schism/split/core/ai/StandaloneAiFeatureInstaller.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/ai/AiFeatureInstallerTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/ai/LlmExpenseParser.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/ai/ModelManager.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/di/AppModule.kt`
- Modify: `schism-android/settings.gradle.kts`
- Modify: `schism-android/app/build.gradle.kts`
- Create: `schism-android/tools/analyze-app-size.sh`
- Create: `docs/release/v1.3/android-size.md`

**Interfaces:**
- Produces: base-safe `ExpenseAiParser`, on-demand Play `ai_feature`, direct standalone binding, and
  measured base/feature/standalone download and installed sizes per ABI.
- Consumes: existing local LLM model download, receipt/voice parser call sites, and Play SplitInstall.

- [ ] **Step 1: Record current dependency and native-size baseline**

Run `./gradlew :app:dependencies` and `tools/analyze-app-size.sh` against the v1.2.2 release APK/AAB.
Record MediaPipe AAR size and `libllm_inference_engine_jni.so` per ABI. The observed arm64 library is
roughly 12 MiB, above the fixed 5-MiB base-impact threshold, so feature isolation is required.

- [ ] **Step 2: Write failing base-isolation and behavior tests**

Assert Play base contains no MediaPipe class or `libllm_inference_engine_jni.so`; its on-demand
feature contains both. Assert standalone contains the runtime, unavailable/not-downloaded states
fall back to deterministic receipt/voice parsing, feature cancellation does not block editing, and
the same installed model produces equivalent sanitized drafts through both bindings.

- [ ] **Step 3: Implement contract, feature, and distribution bindings**

Move direct MediaPipe imports into `ai-impl`; keep prompts/sanitization behind the neutral contract.
The Play feature is `dist:onDemand="true"` with fusing disabled and exposes a public no-argument
provider. Standalone links `ai-impl` directly. Use the existing versioned data-model downloader;
never download executable code from the backend. Preserve the setting and always retain the
deterministic parser fallback.

- [ ] **Step 4: Verify behavior and measure final sizes**

Run: `cd schism-android && ./gradlew :ai-contract:check :ai-impl:check :app:testPlayDebugUnitTest :app:testStandaloneDebugUnitTest :app:bundlePlayRelease :app:assembleStandaloneRelease`, then run `tools/analyze-app-size.sh` on both artifacts.

Expected: PASS; Play base excludes MediaPipe, the feature contains it, standalone behavior is
unchanged, and `android-size.md` reports Play base/OCR feature/AI feature/standalone sizes per ABI.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/android-size.md
git commit -m "perf(android): deliver optional local AI on demand"
```

### Task 7: Baseline Profiles and measurable critical journeys

**Files:**
- Create: `schism-android/benchmark/build.gradle.kts`
- Create: `schism-android/benchmark/src/main/AndroidManifest.xml`
- Create: `schism-android/benchmark/src/main/java/ai/schism/split/benchmark/BaselineProfileGenerator.kt`
- Create: `schism-android/benchmark/src/main/java/ai/schism/split/benchmark/StartupBenchmark.kt`
- Create: `schism-android/app/src/main/baseline-prof.txt`
- Modify: `schism-android/settings.gradle.kts`
- Modify: `schism-android/build.gradle.kts`
- Modify: `schism-android/gradle/libs.versions.toml`
- Modify: `schism-android/app/build.gradle.kts`
- Create: `docs/release/v1.3/android-performance.md`

**Interfaces:**
- Produces: Baseline/Startup Profiles for cold launch, inbox, group open, expense creation, receipt
  scan entry, and itemized review; macrobenchmark before/after metrics.
- Consumes: deterministic benchmark fixture account/group/data and standalone release variant.

- [ ] **Step 1: Create baseline generator and benchmark assertions**

Use `BaselineProfileRule` for the six journeys and `MacrobenchmarkRule` with `StartupMode.COLD`, 10
iterations, compilation modes None and Partial(BaselineProfile). Assert the benchmark completes and
records `timeToInitialDisplayMs`/`frameDurationCpuMs`; performance improvement is reported, not made
flaky by a hard percentage unit-test gate.

- [ ] **Step 2: Run generation once and confirm profile is initially absent**

Run: `cd schism-android && ./gradlew :benchmark:generateStandaloneReleaseBaselineProfile`.

Expected: generator runs against a physical or API-36 emulator and produces new profile output.

- [ ] **Step 3: Wire supported profile tool versions**

Add Baseline Profile plugin/library `1.4.1`, Macrobenchmark `1.4.1`, and Profile Installer `1.4.1`.
Include only stable selectors/test tags and seed safe fixture data through an internal benchmark-only
entry point excluded from production manifests.

- [ ] **Step 4: Benchmark before/after and record results**

Run: `cd schism-android && ./gradlew :benchmark:connectedCheck :app:assembleStandaloneRelease`.

Expected: PASS. Record device/API/build, medians/ranges, profile size/coverage, and startup/frame
results in `docs/release/v1.3/android-performance.md`.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/android-performance.md
git commit -m "perf(android): add baseline profiles for core journeys"
```

### Task 8: Accessibility, lifecycle, and full Android audit evidence

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/MainActivity.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/OnboardingScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/groups/list/GroupsListScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/groups/detail/GroupDetailScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/expense/edit/ExpenseEditScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/inbox/InboxScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/BillScan.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/ItemizedSplitScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/ui/LifecycleRecoveryTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/ui/UriValidationTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/accessibility/PrimaryJourneysAccessibilityTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/security/ManifestSecurityInstrumentedTest.kt`
- Create: `docs/release/v1.3/android-audit.md`
- Create: `docs/release/v1.3/device-matrix.md`

**Interfaces:**
- Produces: resolved audit table and automated/manual evidence for all primary journeys.
- Consumes: all Android and backend contract tasks.

- [ ] **Step 1: Add failure-first UI/lifecycle tests for audit findings**

Test content descriptions and roles for icon-only controls; 200% font scaling without clipped primary
actions; dark theme contrast snapshots/semantics; keyboard/insets on auth/expense forms; process
recreation during onboarding/download/edit; offline errors with retry; URI rejection; image bounds;
notification denial; and activity/receiver export expectations.

- [ ] **Step 2: Run unit, lint, and instrumentation audit**

Run: `cd schism-android && ./gradlew test lint connectedStandaloneDebugAndroidTest --continue`.

Expected: initial audit exposes concrete failures; list each in `android-audit.md` with severity and
reproduction before fixing.

- [ ] **Step 3: Fix every P0/P1 and primary-flow P2 finding**

Use consistent loading/empty/offline/error components, lifecycle-aware collection, saved state for
forms, bounded bitmap streams, validated content URIs, non-exported components by default, immutable
PendingIntent flags, and semantic labels/touch targets. Record lower-risk deferred polish explicitly
with owner impact; do not call it release-blocking work complete while any P0/P1 remains.

- [ ] **Step 4: Run complete release matrix automation**

Run:

```bash
cd schism-android
./gradlew clean test lint :app:bundlePlayRelease :app:assembleStandaloneRelease
./gradlew :app:connectedStandaloneDebugAndroidTest :ppocr-sdk:connectedDebugAndroidTest
```

Expected: PASS. Fill `android-audit.md` with commands/results and `device-matrix.md` with Android 8,
mid-range, Android 15 4-KB/16-KB, and Android 16/API-36 journey results; unavailable physical devices
remain explicit owner-controlled matrix rows, not fabricated passes.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/android-audit.md docs/release/v1.3/device-matrix.md
git commit -m "fix(android): close launch readiness audit"
```
