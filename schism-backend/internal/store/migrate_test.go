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
