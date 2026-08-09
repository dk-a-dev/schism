# Financial features declaration — Schism 1.3.0

Play Console → App content → Financial features.

## Answer

**"My app doesn't provide any financial features."**

Schism organises expenses people already paid. It does not offer, broker or facilitate any of the
declarable categories: no personal loans, no lending marketplace, no debt management, no banking or
e-money account, no stored value, no remittance or money transmission, no crypto exchange or wallet,
no investment or trading, no insurance, no tax preparation or filing.

## Supporting facts

- No bank connection, no aggregator SDK, no statement or account access. The only financial input is
  transaction alerts the user's phone already received, parsed on-device (`SmsScanWorker`), plus
  receipts and manual entry.
- Balances are arithmetic over expenses the group entered (`schism-backend/internal/api/balances.go`).
  Marking a settle-up records a row; no money moves.
- Payment hand-off: "Pay with UPI" builds an implicit `upi://pay` intent
  (`groups/detail/settle/Upi.kt`) with an amount and note and **no payee VPA**, then lets Android
  open whichever UPI app the user chooses. Schism is not in the payment path, never sees a VPA,
  card number, PIN or payment result, and takes no fee. If the device has no UPI app the action is a
  no-op.
- The only money Schism itself charges is the Schism Plus subscription, billed by Google Play.
  Schism receives a purchase token, product ID and subscription state — never card details.

## If a reviewer disagrees about the UPI hand-off

Point them at `upiPaymentIntent()`. It is a link-out to a user-chosen third-party app, equivalent to
an `ACTION_VIEW` on a URL, and the surrounding screen states that Schism does not move money. The
listing repeats this. Escalation contact: dev.keshwani345@gmail.com.

## India-specific note for the owner

Google Play's India financial-services policies apply to apps that provide lending or payment
services. Schism does neither. The owner should still confirm current Play policy wording before
submission, because the UPI hand-off is the only surface a reviewer is likely to question.
