# Android On-Demand OCR Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Play installs download OCR code and verified Paddle models only when scanning is first used while keeping one-file standalone APKs immediately offline-capable.

**Architecture:** A tiny `:ocr-contract` is the only OCR type visible to the base app. `:ocr-impl` owns Paddle/OpenCV/ONNX and implements that contract; Play loads it through an on-demand `:ocr-feature`, while the standalone flavor links it directly and bundles model assets. A base WorkManager installer consumes the backend manifest and atomically installs checked model data.

**Tech Stack:** Kotlin, Android Gradle product flavors, Play Feature Delivery/SplitInstall, WorkManager, OkHttp, kotlinx.serialization, PaddleOCR PP-OCRv6 Tiny, ONNX Runtime, OpenCV.

## Global Constraints

- `play` base contains no Paddle, ONNX Runtime, OpenCV, or OCR model assets.
- `standalone` produces one signed APK with those runtimes and the three verified model assets.
- Downloaded files are app-private data, size- and SHA-256-verified, atomically promoted, and resumable.
- The previous verified model remains usable until a replacement loads successfully.
- Scanning images and OCR output never traverse the network.
- Existing parser `Row` stays in the app; module contracts expose neutral OCR geometry only.

---

### Task 1: File-backed Paddle model sources

**Files:**
- Create: `schism-android/ppocr-sdk/src/main/java/com/paddle/ocr/model/ModelSource.kt`
- Create: `schism-android/ppocr-sdk/src/test/java/com/paddle/ocr/model/ModelSourceTest.kt`
- Modify: `schism-android/ppocr-sdk/src/main/java/com/paddle/ocr/PaddleOCR.kt`
- Modify: `schism-android/ppocr-sdk/src/main/java/com/paddle/ocr/engine/OCREngine.kt`
- Modify: `schism-android/ppocr-sdk/src/main/java/com/paddle/ocr/engine/ORTSessionManager.kt`
- Modify: `schism-android/ppocr-sdk/src/main/java/com/paddle/ocr/model/ModelConfig.kt`
- Modify: `schism-android/ppocr-sdk/build.gradle.kts`

**Interfaces:**
- Produces: `sealed interface ModelSource { data class Asset(val path: String); data class FilePath(val file: File) }` and `PaddleOCR.create(context, config, engineConfig, detModel, recModel, recConfig)`.
- Consumes: existing asset defaults and returns existing `OCRRunResult` unchanged.

- [ ] **Step 1: Write failing source validation tests**

Test that an asset source retains its path, a file source rejects a missing/empty file with
`OCRError.ModelNotFound`, YAML parses equivalently from asset and file bytes, and model sources never
accept directory traversal as a downloadable filename.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd schism-android && ./gradlew :ppocr-sdk:testDebugUnitTest --tests '*ModelSourceTest'`

Expected: FAIL because `ModelSource` and file parsing do not exist.

- [ ] **Step 3: Implement source-aware loading**

Resolve assets with `AssetManager.open`; resolve files only after canonical-path, regular-file, and
non-zero checks. Pass absolute file paths to the ONNX Runtime file-session overload to avoid duplicate
whole-model byte arrays. Parse YAML from a supplied stream. Keep asset overloads as adapters so
existing callers and tests compile.

- [ ] **Step 4: Verify SDK unit and instrumentation behavior**

Run: `cd schism-android && ./gradlew :ppocr-sdk:testDebugUnitTest :ppocr-sdk:connectedDebugAndroidTest`.

Expected: PASS; asset inference remains identical and a temporary file-backed copy loads successfully.

- [ ] **Step 5: Commit**

```bash
git add schism-android/ppocr-sdk
git commit -m "feat(ocr): load Paddle models from verified files"
```

### Task 2: OCR contract and concrete implementation libraries

**Files:**
- Create: `schism-android/ocr-contract/build.gradle.kts`
- Create: `schism-android/ocr-contract/src/main/AndroidManifest.xml`
- Create: `schism-android/ocr-contract/src/main/java/ai/schism/split/ocr/api/OcrProvider.kt`
- Create: `schism-android/ocr-impl/build.gradle.kts`
- Create: `schism-android/ocr-impl/src/main/AndroidManifest.xml`
- Create: `schism-android/ocr-impl/src/main/java/ai/schism/split/ocr/impl/PaddleOcrProvider.kt`
- Create: `schism-android/ocr-impl/src/test/java/ai/schism/split/ocr/impl/PaddleGeometryTest.kt`
- Modify: `schism-android/settings.gradle.kts`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/receipt/PaddleRowAdapter.kt`
- Modify: `schism-android/app/src/test/java/ai/schism/split/sms/receipt/PaddleRowAdapterTest.kt`

