# Changelog

## 1.3.1 — Unreleased

- Fixed groups created on one device not appearing on another: membership now syncs on every refresh, not only at sign-in.
- Fixed receipt scanning returning nothing on slightly angled photos; rows are grouped on tilt-corrected geometry, and amounts no longer shift onto the following item.
- Fixed the guided tour closing immediately instead of opening.
- Fixed status bar icons being invisible in light mode on a dark-mode phone.
- Added a shareable group invite link so people can join themselves instead of being entered in advance.
- Live Split claiming is on by default and no longer hidden behind a Labs toggle.
- Moved the unlabelled scan icon into the labelled action menu.
- Receipt parsing now passes all 100 fixtures in the reference battery, including non-rupee currencies and quantity-noise columns.

## 1.3.0 — Unreleased

- Added fully on-device PaddleOCR receipt scanning with verified, resumable model delivery from the Schism backend.
- Added explicit first-use OCR download consent, Wi-Fi-only mode, progress, retry, and offline reuse.
- Made bank-message import strictly optional, duplicate-safe, local-only, revocable, and independently deletable.
- Hardened backend authentication, authorization, rate limits, account deletion, model distribution, and container deployment.
- Encrypted Android bearer tokens, disabled financial-data backup, enforced HTTPS in release builds, and removed destructive database fallback.
- Targeted Android API 36 and enabled release lint, R8 optimization, resource shrinking, and required signing.
- Added launch website, privacy/support/deletion surfaces, guided discovery, and a 100-receipt parser battery.

