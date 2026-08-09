#!/usr/bin/env python3
"""Checks the exported store assets: dimensions, opacity, weight, provenance, brand drift."""

import hashlib
import unittest
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "store" / "play" / "assets"
PROMO = ROOT / "store" / "promo"
LOG = ASSETS / "source" / "generation-log.md"

EXPECTED = {
    ASSETS / "icon-512.png": (512, 512),
    ASSETS / "feature-1024x500.png": (1024, 500),
    PROMO / "square-1080.png": (1080, 1080),
    PROMO / "portrait-1080x1350.png": (1080, 1350),
    PROMO / "landscape-1200x628.png": (1200, 628),
    PROMO / "story-1080x1920.png": (1080, 1920),
}


class AssetTest(unittest.TestCase):
    def test_dimensions_and_opacity(self):
        for path, size in EXPECTED.items():
            with self.subTest(path=path.name):
                self.assertTrue(path.is_file(), f"missing {path}")
                with Image.open(path) as image:
                    self.assertEqual("PNG", image.format)
                    self.assertEqual(size, image.size)
                    # RGB with no alpha channel: Play rejects transparent icons/feature graphics.
                    self.assertEqual("RGB", image.mode)
                    self.assertNotIn("transparency", image.info)

    def test_play_icon_under_one_mebibyte(self):
        self.assertLess((ASSETS / "icon-512.png").stat().st_size, 1024 * 1024)

    def test_icon_content_fills_the_safe_zone(self):
        """The mark must occupy the centre of the icon, not float in a corner."""
        with Image.open(ASSETS / "icon-512.png") as image:
            field = image.getpixel((4, 4))
            centre = image.crop((512 // 6, 512 // 6, 512 * 5 // 6, 512 * 5 // 6))
            different = sum(n for n, colour in centre.getcolors(maxcolors=1 << 20) if colour != field)
        self.assertGreater(different / (centre.width * centre.height), 0.5)

    def test_generation_log_records_every_output(self):
        self.assertTrue(LOG.is_file(), "run tools/store/export_assets.py")
        log = LOG.read_text()
        for path in EXPECTED:
            with self.subTest(path=path.name):
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                self.assertIn(str(path.relative_to(ROOT)), log)
                self.assertIn(digest, log, f"{path.name} changed without re-running export_assets.py")

    def test_generation_log_records_the_omitted_raster(self):
        self.assertIn("generated-paper-ledger.png", LOG.read_text())
        self.assertFalse((ASSETS / "source" / "generated-paper-ledger.png").exists())

    def test_source_mark_matches_the_shipped_launcher(self):
        launcher = (ROOT / "schism-android/app/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
        svg = (ASSETS / "source" / "split-coin.svg").read_text()
        for launcher_path, svg_path in (("M50,26", "M50 26"), ("M58,30", "M58 30")):
            self.assertIn(launcher_path, launcher, "launcher mark changed")
            self.assertIn(svg_path, svg, "store mark drifted from the launcher mark")

    def test_any_captured_screenshot_is_phone_sized(self):
        for shot in sorted((ASSETS / "phone").glob("*.png")):
            with self.subTest(shot=shot.name), Image.open(shot) as image:
                self.assertEqual((1080, 1920), image.size)


if __name__ == "__main__":
    unittest.main()
