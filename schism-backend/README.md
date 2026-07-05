# schism-backend

Scalable Go + Postgres backend for the schism app's shared expense-splitting (groups, participants,
expenses, split modes, balances, reimbursements, categories, activity, and dashboard insights).
Exposes a REST API under `/v1`. The on-device parts of the app (SMS parsing, local AI) live in the
Android client — only expenses the user shares reach this service.

## Stack
- Go (`chi` router, `pgx`), Postgres 16, `golang-migrate` (embedded migrations).
- Pure `internal/split` package for the split math (balances, suggested reimbursements, validation)
  and `internal/analytics` for dashboard insights — both DB-free and fully unit-tested.

## Features
- **User identity & auth** — register a user (`POST /v1/users`) and receive a **bearer token**
  (stored sha256-hashed); every request carries `Authorization: Bearer …`. Participant ↔ user
  linking is server-sanitized so it can't be spoofed.
- **Groups & participants** — create / fetch / **update** (participant reconcile: rows with an id are
  updated in place, new rows inserted, absent rows deleted — balances preserved).
- **Expenses** — all four split modes (evenly / shares / percentage / exact amount) and
  reimbursements (settle-up), with **idempotency keys** so retried creates never double-post.
- **Balances & suggested reimbursements**, **activity feed** (actor + title), **group & cross-group
  dashboards / stats**, and **expense categories**.
- Migrations in `internal/store/migrations` (`0001_init` … `0004_user_token`), auto-applied on start.

## Endpoints (`/v1`)
| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/users` | Register a user → id + bearer token |
| `GET`  | `/users/me` | Current authenticated user |
| `GET`  | `/categories` | Expense categories |
| `GET`  | `/dashboard` | Cross-group dashboard |
| `POST` | `/groups` | Create a group |
| `GET`  | `/groups/{id}` · `/details` | Group + participants |
| `PUT`  | `/groups/{id}` | Update group (reconciles participants) |
| `GET`  | `/groups/{id}/balances` · `/activities` · `/stats` · `/dashboard` | Balances, activity, insights |
| `GET`/`POST` | `/groups/{id}/expenses` | List / create expense (create takes `Idempotency-Key`) |
| `GET`/`PUT`/`DELETE` | `/groups/{id}/expenses/{expenseID}` | Read / update / delete an expense |

## Run

```bash
make up        # build + run server and Postgres in Docker (server on :8080)
make logs      # follow server logs
make down      # stop everything
```

Local (Postgres in Docker, server on host):

```bash
make db        # start Postgres only
make run       # migrate + serve on :8080
```

## Test

```bash
make db        # Postgres must be up for the store/api tests
make test      # runs the whole suite
```

Unit tests (`internal/split`, `internal/analytics`, `internal/config`, `internal/id`) need no DB.

## Configuration (env)
- `DATABASE_URL` (required) — e.g. `postgres://schism:schism@127.0.0.1:55432/schism?sslmode=disable`
- `ADDR` — listen address (default `:8080`)
- `LOG_REQUESTS` — `true/1/yes/on` enables per-request access logging (**off by default**; leave
  unset in production)

## API
See [`docs/api-contract.md`](docs/api-contract.md) for the full frontend contract (endpoints,
request/response shapes, money conventions, split-mode encoding, dashboards).

Migrations run automatically on server start.
