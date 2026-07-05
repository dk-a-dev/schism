package config

import (
	"errors"
	"os"
	"strings"
)

type Config struct {
	Addr        string
	DatabaseURL string
	// LogRequests enables per-request access logging. Off by default so production stays quiet;
	// enable in dev with LOG_REQUESTS=true (also accepts 1/yes/on).
	LogRequests bool
}

func Load() (Config, error) {
	c := Config{
		Addr:        os.Getenv("ADDR"),
		DatabaseURL: os.Getenv("DATABASE_URL"),
		LogRequests: isTruthy(os.Getenv("LOG_REQUESTS")),
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

func isTruthy(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}
