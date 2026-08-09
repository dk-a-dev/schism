package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

func testURL(t *testing.T) string {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		t.Skip("DATABASE_URL not set")
	}
	return url
}

func newTestServer(t *testing.T) *httptest.Server {
	url := testURL(t)
	require.NoError(t, store.RunMigrations(url))
	pool, err := store.NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	srv := httptest.NewServer(NewRouter(store.NewStore(pool), false))
	t.Cleanup(srv.Close)
	return srv
}

func TestCreateGroupHTTP(t *testing.T) {
	srv := newTestServer(t)
	user, token := registerUserToken(t, srv.URL, "Alice", "alice-http-"+id.New()+"@example.com", "")
	body := fmt.Sprintf(`{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Alice","userId":%q},{"name":"Bob"}]}`, user.ID)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups", token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.NotEmpty(t, created.GroupID)

	resp2 := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+created.GroupID, token, "")
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	require.Equal(t, "Trip", g.Name)
	require.Len(t, g.Participants, 2)

	resp3 := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/nope", token, "")
	require.Equal(t, http.StatusNotFound, resp3.StatusCode)
}

func TestCreateGroupLogsActivity(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)

	actResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/activities", g.Token, "")
	require.Equal(t, http.StatusOK, actResp.StatusCode)
	var acts []store.Activity
	require.NoError(t, json.NewDecoder(actResp.Body).Decode(&acts))
	require.Len(t, acts, 1)
	require.Equal(t, "GROUP_CREATED", acts[0].ActivityType)
	require.Equal(t, "Trip", acts[0].Data)
}

func TestUpdateGroupLogsMemberAndRenameActivity(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL) // participants: A, B

	// Rename the group, drop B, and add C.
	body := fmt.Sprintf(`{"name":"Trip 2","currency":"$",
	  "participants":[{"id":%q,"name":"A"},{"name":"C"}]}`, g.Participants[0].ID)
	resp := authRequest(t, http.MethodPut, srv.URL+"/v1/groups/"+g.ID, g.Token, body)
	require.Equal(t, http.StatusOK, resp.StatusCode)

	actResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/activities", g.Token, "")
	require.Equal(t, http.StatusOK, actResp.StatusCode)
	var acts []store.Activity
	require.NoError(t, json.NewDecoder(actResp.Body).Decode(&acts))

	byType := map[string][]store.Activity{}
	for _, a := range acts {
		byType[a.ActivityType] = append(byType[a.ActivityType], a)
	}
	require.Len(t, byType["GROUP_RENAMED"], 1)
	require.Equal(t, "Trip 2", byType["GROUP_RENAMED"][0].Data)
	require.Len(t, byType["MEMBER_ADDED"], 1)
	require.Equal(t, "C", byType["MEMBER_ADDED"][0].Data)
	require.Len(t, byType["MEMBER_REMOVED"], 1)
	require.Equal(t, "B", byType["MEMBER_REMOVED"][0].Data)
}

func TestListCategoriesHTTP(t *testing.T) {
	srv := newTestServer(t)
	_, token := registerUserToken(t, srv.URL, "Cat", "cat-list-"+id.New()+"@example.com", "")
	resp := authRequest(t, http.MethodGet, srv.URL+"/v1/categories", token, "")
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var cats []store.Category
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&cats))
	require.GreaterOrEqual(t, len(cats), 40)
}
