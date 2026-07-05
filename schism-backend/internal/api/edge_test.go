package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

func doJSON(t *testing.T, method, url, body string, headers map[string]string) *http.Response {
	req, err := http.NewRequest(method, url, bytes.NewBufferString(body))
	require.NoError(t, err)
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	return resp
}

func TestCreateGroupRejectsShortName(t *testing.T) {
	srv := newTestServer(t)
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups",
		`{"name":"X","currency":"$","participants":[{"name":"A"}]}`, nil)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestCreateGroupRejectsNoParticipants(t *testing.T) {
	srv := newTestServer(t)
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups",
		`{"name":"Trip","currency":"$","participants":[]}`, nil)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestCreateGroupRejectsBadJSON(t *testing.T) {
	srv := newTestServer(t)
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups", `{not json`, nil)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestExpenseOnMissingGroup404(t *testing.T) {
	srv := newTestServer(t)
	body := `{"title":"x","amount":100,"paidById":"a","splitMode":"EVENLY","paidFor":[{"participantId":"a","shares":100}]}`
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups/nope/expenses", body, nil)
	require.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestExpensePercentageMustSum100(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID
	body := fmt.Sprintf(`{"title":"x","amount":1000,"paidById":%q,"splitMode":"BY_PERCENTAGE",
	  "paidFor":[{"participantId":%q,"shares":3000},{"participantId":%q,"shares":6000}]}`, a, a, b)
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, nil)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestUpdateAndDeleteExpenseFlow(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	resp := doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, nil)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.NotEmpty(t, created.ID)

	upd := fmt.Sprintf(`{"title":"Dinner2","amount":1200,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, b, a, b)
	resp2 := doJSON(t, http.MethodPut, srv.URL+"/v1/groups/"+g.ID+"/expenses/"+created.ID, upd, nil)
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var updated struct {
		Title  string `json:"title"`
		Amount int64  `json:"amount"`
	}
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&updated))
	require.Equal(t, "Dinner2", updated.Title)
	require.Equal(t, int64(1200), updated.Amount)

	resp3 := doJSON(t, http.MethodDelete, srv.URL+"/v1/groups/"+g.ID+"/expenses/"+created.ID, "", nil)
	require.Equal(t, http.StatusNoContent, resp3.StatusCode)

	resp4, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/expenses/" + created.ID)
	require.Equal(t, http.StatusNotFound, resp4.StatusCode)
}

func TestIdempotencyHeaderDedupes(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID
	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	h := map[string]string{"Idempotency-Key": "abc-123"}

	r1 := doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, h)
	r2 := doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, h)
	var e1, e2 struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(r1.Body).Decode(&e1))
	require.NoError(t, json.NewDecoder(r2.Body).Decode(&e2))
	require.Equal(t, e1.ID, e2.ID)

	resp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/expenses")
	var list []map[string]any
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&list))
	require.Len(t, list, 1)
}

func TestListGroupsByIDs(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)

	resp, _ := http.Get(srv.URL + "/v1/groups?ids=" + g.ID + ",missing")
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var groups []map[string]any
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&groups))
	require.Len(t, groups, 1)

	resp2, _ := http.Get(srv.URL + "/v1/groups")
	var empty []map[string]any
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&empty))
	require.Empty(t, empty)
}

func TestGroupDashboardEndpoint(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID
	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"categoryId":7,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, nil).Body.Close()

	resp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/dashboard?participant=" + a)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var dash struct {
		TotalSpending int64 `json:"totalSpending"`
		ByCategory    []struct {
			Name   string `json:"name"`
			Amount int64  `json:"amount"`
		} `json:"byCategory"`
		Personal *struct {
			Share int64 `json:"share"`
		} `json:"personal"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&dash))
	require.Equal(t, int64(1000), dash.TotalSpending)
	require.Len(t, dash.ByCategory, 1)
	require.Equal(t, "Groceries", dash.ByCategory[0].Name)
	require.NotNil(t, dash.Personal)
	require.Equal(t, int64(500), dash.Personal.Share)
}

func TestPersonalDashboardEndpoint(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL) // participants A, B
	a, b := g.Participants[0].ID, g.Participants[1].ID
	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	doJSON(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", body, nil).Body.Close()

	resp, _ := http.Get(srv.URL + "/v1/dashboard?participant=A&groupIds=" + g.ID)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var pd struct {
		GroupCount int `json:"groupCount"`
		Totals     []struct {
			Net int64 `json:"net"`
		} `json:"totals"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&pd))
	require.Equal(t, 1, pd.GroupCount)
	require.Len(t, pd.Totals, 1)
	require.Equal(t, int64(500), pd.Totals[0].Net) // A paid 1000, owes 500

	resp2, _ := http.Get(srv.URL + "/v1/dashboard") // missing participant
	require.Equal(t, http.StatusBadRequest, resp2.StatusCode)
	io.Copy(io.Discard, resp2.Body)
}
