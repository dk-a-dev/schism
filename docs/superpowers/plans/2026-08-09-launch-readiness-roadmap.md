# Schism v1.3 Launch Readiness Implementation Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a security-hardened backend and optimized Android v1.3.0 release candidate with on-demand OCR, retained SMS import, reproducible receipt evaluation, Play submission materials, and rollback evidence.

**Architecture:** Six plans produce independently reviewable deliverables in dependency order. Backend contracts land first; Android model delivery and app quality consume them; evaluation and store work consume the release candidate; CI/release automation gates the final artifacts.

**Tech Stack:** Go 1.26.2, chi, pgx/Postgres 16, Kotlin/Compose, WorkManager, Play Feature Delivery, ONNX Runtime, OpenCV, PaddleOCR, Gradle/AGP, GitHub Actions.

## Global Constraints

- Receipts, OCR output, bank SMS, voice recordings, and local-AI prompts never leave the device.
- Target/compile Android API 36; minimum SDK remains 26.
- Play downloads executable OCR code only through Play Feature Delivery; backend downloads only allowlisted model data.
- Play uses on-demand OCR; the GitHub standalone APK contains working offline OCR.
- SMS import remains a core feature and requires disclosure plus Play's SMS-based money-management declaration.
- Generic links never create arbitrary participants; redemption links bind only an organizer-created participant.
- No debug signing fallback, permissive production cleartext, hidden analytics, or new paid vendor.
- Preserve user-owned `.claude/` and `schism-backend/docker-compose.override.yml` changes.
- v1.2.2 data and backend clients must upgrade without destructive migration.

---

## Execution order

1. `2026-08-09-backend-security-model-delivery.md`
   - Produces authenticated group APIs, secure participant invites, hardened HTTP/auth, and the OCR
     manifest/download contract.
2. `2026-08-09-android-ocr-delivery.md`
   - Consumes the OCR manifest and creates resumable verified installs plus Play/standalone OCR
     packaging.
3. `2026-08-09-android-launch-quality.md`
   - Consumes secured backend/invite APIs and makes the full Android build, SMS path, privacy, and
     runtime release-ready.
4. `2026-08-09-ocr-evaluation.md`
   - Evaluates the release implementation against a deterministic 100-receipt public corpus and
     commits the launch thresholds/report.
5. `2026-08-09-play-assets-policy.md`
   - Produces the truthful policy pages, Play worksheets/listing, real screenshots, icon, feature
     graphic, and promo exports.
6. `2026-08-09-ci-release.md`
   - Automates every verified command, packages signed artifacts/checksums, runs the device matrix,
     and prepares the staged v1.3.0 release.

## Cross-plan checkpoints

- [ ] **Checkpoint A:** Backend migration/API compatibility tests pass before Android changes consume
  the new contracts.
- [ ] **Checkpoint B:** Play and standalone OCR instrumentation tests pass before removing models or
  runtimes from the base variant.
- [ ] **Checkpoint C:** Full Android release lint/unit/instrumentation/R8/16-KB verification passes
  before collecting screenshots or accuracy numbers.
- [ ] **Checkpoint D:** The 100-receipt report records immutable sample IDs, metrics, and failures
  before declaring OCR launch-ready.
- [ ] **Checkpoint E:** Store screenshots come from the verified release candidate and policy text
  matches actual data flow.
- [ ] **Checkpoint F:** Tag/release occurs only after CI, manual matrix, artifact signature/checksum,
  and rollback drill evidence are complete.