**Interfaces:**
- Produces: `data class OcrModelFiles(val detection: File, val recognition: File, val recognitionConfig: File)`, `data class OcrLine(val text: String, val confidence: Float, val points: List<OcrPoint>)`, `data class OcrOutput(val lines: List<OcrLine>, val timing: OcrTiming)`, and `interface OcrProvider { suspend fun recognize(context: Context, uri: Uri, models: OcrModelFiles): OcrOutput; suspend fun close() }`.
- Consumes: Task 1 `ModelSource.FilePath`; app adapter maps `OcrLine` to existing `DetectedLine`/`Row`.

- [ ] **Step 1: Write failing contract/geometry tests**

Move geometry expectations from direct Paddle result types to neutral `OcrLine`: reject non-four-point
and non-finite boxes, preserve confidence/text, and sort output into the same visual rows.

- [ ] **Step 2: Run tests to verify failure**

Run: `cd schism-android && ./gradlew :app:testDebugUnitTest --tests '*PaddleRowAdapterTest' :ocr-impl:testDebugUnitTest`

Expected: FAIL because modules and neutral types do not exist.

- [ ] **Step 3: Create focused modules and provider**

Make `ocr-contract` depend only on Android/Kotlin. Make `ocr-impl` depend on `ocr-contract`,
`ppocr-sdk`, and ExifInterface. Move bitmap decode/EXIF rotation from `ReceiptScanner` into
`PaddleOcrProvider`, retain 2400-pixel decode and 1280-pixel detection bounds, and construct Paddle
from `OcrModelFiles(det, rec, config)`.

- [ ] **Step 4: Run module and adapter tests**

Run: `cd schism-android && ./gradlew :ocr-contract:assembleDebug :ocr-impl:testDebugUnitTest :app:testDebugUnitTest --tests '*PaddleRowAdapterTest'`.

Expected: PASS with no app source importing `com.paddle.ocr`.

- [ ] **Step 5: Commit**

```bash
git add schism-android/settings.gradle.kts schism-android/ocr-contract schism-android/ocr-impl schism-android/app/src
git commit -m "refactor(ocr): isolate Paddle behind an app contract"
```

### Task 3: Manifest client and atomic resumable model store

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/core/model/ArtifactManifest.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/core/model/ArtifactDownloader.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/core/model/OcrModelStore.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/model/ArtifactDownloaderTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/core/model/OcrModelStoreTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/ApiService.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/net/Dto.kt`

**Interfaces:**
- Produces: serializable `OcrManifestDto`, `ArtifactDownloader.download(spec, partFile, progress)`, and `OcrModelStore.current(): OcrModelFiles?`, `install(manifest, progress): OcrModelFiles`.
- Consumes: backend `GET /v1/models/ocr/manifest` and relative download paths from backend Task 5.

- [ ] **Step 1: Write MockWebServer failure-first tests**

Cover fresh `200`, interrupted `.part` resumed with `Range`/`If-Range`, `206` append, server ignoring
range with `200` restart, ETag change restart, redirect, wrong size, wrong SHA-256, insufficient
storage mapping, cancel preserving resumable part, and atomic promotion retaining prior `current`.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `cd schism-android && ./gradlew :app:testDebugUnitTest --tests 'ai.schism.split.core.model.*'`

Expected: FAIL because installer classes do not exist.

- [ ] **Step 3: Implement installer state machine**

Restrict names to `det.onnx`, `rec.onnx`, `rec.yml`; resolve relative paths against
`BuildConfig.BACKEND_URL`; persist ETag beside parts; stream through a 64 KiB buffer; calculate SHA-256
after transfer; `fsync`; rename a completed version directory and atomically replace a `current`
text marker. Keep one previous verified version and clean older/abandoned parts after seven days.

- [ ] **Step 4: Verify all model-store tests**

Run: `cd schism-android && ./gradlew :app:testDebugUnitTest --tests 'ai.schism.split.core.model.*' --info`.

Expected: PASS; corruption never replaces current.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src/main/java/ai/schism/split/core schism-android/app/src/test/java/ai/schism/split/core
git commit -m "feat(android): install versioned OCR models safely"
```

