package store

import (
	"context"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

func newTestStore(t *testing.T) *Store {
	url := testURL(t)
	require.NoError(t, RunMigrations(url))
	pool, err := NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	return NewStore(pool)
}

func TestCreateAndGetGroup(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{
		Name: "Trip", Currency: "$", CurrencyCode: "USD",
		Participants: []ParticipantInput{{Name: "Alice"}, {Name: "Bob"}},
	})
	require.NoError(t, err)
	require.NotEmpty(t, g.ID)
	require.Len(t, g.Participants, 2)

	got, err := s.GetGroup(ctx, g.ID)
	require.NoError(t, err)
	require.Equal(t, "Trip", got.Name)
	require.Len(t, got.Participants, 2)

	missing, err := s.GetGroup(ctx, "does-not-ex")
	require.NoError(t, err)
	require.Nil(t, missing)
}

func TestUpdateGroupReconcilesParticipants(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{Name: "Trip", Currency: "$",
		Participants: []ParticipantInput{{Name: "Alice"}, {Name: "Bob"}}})
	require.NoError(t, err)
	keepID := g.Participants[0].ID

	updated, err := s.UpdateGroup(ctx, g.ID, GroupInput{Name: "Trip2", Currency: "$",
		Participants: []ParticipantInput{{ID: &keepID, Name: "Alice R"}, {Name: "Carol"}}})
	require.NoError(t, err)
	require.Equal(t, "Trip2", updated.Name)
	require.Len(t, updated.Participants, 2)
	names := []string{updated.Participants[0].Name, updated.Participants[1].Name}
	require.Contains(t, names, "Alice R")
	require.Contains(t, names, "Carol")
	require.NotContains(t, names, "Bob")
}

func TestCreateGroupForUserLinksOnlySelectedCreatorParticipant(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	creatorID := "creator-" + id.New()
	spoofedID := "spoofed-" + id.New()
	g, err := s.CreateGroupForUser(ctx, GroupInput{
		Name: "Private", Currency: "₹", Participants: []ParticipantInput{
			{Name: "Mira", UserID: &spoofedID},
			{Name: "Asha", UserID: &creatorID},
			{Name: "Rohan", UserID: &creatorID},
		},
	}, creatorID)
	require.NoError(t, err)

	linked := 0
	for _, participant := range g.Participants {
		if participant.UserID != nil {
			linked++
			require.Equal(t, creatorID, *participant.UserID)
			require.Equal(t, "Asha", participant.Name)
		}
	}
	require.Equal(t, 1, linked)
}

func TestCreateGroupForUserFallsBackToFirstParticipant(t *testing.T) {
	s := newTestStore(t)
	creatorID := "creator-" + id.New()
	g, err := s.CreateGroupForUser(context.Background(), GroupInput{
		Name: "Private", Currency: "₹", Participants: []ParticipantInput{{Name: "Asha"}, {Name: "Mira"}},
	}, creatorID)
	require.NoError(t, err)
	for _, participant := range g.Participants {
		if participant.Name == "Asha" {
			require.NotNil(t, participant.UserID)
			require.Equal(t, creatorID, *participant.UserID)
			return
		}
	}
	t.Fatal("first participant not found")
}

func TestAuthorizedGroupIDsIntersectsRequestedMembership(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	userID := "user-" + id.New()
	memberGroup, err := s.CreateGroupForUser(ctx, GroupInput{
		Name: "Member", Currency: "₹", Participants: []ParticipantInput{{Name: "Asha"}},
	}, userID)
	require.NoError(t, err)
	otherGroup, err := s.CreateGroup(ctx, GroupInput{
		Name: "Other", Currency: "₹", Participants: []ParticipantInput{{Name: "Rohan"}},
	})
	require.NoError(t, err)

	ids, err := s.AuthorizedGroupIDs(ctx, userID, []string{memberGroup.ID, otherGroup.ID, "missing"})
	require.NoError(t, err)
	require.Equal(t, []string{memberGroup.ID}, ids)

	all, err := s.AuthorizedGroupIDs(ctx, userID, nil)
	require.NoError(t, err)
	require.Equal(t, []string{memberGroup.ID}, all)
}

