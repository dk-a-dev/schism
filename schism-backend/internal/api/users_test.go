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
	body := fmt.Sprintf(`{"name":%q,"email":%q,"phone":%q}`, name, email, phone)
	resp, err := http.Post(srv+"/v1/users", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var u store.User
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&u))
	return u
}

func TestRegisterUserUpsert(t *testing.T) {
	srv := newTestServer(t)

	// Same email upserts onto the same id, updating the name.
	email := "alice-" + id.New() + "@example.com"
	u1 := registerUser(t, srv.URL, "Alice", email, "111")
	require.NotEmpty(t, u1.ID)
	require.Equal(t, "Alice", u1.Name)

	u2 := registerUser(t, srv.URL, "Alice Cooper", email, "222")
	require.Equal(t, u1.ID, u2.ID)
	require.Equal(t, "Alice Cooper", u2.Name)
	require.Equal(t, "222", u2.Phone)

	// Empty email always inserts a distinct id.
	e1 := registerUser(t, srv.URL, "Anon", "", "")
	e2 := registerUser(t, srv.URL, "Anon", "", "")
	require.NotEmpty(t, e1.ID)
	require.NotEmpty(t, e2.ID)
	require.NotEqual(t, e1.ID, e2.ID)
}

func TestGetUser(t *testing.T) {
	srv := newTestServer(t)
	u := registerUser(t, srv.URL, "Bob", "bob-"+id.New()+"@example.com", "333")

	resp, err := http.Get(srv.URL + "/v1/users/" + u.ID)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var got store.User
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	require.Equal(t, u.ID, got.ID)
	require.Equal(t, "Bob", got.Name)

	resp2, _ := http.Get(srv.URL + "/v1/users/nope")
	require.Equal(t, http.StatusNotFound, resp2.StatusCode)
}

func TestGroupParticipantUserID(t *testing.T) {
	srv := newTestServer(t)
	u := registerUser(t, srv.URL, "Carol", "carol-"+id.New()+"@example.com", "444")

	body := fmt.Sprintf(`{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Carol","userId":%q},{"name":"Dave"}]}`, u.ID)
	resp, err := http.Post(srv.URL+"/v1/groups", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	resp2, err := http.Get(srv.URL + "/v1/groups/" + created.GroupID)
	require.NoError(t, err)
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
