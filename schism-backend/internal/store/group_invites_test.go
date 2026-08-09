package store

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

func groupInviteFixture(t *testing.T) (*Store, User, User, Group) {
	t.Helper()
	s := newTestStore(t)
	ctx := context.Background()
	creator, _, err := s.RegisterUser(ctx, "Asha", "asha-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	joiner, _, err := s.RegisterUser(ctx, "Mira", "mira-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	group, err := s.CreateGroupForUser(ctx, GroupInput{Name: "Trip", Currency: "₹", Participants: []ParticipantInput{{Name: "Asha"}}}, creator.ID)
	require.NoError(t, err)
	return s, creator, joiner, group
}

func participantCount(t *testing.T, s *Store, groupID string) int {
	t.Helper()
	var n int
	require.NoError(t, s.pool.QueryRow(context.Background(), `SELECT count(*) FROM participants WHERE group_id=$1`, groupID).Scan(&n))
	return n
}

func TestGroupInviteRequiresMembershipAndStoresOnlyHash(t *testing.T) {
	s, creator, joiner, group := groupInviteFixture(t)
	_, _, err := s.CreateGroupInvite(context.Background(), group.ID, joiner.ID)
	require.ErrorIs(t, err, ErrNotGroupMember)

	raw, expiresAt, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)
	require.Len(t, raw, 43)
	require.WithinDuration(t, time.Now().Add(7*24*time.Hour), expiresAt, 5*time.Second)
	var stored string
	require.NoError(t, s.pool.QueryRow(context.Background(), `SELECT token_hash FROM group_invites WHERE group_id=$1`, group.ID).Scan(&stored))
	require.NotEqual(t, raw, stored)
	require.Equal(t, TokenHash(raw), stored)

	preview, err := s.PreviewGroupInvite(context.Background(), raw)
	require.NoError(t, err)
	require.Equal(t, GroupInvitePreview{GroupName: "Trip", MemberCount: 1}, *preview)
}

func TestGroupInviteRotationRevokesPreviousLink(t *testing.T) {
	s, creator, _, group := groupInviteFixture(t)
	old, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)
	fresh, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)
	require.NotEqual(t, old, fresh)

	_, err = s.PreviewGroupInvite(context.Background(), old)
	require.ErrorIs(t, err, ErrInviteRevoked)
	_, err = s.PreviewGroupInvite(context.Background(), fresh)
	require.NoError(t, err)
}

func TestGroupInviteExpiryRevocationAndUnknownToken(t *testing.T) {
	s, creator, joiner, group := groupInviteFixture(t)
	_, err := s.PreviewGroupInvite(context.Background(), "not-a-token")
	require.ErrorIs(t, err, ErrInviteNotFound)
	_, err = s.RedeemGroupInvite(context.Background(), "not-a-token", joiner.ID)
	require.ErrorIs(t, err, ErrInviteNotFound)

	raw, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)
	_, err = s.pool.Exec(context.Background(), `UPDATE group_invites SET expires_at=now()-interval '1 second' WHERE token_hash=$1`, TokenHash(raw))
	require.NoError(t, err)
	_, err = s.PreviewGroupInvite(context.Background(), raw)
	require.ErrorIs(t, err, ErrInviteExpired)
	_, err = s.RedeemGroupInvite(context.Background(), raw, joiner.ID)
	require.ErrorIs(t, err, ErrInviteExpired)

	live, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)
	require.ErrorIs(t, s.RevokeGroupInvite(context.Background(), group.ID, joiner.ID), ErrNotGroupMember)
	require.NoError(t, s.RevokeGroupInvite(context.Background(), group.ID, creator.ID))
	require.NoError(t, s.RevokeGroupInvite(context.Background(), group.ID, creator.ID))
	_, err = s.RedeemGroupInvite(context.Background(), live, joiner.ID)
	require.ErrorIs(t, err, ErrInviteRevoked)
}

func TestGroupInviteRedeemCreatesOneParticipantAndIsIdempotent(t *testing.T) {
	s, creator, joiner, group := groupInviteFixture(t)
	raw, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)

	groupID, err := s.RedeemGroupInvite(context.Background(), raw, joiner.ID)
	require.NoError(t, err)
	require.Equal(t, group.ID, groupID)
	require.Equal(t, 2, participantCount(t, s, group.ID))

	linked, err := s.ParticipantForUserInGroup(context.Background(), joiner.ID, group.ID)
	require.NoError(t, err)
	require.NotEmpty(t, linked)
	var name string
	require.NoError(t, s.pool.QueryRow(context.Background(), `SELECT name FROM participants WHERE id=$1`, linked).Scan(&name))
	require.Equal(t, "Mira", name)

	// Second redemption, and a redemption by an existing member, both no-op back to the group.
	groupID, err = s.RedeemGroupInvite(context.Background(), raw, joiner.ID)
	require.NoError(t, err)
	require.Equal(t, group.ID, groupID)
	groupID, err = s.RedeemGroupInvite(context.Background(), raw, creator.ID)
	require.NoError(t, err)
	require.Equal(t, group.ID, groupID)
	require.Equal(t, 2, participantCount(t, s, group.ID))
}

func TestGroupInviteConcurrentRedemptionCreatesOneParticipant(t *testing.T) {
	s, creator, joiner, group := groupInviteFixture(t)
	raw, _, err := s.CreateGroupInvite(context.Background(), group.ID, creator.ID)
	require.NoError(t, err)

	errs := make([]error, 4)
	var wg sync.WaitGroup
	for i := range errs {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, errs[i] = s.RedeemGroupInvite(context.Background(), raw, joiner.ID)
		}(i)
	}
	wg.Wait()
	for _, redeemErr := range errs {
		require.NoError(t, redeemErr)
	}
	require.Equal(t, 2, participantCount(t, s, group.ID))
}
