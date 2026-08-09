package config

import (
	"encoding/base64"
	"errors"
	"fmt"
	"math"
	"net/mail"
	"net/url"
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
	SupportEmail      string
	PublicURL         string
	PlayURL           string
	DBMaxConns        int32
	DBMinConns        int32
	DBMaxConnLifetime time.Duration
	// Monetization switches. All three default to false so a deployment without explicit
	// configuration ships no paywall, no purchase surface, and no ads.
	PlusEnabled      bool
	AdsEnabled       bool
	PurchasesEnabled bool
	PlayPackageName  string
	// BillingTokenKey is the 32-byte AES-256-GCM key that encrypts stored Play purchase tokens,
	// supplied as standard base64 (`openssl rand -base64 32`). Required only when purchases are on.
	BillingTokenKey []byte
	// PlayServiceAccountJSON is the Google service-account key used to call the Play Developer API.
	// Required only when purchases are on.
	PlayServiceAccountJSON string
}

func Load() (Config, error) {
	c := Config{
		Addr:             os.Getenv("ADDR"),
		DatabaseURL:      os.Getenv("DATABASE_URL"),
		LogRequests:      isTruthy(os.Getenv("LOG_REQUESTS")),
		SupportEmail:     strings.TrimSpace(os.Getenv("SCHISM_SUPPORT_EMAIL")),
		PublicURL:        strings.TrimSpace(os.Getenv("SCHISM_PUBLIC_URL")),
		PlayURL:          strings.TrimSpace(os.Getenv("SCHISM_PLAY_URL")),
		PlusEnabled:      isTruthy(os.Getenv("PLUS_ENABLED")),
		AdsEnabled:       isTruthy(os.Getenv("ADS_ENABLED")),
		PurchasesEnabled: isTruthy(os.Getenv("PURCHASES_ENABLED")),
		PlayPackageName:  strings.TrimSpace(os.Getenv("PLAY_PACKAGE_NAME")),

		PlayServiceAccountJSON: os.Getenv("PLAY_SERVICE_ACCOUNT_JSON"),

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
	if err := validateSupportEmail(c.SupportEmail); err != nil {
		return Config{}, err
	}
	if err := validateHTTPSURL("SCHISM_PUBLIC_URL", c.PublicURL, false); err != nil {
		return Config{}, err
	}
	if err := validateHTTPSURL("SCHISM_PLAY_URL", c.PlayURL, true); err != nil {
		return Config{}, err
	}
	if c.BillingTokenKey, err = billingTokenKey(); err != nil {
		return Config{}, err
	}
	if c.PurchasesEnabled {
		if len(c.BillingTokenKey) == 0 {
			return Config{}, errors.New("BILLING_TOKEN_KEY is required when PURCHASES_ENABLED is set")
		}
		if c.PlayPackageName == "" {
			return Config{}, errors.New("PLAY_PACKAGE_NAME is required when PURCHASES_ENABLED is set")
		}
		if strings.TrimSpace(c.PlayServiceAccountJSON) == "" {
			return Config{}, errors.New("PLAY_SERVICE_ACCOUNT_JSON is required when PURCHASES_ENABLED is set")
		}
	}
	return c, nil
}

// billingTokenKey decodes BILLING_TOKEN_KEY, which must be standard base64 for exactly 32 bytes
// (AES-256). An unset key is allowed — purchases are off by default — but a malformed one is not,
// since a short key would silently weaken encryption of stored purchase tokens.
func billingTokenKey() ([]byte, error) {
	raw := strings.TrimSpace(os.Getenv("BILLING_TOKEN_KEY"))
	if raw == "" {
		return nil, nil
	}
	key, err := base64.StdEncoding.DecodeString(raw)
	if err != nil || len(key) != 32 {
		return nil, errors.New("BILLING_TOKEN_KEY must be base64 for exactly 32 bytes")
	}
	return key, nil
}

func validateSupportEmail(value string) error {
	if value == "" {
		return errors.New("SCHISM_SUPPORT_EMAIL is required")
	}
	address, err := mail.ParseAddress(value)
	if err != nil || address.Address != value || strings.ContainsAny(value, "\r\n") {
		return errors.New("SCHISM_SUPPORT_EMAIL must be a valid bare email address")
	}
	return nil
}

func validateHTTPSURL(name, value string, allowQuery bool) error {
	if value == "" {
		return nil
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return fmt.Errorf("%s must be an absolute HTTPS URL without userinfo or fragment", name)
	}
	if !allowQuery && (parsed.RawQuery != "" || parsed.ForceQuery) {
		return fmt.Errorf("%s must not contain a query", name)
	}
	return nil
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
