package split

import (
	"testing"

	"github.com/stretchr/testify/require"
)

// Fixtures below are hand-computed from the split algorithm (last participant absorbs the
// rounding remainder). They independently pin the expected output so a regression in the
// implementation is caught.

func TestGetBalances(t *testing.T) {
	cases := []struct {
		name     string
		expenses []Expense
		want     map[string]Balance
	}{
		{
			name: "evenly_two",
			expenses: []Expense{{Amount: 1000, PaidByID: "a", SplitMode: Evenly,
				PaidFor: []PaidFor{{"a", 100}, {"b", 100}}}},
			want: map[string]Balance{
				"a": {Paid: 1000, PaidFor: 500, Total: 500},
				"b": {Paid: 0, PaidFor: 500, Total: -500},
			},
		},
		{
			name: "evenly_three_odd",
			expenses: []Expense{{Amount: 1000, PaidByID: "a", SplitMode: Evenly,
				PaidFor: []PaidFor{{"a", 100}, {"b", 100}, {"c", 100}}}},
			want: map[string]Balance{
				"a": {Paid: 1000, PaidFor: 333, Total: 667},
				"b": {Paid: 0, PaidFor: 333, Total: -333},
				"c": {Paid: 0, PaidFor: 333, Total: -333},
			},
		},
		{
			name: "by_shares",
			expenses: []Expense{{Amount: 900, PaidByID: "a", SplitMode: ByShares,
				PaidFor: []PaidFor{{"a", 100}, {"b", 200}}}},
			want: map[string]Balance{
				"a": {Paid: 900, PaidFor: 300, Total: 600},
				"b": {Paid: 0, PaidFor: 600, Total: -600},
			},
		},
		{
			name: "by_percentage",
			expenses: []Expense{{Amount: 1000, PaidByID: "a", SplitMode: ByPercentage,
				PaidFor: []PaidFor{{"a", 3000}, {"b", 7000}}}},
			want: map[string]Balance{
				"a": {Paid: 1000, PaidFor: 300, Total: 700},
				"b": {Paid: 0, PaidFor: 700, Total: -700},
			},
		},
		{
			name: "by_amount",
			expenses: []Expense{{Amount: 1000, PaidByID: "a", SplitMode: ByAmount,
				PaidFor: []PaidFor{{"a", 300}, {"b", 700}}}},
			want: map[string]Balance{
				"a": {Paid: 1000, PaidFor: 300, Total: 700},
				"b": {Paid: 0, PaidFor: 700, Total: -700},
			},
		},
		{
			name: "multi_expense",
			expenses: []Expense{
				{Amount: 3000, PaidByID: "a", SplitMode: Evenly,
					PaidFor: []PaidFor{{"a", 100}, {"b", 100}, {"c", 100}}},
				{Amount: 1500, PaidByID: "b", SplitMode: Evenly,
					PaidFor: []PaidFor{{"b", 100}, {"c", 100}}},
			},
			want: map[string]Balance{
				"a": {Paid: 3000, PaidFor: 1000, Total: 2000},
				"b": {Paid: 1500, PaidFor: 1750, Total: -250},
				"c": {Paid: 0, PaidFor: 1750, Total: -1750},
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := GetBalances(tc.expenses)
			require.Len(t, got, len(tc.want))
			for id, want := range tc.want {
				require.NotNil(t, got[id], "missing %s", id)
				require.Equal(t, want, *got[id], "balance[%s]", id)
			}
		})
	}
}

func TestReimbursements(t *testing.T) {
	balances := Balances{
		"a": {Paid: 3000, PaidFor: 1000, Total: 2000},
		"b": {Paid: 1500, PaidFor: 1750, Total: -250},
		"c": {Paid: 0, PaidFor: 1750, Total: -1750},
	}
	got := GetSuggestedReimbursements(balances)
	require.Equal(t, []Reimbursement{
		{From: "c", To: "a", Amount: 1750},
		{From: "b", To: "a", Amount: 250},
	}, got)

	pub := GetPublicBalances(got)
	require.Equal(t, Balance{Paid: 2000, PaidFor: 0, Total: 2000}, *pub["a"])
	require.Equal(t, Balance{Paid: 0, PaidFor: 250, Total: -250}, *pub["b"])
	require.Equal(t, Balance{Paid: 0, PaidFor: 1750, Total: -1750}, *pub["c"])
}

func TestReimbursementsSimple(t *testing.T) {
	balances := Balances{
		"a": {Paid: 1000, PaidFor: 500, Total: 500},
		"b": {Paid: 0, PaidFor: 500, Total: -500},
	}
	got := GetSuggestedReimbursements(balances)
	require.Equal(t, []Reimbursement{{From: "b", To: "a", Amount: 500}}, got)
}
