package store

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestCreateAndListExpense(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, err := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}, {Name: "B"}}})
	require.NoError(t, err)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	e, err := s.CreateExpense(ctx, g.ID, ExpenseInput{
		Title: "Dinner", Amount: 1000, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY",
		PaidFor: []PaidForInput{{a, 100}, {b, 100}},
	}, "")
	require.NoError(t, err)
	require.NotEmpty(t, e.ID)
	require.Len(t, e.PaidFor, 2)

	list, err := s.ListExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, list, 1)

	se, err := s.SplitExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, se, 1)
	require.Equal(t, int64(1000), se[0].Amount)
	require.Equal(t, a, se[0].PaidByID)
}

func TestCreateExpenseIdempotent(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, _ := s.CreateGroup(ctx, GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}, {Name: "B"}}})
	a, b := g.Participants[0].ID, g.Participants[1].ID
	in := ExpenseInput{Title: "X", Amount: 500, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}}

	e1, err := s.CreateExpense(ctx, g.ID, in, "key-1")
	require.NoError(t, err)
	e2, err := s.CreateExpense(ctx, g.ID, in, "key-1")
	require.NoError(t, err)
	require.Equal(t, e1.ID, e2.ID)

	list, _ := s.ListExpenses(ctx, g.ID)
	require.Len(t, list, 1)
}
