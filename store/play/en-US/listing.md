# Play Store listing — en-US

App: Schism (`com.dkadev.schism`) · versionName 1.3.4 · versionCode 10304

Every line below is checked against shipped code. `tools/store/validate_copy.py` enforces the
Play character limits and the prohibited-claim list.

## App name

Schism: Split Expenses

## Short description

Scan bills, track spending and split shared expenses privately.

## Full description

Schism turns a bill, a bank alert or a quick note into a shared expense — and keeps the detail that
explains it, so nobody has to remember who paid for what.

SPLIT A BILL LINE BY LINE
• Scan a receipt and Schism reads the items, taxes and total on your phone.
• Check and correct every line before anything is saved — including tax, service charge and
  round-off, each editable on its own.
• Split by item, evenly, by shares, by percentage, or by exact amounts.
• Or add an expense by hand, or by speaking it.

GROUPS AND WHO OWES WHOM
• Create a group for a flat, a trip or a dinner, and invite people with a link or a QR code.
• Balances settle down to the fewest transfers, so three people don't make six payments.
• Record a settle-up, browse group activity, and see personal and group spending summaries.
• Hand a settlement to your own UPI app when you want to pay — Schism does not move the money.

CLAIM TOGETHER, IN REAL TIME
• Share a scanned bill with the group and let everyone tick what they had.
• Watch each person's total update as they claim, then finalise it as one expense.

PRIVATE WHERE IT MATTERS
• Receipt reading runs on your phone by default. Nothing is uploaded unless you switch on the
  optional cloud reader, which tells you where your photo goes before the first scan.
• Bank message import is optional and off on a fresh install. Schism explains it before asking for
  the Android SMS permission, reads only recognised bank senders, and parses them on your device.
  Your messages are never sent to Schism.
• Settings has separate controls to switch import off, revoke the SMS permission, and delete
  imported transactions.
• The database on your phone is encrypted.
• You can delete your Schism account from inside the app.

WORKS OFFLINE
• The receipt reading model (about 6 MB) downloads the first time you choose to scan, can wait for
  Wi-Fi, and works offline afterwards.
• Settings offers a larger optional language model (about 1.5 GB) for smarter parsing. Both are
  optional and both run entirely on your device.

WHAT SCHISM IS NOT
• It is not a bank, a payment service or an accounting service. It does not connect to bank
  accounts, read statements, or transfer money.
• Receipt reading and message parsing can be wrong, and only some senders and layouts are
  understood. You review and edit everything before it becomes an expense.

Schism is free. There is no subscription and nothing is locked behind a purchase.

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
