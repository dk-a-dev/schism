package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

// authRequest performs an HTTP request carrying `Authorization: Bearer <token>` when token != "".
func authRequest(t *testing.T, method, url, token, body string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, url, bytes.NewBufferString(body))
	require.NoError(t, err)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	return resp
}

func TestRegisterReturnsToken(t *testing.T) {
	srv := newTestServer(t)
	u, token := registerUserToken(t, srv.URL, "Eve", "eve-"+id.New()+"@example.com", "555")
	require.NotEmpty(t, u.ID)
	require.NotEmpty(t, token)
}

func TestUsersMe(t *testing.T) {
	srv := newTestServer(t)
	u, token := registerUserToken(t, srv.URL, "Frank", "frank-"+id.New()+"@example.com", "666")

	// With the bearer token → 200 and the matching user.
	resp := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", token, "")
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var got store.User
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	require.Equal(t, u.ID, got.ID)

	// Without the header → 401.
	no := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", "", "")
	require.Equal(t, http.StatusUnauthorized, no.StatusCode)

	// Garbage token → 401.
	bad := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", "not-a-real-token", "")
	require.Equal(t, http.StatusUnauthorized, bad.StatusCode)
}

// TestParticipantUserIDSanitized proves identity is enforced server-side: a participant userId is
// kept only when it matches the authenticated caller; any other id (or no auth) comes back null.
func TestParticipantUserIDSanitized(t *testing.T) {
	srv := newTestServer(t)
	u, token := registerUserToken(t, srv.URL, "Grace", "grace-"+id.New()+"@example.com", "777")
	other := registerUser(t, srv.URL, "Heidi", "heidi-"+id.New()+"@example.com", "888")

	// Case 1: linking to SOMEONE ELSE's id while authenticated → sanitized to null.
	body1 := fmt.Sprintf(`{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Grace","userId":%q}]}`, other.ID)
	require.Nil(t, createAndFetchUserID(t, srv.URL, token, body1, "Grace"))

	// Case 2: linking to the caller's OWN id with no auth → sanitized to null.
	body2 := fmt.Sprintf(`{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Grace","userId":%q}]}`, u.ID)
	require.Nil(t, createAndFetchUserID(t, srv.URL, "", body2, "Grace"))

	// Case 3: linking to the caller's OWN id WHILE authenticated → preserved.
	got := createAndFetchUserID(t, srv.URL, token, body2, "Grace")
	require.NotNil(t, got)
	require.Equal(t, u.ID, *got)
}

// TestAuthLoginPreservesOtherSessions is the API-level multi-session assertion: logging in again
// (e.g. a second device) must not sign out an already-authenticated session.
func TestAuthLoginPreservesOtherSessions(t *testing.T) {
	srv := newTestServer(t)
	email := "session-" + id.New() + "@example.com"

	regBody := fmt.Sprintf(`{"name":"Sasha","email":%q,"password":"hunter22","phone":""}`, email)
	regResp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "", regBody)
	require.Equal(t, http.StatusOK, regResp.StatusCode)
	var reg authResponse
	require.NoError(t, json.NewDecoder(regResp.Body).Decode(&reg))

	loginBody := fmt.Sprintf(`{"email":%q,"password":"hunter22"}`, email)
	loginResp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/login", "", loginBody)
	require.Equal(t, http.StatusOK, loginResp.StatusCode)
	var login authResponse
	require.NoError(t, json.NewDecoder(loginResp.Body).Decode(&login))
	require.NotEqual(t, reg.Token, login.Token)

	// BOTH sessions are still valid — logging in again did not kick out the first device.
	meOriginal := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", reg.Token, "")
	require.Equal(t, http.StatusOK, meOriginal.StatusCode)
	meNew := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", login.Token, "")
	require.Equal(t, http.StatusOK, meNew.StatusCode)
}

func TestAuthLogoutEndsOnlyThatSession(t *testing.T) {
	srv := newTestServer(t)
	email := "logout-api-" + id.New() + "@example.com"

	regBody := fmt.Sprintf(`{"name":"Tux","email":%q,"password":"hunter22","phone":""}`, email)
	regResp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "", regBody)
	require.Equal(t, http.StatusOK, regResp.StatusCode)
	var reg authResponse
	require.NoError(t, json.NewDecoder(regResp.Body).Decode(&reg))

	loginBody := fmt.Sprintf(`{"email":%q,"password":"hunter22"}`, email)
	loginResp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/login", "", loginBody)
	require.Equal(t, http.StatusOK, loginResp.StatusCode)
	var login authResponse
	require.NoError(t, json.NewDecoder(loginResp.Body).Decode(&login))

	logoutResp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/logout", reg.Token, "")
	require.Equal(t, http.StatusNoContent, logoutResp.StatusCode)

	// The logged-out session is gone...
	meOriginal := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", reg.Token, "")
	require.Equal(t, http.StatusUnauthorized, meOriginal.StatusCode)

	// ...but the OTHER session (the second login) is untouched.
	meOther := authRequest(t, http.MethodGet, srv.URL+"/v1/users/me", login.Token, "")
	require.Equal(t, http.StatusOK, meOther.StatusCode)
}