### Task 4: WorkManager download UX and OCR coordinator

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/ocr/OcrAvailability.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/ocr/OcrModelDownloadWorker.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/ocr/OcrCoordinator.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/ocr/OcrCoordinatorTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/receipt/ReceiptScanner.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/itemized/BillScan.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/sms/inbox/InboxViewModel.kt`
- Modify: `schism-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `sealed interface OcrAvailability` states and `OcrCoordinator.prepare(allowCellular: Boolean): Flow<OcrAvailability>`, `recognize(uri): List<Row>`.
- Consumes: `OcrModelStore`, distribution-specific `OcrFeatureInstaller`, and `OcrProvider`.

- [ ] **Step 1: Write failing coordinator state tests**

Test first-use consent shows exact 6,298,800-byte model size plus feature size when available;
module/model progress, Wi-Fi-only wait, cellular opt-in, notification denial, cancel/resume, no space,
hash failure retry, ready offline, and provider load failure rollback.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd schism-android && ./gradlew :app:testDebugUnitTest --tests '*OcrCoordinatorTest'`

Expected: FAIL because coordinator/states do not exist.

- [ ] **Step 3: Implement foreground worker and user flow**

Use unique work `ocr_model_download`, data-sync foreground notification, WorkManager backoff, and
progress fields for bytes/total/stage. Present a first-use sheet with Download, Wi-Fi only, and Not
now. Keep manual bill entry available. `ReceiptScanner` becomes a thin compatibility adapter over
`OcrCoordinator` so existing ViewModels need minimal changes.

- [ ] **Step 4: Run coordinator and existing receipt tests**

Run: `cd schism-android && ./gradlew :app:testDebugUnitTest --tests '*OcrCoordinatorTest' --tests '*Receipt*' --tests '*Bill*'`.

Expected: PASS; existing parsing behavior is unchanged after OCR becomes ready.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(android): add first-use OCR download flow"
```

### Task 5: Play dynamic feature and standalone binding

**Files:**
- Create: `schism-android/ocr-feature/build.gradle.kts`
- Create: `schism-android/ocr-feature/src/main/AndroidManifest.xml`
- Create: `schism-android/ocr-feature/src/main/java/ai/schism/split/ocr/feature/PlayOcrProvider.kt`
- Create: `schism-android/app/src/play/java/ai/schism/split/ocr/PlayOcrFeatureInstaller.kt`
- Create: `schism-android/app/src/standalone/java/ai/schism/split/ocr/StandaloneOcrFeatureInstaller.kt`
- Create: `schism-android/app/src/standalone/assets/models/det/inference.onnx`
- Create: `schism-android/app/src/standalone/assets/models/rec/inference.onnx`
- Create: `schism-android/app/src/standalone/assets/models/rec/inference.yml`
- Create: `schism-android/ppocr-sdk/src/androidTest/assets/models/det/inference.onnx`
- Create: `schism-android/ppocr-sdk/src/androidTest/assets/models/rec/inference.onnx`
- Create: `schism-android/ppocr-sdk/src/androidTest/assets/models/rec/inference.yml`
- Modify: `schism-android/settings.gradle.kts`
- Modify: `schism-android/build.gradle.kts`
- Modify: `schism-android/gradle/libs.versions.toml`
- Modify: `schism-android/app/build.gradle.kts`
- Modify: `schism-android/ppocr-sdk/build.gradle.kts`
- Delete: `schism-android/ppocr-sdk/src/main/assets/models/det/inference.onnx`
- Delete: `schism-android/ppocr-sdk/src/main/assets/models/rec/inference.onnx`
- Delete: `schism-android/ppocr-sdk/src/main/assets/models/rec/inference.yml`

**Interfaces:**
- Produces: `OcrFeatureInstaller.ensureInstalled(): Flow<FeatureInstallState>` and
  `loadProvider(): OcrProvider` for both `play` and `standalone`.
