package web

import (
	"net/http"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestLegalAndSupportPagesShareRequiredContract(t *testing.T) {
	h := newTestSite(t, "")
	paths := []string{"/privacy", "/terms", "/support", "/account-deletion"}
	for _, path := range paths {
		rec := get(t, h, path)
		require.Equal(t, http.StatusOK, rec.Code, path)
		require.Equal(t, "text/html; charset=utf-8", rec.Header().Get("Content-Type"), path)
		require.Contains(t, rec.Body.String(), `name="viewport"`, path)
		require.Contains(t, rec.Body.String(), "Schism", path)
		require.Contains(t, rec.Body.String(), "Effective 9 August 2026", path)
		// html/template escapes the "+" as &#43;; assert the escaped form so an unescaped
		// address (i.e. a broken injection point) fails the test.
		require.Contains(t, rec.Body.String(), "mailto:support&#43;launch@schism.test", path)
		for _, href := range paths {
			require.Contains(t, rec.Body.String(), `href="`+href+`"`, path+" -> "+href)
		}
	}
}

func TestPrivacyExplainsActualDataFlow(t *testing.T) {
	body := get(t, newTestSite(t, ""), "/privacy").Body.String()
	for _, claim := range []string{
		"on your device", "explicitly opt in", "receipt", "OCR", "SMS",
		"account", "shared group", "purchase token", "Google Play Billing",
		"Mobile Ads", "User Messaging Platform", "IP-derived general location",
		"app interactions", "diagnostics", "device or account identifiers",
		"encrypted in transit", "do not sell your personal data", "payment-card details",
	} {
		require.Contains(t, body, claim)
	}
}

func TestAccountDeletionSeparatesAccountAndSubscriptionActions(t *testing.T) {
	rec := get(t, newTestSite(t, ""), "/account-deletion")
	body := rec.Body.String()
	for _, claim := range []string{
		"Delete your Schism account", "cancel a Google Play subscription", "separate actions",
		"after uninstalling", `href="mailto:support&#43;launch@schism.test`,
		// Retention must be disclosed concretely: a stated purge window plus the carve-out for
		// records the law obliges us to keep.
		"purged within 30 days", "required by law to keep",
	} {
		require.Contains(t, body, claim)
	}
	require.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
}

func TestSupportPageProvidesFunctionalContactWithoutCookies(t *testing.T) {
	rec := get(t, newTestSite(t, ""), "/support")
	require.Contains(t, rec.Body.String(), `href="mailto:support&#43;launch@schism.test"`)
	require.NotContains(t, strings.ToLower(rec.Body.String()), "cookie banner")
	require.Empty(t, rec.Header().Values("Set-Cookie"))
}
