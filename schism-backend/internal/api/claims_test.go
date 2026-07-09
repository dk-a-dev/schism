package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

// claimFixture registers a user and creates a group whose first participant is linked to that user,
// returning the group, the caller's token, and the caller's participant id (the creator).
func claimFixture(t *testing.T, srvURL string) (store.Group, string, string) {
	t.Helper()
	u, token := registerUserToken(t, srvURL, "Dev", "dev-"+id.New()+"@example.com", "1")
	body := fmt.Sprintf(`{"name":"Trip","currency":"₹","participants":[{"name":"Dev","userId":%q},{"name":"Ru"}]}`, u.ID)
	resp := authRequest(t, http.MethodPost, srvURL+"/v1/groups", token, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var created struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&created))

	resp2, _ := http.Get(srvURL + "/v1/groups/" + created.GroupID)
	var g store.Group
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&g))

	var creator string
	for _, p := range g.Participants {
		if p.UserID != nil && *p.UserID == u.ID {
			creator = p.ID
		}
	}
	require.NotEmpty(t, creator)
	return g, token, creator
}

func TestClaimSessionAPIFlow(t *testing.T) {
	srv := newTestServer(t)
	g, token, creator := claimFixture(t, srv.URL)

	// Create a session (201).
	createBody := `{"title":"Dinner","currency":"₹","items":[{"idx":0,"name":"Dish","qty":3,"amountMinor":30000}],"taxMinor":3000}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID      string `json:"id"`
		Status  string `json:"status"`
		Version int    `json:"version"`
		Items   []struct {
			AmountMinor int64 `json:"amountMinor"`
		} `json:"items"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))
	require.NotEmpty(t, session.ID)
	require.Equal(t, "open", session.Status)

	// GET (200 with items).
	getResp := authRequest(t, http.MethodGet, srv.URL+"/v1/claim-sessions/"+session.ID, token, "")
	require.Equal(t, http.StatusOK, getResp.StatusCode)
	var got struct {
		Items []struct {
			AmountMinor int64 `json:"amountMinor"`
		} `json:"items"`
	}
	require.NoError(t, json.NewDecoder(getResp.Body).Decode(&got))
	require.Len(t, got.Items, 1)
	require.Equal(t, int64(30000), got.Items[0].AmountMinor)

	// PUT claims (creator weights item 0) → 200.
	putBody := `{"expectedVersion":1,"weights":[{"itemIdx":0,"weight":2}]}`
	putResp := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/claims", token, putBody)
	require.Equal(t, http.StatusOK, putResp.StatusCode)

	// GET shows the claim + owesPreview.
	get2 := authRequest(t, http.MethodGet, srv.URL+"/v1/claim-sessions/"+session.ID, token, "")
	require.Equal(t, http.StatusOK, get2.StatusCode)
	var withClaims struct {
		Claims []struct {
			ItemIdx       int     `json:"itemIdx"`
			ParticipantID string  `json:"participantId"`
			Weight        float64 `json:"weight"`
		} `json:"claims"`
		OwesPreview map[string]int64 `json:"owesPreview"`
	}
	require.NoError(t, json.NewDecoder(get2.Body).Decode(&withClaims))
	require.Len(t, withClaims.Claims, 1)
	require.Equal(t, creator, withClaims.Claims[0].ParticipantID)
	// Only the creator claimed everything → they owe the whole 30000 + 3000 tax.
	require.Equal(t, int64(33000), withClaims.OwesPreview[creator])

	// Finalize (200 with expenseId).
	finBody := `{"expectedVersion":1,"resolutions":[]}`
	finResp := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/finalize", token, finBody)
	require.Equal(t, http.StatusOK, finResp.StatusCode)
	var fin struct {
		ExpenseID string `json:"expenseId"`
	}
	require.NoError(t, json.NewDecoder(finResp.Body).Decode(&fin))
	require.NotEmpty(t, fin.ExpenseID)

	// PUT after finalize → 409 LOCKED.
	put2 := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/claims", token, putBody)
	require.Equal(t, http.StatusConflict, put2.StatusCode)
	var errBody struct {
		Error string `json:"error"`
	}
	require.NoError(t, json.NewDecoder(put2.Body).Decode(&errBody))
	require.Equal(t, "LOCKED", errBody.Error)
}

