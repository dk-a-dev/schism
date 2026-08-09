package api

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestParticipantInviteAPIFlow(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	participantID := otherParticipantID(t, fixture.group, fixture.member.ID)
	create := authRequest(t, http.MethodPost,
		fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/participants/"+participantID+"/invite",
		fixture.memberToken, "{}")
	require.Equal(t, http.StatusCreated, create.StatusCode)
	var invitation struct {
		Token string `json:"token"`
	}
	require.NoError(t, json.NewDecoder(create.Body).Decode(&invitation))
	create.Body.Close()
	require.Len(t, invitation.Token, 43)

	preview := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/invites/"+invitation.Token, fixture.outsiderToken, "")
	require.Equal(t, http.StatusOK, preview.StatusCode)
	var body struct {
		GroupID         string `json:"groupId"`
		GroupName       string `json:"groupName"`
		ParticipantID   string `json:"participantId"`
		ParticipantName string `json:"participantName"`
	}
	require.NoError(t, json.NewDecoder(preview.Body).Decode(&body))
	preview.Body.Close()
	require.Equal(t, fixture.group.ID, body.GroupID)
	require.Equal(t, participantID, body.ParticipantID)

	redeem := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/invites/"+invitation.Token+"/redeem", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusOK, redeem.StatusCode)
	redeem.Body.Close()
	group := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/groups/"+fixture.group.ID, fixture.outsiderToken, "")
	require.Equal(t, http.StatusOK, group.StatusCode)
	group.Body.Close()

	replay := authRequest(t, http.MethodPost, fixture.serverURL+"/v1/invites/"+invitation.Token+"/redeem", fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusConflict, replay.StatusCode)
	replay.Body.Close()
}

func TestParticipantInviteEndpointsRequireAuthenticationAndMembership(t *testing.T) {
	fixture := newAuthorizationFixture(t)
	participantID := otherParticipantID(t, fixture.group, fixture.member.ID)
	outsider := authRequest(t, http.MethodPost,
		fixture.serverURL+"/v1/groups/"+fixture.group.ID+"/participants/"+participantID+"/invite",
		fixture.outsiderToken, "{}")
	require.Equal(t, http.StatusForbidden, outsider.StatusCode)
	outsider.Body.Close()
	anonymous := authRequest(t, http.MethodGet, fixture.serverURL+"/v1/invites/not-a-token", "", "")
	require.Equal(t, http.StatusUnauthorized, anonymous.StatusCode)
	anonymous.Body.Close()
}

func TestParticipantInviteLandingAndLegacyUpgradePage(t *testing.T) {
	srv := newTestServer(t)
	landing, err := http.Get(srv.URL + "/i/token-value")
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, landing.StatusCode)
	content, err := io.ReadAll(landing.Body)
	require.NoError(t, err)
	landing.Body.Close()
	require.Contains(t, string(content), "schism://invite/token-value")

	legacy, err := http.Get(srv.URL + "/g/private-group-id")
	require.NoError(t, err)
	legacyBody, err := io.ReadAll(legacy.Body)
	require.NoError(t, err)
	legacy.Body.Close()
	require.False(t, strings.Contains(string(legacyBody), "private-group-id"))
	require.False(t, strings.Contains(string(legacyBody), "schism://group/"))
}
