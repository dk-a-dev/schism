# Play Store listing — en-US

App: Schism (`ai.schism.split`) · versionName 1.3.1 · versionCode 10301

Every line below is checked against shipped code. `tools/store/validate_copy.py` enforces the
Play character limits and the prohibited-claim list.

## App name

Schism: Split Expenses

## Short description

Scan bills, track spending and split shared expenses privately.

## Full description

Schism turns a receipt, a bank alert or a quick note into a clear shared expense — and keeps the
detail that explains it.

SCAN AND SPLIT
• Pick a receipt photo and let Schism read the line items on your phone.
• Check and edit every item, tax line and total before anything is saved.
• Split by item, equally, or by custom shares.
• Add expenses by hand or by voice whenever you prefer.

GROUPS AND BALANCES
• Create groups, invite people with a link or a QR code, and see who owes whom.
• Record settle-ups, browse group activity, and open personal and group spending summaries.
• Hand off a settlement to your own UPI app when you want to pay someone — Schism does not move
  the money itself.

PRIVATE WHERE IT MATTERS
• Receipt reading runs on your device. Photos and the text read from them are not uploaded.
• Bank message import is optional and switched off on a fresh install. Schism explains the feature
  before asking for the Android SMS permission, looks only at supported transaction senders, and
  parses them on your device. Raw messages are not sent to Schism.
• Settings has separate controls to switch import off, revoke the Android SMS permission, and
  delete imported message transactions.
• Only the groups, expenses and splits you choose to share are synced to your Schism account.
• Voice quick-add uses your device's speech recognition and prefers the offline engine when your
  phone has one.
• You can delete your Schism account from inside the app.

ON-DEVICE MODELS, DOWNLOADED WHEN YOU ASK
• The receipt reading model (about 6 MB) downloads the first time you choose to scan, can wait for
  Wi-Fi, resumes safely, and works offline afterwards.
• Settings offers a larger optional language model (about 1.5 GB) for smarter receipt and voice
  parsing. Both are optional and both run entirely on your device.

WHAT SCHISM IS NOT
• It is not a bank, a payment service or an accounting service. It does not connect to bank
  accounts, read statements, or transfer money.
• Receipt reading and message parsing can be wrong, and only some senders and layouts are
  understood. You review and edit everything before it becomes an expense.

Schism is free to use. There is no subscription and nothing is locked behind a purchase.

Support, privacy, terms and account deletion pages are linked below and from Settings.

<!--
LAUNCH IS FREE. The paragraph below replaces the line above the moment PLUS_ENABLED and
PURCHASES_ENABLED are switched on server-side, and not before: describing a subscription a user
cannot buy is a listing/behaviour mismatch reviewers do check for.

SCHISM PLUS
Schism Plus is an optional subscription. Free accounts can host three Live Splits per calendar
month (UTC); joining a Live Split someone else hosts is always free, and receipt scanning, message
import, manual entry, invitations, balances and settle-up are never part of the subscription. Plus
adds unlimited hosting, removes the banner ad, and adds Plus Insights plus CSV and PDF export.
Without Plus the app shows one banner after the spending insights, and asks for advertising consent
where that is required.
-->

## Contact and policy URLs

- Privacy policy: https://api.schism.182116111.xyz/privacy
- Terms: https://api.schism.182116111.xyz/terms
- Support: https://api.schism.182116111.xyz/support
- Account deletion: https://api.schism.182116111.xyz/account-deletion
- Support email: dev.keshwani345@gmail.com

## Store settings

- Category: Finance
- Tags: Personal finance, Finance, Productivity (chosen from Play's fixed tag list)
- Contains ads: no at launch (the Mobile Ads SDK ships but is switched off server-side; revisit when ADS_ENABLED is turned on)
- In-app purchases: no at launch (Play Billing ships but PURCHASES_ENABLED is off; revisit when Plus goes live)
- Default language: en-US
- Countries: owner decision at submission time

## Graphics

- App icon: `store/play/assets/icon-512.png`
- Feature graphic: `store/play/assets/feature-1024x500.png`
- Phone screenshots: `store/play/assets/phone/` — 1080x1920 (9:16), captured from the seeded demo account.
