package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/split"
)

type PaidForInput struct {
	ParticipantID string
	Shares        int64
}
type ExpenseInput struct {
	Title           string
	Amount          int64
	CategoryID      int
	ExpenseDate     time.Time
	PaidByID        string
	SplitMode       string
	IsReimbursement bool
	Notes           string
	PaidFor         []PaidForInput
}
type PaidForRow struct {
	ParticipantID string `json:"participantId"`
	Shares        int64  `json:"shares"`
}
type Expense struct {
	ID              string       `json:"id"`
	GroupID         string       `json:"groupId"`
	Title           string       `json:"title"`
	Amount          int64        `json:"amount"`
	CategoryID      int          `json:"categoryId"`
	ExpenseDate     time.Time    `json:"expenseDate"`
	PaidByID        string       `json:"paidById"`
	SplitMode       string       `json:"splitMode"`
	IsReimbursement bool         `json:"isReimbursement"`
	Notes           string       `json:"notes"`
	CreatedAt       time.Time    `json:"createdAt"`
	PaidFor         []PaidForRow `json:"paidFor"`
}

func (s *Store) CreateExpense(ctx context.Context, groupID string, in ExpenseInput, idemKey string) (Expense, error) {
	if idemKey != "" {
		var existingID string
		err := s.pool.QueryRow(ctx, `SELECT expense_id FROM expense_idempotency WHERE group_id=$1 AND key=$2`, groupID, idemKey).Scan(&existingID)
		if err == nil {
			e, gerr := s.GetExpense(ctx, groupID, existingID)
			if gerr != nil {
				return Expense{}, gerr
			}
			if e != nil {
				return *e, nil
			}
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return Expense{}, err
		}
	}

	eid := id.New()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Expense{}, err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`INSERT INTO expenses (id, group_id, expense_date, title, category_id, amount, paid_by_id, is_reimbursement, split_mode, notes)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
		eid, groupID, in.ExpenseDate, in.Title, in.CategoryID, in.Amount, in.PaidByID, in.IsReimbursement, in.SplitMode, nullify(in.Notes)); err != nil {
		return Expense{}, err
	}
	for _, pf := range in.PaidFor {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_paid_for (expense_id, participant_id, shares) VALUES ($1,$2,$3)`,
			eid, pf.ParticipantID, pf.Shares); err != nil {
			return Expense{}, err
		}
	}
	if idemKey != "" {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_idempotency (group_id, key, expense_id) VALUES ($1,$2,$3)`, groupID, idemKey, eid); err != nil {
			return Expense{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return Expense{}, err
	}
	e, err := s.GetExpense(ctx, groupID, eid)
	if err != nil {
		return Expense{}, err
	}
	return *e, nil
}

func (s *Store) GetExpense(ctx context.Context, groupID, expenseID string) (*Expense, error) {
	var e Expense
	err := s.pool.QueryRow(ctx,
		`SELECT id, group_id, title, amount, category_id, expense_date, paid_by_id,
		        is_reimbursement, split_mode, COALESCE(notes,''), created_at
		 FROM expenses WHERE id=$1 AND group_id=$2`, expenseID, groupID).
		Scan(&e.ID, &e.GroupID, &e.Title, &e.Amount, &e.CategoryID, &e.ExpenseDate, &e.PaidByID,
			&e.IsReimbursement, &e.SplitMode, &e.Notes, &e.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	pf, err := s.paidFor(ctx, e.ID)
	if err != nil {
		return nil, err
	}
	e.PaidFor = pf
	return &e, nil
}

func (s *Store) paidFor(ctx context.Context, expenseID string) ([]PaidForRow, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT participant_id, shares FROM expense_paid_for WHERE expense_id=$1 ORDER BY participant_id`, expenseID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PaidForRow{}
	for rows.Next() {
		var p PaidForRow
		if err := rows.Scan(&p.ParticipantID, &p.Shares); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ListExpenses(ctx context.Context, groupID string) ([]Expense, error) {
	rows, err := s.pool.Query(ctx, `SELECT id FROM expenses WHERE group_id=$1 ORDER BY expense_date DESC, created_at DESC`, groupID)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var eid string
		if err := rows.Scan(&eid); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, eid)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := []Expense{}
	for _, eid := range ids {
		e, err := s.GetExpense(ctx, groupID, eid)
		if err != nil {
			return nil, err
		}
		if e != nil {
			out = append(out, *e)
		}
	}
	return out, nil
}

func (s *Store) UpdateExpense(ctx context.Context, groupID, expenseID string, in ExpenseInput) (*Expense, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	ct, err := tx.Exec(ctx,
		`UPDATE expenses SET title=$3, amount=$4, category_id=$5, expense_date=$6, paid_by_id=$7,
		        is_reimbursement=$8, split_mode=$9, notes=$10
		 WHERE id=$1 AND group_id=$2`,
		expenseID, groupID, in.Title, in.Amount, in.CategoryID, in.ExpenseDate, in.PaidByID,
		in.IsReimbursement, in.SplitMode, nullify(in.Notes))
	if err != nil {
		return nil, err
	}
	if ct.RowsAffected() == 0 {
		return nil, nil
	}
	if _, err := tx.Exec(ctx, `DELETE FROM expense_paid_for WHERE expense_id=$1`, expenseID); err != nil {
		return nil, err
	}
	for _, pf := range in.PaidFor {
		if _, err := tx.Exec(ctx,
			`INSERT INTO expense_paid_for (expense_id, participant_id, shares) VALUES ($1,$2,$3)`,
			expenseID, pf.ParticipantID, pf.Shares); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return s.GetExpense(ctx, groupID, expenseID)
}

func (s *Store) DeleteExpense(ctx context.Context, groupID, expenseID string) (bool, error) {
	ct, err := s.pool.Exec(ctx, `DELETE FROM expenses WHERE id=$1 AND group_id=$2`, expenseID, groupID)
	if err != nil {
		return false, err
	}
	return ct.RowsAffected() > 0, nil
}

// SplitExpenses adapts stored expenses into the pure split package's shape.
func (s *Store) SplitExpenses(ctx context.Context, groupID string) ([]split.Expense, error) {
	list, err := s.ListExpenses(ctx, groupID)
	if err != nil {
		return nil, err
	}
	out := make([]split.Expense, len(list))
	for i, e := range list {
		pf := make([]split.PaidFor, len(e.PaidFor))
		for j, p := range e.PaidFor {
			pf[j] = split.PaidFor{ParticipantID: p.ParticipantID, Shares: p.Shares}
		}
		out[i] = split.Expense{ID: e.ID, Amount: e.Amount, PaidByID: e.PaidByID, PaidFor: pf, SplitMode: split.SplitMode(e.SplitMode)}
	}
	return out, nil
}
