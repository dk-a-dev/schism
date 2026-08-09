# Turning on Schism Plus and ads

Everything is already implemented and inert. Nothing here changes app code — it is all
account setup plus configuration values. Until each flag is set the corresponding feature stays
off, which is deliberate: no half-configured billing or ad code ever reaches a user.

The exact names below are the ones the code reads. They are not examples.

---

## 1. Ads (Google AdMob)

Schism shows exactly one inline adaptive banner, after the Spending/Insights summary, only for a
consented user whose account is at least 7 days old with 3+ meaningful actions. No interstitials,
no app-open, no rewarded ads.

**Get the identifiers**

1. Create an AdMob account at https://admob.google.com and add an Android app.
   - If the app is not on Play yet, choose "No, it's not listed on a supported app store" and link
     it to the Play listing later. Linking is what makes real ads serve.
2. Copy the **App ID**. Format `ca-app-pub-################~##########` (tilde).
3. In that app, create one ad unit → **Banner**. Copy the **Ad unit ID**.
   Format `ca-app-pub-################/##########` (slash).
4. AdMob → Privacy & messaging → create a **GDPR** and a **US state regulations** message. The app
   uses the UMP SDK and will show whatever you configure. Without a message, consent cannot be
   collected and the banner will not serve in regulated regions.
5. AdMob → Payments: complete address verification and add a payment method, or nothing pays out.

**Schism's live identifiers** (already wired in `app/build.gradle.kts`):

```
App ID           ca-app-pub-1250081810965574~3212649945
Banner unit ID   ca-app-pub-1250081810965574/2871924504
```

These are **not secrets** — they ship inside the APK and anyone can read them out of it — so they
are committed rather than injected. What matters is *which variant uses them*:

| Variant | AdMob ids |
|---|---|
| `release` | the production pair above |
| `debug`, tests, CI | Google's published **test** ids |

A debug build requesting production ads is invalid traffic, which is an AdMob policy violation and
can get the account terminated. That is why the production pair is attached to the release build
type only, never to `defaultConfig`.

To override without editing the build file — a second AdMob account, or a throwaway experiment —
either environment variables:

```bash
export SCHISM_ADMOB_APP_ID='ca-app-pub-…~…'
export SCHISM_ADMOB_BANNER_UNIT_ID='ca-app-pub-…/…'
./gradlew :app:assembleRelease
```

or Gradle properties, in `~/.gradle/gradle.properties` (machine-local, never committed):

```properties
schism.admobAppId=ca-app-pub-…~…
schism.admobBannerUnitId=ca-app-pub-…/…
```

Env wins over the Gradle property, which wins over the committed default.

**Test devices.** Register every phone you test on: AdMob → Settings → Test devices, or copy the
`Use RequestConfiguration.Builder().setTestDeviceIds(...)` line AdMob prints in logcat on first ad
request. A registered device gets test ads even from a production build, so you can tap them safely.
An unregistered device on a release build is generating real traffic.

> Clicking your own live ads is policy-violating and can get the account terminated. Test with the
> default test IDs, or register your device as a test device in AdMob.

**Backend switch:** `ADS_ENABLED=true`.

---

## 2. Schism Plus (Google Play Billing)

**a. Create the products** — Play Console → your app → Monetise → Subscriptions.

1. Create a subscription with product ID `schism_plus`.
2. Add base plans, e.g. `monthly` (P1M) and `annual` (P1Y), each with a price in your markets.
3. Activate them. A subscription with no active base plan cannot be purchased.
4. The app must be published to at least an internal testing track before purchases work at all.
5. Add licence testers: Play Console → Setup → Licence testing. Testers buy without being charged.

**b. Create the service account** so the backend can verify purchases server-side.

1. Google Cloud Console → the project linked to your Play account → IAM & Admin → Service accounts →
   Create service account.
2. Create a **JSON key** for it and download it. This file is a credential — never commit it.
3. Play Console → Setup → API access → link the Cloud project, find the service account, Grant
   access, and give it **View financial data** and **Manage orders and subscriptions**.
4. Permissions can take a few hours to propagate. Verification returning "not found" immediately
   after granting usually means propagation, not a bug.

**c. Generate the token-encryption key.** Purchase tokens are stored AES-256-GCM encrypted; this is
the key. It must be standard base64 of exactly 32 bytes:

```bash
openssl rand -base64 32
```

Store it once. Rotating it makes existing stored purchase tokens undecryptable.

**Backend switches:**

```
PURCHASES_ENABLED=true
PLUS_ENABLED=true
PLAY_PACKAGE_NAME=com.dkadev.schism
BILLING_TOKEN_KEY=<base64 of 32 random bytes>
PLAY_SERVICE_ACCOUNT_JSON=<the entire JSON key file contents>
```

Config validation refuses to start if `PURCHASES_ENABLED` is set without the last three, so a
misconfigured deploy fails loudly at boot instead of silently failing to verify purchases.

---

## 3. What each backend flag actually does

| Variable | Effect when true |
|---|---|
| `PLUS_ENABLED` | Enforces the free tier: **3 hosted Live Splits per UTC month** per account. Joining and every operation on an existing session stay free. Off = unlimited, as today. |
| `ADS_ENABLED` | Lets the client request the one banner, subject to consent and the eligibility gate. |
| `PURCHASES_ENABLED` | Enables the purchase-verification endpoints. Requires the three values above. |

**Turn on `PLUS_ENABLED` last.** It starts metering Live Splits for every existing free account in
production the moment it is set — including accounts mid-use. `ADS_ENABLED` and
`PURCHASES_ENABLED` can be enabled earlier without changing anyone's limits.

---

## 4. Order to do it in

1. Set `ADS_ENABLED` with AdMob test IDs still in the build → confirm the slot renders and consent
   is requested. No revenue risk.
2. Create the Play subscription and service account → set `PURCHASES_ENABLED` with its three
   values → confirm a licence tester can complete a purchase and the backend grants entitlement.
3. Only then set `PLUS_ENABLED`, so the free-tier limit appears at the same moment a paid upgrade
   is genuinely purchasable.
4. Rebuild the release APK/AAB with the production AdMob IDs exported.
