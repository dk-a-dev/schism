# schism-backend — Frontend API Contract (v1)

Base URL: `{BASE}` (configurable in the app; e.g. `http://10.0.2.2:8080` from an Android emulator).
All endpoints are under `/v1`. All request and response bodies are JSON with **camelCase** keys.

## Conventions

- **Money** is always an integer in **minor units** (cents/paise). `1000` = 10.00 in the group's
  currency. Never a float. The frontend formats using the group's `currency`/`currencyCode`.
- **Authentication is required** for all `/v1` data routes except register, login, and the OCR
  model catalog. Send `Authorization: Bearer <token>`. Group IDs are identifiers, not capabilities:
  an existing non-member receives `403`, an anonymous caller `401`, and a missing group `404`.
- **IDs** are opaque URL-safe strings (12 chars) except `Category.id` which is an integer.
- **Errors** use HTTP status + `{ "error": "<message>" }`. Validation → `400`, unknown resource →
  `404`, server fault → `500`.
- **Split modes:** `EVENLY | BY_SHARES | BY_PERCENTAGE | BY_AMOUNT`. Share encoding:
  - `EVENLY`: shares ignored (equal split across `paidFor`).
  - `BY_SHARES`: integer weights (e.g. 1, 2).
  - `BY_PERCENTAGE`: integers that sum to `10000` (i.e. percent ×100; 30% = `3000`).
  - `BY_AMOUNT`: integers (minor units) that sum to the expense `amount`.

---

## Health

`GET /health` → `200 {"status":"ok"}` (liveness; not under `/v1`). Database readiness is
`GET /ready`.

## Categories

`GET /v1/categories` → `200`
```json
[ { "id": 0, "grouping": "Uncategorized", "name": "General" },
  { "id": 7, "grouping": "Food and drink", "name": "Groceries" } ]
```
43 seeded categories grouped by: Uncategorized, Entertainment, Food and drink, Home, Life,
Transportation, Utilities. `categoryId` `0` (General) is the default.

## Groups

### Create
`POST /v1/groups`
```json
{ "name": "Goa Trip", "information": "", "currency": "₹", "currencyCode": "INR",
  "participants": [ { "name": "Dev" }, { "name": "Sam" } ] }
```
→ `201 { "groupId": "<id>" }`. Rules: `name` ≥ 2 chars, ≥ 1 participant.

### Get / Details
`GET /v1/groups/{id}` and `GET /v1/groups/{id}/details` → `200`
```json
{ "id": "...", "name": "Goa Trip", "information": "", "currency": "₹", "currencyCode": "INR",
  "createdAt": "2026-07-05T00:00:00Z",
  "participants": [ { "id": "p1", "groupId": "...", "name": "Dev" } ] }
```
`404` if unknown.

### List by ids
`GET /v1/groups?ids=a,b,c` → `200 [ Group, ... ]`. Requested IDs are intersected with the caller's
memberships; unknown/unauthorized IDs are omitted. With no `ids`, all caller memberships are listed.

### Update
`PUT /v1/groups/{id}` — same body as create. Participants **with** an `id` are updated, **without**
an `id` are added, and existing participants omitted from the array are removed. → `200 Group`.

## Expenses

`Expense` shape (responses):
```json
{ "id": "...", "groupId": "...", "title": "Dinner", "amount": 1200, "categoryId": 7,
  "expenseDate": "2026-07-05T00:00:00Z", "paidById": "p1", "splitMode": "EVENLY",
  "isReimbursement": false, "notes": "", "createdAt": "...",
  "paidFor": [ { "participantId": "p1", "shares": 100 }, { "participantId": "p2", "shares": 100 } ] }
```

### List / Get
- `GET /v1/groups/{id}/expenses` → `200 [ Expense, ... ]` (newest first).
- `GET /v1/groups/{id}/expenses/{expenseId}` → `200 Expense` / `404`.

### Create
`POST /v1/groups/{id}/expenses`
```json
{ "title": "Dinner", "amount": 1200, "categoryId": 7, "expenseDate": "2026-07-05T00:00:00Z",
  "paidById": "p1", "splitMode": "EVENLY", "isReimbursement": false, "notes": "",
  "paidFor": [ { "participantId": "p1", "shares": 100 }, { "participantId": "p2", "shares": 100 } ] }
```
→ `201 Expense`. `expenseDate`/`splitMode` optional (default today / `EVENLY`).
**Idempotency:** send header `Idempotency-Key: <stable-key>` (per group) so a retry never
double-creates — the same key returns the original expense. (Used by SMS push-to-split.)
Validation `400`: amount ≠ 0 and ≤ `1000000000`; ≥ 1 `paidFor`; each `shares` > 0;
`BY_AMOUNT` shares sum to `amount`; `BY_PERCENTAGE` shares sum to `10000`.

### Update / Delete
- `PUT /v1/groups/{id}/expenses/{expenseId}` — same body as create → `200 Expense` / `404`.
- `DELETE /v1/groups/{id}/expenses/{expenseId}` → `204` / `404`.

## Balances