- Consumes: Task 2 contract/implementation and Task 4 coordinator.

- [ ] **Step 1: Add failing distribution dependency assertions**

Add Gradle verification tests/scripts asserting `playRelease` base has no `libonnxruntime.so`,
`libopencv_java4.so`, `com.paddle.ocr`, or `assets/models`; `standaloneRelease` contains all and the
three model hashes match. Add a fake SplitInstall test for install success/failure/cancel.

- [ ] **Step 2: Run distribution checks and confirm failure**

Run: `cd schism-android && ./gradlew :app:assemblePlayDebug :app:assembleStandaloneDebug :app:testPlayDebugUnitTest`.

Expected: FAIL because flavors/feature module do not exist.

- [ ] **Step 3: Implement variant graph and provider loading**

Add distribution dimension with `play` and `standalone`. The dynamic feature uses
`dist:onDemand="true"` and `dist:fusing include="false"`. Play asks SplitInstall for `ocr_feature`,
then reflectively invokes the public no-argument `PlayOcrProvider` constructor only after INSTALLED
and casts to `OcrProvider`. Standalone directly constructs `PaddleOcrProvider` with bundled asset
sources. Move production models with Git history preserved, copy the same verified bytes into the
`ppocr-sdk` instrumentation-test asset source set, and add Play Core only to play sources.

- [ ] **Step 4: Verify packaging and both runtime paths**

Run:

```bash
cd schism-android
./gradlew :app:bundlePlayRelease :app:assembleStandaloneRelease
unzip -l app/build/outputs/apk/standalone/release/*.apk | rg 'inference.onnx|libonnxruntime|libopencv'
```

Expected: Play AAB base excludes OCR payload; standalone APK contains verified payload; fake split
tests pass.

- [ ] **Step 5: Commit**

```bash
git add schism-android
git commit -m "feat(android): deliver OCR on demand for Play"
```

### Task 6: OCR upgrade, rollback, and instrumentation verification

**Files:**
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/ocr/OcrDeliveryInstrumentedTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/ocr/OcrUpgradeTest.kt`
- Modify: `schism-android/app/src/androidTest/java/ai/schism/split/sms/receipt/ReceiptScannerInstrumentedTest.kt`
- Modify: `schism-android/ppocr-sdk/src/androidTest/java/com/paddle/ocr/benchmark/OCRBenchmarkTest.kt`
- Create: `docs/release/v1.3/ocr-delivery.md`

**Interfaces:**
- Produces: verified evidence that the model/download/module state machine works across install,
  restart, offline use, corrupt update, and rollback.
- Consumes: all previous OCR tasks.

- [ ] **Step 1: Add end-to-end delivery tests**

Test standalone first scan without network; play fake-delivery first scan after download; process
restart during `.part`; correct output after offline restart; corrupt new version retains old; delete
and reinstall; engine close/reopen; and a 16-KB emulator scan.

- [ ] **Step 2: Run focused instrumentation and confirm any gaps**

Run: `cd schism-android && ./gradlew :app:connectedStandaloneDebugAndroidTest :ppocr-sdk:connectedDebugAndroidTest`.

Expected before final wiring: at least one new delivery assertion fails; use it to complete wiring,
not to loosen output expectations.

- [ ] **Step 3: Complete lifecycle cleanup and rollback wiring**

Ensure sessions close before deleting a model; serialize engine replacement with the existing mutex;
mark a version current only after one reference inference loads; fall back to previous on typed load
failure; expose delete/retry in Settings without deleting a working version mid-scan.

- [ ] **Step 4: Run full OCR verification and record evidence**

Run: `cd schism-android && ./gradlew :ocr-contract:check :ocr-impl:check :ppocr-sdk:check :app:testStandaloneDebugUnitTest :app:connectedStandaloneDebugAndroidTest :app:bundlePlayRelease :app:assembleStandaloneRelease`.

Expected: PASS. Record artifact paths, exact hashes, feature/base sizes, emulator API/page size, cold
load, and reference inference output in `docs/release/v1.3/ocr-delivery.md`.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/ocr-delivery.md
git commit -m "test(ocr): verify delivery upgrade and rollback"
```