// activitiesFor fetches a group's activity feed keyed by activity type (last one wins for repeated
// types, which is fine for these tests since they assert on a single occurrence per type).
func activitiesFor(t *testing.T, srvURL, groupID string) map[string]store.Activity {
	t.Helper()
	resp, err := http.Get(srvURL + "/v1/groups/" + groupID + "/activities")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var acts []store.Activity
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&acts))
	byType := map[string]store.Activity{}
	for _, a := range acts {
		byType[a.ActivityType] = a
	}
	return byType
}

func TestClaimSessionActivityFullFlow(t *testing.T) {
	srv := newTestServer(t)
	g, token, creator := claimFixture(t, srv.URL)

	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	byType := activitiesFor(t, srv.URL, g.ID)
	require.Contains(t, byType, "CLAIM_SESSION_CREATED")
	require.Equal(t, "Dinner", byType["CLAIM_SESSION_CREATED"].Data)
	require.NotNil(t, byType["CLAIM_SESSION_CREATED"].ParticipantID)
	require.Equal(t, creator, *byType["CLAIM_SESSION_CREATED"].ParticipantID)

	putBody := `{"expectedVersion":1,"weights":[{"itemIdx":0,"weight":1}]}`
	putResp := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/claims", token, putBody)
	require.Equal(t, http.StatusOK, putResp.StatusCode)

	byType = activitiesFor(t, srv.URL, g.ID)
	require.Contains(t, byType, "CLAIM_SUBMITTED")
	require.Contains(t, byType["CLAIM_SUBMITTED"].Data, "1 item")

	finBody := `{"expectedVersion":1,"resolutions":[]}`
	finResp := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/finalize", token, finBody)
	require.Equal(t, http.StatusOK, finResp.StatusCode)
	var fin struct {
		ExpenseID string `json:"expenseId"`
	}
	require.NoError(t, json.NewDecoder(finResp.Body).Decode(&fin))
	require.NotEmpty(t, fin.ExpenseID)

	byType = activitiesFor(t, srv.URL, g.ID)
	require.Contains(t, byType, "CLAIM_SESSION_FINALIZED")
	require.Equal(t, "Dinner", byType["CLAIM_SESSION_FINALIZED"].Data)
	require.Contains(t, byType, "CREATE_EXPENSE")
	require.NotNil(t, byType["CREATE_EXPENSE"].ExpenseID)
	require.Equal(t, fin.ExpenseID, *byType["CREATE_EXPENSE"].ExpenseID)

	// Re-finalizing (idempotent replay) must not log a second FINALIZED/CREATE_EXPENSE pair.
	finResp2 := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/finalize", token, finBody)
	require.Equal(t, http.StatusOK, finResp2.StatusCode)
	resp2, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/activities")
	var acts2 []store.Activity
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&acts2))
	count := 0
	for _, a := range acts2 {
		if a.ActivityType == "CLAIM_SESSION_FINALIZED" {
			count++
		}
	}
	require.Equal(t, 1, count)
}

func TestEditClaimItemsLogsActivity(t *testing.T) {
	srv := newTestServer(t)
	g, token, _ := claimFixture(t, srv.URL)

	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	editBody := `{"items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000},{"idx":1,"name":"Drink","qty":1,"amountMinor":5000}]}`
	editResp := authRequest(t, http.MethodPatch, srv.URL+"/v1/claim-sessions/"+session.ID+"/items", token, editBody)
	require.Equal(t, http.StatusOK, editResp.StatusCode)

	byType := activitiesFor(t, srv.URL, g.ID)
	require.Contains(t, byType, "CLAIM_ITEMS_EDITED")
	require.Contains(t, byType["CLAIM_ITEMS_EDITED"].Data, "2 items")
}

func TestCancelClaimSessionLogsActivityOnce(t *testing.T) {
	srv := newTestServer(t)
	g, token, _ := claimFixture(t, srv.URL)

	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":10000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	cancelResp := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/cancel", token, "")
	require.Equal(t, http.StatusNoContent, cancelResp.StatusCode)

	byType := activitiesFor(t, srv.URL, g.ID)
	require.Contains(t, byType, "CLAIM_SESSION_CANCELLED")
	require.Equal(t, "Dinner", byType["CLAIM_SESSION_CANCELLED"].Data)

	// Cancelling again is a store-level no-op; it must not log a second activity.
	cancelResp2 := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/cancel", token, "")
	require.Equal(t, http.StatusNoContent, cancelResp2.StatusCode)
	resp2, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/activities")
	var acts2 []store.Activity
	require.NoError(t, json.NewDecoder(resp2.Body).Decode(&acts2))
	count := 0
	for _, a := range acts2 {
		if a.ActivityType == "CLAIM_SESSION_CANCELLED" {
			count++
		}
	}
	require.Equal(t, 1, count)
}