func TestAuthLogoutRequiresAuth(t *testing.T) {
	srv := newTestServer(t)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/logout", "", "")
	require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestRequireUserRejectsMissingSession(t *testing.T) {
	h := &Handler{}
	protected := h.requireUser(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	rec := httptest.NewRecorder()
	protected.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/protected", nil))
	require.Equal(t, http.StatusUnauthorized, rec.Code)
	require.JSONEq(t, `{"error":"unauthorized"}`, rec.Body.String())
}

func TestRequireUserAllowsResolvedSession(t *testing.T) {
	h := &Handler{}
	protected := h.requireUser(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req = req.WithContext(context.WithValue(req.Context(), userKey, &store.User{ID: "user-1"}))
	rec := httptest.NewRecorder()
	protected.ServeHTTP(rec, req)
	require.Equal(t, http.StatusNoContent, rec.Code)
}

func TestAuthRegisterValidatesAndNormalizesIdentity(t *testing.T) {
	unique := id.New()
	cases := []struct {
		name string
		body string
	}{
		{"empty name", fmt.Sprintf(`{"name":"  ","email":"empty-%s@example.com","password":"password1"}`, unique)},
		{"seven character password", fmt.Sprintf(`{"name":"Asha","email":"short-%s@example.com","password":"1234567"}`, unique)},
		{"password over one hundred twenty eight characters", fmt.Sprintf(`{"name":"Asha","email":"password-%s@example.com","password":%q}`, unique, strings.Repeat("p", 129))},
		{"name over one hundred characters", fmt.Sprintf(`{"name":%q,"email":"name-%s@example.com","password":"password1"}`, strings.Repeat("n", 101), unique)},
		{"email over one hundred twenty characters", fmt.Sprintf(`{"name":"Asha","email":%q,"password":"password1"}`, strings.Repeat("e", 109)+"@example.com")},
		{"phone over thirty two characters", fmt.Sprintf(`{"name":"Asha","email":"phone-%s@example.com","password":"password1","phone":%q}`, unique, strings.Repeat("1", 33))},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := newTestServer(t)
			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "", tc.body)
			defer resp.Body.Close()
			require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		})
	}

	srv := newTestServer(t)
	email := "  MixedCase-" + unique + "@Example.COM  "
	body := fmt.Sprintf(`{"name":"  Asha  ","email":%q,"password":"password1"}`, email)
	first := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "", body)
	defer first.Body.Close()
	require.Equal(t, http.StatusOK, first.StatusCode)
	var registered authResponse
	require.NoError(t, json.NewDecoder(first.Body).Decode(&registered))
	require.Equal(t, "Asha", registered.Name)
	require.Equal(t, strings.ToLower(strings.TrimSpace(email)), registered.Email)

	conflict := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "",
		fmt.Sprintf(`{"name":"Rohan","email":%q,"password":"password2"}`, strings.ToLower(strings.TrimSpace(email))))
	defer conflict.Body.Close()
	require.Equal(t, http.StatusConflict, conflict.StatusCode)
}

func TestAuthLoginRejectsOversizedCredentials(t *testing.T) {
	srv := newTestServer(t)
	cases := []string{
		fmt.Sprintf(`{"email":%q,"password":"password1"}`, strings.Repeat("e", 109)+"@example.com"),
		fmt.Sprintf(`{"email":"asha@example.com","password":%q}`, strings.Repeat("p", 129)),
	}
	for _, body := range cases {
		resp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/login", "", body)
		resp.Body.Close()
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	}
}

func TestLoginRateLimitRejectsSixthRapidAttempt(t *testing.T) {
	srv := newTestServer(t)
	email := "rate-" + id.New() + "@example.com"
	reg := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/register", "",
		fmt.Sprintf(`{"name":"Asha","email":%q,"password":"password1"}`, email))
	reg.Body.Close()
	require.Equal(t, http.StatusOK, reg.StatusCode)

	for attempt := 1; attempt <= 6; attempt++ {
		resp := authRequest(t, http.MethodPost, srv.URL+"/v1/auth/login", "",
			fmt.Sprintf(`{"email":%q,"password":"wrong-password"}`, email))
		resp.Body.Close()
		if attempt <= 5 {
			require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
			continue
		}
		require.Equal(t, http.StatusTooManyRequests, resp.StatusCode)
		require.NotEmpty(t, resp.Header.Get("Retry-After"))
	}
}

func TestLegacyUserRegistrationRouteIsDisabled(t *testing.T) {
	srv := newTestServer(t)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/users", "", `{"name":"Legacy"}`)
	defer resp.Body.Close()
	require.Equal(t, http.StatusNotFound, resp.StatusCode)
}

// createAndFetchUserID creates a group (optionally authenticated) then returns the stored userId of
// the named participant.
func createAndFetchUserID(t *testing.T, srv, token, body, name string) *string {
	t.Helper()
	resp := authRequest(t, http.MethodPost, srv+"/v1/groups", token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	resp2, err := http.Get(srv + "/v1/groups/" + created.GroupID)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	for i := range g.Participants {
		if g.Participants[i].Name == name {
			return g.Participants[i].UserID
		}
	}
	t.Fatalf("participant %q not found", name)
	return nil
}