`GET /v1/groups/{id}/balances` → `200`
```json
{ "balances": { "p1": { "paid": 0, "paidFor": 500, "total": 500 },
                "p2": { "paid": 0, "paidFor": 500, "total": -500 } },
  "reimbursements": [ { "from": "p2", "to": "p1", "amount": 500 } ] }
```
`balances` is the privacy-preserving settle-up view derived from `reimbursements`; `total > 0`
means the participant is owed money, `total < 0` means they owe. `reimbursements` is the minimal
set of "who pays whom" transfers.

## Activities

`GET /v1/groups/{id}/activities` → `200`
```json
[ { "id": "...", "groupId": "...", "time": "...", "activityType": "CREATE_EXPENSE",
    "participantId": null, "expenseId": "...", "data": "" } ]
```
`activityType ∈ CREATE_EXPENSE | UPDATE_EXPENSE | DELETE_EXPENSE | UPDATE_GROUP` (newest first).

## Stats (lightweight)

`GET /v1/groups/{id}/stats` → `200 { "totalGroupSpending": 1200, "expenseCount": 1 }`
(spending excludes reimbursements). For the full dashboard use the endpoint below.

---

## Dashboards / Insights

### Group dashboard
`GET /v1/groups/{id}/dashboard` — optional `?participant={participantId}` attaches a personal slice.
→ `200`
```json
{ "groupId": "...", "name": "Goa Trip", "currency": "₹", "currencyCode": "INR",
  "totalSpending": 2000, "expenseCount": 3, "reimbursementCount": 1, "averageExpense": 666,
  "firstExpenseDate": "2026-06-10T00:00:00Z", "lastExpenseDate": "2026-07-05T00:00:00Z",
  "byCategory": [ { "categoryId": 7, "grouping": "Food and drink", "name": "Groceries",
                   "amount": 1400, "count": 2 } ],
  "byParticipant": [ { "participantId": "p1", "name": "Dev", "paid": 1400, "share": 1000,
                       "net": 400 } ],
  "byMonth": [ { "month": "2026-06", "amount": 1000, "count": 1 },
               { "month": "2026-07", "amount": 1000, "count": 2 } ],
  "topExpenses": [ { "id": "...", "title": "Groceries", "amount": 1000,
                     "date": "2026-06-10T00:00:00Z", "paidById": "p1", "categoryId": 7 } ],
  "personal": { "participantId": "p1", "name": "Dev", "paid": 1400, "share": 1000, "net": 400,
                "expenseCount": 3, "byCategory": [ { "categoryId": 7, "name": "Groceries",
                                                     "amount": 700, "count": 2 } ] } }
```
`byCategory`/`byMonth`/`topExpenses`/`totalSpending`/`averageExpense` exclude reimbursements.
`personal.byCategory` is that participant's own share per category (approximate to the cent).
All list fields are always present (may be `[]`). `personal` is omitted when `participant` is not
provided or not found.

### Personal dashboard (cross-group)
`GET /v1/dashboard?participant={name|id}&groupIds=a,b,c` — aggregates the person's position across
the given groups. `participant` matches a group participant by **id** or by **name**
(case-insensitive); groups where nobody matches are skipped. → `200`
```json
{ "identity": "Dev", "groupCount": 2,
  "groups": [ { "groupId": "g1", "groupName": "Goa", "currency": "₹", "currencyCode": "INR",
                "participantId": "p1", "participantName": "Dev",
                "paid": 1000, "share": 500, "net": 500, "expenseCount": 1 } ],
  "totals": [ { "currencyCode": "INR", "currency": "₹", "paid": 1000, "share": 500,
                "net": 500, "groupCount": 1 },
              { "currencyCode": "USD", "currency": "$", "paid": 0, "share": 250,
                "net": -250, "groupCount": 1 } ] }
```
`totals` are **bucketed per currency** — figures are never summed across different currencies.
`participant` is required (`400` if missing); no `groupIds` → empty dashboard.

---

## Notes for the client

- Cache `GET` responses in Room for offline viewing; writes require network (surface a clear
  offline error). Refetch affected resources after a successful write.
- Activity actors and the group dashboard's "you" participant are derived from the session; client
  `addedBy` and group-dashboard participant values are not trusted.
- Prefer the group `dashboard` endpoint over `stats` for anything richer than a headline number.

## Participant invitations

`POST /v1/groups/{groupId}/participants/{participantId}/invite` creates a seven-day one-time token
for an existing unlinked participant. `GET /v1/invites/{token}` returns safe preview fields;
`POST /v1/invites/{token}/redeem` atomically links the authenticated user. Replay or an already-linked
participant returns `409`. Share the HTTPS landing `/i/{token}`, which opens `schism://invite/{token}`.

## OCR model delivery

`GET /v1/models/ocr/manifest` is public and returns version `2026.06`, minimum app code `10300`,
total bytes, per-file SHA-256 values, and relative download paths. `GET|HEAD
/v1/models/ocr/2026.06/{det.onnx|rec.onnx|rec.yml}` redirects only to revision-pinned official
PaddlePaddle Hugging Face files. Versioned redirects are immutable and do not accept upstream URLs.

