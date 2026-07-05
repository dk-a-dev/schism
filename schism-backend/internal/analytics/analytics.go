// Package analytics computes dashboard/insight data from stored groups and expenses.
// It is a pure read/compute layer over the store types — no database access — so it is fully
// unit-testable and never sums money across different currencies.
package analytics

import (
	"sort"
	"strings"
	"time"

	"github.com/schism/schism-backend/internal/split"
	"github.com/schism/schism-backend/internal/store"
)

type CategoryTotal struct {
	CategoryID int    `json:"categoryId"`
	Grouping   string `json:"grouping"`
	Name       string `json:"name"`
	Amount     int64  `json:"amount"`
	Count      int    `json:"count"`
}

type ParticipantTotal struct {
	ParticipantID string `json:"participantId"`
	Name          string `json:"name"`
	Paid          int64  `json:"paid"`
	Share         int64  `json:"share"`
	Net           int64  `json:"net"`
}

type MonthTotal struct {
	Month  string `json:"month"` // YYYY-MM
	Amount int64  `json:"amount"`
	Count  int    `json:"count"`
}

type ExpenseSummary struct {
	ID         string    `json:"id"`
	Title      string    `json:"title"`
	Amount     int64     `json:"amount"`
	Date       time.Time `json:"date"`
	PaidByID   string    `json:"paidById"`
	CategoryID int       `json:"categoryId"`
}

// PersonalInGroup is the requesting participant's slice of a single (single-currency) group.
type PersonalInGroup struct {
	ParticipantID string          `json:"participantId"`
	Name          string          `json:"name"`
	Paid          int64           `json:"paid"`
	Share         int64           `json:"share"`
	Net           int64           `json:"net"`
	ExpenseCount  int             `json:"expenseCount"`
	ByCategory    []CategoryTotal `json:"byCategory"`
}

type GroupDashboard struct {
	GroupID            string             `json:"groupId"`
	Name               string             `json:"name"`
	Currency           string             `json:"currency"`
	CurrencyCode       string             `json:"currencyCode"`
	TotalSpending      int64              `json:"totalSpending"`
	ExpenseCount       int                `json:"expenseCount"`
	ReimbursementCount int                `json:"reimbursementCount"`
	AverageExpense     int64              `json:"averageExpense"`
	FirstExpenseDate   *time.Time         `json:"firstExpenseDate"`
	LastExpenseDate    *time.Time         `json:"lastExpenseDate"`
	ByCategory         []CategoryTotal    `json:"byCategory"`
	ByParticipant      []ParticipantTotal `json:"byParticipant"`
	ByMonth            []MonthTotal       `json:"byMonth"`
	TopExpenses        []ExpenseSummary   `json:"topExpenses"`
	Personal           *PersonalInGroup   `json:"personal,omitempty"`
}

func toSplit(expenses []store.Expense) []split.Expense {
	out := make([]split.Expense, len(expenses))
	for i, e := range expenses {
		pf := make([]split.PaidFor, len(e.PaidFor))
		for j, p := range e.PaidFor {
			pf[j] = split.PaidFor{ParticipantID: p.ParticipantID, Shares: p.Shares}
		}
		out[i] = split.Expense{ID: e.ID, Amount: e.Amount, PaidByID: e.PaidByID, PaidFor: pf, SplitMode: split.SplitMode(e.SplitMode)}
	}
	return out
}

func catLabel(cats map[int]store.Category, id int) (string, string) {
	if c, ok := cats[id]; ok {
		return c.Grouping, c.Name
	}
	return "Uncategorized", "General"
}

