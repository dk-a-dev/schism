package store

import (
	"context"
	"testing"

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
