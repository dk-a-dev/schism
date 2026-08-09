package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	webui "github.com/schism/schism-backend/internal/web"
	"github.com/stretchr/testify/require"
)

func TestSiteRoutesMountWithoutChangingAPIHealth(t *testing.T) {
	site, err := webui.New(webui.Config{SupportEmail: "owner@example.test"})
	require.NoError(t, err)
	router := NewRouter(nil, false, site)

	for _, path := range []string{"/", "/privacy", "/terms", "/support", "/account-deletion"} {
		rec := httptest.NewRecorder()
		router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		require.Equal(t, http.StatusOK, rec.Code, path)
	}

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	require.Equal(t, http.StatusOK, rec.Code)
}
