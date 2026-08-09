# Data safety answers — Schism 1.3.0

Source of truth: `schism-backend/internal/api/*.go`, `schism-android/app/src/main/AndroidManifest.xml`,
`schism-android/app/src/main/java/ai/schism/split/core/net/ApiService.kt`, and the published privacy
page (`schism-backend/internal/web/templates/privacy.html`). Contact: dev.keshwani345@gmail.com.

## Summary answers

| Play question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS only; `network_security_config` forbids cleartext) |
| Do you provide a way for users to request that their data is deleted? | Yes — in-app Settings → Delete account (`DELETE /v1/users/me`), plus the account deletion page and dev.keshwani345@gmail.com |

## Collected by Schism's own backend

| Data type | Collected | Shared | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Name | Yes | Yes, with members of groups you join | Required | App functionality (account, group ledger) |
| Email address | Yes | No | Required | App functionality, account management |
| Phone number | Yes | Yes, with group members when supplied for a participant | Optional | App functionality (matching a participant to an account) |
| User IDs | Yes | No | Required | App functionality, account management |
| Other user-generated content (group names, expense titles, notes, amounts, splits, claims, activity) | Yes | Yes, with members of the group | Required for shared groups | App functionality |
| Purchase history (Google Play purchase token, product ID, subscription state) | Yes | No | Required for subscribers | App functionality, fraud prevention |
| App info and performance (crash/diagnostic signals from the ads SDK) | Yes, via SDK | Yes, with Google | Required | Analytics, fraud prevention |
| Approximate location (IP-derived, ads SDK) | Yes, via SDK | Yes, with Google | Required | Advertising |
| Device or other IDs (ads/consent SDK) | Yes, via SDK | Yes, with Google | Required | Advertising, fraud prevention |

Passwords are transmitted for register/login and stored only as a hash — declare as "collected", type
"User IDs"/account credentials per Play's account-management guidance; never shared.

## NOT collected — stays on device

- SMS message contents and sender IDs. Parsed by `SmsScanWorker`/`SmsReceiver` into a local Room
  database. No API endpoint accepts message text.
- Receipt photos and the OCR text extracted from them. `ReceiptScanner` runs the PP-OCRv6 ONNX model
  locally; no upload endpoint exists.
- Voice audio. `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, on-device recognizer when available.
- Contacts. The app has no contacts permission.
- Precise location, photos library beyond the single image the user picks with the Android photo
  picker, files, calendar, health.

## Third-party SDK disclosures

There is one shipped build — no product flavors, no Play Feature Delivery, one release AAB and one
APK from the default variant — so every SDK below is present in whatever the user installs.
Versions from `schism-android/gradle/libs.versions.toml`:

| SDK | Version | Data | Notes |
| --- | --- | --- | --- |
| Google Play Billing (`com.android.billingclient:billing-ktx`) | 9.1.0 | Purchase token, product ID, subscription state | Verified server-side; Schism never receives payment-card details |
| Google Mobile Ads (`com.google.android.gms:play-services-ads`) | 25.4.0 | IP-derived general location, app interactions, diagnostics, device/account identifiers | One inline banner after the Spending/Insights summaries |
| Google User Messaging Platform (`com.google.android.ump:user-messaging-platform`) | 4.0.0 | Consent state, device identifiers | Consent gathered before ad requests; Privacy choices entry in Settings |

## Other services the device may contact

Must stay consistent with the "Other services your device may contact" section of the hosted privacy
page (`schism-backend/internal/web/templates/privacy.html`).

- **Model download → Hugging Face.** The app asks the Schism backend which OCR model to use; the
  artifact routes redirect (HTTP 307/302) to `huggingface.co`, which serves the bytes. Hugging Face
  therefore receives ordinary network metadata such as the device IP and the file requested. No
  receipt image, receipt text or message content is part of that request.
- **GitHub update check — not on Play installs.** `core/update/UpdateChecker.kt` resolves the
  installing package and short-circuits to null for `com.android.vending` and
  `com.google.android.feedback`, so a Play-installed app makes **no** request to `api.github.com`
  and shows no update banner or APK link. Only sideloaded builds check GitHub. Do not declare this
  call for the Play distribution.
- **UPI hand-off.** "Pay with UPI" passes an amount and a note to whichever UPI app the user picks.
  No payee VPA, no card or bank credential, and no payment result reaches Schism.

## Owner actions before submitting the form

1. Re-check the SDK versions above against the final dependency graph before submitting.
2. If Play Billing and Mobile Ads do NOT ship in 1.3.0, delete the purchase/ads rows above and the
   Schism Plus paragraph in `en-US/listing.md`, and set "Contains ads"/"In-app purchases" to no.
3. Answer Play's "Data collected vs shared" toggles exactly as tabled above; each ads row is
   "shared" because Google processes it as an independent controller in some configurations.
