# Data safety answers — Schism 1.3.1

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

### Not collected at launch — monetization is off

1.3.1 launches free. `PLUS_ENABLED`, `PURCHASES_ENABLED` and `ADS_ENABLED` are all off server-side,
and the app treats them as off until an authenticated config says otherwise, so on a fresh install
none of the rows below collect anything:

- **Play Billing** is never invoked: nothing reaches a purchase flow, so no purchase token exists.
- **Mobile Ads** is never initialised. `InlineAdaptiveAd` calls `MobileAds.initialize` only after
  the eligibility gate passes, and that gate requires `adsEnabledByBackend`.
- **UMP consent** is never requested. `ConsentManager.refresh` is gated on the same
  `adsEnabledByBackend` flag, so no consent form is shown and no consent identifier is processed.

Answer these four rows **No** for the launch build. Turning any of the flags on server-side makes
them true without an app update — update the form in the same change.

| Data type | Collected | Shared | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Purchase history (Google Play purchase token, product ID, subscription state) | Only if `PURCHASES_ENABLED` | No | Required for subscribers | App functionality, fraud prevention |
| App info and performance (crash/diagnostic signals from the ads SDK) | Only if `ADS_ENABLED` | Yes, with Google | Required | Analytics, fraud prevention |
| Approximate location (IP-derived, ads SDK) | Only if `ADS_ENABLED` | Yes, with Google | Required | Advertising |
| Device or other IDs (ads/consent SDK) | Only if `ADS_ENABLED` | Yes, with Google | Required | Advertising, fraud prevention |

Passwords are transmitted for register/login and stored only as a hash — declare as "collected", type
"User IDs"/account credentials per Play's account-management guidance; never shared.

### Photos — optional cloud receipt reading

| Data type | Collected | Shared | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Photos (the single receipt image the user picks) | Yes, only when cloud reading is enabled | Yes, with Google (Gemini) or Groq | **Optional** — off by default | App functionality (reading the bill) |

Declare Photos as **collected and shared, optional**. Two things make this row unavoidable even
though the default setting uploads nothing:

- **Own-key mode** sends the photo from the device straight to Google or Groq. Play counts an app
  transmitting user data off the device as collection *and* sharing, regardless of whose credential
  authorised it.
- **Schism's cloud reader** sends the photo to our backend, which forwards it to the provider. The
  image is held only for the length of the request — never written to disk, the database, or logs
  (`internal/receiptai`, asserted by test) — but it is still transmitted, so it is still declared.

Answer "Is this data processed ephemerally?" **yes** for the Schism path and **no** for own-key,
which Play cannot express separately; declare the stricter of the two (not ephemeral).

## NOT collected — stays on device

- SMS message contents and sender IDs. Parsed by `SmsScanWorker`/`SmsReceiver` into a local Room
  database. No API endpoint accepts message text.
- Receipt OCR text produced on-device. `ReceiptScanner` runs the PP-OCRv6 ONNX model locally and no
  endpoint accepts OCR text.
- Receipt photos, **on the default setting only** — see the Photos row below. On-device reading is
  the default and uploads nothing; the optional cloud reader changes this and must be declared.
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
2. Answer the four monetization rows **No** — see "Not collected at launch" above. Set
   "Contains ads" and "In-app purchases" to **no**, matching `en-US/listing.md`, where the Schism
   Plus paragraph is already commented out.
3. Answer Play's "Data collected vs shared" toggles exactly as tabled above; each ads row is
   "shared" because Google processes it as an independent controller in some configurations.
4. **Advertising ID declaration.** The merged release manifest contains
   `com.google.android.gms.permission.AD_ID` and the three `ACCESS_ADSERVICES_*` permissions,
   contributed by the Mobile Ads SDK, so Play will require this declaration even though no ad is
   served at launch. Two defensible answers, and this is an owner decision:
   - **Declare it (recommended).** Say the app uses the advertising ID for Advertising. This stays
     true the moment `ADS_ENABLED` is switched on, which needs no app update.
   - **Strip it.** Add a `tools:node="remove"` for `AD_ID` in `AndroidManifest.xml` and declare no
     advertising ID. Then enabling ads later requires a new app release, not just a flag.
