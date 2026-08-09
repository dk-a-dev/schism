#!/usr/bin/env python3
"""Tests for validate_copy.py, run against a copy of the real store/play package."""

import shutil
import tempfile
import unittest
from pathlib import Path

import validate_copy

REAL = Path(__file__).resolve().parents[2] / "store" / "play"


class ValidateCopyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.root = self.tmp / "play"
        shutil.copytree(REAL, self.root)
        self.addCleanup(shutil.rmtree, self.tmp)

    def errors(self):
        return validate_copy.check(self.root)

    def edit(self, name, old, new):
        path = self.root / name
        text = path.read_text()
        self.assertIn(old, text, f"fixture drift: {old!r} not in {name}")
        path.write_text(text.replace(old, new))

    def assertFails(self, needle):
        errors = self.errors()
        self.assertTrue(
            any(needle in error for error in errors),
            f"expected an error containing {needle!r}, got {errors}",
        )

    def test_real_package_passes(self):
        self.assertEqual([], self.errors())

    def test_missing_file(self):
        (self.root / "data-safety.md").unlink()
        self.assertFails("missing required file: data-safety.md")

    def test_app_name_too_long(self):
        self.edit("en-US/listing.md", "Schism: Split Expenses", "S" * 31)
        self.assertFails("'App name' is 31 chars")

    def test_short_description_too_long(self):
        self.edit(
            "en-US/listing.md",
            "Scan bills, track spending and split shared expenses privately.",
            "x" * 81,
        )
        self.assertFails("'Short description' is 81 chars")

    def test_full_description_too_long(self):
        self.edit("en-US/listing.md", "SPLIT A BILL LINE BY LINE", "y" * 4001)
        self.assertFails("'Full description' is")

    def test_release_notes_too_long(self):
        (self.root / "en-US/release-notes/10300.txt").write_text("z" * 501)
        self.assertFails("limit 500")

    def test_prohibited_claim(self):
        self.edit("en-US/listing.md", "SPLIT A BILL LINE BY LINE", "The best expense app, supports all banks")
        self.assertFails("prohibited claim")

    def test_policy_url_must_be_https(self):
        self.edit("en-US/listing.md", "- Terms: https://", "- Terms: http://")
        self.assertFails("'Terms' URL is not https")

    def test_missing_deletion_url(self):
        self.edit("en-US/listing.md", "- Account deletion: ", "- Deletion someday: ")
        self.assertFails("no 'Account deletion' entry")

    def test_missing_support_email(self):
        self.edit("en-US/listing.md", "- Support email: dev.keshwani345@gmail.com", "- Support email: TBD")
        self.assertFails("no 'Support email' entry")

    def test_sms_declaration_must_name_both_permissions(self):
        self.edit("sms-permission-declaration.md", "android.permission.RECEIVE_SMS", "SMS receiving")
        self.assertFails("must state 'android.permission.RECEIVE_SMS'")

    def test_data_safety_must_cover_billing_and_ads(self):
        self.edit("data-safety.md", "Google Mobile Ads", "an ad SDK")
        self.assertFails("must state 'Google Mobile Ads'")

    def test_data_safety_must_disclose_the_model_host(self):
        self.edit("data-safety.md", "huggingface.co", "a model host")
        self.assertFails("must state 'huggingface.co'")

    def test_data_safety_must_scope_the_github_check(self):
        self.edit("data-safety.md", "com.android.vending", "the Play installer")
        self.assertFails("must state 'com.android.vending'")

    def test_deletion_vs_subscription_distinction_required(self):
        self.edit(
            "reviewer-instructions.md",
            "Cancelling the subscription does not delete the Schism account",
            "Cancelling handles everything",
        )
        self.assertFails("Cancelling the subscription does not delete")

    def test_committed_credential_is_rejected(self):
        (self.root / "reviewer-instructions.md").write_text("Demo login\npassword: hunter2hunter2\n")
        self.assertFails("committed literal password")


if __name__ == "__main__":
    unittest.main()