// BuildGroupDashboard aggregates a single group's expenses into dashboard insights.
// If participantID is non-empty, the requesting participant's personal slice is attached.
func BuildGroupDashboard(g store.Group, expenses []store.Expense, categories []store.Category, participantID string) GroupDashboard {
	cats := map[int]store.Category{}
	for _, c := range categories {
		cats[c.ID] = c
	}
	balances := split.GetBalances(toSplit(expenses))

	d := GroupDashboard{
		GroupID: g.ID, Name: g.Name, Currency: g.Currency, CurrencyCode: g.CurrencyCode,
		ByCategory: []CategoryTotal{}, ByParticipant: []ParticipantTotal{},
		ByMonth: []MonthTotal{}, TopExpenses: []ExpenseSummary{},
	}

	catAgg := map[int]*CategoryTotal{}
	monthAgg := map[string]*MonthTotal{}
	spend := make([]store.Expense, 0, len(expenses))

	for _, e := range expenses {
		if e.IsReimbursement {
			d.ReimbursementCount++
			continue
		}
		d.TotalSpending += e.Amount
		d.ExpenseCount++
		spend = append(spend, e)

		ed := e.ExpenseDate
		if d.FirstExpenseDate == nil || ed.Before(*d.FirstExpenseDate) {
			t := ed
			d.FirstExpenseDate = &t
		}
		if d.LastExpenseDate == nil || ed.After(*d.LastExpenseDate) {
			t := ed
			d.LastExpenseDate = &t
		}

		ct := catAgg[e.CategoryID]
		if ct == nil {
			grouping, name := catLabel(cats, e.CategoryID)
			ct = &CategoryTotal{CategoryID: e.CategoryID, Grouping: grouping, Name: name}
			catAgg[e.CategoryID] = ct
		}
		ct.Amount += e.Amount
		ct.Count++

		m := e.ExpenseDate.Format("2006-01")
		mt := monthAgg[m]
		if mt == nil {
			mt = &MonthTotal{Month: m}
			monthAgg[m] = mt
		}
		mt.Amount += e.Amount
		mt.Count++
	}

	if d.ExpenseCount > 0 {
		d.AverageExpense = d.TotalSpending / int64(d.ExpenseCount)
	}

	for _, ct := range catAgg {
		d.ByCategory = append(d.ByCategory, *ct)
	}
	sort.Slice(d.ByCategory, func(i, j int) bool {
		if d.ByCategory[i].Amount != d.ByCategory[j].Amount {
			return d.ByCategory[i].Amount > d.ByCategory[j].Amount
		}
		return d.ByCategory[i].CategoryID < d.ByCategory[j].CategoryID
	})

	for _, mt := range monthAgg {
		d.ByMonth = append(d.ByMonth, *mt)
	}
	sort.Slice(d.ByMonth, func(i, j int) bool { return d.ByMonth[i].Month < d.ByMonth[j].Month })

	for _, p := range g.Participants {
		pt := ParticipantTotal{ParticipantID: p.ID, Name: p.Name}
		if b := balances[p.ID]; b != nil {
			pt.Paid, pt.Share, pt.Net = b.Paid, b.PaidFor, b.Total
		}
		d.ByParticipant = append(d.ByParticipant, pt)
	}
	sort.Slice(d.ByParticipant, func(i, j int) bool {
		if d.ByParticipant[i].Paid != d.ByParticipant[j].Paid {
			return d.ByParticipant[i].Paid > d.ByParticipant[j].Paid
		}
		return d.ByParticipant[i].ParticipantID < d.ByParticipant[j].ParticipantID
	})

	sort.SliceStable(spend, func(i, j int) bool { return spend[i].Amount > spend[j].Amount })
	for i, e := range spend {
		if i >= 5 {
			break
		}
		d.TopExpenses = append(d.TopExpenses, ExpenseSummary{
			ID: e.ID, Title: e.Title, Amount: e.Amount, Date: e.ExpenseDate,
			PaidByID: e.PaidByID, CategoryID: e.CategoryID,
		})
	}

	if participantID != "" {
		d.Personal = buildPersonalInGroup(g, expenses, cats, balances, participantID)
	}
	return d
}

func buildPersonalInGroup(g store.Group, expenses []store.Expense, cats map[int]store.Category, balances split.Balances, participantID string) *PersonalInGroup {
	name := participantID
	found := false
	for _, p := range g.Participants {
		if p.ID == participantID {
			name = p.Name
			found = true
			break
		}
	}
	if !found {
		return nil
	}

	p := &PersonalInGroup{ParticipantID: participantID, Name: name, ByCategory: []CategoryTotal{}}
	if b := balances[participantID]; b != nil {
		p.Paid, p.Share, p.Net = b.Paid, b.PaidFor, b.Total
	}

	catAgg := map[int]*CategoryTotal{}
	for _, e := range expenses {
		if e.IsReimbursement {
			continue
		}
		shares := split.AllocateShares(toSplit([]store.Expense{e})[0])
		mine, ok := shares[participantID]
		if !ok || mine == 0 {
			continue
		}
		p.ExpenseCount++
		ct := catAgg[e.CategoryID]
		if ct == nil {
			grouping, cname := catLabel(cats, e.CategoryID)
			ct = &CategoryTotal{CategoryID: e.CategoryID, Grouping: grouping, Name: cname}
			catAgg[e.CategoryID] = ct
		}
		ct.Amount += mine
		ct.Count++
	}
	for _, ct := range catAgg {
		p.ByCategory = append(p.ByCategory, *ct)
	}
	sort.Slice(p.ByCategory, func(i, j int) bool {
		if p.ByCategory[i].Amount != p.ByCategory[j].Amount {
			return p.ByCategory[i].Amount > p.ByCategory[j].Amount
		}
		return p.ByCategory[i].CategoryID < p.ByCategory[j].CategoryID
	})
	return p
}

