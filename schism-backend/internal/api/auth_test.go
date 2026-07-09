package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
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
