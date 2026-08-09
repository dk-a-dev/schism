# Owner checklist — Schism 1.3.1 Play submission

Checked rows are done in this repository. Unchecked rows need the owner: a Console form, a
deployment, a device, or legal sign-off. Nothing here can be completed by an agent.

## Prepared in the repo

- [x] Listing copy within Play limits — `en-US/listing.md`, enforced by `tools/store/validate_copy.py`
- [x] Release notes for versionCode 10300 — `en-US/release-notes/10300.txt`
- [x] Release notes for versionCode 10301 — `en-US/release-notes/10301.txt`
- [x] Data safety worksheet — `data-safety.md`
- [x] SMS permission declaration — `sms-permission-declaration.md`
- [x] Financial features declaration — `financial-features-declaration.md`
- [x] Content rating answers — `content-rating.md`
- [x] Reviewer instructions with no committed credentials — `reviewer-instructions.md`
- [x] Support contact address for all store fields: dev.keshwani345@gmail.com
- [x] App icon and feature graphic exported from brand source — `assets/`, `tools/store/export_assets.py`
- [x] Promo kit — `store/promo/`
- [x] Phone screenshots — six 1080×1920 captures in `assets/phone/`, from the release build
- [x] Tablet screenshots — four 1440×2560 captures in `assets/tablet/`, covering both the 7-inch
      and 10-inch bands
- [x] Monetization reconciliation — the app launches free. The Schism Plus paragraph is commented
      out of `en-US/listing.md`, `data-safety.md` answers the four monetization rows "no", and the
      Billing/Ads/UMP SDKs are inert until the server-side flags are switched on
- [x] Manifest cross-check — `./gradlew :app:processReleaseMainManifest`; every permission in the
      merged manifest is accounted for below

## Merged release manifest — permission accounting

| Permission | Why it is there | Declaration needed |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Backend sync | None (normal) |
| `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager sync and model download | Foreground service type declaration in Console → App content |
| `POST_NOTIFICATIONS` | Sync and download progress | None |
| `READ_SMS`, `RECEIVE_SMS` | Optional bank-message import, off on a fresh install | `sms-permission-declaration.md` |
| `RECORD_AUDIO` | Voice quick-add via `SpeechRecognizer`, on-device where available | No form; covered by the privacy page and the "not collected" list in `data-safety.md` |
| `AD_ID`, `ACCESS_ADSERVICES_{AD_ID,ATTRIBUTION,TOPICS}` | Contributed by the Mobile Ads SDK, which never initialises at launch | Advertising ID declaration — see `data-safety.md` step 4 for the two options |

## Owner-controlled gates

- [ ] **Public domain.** All policy URLs currently point at `https://api.schism.182116111.xyz`,
      the host the app is built against. The marketing site is separately deployed at
      `https://schism-app.vercel.app`. If policy URLs move, set `SCHISM_PUBLIC_URL` and update the
      URLs in `en-US/listing.md`, `data-safety.md` and `reviewer-instructions.md`.
- [ ] **Deployment.** Deploy the backend with `SCHISM_SUPPORT_EMAIL` set (startup fails when empty —
      by design; the address is never a code default) and verify `/privacy`, `/terms`, `/support`
      and `/account-deletion` return 200 over HTTPS from outside your network.
- [ ] **Legal approval.** The privacy, terms and account-deletion pages carry explicit placeholders
      for jurisdiction, retention periods, warranty, liability and dispute wording. A legal reviewer
      must supply them before public launch.
- [ ] **Advertising ID declaration.** Pick one of the two options in `data-safety.md` step 4.
- [ ] **Play Console forms.** Data safety, app access, ads, content rating, target audience,
      government-apps, news, COVID, financial features, foreground service types, and the restricted
      SMS permission declaration — all must be submitted by the owner.
- [ ] **Developer verification** and payments profile.
- [ ] **Closed testing gate.** A personal Play developer account created after 2023-11-13 needs at
      least 12 opted-in testers for 14 continuous days before production access is granted.
- [ ] **Staged rollout approval** and production release.
- [ ] **Play listing IAP/subscription setup**: not needed at launch. Create `schism_plus` with
      `monthly` / `annual` base plans only when Plus is switched on, and restore the commented-out
      Plus paragraph in `en-US/listing.md` in the same change.

## Known cosmetic issue

`assets/phone/02-groups.png` and `05-dashboard.png` were captured from an account that still holds
a leftover group called "test" with one member, so it appears in the Groups list and in the "4
groups" count. Harmless for review, but worth re-capturing those two frames once the group is gone.
The app has no delete-group action yet, so removing it needs either that feature or a direct
database change.

## Verification commands

```bash
cd /Users/devkeshwani/Developer/schism
python3 -m unittest discover -s tools/store -p 'test_*.py'
python3 tools/store/validate_copy.py store/play
python3 tools/store/export_assets.py    # re-exports assets and rewrites the generation log

cd schism-android
./gradlew :app:processReleaseMainManifest   # then read the merged manifest permissions
```
