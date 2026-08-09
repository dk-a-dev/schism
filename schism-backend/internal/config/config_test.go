package config

import (
	"bytes"
	"encoding/base64"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/receiptai"
	"github.com/stretchr/testify/require"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
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
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
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
			t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
			t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
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
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	_, err := Load()
	require.Error(t, err)
}

func TestLogRequestsFlag(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	t.Setenv("LOG_REQUESTS", "")
	c, _ := Load()
	require.False(t, c.LogRequests)

	for _, v := range []string{"true", "1", "YES", "on"} {
		t.Setenv("LOG_REQUESTS", v)
		c, _ := Load()
		require.True(t, c.LogRequests, "LOG_REQUESTS=%q should enable", v)
	}
}

func TestLoadPublicURLAndPlayURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "support@schism.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	t.Setenv("SCHISM_PUBLIC_URL", "https://schism.test")
	t.Setenv("SCHISM_PLAY_URL", "https://play.google.com/store/apps/details?id=com.dkadev.schism")

	c, err := Load()
	require.NoError(t, err)
	require.Equal(t, "support@schism.test", c.SupportEmail)
	require.Equal(t, "https://schism.test", c.PublicURL)
	require.Equal(t, "https://play.google.com/store/apps/details?id=com.dkadev.schism", c.PlayURL)
}

func TestLoadAcceptsEmptyPlayURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "support@schism.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	t.Setenv("SCHISM_PLAY_URL", "")

	c, err := Load()
	require.NoError(t, err)
	require.Empty(t, c.PlayURL)
}

// monetizationEnv sets the always-required vars and clears every monetization var, so each
// monetization test starts from a deployment that configured nothing.
func monetizationEnv(t *testing.T) {
	t.Helper()
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	for _, name := range []string{
		"PLUS_ENABLED", "ADS_ENABLED", "PURCHASES_ENABLED",
		"PLAY_PACKAGE_NAME", "BILLING_TOKEN_KEY", "PLAY_SERVICE_ACCOUNT_JSON",
	} {
		t.Setenv(name, "")
	}
}

func TestMonetizationFlagsDefaultOff(t *testing.T) {
	monetizationEnv(t)
	c, err := Load()
	require.NoError(t, err)
	require.False(t, c.PlusEnabled)
	require.False(t, c.AdsEnabled)
	require.False(t, c.PurchasesEnabled)
	require.Empty(t, c.BillingTokenKey)
	require.Empty(t, c.PlayPackageName)
}

func TestMonetizationFlagsAcceptTruthyValues(t *testing.T) {
	for _, v := range []string{"true", "1", "YES", "on"} {
		t.Run(v, func(t *testing.T) {
			monetizationEnv(t)
			t.Setenv("PLUS_ENABLED", v)
			t.Setenv("ADS_ENABLED", v)
			c, err := Load()
			require.NoError(t, err)
			require.True(t, c.PlusEnabled)
			require.True(t, c.AdsEnabled)
			// Purchases stay off: each switch is independent.
			require.False(t, c.PurchasesEnabled)
		})
	}
}

func TestBillingTokenKeyMustDecodeTo32Bytes(t *testing.T) {
	monetizationEnv(t)
	key := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	t.Setenv("BILLING_TOKEN_KEY", key)
	c, err := Load()
	require.NoError(t, err)
	require.Len(t, c.BillingTokenKey, 32)

	for _, bad := range []string{
		base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 16)),
		base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 31)),
		base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 64)),
		"not base64 at all!!",
	} {
		t.Run(bad, func(t *testing.T) {
			monetizationEnv(t)
			t.Setenv("BILLING_TOKEN_KEY", bad)
			_, err := Load()
			require.ErrorContains(t, err, "BILLING_TOKEN_KEY")
		})
	}
}

func TestPurchasesEnabledRequiresBillingConfiguration(t *testing.T) {
	key := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	sa := `{"client_email":"a@b.iam.gserviceaccount.com","private_key":"x"}`

	for _, missing := range []string{"BILLING_TOKEN_KEY", "PLAY_PACKAGE_NAME", "PLAY_SERVICE_ACCOUNT_JSON"} {
		t.Run(missing, func(t *testing.T) {
			monetizationEnv(t)
			t.Setenv("PURCHASES_ENABLED", "true")
			t.Setenv("BILLING_TOKEN_KEY", key)
			t.Setenv("PLAY_PACKAGE_NAME", "com.dkadev.schism")
			t.Setenv("PLAY_SERVICE_ACCOUNT_JSON", sa)
			t.Setenv(missing, "")
			_, err := Load()
			require.ErrorContains(t, err, missing)
		})
	}

	monetizationEnv(t)
	t.Setenv("PURCHASES_ENABLED", "true")
	t.Setenv("BILLING_TOKEN_KEY", key)
	t.Setenv("PLAY_PACKAGE_NAME", "com.dkadev.schism")
	t.Setenv("PLAY_SERVICE_ACCOUNT_JSON", sa)
	c, err := Load()
	require.NoError(t, err)
	require.True(t, c.PurchasesEnabled)
	require.Equal(t, "com.dkadev.schism", c.PlayPackageName)
}

