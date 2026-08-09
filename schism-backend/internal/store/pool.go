package store

import (
	"context"
	"embed"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

func NewPool(ctx context.Context, url string) (*pgxpool.Pool, error) {
	return pgxpool.New(ctx, url)
}

type PoolOptions struct {
	MaxConns        int32
	MinConns        int32
	MaxConnLifetime time.Duration
}

func NewPoolWithOptions(ctx context.Context, url string, options PoolOptions) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = options.MaxConns
	cfg.MinConns = options.MinConns
	cfg.MaxConnLifetime = options.MaxConnLifetime
	return pgxpool.NewWithConfig(ctx, cfg)
}

// RunMigrations applies all pending migrations. It is idempotent.
func RunMigrations(url string) error {
	src, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return err
	}
	m, err := migrate.NewWithSourceInstance("iofs", src, migrateURL(url))
	if err != nil {
		return err
	}
	defer func() {
		_, _ = m.Close()
	}()
	if err := m.Up(); err != nil && err != migrate.ErrNoChange {
		return err
	}
	return nil
}

// migrateURL ensures the pgx/v5 migrate driver scheme is used.
func migrateURL(url string) string {
	if len(url) >= 11 && url[:11] == "postgres://" {
		return "pgx5://" + url[11:]
	}
	if len(url) >= 13 && url[:13] == "postgresql://" {
		return "pgx5://" + url[13:]
	}
	return url
}