func TestFinalizeUnresolvedItemsMapsTo409(t *testing.T) {
	srv := newTestServer(t)
	g, token, _ := claimFixture(t, srv.URL)

	// Two items, only one gets claimed — the other is neither claimed nor resolved.
	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Claimed","qty":1,"amountMinor":10000},` +
		`{"idx":1,"name":"Unclaimed","qty":1,"amountMinor":5000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	putBody := `{"expectedVersion":1,"weights":[{"itemIdx":0,"weight":1}]}`
	putResp := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/claims", token, putBody)
	require.Equal(t, http.StatusOK, putResp.StatusCode)

	// Finalize with no resolutions → 409 UNRESOLVED_ITEMS (item 1 is unresolved, not silently dropped).
	finBody := `{"expectedVersion":1,"resolutions":[]}`
	finResp := authRequest(t, http.MethodPost, srv.URL+"/v1/claim-sessions/"+session.ID+"/finalize", token, finBody)
	require.Equal(t, http.StatusConflict, finResp.StatusCode)
	var errBody struct {
		Error string `json:"error"`
	}
	require.NoError(t, json.NewDecoder(finResp.Body).Decode(&errBody))
	require.Equal(t, "UNRESOLVED_ITEMS", errBody.Error)
}

func TestClaimSessionRequiresMembership(t *testing.T) {
	srv := newTestServer(t)
	g, token, _ := claimFixture(t, srv.URL)
	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":1000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	// A different user who isn't in the group → 403 on GET.
	_, otherToken := registerUserToken(t, srv.URL, "Mallory", "m-"+id.New()+"@example.com", "9")
	other := authRequest(t, http.MethodGet, srv.URL+"/v1/claim-sessions/"+session.ID, otherToken, "")
	require.Equal(t, http.StatusForbidden, other.StatusCode)

	// Unauthenticated → 401.
	noauth := authRequest(t, http.MethodGet, srv.URL+"/v1/claim-sessions/"+session.ID, "", "")
	require.Equal(t, http.StatusUnauthorized, noauth.StatusCode)
}

func TestSetReadyAsMemberAndNonMember(t *testing.T) {
	srv := newTestServer(t)
	g, token, creator := claimFixture(t, srv.URL)

	createBody := `{"title":"Dinner","items":[{"idx":0,"name":"Dish","qty":1,"amountMinor":1000}]}`
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+g.ID+"/claim-sessions", token, createBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var session struct {
		ID string `json:"id"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&session))

	// A member marking themselves ready → 200 with readyParticipantIds containing the caller.
	readyResp := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/ready", token, `{"ready":true}`)
	require.Equal(t, http.StatusOK, readyResp.StatusCode)
	var ready struct {
		ReadyParticipantIds []string `json:"readyParticipantIds"`
	}
	require.NoError(t, json.NewDecoder(readyResp.Body).Decode(&ready))
	require.Contains(t, ready.ReadyParticipantIds, creator)

	// A non-member → 403.
	_, otherToken := registerUserToken(t, srv.URL, "Mallory", "m-"+id.New()+"@example.com", "9")
	other := authRequest(t, http.MethodPut, srv.URL+"/v1/claim-sessions/"+session.ID+"/ready", otherToken, `{"ready":true}`)
	require.Equal(t, http.StatusForbidden, other.StatusCode)
}

func TestClaimDeepLinkLanding(t *testing.T) {
	srv := newTestServer(t)
	resp, err := http.Get(srv.URL + "/c/test-sid")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	require.Contains(t, resp.Header.Get("Content-Type"), "text/html")
	body, _ := io.ReadAll(resp.Body)
	require.True(t, strings.Contains(string(body), "schism://claim/test-sid"),
		"landing must contain the claim deep link")
}
