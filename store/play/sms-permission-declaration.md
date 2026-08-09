# SMS permission declaration — Schism 1.3.0

## Permissions requested

- `android.permission.READ_SMS`
- `android.permission.RECEIVE_SMS`

Declared in `schism-android/app/src/main/AndroidManifest.xml`. No other restricted permission is
requested; Schism is not, and never asks to be, the default SMS handler.

## Declared core functionality

Financial services / money management: automatic import of transaction alerts from supported bank
senders, so a user can turn a real payment into an expense without retyping it.

## Why the permissions are required

`SmsReceiver` (registered for `SMS_RECEIVED`, guarded by `BROADCAST_SMS`) needs `RECEIVE_SMS` to see
a new transaction alert as it arrives. `SmsScanWorker` needs `READ_SMS` to run one backfill over
`Telephony.Sms.Inbox` so alerts that arrived before the user opted in also appear. Both check
`SmsImportPreference.isEnabled` first and stop immediately when it is false, and both skip every
sender that `BankParserFactory.isKnownBankSender` does not recognise.

## Why no alternative API is sufficient

Android has no user-permission-free API that exposes bank transaction alerts, and there is no
account link or bank aggregator in Schism. The alternative already shipped in the app — manual entry
and receipt scanning — remains fully available and is what the user gets if they decline.

## User control

1. Import is **off** on a fresh install. `SmsImportPreference` defaults to `false`.
2. Settings → Bank message import explains the feature and requires an explicit confirmation before
   the toggle turns on.
3. The Android permission is requested separately, from the Inbox screen, behind a disclosure that
   states: messages are read on the device to suggest expenses, are parsed locally, and never leave
   the phone.
4. Settings offers three separate actions: turn the toggle off, "Disable and revoke Android
   permission" (`revokeSelfPermissionOnKill` on Android 13+, otherwise the app settings screen), and
   "Delete imported message transactions".
5. Disabling import stops reading immediately and leaves already-parsed transactions in place until
   the user deletes them.

## Data handling

Raw SMS content, sender IDs and parsed message text stay in the app's local Room database. There is
no Schism API endpoint that accepts message text: message content is not sent to Schism servers or
to any third party. Only an expense the user explicitly creates and shares into a group is uploaded, and
that upload carries the title, amount, date, category and split the user confirmed — not the message.

Message data is not sold, not used for advertising, and not used to build a profile.

## Video / walkthrough for review

Record the flow in `reviewer-instructions.md` §3: fresh install → SMS off → Settings toggle with
confirmation → Inbox disclosure → Android permission dialog → imported transaction → disable and
revoke → delete imported transactions.
