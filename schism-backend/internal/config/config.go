package config

import (
	"errors"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Addr        string
	DatabaseURL string
	// LogRequests enables per-request access logging. Off by default so production stays quiet;
	// enable in dev with LOG_REQUESTS=true (also accepts 1/yes/on).
	LogRequests       bool
	DBMaxConns        int32
	DBMinConns        int32
	DBMaxConnLifetime time.Duration
}

func Load() (Config, error) {
	c := Config{
		Addr:              os.Getenv("ADDR"),
		DatabaseURL:       os.Getenv("DATABASE_URL"),
		LogRequests:       isTruthy(os.Getenv("LOG_REQUESTS")),
		DBMaxConns:        20,
		DBMinConns:        2,
		DBMaxConnLifetime: 30 * time.Minute,
	}
	var err error
	if c.DBMaxConns, err = envInt32("DB_MAX_CONNS", c.DBMaxConns, false); err != nil {
		return Config{}, err
	}
	if c.DBMinConns, err = envInt32("DB_MIN_CONNS", c.DBMinConns, true); err != nil {
		return Config{}, err
	}
	if c.DBMaxConnLifetime, err = envDuration("DB_MAX_CONN_LIFETIME", c.DBMaxConnLifetime); err != nil {
		return Config{}, err
	}
	if c.DBMinConns > c.DBMaxConns {
		return Config{}, errors.New("DB_MIN_CONNS must not exceed DB_MAX_CONNS")
	}
	if c.Addr == "" {
		// Many PaaS inject the port to listen on as PORT; fall back to that, then to :8080.
		if p := os.Getenv("PORT"); p != "" {
			c.Addr = ":" + p
		} else {
			c.Addr = ":8080"
		}
	}
	if c.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	return c, nil
}

func envInt32(name string, fallback int32, allowZero bool) (int32, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseInt(raw, 10, 32)
	if err != nil || value < 0 || (!allowZero && value == 0) || value > math.MaxInt32 {
		return 0, fmt.Errorf("%s must be a valid positive integer", name)
	}
	return int32(value), nil
}

func envDuration(name string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("%s must be a positive duration", name)
	}
	return value, nil
}

func isTruthy(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}
