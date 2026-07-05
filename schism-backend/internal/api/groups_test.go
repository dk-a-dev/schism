package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

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
	body := `{"name":"Trip","currency":"$","currencyCode":"USD",
	          "participants":[{"name":"Alice"},{"name":"Bob"}]}`
	resp, err := http.Post(srv.URL+"/v1/groups", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.NotEmpty(t, created.GroupID)

	resp2, err := http.Get(srv.URL + "/v1/groups/" + created.GroupID)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	require.Equal(t, "Trip", g.Name)
	require.Len(t, g.Participants, 2)

	resp3, _ := http.Get(srv.URL + "/v1/groups/nope")
	require.Equal(t, http.StatusNotFound, resp3.StatusCode)
}

func TestListCategoriesHTTP(t *testing.T) {
	srv := newTestServer(t)
	resp, err := http.Get(srv.URL + "/v1/categories")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var cats []store.Category
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&cats))
	require.GreaterOrEqual(t, len(cats), 40)
}
