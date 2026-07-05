package split

import "errors"

const MaxAmount int64 = 10_000_000_00

var (
	ErrAmountZero     = errors.New("amount must not be zero")
	ErrAmountTooLarge = errors.New("amount exceeds maximum")
	ErrPaidForMin     = errors.New("at least one paidFor is required")
	ErrZeroShares     = errors.New("shares must be greater than zero")
	ErrAmountSum      = errors.New("paidFor amounts must sum to expense amount")
	ErrPercentageSum  = errors.New("paidFor percentages must sum to 100%")
)

// ValidateExpense validates an expense against the split rules, operating on the
// already-scaled integer share model the API accepts.
func ValidateExpense(e Expense) error {
	if e.Amount == 0 {
		return ErrAmountZero
	}
	if e.Amount > MaxAmount {
		return ErrAmountTooLarge
	}
	if len(e.PaidFor) < 1 {
		return ErrPaidForMin
	}
	for _, pf := range e.PaidFor {
		if pf.Shares <= 0 {
			return ErrZeroShares
		}
	}
	switch e.SplitMode {
	case ByAmount:
		var sum int64
		for _, pf := range e.PaidFor {
			sum += pf.Shares
		}
		if sum != e.Amount {
			return ErrAmountSum
		}
	case ByPercentage:
		var sum int64
		for _, pf := range e.PaidFor {
			sum += pf.Shares
		}
		if sum != 10000 {
			return ErrPercentageSum
		}
	}
	return nil
}
