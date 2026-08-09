# Owner checklist — Schism 1.3.0 Play submission

Checked rows are done in this repository. Unchecked rows need the owner: a Console form, a
deployment, a device, or legal sign-off. Nothing here can be completed by an agent.

## Prepared in the repo

- [x] Listing copy within Play limits — `en-US/listing.md`, enforced by `tools/store/validate_copy.py`
- [x] Release notes for versionCode 10300 — `en-US/release-notes/10300.txt`
- [x] Data safety worksheet — `data-safety.md`
- [x] SMS permission declaration — `sms-permission-declaration.md`
- [x] Financial features declaration — `financial-features-declaration.md`
- [x] Content rating answers — `content-rating.md`
- [x] Reviewer instructions with no committed credentials — `reviewer-instructions.md`
- [x] Support contact address for all store fields: dev.keshwani345@gmail.com
- [x] App icon and feature graphic exported from brand source — `assets/`, `tools/store/export_assets.py`
- [x] Promo kit — `store/promo/`

## Owner-controlled gates

- [ ] **Public domain.** All policy URLs currently point at `https://api.schism.182116111.xyz`,
      the host the 1.3.0 app is built against. If a marketing domain is used instead, set
      `SCHISM_PUBLIC_URL` and update the URLs in `en-US/listing.md`, `data-safety.md` and
      `reviewer-instructions.md`.
- [ ] **Deployment.** Deploy the backend with `SCHISM_SUPPORT_EMAIL` set (startup fails when empty —
      by design; the address is never a code default) and verify `/privacy`, `/terms`, `/support`
      and `/account-deletion` return 200 over HTTPS from outside your network.
- [ ] **Legal approval.** The privacy, terms and account-deletion pages carry explicit placeholders
      for jurisdiction, retention periods, warranty, liability and dispute wording. A legal reviewer
      must supply them before public launch.
- [ ] **Screenshots.** Plan Task 4 — six 1080×1920 captures from the release candidate on an API 36
      emulator with synthetic fixtures. Not done; `assets/phone/` is empty.
- [ ] **Monetization reconciliation.** If Play Billing and Mobile Ads do not ship in 1.3.0, remove
      the Schism Plus paragraph from the full description, the purchase/ads rows from
      `data-safety.md`, and set "contains ads" / "in-app purchases" to no. If they do ship, record
      the exact SDK versions in `data-safety.md` from the final dependency graph.
- [ ] **Manifest cross-check.** Run `./gradlew :app:processReleaseMainManifest` on the release
      artifact and confirm every permission in the merged manifest is covered by the declarations.
- [ ] **Play Console forms.** Data safety, app access, ads, content rating, target audience,
      government-apps, news, COVID, financial features, and the restricted SMS permission
      declaration — all must be submitted by the owner.
- [ ] **Developer verification** and payments profile for the subscription.
- [ ] **Closed testing gate.** A personal Play developer account created after 2023-11-13 needs at
      least 12 opted-in testers for 14 continuous days before production access is granted.
- [ ] **Staged rollout approval** and production release.
- [ ] **Play listing IAP/subscription setup**: create `schism_plus` with `monthly` / `annual` base
      plans before the listing claims them.

## Verification commands

```bash
cd /Users/devkeshwani/Developer/schism
python3 -m unittest discover -s tools/store -p 'test_*.py'
python3 tools/store/validate_copy.py store/play
python3 tools/store/export_assets.py    # re-exports assets and rewrites the generation log
```
