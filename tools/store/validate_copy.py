#!/usr/bin/env python3
"""Validate the Play listing package: character limits, required files, truthfulness rules.

Usage: python3 tools/store/validate_copy.py store/play
Exits 0 when the package is submittable, 1 with one line per problem otherwise.
"""

import re
import sys
from pathlib import Path

REQUIRED_FILES = [
    "en-US/listing.md",
    "en-US/release-notes/10300.txt",
    "data-safety.md",
    "sms-permission-declaration.md",
    "financial-features-declaration.md",
    "content-rating.md",
    "reviewer-instructions.md",
    "owner-checklist.md",
]

# Play metadata limits, per listing.md section heading.
LIMITS = {"App name": 30, "Short description": 80, "Full description": 4000}
RELEASE_NOTES_LIMIT = 500

# Claims we are not allowed to make: unverifiable accuracy, coverage, rankings, testimonials.
BANNED = [
    r"\bguarantee\w*\b",
    r"\b100\s*%\s*accur",
    r"\ball (?:banks|indian banks|sms formats)\b",
    r"\bevery bank\b",
    r"\bsupports all\b",
    r"\bnumber one\b",
    r"#1\b",
    r"\bbest (?:app|expense|splitting)\b",
    r"\btop[- ]rated\b",
    r"\baward[- ]winning\b",
    r"\bmillions? of (?:users|downloads)\b",
    r"\btestimonial\b",
    r"\busers say\b",
    r"\b(?:five|5)[- ]star\b",
    r"\bbank[- ]approved\b",
    r"\bpartnered with\b",
]

# Phrases each declaration must actually contain, so a rewrite cannot silently drop a disclosure.
REQUIRED_PHRASES = {
    "sms-permission-declaration.md": [
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "on a fresh install",
        "revoke",
        "not sent to Schism servers",
    ],
    "data-safety.md": [
        "Play Billing",
        "purchase token",
        "Google Mobile Ads",
        "User Messaging Platform",
        "encrypted in transit",
        "Delete account",
        "huggingface.co",  # the model bytes really come from there
        "com.android.vending",  # Play installs make no GitHub update call
    ],
    "reviewer-instructions.md": [
        "Deleting the Schism account and cancelling a Google Play subscription are two separate",
        "Cancelling the subscription does not delete the Schism account",
    ],
}

URL_KEYS = ["Privacy policy", "Terms", "Support", "Account deletion"]

# ponytail: crude secret sniff, enough to catch a pasted credential in review copy.
SECRET_PATTERNS = [
    (r"(?i)\bpass(?:word|phrase)\s*[:=]\s*(?!\s*$)(?![<\[])\S+", "literal password"),
    (r"(?i)\b(?:api[_ -]?key|token|secret)\s*[:=]\s*(?![<\[])[A-Za-z0-9_\-]{12,}", "literal secret"),
    (r"AIza[0-9A-Za-z_\-]{20,}", "Google API key"),
    (r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "private key"),
]


def sections(markdown):
    """Split a markdown doc into {level-2 heading: body}."""
    out, heading, body = {}, None, []
    for line in markdown.splitlines():
        if line.startswith("## "):
            if heading is not None:
                out[heading] = "\n".join(body).strip()
            heading, body = line[3:].strip(), []
        elif heading is not None:
            body.append(line)
    if heading is not None:
        out[heading] = "\n".join(body).strip()
    return out


def check(root):
    root = Path(root)
    errors = []

    missing = [name for name in REQUIRED_FILES if not (root / name).is_file()]
    errors += [f"missing required file: {name}" for name in missing]
    if "en-US/listing.md" in missing:
        return errors  # nothing else is checkable

    listing = (root / "en-US/listing.md").read_text(encoding="utf-8")
    parts = sections(listing)

    for heading, limit in LIMITS.items():
        text = parts.get(heading)
        if not text:
            errors.append(f"listing.md: missing or empty '## {heading}' section")
            continue
        if len(text) > limit:
            errors.append(f"listing.md: '{heading}' is {len(text)} chars, limit {limit}")

    urls = parts.get("Contact and policy URLs", "")
    for key in URL_KEYS:
        match = re.search(rf"^- {re.escape(key)}:\s*(\S+)$", urls, re.MULTILINE)
        if not match:
            errors.append(f"listing.md: no '{key}' entry under 'Contact and policy URLs'")
        elif not match.group(1).startswith("https://"):
            errors.append(f"listing.md: '{key}' URL is not https")
    if not re.search(r"^- Support email:\s*[^@\s]+@[^@\s]+\.\w+$", urls, re.MULTILINE):
        errors.append("listing.md: no 'Support email' entry with a real address")

    # Every release-notes file is checked, not just the one named in REQUIRED_FILES: the notes for
    # the version actually being submitted are the ones a reviewer reads.
    notes_dir = root / "en-US/release-notes"
    for notes_path in sorted(notes_dir.glob("*.txt")):
        label = f"release-notes/{notes_path.name}"
        notes = notes_path.read_text(encoding="utf-8").strip()
        if not notes:
            errors.append(f"{label} is empty")
        elif len(notes) > RELEASE_NOTES_LIMIT:
            errors.append(f"{label} is {len(notes)} chars, limit {RELEASE_NOTES_LIMIT}")
        for pattern in BANNED:
            if re.search(pattern, notes, re.IGNORECASE):
                errors.append(f"{label}: prohibited claim matching /{pattern}/")

    for pattern in BANNED:
        if re.search(pattern, listing, re.IGNORECASE):
            errors.append(f"listing.md: prohibited claim matching /{pattern}/")

    for name, phrases in REQUIRED_PHRASES.items():
        if name in missing:
            continue
        text = (root / name).read_text(encoding="utf-8")
        errors += [f"{name}: must state {phrase!r}" for phrase in phrases if phrase not in text]

    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in {".md", ".txt"}:
            continue
        text = path.read_text(encoding="utf-8")
        for pattern, label in SECRET_PATTERNS:
            if re.search(pattern, text):
                errors.append(f"{path.relative_to(root)}: looks like a committed {label}")

    return errors


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    errors = check(argv[1])
    for error in errors:
        print(f"FAIL {error}")
    if errors:
        return 1
    print(f"OK {argv[1]}: {len(REQUIRED_FILES)} files, limits and disclosures pass")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
