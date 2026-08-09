package web

import (
	"regexp"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestHomeContractAndAccessibility(t *testing.T) {
	rec := get(t, newTestSite(t, ""), "/")
	body := rec.Body.String()
	lower := strings.ToLower(body)

	require.Len(t, regexp.MustCompile(`<h1(?:\s[^>]*)?>`).FindAllString(body, -1), 1)
	for _, fragment := range []string{
		`class="skip-link"`, `<header`, `<nav`, `<main id="main-content">`, `<footer`,
		`Capture`, `Review`, `Split`, `On-device by design`, `Live Split`,
		`href="/privacy"`, `href="/terms"`, `href="/support"`, `href="/account-deletion"`,
		`aria-label="Schism home"`, `alt=""`,
	} {
		require.Contains(t, body, fragment)
	}
	for _, forbidden := range []string{
		"100% accurate", "perfect ocr", "all banks", "bank affiliated", "settles debt automatically",
		"customer review", "₹99", "$9.99",
	} {
		require.NotContains(t, lower, forbidden)
	}

	css := get(t, newTestSite(t, ""), "/assets/site/site.css").Body.String()
	require.Contains(t, css, ":focus-visible")
	require.Contains(t, css, "prefers-reduced-motion: reduce")
}

func TestHomeUsesConfiguredPlayCTA(t *testing.T) {
	rec := get(t, newTestSite(t, "https://play.google.com/store/apps/details?id=com.dkadev.schism"), "/")
	require.Contains(t, rec.Body.String(), `href="https://play.google.com/store/apps/details?id=com.dkadev.schism"`)
	require.Contains(t, rec.Body.String(), `Get Schism on Google Play`)
	require.NotContains(t, rec.Body.String(), `Coming soon on Google Play`)
}
