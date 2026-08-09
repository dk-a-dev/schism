#!/usr/bin/env python3
"""Export the Schism Play icon, feature graphic and promo sizes from the brand source.

Everything is drawn from the shipped split-coin geometry
(`schism-android/app/src/main/res/drawable/ic_launcher_*.xml`) and the brand palette — no
photographic or generated raster input, no screenshot. Re-run after any brand change:

    python3 tools/store/export_assets.py

It rewrites every PNG below plus `store/play/assets/source/generation-log.md` (which carries the
SHA-256 of each output).
"""

import hashlib
import subprocess
import sys
from datetime import date
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "store" / "play" / "assets"
PROMO = ROOT / "store" / "promo"
SOURCE = ASSETS / "source"

GREEN = "#14874F"
MINT = "#B6ECCE"
CREAM = "#FBFAF4"
CHARCOAL = "#1A1A16"
AMBER = "#9A7A2E"
# Launcher field colours, copied from ic_launcher_background.xml so the store icon is the app icon.
ICON_FIELD = "#128049"
ICON_HALO = "#16925A"

HEADLINE = "Split expenses. Keep the context."
SUBLINE = "Private receipt and SMS understanding, on your phone."
FOOTER = "Schism"

SS = 4  # supersample factor; PIL has no antialiased shape drawing
HALO = 42 / 26  # launcher field radius / half-disc radius

# (path, face index) in preference order: Demi Bold / Medium equivalents.
FONTS = {
    "bold": [("/System/Library/Fonts/Avenir Next.ttc", 2), ("/System/Library/Fonts/HelveticaNeue.ttc", 1),
             ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 0)],
    "regular": [("/System/Library/Fonts/Avenir Next.ttc", 5), ("/System/Library/Fonts/HelveticaNeue.ttc", 0),
                ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0)],
}


def font(weight, size):
    for path, index in FONTS[weight]:
        if Path(path).exists():
            return ImageFont.truetype(path, size, index=index)
    raise SystemExit(f"no usable {weight} font found; add one to FONTS in {__file__}")


def draw_coin(draw, cx, cy, radius, halo=None):
    """The split coin: two half-discs sheared apart, exactly as in ic_launcher_foreground.xml.

    SVG units are the launcher's 108x108 viewport; `radius` is the coin's 26-unit half-disc radius.
    """
    unit = radius / 26.0
    if halo:
        r = 42 * unit
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=halo)
    # Left half-disc, centre (50,52) in launcher units -> (-4,-4) units from the 54,54 viewport centre.
    lx, ly = cx - 4 * unit, cy - 4 * unit
    draw.pieslice([lx - radius, ly - radius, lx + radius, ly + radius], 90, 270, fill=MINT)
    # Right half-disc, centre (58,56) -> (+4,+4).
    rx, ry = cx + 4 * unit, cy + 4 * unit
    draw.pieslice([rx - radius, ry - radius, rx + radius, ry + radius], 270, 90, fill=CREAM)


def fit(text, weight, max_width, start_size):
    """Largest size at or below start_size whose rendered width fits max_width."""
    size = start_size
    while size > 8:
        f = font(weight, size)
        if f.getlength(text) <= max_width:
            return f
        size -= 2
    return font(weight, 8)


def canvas(width, height, background):
    image = Image.new("RGB", (width * SS, height * SS), background)
    return image, ImageDraw.Draw(image)


def finish(image, width, height, path):
    image.resize((width, height), Image.LANCZOS).save(path, "PNG", optimize=True)
    return path


def export_icon(path, size=512):
    image, draw = canvas(size, size, ICON_FIELD)
    draw_coin(draw, size * SS / 2, size * SS / 2, radius=size * SS * 26 / 108, halo=ICON_HALO)
    return finish(image, size, size, path)


def export_wide(path, width, height):
    """Mint panel + seam + text: used for the feature graphic and the landscape promo."""
    image, draw = canvas(width, height, CREAM)
    w, h = width * SS, height * SS
    panel = int(w * 0.33)
    draw.rectangle([0, 0, panel, h], fill=MINT)
    draw.rectangle([panel, 0, panel + max(2, int(w * 0.004)), h], fill=GREEN)
    # The mark needs its green field to read against the mint panel; HALO is that field's radius.
    outer = min(panel * 0.40, h * 0.34)
    draw_coin(draw, panel * 0.5, h * 0.5, radius=outer / HALO, halo=GREEN)

    left = panel + int(w * 0.06)
    text_width = int(w * 0.94) - left
    head = fit(HEADLINE, "bold", text_width, int(h * 0.115))
    sub_size = max(10, int(head.size * 0.5))
    sub = fit(SUBLINE, "regular", text_width, sub_size)
    block = head.size * 1.25 + sub.size * 1.6
    top = (h - block) / 2
    draw.text((left, top), HEADLINE, font=head, fill=CHARCOAL)
    draw.text((left, top + head.size * 1.45), SUBLINE, font=sub, fill=AMBER)
    return finish(image, width, height, path)


