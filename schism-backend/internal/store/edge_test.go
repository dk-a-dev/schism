package store

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func seedGroup(t *testing.T, s *Store) (Group, string, string) {
	g, err := s.CreateGroup(context.Background(), GroupInput{Name: "T", Currency: "$",
		Participants: []ParticipantInput{{Name: "A"}, {Name: "B"}}})
	require.NoError(t, err)
	return g, g.Participants[0].ID, g.Participants[1].ID
}

func TestGetExpenseWrongGroupReturnsNil(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, a, b := seedGroup(t, s)
	e, err := s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "X", Amount: 100, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}}, "")
	require.NoError(t, err)

	got, err := s.GetExpense(ctx, "another-group", e.ID)
	require.NoError(t, err)
	require.Nil(t, got)
}

func TestUpdateExpenseNotFound(t *testing.T) {
	s := newTestStore(t)
	g, a, b := seedGroup(t, s)
	got, err := s.UpdateExpense(context.Background(), g.ID, "missing", ExpenseInput{
		Title: "X", Amount: 100, ExpenseDate: time.Now(), PaidByID: a, SplitMode: "EVENLY",
		PaidFor: []PaidForInput{{a, 100}, {b, 100}}})
	require.NoError(t, err)
	require.Nil(t, got)
}

func TestUpdateExpenseReplacesPaidFor(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, a, b := seedGroup(t, s)
	e, _ := s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "X", Amount: 900, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "BY_SHARES", PaidFor: []PaidForInput{{a, 100}, {b, 200}}}, "")

	upd, err := s.UpdateExpense(ctx, g.ID, e.ID, ExpenseInput{Title: "X2", Amount: 1000, ExpenseDate: time.Now(),
		PaidByID: b, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}})
	require.NoError(t, err)
	require.Equal(t, "X2", upd.Title)
	require.Equal(t, int64(1000), upd.Amount)
	require.Equal(t, b, upd.PaidByID)
	require.Len(t, upd.PaidFor, 2)
	for _, pf := range upd.PaidFor {
		require.Equal(t, int64(100), pf.Shares)
	}
}

func TestDeleteExpenseCascadesPaidFor(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, a, b := seedGroup(t, s)
	e, _ := s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "X", Amount: 100, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}}, "")

	ok, err := s.DeleteExpense(ctx, g.ID, e.ID)
	require.NoError(t, err)
	require.True(t, ok)

	var n int
	require.NoError(t, s.pool.QueryRow(ctx,
		`SELECT count(*) FROM expense_paid_for WHERE expense_id=$1`, e.ID).Scan(&n))
	require.Equal(t, 0, n)

	ok, err = s.DeleteExpense(ctx, g.ID, e.ID)
	require.NoError(t, err)
	require.False(t, ok) // already gone
}

func TestDeleteGroupCascadesEverything(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, a, b := seedGroup(t, s)
	_, _ = s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "X", Amount: 100, ExpenseDate: time.Now(),
		PaidByID: a, SplitMode: "EVENLY", PaidFor: []PaidForInput{{a, 100}, {b, 100}}}, "")

	_, err := s.pool.Exec(ctx, `DELETE FROM groups WHERE id=$1`, g.ID)
	require.NoError(t, err)

	for _, tbl := range []string{"participants", "expenses"} {
		var n int
		require.NoError(t, s.pool.QueryRow(ctx,
			`SELECT count(*) FROM `+tbl+` WHERE group_id=$1`, g.ID).Scan(&n))
		require.Equal(t, 0, n, "table %s should be empty after group delete", tbl)
	}
}

func TestListExpensesOrdersByDateDesc(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, a, b := seedGroup(t, s)
	older, _ := time.Parse("2006-01-02", "2026-01-01")
	newer, _ := time.Parse("2006-01-02", "2026-07-01")
	pf := []PaidForInput{{a, 100}, {b, 100}}
	_, _ = s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "old", Amount: 100, ExpenseDate: older, PaidByID: a, SplitMode: "EVENLY", PaidFor: pf}, "")
	_, _ = s.CreateExpense(ctx, g.ID, ExpenseInput{Title: "new", Amount: 100, ExpenseDate: newer, PaidByID: a, SplitMode: "EVENLY", PaidFor: pf}, "")

	list, err := s.ListExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, list, 2)
	require.Equal(t, "new", list[0].Title)
	require.Equal(t, "old", list[1].Title)
}

func TestListGroupsSkipsMissing(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	g, _, _ := seedGroup(t, s)
	groups, err := s.ListGroups(ctx, []string{g.ID, "nope-nope-nop"})
	require.NoError(t, err)
	require.Len(t, groups, 1)
	require.Equal(t, g.ID, groups[0].ID)
}
