# Public-dataset benchmark for the receipt engine

Measures `BillParser` against two public receipt corpora, in two different locales:

| Dataset | Receipts | Locale | Ground truth | Annotation |
|---|---|---|---|---|
| **SROIE** (ICDAR 2019, task 3) | 626 | Malaysian — `RM`, `dd/mm/yyyy`, 6% GST summary blocks | company, date, address, total | line-level boxes + text |
| **CORD** (via `Voxel51/consolidated_receipt_dataset`) | 800 | Indonesian — dot-grouped thousands (`48.000`) | total (+ subtotal, tax, service) | word-level boxes + text |

Two locales on purpose: a rule that only lifts one of them is not a general rule.

Feeding the **annotations** into the parser — rather than our own OCR output — measures parser
accuracy in isolation from OCR accuracy, which is the point: an OCR miss and a parsing miss need
different fixes, and this harness only reports the second.

Nothing here runs at `./gradlew test` time. The committed samples live in
`app/src/test/resources/receipt-engine/{sroie,cord}-sample.txt`; the datasets are not committed.
Neither download needs credentials (the Kaggle SROIE mirror does, so this uses a GitHub mirror of
the same `box/` + `key/` files).

## Reproduce

```sh
cd tools/receipt-eval

# 1a. SROIE — annotations only, no images, ~6 MB
git clone --filter=blob:none --no-checkout --depth 1 \
    https://github.com/zzzDavid/ICDAR-2019-SROIE.git data/sroie
(cd data/sroie && git sparse-checkout set --no-cone data/box data/key && git checkout)

# 1b. CORD — one 5.7 MB annotation file, no images
curl -L -o data/cord-samples.json \
  https://huggingface.co/datasets/Voxel51/consolidated_receipt_dataset/resolve/main/samples.json

# 2. Convert to fixtures
python3 sroie_to_fixtures.py data/sroie/data     out/sroie-corpus.txt
python3 sroie_to_fixtures.py data/sroie/data     out/sroie-sample.txt --sample 75
python3 sroie_to_fixtures.py data/cord-samples.json out/cord-corpus.txt --source cord
python3 sroie_to_fixtures.py data/cord-samples.json out/cord-sample.txt --source cord --sample 25

# 3. Score a full corpus (opt-in; not part of ./gradlew test)
cd ../..
RECEIPT_CORPUS=$PWD/tools/receipt-eval/out/sroie-corpus.txt \
    ./gradlew :app:testDebugUnitTest --tests '*SroieCorpusRunner*' --rerun-tasks -i
```

`SROIE_EXAMPLES=200` raises the per-bucket example cap when investigating a failure class.
`python3 show.py out/sroie-corpus.txt 021 144` prints individual receipts.

## Results

Whole-corpus, annotations in / parsed draft out:

| | SROIE total | SROIE date | SROIE merchant | CORD total |
|---|---|---|---|---|
| before | 56.6% (354/625) | 78.7% (485/616) | 78.1% (488/625) | 63.8% (486/762) |
| after | **87.5%** (547/625) | **96.6%** (595/616) | **87.2%** (545/625) | **87.4%** (666/762) |

Currency detection on SROIE went 0% → 83.2% (the ringgit was not a currency the parser knew).

## Fixture format

Same dialect `RealBillFixturesTest` already uses: one visual row per text line, a run of **2+ spaces**
separating detections into cells (which is how the shipped PP-OCR detector splits a printed line).

Geometry is reconstructed from the real annotation boxes, never invented: rows are grouped by y with
the same 0.6 × line-height rule as `Geometry.groupIntoRows`, and each detection's x is mapped through
the receipt's own median glyph width, so column structure is the printed one. Reading order is y then
x. Each receipt is preceded by

```
>>> <id>\t<totalMinor>\t<isoDate>\t<company>[\tAMBIGUOUS]
```

`AMBIGUOUS` marks a date label where both leading fields are ≤ 12, so d/m vs m/d cannot be resolved
from the label alone.

## Known limits of this dataset

- **Locale.** SROIE is Malaysian: `RM`, `dd/mm/yyyy`, and a 6% GST summary block on most receipts.
  Some receipts print a bare `$` for ringgit.
- **Ground-truth definition.** `company` is the tax-invoice **legal entity**, which on a franchise
  receipt ("DOMMAL FOOD SERVICES SDN BHD") is printed small in the footer while the trading name
  ("DOMINO'S PIZZA") is the large line at the top. The scorer counts that disagreement separately
  from a genuine wrong-line pick.
- **Line-level boxes.** SROIE annotates whole printed lines, so a trailing tax code stays glued to
  the amount in one box (`2.20 ZRL`). Our own detector splits tighter; a handful of receipts are
  unparseable purely because of that merge.
- **Label noise.** A small number of totals disagree with what the receipt prints. The scorer buckets
  those rather than passing them.

## Datasets considered and not used

- `dhiaznaidi/receiptdatasetssd300v2` (Kaggle) — needs an API token (`~/.kaggle/kaggle.json`); the
  public API returns 404 unauthenticated. It is an SSD300 **detection** set: bounding boxes with no
  transcriptions and no key-value ground truth, so there is nothing for a *text* parser to consume
  even with credentials. Skipped deliberately.
