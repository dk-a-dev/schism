#!/usr/bin/env python3
"""Turn SROIE (ICDAR 2019 task 3) annotations into Schism receipt-engine fixtures.

SROIE ships, per receipt, a line-level OCR file (`data/box/NNN.csv`: 8 quad
coordinates + the transcribed text) and a ground-truth file (`data/key/NNN.json`:
company / date / address / total). Feeding the *annotations* straight into
BillParser isolates parser accuracy from OCR accuracy.

Output is the fixture dialect RealBillFixturesTest already reads: one visual row
per text line, a run of 2+ spaces separating detections into cells. Geometry is
reconstructed from the real box coordinates (x mapped through the receipt's own
median glyph width; rows grouped by y exactly like Geometry.groupIntoRows), so
the column structure the engine sees is the printed one, not an invented one.

    python3 sroie_to_fixtures.py data/sroie/data out/sroie-corpus.txt
    python3 sroie_to_fixtures.py data/sroie/data out/sample.txt --sample 75
"""

import argparse
import json
import pathlib
import re
import statistics
import sys

# ---------------------------------------------------------------- ground truth

MONEY = re.compile(r"-?\d[\d,]*(?:\.\d{1,2})?")
# d/m/y, d-m-y, d.m.y and the month-name spellings SROIE receipts also print.
DATE_NUM = re.compile(r"\b(\d{1,4})\s*[/.-]\s*(\d{1,2})\s*[/.-]\s*(\d{2,4})\b")
MONTHS = "jan feb mar apr may jun jul aug sep oct nov dec".split()
DATE_NAME = re.compile(r"\b(\d{1,2})\s*[-. ]\s*([A-Za-z]{3,9})\s*[-. ,]\s*(\d{2,4})\b")


def total_minor(raw):
    m = MONEY.findall(raw or "")
    if not m:
        return None
    return round(float(m[-1].replace(",", "")) * 100)


def gt_date(raw):
    """(iso, ambiguous) for a SROIE date label, or (None, False).

    SROIE is Malaysian, so day-first is the default reading. A middle field > 12
    can only be a day, so that label was printed month-first and is read as such.
    A label where BOTH fields are <= 12 is genuinely ambiguous and flagged.
    """
    raw = (raw or "").strip()
    m = DATE_NAME.search(raw)
    if m:
        d, name, y = m.group(1), m.group(2)[:3].lower(), m.group(3)
        if name in MONTHS:
            return _iso(y, MONTHS.index(name) + 1, int(d)), False
    m = DATE_NUM.search(raw)
    if not m:
        return None, False
    a, b, c = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if len(m.group(1)) == 4:  # yyyy-mm-dd
        return _iso(a, b, c), False
    if b > 12:  # mm/dd/yyyy — the middle field cannot be a month
        return _iso(c, a, b), False
    return _iso(c, b, a), a <= 12 and b <= 12 and a != b


def _iso(y, m, d):
    y = int(y)
    if y < 100:
        y += 2000
    if not (1 <= int(m) <= 12 and 1 <= int(d) <= 31):
        return None
    return f"{y:04d}-{int(m):02d}-{int(d):02d}"


# ------------------------------------------------------------------ conversion


def read_boxes(path):
    """[(x_left, x_right, y_top, y_bottom, text)] — text may itself contain commas."""
    out = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        parts = line.split(",", 8)
        if len(parts) < 9:
            continue
        try:
            c = [int(float(p)) for p in parts[:8]]
        except ValueError:
            continue
        text = " ".join(parts[8].split())  # internal runs of space would fake a column gap
        if not text:
            continue
        xs, ys = c[0::2], c[1::2]
        out.append((min(xs), max(xs), min(ys), max(ys), text))
    return out


def group_rows(boxes):
    """Same rule as Geometry.groupIntoRows: same row while within 0.6 * line height."""
    heights = [b[3] - b[2] for b in boxes if b[3] > b[2]]
    thresh = max(int(statistics.median(heights) * 0.6), 1) if heights else 1
    rows = []
    for b in sorted(boxes, key=lambda b: (b[2] + b[3]) / 2):
        yc = (b[2] + b[3]) / 2
        if rows and abs(yc - statistics.fmean((x[2] + x[3]) / 2 for x in rows[-1])) <= thresh:
            rows[-1].append(b)
        else:
            rows.append([b])
    return [sorted(r, key=lambda b: b[0]) for r in rows]


def char_width(boxes):
    w = [(b[1] - b[0]) / len(b[4]) for b in boxes if b[4]]
    return max(statistics.median(w), 1.0) if w else 1.0


def merge_words(row, cw):
    """Glue WORD boxes separated by less than ~1.5 glyph widths into one cell.

    Only needed for word-level sources (CORD): a line detector emits "SPGTHY BOLOGNASE" as one
    detection, and leaving the words apart would fake a column gap between every pair of them.
    """
    out = []
    for b in row:
        if out and b[0] - out[-1][1] < cw * 1.5:
            p = out[-1]
            out[-1] = (p[0], max(p[1], b[1]), min(p[2], b[2]), max(p[3], b[3]), p[4] + " " + b[4])
        else:
            out.append(b)
    return out


