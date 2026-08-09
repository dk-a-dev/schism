package config

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("ADDR", "")
	c, err := Load()
	require.NoError(t, err)
	require.Equal(t, ":8080", c.Addr)
	require.Equal(t, "postgres://x", c.DatabaseURL)
	require.Equal(t, int32(20), c.DBMaxConns)
	require.Equal(t, int32(2), c.DBMinConns)
	require.Equal(t, 30*time.Minute, c.DBMaxConnLifetime)
}

func TestLoadPoolOverridesAndRejectsInvalidValues(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("DB_MAX_CONNS", "12")
	t.Setenv("DB_MIN_CONNS", "3")
	t.Setenv("DB_MAX_CONN_LIFETIME", "45m")
	c, err := Load()
	require.NoError(t, err)
	require.Equal(t, int32(12), c.DBMaxConns)
	require.Equal(t, int32(3), c.DBMinConns)
	require.Equal(t, 45*time.Minute, c.DBMaxConnLifetime)

	for name, value := range map[string]string{
		"DB_MAX_CONNS":         "0",
		"DB_MIN_CONNS":         "-1",
		"DB_MAX_CONN_LIFETIME": "forever",
	} {
		t.Run(name, func(t *testing.T) {
			t.Setenv("DATABASE_URL", "postgres://x")
			t.Setenv("DB_MAX_CONNS", "")
			t.Setenv("DB_MIN_CONNS", "")
			t.Setenv("DB_MAX_CONN_LIFETIME", "")
			t.Setenv(name, value)
			_, err := Load()
			require.Error(t, err)
		})
	}
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
