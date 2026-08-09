package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5/middleware"
	"github.com/stretchr/testify/require"
)

type requestBoundaryPayload struct {
	Name string `json:"name"`
}

func decodeBoundaryRequest(body, contentType string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/boundary", strings.NewReader(body))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	rec := httptest.NewRecorder()
	var payload requestBoundaryPayload
	if decodeJSON(rec, req, &payload) {
		writeJSON(rec, http.StatusOK, payload)
	}
	return rec
}

func TestDecodeJSONRejectsBodyLargerThanOneMiB(t *testing.T) {
	body := `{"name":"` + strings.Repeat("a", (1<<20)+1) + `"}`
	rec := decodeBoundaryRequest(body, "application/json")
	require.Equal(t, http.StatusRequestEntityTooLarge, rec.Code)
	require.JSONEq(t, `{"error":"request_too_large"}`, rec.Body.String())
}

func TestDecodeJSONRejectsUnknownField(t *testing.T) {
	rec := decodeBoundaryRequest(`{"name":"Asha","admin":true}`, "application/json")
	require.Equal(t, http.StatusBadRequest, rec.Code)
	require.JSONEq(t, `{"error":"invalid_json"}`, rec.Body.String())
}

func TestDecodeJSONRejectsSecondDocument(t *testing.T) {
	rec := decodeBoundaryRequest(`{"name":"Asha"}{"name":"Rohan"}`, "application/json")
	require.Equal(t, http.StatusBadRequest, rec.Code)
	require.JSONEq(t, `{"error":"invalid_json"}`, rec.Body.String())
}

func TestDecodeJSONRejectsNonJSONContentType(t *testing.T) {
	rec := decodeBoundaryRequest(`{"name":"Asha"}`, "text/plain")
	require.Equal(t, http.StatusUnsupportedMediaType, rec.Code)
	require.JSONEq(t, `{"error":"content_type_must_be_application_json"}`, rec.Body.String())
}

func TestDecodeJSONAcceptsJSONWithCharset(t *testing.T) {
	rec := decodeBoundaryRequest(`{"name":"Asha"}`, "application/json; charset=utf-8")
	require.Equal(t, http.StatusOK, rec.Code)
	require.JSONEq(t, `{"name":"Asha"}`, rec.Body.String())
}

func TestHealthReturnsValidJSON(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	NewRouter(nil, false).ServeHTTP(rec, req)

	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, "application/json", rec.Header().Get("Content-Type"))
	var payload map[string]string
	require.NoError(t, json.NewDecoder(bytes.NewReader(rec.Body.Bytes())).Decode(&payload))
	require.Equal(t, map[string]string{"status": "ok"}, payload)
}

func TestSecurityHeadersAndRequestIDApplyWithoutAccessLogging(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	rec := httptest.NewRecorder()
	NewRouter(nil, false).ServeHTTP(rec, req)

	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, "nosniff", rec.Header().Get("X-Content-Type-Options"))
	require.Equal(t, "no-referrer", rec.Header().Get("Referrer-Policy"))
	require.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
	require.NotEmpty(t, rec.Header().Get("X-Request-ID"))
}

func TestSanitizedErrorDoesNotExposeStoreMessage(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/failure", nil)
	rec := httptest.NewRecorder()
	writeInternalError(rec, req, errors.New("postgres password=top-secret"))

	require.Equal(t, http.StatusInternalServerError, rec.Code)
	require.JSONEq(t, `{"error":"internal_error"}`, rec.Body.String())
	require.NotContains(t, rec.Body.String(), "postgres")
	require.NotContains(t, rec.Body.String(), "top-secret")
}

func TestRecoveryReturnsSanitizedJSON(t *testing.T) {
	panicHandler := http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("database password=top-secret")
	})
	handler := middleware.RequestID(securityHeaders(recoverPanics(panicHandler)))
	req := httptest.NewRequest(http.MethodGet, "/panic", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	require.Equal(t, http.StatusInternalServerError, rec.Code)
	require.JSONEq(t, `{"error":"internal_error"}`, rec.Body.String())
	require.NotContains(t, rec.Body.String(), "database")
	require.NotContains(t, rec.Body.String(), "top-secret")
}
