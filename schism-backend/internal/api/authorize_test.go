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

type authorizationFixture struct {
	serverURL     string
	member        store.User
	memberToken   string
	outsider      store.User
	outsiderToken string
	group         store.Group
	expense       store.Expense
}

func newAuthorizationFixture(t *testing.T) authorizationFixture {
	t.Helper()
	srv := newTestServer(t)
	member, memberToken := registerUserToken(t, srv.URL, "Asha", "member-"+id.New()+"@example.com", "")
	outsider, outsiderToken := registerUserToken(t, srv.URL, "Rohan", "outsider-"+id.New()+"@example.com", "")

	createBody := fmt.Sprintf(`{"name":"Private trip","currency":"₹","currencyCode":"INR","participants":[{"name":"Asha","userId":%q},{"name":"Mira"}]}`, member.ID)
	created := authRequest(t, http.MethodPost, srv.URL+"/v1/groups", memberToken, createBody)
	require.Equal(t, http.StatusCreated, created.StatusCode)
	var createdGroup struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(created.Body).Decode(&createdGroup))
	created.Body.Close()

	groupResp := authRequest(t, http.MethodGet, srv.URL+"/v1/groups/"+createdGroup.GroupID, memberToken, "")
	require.Equal(t, http.StatusOK, groupResp.StatusCode)
	var group store.Group
	require.NoError(t, json.NewDecoder(groupResp.Body).Decode(&group))
	groupResp.Body.Close()

	memberPID := participantIDForUser(t, group, member.ID)
	otherPID := group.Participants[0].ID
	if otherPID == memberPID {
		otherPID = group.Participants[1].ID
	}
	expenseBody := fmt.Sprintf(`{"title":"Seed","amount":1200,"paidById":%q,"splitMode":"EVENLY","paidFor":[{"participantId":%q,"shares":1},{"participantId":%q,"shares":1}]}`, memberPID, memberPID, otherPID)
	expenseResp := authRequest(t, http.MethodPost, srv.URL+"/v1/groups/"+group.ID+"/expenses", memberToken, expenseBody)
	require.Equal(t, http.StatusCreated, expenseResp.StatusCode)
	var expense store.Expense
	require.NoError(t, json.NewDecoder(expenseResp.Body).Decode(&expense))
	expenseResp.Body.Close()

	return authorizationFixture{
		serverURL: srv.URL, member: member, memberToken: memberToken,
		outsider: outsider, outsiderToken: outsiderToken, group: group, expense: expense,
	}
}

func participantIDForUser(t *testing.T, group store.Group, userID string) string {
	t.Helper()
	for _, participant := range group.Participants {
		if participant.UserID != nil && *participant.UserID == userID {
			return participant.ID
		}
	}
	t.Fatalf("group %s has no participant for user %s", group.ID, userID)
	return ""
}

