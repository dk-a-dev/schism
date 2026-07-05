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

func TestExpenseActivityData(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Museum tickets","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created store.Expense
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	req, _ := http.NewRequest(http.MethodDelete, srv.URL+"/v1/groups/"+g.ID+"/expenses/"+created.ID, nil)
	delResp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusNoContent, delResp.StatusCode)

	actResp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/activities")
	require.Equal(t, http.StatusOK, actResp.StatusCode)
	var acts []store.Activity
	require.NoError(t, json.NewDecoder(actResp.Body).Decode(&acts))

	byType := map[string]store.Activity{}
	for _, act := range acts {
		byType[act.ActivityType] = act
	}
	require.Contains(t, byType["CREATE_EXPENSE"].Data, "Museum tickets")
	require.Contains(t, byType["DELETE_EXPENSE"].Data, "Museum tickets")
}

func TestCreateExpenseAddedBy(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY","addedBy":%q,
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, a, b)
	resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
	require.NoError(t, err)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created store.Expense
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.Equal(t, a, created.AddedBy)

	getResp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/expenses/" + created.ID)
	require.Equal(t, http.StatusOK, getResp.StatusCode)
	var fetched store.Expense
	require.NoError(t, json.NewDecoder(getResp.Body).Decode(&fetched))
	require.Equal(t, a, fetched.AddedBy)

	// The CREATE_EXPENSE activity's actor is the addedBy participant.
	actResp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/activities")
	require.Equal(t, http.StatusOK, actResp.StatusCode)
	var activities []store.Activity
	require.NoError(t, json.NewDecoder(actResp.Body).Decode(&activities))
	var create *store.Activity
	for i := range activities {
		if activities[i].ActivityType == "CREATE_EXPENSE" && activities[i].ExpenseID != nil && *activities[i].ExpenseID == created.ID {
			create = &activities[i]
			break
		}
	}
	require.NotNil(t, create)
	require.NotNil(t, create.ParticipantID)
	require.Equal(t, a, *create.ParticipantID)
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
