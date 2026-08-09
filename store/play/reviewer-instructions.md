# Reviewer instructions — Schism 1.3.0

Paste §0–§6 into Play Console → App access → "All functionality is available without special
access" notes (or attach as instructions if a login is required later).

## 0. Account access

No credential is needed and none is committed to this repository. Schism uses self-service
registration with no email verification: on the sign-up screen enter any name, any email address you
control, and a password of 8 characters or more. Phone number is optional and can be left blank.
The app needs an internet connection because accounts, groups and shared expenses live on the
Schism backend.

If Play requires a demo account for a later review, the owner creates one and types the credentials
into the Play Console form directly — never into this repository.

## 1. Fresh install state

- Bank message import is OFF. Nothing is read from SMS until it is explicitly enabled.
- No OCR model is present. The receipt reading model downloads only after you choose to scan and
  accept the download.
- Settings links to the privacy policy, terms, support and account deletion pages.

## 2. Core flow (no permissions needed)

1. Register, then create a group and add two or three participants by name.
2. Add an expense manually, choose equal or custom shares, and open Balances.
3. Invite: open the group's invite QR / link. It is `schism://group/<id>` behind an
   `https://<backend>/g/<id>` landing page.
4. Settle up: "Pay with UPI" opens an implicit `upi://pay` intent with no payee, so Android shows a
   chooser of the reviewer's own UPI apps. Schism does not process the payment. Skip this step if no
   UPI app is installed — the action is a safe no-op.

## 3. SMS import (the restricted-permission flow)

1. Settings → Bank message import → toggle on → confirm the explanation dialog.
2. Open Inbox → the disclosure screen appears first ("Schism reads transaction texts on your device
   to suggest expenses... parsed locally and never leave your phone") → "Enable automatic import"
   → the Android SMS permission dialog appears.
3. Deny it: the app stays usable; manual entry and receipt scanning are unaffected.
4. Grant it: a one-time backfill reads only messages from senders that
   `BankParserFactory.isKnownBankSender` recognises. To see a parsed row on an emulator, send a
   synthetic transaction SMS from a matching sender ID (for example `AD-HDFCBK`, body
   `Rs.450.00 debited from a/c XX1234 to SWIGGY on 09-08-26. Ref 123456.`).
5. Settings → "Disable and revoke Android permission" and "Delete imported message transactions" are
   two separate controls; both work.

Nothing from a message is uploaded. There is no API endpoint that accepts message text.

## 4. Receipt OCR

1. Inbox or a group → scan a bill → the app asks before downloading the ~6 MB PP-OCRv6 model and
   offers to wait for Wi-Fi.
2. Pick any receipt photo with the Android photo picker (no camera permission is requested).
3. Recognition runs locally with ONNX Runtime. The photo and the extracted text are not uploaded.
4. Results are editable. Quality varies with crop, focus and receipt layout — that is expected and
   the listing says so.

## 5. Optional extras

- Settings → On-device AI downloads an optional ~1.5 GB language model for better receipt and voice
  parsing. It is off by default and never required.
- Settings → Labs → "Claim links (alpha)" enables Live Split, where group members claim their own
  items from a scanned bill. It is off by default.
- Voice quick-add uses Android speech recognition with the offline engine preferred.

## 6. Deleting the account

Settings → Delete account, confirm. This calls `DELETE /v1/users/me` and removes the account
server-side; local data goes with the app on uninstall.

Deleting the Schism account and cancelling a Google Play subscription are two separate actions. To
cancel a subscription: Google Play → Payments & subscriptions → Subscriptions → Schism → Cancel.
Cancelling the subscription does not delete the Schism account, and deleting the account does not
cancel the subscription. Deletion after uninstall can be requested by emailing
dev.keshwani345@gmail.com, as documented on the account deletion page.

## 7. Notes for the reviewer

- Outbound network on a Play install: the Schism backend, a redirect to `huggingface.co` for the
  optional model bytes, and the Google Billing/Ads/UMP SDKs. Nothing else.
- The app does not distribute its own updates. `core/update/UpdateChecker.kt` checks the installing
  package and short-circuits when it is `com.android.vending` or `com.google.android.feedback`, so a
  Play-installed build never calls `api.github.com` and never shows an update banner or an APK link.
  Only sideloaded builds (installed by adb, a file manager or another store) run that check, because
  nothing else would tell them a new version exists.
- The app is not a default SMS handler and does not send SMS.
- Support: dev.keshwani345@gmail.com and https://api.schism.182116111.xyz/support
