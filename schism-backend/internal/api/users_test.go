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

func registerUser(t *testing.T, srv string, name, email, phone string) store.User {
	t.Helper()
	u, _ := registerUserToken(t, srv, name, email, phone)
	return u
}

// registerUserToken creates the password-backed account used by API integration fixtures and returns
// its one-time bearer token.
func registerUserToken(t *testing.T, srv string, name, email, phone string) (store.User, string) {
	t.Helper()
	body := fmt.Sprintf(`{"name":%q,"email":%q,"phone":%q,"password":"password1"}`, name, email, phone)
	resp, err := http.Post(srv+"/v1/auth/register", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var out authResponse
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&out))
	return store.User{ID: out.ID, Name: out.Name, Email: out.Email, Phone: phone}, out.Token
}

func TestGroupParticipantUserID(t *testing.T) {
	srv := newTestServer(t)
	u, token := registerUserToken(t, srv.URL, "Carol", "carol-"+id.New()+"@example.com", "444")

	// The caller must authenticate; the backend links exactly their own participant.
	body := fmt.Sprintf(`{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Carol","userId":%q},{"name":"Dave"}]}`, u.ID)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups", token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	resp2 := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+created.GroupID, token, "")
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	require.Len(t, g.Participants, 2)

	var carol, dave *store.Participant
	for i := range g.Participants {
		switch g.Participants[i].Name {
		case "Carol":
			carol = &g.Participants[i]
		case "Dave":
			dave = &g.Participants[i]
		}
	}
	require.NotNil(t, carol)
	require.NotNil(t, carol.UserID)
	require.Equal(t, u.ID, *carol.UserID)
	require.NotNil(t, dave)
	require.Nil(t, dave.UserID)
}
