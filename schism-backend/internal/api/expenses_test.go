package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

type groupFixture struct {
	store.Group
	Token string
}

func createGroupFixture(t *testing.T, srvURL string) groupFixture {
	user, token := registerUserToken(t, srvURL, "A", "fixture-"+id.New()+"@example.com", "")
	body := fmt.Sprintf(`{"name":"Trip","currency":"$","participants":[{"name":"A","userId":%q},{"name":"B"}]}`, user.ID)
	resp := authRequest(t, http.MethodPost, srvURL+"/v1/groups", token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	resp2 := authRequest(t, http.MethodGet, srvURL+"/v1/groups/"+created.GroupID, token, "")
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))
	return groupFixture{Group: g, Token: token}
}

func TestExpenseAndBalancesHTTP(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	body := fmt.Sprintf(`{"title":"Dinner","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, a, a, b)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", g.Token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)

	resp2 := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/balances", g.Token, "")
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
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", g.Token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created store.Expense
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	delResp := authRequest(t, http.MethodDelete, srv.URL+"/v1/groups/"+g.ID+"/expenses/"+created.ID, g.Token, "")
	require.Equal(t, http.StatusNoContent, delResp.StatusCode)

	actResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/activities", g.Token, "")
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
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", g.Token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created store.Expense
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))
	require.Equal(t, a, created.AddedBy)

	getResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/expenses/"+created.ID, g.Token, "")
	require.Equal(t, http.StatusOK, getResp.StatusCode)
	var fetched store.Expense
	require.NoError(t, json.NewDecoder(getResp.Body).Decode(&fetched))
	require.Equal(t, a, fetched.AddedBy)

	// The CREATE_EXPENSE activity's actor is the addedBy participant.
	actResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+g.ID+"/activities", g.Token, "")
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
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/expenses", g.Token, body)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