func TestLoadRejectsMissingSupportEmail(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "")

	_, err := Load()
	require.ErrorContains(t, err, "SCHISM_SUPPORT_EMAIL")
}

func TestLoadRejectsInvalidPublicURL(t *testing.T) {
	for _, value := range []string{
		"http://schism.test",
		"/relative",
		"https://user@schism.test",
		"https://schism.test?campaign=launch",
		"https://schism.test/#fragment",
	} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("DATABASE_URL", "postgres://x")
			t.Setenv("SCHISM_SUPPORT_EMAIL", "support@schism.test")
			t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
			t.Setenv("SCHISM_PUBLIC_URL", value)

			_, err := Load()
			require.ErrorContains(t, err, "SCHISM_PUBLIC_URL")
		})
	}
}

func TestLoadRejectsInvalidPlayURL(t *testing.T) {
	for _, value := range []string{
		"http://play.google.com/store/apps/details?id=com.dkadev.schism",
		"play.google.com/store/apps/details?id=com.dkadev.schism",
		"https://user@play.google.com/store/apps/details?id=com.dkadev.schism",
		"https://play.google.com/store/apps/details?id=com.dkadev.schism#details",
	} {
		t.Run(value, func(t *testing.T) {
			t.Setenv("DATABASE_URL", "postgres://x")
			t.Setenv("SCHISM_SUPPORT_EMAIL", "support@schism.test")
			t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
			t.Setenv("SCHISM_PLAY_URL", value)

			_, err := Load()
			require.ErrorContains(t, err, "SCHISM_PLAY_URL")
		})
	}
}

// receiptAIEnv gives a valid baseline with every cloud-extraction variable cleared.
func receiptAIEnv(t *testing.T) {
	t.Helper()
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("SCHISM_SUPPORT_EMAIL", "owner@example.test")
	t.Setenv("SCHISM_LEGAL_VENUE_CITY", "Testville")
	for _, name := range []string{
		"RECEIPT_AI_ENABLED", "GEMINI_API_KEY", "GEMINI_MODEL", "GROQ_API_KEY", "GROQ_MODEL",
	} {
		t.Setenv(name, "")
	}
}

// Nothing configured must mean no cloud extraction at all, exactly like the monetization switches.
func TestLoadReceiptAIDefaultsOff(t *testing.T) {
	receiptAIEnv(t)
	c, err := Load()
	require.NoError(t, err)
	require.False(t, c.ReceiptAIEnabled)
	require.Empty(t, c.GeminiAPIKey)
	require.Empty(t, c.GroqAPIKey)
	require.Equal(t, receiptai.DefaultGeminiModel, c.GeminiModel)
	require.Equal(t, receiptai.DefaultGroqModel, c.GroqModel)
	require.Nil(t, receiptai.Select(c.GeminiAPIKey, c.GeminiModel, c.GroqAPIKey, c.GroqModel))
}

func TestLoadReceiptAIModelsAreConfigurable(t *testing.T) {
	receiptAIEnv(t)
	t.Setenv("GEMINI_MODEL", "gemini-3-flash")
	t.Setenv("GROQ_MODEL", "meta-llama/llama-4-maverick-17b-128e-instruct")
	c, err := Load()
	require.NoError(t, err)
	require.Equal(t, "gemini-3-flash", c.GeminiModel)
	require.Equal(t, "meta-llama/llama-4-maverick-17b-128e-instruct", c.GroqModel)
}

// The flag on with no key behind it is a misconfiguration: it must fail startup, not at runtime.
func TestLoadReceiptAIRequiresAProviderKeyWhenEnabled(t *testing.T) {
	cases := []struct {
		name      string
		geminiKey string
		groqKey   string
		wantErr   bool
	}{
		{name: "no key at all", wantErr: true},
		{name: "blank key", geminiKey: "   ", wantErr: true},
		{name: "gemini key", geminiKey: "g"},
		{name: "groq key", groqKey: "q"},
		{name: "both keys", geminiKey: "g", groqKey: "q"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			receiptAIEnv(t)
			t.Setenv("RECEIPT_AI_ENABLED", "true")
			t.Setenv("GEMINI_API_KEY", tc.geminiKey)
			t.Setenv("GROQ_API_KEY", tc.groqKey)
			c, err := Load()
			if tc.wantErr {
				require.ErrorContains(t, err, "RECEIPT_AI_ENABLED")
				return
			}
			require.NoError(t, err)
			require.True(t, c.ReceiptAIEnabled)
			require.NotNil(t, receiptai.Select(c.GeminiAPIKey, c.GeminiModel, c.GroqAPIKey, c.GroqModel))
		})
	}
}

// A key present while the flag is off stays off: the flag is the switch, not the key.
func TestLoadReceiptAIKeyWithoutFlagStaysOff(t *testing.T) {
	receiptAIEnv(t)
	t.Setenv("GEMINI_API_KEY", "g")
	c, err := Load()
	require.NoError(t, err)
	require.False(t, c.ReceiptAIEnabled)
}
