# schism-backend — Frontend API Contract (v1)

Base URL: `{BASE}` (configurable in the app; e.g. `http://10.0.2.2:8080` from an Android emulator).
All endpoints are under `/v1`. All request and response bodies are JSON with **camelCase** keys.

## Conventions

- **Money** is always an integer in **minor units** (cents/paise). `1000` = 10.00 in the group's
  currency. Never a float. The frontend formats using the group's `currency`/`currencyCode`.
- **No auth.** A group is reached by its `id`. Identity is chosen client-side (device profile);
  the app passes the acting participant where an endpoint needs it.
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

`GET /health` → `200 {"status":"ok"}` (liveness+DB check; not under `/v1`).

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
`GET /v1/groups?ids=a,b,c` → `200 [ Group, ... ]` (unknown ids silently skipped; no `ids` → `[]`).
Used to render the device's saved groups.

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
- To render "you", pass the device profile's participant id (or name) to the dashboard endpoints.
- Prefer the group `dashboard` endpoint over `stats` for anything richer than a headline number.