def export_tall(path, width, height):
    """Centred stack: used for the square, portrait and story promos."""
    image, draw = canvas(width, height, CREAM)
    w, h = width * SS, height * SS
    band = 0.5 if height / width > 1.5 else 0.42  # stories need the mark clear of the app chrome
    seam = int(h * band)
    draw.rectangle([0, 0, w, seam], fill=MINT)
    draw.rectangle([0, seam, w, seam + max(2, int(h * 0.004))], fill=GREEN)
    outer = min(w * 0.22, h * band * 0.36)
    draw_coin(draw, w * 0.5, seam * 0.5, radius=outer / HALO, halo=GREEN)

    margin = int(w * 0.10)
    text_width = w - 2 * margin
    head = fit(HEADLINE, "bold", text_width, int(w * 0.085))
    sub = fit(SUBLINE, "regular", text_width, max(10, int(head.size * 0.46)))
    foot = font("regular", max(10, int(head.size * 0.38)))
    top = seam + (h - seam - (head.size * 1.5 + sub.size * 1.8)) * 0.34
    draw.text((w / 2, top), HEADLINE, font=head, fill=CHARCOAL, anchor="ma")
    draw.text((w / 2, top + head.size * 1.6), SUBLINE, font=sub, fill=AMBER, anchor="ma")
    draw.text((w / 2, h - int(h * 0.06)), FOOTER, font=foot, fill=GREEN, anchor="ma")
    return finish(image, width, height, path)


def git_commit():
    try:
        return subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


LOG_HEADER = """# Asset generation log

Generated by `tools/store/export_assets.py` on {today} from commit `{commit}`.

## Sources

- `store/play/assets/source/split-coin.svg` — the app mark, reconstructed from
  `schism-android/app/src/main/res/drawable/ic_launcher_foreground.xml` and
  `ic_launcher_background.xml`. Identical geometry: half-discs of radius 26 centred at (50,52) and
  (58,56) in the launcher's 108x108 viewport.
- `store/play/assets/source/feature-layout.svg` — the layout reference for the feature graphic.
- Palette: green `#14874F`, mint `#B6ECCE`, cream `#FBFAF4`, charcoal `#1A1A16`, amber `#9A7A2E`.
  The icon uses the launcher's own field colours `#128049` / `#16925A` so the store icon is the
  shipped app icon.
- Type: Avenir Next Demi Bold / Medium (macOS), falling back to Helvetica Neue then DejaVu Sans.

## Copy used

- Headline: "{headline}"
- Subline: "{subline}"

Both are factual: receipt OCR and opted-in SMS parsing run on the device
(`ReceiptScanner`, `SmsScanWorker`), and no photo, OCR text or message is uploaded.

## Deliberate omissions

- **No generated raster background.** The plan called for one imagegen paper-ledger image at
  `source/generated-paper-ledger.png`. This environment has no image-generation tool, so the
  layouts are built from vector geometry and flat brand colour instead. If the owner later wants the
  photographic version, generate it with the plan's prompt, drop it at that path, record the tool
  and date here, and composite it behind the text.
- **No screenshots.** Plan Task 4 needs an emulator; `store/play/assets/phone/` is empty and no
  export here imitates an app screen.
- **No SVG rasteriser.** The `.svg` files are editable design source; `export_assets.py` redraws the
  same geometry with Pillow because rasterising SVG would need a new dependency. Change both when
  the mark changes.

## Outputs

| File | Pixels | SHA-256 |
| --- | --- | --- |
"""


def main():
    for directory in (ASSETS, SOURCE, PROMO, ASSETS / "phone"):
        directory.mkdir(parents=True, exist_ok=True)

    outputs = [
        export_icon(ASSETS / "icon-512.png"),
        export_wide(ASSETS / "feature-1024x500.png", 1024, 500),
        export_tall(PROMO / "square-1080.png", 1080, 1080),
        export_tall(PROMO / "portrait-1080x1350.png", 1080, 1350),
        export_wide(PROMO / "landscape-1200x628.png", 1200, 628),
        export_tall(PROMO / "story-1080x1920.png", 1080, 1920),
    ]

    rows = []
    for path in outputs:
        data = path.read_bytes()
        with Image.open(path) as image:
            size = f"{image.width}x{image.height}"
        rows.append(f"| `{path.relative_to(ROOT)}` | {size} | `{hashlib.sha256(data).hexdigest()}` |")

    log = LOG_HEADER.format(today=date.today().isoformat(), commit=git_commit(),
                            headline=HEADLINE, subline=SUBLINE) + "\n".join(rows) + "\n"
    (SOURCE / "generation-log.md").write_text(log, encoding="utf-8")

    for path in outputs:
        print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
