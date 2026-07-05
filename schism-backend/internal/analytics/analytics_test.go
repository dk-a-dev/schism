package analytics

import (
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

func mkGroup(id, name, cur, code string, parts ...store.Participant) store.Group {
	return store.Group{ID: id, Name: name, Currency: cur, CurrencyCode: code, Participants: parts}
}

func exp(id, title string, amount int64, cat int, date string, paidBy string, mode string, reimb bool, pf ...store.PaidForRow) store.Expense {
	d, _ := time.Parse("2006-01-02", date)
	return store.Expense{ID: id, Title: title, Amount: amount, CategoryID: cat, ExpenseDate: d,
		PaidByID: paidBy, SplitMode: mode, IsReimbursement: reimb, PaidFor: pf}
}

var cats = []store.Category{
	{ID: 0, Grouping: "Uncategorized", Name: "General"},
	{ID: 7, Grouping: "Food and drink", Name: "Groceries"},
	{ID: 2, Grouping: "Entertainment", Name: "Movies"},
}

func TestBuildGroupDashboardEmpty(t *testing.T) {
	g := mkGroup("g1", "Trip", "$", "USD",
		store.Participant{ID: "a", Name: "A"}, store.Participant{ID: "b", Name: "B"})
	d := BuildGroupDashboard(g, nil, cats, "")
	require.Equal(t, int64(0), d.TotalSpending)
	require.Equal(t, 0, d.ExpenseCount)
	require.Equal(t, int64(0), d.AverageExpense)
	require.Nil(t, d.FirstExpenseDate)
	// slices must serialize as [] not null
	require.NotNil(t, d.ByCategory)
	require.NotNil(t, d.ByMonth)
	require.NotNil(t, d.TopExpenses)
	require.Len(t, d.ByParticipant, 2)
}

func TestBuildGroupDashboardAggregates(t *testing.T) {
	g := mkGroup("g1", "Trip", "$", "USD",
		store.Participant{ID: "a", Name: "A"}, store.Participant{ID: "b", Name: "B"})
	expenses := []store.Expense{
		exp("e1", "Groceries", 1000, 7, "2026-06-10", "a", "EVENLY", false,
			store.PaidForRow{ParticipantID: "a", Shares: 100}, store.PaidForRow{ParticipantID: "b", Shares: 100}),
		exp("e2", "Movie", 600, 2, "2026-07-02", "b", "EVENLY", false,
			store.PaidForRow{ParticipantID: "a", Shares: 100}, store.PaidForRow{ParticipantID: "b", Shares: 100}),
		exp("e3", "More food", 400, 7, "2026-07-05", "a", "EVENLY", false,
			store.PaidForRow{ParticipantID: "a", Shares: 100}, store.PaidForRow{ParticipantID: "b", Shares: 100}),
		exp("r1", "Settle", 300, 0, "2026-07-06", "b", "EVENLY", true,
			store.PaidForRow{ParticipantID: "a", Shares: 100}),
	}
	d := BuildGroupDashboard(g, expenses, cats, "")

	require.Equal(t, int64(2000), d.TotalSpending) // reimbursement excluded
	require.Equal(t, 3, d.ExpenseCount)
	require.Equal(t, 1, d.ReimbursementCount)
	require.Equal(t, int64(666), d.AverageExpense) // 2000/3

	// byCategory sorted desc: Groceries 1400 (2), Movies 600 (1)
	require.Len(t, d.ByCategory, 2)
	require.Equal(t, 7, d.ByCategory[0].CategoryID)
	require.Equal(t, int64(1400), d.ByCategory[0].Amount)
	require.Equal(t, 2, d.ByCategory[0].Count)
	require.Equal(t, "Groceries", d.ByCategory[0].Name)

	// byMonth ascending: 2026-06 (1000), 2026-07 (1000)
	require.Len(t, d.ByMonth, 2)
	require.Equal(t, "2026-06", d.ByMonth[0].Month)
	require.Equal(t, int64(1000), d.ByMonth[0].Amount)
	require.Equal(t, "2026-07", d.ByMonth[1].Month)

	// date bounds over spend expenses
	require.Equal(t, "2026-06-10", d.FirstExpenseDate.Format("2006-01-02"))
	require.Equal(t, "2026-07-05", d.LastExpenseDate.Format("2006-01-02"))

	// topExpenses desc
	require.Equal(t, "e1", d.TopExpenses[0].ID)
	require.Equal(t, int64(1000), d.TopExpenses[0].Amount)

	// byParticipant: A paid 1400, B paid 600
	require.Equal(t, "a", d.ByParticipant[0].ParticipantID)
	require.Equal(t, int64(1400), d.ByParticipant[0].Paid)
}

func TestBuildGroupDashboardPersonal(t *testing.T) {
	g := mkGroup("g1", "Trip", "$", "USD",
		store.Participant{ID: "a", Name: "A"}, store.Participant{ID: "b", Name: "B"})
	expenses := []store.Expense{
		exp("e1", "Groceries", 1000, 7, "2026-07-01", "a", "EVENLY", false,
			store.PaidForRow{ParticipantID: "a", Shares: 100}, store.PaidForRow{ParticipantID: "b", Shares: 100}),
		exp("e2", "Movie", 600, 2, "2026-07-02", "b", "EVENLY", false,
			store.PaidForRow{ParticipantID: "b", Shares: 100}), // A not involved
	}
	d := BuildGroupDashboard(g, expenses, cats, "a")
	require.NotNil(t, d.Personal)
	require.Equal(t, "A", d.Personal.Name)
	require.Equal(t, 1, d.Personal.ExpenseCount) // only e1 involves A
	require.Len(t, d.Personal.ByCategory, 1)
	require.Equal(t, 7, d.Personal.ByCategory[0].CategoryID)
	require.Equal(t, int64(500), d.Personal.ByCategory[0].Amount) // A's share of 1000 evenly
}

func TestBuildGroupDashboardPersonalUnknownParticipant(t *testing.T) {
	g := mkGroup("g1", "Trip", "$", "USD", store.Participant{ID: "a", Name: "A"})
	d := BuildGroupDashboard(g, nil, cats, "zzz")
	require.Nil(t, d.Personal)
}

func TestPersonalDashboardCrossGroupCurrencyBucketing(t *testing.T) {
	g1 := mkGroup("g1", "Goa", "₹", "INR", store.Participant{ID: "a1", Name: "Dev"}, store.Participant{ID: "b1", Name: "Sam"})
	g2 := mkGroup("g2", "US Trip", "$", "USD", store.Participant{ID: "a2", Name: "Dev"}, store.Participant{ID: "b2", Name: "Max"})
	g3 := mkGroup("g3", "Other", "$", "USD", store.Participant{ID: "x", Name: "Nobody"})

	ge := []GroupExpenses{
		{Group: g1, Expenses: []store.Expense{
			exp("e1", "Dinner", 1000, 0, "2026-07-01", "a1", "EVENLY", false,
				store.PaidForRow{ParticipantID: "a1", Shares: 100}, store.PaidForRow{ParticipantID: "b1", Shares: 100})}},
		{Group: g2, Expenses: []store.Expense{
			exp("e2", "Lunch", 500, 0, "2026-07-02", "b2", "EVENLY", false,
				store.PaidForRow{ParticipantID: "a2", Shares: 100}, store.PaidForRow{ParticipantID: "b2", Shares: 100})}},
		{Group: g3, Expenses: nil}, // Dev not a participant -> skipped
	}
	d := BuildPersonalDashboard("Dev", ge)

	require.Equal(t, 2, d.GroupCount) // g3 skipped
	require.Len(t, d.Groups, 2)
	require.Len(t, d.Totals, 2)      // INR + USD buckets, never summed together

	byCode := map[string]CurrencyTotal{}
	for _, t := range d.Totals {
		byCode[t.CurrencyCode] = t
	}
	require.Equal(t, int64(1000), byCode["INR"].Paid) // Dev paid dinner in g1
	require.Equal(t, int64(500), byCode["INR"].Share) // owes half
	require.Equal(t, int64(500), byCode["INR"].Net)
	require.Equal(t, int64(0), byCode["USD"].Paid) // Max paid in g2
	require.Equal(t, int64(250), byCode["USD"].Share)
	require.Equal(t, int64(-250), byCode["USD"].Net)
}

func TestPersonalDashboardMatchesByID(t *testing.T) {
	g := mkGroup("g1", "Goa", "$", "USD", store.Participant{ID: "pid-1", Name: "Dev"})
	d := BuildPersonalDashboard("pid-1", []GroupExpenses{{Group: g, Expenses: nil}})
	require.Equal(t, 1, d.GroupCount)
	require.Equal(t, "Dev", d.Groups[0].ParticipantName)
}
