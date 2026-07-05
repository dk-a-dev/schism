package split

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func expenseWith(mode SplitMode, amount int64, pf ...PaidFor) Expense {
	return Expense{Amount: amount, PaidByID: "a", PaidFor: pf, SplitMode: mode}
}

func TestValidateOK(t *testing.T) {
	require.NoError(t, ValidateExpense(expenseWith(Evenly, 1000,
		PaidFor{"a", 100}, PaidFor{"b", 100})))
}

func TestValidateZeroAmount(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 0, PaidFor{"a", 100})), ErrAmountZero)
}

func TestValidateTooLarge(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 10_000_000_01, PaidFor{"a", 100})), ErrAmountTooLarge)
}

func TestValidateNoPaidFor(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(Evenly, 1000)), ErrPaidForMin)
}

func TestValidateZeroShares(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByShares, 1000, PaidFor{"a", 0})), ErrZeroShares)
}

func TestValidateByAmountSum(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByAmount, 1000, PaidFor{"a", 300}, PaidFor{"b", 600})), ErrAmountSum)
	require.NoError(t, ValidateExpense(expenseWith(ByAmount, 1000, PaidFor{"a", 300}, PaidFor{"b", 700})))
}

func TestValidateByPercentageSum(t *testing.T) {
	require.ErrorIs(t, ValidateExpense(expenseWith(ByPercentage, 1000, PaidFor{"a", 3000}, PaidFor{"b", 6000})), ErrPercentageSum)
	require.NoError(t, ValidateExpense(expenseWith(ByPercentage, 1000, PaidFor{"a", 3000}, PaidFor{"b", 7000})))
}