func TestAuthorizationMatrix(t *testing.T) {
	type routeCase struct {
		name       string
		method     string
		path       func(authorizationFixture) string
		body       func(authorizationFixture) string
		memberCode int
	}
	cases := []routeCase{
		{"group", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID }, nil, http.StatusOK},
		{"group details", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/details" }, nil, http.StatusOK},
		{"balances", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/balances" }, nil, http.StatusOK},
		{"activities", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/activities" }, nil, http.StatusOK},
		{"stats", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/stats" }, nil, http.StatusOK},
		{"group dashboard", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/dashboard" }, nil, http.StatusOK},
		{"expense list", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/expenses" }, nil, http.StatusOK},
		{"expense", http.MethodGet, func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/expenses/" + f.expense.ID }, nil, http.StatusOK},
		{
			"group update", http.MethodPut,
			func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID },
			func(f authorizationFixture) string {
				return fmt.Sprintf(`{"name":"Private trip 2","currency":"₹","participants":[{"id":%q,"name":"Asha","userId":%q},{"id":%q,"name":"Mira"}]}`,
					participantIDForUser(t, f.group, f.member.ID), f.member.ID, otherParticipantID(t, f.group, f.member.ID))
			}, http.StatusOK,
		},
		{
			"expense create", http.MethodPost,
			func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/expenses" },
			func(f authorizationFixture) string { return expenseMutationBody(t, f, "Create", "spoofed-participant") }, http.StatusCreated,
		},
		{
			"expense update", http.MethodPut,
			func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/expenses/" + f.expense.ID },
			func(f authorizationFixture) string { return expenseMutationBody(t, f, "Update", "spoofed-participant") }, http.StatusOK,
		},
		{
			"expense delete", http.MethodDelete,
			func(f authorizationFixture) string { return "/v1/groups/" + f.group.ID + "/expenses/" + f.expense.ID },
			nil, http.StatusNoContent,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fixture := newAuthorizationFixture(t)
			body := ""
			if tc.body != nil {
				body = tc.body(fixture)
			}
			path := fixture.serverURL + tc.path(fixture)

			anonymous := authRequest(t, tc.method, path, "", body)
			anonymous.Body.Close()
			require.Equal(t, http.StatusUnauthorized, anonymous.StatusCode)

			nonMember := authRequest(t, tc.method, path, fixture.outsiderToken, body)
			nonMember.Body.Close()
			require.Equal(t, http.StatusForbidden, nonMember.StatusCode)

			member := authRequest(t, tc.method, path, fixture.memberToken, body)
			member.Body.Close()
			require.Equal(t, tc.memberCode, member.StatusCode)
		})
	}
}

func otherParticipantID(t *testing.T, group store.Group, userID string) string {
	t.Helper()
	memberPID := participantIDForUser(t, group, userID)
	for _, participant := range group.Participants {
		if participant.ID != memberPID {
			return participant.ID
		}
	}
	t.Fatal("fixture requires a second participant")
	return ""
}

func expenseMutationBody(t *testing.T, fixture authorizationFixture, title, addedBy string) string {
	t.Helper()
	memberPID := participantIDForUser(t, fixture.group, fixture.member.ID)
	otherPID := otherParticipantID(t, fixture.group, fixture.member.ID)
	return fmt.Sprintf(`{"title":%q,"amount":1500,"paidById":%q,"addedBy":%q,"splitMode":"EVENLY","paidFor":[{"participantId":%q,"shares":1},{"participantId":%q,"shares":1}]}`,
		title, memberPID, addedBy, memberPID, otherPID)
}

func TestActorCannotBeSpoofed(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	memberPID := participantIDForUser(t, fixture.group, fixture.member.ID)
	body := expenseMutationBody(t, fixture, "Server actor", "spoofed-participant")
	resp := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/expenses", fixture.memberToken, body)
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	var expense store.Expense
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&expense))
	resp.Body.Close()
	require.Equal(t, memberPID, expense.AddedBy)

	activityResp := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/activities", fixture.memberToken, "")
	require.Equal(t, http.StatusOK, activityResp.StatusCode)
	var activities []store.Activity
	require.NoError(t, json.NewDecoder(activityResp.Body).Decode(&activities))
	activityResp.Body.Close()
	for _, activity := range activities {
		if activity.ExpenseID != nil && *activity.ExpenseID == expense.ID {
			require.NotNil(t, activity.ParticipantID)
			require.Equal(t, memberPID, *activity.ParticipantID)
			return
		}
	}
	t.Fatal("created expense activity not found")
}

func TestAuthorizedGroupListsIntersectRequestedIDs(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	secondBody := fmt.Sprintf(`{"name":"Outsider group","currency":"₹","participants":[{"name":"Asha","userId":%q}]}`, fixture.outsider.ID)
	secondResp := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups", fixture.outsiderToken, secondBody)
	require.Equal(t, http.StatusCreated, secondResp.StatusCode)
	var second struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(secondResp.Body).Decode(&second))
	secondResp.Body.Close()

	listResp := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups?ids="+fixture.group.ID+","+second.GroupID, fixture.memberToken, "")
	require.Equal(t, http.StatusOK, listResp.StatusCode)
	var groups []store.Group
	require.NoError(t, json.NewDecoder(listResp.Body).Decode(&groups))
	listResp.Body.Close()
	require.Len(t, groups, 1)
	require.Equal(t, fixture.group.ID, groups[0].ID)

	dashboardResp := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/dashboard?participant=Asha&groupIds="+fixture.group.ID+","+second.GroupID, fixture.memberToken, "")
	require.Equal(t, http.StatusOK, dashboardResp.StatusCode)
	var dashboard struct {
		GroupCount int `json:"groupCount"`
	}
	require.NoError(t, json.NewDecoder(dashboardResp.Body).Decode(&dashboard))
	dashboardResp.Body.Close()
	require.Equal(t, 1, dashboard.GroupCount)
}

func TestAuthorizedMissingGroupIsNotFound(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	resp := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups/missing-"+id.New(), fixture.memberToken, "")
	defer resp.Body.Close()
	require.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestGroupUpdateCannotRemoveCallersParticipant(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	otherID := otherParticipantID(t, fixture.group, fixture.member.ID)
	body := fmt.Sprintf(`{"name":"Private trip","currency":"₹","participants":[{"id":%q,"name":"Mira"}]}`, otherID)
	resp := authRequest(t, http.MethodPut, fixture.serverURL+"/v1/groups/"+fixture.group.ID, fixture.memberToken, body)
	defer resp.Body.Close()
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestExpenseRejectsParticipantsFromAnotherGroup(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	body := fmt.Sprintf(`{"name":"Other trip","currency":"₹","participants":[{"name":"Rohan","userId":%q},{"name":"Kai"}]}`, fixture.outsider.ID)
	created := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups", fixture.outsiderToken, body)
	require.Equal(t, http.StatusCreated, created.StatusCode)
	var other struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(created.Body).Decode(&other))
	created.Body.Close()
	details := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups/"+other.GroupID, fixture.outsiderToken, "")
	require.Equal(t, http.StatusOK, details.StatusCode)
	var otherGroup store.Group
	require.NoError(t, json.NewDecoder(details.Body).Decode(&otherGroup))
	details.Body.Close()

	memberPID := participantIDForUser(t, fixture.group, fixture.member.ID)
	foreignPID := otherGroup.Participants[0].ID
	expense := fmt.Sprintf(`{"title":"Cross group","amount":1000,"paidById":%q,"splitMode":"EVENLY","paidFor":[{"participantId":%q,"shares":1}]}`, foreignPID, memberPID)
	resp := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/expenses", fixture.memberToken, expense)
	defer resp.Body.Close()
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
