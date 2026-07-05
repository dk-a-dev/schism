# schism-backend

Scalable Go + Postgres backend for the schism app's shared expense-splitting (groups, participants,
expenses, split modes, balances, reimbursements, categories, activity, and dashboard insights).
Exposes a REST API under `/v1`. The on-device parts of the app (SMS parsing, local AI) live in the
Android client — only expenses the user shares reach this service.

## Stack
- Go (`chi` router, `pgx`), Postgres 16, `golang-migrate` (embedded migrations).
- Pure `internal/split` package for the split math (balances, suggested reimbursements, validation)
  and `internal/analytics` for dashboard insights — both DB-free and fully unit-tested.

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
