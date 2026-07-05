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

func TestLogRequestsFlag(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("LOG_REQUESTS", "")
	c, _ := Load()
	require.False(t, c.LogRequests)

	for _, v := range []string{"true", "1", "YES", "on"} {
		t.Setenv("LOG_REQUESTS", v)
		c, _ := Load()
		require.True(t, c.LogRequests, "LOG_REQUESTS=%q should enable", v)
	}
}
