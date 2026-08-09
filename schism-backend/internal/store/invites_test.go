package store

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

func inviteFixture(t *testing.T) (*Store, User, User, Group, string) {
	t.Helper()
	s := newTestStore(t)
	ctx := context.Background()
	creator, _, err := s.RegisterUser(ctx, "Asha", "asha-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	redeemer, _, err := s.RegisterUser(ctx, "Mira", "mira-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	group, err := s.CreateGroupForUser(ctx, GroupInput{Name: "Trip", Currency: "₹", Participants: []ParticipantInput{{Name: "Asha"}, {Name: "Mira"}}}, creator.ID)
	require.NoError(t, err)
	var participantID string
	for _, participant := range group.Participants {
		if participant.UserID == nil {
			participantID = participant.ID
		}
	}
	require.NotEmpty(t, participantID)
	return s, creator, redeemer, group, participantID
}

func TestParticipantInviteLifecycleStoresOnlyHash(t *testing.T) {
	s, creator, redeemer, group, participantID := inviteFixture(t)
	raw, expiresAt, err := s.CreateParticipantInvite(context.Background(), group.ID, participantID, creator.ID)
	require.NoError(t, err)
	require.Len(t, raw, 43)
	require.WithinDuration(t, time.Now().Add(7*24*time.Hour), expiresAt, 5*time.Second)
	var stored string
	require.NoError(t, s.pool.QueryRow(context.Background(), `SELECT token_hash FROM participant_invites WHERE participant_id=$1`, participantID).Scan(&stored))
	require.NotEqual(t, raw, stored)
	require.Equal(t, TokenHash(raw), stored)

	preview, err := s.PreviewParticipantInvite(context.Background(), raw)
	require.NoError(t, err)
	require.Equal(t, group.ID, preview.GroupID)
	require.Equal(t, "Trip", preview.GroupName)
	require.Equal(t, participantID, preview.ParticipantID)
	require.Equal(t, "Mira", preview.ParticipantName)

	groupID, err := s.RedeemParticipantInvite(context.Background(), raw, redeemer.ID)
	require.NoError(t, err)
	require.Equal(t, group.ID, groupID)
	_, err = s.RedeemParticipantInvite(context.Background(), raw, redeemer.ID)
	require.ErrorIs(t, err, ErrInviteUsed)
}

func TestParticipantInviteGuardsAndExpiry(t *testing.T) {
	s, creator, _, group, participantID := inviteFixture(t)
	outsider, _, err := s.RegisterUser(context.Background(), "Kai", "kai-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	_, _, err = s.CreateParticipantInvite(context.Background(), group.ID, participantID, outsider.ID)
	require.ErrorIs(t, err, ErrNotGroupMember)

	raw, _, err := s.CreateParticipantInvite(context.Background(), group.ID, participantID, creator.ID)
	require.NoError(t, err)
	_, err = s.pool.Exec(context.Background(), `UPDATE participant_invites SET expires_at=now()-interval '1 second' WHERE token_hash=$1`, TokenHash(raw))
	require.NoError(t, err)
	_, err = s.PreviewParticipantInvite(context.Background(), raw)
	require.ErrorIs(t, err, ErrInviteExpired)
}

func TestParticipantInviteConcurrentRedemptionHasOneWinner(t *testing.T) {
	s, creator, redeemer, group, participantID := inviteFixture(t)
	second, _, err := s.RegisterUser(context.Background(), "Ru", "ru-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	raw, _, err := s.CreateParticipantInvite(context.Background(), group.ID, participantID, creator.ID)
	require.NoError(t, err)

	users := []string{redeemer.ID, second.ID}
	errs := make([]error, 2)
	var wg sync.WaitGroup
	for i := range users {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, errs[i] = s.RedeemParticipantInvite(context.Background(), raw, users[i])
		}(i)
	}
	wg.Wait()
	successes := 0
	for _, redeemErr := range errs {
		if redeemErr == nil {
			successes++
		} else {
			require.ErrorIs(t, redeemErr, ErrInviteUsed)
		}
	}
	require.Equal(t, 1, successes)
}
