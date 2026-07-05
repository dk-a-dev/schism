package store

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestListCategories(t *testing.T) {
	s := newTestStore(t)
	cats, err := s.ListCategories(context.Background())
	require.NoError(t, err)
	require.NotEmpty(t, cats)
	require.Equal(t, 0, cats[0].ID)
	require.Equal(t, "General", cats[0].Name)
}

func TestLogAndListActivities(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, _ := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}}})
	require.NoError(t, s.LogActivity(ctx, g.ID, "CREATE_EXPENSE", nil, nil, ""))
	acts, err := s.ListActivities(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, acts, 1)
	require.Equal(t, "CREATE_EXPENSE", acts[0].ActivityType)
}