// ---- Cross-group personal dashboard ----

type PersonalGroupSlice struct {
	GroupID         string `json:"groupId"`
	GroupName       string `json:"groupName"`
	Currency        string `json:"currency"`
	CurrencyCode    string `json:"currencyCode"`
	ParticipantID   string `json:"participantId"`
	ParticipantName string `json:"participantName"`
	Paid            int64  `json:"paid"`
	Share           int64  `json:"share"`
	Net             int64  `json:"net"`
	ExpenseCount    int    `json:"expenseCount"`
}

// CurrencyTotal buckets personal totals by currency so figures are never summed across currencies.
type CurrencyTotal struct {
	CurrencyCode string `json:"currencyCode"`
	Currency     string `json:"currency"`
	Paid         int64  `json:"paid"`
	Share        int64  `json:"share"`
	Net          int64  `json:"net"`
	GroupCount   int    `json:"groupCount"`
}

type PersonalDashboard struct {
	Identity   string               `json:"identity"`
	GroupCount int                  `json:"groupCount"`
	Groups     []PersonalGroupSlice `json:"groups"`
	Totals     []CurrencyTotal      `json:"totals"`
}

// GroupExpenses pairs a group with its expenses for cross-group aggregation.
type GroupExpenses struct {
	Group    store.Group
	Expenses []store.Expense
}

// BuildPersonalDashboard aggregates the requesting person's position across many groups.
// identity matches a participant by exact name (case-insensitive) or by participant id within each
// group; groups where no participant matches are skipped. Totals are bucketed per currency.
func BuildPersonalDashboard(identity string, groups []GroupExpenses) PersonalDashboard {
	d := PersonalDashboard{Identity: identity, Groups: []PersonalGroupSlice{}, Totals: []CurrencyTotal{}}
	totals := map[string]*CurrencyTotal{}
	needle := strings.ToLower(strings.TrimSpace(identity))

	for _, ge := range groups {
		var pid, pname string
		for _, p := range ge.Group.Participants {
			if p.ID == identity || strings.ToLower(p.Name) == needle {
				pid, pname = p.ID, p.Name
				break
			}
		}
		if pid == "" {
			continue
		}
		balances := split.GetBalances(toSplit(ge.Expenses))
		b := balances[pid]
		slice := PersonalGroupSlice{
			GroupID: ge.Group.ID, GroupName: ge.Group.Name,
			Currency: ge.Group.Currency, CurrencyCode: ge.Group.CurrencyCode,
			ParticipantID: pid, ParticipantName: pname,
		}
		if b != nil {
			slice.Paid, slice.Share, slice.Net = b.Paid, b.PaidFor, b.Total
		}
		for _, e := range ge.Expenses {
			if e.PaidByID == pid {
				slice.ExpenseCount++
				continue
			}
			for _, pf := range e.PaidFor {
				if pf.ParticipantID == pid {
					slice.ExpenseCount++
					break
				}
			}
		}
		d.Groups = append(d.Groups, slice)
		d.GroupCount++

		key := ge.Group.CurrencyCode
		if key == "" {
			key = ge.Group.Currency
		}
		ct := totals[key]
		if ct == nil {
			ct = &CurrencyTotal{CurrencyCode: ge.Group.CurrencyCode, Currency: ge.Group.Currency}
			totals[key] = ct
		}
		ct.Paid += slice.Paid
		ct.Share += slice.Share
		ct.Net += slice.Net
		ct.GroupCount++
	}

	for _, ct := range totals {
		d.Totals = append(d.Totals, *ct)
	}
	sort.Slice(d.Totals, func(i, j int) bool { return d.Totals[i].CurrencyCode < d.Totals[j].CurrencyCode })
	return d
}
