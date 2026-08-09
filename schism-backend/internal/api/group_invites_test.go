package api

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func createGroupInvite(t *testing.T, fixture authorizationFixture) string {
	t.Helper()
	created := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/invite-link", fixture.memberToken, "{}")
	require.Equal(t, http.StatusCreated, created.StatusCode)
	var invitation struct {
		Token string `json:"token"`
	}
	require.NoError(t, json.NewDecoder(created.Body).Decode(&invitation))
	created.Body.Close()
	require.Len(t, invitation.Token, 43)
	return invitation.Token
}

func TestGroupInviteAPIFlow(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	token := createGroupInvite(t, fixture)

	preview := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/group-invites/"+token, fixture.outsiderToken, "")
	require.Equal(t, http.StatusOK, preview.StatusCode)
	previewBody, err := io.ReadAll(preview.Body)
	require.NoError(t, err)
	preview.Body.Close()
	require.Contains(t, string(previewBody), `"groupName":"Private trip"`)
	require.Contains(t, string(previewBody), `"memberCount":2`)
	require.NotContains(t, string(previewBody), fixture.group.ID)

	redeem := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/group-invites/"+token+"/redeem", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusOK, redeem.StatusCode)
	var redeemed struct {
		GroupID string `json:"groupId"`
	}
	require.NoError(t, json.NewDecoder(redeem.Body).Decode(&redeemed))
	redeem.Body.Close()
	require.Equal(t, fixture.group.ID, redeemed.GroupID)

	// The redeemer is now a member, and a replay is a no-op rather than a second participant.
	group := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups/"+fixture.group.ID, fixture.outsiderToken, "")
	require.Equal(t, http.StatusOK, group.StatusCode)
	groupBody, err := io.ReadAll(group.Body)
	require.NoError(t, err)
	group.Body.Close()
	require.Equal(t, 1, strings.Count(string(groupBody), `"Rohan"`))

	replay := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/group-invites/"+token+"/redeem", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusOK, replay.StatusCode)
	replay.Body.Close()
}

func TestGroupInviteRevocationAndAuthorization(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	outsider := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/invite-link", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusForbidden, outsider.StatusCode)
	outsider.Body.Close()
	anonymous := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/group-invites/not-a-token", "", "")
	require.Equal(t, http.StatusUnauthorized, anonymous.StatusCode)
	anonymous.Body.Close()
	unknown := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/group-invites/not-a-token", fixture.outsiderToken, "")
	require.Equal(t, http.StatusNotFound, unknown.StatusCode)
	unknown.Body.Close()

	token := createGroupInvite(t, fixture)
	revoke := authRequest(t, http.MethodDelete, fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/invite-link", fixture.memberToken, "")
	require.Equal(t, http.StatusNoContent, revoke.StatusCode)
	revoke.Body.Close()

	dead := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/group-invites/"+token+"/redeem", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusGone, dead.StatusCode)
	dead.Body.Close()
}

func TestGroupInviteLandingExposesOnlyTheDeepLink(t *testing.T) {
	srv := newTestServer(t)
	landing, err := http.Get(srv.URL + "/i/g/token-value")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, landing.StatusCode)
	content, err := io.ReadAll(landing.Body)
	require.NoError(t, err)
	landing.Body.Close()
	require.Contains(t, string(content), "schism://group-invite/token-value")
}
