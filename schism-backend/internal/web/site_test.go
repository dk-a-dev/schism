package web

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func newTestSite(t *testing.T, playURL string) http.Handler {
	t.Helper()
	h, err := New(Config{
		SupportEmail:   "support+launch@schism.test",
		PublicURL:      "https://schism.test",
		PlayURL:        playURL,
		LegalVenueCity: "Testville",
	})
	require.NoError(t, err)
	return h
}

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestHomeIsSemanticUTF8HTML(t *testing.T) {
	rec := get(t, newTestSite(t, ""), "/")
	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, "text/html; charset=utf-8", rec.Header().Get("Content-Type"))
	require.Contains(t, rec.Body.String(), `<!doctype html>`)
	require.Contains(t, rec.Body.String(), `<main id="main-content">`)
	require.Contains(t, rec.Body.String(), `<h1 id="hero-title">Split expenses. Keep the context.</h1>`)
	require.Contains(t, rec.Body.String(), `Coming soon on Google Play`)
	require.NotContains(t, rec.Body.String(), `href=""`)
}

func TestHomeEscapesConfiguredPlayURL(t *testing.T) {
	h := newTestSite(t, "https://play.google.com/store/apps/details?id=com.dkadev.schism&referrer=launch")
	rec := get(t, h, "/")
	require.Equal(t, http.StatusOK, rec.Code)
	require.Contains(t, rec.Body.String(), `id=com.dkadev.schism&amp;referrer=launch`)
	require.NotContains(t, rec.Body.String(), `id=com.dkadev.schism&referrer=launch`)
}

func TestSiteHasNoScriptsCookiesOrExternalAssets(t *testing.T) {
	h := newTestSite(t, "https://play.google.com/store/apps/details?id=com.dkadev.schism")
	for _, path := range []string{"/", "/privacy", "/terms", "/support", "/account-deletion"} {
		rec := get(t, h, path)
		body := strings.ToLower(rec.Body.String())
		require.NotContains(t, body, "<script", path)
		require.NotContains(t, body, `src="http`, path)
		require.NotContains(t, body, `url(http`, path)
		require.Empty(t, rec.Header().Values("Set-Cookie"), path)
	}

	css := get(t, h, "/assets/site/site.css")
	require.NotContains(t, strings.ToLower(css.Body.String()), "@import")
	require.NotContains(t, strings.ToLower(css.Body.String()), "url(http")
}

func TestSiteAssetsUseAllowlistedTypesAndValidators(t *testing.T) {
	h := newTestSite(t, "")
	for path, contentType := range map[string]string{
		"/assets/site/site.css":       "text/css; charset=utf-8",
		"/assets/site/split-coin.svg": "image/svg+xml",
	} {
		rec := get(t, h, path)
		require.Equal(t, http.StatusOK, rec.Code, path)
		require.Equal(t, contentType, rec.Header().Get("Content-Type"), path)
		require.NotEmpty(t, rec.Header().Get("ETag"), path)
		require.Contains(t, rec.Header().Get("Cache-Control"), "max-age", path)

		conditional := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, path, nil)
		req.Header.Set("If-None-Match", rec.Header().Get("ETag"))
		h.ServeHTTP(conditional, req)
		require.Equal(t, http.StatusNotModified, conditional.Code, path)
	}
}

func TestSiteRejectsUnknownAndTraversingAssetPaths(t *testing.T) {
	h := newTestSite(t, "")
	for _, path := range []string{
		"/assets/site/unknown.css",
		"/assets/site/templates/home.html",
		"/assets/site/%2e%2e/templates/home.html",
	} {
		rec := get(t, h, path)
		require.Equal(t, http.StatusNotFound, rec.Code, path)
	}
}

func TestSiteSecurityHeadersAndCaching(t *testing.T) {
	rec := get(t, newTestSite(t, ""), "/")
	require.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
	require.Equal(t, "nosniff", rec.Header().Get("X-Content-Type-Options"))
	require.Equal(t, "no-referrer", rec.Header().Get("Referrer-Policy"))
	require.Contains(t, rec.Header().Get("Content-Security-Policy"), "default-src 'self'")
}

func TestSiteSupportsHEADWithoutResponseBody(t *testing.T) {
	h := newTestSite(t, "")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodHead, "/", nil))
	require.Equal(t, http.StatusOK, rec.Code)
	body, err := io.ReadAll(rec.Result().Body)
	require.NoError(t, err)
	require.Empty(t, body)
}
