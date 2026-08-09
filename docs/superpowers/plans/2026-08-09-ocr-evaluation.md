# OCR 100-Receipt Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a reproducible Android end-to-end accuracy, latency, memory, and parser report on 100 licensed public receipts plus deterministic Indian-format synthetic fixtures.

**Architecture:** A pinned downloader extracts the complete 100-image CORD v2 test split outside Git and emits normalized annotations/sample IDs. A parameterized Android instrumentation runner reads those files from app external storage and writes predictions/timings. A deterministic scorer creates machine-readable metrics and a reviewed Markdown failure report.

**Tech Stack:** Python 3.12, PyArrow, Pillow, Android instrumentation, Kotlin/JUnit, adb, PP-OCRv6 Tiny, CORD v2 CC BY 4.0.

## Global Constraints

- CORD source revision is `c204ed859c6e7413d5d806a7e02e947f6557e470`.
- Test parquet is `data/test-00000-of-00001-9c204eb3f4e11791.parquet`, 100 rows, SHA-256 `51c65f1788faff392abe2a0b55b023eb23e9be551c509138eaa3a832514224e7`.
- Dataset images/parquet never enter Git; only sample IDs, annotations needed for metrics, license, scripts, and aggregate/failure reports are committed.
- OCR runs through the Android release implementation, not desktop ONNX.
- No metric is silently changed by rewriting gold output; threshold changes require a committed explanation.

---

### Task 1: Pinned CORD v2 fetch and extraction

**Files:**
- Create: `tools/ocr-benchmark/requirements.txt`
- Create: `tools/ocr-benchmark/fetch_cord.py`
- Create: `tools/ocr-benchmark/test_fetch_cord.py`
- Create: `tools/ocr-benchmark/LICENSE-CORD-CC-BY-4.0.txt`
- Create: `tools/ocr-benchmark/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `.cache/ocr-benchmark/cord-v2-test/images/000.png..099.png`, `ground-truth.jsonl`, and `manifest.json` with source revision/file hash/per-image hashes.
- Consumes: revision-pinned Hugging Face parquet URL and its exact SHA-256.

- [ ] **Step 1: Write failing downloader unit tests**

Use a tiny generated parquet fixture to assert HTTPS-only source, streaming SHA verification before
parse, exactly 100 production rows, deterministic zero-padded image names, preserved `image_id`,
normalized `valid_line` text/boxes and `gt_parse`, atomic output directory, and refusal to reuse a
cache whose manifest hash differs.

- [ ] **Step 2: Run tests and verify failure**

Run: `python3 -m venv .venv-ocr && .venv-ocr/bin/pip install -r tools/ocr-benchmark/requirements.txt && .venv-ocr/bin/python -m unittest tools/ocr-benchmark/test_fetch_cord.py`

Expected: FAIL because fetch/extraction code does not exist. Pin requirements to `pyarrow==21.0.0`,
`Pillow==11.3.0`, and `requests==2.32.4`.

- [ ] **Step 3: Implement verified streaming extraction**

Download
`https://huggingface.co/datasets/naver-clova-ix/cord-v2/resolve/c204ed859c6e7413d5d806a7e02e947f6557e470/data/test-00000-of-00001-9c204eb3f4e11791.parquet`
into a temporary file, verify the required hash, decode rows with PyArrow/Pillow, normalize images to
PNG without resizing, and promote output atomically. Copy the full CC BY 4.0 text and document NAVER
CLOVA attribution plus the original CORD repository/paper.

- [ ] **Step 4: Fetch and validate the actual 100-row corpus**

Run: `.venv-ocr/bin/python tools/ocr-benchmark/fetch_cord.py --output .cache/ocr-benchmark/cord-v2-test && .venv-ocr/bin/python tools/ocr-benchmark/fetch_cord.py --output .cache/ocr-benchmark/cord-v2-test --verify-only`.

