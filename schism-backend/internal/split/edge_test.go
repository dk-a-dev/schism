package split

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestGetBalancesEmpty(t *testing.T) {
	require.Empty(t, GetBalances(nil))
}

func TestGetBalancesSingleParticipant(t *testing.T) {
	b := GetBalances([]Expense{{Amount: 1000, PaidByID: "a", SplitMode: Evenly,
		PaidFor: []PaidFor{{"a", 100}}}})
	require.Equal(t, Balance{Paid: 1000, PaidFor: 1000, Total: 0}, *b["a"])
}

func TestGetBalancesPayerNotInPaidFor(t *testing.T) {
	// a pays 900 that is entirely owed by b and c
	b := GetBalances([]Expense{{Amount: 900, PaidByID: "a", SplitMode: Evenly,
		PaidFor: []PaidFor{{"b", 100}, {"c", 100}}}})
	require.Equal(t, Balance{Paid: 900, PaidFor: 0, Total: 900}, *b["a"])
	require.Equal(t, Balance{Paid: 0, PaidFor: 450, Total: -450}, *b["b"])
	require.Equal(t, Balance{Paid: 0, PaidFor: 450, Total: -450}, *b["c"])
}

func TestReimbursementExpenseShiftsBalance(t *testing.T) {
	// b owes a 500; a reimbursement expense (b pays a back 500) nets them to zero.
	expenses := []Expense{
		{Amount: 1000, PaidByID: "a", SplitMode: Evenly, PaidFor: []PaidFor{{"a", 100}, {"b", 100}}},
		{Amount: 500, PaidByID: "b", SplitMode: Evenly, PaidFor: []PaidFor{{"a", 100}}}, // settlement
	}
	b := GetBalances(expenses)
	require.Equal(t, int64(0), b["a"].Total)
	require.Equal(t, int64(0), b["b"].Total)
}

func TestSuggestedReimbursementsAllSettled(t *testing.T) {
	require.Empty(t, GetSuggestedReimbursements(Balances{
		"a": {Total: 0}, "b": {Total: 0},
	}))
}

func TestSuggestedReimbursementsNetToZero(t *testing.T) {
	balances := Balances{
		"a": {Total: 700}, "b": {Total: -200}, "c": {Total: -500},
	}
	reimb := GetSuggestedReimbursements(balances)
	var sum int64
	for _, r := range reimb {
		sum += r.Amount
	}
	require.Equal(t, int64(700), sum) // everyone repays the +700 holder
	for _, r := range reimb {
		require.Equal(t, "a", r.To)
	}
}

func TestAllocateSharesExact(t *testing.T) {
	require.Equal(t, map[string]int64{"a": 300, "b": 600},
		AllocateShares(Expense{Amount: 900, SplitMode: ByShares,
			PaidFor: []PaidFor{{"a", 100}, {"b", 200}}}))

	require.Equal(t, map[string]int64{"a": 300, "b": 700},
		AllocateShares(Expense{Amount: 1000, SplitMode: ByAmount,
			PaidFor: []PaidFor{{"a", 300}, {"b", 700}}}))

	require.Equal(t, map[string]int64{"a": 500, "b": 500},
		AllocateShares(Expense{Amount: 1000, SplitMode: Evenly,
			PaidFor: []PaidFor{{"a", 100}, {"b", 100}}}))
}

func TestValidateBoundaryMaxAmount(t *testing.T) {
	require.NoError(t, ValidateExpense(Expense{Amount: MaxAmount, SplitMode: Evenly,
		PaidFor: []PaidFor{{"a", 100}}}))
	require.ErrorIs(t, ValidateExpense(Expense{Amount: MaxAmount + 1, SplitMode: Evenly,
		PaidFor: []PaidFor{{"a", 100}}}), ErrAmountTooLarge)
}
