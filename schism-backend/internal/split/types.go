package split

type SplitMode string

const (
	Evenly       SplitMode = "EVENLY"
	ByShares     SplitMode = "BY_SHARES"
	ByPercentage SplitMode = "BY_PERCENTAGE"
	ByAmount     SplitMode = "BY_AMOUNT"
)

type PaidFor struct {
	ParticipantID string
	Shares        int64
}

type Expense struct {
	ID        string
	Amount    int64
	PaidByID  string
	PaidFor   []PaidFor
	SplitMode SplitMode
}

type Balance struct {
	Paid    int64 `json:"paid"`
	PaidFor int64 `json:"paidFor"`
	Total   int64 `json:"total"`
}

type Balances map[string]*Balance

type Reimbursement struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Amount int64  `json:"amount"`
}
