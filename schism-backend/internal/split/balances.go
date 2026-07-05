package split

import (
	"math"
	"sort"
)

// roundHalfUp mirrors JS Math.round for non-negative values (ties go up).
func roundHalfUp(x float64) int64 {
	return int64(math.Floor(x + 0.5))
}

// GetBalances computes per-participant paid/paidFor/total for a set of expenses.
func GetBalances(expenses []Expense) Balances {
	balances := Balances{}
	paidForFloat := map[string]float64{}
	ensure := func(id string) {
		if balances[id] == nil {
			balances[id] = &Balance{}
		}
	}

	for _, e := range expenses {
		ensure(e.PaidByID)
		balances[e.PaidByID].Paid += e.Amount

		var totalShares int64
		for _, pf := range e.PaidFor {
			totalShares += pf.Shares
		}

		remaining := float64(e.Amount)
		n := len(e.PaidFor)
		for i, pf := range e.PaidFor {
			ensure(pf.ParticipantID)
			isLast := i == n-1

			var shares, tShares float64
			switch e.SplitMode {
			case Evenly:
				shares, tShares = 1, float64(n)
			default: // BY_SHARES, BY_PERCENTAGE, BY_AMOUNT
				shares, tShares = float64(pf.Shares), float64(totalShares)
			}

			var divided float64
			if isLast {
				divided = remaining
			} else {
				divided = float64(e.Amount) * shares / tShares
			}
			remaining -= divided
			paidForFloat[pf.ParticipantID] += divided
		}
	}

	for id := range balances {
		balances[id].PaidFor = roundHalfUp(paidForFloat[id])
		balances[id].Total = balances[id].Paid - balances[id].PaidFor
	}
	return balances
}

// AllocateShares returns each participant's allocated portion of a single expense, with the last
// participant absorbing the rounding remainder. Useful for per-expense/per-category attribution.
func AllocateShares(e Expense) map[string]int64 {
	out := map[string]int64{}
	var totalShares int64
	for _, pf := range e.PaidFor {
		totalShares += pf.Shares
	}
	remaining := float64(e.Amount)
	n := len(e.PaidFor)
	for i, pf := range e.PaidFor {
		isLast := i == n-1
		var shares, tShares float64
		switch e.SplitMode {
		case Evenly:
			shares, tShares = 1, float64(n)
		default:
			shares, tShares = float64(pf.Shares), float64(totalShares)
		}
		var divided float64
		if isLast {
			divided = remaining
		} else {
			divided = float64(e.Amount) * shares / tShares
		}
		remaining -= divided
		out[pf.ParticipantID] += roundHalfUp(divided)
	}
	return out
}

type balanceTotal struct {
	participantID string
	total         int64
}

// compareForReimbursements: positives before negatives; else by participantID asc.
func compareForReimbursements(a, b balanceTotal) int {
	if a.total > 0 && b.total < 0 {
		return -1
	}
	if b.total > 0 && a.total < 0 {
		return 1
	}
	if a.participantID < b.participantID {
		return -1
	}
	return 1
}

// GetSuggestedReimbursements greedily minimizes the set of transfers that settle all balances.
func GetSuggestedReimbursements(balances Balances) []Reimbursement {
	arr := make([]balanceTotal, 0, len(balances))
	for id, b := range balances {
		if b.Total != 0 {
			arr = append(arr, balanceTotal{id, b.Total})
		}
	}
	sort.SliceStable(arr, func(i, j int) bool {
		return compareForReimbursements(arr[i], arr[j]) < 0
	})

	reimb := []Reimbursement{}
	for len(arr) > 1 {
		first := 0
		last := len(arr) - 1
		amount := arr[first].total + arr[last].total
		if arr[first].total > -arr[last].total {
			reimb = append(reimb, Reimbursement{From: arr[last].participantID, To: arr[first].participantID, Amount: -arr[last].total})
			arr[first].total = amount
			arr = arr[:last] // pop last
		} else {
			reimb = append(reimb, Reimbursement{From: arr[last].participantID, To: arr[first].participantID, Amount: arr[first].total})
			arr[last].total = amount
			arr = arr[1:] // shift first
		}
	}

	out := []Reimbursement{}
	for _, r := range reimb {
		if r.Amount != 0 {
			out = append(out, r)
		}
	}
	return out
}

// GetPublicBalances derives displayable balances from the suggested reimbursements.
func GetPublicBalances(reimbursements []Reimbursement) Balances {
	balances := Balances{}
	ensure := func(id string) {
		if balances[id] == nil {
			balances[id] = &Balance{}
		}
	}
	for _, r := range reimbursements {
		ensure(r.From)
		ensure(r.To)
		balances[r.From].PaidFor += r.Amount
		balances[r.From].Total -= r.Amount
		balances[r.To].Paid += r.Amount
		balances[r.To].Total += r.Amount
	}
	return balances
}
