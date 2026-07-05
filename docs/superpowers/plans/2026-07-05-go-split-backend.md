# Go Split Backend Implementation Plan (SP1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a scalable Go + Postgres REST service that ports spliit's split domain (schema, `balances.ts`, expense validation) behavior-for-behavior and exposes it to the Android client.

**Architecture:** Stateless Go HTTP service (`chi` router) over Postgres via `pgx`. The split math lives in a pure, dependency-free `internal/split` package proven against golden fixtures captured from spliit's own TypeScript. A thin repository layer (`internal/store`) does SQL; handlers (`internal/api`) map JSON DTOs ↔ domain and call the store + split package.

**Tech Stack:** Go 1.22+, `chi` v5, `pgx` v5, `golang-migrate`, `stretchr/testify`, Postgres 16, Docker Compose for local/CI Postgres.

## Global Constraints

- Module path: `github.com/schism/schism-backend`. Repo root: `/Users/devkeshwani/Developer/schism/schism-backend`.
- Money is **integer minor units** (`int64`) everywhere; never float in storage or JSON.
- No auth in v1: a group is reached by its ID (matches spliit). No user accounts.
- Split math must be **byte-identical** to spliit for the golden fixtures — the `internal/split`
  package has **zero** external dependencies (only std lib) and is never allowed to import `pgx`,
  `chi`, or DTOs.
- `MaxAmount = 10_000_000_00` (minor units). Percentage shares are integers summing to `10000`
  (i.e. ×100). Non-`BY_AMOUNT` shares are the already-scaled integers the client sends.
- IDs are opaque strings generated server-side (nanoid-style, `[A-Za-z0-9_-]`, length 12), matching
  spliit's `randomId()` shape.
- All endpoints are prefixed `/v1`. JSON keys are camelCase.

---

## File Structure

```
schism-backend/
  go.mod
  docker-compose.yml            # postgres:16 for local + test
  Makefile                      # up, migrate, test, run
  cmd/server/main.go            # wiring: config → pool → migrate → router → http.Server
  internal/
    config/config.go            # env → Config
    id/id.go                    # randomId() port
    split/
      types.go                  # SplitMode, PaidFor, Expense, Balance, Balances, Reimbursement
      balances.go               # GetBalances, GetSuggestedReimbursements, GetPublicBalances
      validate.go               # ValidateExpense + sentinel errors
      balances_test.go          # golden-fixture parity tests
      validate_test.go
      testdata/*.golden.json    # captured from spliit
    store/
      migrations/0001_init.up.sql / .down.sql
      pool.go                   # pgx pool + RunMigrations
      groups.go                 # group + participant CRUD
      expenses.go               # expense + paidFor CRUD, getGroupExpenses
      categories.go             # seed + list
      activities.go             # logActivity + list
    api/
      router.go                 # chi router, routes → handlers
      errors.go                 # apiError, writeJSON, writeErr
      dto.go                    # request/response DTOs + domain mappers
      groups.go                 # group handlers
      expenses.go               # expense handlers
      balances.go               # balances handler
      categories.go             # categories handler
      activities.go             # activities handler
      stats.go                  # stats handler
  spliit-golden/gen-golden.ts   # (added to spliit repo) emits testdata from real balances.ts
```

---

## Task 1: Repo scaffold + Postgres + health endpoint

**Files:**
- Create: `schism-backend/go.mod`, `schism-backend/docker-compose.yml`, `schism-backend/Makefile`
- Create: `schism-backend/internal/config/config.go`
- Create: `schism-backend/internal/store/pool.go`
- Create: `schism-backend/cmd/server/main.go`
- Test: `schism-backend/internal/config/config_test.go`

**Interfaces:**
- Produces: `config.Load() (config.Config, error)` with fields `Addr string`, `DatabaseURL string`;
  `store.NewPool(ctx, url) (*pgxpool.Pool, error)`.

- [ ] **Step 1: Init module and dependencies**

```bash
cd /Users/devkeshwani/Developer/schism && mkdir -p splitd && cd schism-backend
go mod init github.com/schism/schism-backend
go get github.com/go-chi/chi/v5@latest github.com/jackc/pgx/v5@latest \
  github.com/golang-migrate/migrate/v4@latest github.com/stretchr/testify@latest
```

- [ ] **Step 2: docker-compose for Postgres**

Create `schism-backend/docker-compose.yml`:

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_USER: splitd
      POSTGRES_PASSWORD: splitd
      POSTGRES_DB: splitd
    ports: ["5433:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U splitd"]
      interval: 2s
      timeout: 3s
      retries: 20
```

Create `schism-backend/Makefile`:

```makefile
DB_URL ?= postgres://schism:schism@127.0.0.1:55432/schism?sslmode=disable
up: ; docker compose up -d db
down: ; docker compose down
test: ; DATABASE_URL="$(DB_URL)" go test ./...
run: ; DATABASE_URL="$(DB_URL)" ADDR=":8080" go run ./cmd/server
```

- [ ] **Step 3: Write the failing config test**

Create `schism-backend/internal/config/config_test.go`:

```go
package config

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("ADDR", "")
	c, err := Load()
	require.NoError(t, err)
	require.Equal(t, ":8080", c.Addr)
	require.Equal(t, "postgres://x", c.DatabaseURL)
}

func TestLoadMissingDBURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	_, err := Load()
	require.Error(t, err)
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd schism-backend && go test ./internal/config/...`
Expected: FAIL (package `config` has no `Load`).

- [ ] **Step 5: Implement config**

Create `schism-backend/internal/config/config.go`:

```go
package config

import (
	"errors"
	"os"
)

type Config struct {
	Addr        string
	DatabaseURL string
}

func Load() (Config, error) {
	c := Config{
		Addr:        os.Getenv("ADDR"),
		DatabaseURL: os.Getenv("DATABASE_URL"),
	}
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	return c, nil
}
```

- [ ] **Step 6: Implement pool**

Create `schism-backend/internal/store/pool.go`:

```go
package store

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
)

func NewPool(ctx context.Context, url string) (*pgxpool.Pool, error) {
	return pgxpool.New(ctx, url)
}
```

- [ ] **Step 7: Implement server main with /health**

Create `schism-backend/cmd/server/main.go`:

```go
package main

import (
	"context"
	"log"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/config"
	"github.com/schism/schism-backend/internal/store"
)

