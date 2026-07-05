package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

func createGroupFixture(t *testing.T, srvURL string) store.Group {
	body := `{"name":"Trip","currency":"$","participants":[{"name":"A"},{"name":"B"}]}`
	resp, _ := http.Post(srvURL+"/v1/groups", "application/json", bytes.NewBufferString(body))
	var created struct {
		GroupID string `json:"groupId"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&created)
	resp2, _ := http.Get(srvURL + "/v1/groups/" + created.GroupID)
	var g store.Group
	_ = json.NewDecoder(resp2.Body).Decode(&g)
	return g
}

func TestExpenseAndBalancesHTTP(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)

	resp2, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/balances")
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	var out struct {
		Balances map[string]struct {
			Paid, PaidFor, Total int64
		} `json:"balances"`
		Reimbursements []struct {
			From, To string
			Amount   int64
		} `json:"reimbursements"`
	}
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&out))
	require.Equal(t, int64(500), out.Balances[a].Total)
	require.Equal(t, int64(-500), out.Balances[b].Total)
	require.Len(t, out.Reimbursements, 1)
	require.Equal(t, b, out.Reimbursements[0].From)
	require.Equal(t, a, out.Reimbursements[0].To)
	require.Equal(t, int64(500), out.Reimbursements[0].Amount)
}

func TestCreateExpenseValidation(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a := g.Participants[0].ID
	body := fmt.Sprintf(`{"title":"Bad","amount":0,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100}]}`, a, a)
	resp, _ := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