## Monetization: entitlement, Plus, and the free Live Split allowance

All four routes below are authenticated. Every switch defaults **off**, so a deployment that
configured nothing gates nothing and sells nothing.

`GET /v1/monetization/config` →
`200 {"plusEnabled":false,"adsEnabled":false,"purchasesEnabled":false,"freeLiveSplits":3}`.

`GET /v1/entitlement` →
`200 {"active":false,"productId":"","expiresAt":"0001-01-01T00:00:00Z","autoRenewing":false,
"freeLiveSplits":{"used":0,"limit":3,"resetsAt":"..."}}`. `active` is server-owned: it is derived
only from purchases the backend itself verified with Google, never from a client claim. The call
also refreshes purchase records older than six hours or past their expiry.

`POST /v1/billing/verify` with `{"productId":"schism_plus","purchaseToken":"..."}` verifies the token
with Google, records it (AES-256-GCM encrypted; the token is never logged or echoed) and returns the
same body as `GET /v1/entitlement`. Only package `com.dkadev.schism`, product `schism_plus`, and a
`PURCHASED` state grant Plus. Rejections: `400 purchase_not_verified` (wrong package/product or
unknown token), `409 purchase_already_linked` (token belongs to another Schism account),
`502 purchase_verification_unavailable` (transient Google failure — the caller should retry, and any
existing entitlement is left intact), `503 purchases_disabled`.

`POST /v1/billing/restore` (empty body) re-verifies every purchase already on file for the caller —
this is the reinstall / Play-account-switch path — and returns the entitlement body.

### The only gated action: hosting a Live Split

`POST /v1/groups/{groupId}/claim-sessions` is the **single** route that consumes allowance. When
`PLUS_ENABLED` is on it requires an `Idempotency-Key` header (1–200 chars); a missing or oversized
key is `400 idempotency_key_required`. Retrying with the same key returns the original session and
consumes nothing. Free accounts get three creates per UTC calendar month; the fourth is

```json
402 {"error":"PLUS_REQUIRED","used":3,"limit":3,"resetsAt":"2026-09-01T00:00:00Z"}
```

Plus accounts bypass the counter entirely. Joining a session and every other claim-session route
(`GET`, `PUT /claims`, `PUT /ready`, `PATCH /items`, `POST /finalize`, `POST /cancel`) are never
gated, and an expired subscription never invalidates existing sessions or hides existing data.

## Cloud receipt extraction

`POST /v1/receipts/extract` turns a photographed bill into a draft using a cloud vision model
(Gemini or Groq, whichever the deployment configured). It is authenticated, and it is **off by
default**: with `RECEIPT_AI_ENABLED` unset the route is not registered at all and the path answers
`404`. The same extraction also exists as a bring-your-own-key path in the app, which calls the
vendor directly and never touches this endpoint.

**The image is never persisted.** Not to disk, not to Postgres, not to any log line. It exists only
for the duration of the request, and only the timestamp of the call is stored (for the rate limit).

Request — JSON, image base64-encoded, `mimeType` one of `image/jpeg`, `image/png`, `image/webp`:

```json
{"groupId":"grp_123","mimeType":"image/jpeg","imageBase64":"/9j/4AAQSkZJRgABA..."}
```

Response `200` is the draft. Every amount is an **integer in minor units**; `chargeLines[].amountMinor`
is signed in the direction it moves the bill (taxes and fees positive, discounts negative, round-off
either), `taxMinor` is their sum, and `subtotalMinor + taxMinor == totalMinor`:

```json
{
  "merchant":"Gulab Dhaba","date":"2026-03-05","currency":"₹",
  "items":[{"name":"Paneer Tikka","qty":2,"amountMinor":38000}],
  "subtotalMinor":38000,"taxMinor":1900,"totalMinor":39900,
  "chargeLines":[{"label":"GST 5%","amountMinor":1900,"kind":"TAX"}]
}
```

`kind` is one of `TAX`, `FEE`, `DISCOUNT`, `ROUNDOFF`. `date` is `yyyy-MM-dd` or `""`.

**Rate limit: one extraction per user per group per hour**, held in Postgres so it holds across
replicas. The second call inside the window is

```json
429 {"error":"receipt_extract_rate_limited","retryAfterSeconds":3480,
     "nextAllowedAt":"2026-03-05T13:00:00Z"}
```

with a matching `Retry-After` header.

Other rejections: `404` (feature off, or unknown group), `403` (not a member of the group),
`413 image_too_large` (decoded image over 8 MB, or the request body over its ceiling — checked
before any provider call), `415 image_must_be_jpeg_png_or_webp`, `400 invalid_image` (not base64 or
empty), `422 receipt_not_readable` (the model's answer was prose, carried non-integer money, or did
not reconcile — retrying the same photo will not help), `502 receipt_extraction_unavailable`
(provider down, rate-limited us, or misconfigured key). No provider message or API key is ever
included in an error body or a log line.