Expected: both commands pass, manifest reports 100 unique images and the pinned parquet hash.

- [ ] **Step 5: Commit scripts and attribution only**

```bash
git add .gitignore tools/ocr-benchmark
git commit -m "test(ocr): add pinned CORD receipt corpus fetcher"
```

### Task 2: Parameterized Android corpus runner

**Files:**
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/ocr/ReceiptCorpusInstrumentedTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/ocr/CorpusResult.kt`
- Create: `tools/ocr-benchmark/run_android.sh`
- Create: `tools/ocr-benchmark/test_run_contract.py`
- Modify: `schism-android/app/build.gradle.kts`

**Interfaces:**
- Produces: `.cache/ocr-benchmark/results/api36-16kb-${GIT_SHA}/predictions.jsonl` containing sample ID,
  ordered lines/geometry/confidence, OCR timings, parser draft, Java heap delta, and PSS before/peak/after.
- Consumes: Task 1 image directory pushed to `getExternalFilesDir("ocr-benchmark")` and standalone release OCR provider.

- [ ] **Step 1: Write failing runner-contract tests**

Assert the shell script checks one authorized device, installs the standalone benchmark APK, clears
only the app benchmark directory, pushes all 100 images/ground truth, invokes instrumentation with
`corpusDir`, pulls results, and rejects missing/duplicate sample IDs. Add a single-image instrumentation
test that asserts every JSON field and no receipt text is logged to logcat.

- [ ] **Step 2: Run contract and single-image tests**

Run: `.venv-ocr/bin/python -m unittest tools/ocr-benchmark/test_run_contract.py && cd schism-android && ./gradlew :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.schism.split.ocr.ReceiptCorpusInstrumentedTest`.

Expected: FAIL because runner/result types do not exist.

- [ ] **Step 3: Implement sequential bounded corpus execution**

Warm the engine once, then process sample IDs in lexical order with a 30-second per-image coroutine
timeout. Recycle bitmaps through the production provider; force a short GC settle only between
measurements; record `Debug.getPss()` and runtime heap without claiming device-global peak RAM. Write
one fsynced JSON line after each sample so a crash preserves prior evidence; report failure type per
sample and continue unless the process dies.

- [ ] **Step 4: Run all 100 receipts on the 16-KB emulator**

Run: `tools/ocr-benchmark/run_android.sh .cache/ocr-benchmark/cord-v2-test .cache/ocr-benchmark/results`.

Expected: exactly 100 result rows, no process crash/OOM, device/API/page-size/model/app commit metadata
present, and any per-image timeout/error explicit.

- [ ] **Step 5: Commit runner code**

```bash
git add schism-android/app/src/androidTest tools/ocr-benchmark
git commit -m "test(ocr): run receipt corpora on Android"
```

### Task 3: Deterministic OCR/parser scoring

**Files:**
- Create: `tools/ocr-benchmark/score.py`
- Create: `tools/ocr-benchmark/test_score.py`
- Create: `tools/ocr-benchmark/schema/result.schema.json`
- Create: `tools/ocr-benchmark/schema/report.schema.json`
- Create: `docs/release/v1.3/ocr-benchmark.json`
- Create: `docs/release/v1.3/ocr-benchmark.md`

**Interfaces:**
- Produces: corpus aggregate and per-sample normalized CER/WER, line recall, item-name/amount precision/recall/F1, total/tax exact match, arithmetic verification, latency percentiles, and memory summary.
- Consumes: CORD `valid_line`/`gt_parse` plus Android predictions.

- [ ] **Step 1: Write exact scorer golden tests**

Cover Unicode NFKC/case/whitespace normalization, Levenshtein CER/WER, punctuation-only/empty lines,
box-independent reading order, comma/dot thousands separators, item matching by normalized name plus
amount, missing/extra items, totals/taxes, percentile interpolation, timeout/error denominators, and
JSON schema validation. Use hand-calculated expected fractions.

- [ ] **Step 2: Run scorer tests and confirm failure**

Run: `.venv-ocr/bin/python -m unittest tools/ocr-benchmark/test_score.py`.

Expected: FAIL because scorer does not exist.

- [ ] **Step 3: Implement scorer and failure taxonomy**

Emit stable sorted JSON and Markdown with environment, hashes, success count, OCR/parser tables,
latency p50/p90/p95/max, PSS summary, and sample links grouped as detection miss, recognition error,
reading-order error, parser item/total/tax error, timeout, or runtime failure. Limit committed failure
examples to sample IDs and normalized snippets already covered by CC BY; do not commit the images.

- [ ] **Step 4: Score actual run and establish explicit gate values**

Run: `GIT_SHA=$(git rev-parse --short=12 HEAD) && .venv-ocr/bin/python tools/ocr-benchmark/score.py --ground-truth .cache/ocr-benchmark/cord-v2-test/ground-truth.jsonl --predictions ".cache/ocr-benchmark/results/api36-16kb-${GIT_SHA}/predictions.jsonl" --json docs/release/v1.3/ocr-benchmark.json --markdown docs/release/v1.3/ocr-benchmark.md`.

Expected: report accounts for all 100 samples. Add measured launch gates to the report: 100/100
completed without process crash/OOM plus numeric lower/upper bounds derived from this accepted
baseline; do not invent values before measurement.

- [ ] **Step 5: Commit scorer and measured report**

```bash
git add tools/ocr-benchmark docs/release/v1.3/ocr-benchmark.json docs/release/v1.3/ocr-benchmark.md
git commit -m "test(ocr): publish the 100-receipt baseline"
```

### Task 4: Indian-format synthetic regression supplement

**Files:**
- Create: `tools/ocr-benchmark/generate_indian.py`
- Create: `tools/ocr-benchmark/test_generate_indian.py`
- Create: `tools/ocr-benchmark/assets/NotoSans-Regular.ttf`
- Create: `tools/ocr-benchmark/assets/NOTICE-NOTO.txt`
- Create: `tools/ocr-benchmark/indian-cases.json`
- Modify: `docs/release/v1.3/ocr-benchmark.md`

**Interfaces:**
- Produces: 20 deterministic synthetic INR/GST/UPI/POS receipt PNGs plus exact line/item/total/tax truth under `.cache/ocr-benchmark/indian-synthetic`.
- Consumes: the same Android runner/scorer without mixing synthetic metrics into CORD metrics.

- [ ] **Step 1: Write deterministic generator tests**

Assert byte-identical output for seed `1300`, 20 case IDs, arithmetic-valid subtotal/GST/discount/
round-off/total, diverse one/two-column layouts, multi-quantity and wrapped names, ₹/INR, CGST/SGST/
IGST, UPI IDs redacted as synthetic, rotations ±2 degrees, blur/contrast variants, and Apache-2.0
Noto font attribution.

- [ ] **Step 2: Run tests and confirm failure**

Run: `.venv-ocr/bin/python -m unittest tools/ocr-benchmark/test_generate_indian.py`.

Expected: FAIL because generator does not exist.

- [ ] **Step 3: Implement fixture generator**

Render only data from committed `indian-cases.json`; compute totals using integer paise; use Pillow
with the bundled font; store expected geometry/text/draft JSONL and manifest hashes outside Git. Never
use real merchant phone, GSTIN, UPI, card, order, or address data.

- [ ] **Step 4: Run Android/scorer and append separate results**

Run the generator, `run_android.sh`, and scorer with corpus label `indian-synthetic-v1`. Append its
separate 20-case table/failure taxonomy to the report; CORD aggregate remains unchanged.

- [ ] **Step 5: Commit generator and updated report**

```bash
git add tools/ocr-benchmark docs/release/v1.3/ocr-benchmark.md
git commit -m "test(ocr): add Indian receipt regression fixtures"
```