func main() {
	ctx := context.Background()
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	pool, err := store.NewPool(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()

	r := chi.NewRouter()
	r.Get("/health", func(w http.ResponseWriter, req *http.Request) {
		if err := pool.Ping(req.Context()); err != nil {
			http.Error(w, "db down", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})

	log.Printf("listening on %s", cfg.Addr)
	log.Fatal(http.ListenAndServe(cfg.Addr, r))
}
```

- [ ] **Step 8: Run config tests (green) and build**

Run: `cd schism-backend && go test ./internal/config/... && go build ./...`
Expected: PASS, build succeeds.

- [ ] **Step 9: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git init -q && git add -A
git commit -q -m "feat(splitd): scaffold Go service, config, pg pool, health endpoint"
```

---

## Task 2: Database schema (port Prisma models)

**Files:**
- Create: `schism-backend/internal/store/migrations/0001_init.up.sql`
- Create: `schism-backend/internal/store/migrations/0001_init.down.sql`
- Modify: `schism-backend/internal/store/pool.go` (add `RunMigrations`)
- Test: `schism-backend/internal/store/migrate_test.go`

**Interfaces:**
- Produces: `store.RunMigrations(url string) error` (idempotent, uses embedded migrations).

- [ ] **Step 1: Write up migration**

Create `schism-backend/internal/store/migrations/0001_init.up.sql`:

```sql
CREATE TABLE groups (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  information   TEXT,
  currency      TEXT NOT NULL DEFAULT '$',
  currency_code TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE participants (
  id       TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  name     TEXT NOT NULL
);
CREATE INDEX idx_participants_group ON participants(group_id);

CREATE TABLE categories (
  id       INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  grouping TEXT NOT NULL,
  name     TEXT NOT NULL
);

CREATE TABLE expenses (
  id                TEXT PRIMARY KEY,
  group_id          TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  expense_date      DATE NOT NULL DEFAULT CURRENT_DATE,
  title             TEXT NOT NULL,
  category_id       INT NOT NULL DEFAULT 0,
  amount            BIGINT NOT NULL,
  original_amount   BIGINT,
  original_currency TEXT,
  conversion_rate   NUMERIC,
  paid_by_id        TEXT NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
  is_reimbursement  BOOLEAN NOT NULL DEFAULT false,
  split_mode        TEXT NOT NULL DEFAULT 'EVENLY',
  notes             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenses_group ON expenses(group_id);

CREATE TABLE expense_paid_for (
  expense_id     TEXT NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  participant_id TEXT NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
  shares         INT NOT NULL DEFAULT 1,
  PRIMARY KEY (expense_id, participant_id)
);

CREATE TABLE activities (
  id             TEXT PRIMARY KEY,
  group_id       TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  time           TIMESTAMPTZ NOT NULL DEFAULT now(),
  activity_type  TEXT NOT NULL,
  participant_id TEXT,
  expense_id     TEXT,
  data           TEXT
);
CREATE INDEX idx_activities_group ON activities(group_id);

-- idempotency for expense creation (SP3 push-to-split)
CREATE TABLE expense_idempotency (
  key        TEXT PRIMARY KEY,
  expense_id TEXT NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Create `schism-backend/internal/store/migrations/0001_init.down.sql`:

```sql
DROP TABLE IF EXISTS expense_idempotency;
DROP TABLE IF EXISTS activities;
DROP TABLE IF EXISTS expense_paid_for;
DROP TABLE IF EXISTS expenses;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS participants;
DROP TABLE IF EXISTS groups;
```

- [ ] **Step 2: Add embedded migrations + RunMigrations**

Append to `schism-backend/internal/store/pool.go`:

```go
import (
	"embed"

	"github.com/golang-migrate/migrate/v4"
	"github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

func RunMigrations(url string) error {
	src, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return err
	}
	m, err := migrate.NewWithSourceInstance("iofs", src, url)
	if err != nil {
		return err
	}
	if err := m.Up(); err != nil && err != migrate.ErrNoChange {
		return err
	}
	return nil
}
```

(Keep existing `NewPool` and imports; add the new imports to the file's import block.)

- [ ] **Step 3: Write the failing migration test**

Create `schism-backend/internal/store/migrate_test.go`:

```go
package store

import (
	"context"
	"os"
	"testing"

	"github.com/stretchr/testify/require"
)

func testURL(t *testing.T) string {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		t.Skip("DATABASE_URL not set")
	}
	return url
}

func TestRunMigrationsCreatesTables(t *testing.T) {
	url := testURL(t)
	require.NoError(t, RunMigrations(url))

	pool, err := NewPool(context.Background(), url)
	require.NoError(t, err)
	defer pool.Close()

	var n int
	err = pool.QueryRow(context.Background(),
		`SELECT count(*) FROM information_schema.tables
		 WHERE table_name IN ('groups','participants','expenses','expense_paid_for','categories','activities')`).
		Scan(&n)
	require.NoError(t, err)
	require.Equal(t, 6, n)
}
```

- [ ] **Step 4: Run migration test (green)**

Run: `cd schism-backend && make up && sleep 3 && make test ARGS=./internal/store/...`
Or: `DATABASE_URL="postgres://schism:schism@127.0.0.1:55432/schism?sslmode=disable" go test ./internal/store/...`
Expected: PASS (6 tables present).

- [ ] **Step 5: Wire RunMigrations into main**

In `cmd/server/main.go`, after `config.Load()` and before `NewPool`, add:

```go
	if err := store.RunMigrations(cfg.DatabaseURL); err != nil {
		log.Fatal(err)
	}
```

- [ ] **Step 6: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A
git commit -q -m "feat(splitd): initial schema migrations ported from spliit prisma"
```

---

## Task 3: ID generator + split domain types

**Files:**
- Create: `schism-backend/internal/id/id.go`
- Create: `schism-backend/internal/split/types.go`
- Test: `schism-backend/internal/id/id_test.go`

**Interfaces:**
- Produces: `id.New() string` (12 chars, `[A-Za-z0-9_-]`).
- Produces types: `split.SplitMode` (`Evenly`,`ByShares`,`ByPercentage`,`ByAmount`),
  `split.PaidFor{ParticipantID string; Shares int64}`,
  `split.Expense{ID string; Amount int64; PaidByID string; PaidFor []PaidFor; SplitMode SplitMode}`,
  `split.Balance{Paid,PaidFor,Total int64}`, `split.Balances map[string]*Balance`,
  `split.Reimbursement{From,To string; Amount int64}`.

- [ ] **Step 1: Write failing id test**

Create `schism-backend/internal/id/id_test.go`:

```go
package id

import (
	"regexp"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestNewFormat(t *testing.T) {
	re := regexp.MustCompile(`^[A-Za-z0-9_-]{12}$`)
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		v := New()
		require.Regexp(t, re, v)
		require.False(t, seen[v], "duplicate id %s", v)
		seen[v] = true
	}
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/id/...`
Expected: FAIL (no `New`).

- [ ] **Step 3: Implement id**

Create `schism-backend/internal/id/id.go`:

```go
package id

import "crypto/rand"

const alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-"

func New() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = alphabet[int(b[i])&63]
	}
	return string(b)
}
```

- [ ] **Step 4: Implement split types**

Create `schism-backend/internal/split/types.go`:

```go
package split

type SplitMode string

const (
	Evenly       SplitMode = "EVENLY"
	ByShares     SplitMode = "BY_SHARES"
	ByPercentage SplitMode = "BY_PERCENTAGE"
	ByAmount     SplitMode = "BY_AMOUNT"
)

type PaidFor struct {
	ParticipantID string
	Shares        int64
}

type Expense struct {
	ID        string
	Amount    int64
	PaidByID  string
	PaidFor   []PaidFor
	SplitMode SplitMode
}

type Balance struct {
	Paid    int64 `json:"paid"`
	PaidFor int64 `json:"paidFor"`
	Total   int64 `json:"total"`
}

type Balances map[string]*Balance

type Reimbursement struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Amount int64  `json:"amount"`
}
```

- [ ] **Step 5: Run id test (green) + build**

Run: `cd schism-backend && go test ./internal/id/... && go build ./...`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A
git commit -q -m "feat(splitd): id generator and split domain types"
```

---

## Task 4: Golden fixtures from spliit + GetBalances port

**Files:**
- Create: `spliit/spliit-golden/gen-golden.ts` (in the spliit repo)
- Create: `schism-backend/internal/split/testdata/*.golden.json`
- Create: `schism-backend/internal/split/balances.go`
- Test: `schism-backend/internal/split/balances_test.go`

**Interfaces:**
- Produces: `split.GetBalances(expenses []Expense) Balances`.

- [ ] **Step 1: Write the golden generator in spliit (uses the REAL balances.ts)**

Create `spliit/spliit-golden/gen-golden.ts`:

```ts
import { getBalances } from '../src/lib/balances'
import { writeFileSync, mkdirSync } from 'fs'
import { join } from 'path'

// Minimal expense shape getBalances reads: amount, paidBy.id, paidFor[].participant.id + shares, splitMode
type E = {
  amount: number
  paidBy: { id: string }
  paidFor: { participant: { id: string }; shares: number }[]
  splitMode: 'EVENLY' | 'BY_SHARES' | 'BY_PERCENTAGE' | 'BY_AMOUNT'
}

const cases: Record<string, E[]> = {
  evenly_two: [
    { amount: 1000, paidBy: { id: 'a' }, splitMode: 'EVENLY',
      paidFor: [{ participant: { id: 'a' }, shares: 100 }, { participant: { id: 'b' }, shares: 100 }] },
  ],
  evenly_three_odd: [
    { amount: 1000, paidBy: { id: 'a' }, splitMode: 'EVENLY',
      paidFor: [{ participant: { id: 'a' }, shares: 100 }, { participant: { id: 'b' }, shares: 100 }, { participant: { id: 'c' }, shares: 100 }] },
  ],
  by_shares: [
    { amount: 900, paidBy: { id: 'a' }, splitMode: 'BY_SHARES',
      paidFor: [{ participant: { id: 'a' }, shares: 100 }, { participant: { id: 'b' }, shares: 200 }] },
  ],
  by_percentage: [
    { amount: 1000, paidBy: { id: 'a' }, splitMode: 'BY_PERCENTAGE',
      paidFor: [{ participant: { id: 'a' }, shares: 3000 }, { participant: { id: 'b' }, shares: 7000 }] },
  ],
  by_amount: [
    { amount: 1000, paidBy: { id: 'a' }, splitMode: 'BY_AMOUNT',
      paidFor: [{ participant: { id: 'a' }, shares: 300 }, { participant: { id: 'b' }, shares: 700 }] },
  ],
  multi_expense: [
    { amount: 3000, paidBy: { id: 'a' }, splitMode: 'EVENLY',
      paidFor: [{ participant: { id: 'a' }, shares: 100 }, { participant: { id: 'b' }, shares: 100 }, { participant: { id: 'c' }, shares: 100 }] },
    { amount: 1500, paidBy: { id: 'b' }, splitMode: 'EVENLY',
      paidFor: [{ participant: { id: 'b' }, shares: 100 }, { participant: { id: 'c' }, shares: 100 }] },
  ],
}

const outDir = join(__dirname, '..', '..', 'splitd', 'internal', 'split', 'testdata')
mkdirSync(outDir, { recursive: true })
for (const [name, expenses] of Object.entries(cases)) {
  const balances = getBalances(expenses as any)
  writeFileSync(join(outDir, `${name}.golden.json`),
    JSON.stringify({ expenses, balances }, null, 2))
}
console.log('wrote', Object.keys(cases).length, 'golden files')
```

- [ ] **Step 2: Generate the golden files**

Run:
```bash
cd /Users/devkeshwani/Developer/schism/spliit && npx tsx spliit-golden/gen-golden.ts
```
Expected: prints `wrote 6 golden files`; files appear under `schism-backend/internal/split/testdata/`.

- [ ] **Step 3: Write the failing GetBalances test (loads golden files)**

Create `schism-backend/internal/split/balances_test.go`:

```go
package split

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"
)

type goldenExpense struct {
	Amount  int64 `json:"amount"`
	PaidBy  struct{ ID string } `json:"paidBy"`
	PaidFor []struct {
		Participant struct{ ID string } `json:"participant"`
		Shares      int64               `json:"shares"`
	} `json:"paidFor"`
	SplitMode SplitMode `json:"splitMode"`
}

type goldenFile struct {
	Expenses []goldenExpense    `json:"expenses"`
	Balances map[string]Balance `json:"balances"`
}

func loadGolden(t *testing.T, name string) goldenFile {
	b, err := os.ReadFile(filepath.Join("testdata", name))
	require.NoError(t, err)
	var g goldenFile
	require.NoError(t, json.Unmarshal(b, &g))
	return g
}

func (g goldenFile) toExpenses() []Expense {
	out := make([]Expense, len(g.Expenses))
	for i, e := range g.Expenses {
		pf := make([]PaidFor, len(e.PaidFor))
		for j, p := range e.PaidFor {
			pf[j] = PaidFor{ParticipantID: p.Participant.ID, Shares: p.Shares}
		}
		out[i] = Expense{Amount: e.Amount, PaidByID: e.PaidBy.ID, PaidFor: pf, SplitMode: e.SplitMode}
	}
	return out
}

func TestGetBalancesParity(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("testdata", "*.golden.json"))
	require.NoError(t, err)
	require.NotEmpty(t, files)
	for _, f := range files {
		name := filepath.Base(f)
		t.Run(name, func(t *testing.T) {
			g := loadGolden(t, name)
			got := GetBalances(g.toExpenses())
			for id, want := range g.Balances {
				require.NotNil(t, got[id], "missing participant %s", id)
				require.Equal(t, want.Paid, got[id].Paid, "paid[%s]", id)
				require.Equal(t, want.PaidFor, got[id].PaidFor, "paidFor[%s]", id)
				require.Equal(t, want.Total, got[id].Total, "total[%s]", id)
			}
			require.Len(t, got, len(g.Balances))
		})
	}
}
```

- [ ] **Step 4: Run to verify fail**

Run: `cd schism-backend && go test ./internal/split/...`
Expected: FAIL (no `GetBalances`).

- [ ] **Step 5: Implement GetBalances (port of balances.ts)**

Create `schism-backend/internal/split/balances.go`:

```go
package split

import "math"

// roundHalfUp mirrors JS Math.round for non-negative values (ties go up).
func roundHalfUp(x float64) int64 {
	return int64(math.Floor(x + 0.5))
}

func GetBalances(expenses []Expense) Balances {
	balances := Balances{}
	paidForFloat := map[string]float64{}
	ensure := func(id string) {
		if balances[id] == nil {
			balances[id] = &Balance{}
		}
	}

	for _, e := range expenses {
		ensure(e.PaidByID)
		balances[e.PaidByID].Paid += e.Amount

		var totalShares int64
		for _, pf := range e.PaidFor {
			totalShares += pf.Shares
		}

		remaining := float64(e.Amount)
		n := len(e.PaidFor)
		for i, pf := range e.PaidFor {
			ensure(pf.ParticipantID)
			isLast := i == n-1

			var shares, tShares float64
			switch e.SplitMode {
			case Evenly:
				shares, tShares = 1, float64(n)
			default: // BY_SHARES, BY_PERCENTAGE, BY_AMOUNT
				shares, tShares = float64(pf.Shares), float64(totalShares)
			}

			var divided float64
			if isLast {
				divided = remaining
			} else {
				divided = float64(e.Amount) * shares / tShares
			}
			remaining -= divided
			paidForFloat[pf.ParticipantID] += divided
		}
	}

	for id := range balances {
		balances[id].PaidFor = roundHalfUp(paidForFloat[id])
		balances[id].Total = balances[id].Paid - balances[id].PaidFor
	}
	return balances
}
```

- [ ] **Step 6: Run parity test (green)**

Run: `cd schism-backend && go test ./internal/split/... -run TestGetBalancesParity -v`
Expected: PASS for all golden files.

- [ ] **Step 7: Commit (both repos)**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): GetBalances port with spliit golden-fixture parity"
cd /Users/devkeshwani/Developer/schism/spliit && git add spliit-golden && \
git commit -q -m "test: golden fixture generator for splitd parity"
```

---

## Task 5: Reimbursements + public balances

**Files:**
- Modify: `schism-backend/internal/split/balances.go` (add functions)
- Modify: `spliit/spliit-golden/gen-golden.ts` (also emit reimbursements + publicBalances)
- Modify: `schism-backend/internal/split/balances_test.go` (assert reimbursements/public)

**Interfaces:**
- Produces: `split.GetSuggestedReimbursements(b Balances) []Reimbursement`,
  `split.GetPublicBalances(r []Reimbursement) Balances`.

- [ ] **Step 1: Extend the golden generator**

In `spliit/spliit-golden/gen-golden.ts`, replace the import and write block:

```ts
import { getBalances, getSuggestedReimbursements, getPublicBalances } from '../src/lib/balances'
```

```ts
for (const [name, expenses] of Object.entries(cases)) {
  const balances = getBalances(expenses as any)
  const reimbursements = getSuggestedReimbursements(balances)
  const publicBalances = getPublicBalances(reimbursements)
  writeFileSync(join(outDir, `${name}.golden.json`),
    JSON.stringify({ expenses, balances, reimbursements, publicBalances }, null, 2))
}
```

Regenerate:
```bash
cd /Users/devkeshwani/Developer/schism/spliit && npx tsx spliit-golden/gen-golden.ts
```

- [ ] **Step 2: Extend the test to assert reimbursements + public balances**

In `balances_test.go`, add to `goldenFile`:

```go
	Reimbursements []Reimbursement    `json:"reimbursements"`
	PublicBalances map[string]Balance `json:"publicBalances"`
```

Add a new test:

```go
func TestReimbursementsParity(t *testing.T) {
	files, _ := filepath.Glob(filepath.Join("testdata", "*.golden.json"))
	for _, f := range files {
		name := filepath.Base(f)
		t.Run(name, func(t *testing.T) {
			g := loadGolden(t, name)
			bal := GetBalances(g.toExpenses())
			reimb := GetSuggestedReimbursements(bal)
			require.Equal(t, g.Reimbursements, reimb)

			pub := GetPublicBalances(reimb)
			for id, want := range g.PublicBalances {
				require.NotNil(t, pub[id])
				require.Equal(t, want, *pub[id], "public[%s]", id)
			}
			require.Len(t, pub, len(g.PublicBalances))
		})
	}
}
```

- [ ] **Step 3: Run to verify fail**

Run: `cd schism-backend && go test ./internal/split/... -run TestReimbursementsParity`
Expected: FAIL (no `GetSuggestedReimbursements`).

- [ ] **Step 4: Implement reimbursements + public balances**

Append to `schism-backend/internal/split/balances.go`:

```go
import "sort" // add to existing import block alongside "math"

type balanceTotal struct {
	participantID string
	total         int64
}

// compareForReimbursements: positives before negatives; else by participantID asc.
func compareForReimbursements(a, b balanceTotal) int {
	if a.total > 0 && b.total < 0 {
		return -1
	}
	if b.total > 0 && a.total < 0 {
		return 1
	}
	if a.participantID < b.participantID {
		return -1
	}
	return 1
}

func GetSuggestedReimbursements(balances Balances) []Reimbursement {
	arr := make([]balanceTotal, 0, len(balances))
	for id, b := range balances {
		if b.Total != 0 {
			arr = append(arr, balanceTotal{id, b.Total})
		}
	}
	sort.SliceStable(arr, func(i, j int) bool {
		return compareForReimbursements(arr[i], arr[j]) < 0
	})

	reimb := []Reimbursement{}
	for len(arr) > 1 {
		first := 0
		last := len(arr) - 1
		amount := arr[first].total + arr[last].total
		if arr[first].total > -arr[last].total {
			reimb = append(reimb, Reimbursement{From: arr[last].participantID, To: arr[first].participantID, Amount: -arr[last].total})
			arr[first].total = amount
			arr = arr[:last] // pop last
		} else {
			reimb = append(reimb, Reimbursement{From: arr[last].participantID, To: arr[first].participantID, Amount: arr[first].total})
			arr[last].total = amount
			arr = arr[1:] // shift first
		}
	}

	out := []Reimbursement{}
	for _, r := range reimb {
		if r.Amount != 0 {
			out = append(out, r)
		}
	}
	return out
}

func GetPublicBalances(reimbursements []Reimbursement) Balances {
	balances := Balances{}
	ensure := func(id string) {
		if balances[id] == nil {
			balances[id] = &Balance{}
		}
	}
	for _, r := range reimbursements {
		ensure(r.From)
		ensure(r.To)
		balances[r.From].PaidFor += r.Amount
		balances[r.From].Total -= r.Amount
		balances[r.To].Paid += r.Amount
		balances[r.To].Total += r.Amount
	}
	return balances
}
```

- [ ] **Step 5: Run parity tests (green)**

Run: `cd schism-backend && go test ./internal/split/...`
Expected: PASS (both balances and reimbursements parity).

- [ ] **Step 6: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): suggested reimbursements + public balances port"
cd /Users/devkeshwani/Developer/schism/spliit && git add spliit-golden && \
git commit -q -m "test: extend golden fixtures with reimbursements"
```

---

## Task 6: Expense validation (port schemas.ts refinements)

**Files:**
- Create: `schism-backend/internal/split/validate.go`
- Test: `schism-backend/internal/split/validate_test.go`

**Interfaces:**
- Produces: `split.ValidateExpense(e Expense) error`; sentinel errors `ErrAmountZero`,
  `ErrAmountTooLarge`, `ErrPaidForMin`, `ErrZeroShares`, `ErrAmountSum`, `ErrPercentageSum`.
- Consumes: `split.Expense`, `split.SplitMode` (Task 3).

- [ ] **Step 1: Write failing validation tests**

Create `schism-backend/internal/split/validate_test.go`:

```go
package split

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func expenseWith(mode SplitMode, amount int64, pf ...PaidFor) Expense {
	return Expense{Amount: amount, PaidByID: "a", PaidFor: pf, SplitMode: mode}
}

func TestValidateOK(t *testing.T) {
	require.NoError(t, ValidateExpense(expenseWith(Evenly, 1000,
		PaidFor{"a", 100}, PaidFor{"b", 100})))
}

func TestValidateZeroAmount(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 0, PaidFor{"a", 100})), ErrAmountZero)
}

func TestValidateTooLarge(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 10_000_000_01, PaidFor{"a", 100})), ErrAmountTooLarge)
}

func TestValidateNoPaidFor(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 1000)), ErrPaidForMin)
}

func TestValidateZeroShares(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByShares, 1000, PaidFor{"a", 0})), ErrZeroShares)
}

func TestValidateByAmountSum(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByAmount, 1000, PaidFor{"a", 300}, PaidFor{"b", 600})), ErrAmountSum)
	require.NoError(t, ValidateExpense(expenseWith(ByAmount, 1000, PaidFor{"a", 300}, PaidFor{"b", 700})))
}

func TestValidateByPercentageSum(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByPercentage, 1000, PaidFor{"a", 3000}, PaidFor{"b", 6000})), ErrPercentageSum)
	require.NoError(t, ValidateExpense(expenseWith(ByPercentage, 1000, PaidFor{"a", 3000}, PaidFor{"b", 7000})))
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/split/... -run TestValidate`
Expected: FAIL (no `ValidateExpense`).

- [ ] **Step 3: Implement validation**

Create `schism-backend/internal/split/validate.go`:

```go
package split

import "errors"

const MaxAmount int64 = 10_000_000_00

var (
	ErrAmountZero     = errors.New("amount must not be zero")
	ErrAmountTooLarge = errors.New("amount exceeds maximum")
	ErrPaidForMin     = errors.New("at least one paidFor is required")
	ErrZeroShares     = errors.New("shares must be greater than zero")
	ErrAmountSum      = errors.New("paidFor amounts must sum to expense amount")
	ErrPercentageSum  = errors.New("paidFor percentages must sum to 100%")
)

func ValidateExpense(e Expense) error {
	if e.Amount == 0 {
		return ErrAmountZero
	}
	if e.Amount > MaxAmount {
		return ErrAmountTooLarge
	}
	if len(e.PaidFor) < 1 {
		return ErrPaidForMin
	}
	for _, pf := range e.PaidFor {
		if pf.Shares <= 0 {
			return ErrZeroShares
		}
	}
	switch e.SplitMode {
	case ByAmount:
		var sum int64
		for _, pf := range e.PaidFor {
			sum += pf.Shares
		}
		if sum != e.Amount {
			return ErrAmountSum
		}
	case ByPercentage:
		var sum int64
		for _, pf := range e.PaidFor {
			sum += pf.Shares
		}
		if sum != 10000 {
			return ErrPercentageSum
		}
	}
	return nil
}
```

- [ ] **Step 4: Run validation tests (green)**

Run: `cd schism-backend && go test ./internal/split/...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): expense validation ported from spliit schemas"
```

---

## Task 7: Store — groups & participants repository

**Files:**
- Create: `schism-backend/internal/store/groups.go`
- Test: `schism-backend/internal/store/groups_test.go`

**Interfaces:**
- Produces on `*Store`:
  - `NewStore(pool *pgxpool.Pool) *Store`
  - `CreateGroup(ctx, in GroupInput) (Group, error)`
  - `GetGroup(ctx, id string) (*Group, error)` (nil if not found)
  - `UpdateGroup(ctx, id string, in GroupInput) (*Group, error)`
  - `ListGroups(ctx, ids []string) ([]Group, error)`
- Produces types `GroupInput{Name, Information, Currency, CurrencyCode string; Participants []ParticipantInput}`,
  `ParticipantInput{ID *string; Name string}`, `Group{ID,Name,Information,Currency,CurrencyCode string; CreatedAt time.Time; Participants []Participant}`,
  `Participant{ID, GroupID, Name string}`.

- [ ] **Step 1: Write failing groups store test**

Create `schism-backend/internal/store/groups_test.go`:

```go
package store

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"
)

func newTestStore(t *testing.T) *Store {
	url := testURL(t)
	require.NoError(t, RunMigrations(url))
	pool, err := NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	return NewStore(pool)
}

func TestCreateAndGetGroup(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{
		Name: "Trip", Currency: "$", CurrencyCode: "USD",
		Participants: []ParticipantInput{{Name: "Alice"}, {Name: "Bob"}},
	})
	require.NoError(t, err)
	require.NotEmpty(t, g.ID)
	require.Len(t, g.Participants, 2)

	got, err := s.GetGroup(ctx, g.ID)
	require.NoError(t, err)
	require.Equal(t, "Trip", got.Name)
	require.Len(t, got.Participants, 2)

	missing, err := s.GetGroup(ctx, "does-not-ex")
	require.NoError(t, err)
	require.Nil(t, missing)
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/store/... -run TestCreateAndGetGroup`
Expected: FAIL (no `Store`/`CreateGroup`).

- [ ] **Step 3: Implement groups store**

Create `schism-backend/internal/store/groups.go`:

```go
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/schism/schism-backend/internal/id"
)

type Store struct{ pool *pgxpool.Pool }

func NewStore(pool *pgxpool.Pool) *Store { return &Store{pool: pool} }

type ParticipantInput struct {
	ID   *string
	Name string
}
type GroupInput struct {
	Name         string
	Information   string
	Currency     string
	CurrencyCode string
	Participants []ParticipantInput
}
type Participant struct {
	ID      string `json:"id"`
	GroupID string `json:"groupId"`
	Name    string `json:"name"`
}
type Group struct {
	ID           string        `json:"id"`
	Name         string        `json:"name"`
	Information  string        `json:"information"`
	Currency     string        `json:"currency"`
	CurrencyCode string        `json:"currencyCode"`
	CreatedAt    time.Time     `json:"createdAt"`
	Participants []Participant `json:"participants"`
}

func (s *Store) CreateGroup(ctx context.Context, in GroupInput) (Group, error) {
	gid := id.New()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Group{}, err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`INSERT INTO groups (id, name, information, currency, currency_code)
		 VALUES ($1,$2,$3,$4,$5)`,
		gid, in.Name, nullify(in.Information), in.Currency, nullify(in.CurrencyCode)); err != nil {
		return Group{}, err
	}
	for _, p := range in.Participants {
		if _, err := tx.Exec(ctx,
			`INSERT INTO participants (id, group_id, name) VALUES ($1,$2,$3)`,
			id.New(), gid, p.Name); err != nil {
			return Group{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return Group{}, err
	}
	g, err := s.GetGroup(ctx, gid)
	if err != nil {
		return Group{}, err
	}
	return *g, nil
}

func (s *Store) GetGroup(ctx context.Context, gid string) (*Group, error) {
	var g Group
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, COALESCE(information,''), currency, COALESCE(currency_code,''), created_at
		 FROM groups WHERE id=$1`, gid).
		Scan(&g.ID, &g.Name, &g.Information, &g.Currency, &g.CurrencyCode, &g.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	parts, err := s.participants(ctx, gid)
	if err != nil {
		return nil, err
	}
	g.Participants = parts
	return &g, nil
}

func (s *Store) participants(ctx context.Context, gid string) ([]Participant, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, group_id, name FROM participants WHERE group_id=$1 ORDER BY name`, gid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Participant{}
	for rows.Next() {
		var p Participant
		if err := rows.Scan(&p.ID, &p.GroupID, &p.Name); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ListGroups(ctx context.Context, ids []string) ([]Group, error) {
	out := []Group{}
	for _, gid := range ids {
		g, err := s.GetGroup(ctx, gid)
		if err != nil {
			return nil, err
		}
		if g != nil {
			out = append(out, *g)
		}
	}
	return out, nil
}

// UpdateGroup updates name/info/currency and reconciles participants:
// participants with an ID are updated, without an ID are inserted,
// and existing participants absent from the input are deleted.
func (s *Store) UpdateGroup(ctx context.Context, gid string, in GroupInput) (*Group, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	ct, err := tx.Exec(ctx,
		`UPDATE groups SET name=$2, information=$3, currency=$4, currency_code=$5 WHERE id=$1`,
		gid, in.Name, nullify(in.Information), in.Currency, nullify(in.CurrencyCode))
	if err != nil {
		return nil, err
	}
	if ct.RowsAffected() == 0 {
		return nil, nil
	}

	keep := map[string]bool{}
	for _, p := range in.Participants {
		if p.ID != nil {
			keep[*p.ID] = true
			if _, err := tx.Exec(ctx,
				`UPDATE participants SET name=$2 WHERE id=$1 AND group_id=$3`, *p.ID, p.Name, gid); err != nil {
				return nil, err
			}
		} else {
			newID := id.New()
			keep[newID] = true
			if _, err := tx.Exec(ctx,
				`INSERT INTO participants (id, group_id, name) VALUES ($1,$2,$3)`, newID, gid, p.Name); err != nil {
				return nil, err
			}
		}
	}
	existing, err := func() ([]string, error) {
		rows, err := tx.Query(ctx, `SELECT id FROM participants WHERE group_id=$1`, gid)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		var ids []string
		for rows.Next() {
			var pid string
			if err := rows.Scan(&pid); err != nil {
				return nil, err
			}
			ids = append(ids, pid)
		}
		return ids, rows.Err()
	}()
	if err != nil {
		return nil, err
	}
	for _, pid := range existing {
		if !keep[pid] {
			if _, err := tx.Exec(ctx, `DELETE FROM participants WHERE id=$1`, pid); err != nil {
				return nil, err
			}
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return s.GetGroup(ctx, gid)
}

func nullify(s string) any {
	if s == "" {
		return nil
	}
	return s
}
```

- [ ] **Step 4: Run groups store test (green)**

Run: `cd schism-backend && go test ./internal/store/... -run TestCreateAndGetGroup`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): groups & participants repository"
```

---

## Task 8: Store — expenses repository + getGroupExpenses

**Files:**
- Create: `schism-backend/internal/store/expenses.go`
- Test: `schism-backend/internal/store/expenses_test.go`

**Interfaces:**
- Produces on `*Store`:
  - `CreateExpense(ctx, groupID string, in ExpenseInput, idemKey string) (Expense, error)`
  - `GetExpense(ctx, groupID, expenseID string) (*Expense, error)`
  - `ListExpenses(ctx, groupID string) ([]Expense, error)`
  - `UpdateExpense(ctx, groupID, expenseID string, in ExpenseInput) (*Expense, error)`
  - `DeleteExpense(ctx, groupID, expenseID string) (bool, error)`
  - `SplitExpenses(ctx, groupID string) ([]split.Expense, error)` (adapter for the split package)
- Produces types `ExpenseInput{Title string; Amount int64; CategoryID int; ExpenseDate time.Time;
  PaidByID string; SplitMode string; IsReimbursement bool; Notes string; PaidFor []PaidForInput}`,
  `PaidForInput{ParticipantID string; Shares int64}`,
  `Expense{ID, GroupID, Title string; Amount int64; CategoryID int; ExpenseDate time.Time;
  PaidByID string; SplitMode string; IsReimbursement bool; Notes string; CreatedAt time.Time;
  PaidFor []PaidForRow}`, `PaidForRow{ParticipantID string; Shares int64}`.
- Consumes: `split.Expense`, `split.PaidFor`, `split.SplitMode` (Task 3).

- [ ] **Step 1: Write failing expenses store test**

Create `schism-backend/internal/store/expenses_test.go`:

```go
package store

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestCreateAndListExpense(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}, {Name: "B"}}})
	require.NoError(t, err)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	e, err := s.CreateExpense(ctx, g.ID, ExpenseInput{
		Title: "Dinner", Amount: 1000, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY",
		PaidFor: []PaidForInput{{a, 100}, {b, 100}},
	}, "")
	require.NoError(t, err)
	require.NotEmpty(t, e.ID)
	require.Len(t, e.PaidFor, 2)

	list, err := s.ListExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, list, 1)

	se, err := s.SplitExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, se, 1)
	require.Equal(t, int64(1000), se[0].Amount)
	require.Equal(t, a, se[0].PaidByID)
}

func TestCreateExpenseIdempotent(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, _ := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}, {Name: "B"}}})
	a, b := g.Participants[0].ID, g.Participants[1].ID
	in := ExpenseInput{Title: "X", Amount: 500, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}}

	e1, err := s.CreateExpense(ctx, g.ID, in, "key-1")
	require.NoError(t, err)
	e2, err := s.CreateExpense(ctx, g.ID, in, "key-1")
	require.NoError(t, err)
	require.Equal(t, e1.ID, e2.ID)

	list, _ := s.ListExpenses(ctx, g.ID)
	require.Len(t, list, 1)
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/store/... -run TestCreateAndListExpense`
Expected: FAIL (no `CreateExpense`).

- [ ] **Step 3: Implement expenses store**

Create `schism-backend/internal/store/expenses.go`:

```go
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/split"
)

type PaidForInput struct {
	ParticipantID string
	Shares        int64
}
type ExpenseInput struct {
	Title           string
	Amount          int64
	CategoryID      int
	ExpenseDate     time.Time
	PaidByID        string
	SplitMode       string
	IsReimbursement bool
	Notes           string
	PaidFor         []PaidForInput
}
type PaidForRow struct {
	ParticipantID string `json:"participantId"`
	Shares        int64  `json:"shares"`
}
type Expense struct {
	ID              string       `json:"id"`
	GroupID         string       `json:"groupId"`
	Title           string       `json:"title"`
	Amount          int64        `json:"amount"`
	CategoryID      int          `json:"categoryId"`
	ExpenseDate     time.Time    `json:"expenseDate"`
	PaidByID        string       `json:"paidById"`
	SplitMode       string       `json:"splitMode"`
	IsReimbursement bool         `json:"isReimbursement"`
	Notes           string       `json:"notes"`
	CreatedAt       time.Time    `json:"createdAt"`
	PaidFor         []PaidForRow `json:"paidFor"`
}

func (s *Store) CreateExpense(ctx context.Context, groupID string, in ExpenseInput, idemKey string) (Expense, error) {
	if idemKey != "" {
		var existingID string
		err := s.pool.QueryRow(ctx, `SELECT expense_id FROM expense_idempotency WHERE key=$1`, idemKey).Scan(&existingID)
		if err == nil {
			e, gerr := s.GetExpense(ctx, groupID, existingID)
			if gerr != nil {
				return Expense{}, gerr
			}
			if e != nil {
				return *e, nil
			}
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return Expense{}, err
		}
	}

	eid := id.New()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Expense{}, err
	}
	defer tx.Rollback(ctx)

	catID := in.CategoryID
	if _, err := tx.Exec(ctx,
		`INSERT INTO expenses (id, group_id, expense_date, title, category_id, amount, paid_by_id, is_reimbursement, split_mode, notes)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
		eid, groupID, in.ExpenseDate, in.Title, catID, in.Amount, in.PaidByID, in.IsReimbursement, in.SplitMode, nullify(in.Notes)); err != nil {
		return Expense{}, err
	}
	for _, pf := range in.PaidFor {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_paid_for (expense_id, participant_id, shares) VALUES ($1,$2,$3)`,
			eid, pf.ParticipantID, pf.Shares); err != nil {
			return Expense{}, err
		}
	}
	if idemKey != "" {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_idempotency (key, expense_id) VALUES ($1,$2)`, idemKey, eid); err != nil {
			return Expense{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return Expense{}, err
	}
	e, err := s.GetExpense(ctx, groupID, eid)
	if err != nil {
		return Expense{}, err
	}
	return *e, nil
}

func (s *Store) GetExpense(ctx context.Context, groupID, expenseID string) (*Expense, error) {
	var e Expense
	err := s.pool.QueryRow(ctx,
		`SELECT id, group_id, title, amount, category_id, expense_date, paid_by_id,
		        is_reimbursement, split_mode, COALESCE(notes,''), created_at
		 FROM expenses WHERE id=$1 AND group_id=$2`, expenseID, groupID).
		Scan(&e.ID, &e.GroupID, &e.Title, &e.Amount, &e.CategoryID, &e.ExpenseDate, &e.PaidByID,
			&e.IsReimbursement, &e.SplitMode, &e.Notes, &e.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	pf, err := s.paidFor(ctx, e.ID)
	if err != nil {
		return nil, err
	}
	e.PaidFor = pf
	return &e, nil
}

func (s *Store) paidFor(ctx context.Context, expenseID string) ([]PaidForRow, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT participant_id, shares FROM expense_paid_for WHERE expense_id=$1 ORDER BY participant_id`, expenseID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PaidForRow{}
	for rows.Next() {
		var p PaidForRow
		if err := rows.Scan(&p.ParticipantID, &p.Shares); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ListExpenses(ctx context.Context, groupID string) ([]Expense, error) {
	rows, err := s.pool.Query(ctx, `SELECT id FROM expenses WHERE group_id=$1 ORDER BY expense_date DESC, created_at DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var eid string
		if err := rows.Scan(&eid); err != nil {
			return nil, err
		}
		ids = append(ids, eid)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := []Expense{}
	for _, eid := range ids {
		e, err := s.GetExpense(ctx, groupID, eid)
		if err != nil {
			return nil, err
		}
		if e != nil {
			out = append(out, *e)
		}
	}
	return out, nil
}

func (s *Store) UpdateExpense(ctx context.Context, groupID, expenseID string, in ExpenseInput) (*Expense, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	ct, err := tx.Exec(ctx,
		`UPDATE expenses SET title=$3, amount=$4, category_id=$5, expense_date=$6, paid_by_id=$7,
		        is_reimbursement=$8, split_mode=$9, notes=$10
		 WHERE id=$1 AND group_id=$2`,
		expenseID, groupID, in.Title, in.Amount, in.CategoryID, in.ExpenseDate, in.PaidByID,
		in.IsReimbursement, in.SplitMode, nullify(in.Notes))
	if err != nil {
		return nil, err
	}
	if ct.RowsAffected() == 0 {
		return nil, nil
	}
	if _, err := tx.Exec(ctx, `DELETE FROM expense_paid_for WHERE expense_id=$1`, expenseID); err != nil {
		return nil, err
	}
	for _, pf := range in.PaidFor {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_paid_for (expense_id, participant_id, shares) VALUES ($1,$2,$3)`,
			expenseID, pf.ParticipantID, pf.Shares); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return s.GetExpense(ctx, groupID, expenseID)
}

func (s *Store) DeleteExpense(ctx context.Context, groupID, expenseID string) (bool, error) {
	ct, err := s.pool.Exec(ctx, `DELETE FROM expenses WHERE id=$1 AND group_id=$2`, expenseID, groupID)
	if err != nil {
		return false, err
	}
	return ct.RowsAffected() > 0, nil
}

// SplitExpenses adapts stored expenses into the pure split package's shape.
func (s *Store) SplitExpenses(ctx context.Context, groupID string) ([]split.Expense, error) {
	list, err := s.ListExpenses(ctx, groupID)
	if err != nil {
		return nil, err
	}
	out := make([]split.Expense, len(list))
	for i, e := range list {
		pf := make([]split.PaidFor, len(e.PaidFor))
		for j, p := range e.PaidFor {
			pf[j] = split.PaidFor{ParticipantID: p.ParticipantID, Shares: p.Shares}
		}
		out[i] = split.Expense{ID: e.ID, Amount: e.Amount, PaidByID: e.PaidByID, PaidFor: pf, SplitMode: split.SplitMode(e.SplitMode)}
	}
	return out, nil
}
```

- [ ] **Step 4: Run expenses store tests (green)**

Run: `cd schism-backend && go test ./internal/store/... -run TestCreateAndListExpense && go test ./internal/store/... -run TestCreateExpenseIdempotent`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): expenses repository with idempotent create + split adapter"
```

---

## Task 9: Store — categories (seed + list) & activities

**Files:**
- Modify: `schism-backend/internal/store/migrations/0001_init.up.sql` (append category seed)
- Create: `schism-backend/internal/store/categories.go`
- Create: `schism-backend/internal/store/activities.go`
- Test: `schism-backend/internal/store/categories_test.go`

**Interfaces:**
- Produces on `*Store`: `ListCategories(ctx) ([]Category, error)`,
  `LogActivity(ctx, groupID, activityType string, participantID, expenseID *string, data string) error`,
  `ListActivities(ctx, groupID string) ([]Activity, error)`.
- Produces types `Category{ID int; Grouping, Name string}`,
  `Activity{ID, GroupID string; Time time.Time; ActivityType string; ParticipantID, ExpenseID *string; Data string}`.

- [ ] **Step 1: Append the seed to the up migration**

Append to `schism-backend/internal/store/migrations/0001_init.up.sql`:

```sql
INSERT INTO categories (id, grouping, name) OVERRIDING SYSTEM VALUE VALUES
  (0, 'Uncategorized', 'General'),
  (1, 'Food and Drink', 'Groceries'),
  (2, 'Food and Drink', 'Restaurants'),
  (3, 'Transportation', 'Taxi'),
  (4, 'Entertainment', 'Movies'),
  (5, 'Home', 'Rent')
ON CONFLICT (id) DO NOTHING;
```

(Recreate the DB so the seed applies: `docker compose down && make up && sleep 3`, or run against a
fresh test DB. `RunMigrations` is idempotent for already-applied versions.)

- [ ] **Step 2: Write failing categories test**

Create `schism-backend/internal/store/categories_test.go`:

```go
package store

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestListCategories(t *testing.T) {
	s := newTestStore(t)
	cats, err := s.ListCategories(context.Background())
	require.NoError(t, err)
	require.NotEmpty(t, cats)
	require.Equal(t, 0, cats[0].ID)
}

func TestLogAndListActivities(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, _ := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}}})
	require.NoError(t, s.LogActivity(ctx, g.ID, "CREATE_EXPENSE", nil, nil, ""))
	acts, err := s.ListActivities(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, acts, 1)
	require.Equal(t, "CREATE_EXPENSE", acts[0].ActivityType)
}
```

- [ ] **Step 3: Run to verify fail**

Run: `cd schism-backend && go test ./internal/store/... -run 'TestListCategories|TestLogAndListActivities'`
Expected: FAIL.

- [ ] **Step 4: Implement categories + activities**

Create `schism-backend/internal/store/categories.go`:

```go
package store

import "context"

type Category struct {
	ID       int    `json:"id"`
	Grouping string `json:"grouping"`
	Name     string `json:"name"`
}

func (s *Store) ListCategories(ctx context.Context) ([]Category, error) {
	rows, err := s.pool.Query(ctx, `SELECT id, grouping, name FROM categories ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Category{}
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.Grouping, &c.Name); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
```

Create `schism-backend/internal/store/activities.go`:

```go
package store

import (
	"context"
	"time"

	"github.com/schism/schism-backend/internal/id"
)

type Activity struct {
	ID            string    `json:"id"`
	GroupID       string    `json:"groupId"`
	Time          time.Time `json:"time"`
	ActivityType  string    `json:"activityType"`
	ParticipantID *string   `json:"participantId"`
	ExpenseID     *string   `json:"expenseId"`
	Data          string    `json:"data"`
}

func (s *Store) LogActivity(ctx context.Context, groupID, activityType string, participantID, expenseID *string, data string) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO activities (id, group_id, activity_type, participant_id, expense_id, data)
		 VALUES ($1,$2,$3,$4,$5,$6)`,
		id.New(), groupID, activityType, participantID, expenseID, nullify(data))
	return err
}

func (s *Store) ListActivities(ctx context.Context, groupID string) ([]Activity, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, group_id, time, activity_type, participant_id, expense_id, COALESCE(data,'')
		 FROM activities WHERE group_id=$1 ORDER BY time DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Activity{}
	for rows.Next() {
		var a Activity
		if err := rows.Scan(&a.ID, &a.GroupID, &a.Time, &a.ActivityType, &a.ParticipantID, &a.ExpenseID, &a.Data); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
```

- [ ] **Step 5: Recreate DB & run (green)**

Run:
```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && docker compose down && make up && sleep 3
go test ./internal/store/... -run 'TestListCategories|TestLogAndListActivities'
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): categories seed/list and activities store"
```

---

## Task 10: HTTP layer — errors, DTOs, router skeleton, groups + categories handlers

**Files:**
- Create: `schism-backend/internal/api/errors.go`
- Create: `schism-backend/internal/api/dto.go`
- Create: `schism-backend/internal/api/router.go`
- Create: `schism-backend/internal/api/groups.go`
- Create: `schism-backend/internal/api/categories.go`
- Modify: `schism-backend/cmd/server/main.go` (mount `api.NewRouter(store)`)
- Test: `schism-backend/internal/api/groups_test.go`

**Interfaces:**
- Produces: `api.NewRouter(s *store.Store) http.Handler`.
- Consumes: `store.Store` methods (Tasks 7–9).

- [ ] **Step 1: Write failing HTTP test for group create + get**

Create `schism-backend/internal/api/groups_test.go`:

```go
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

func newTestServer(t *testing.T) *httptest.Server {
	url := store.TestURLForAPI(t)
	require.NoError(t, store.RunMigrations(url))
	pool, err := store.NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	srv := httptest.NewServer(NewRouter(store.NewStore(pool)))
	t.Cleanup(srv.Close)
	return srv
}

func TestCreateGroupHTTP(t *testing.T) {
	srv := newTestServer(t)
	body := `{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Alice"},{"name":"Bob"}]}`
	resp, err := http.Post(srv.URL+"/v1/groups", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.NotEmpty(t, created.GroupID)

	resp2, err := http.Get(srv.URL + "/v1/groups/" + created.GroupID)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	require.Equal(t, "Trip", g.Name)
	require.Len(t, g.Participants, 2)

	resp3, _ := http.Get(srv.URL + "/v1/groups/nope")
	require.Equal(t, http.StatusNotFound, resp3.StatusCode)
}
```

Add to `schism-backend/internal/store/pool.go` a small test helper (exported, used only by tests):

```go
import "testing" // add to imports

func TestURLForAPI(t *testing.T) string {
	url := osGetenv("DATABASE_URL")
	if url == "" {
		t.Skip("DATABASE_URL not set")
	}
	return url
}
```

Add near the top of `pool.go`: `import "os"` and helper `func osGetenv(k string) string { return os.Getenv(k) }`.
(Or simply reuse `os.Getenv` inline — keep it importable from the `api` package.)

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/api/...`
Expected: FAIL (no `NewRouter`).

- [ ] **Step 3: Implement errors + JSON helpers**

Create `schism-backend/internal/api/errors.go`:

```go
package api

import (
	"encoding/json"
	"net/http"
)

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
```

- [ ] **Step 4: Implement DTOs + mappers**

Create `schism-backend/internal/api/dto.go`:

```go
package api

import (
	"time"

	"github.com/schism/schism-backend/internal/store"
)

type participantDTO struct {
	ID   *string `json:"id"`
	Name string  `json:"name"`
}
type groupFormDTO struct {
	Name         string           `json:"name"`
	Information  string           `json:"information"`
	Currency     string           `json:"currency"`
	CurrencyCode string           `json:"currencyCode"`
	Participants []participantDTO `json:"participants"`
}

func (d groupFormDTO) toInput() store.GroupInput {
	parts := make([]store.ParticipantInput, len(d.Participants))
	for i, p := range d.Participants {
		parts[i] = store.ParticipantInput{ID: p.ID, Name: p.Name}
	}
	return store.GroupInput{
		Name: d.Name, Information: d.Information, Currency: d.Currency,
		CurrencyCode: d.CurrencyCode, Participants: parts,
	}
}

type paidForDTO struct {
	ParticipantID string `json:"participantId"`
	Shares        int64  `json:"shares"`
}
type expenseFormDTO struct {
	Title           string       `json:"title"`
	Amount          int64        `json:"amount"`
	CategoryID      int          `json:"categoryId"`
	ExpenseDate     time.Time    `json:"expenseDate"`
	PaidByID        string       `json:"paidById"`
	SplitMode       string       `json:"splitMode"`
	IsReimbursement bool         `json:"isReimbursement"`
	Notes           string       `json:"notes"`
	PaidFor         []paidForDTO `json:"paidFor"`
}

func (d expenseFormDTO) toInput() store.ExpenseInput {
	pf := make([]store.PaidForInput, len(d.PaidFor))
	for i, p := range d.PaidFor {
		pf[i] = store.PaidForInput{ParticipantID: p.ParticipantID, Shares: p.Shares}
	}
	date := d.ExpenseDate
	if date.IsZero() {
		date = time.Now()
	}
	mode := d.SplitMode
	if mode == "" {
		mode = "EVENLY"
	}
	return store.ExpenseInput{
		Title: d.Title, Amount: d.Amount, CategoryID: d.CategoryID, ExpenseDate: date,
		PaidByID: d.PaidByID, SplitMode: mode, IsReimbursement: d.IsReimbursement,
		Notes: d.Notes, PaidFor: pf,
	}
}
```

- [ ] **Step 5: Implement router**

Create `schism-backend/internal/api/router.go`:

```go
package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/schism/schism-backend/internal/store"
)

type Handler struct{ store *store.Store }

func NewRouter(s *store.Store) http.Handler {
	h := &Handler{store: s}
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)

	r.Route("/v1", func(r chi.Router) {
		r.Get("/categories", h.listCategories)
		r.Route("/groups", func(r chi.Router) {
			r.Post("/", h.createGroup)
			r.Get("/", h.listGroups)
			r.Route("/{groupID}", func(r chi.Router) {
				r.Get("/", h.getGroup)
				r.Put("/", h.updateGroup)
				r.Get("/details", h.getGroupDetails)
				r.Get("/balances", h.getBalances)
				r.Get("/activities", h.listActivities)
				r.Get("/stats", h.getStats)
				r.Route("/expenses", func(r chi.Router) {
					r.Get("/", h.listExpenses)
					r.Post("/", h.createExpense)
					r.Get("/{expenseID}", h.getExpense)
					r.Put("/{expenseID}", h.updateExpense)
					r.Delete("/{expenseID}", h.deleteExpense)
				})
			})
		})
	})
	return r
}
```

- [ ] **Step 6: Implement group + category handlers**

Create `schism-backend/internal/api/groups.go`:

```go
package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) createGroup(w http.ResponseWriter, r *http.Request) {
	var d groupFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(strings.TrimSpace(d.Name)) < 2 || len(d.Participants) < 1 {
		writeErr(w, http.StatusBadRequest, "name>=2 chars and >=1 participant required")
		return
	}
	g, err := h.store.CreateGroup(r.Context(), d.toInput())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"groupId": g.ID})
}

func (h *Handler) getGroup(w http.ResponseWriter, r *http.Request) {
	g, err := h.store.GetGroup(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	writeJSON(w, http.StatusOK, g)
}

func (h *Handler) getGroupDetails(w http.ResponseWriter, r *http.Request) {
	h.getGroup(w, r) // details == group + participants in v1
}

func (h *Handler) updateGroup(w http.ResponseWriter, r *http.Request) {
	var d groupFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	g, err := h.store.UpdateGroup(r.Context(), chi.URLParam(r, "groupID"), d.toInput())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	writeJSON(w, http.StatusOK, g)
}

func (h *Handler) listGroups(w http.ResponseWriter, r *http.Request) {
	idsParam := r.URL.Query().Get("ids")
	if idsParam == "" {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	ids := strings.Split(idsParam, ",")
	groups, err := h.store.ListGroups(r.Context(), ids)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, groups)
}
```

Create `schism-backend/internal/api/categories.go`:

```go
package api

import "net/http"

func (h *Handler) listCategories(w http.ResponseWriter, r *http.Request) {
	cats, err := h.store.ListCategories(r.Context())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, cats)
}
```

- [ ] **Step 7: Stub the remaining handlers so the package compiles**

These are fully implemented in Task 11. For now create `schism-backend/internal/api/expenses.go`,
`balances.go`, `activities.go`, `stats.go` each with the handler methods returning
`http.StatusNotImplemented`, e.g. in `expenses.go`:

```go
package api

import "net/http"

func (h *Handler) listExpenses(w http.ResponseWriter, r *http.Request)  { writeErr(w, http.StatusNotImplemented, "todo") }
func (h *Handler) createExpense(w http.ResponseWriter, r *http.Request) { writeErr(w, http.StatusNotImplemented, "todo") }
func (h *Handler) getExpense(w http.ResponseWriter, r *http.Request)    { writeErr(w, http.StatusNotImplemented, "todo") }
func (h *Handler) updateExpense(w http.ResponseWriter, r *http.Request) { writeErr(w, http.StatusNotImplemented, "todo") }
func (h *Handler) deleteExpense(w http.ResponseWriter, r *http.Request) { writeErr(w, http.StatusNotImplemented, "todo") }
```

`balances.go`: `func (h *Handler) getBalances(...)`, `activities.go`: `func (h *Handler) listActivities(...)`,
`stats.go`: `func (h *Handler) getStats(...)` — same NotImplemented stub shape.

- [ ] **Step 8: Mount router in main**

Replace the ad-hoc `/health` router body in `cmd/server/main.go` so it mounts the API and keeps health:

```go
	r := chi.NewRouter()
	r.Get("/health", func(w http.ResponseWriter, req *http.Request) {
		if err := pool.Ping(req.Context()); err != nil {
			http.Error(w, "db down", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	r.Mount("/", api.NewRouter(store.NewStore(pool)))
```

Add import `"github.com/schism/schism-backend/internal/api"`.

- [ ] **Step 9: Run HTTP test (green) + build**

Run: `cd schism-backend && go test ./internal/api/... -run TestCreateGroupHTTP && go build ./...`
Expected: PASS, build OK.

- [ ] **Step 10: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): HTTP router, group & category endpoints"
```

---

## Task 11: HTTP layer — expenses, balances, activities, stats handlers

**Files:**
- Modify: `schism-backend/internal/api/expenses.go` (real implementations)
- Modify: `schism-backend/internal/api/balances.go`
- Modify: `schism-backend/internal/api/activities.go`
- Modify: `schism-backend/internal/api/stats.go`
- Test: `schism-backend/internal/api/expenses_test.go`

**Interfaces:**
- Consumes: `store` expense/balance/activity methods (Tasks 8–9), `split.GetBalances`,
  `split.GetSuggestedReimbursements`, `split.GetPublicBalances`, `split.ValidateExpense`.

- [ ] **Step 1: Write failing expense + balances HTTP test**

Create `schism-backend/internal/api/expenses_test.go`:

```go
package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

func createGroupFixture(t *testing.T, srvURL string) store.Group {
	body := `{"name":"Trip","currency":"$","participants":[{"name":"A"},{"name":"B"}]}`
	resp, _ := http.Post(srvURL+"/v1/groups", "application/json", bytes.NewBufferString(body))
	var created struct{ GroupID string `json:"groupId"` }
	_ = json.NewDecoder(resp.Body).Decode(&created)
	resp2, _ := http.Get(srvURL + "/v1/groups/" + created.GroupID)
	var g store.Group
	_ = json.NewDecoder(resp2.Body).Decode(&g)
	return g
}

func TestExpenseAndBalancesHTTP(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)

	resp2, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/balances")
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var out struct {
		Balances       map[string]struct{ Paid, PaidFor, Total int64 } `json:"balances"`
		Reimbursements []struct {
			From, To string
			Amount   int64
		} `json:"reimbursements"`
	}
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&out))
	require.Equal(t, int64(500), out.Balances[a].Total)
	require.Equal(t, int64(-500), out.Balances[b].Total)
	require.Len(t, out.Reimbursements, 1)
	require.Equal(t, b, out.Reimbursements[0].From)
	require.Equal(t, a, out.Reimbursements[0].To)
	require.Equal(t, int64(500), out.Reimbursements[0].Amount)
}

func TestCreateExpenseValidation(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a := g.Participants[0].ID
	body := fmt.Sprintf(`{"title":"Bad","amount":0,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100}]}`, a, a)
	resp, _ := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd schism-backend && go test ./internal/api/... -run 'TestExpenseAndBalancesHTTP|TestCreateExpenseValidation'`
Expected: FAIL (handlers return 501).

- [ ] **Step 3: Implement expenses handlers**

Replace `schism-backend/internal/api/expenses.go`:

```go
package api

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/split"
	"github.com/schism/schism-backend/internal/store"
)

func toSplitExpense(in store.ExpenseInput) split.Expense {
	pf := make([]split.PaidFor, len(in.PaidFor))
	for i, p := range in.PaidFor {
		pf[i] = split.PaidFor{ParticipantID: p.ParticipantID, Shares: p.Shares}
	}
	return split.Expense{Amount: in.Amount, PaidByID: in.PaidByID, PaidFor: pf, SplitMode: split.SplitMode(in.SplitMode)}
}

func (h *Handler) createExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	var d expenseFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	in := d.toInput()
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	g, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	e, err := h.store.CreateExpense(r.Context(), groupID, in, r.Header.Get("Idempotency-Key"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	eid := e.ID
	_ = h.store.LogActivity(r.Context(), groupID, "CREATE_EXPENSE", nil, &eid, "")
	writeJSON(w, http.StatusCreated, e)
}

func (h *Handler) listExpenses(w http.ResponseWriter, r *http.Request) {
	list, err := h.store.ListExpenses(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (h *Handler) getExpense(w http.ResponseWriter, r *http.Request) {
	e, err := h.store.GetExpense(r.Context(), chi.URLParam(r, "groupID"), chi.URLParam(r, "expenseID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	writeJSON(w, http.StatusOK, e)
}

func (h *Handler) updateExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenseID := chi.URLParam(r, "expenseID")
	var d expenseFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	in := d.toInput()
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	e, err := h.store.UpdateExpense(r.Context(), groupID, expenseID, in)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	eid := e.ID
	_ = h.store.LogActivity(r.Context(), groupID, "UPDATE_EXPENSE", nil, &eid, "")
	writeJSON(w, http.StatusOK, e)
}

func (h *Handler) deleteExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenseID := chi.URLParam(r, "expenseID")
	ok, err := h.store.DeleteExpense(r.Context(), groupID, expenseID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !ok {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	_ = h.store.LogActivity(r.Context(), groupID, "DELETE_EXPENSE", nil, &expenseID, "")
	w.WriteHeader(http.StatusNoContent)
}
```

- [ ] **Step 4: Implement balances handler**

Replace `schism-backend/internal/api/balances.go`:

```go
package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/split"
)

func (h *Handler) getBalances(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenses, err := h.store.SplitExpenses(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	balances := split.GetBalances(expenses)
	reimb := split.GetSuggestedReimbursements(balances)
	public := split.GetPublicBalances(reimb)
	if reimb == nil {
		reimb = []split.Reimbursement{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"balances":       public,
		"reimbursements": reimb,
	})
}
```

Note: spliit returns `getPublicBalances(reimbursements)` as `balances` (privacy-preserving), and the
raw `reimbursements` list — matched here.

- [ ] **Step 5: Implement activities + stats handlers**

Replace `schism-backend/internal/api/activities.go`:

```go
package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) listActivities(w http.ResponseWriter, r *http.Request) {
	acts, err := h.store.ListActivities(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, acts)
}
```

Replace `schism-backend/internal/api/stats.go`:

```go
package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) getStats(w http.ResponseWriter, r *http.Request) {
	expenses, err := h.store.ListExpenses(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	var totalSpent int64
	for _, e := range expenses {
		if !e.IsReimbursement {
			totalSpent += e.Amount
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"totalGroupSpending": totalSpent,
		"expenseCount":       len(expenses),
	})
}
```

- [ ] **Step 6: Run all API tests (green)**

Run: `cd schism-backend && go test ./internal/api/...`
Expected: PASS.

- [ ] **Step 7: Full suite + build**

Run: `cd schism-backend && go build ./... && make test`
Expected: build OK, all packages PASS.

- [ ] **Step 8: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "feat(splitd): expenses, balances, activities, stats endpoints"
```

---

## Task 12: End-to-end smoke test + README

**Files:**
- Create: `schism-backend/internal/api/e2e_test.go`
- Create: `schism-backend/README.md`

- [ ] **Step 1: Write an end-to-end flow test**

Create `schism-backend/internal/api/e2e_test.go`:

```go
package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

// Full flow: create group → add 2 expenses (uneven modes) → verify balances net to zero.
func TestEndToEndFlow(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	mk := func(body string) {
		resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
		require.NoError(t, err)
		require.Equal(t, http.StatusCreated, resp.StatusCode)
	}
	mk(fmt.Sprintf(`{"title":"A pays","amount":3000,"paidById":%q,"splitMode":"BY_PERCENTAGE",
	  "paidFor":[{"participantId":%q,"shares":4000},{"participantId":%q,"shares":6000}]}`, a, a, b))
	mk(fmt.Sprintf(`{"title":"B pays","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, b, a, b))

	resp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/balances")
	var out struct {
		Balances map[string]struct{ Total int64 } `json:"balances"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&out))
	var sum int64
	for _, v := range out.Balances {
		sum += v.Total
	}
	require.Equal(t, int64(0), sum, "balances must net to zero")
}
```

- [ ] **Step 2: Run e2e (green)**

Run: `cd schism-backend && go test ./internal/api/... -run TestEndToEndFlow`
Expected: PASS.

- [ ] **Step 3: Write README**

Create `schism-backend/README.md`:

```markdown
# schism-backend — Go split backend

Scalable Go + Postgres service porting spliit's split domain. REST API under `/v1`.

## Run locally
    make up          # start Postgres (docker)
    make run         # migrate + serve on :8080

## Test
    make up
    make test

## Endpoints (v1)
- `POST /v1/groups`, `GET /v1/groups?ids=a,b`, `GET /v1/groups/{id}`,
  `GET /v1/groups/{id}/details`, `PUT /v1/groups/{id}`
- `GET /v1/categories`
- `GET|POST /v1/groups/{id}/expenses`, `GET|PUT|DELETE /v1/groups/{id}/expenses/{expenseId}`
  (create accepts optional `Idempotency-Key` header)
- `GET /v1/groups/{id}/balances` — `{ balances, reimbursements }` (parity with spliit)
- `GET /v1/groups/{id}/activities`, `GET /v1/groups/{id}/stats`

Split math (`internal/split`) is verified byte-for-byte against spliit via golden fixtures.
```

- [ ] **Step 4: Commit**

```bash
cd /Users/devkeshwani/Developer/schism/schism-backend && git add -A && \
git commit -q -m "test(splitd): end-to-end flow + README"
```

---

## Self-Review Notes (checked against spec §8)

- **Schema port** → Task 2 (all 7 tables + idempotency). ✓
- **`getBalances`** → Task 4 with spliit golden parity. ✓
- **`getSuggestedReimbursements` + `getPublicBalances`** → Task 5, golden parity. ✓
- **Validation (amount≠0, ≤10M, BY_AMOUNT sum, BY_PERCENTAGE sum, ≥1 paidFor, share>0)** → Task 6. ✓
- **All REST endpoints (groups/categories/expenses/balances/activities/stats)** → Tasks 10–11. ✓
- **Idempotency-Key on expense create (SP3 dependency)** → Tasks 2, 8, 11. ✓
- **No-auth, group-by-ID access** → routing carries no auth; Global Constraints. ✓
- **Integer minor units everywhere** → int64 in domain, BIGINT in schema, no float in JSON. ✓
- **Split package has zero external deps** → only `math`, `sort`, `errors`. ✓

Balances endpoint returns `getPublicBalances` output as `balances` (not raw per-participant
balances), matching spliit's `list.procedure.ts`. Stats is a minimal v1 (total spending + count);
spliit's richer stats can be added when SP2 needs them.
```
