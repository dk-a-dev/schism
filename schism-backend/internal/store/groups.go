package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/schism/schism-backend/internal/id"
)

type Store struct{ pool *pgxpool.Pool }

func NewStore(pool *pgxpool.Pool) *Store { return &Store{pool: pool} }

type ParticipantInput struct {
	ID     *string
	Name   string
	UserID *string
}
type GroupInput struct {
	Name         string
	Information  string
	Currency     string
	CurrencyCode string
	Participants []ParticipantInput
}
type Participant struct {
	ID      string  `json:"id"`
	GroupID string  `json:"groupId"`
	Name    string  `json:"name"`
	UserID  *string `json:"userId"`
}
type Group struct {
	ID           string        `json:"id"`
	Name         string        `json:"name"`
	Information  string        `json:"information"`
	Currency     string        `json:"currency"`
	CurrencyCode string        `json:"currencyCode"`
	CreatedAt    time.Time     `json:"createdAt"`
	Participants []Participant `json:"participants"`
}

func (s *Store) CreateGroup(ctx context.Context, in GroupInput) (Group, error) {
	gid := id.New()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Group{}, err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`INSERT INTO groups (id, name, information, currency, currency_code)
		 VALUES ($1,$2,$3,$4,$5)`,
		gid, in.Name, nullify(in.Information), in.Currency, nullify(in.CurrencyCode)); err != nil {
		return Group{}, err
	}
	for _, p := range in.Participants {
		if _, err := tx.Exec(ctx,
			`INSERT INTO participants (id, group_id, name, user_id) VALUES ($1,$2,$3,$4)`,
			id.New(), gid, p.Name, nullifyPtr(p.UserID)); err != nil {
			return Group{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return Group{}, err
	}
	g, err := s.GetGroup(ctx, gid)
	if err != nil {
		return Group{}, err
	}
	return *g, nil
}

func (s *Store) GetGroup(ctx context.Context, gid string) (*Group, error) {
	var g Group
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, COALESCE(information,''), currency, COALESCE(currency_code,''), created_at
		 FROM groups WHERE id=$1`, gid).
		Scan(&g.ID, &g.Name, &g.Information, &g.Currency, &g.CurrencyCode, &g.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	parts, err := s.participants(ctx, gid)
	if err != nil {
		return nil, err
	}
	g.Participants = parts
	return &g, nil
}

func (s *Store) participants(ctx context.Context, gid string) ([]Participant, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, group_id, name, user_id FROM participants WHERE group_id=$1 ORDER BY name`, gid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Participant{}
	for rows.Next() {
		var p Participant
		if err := rows.Scan(&p.ID, &p.GroupID, &p.Name, &p.UserID); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ListGroups(ctx context.Context, ids []string) ([]Group, error) {
	out := []Group{}
	for _, gid := range ids {
		g, err := s.GetGroup(ctx, gid)
		if err != nil {
			return nil, err
		}
		if g != nil {
			out = append(out, *g)
		}
	}
	return out, nil
}

// UpdateGroup updates group fields and reconciles participants: those with an ID are updated,
// those without are inserted, and existing participants absent from the input are deleted.
func (s *Store) UpdateGroup(ctx context.Context, gid string, in GroupInput) (*Group, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	ct, err := tx.Exec(ctx,
		`UPDATE groups SET name=$2, information=$3, currency=$4, currency_code=$5 WHERE id=$1`,
		gid, in.Name, nullify(in.Information), in.Currency, nullify(in.CurrencyCode))
	if err != nil {
		return nil, err
	}
	if ct.RowsAffected() == 0 {
		return nil, nil
	}

	keep := map[string]bool{}
	for _, p := range in.Participants {
		if p.ID != nil {
			keep[*p.ID] = true
			if _, err := tx.Exec(ctx,
				`UPDATE participants SET name=$2, user_id=$4 WHERE id=$1 AND group_id=$3`,
				*p.ID, p.Name, gid, nullifyPtr(p.UserID)); err != nil {
				return nil, err
			}
		} else {
			newID := id.New()
			keep[newID] = true
			if _, err := tx.Exec(ctx,
				`INSERT INTO participants (id, group_id, name, user_id) VALUES ($1,$2,$3,$4)`,
				newID, gid, p.Name, nullifyPtr(p.UserID)); err != nil {
				return nil, err
			}
		}
	}

	rows, err := tx.Query(ctx, `SELECT id FROM participants WHERE group_id=$1`, gid)
	if err != nil {
		return nil, err
	}
	var existing []string
	for rows.Next() {
		var pid string
		if err := rows.Scan(&pid); err != nil {
			rows.Close()
			return nil, err
		}
		existing = append(existing, pid)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, pid := range existing {
		if !keep[pid] {
			if _, err := tx.Exec(ctx, `DELETE FROM participants WHERE id=$1`, pid); err != nil {
				return nil, err
			}
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return s.GetGroup(ctx, gid)
}

func nullify(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func nullifyPtr(s *string) any {
	if s == nil || *s == "" {
		return nil
	}
	return *s
}