def to_fixture(boxes, word_level=False):
    """Render boxes as fixture text: real x mapped to character columns, 2+ spaces = column gap."""
    cw = char_width(boxes)
    lines = []
    for row in group_rows(boxes):
        if word_level:
            row = merge_words(row, cw)
        line = ""
        for b in row:
            col = max(round(b[0] / cw), len(line) + 2 if line else 0)
            line += " " * (col - len(line)) + b[4]
        lines.append(line)
    return lines


# ------------------------------------------------------------------------ CORD

# Indonesian receipts group thousands with "." as often as with "," ("48.000" is forty-eight
# thousand rupiah, not forty-eight point nought). Every separator group of exactly 3 digits is a
# thousands group; only a trailing 1-2 digit group is a real fraction.
GROUPED = re.compile(r"^\d{1,3}(?:[.,]\d{3})+$")


def cord_total_minor(raw):
    raw = (raw or "").strip()
    if GROUPED.match(raw):
        return int(re.sub(r"[.,]", "", raw)) * 100
    return total_minor(raw)


def read_cord(samples_path):
    """[(id, totalMinor, boxes)] from a FiftyOne `samples.json` export of CORD."""
    data = json.loads(samples_path.read_text(encoding="utf-8"))
    out = []
    for s in data["samples"]:
        total = cord_total_minor(s.get("total_price"))
        if total is None or total <= 0:
            continue
        w = s.get("metadata", {}).get("width") or 1000
        h = s.get("metadata", {}).get("height") or 1000
        boxes = []
        for d in s.get("detections", {}).get("detections", []):
            text = " ".join((d.get("text") or "").split())
            if not text:
                continue
            bx, by, bw, bh = d["bounding_box"]
            boxes.append((round(bx * w), round((bx + bw) * w), round(by * h), round((by + bh) * h), text))
        if boxes:
            out.append((pathlib.Path(s["filepath"]).stem, total, boxes))
    return out


# ---------------------------------------------------------------------- driver


def signature(boxes, key):
    """Coarse variety bucket, so a sample spans layouts rather than clustering on one."""
    text = " ".join(b[4] for b in boxes)
    sep = next((c for c in "/.-" if re.search(rf"\d\s*\{c}\s*\d", key.get("date", ""))), "?")
    return (sep, "RM" in text.upper(), min(len(boxes) // 25, 3))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=pathlib.Path, help="SROIE data dir (box/ + key/), or CORD samples.json")
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("--sample", type=int, default=0, help="emit only N receipts, spread over layouts")
    ap.add_argument("--source", choices=("sroie", "cord"), default="sroie")
    args = ap.parse_args()

    if args.source == "cord":
        records = [
            {
                "id": rid, "total": total, "date": "-", "ambiguous": False, "company": "-",
                "sig": ("cord", False, min(len(boxes) // 60, 3)),
                "lines": to_fixture(boxes, word_level=True),
            }
            for rid, total, boxes in read_cord(args.root)
        ]
        return write(args, records, 0)

    box_dir, key_dir = args.root / "box", args.root / "key"
    if not box_dir.is_dir() or not key_dir.is_dir():
        sys.exit(f"expected {box_dir} and {key_dir} — see README.md for the download step")

    records, ambiguous = [], 0
    for box_path in sorted(box_dir.glob("*.csv")):
        key_path = key_dir / (box_path.stem + ".json")
        if not key_path.exists():
            continue
        key = json.loads(key_path.read_text(encoding="utf-8", errors="replace"))
        boxes = read_boxes(box_path)
        if not boxes:
            continue
        total = total_minor(key.get("total"))
        if total is None:
            continue
        iso, amb = gt_date(key.get("date"))
        ambiguous += amb
        records.append(
            {
                "id": box_path.stem,
                "total": total,
                "date": iso or "-",
                "ambiguous": amb,
                "company": " ".join((key.get("company") or "").split()),
                "sig": signature(boxes, key),
                "lines": to_fixture(boxes),
            }
        )

    return write(args, records, ambiguous)


def write(args, records, ambiguous):
    if args.sample:
        buckets = {}
        for r in records:
            buckets.setdefault(r["sig"], []).append(r)
        picked, i = [], 0
        while len(picked) < args.sample and any(buckets.values()):
            for sig in sorted(buckets):
                if buckets[sig] and len(picked) < args.sample:
                    picked.append(buckets[sig].pop(i % len(buckets[sig])))
            i += 1
        records = sorted(picked, key=lambda r: r["id"])

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as f:
        f.write("# SROIE (ICDAR 2019 task 3) annotations as receipt-engine fixtures.\n")
        f.write("# Generated by tools/receipt-eval/sroie_to_fixtures.py — do not hand-edit.\n")
        f.write("# '>>> id<TAB>totalMinor<TAB>isoDate<TAB>company' then the receipt's rows.\n")
        f.write("# A row's cells are separated by runs of 2+ spaces (see RealBillFixturesTest).\n")
        f.write("# 'AMBIGUOUS' after the company marks a d/m vs m/d date label that cannot be resolved.\n")
        for r in records:
            flag = "\tAMBIGUOUS" if r["ambiguous"] else ""
            f.write(f">>> {r['id']}\t{r['total']}\t{r['date']}\t{r['company']}{flag}\n")
            f.write("\n".join(r["lines"]) + "\n")

    print(f"{len(records)} receipts -> {args.out} ({ambiguous} ambiguous date labels in full set)")


if __name__ == "__main__":
    main()
