package store

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestNewPoolWithOptionsAppliesBounds(t *testing.T) {
	pool, err := NewPoolWithOptions(context.Background(), testURL(t), PoolOptions{
		MaxConns: 7, MinConns: 1, MaxConnLifetime: 17 * time.Minute,
	})
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	require.Equal(t, int32(7), pool.Config().MaxConns)
	require.Equal(t, int32(1), pool.Config().MinConns)
	require.Equal(t, 17*time.Minute, pool.Config().MaxConnLifetime)
}