func TestUpdateGroupPreservesExistingParticipantUserLinks(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	creatorID := "creator-" + id.New()
	otherUserID := "other-" + id.New()
	g, err := s.CreateGroupForUser(ctx, GroupInput{
		Name: "Private", Currency: "₹", Participants: []ParticipantInput{{Name: "Asha"}, {Name: "Rohan"}},
	}, creatorID)
	require.NoError(t, err)
	var creatorParticipant, otherParticipant Participant
	for _, participant := range g.Participants {
		if participant.UserID != nil {
			creatorParticipant = participant
		} else {
			otherParticipant = participant
		}
	}
	_, err = s.pool.Exec(ctx, `UPDATE participants SET user_id=$1 WHERE id=$2`, otherUserID, otherParticipant.ID)
	require.NoError(t, err)

	spoofed := "attacker-" + id.New()
	updated, err := s.UpdateGroup(ctx, g.ID, GroupInput{
		Name: "Renamed", Currency: "₹", Participants: []ParticipantInput{
			{ID: &creatorParticipant.ID, Name: "Asha", UserID: &spoofed},
			{ID: &otherParticipant.ID, Name: "Rohan"},
		},
	})
	require.NoError(t, err)
	links := map[string]string{}
	for _, participant := range updated.Participants {
		if participant.UserID != nil {
			links[participant.ID] = *participant.UserID
		}
	}
	require.Equal(t, creatorID, links[creatorParticipant.ID])
	require.Equal(t, otherUserID, links[otherParticipant.ID])
}

// Removing a participant used to cascade-delete every expense they paid for, because
// expenses.paid_by_id is ON DELETE CASCADE. Any group member could aim that at any other member —
// the only guard was "you cannot remove yourself" — and a client PUTting a stale snapshot could
// trigger it with no attacker at all. The ledger is the entire product; it must not be destroyable
// by a member-list edit.
func TestUpdateGroupRefusesToRemoveAParticipantWithExpenseHistory(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{Name: "Flat", Currency: "$",
		Participants: []ParticipantInput{{Name: "Alice"}, {Name: "Bob"}}})
	require.NoError(t, err)
	alice, bob := g.Participants[0].ID, g.Participants[1].ID

	_, err = s.CreateExpense(ctx, g.ID, ExpenseInput{
		Title: "Rent", Amount: 40000, ExpenseDate: time.Now(), PaidByID: bob,
		SplitMode: "EVENLY", AddedBy: alice,
		PaidFor: []PaidForInput{{ParticipantID: alice, Shares: 1}, {ParticipantID: bob, Shares: 1}},
	}, id.New())
	require.NoError(t, err)

	// Alice tries to drop Bob by simply omitting him.
	_, err = s.UpdateGroup(ctx, g.ID, GroupInput{Name: "Flat", Currency: "$",
		Participants: []ParticipantInput{{ID: &alice, Name: "Alice"}}})
	require.ErrorIs(t, err, ErrParticipantHasHistory)

	// Nothing was destroyed and nothing was half-applied: Bob is still a member, and the
	// expense he paid for still exists with its original amount.
	after, err := s.GetGroup(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, after.Participants, 2, "Bob must still be a member")
	expenses, err := s.ListExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, expenses, 1, "the expense must survive")
	require.EqualValues(t, 40000, expenses[0].Amount)
}

// A participant who never appeared on an expense is still removable — that is the "added them by
// mistake" case, and it destroys nothing.
func TestUpdateGroupStillRemovesParticipantWithoutHistory(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{Name: "Trip", Currency: "$",
		Participants: []ParticipantInput{{Name: "Alice"}, {Name: "Typo"}}})
	require.NoError(t, err)
	alice := g.Participants[0].ID

	updated, err := s.UpdateGroup(ctx, g.ID, GroupInput{Name: "Trip", Currency: "$",
		Participants: []ParticipantInput{{ID: &alice, Name: "Alice"}}})
	require.NoError(t, err)
	require.Len(t, updated.Participants, 1)
}
