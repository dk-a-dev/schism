package store

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
)

// ErrClaimLocked is returned when an operation targets a claim session that is no longer "open"
// (finalized or cancelled).
var ErrClaimLocked = errors.New("session locked")

// ErrClaimStale is returned when a caller's expectedVersion no longer matches the session's current
// version (someone else edited items or claims concurrently).
var ErrClaimStale = errors.New("session version stale")

// ClaimItem is a single receipt line item captured on a claim session.
type ClaimItem struct {
	Idx         int    `json:"idx"`
	Name        string `json:"name"`
	Qty         int    `json:"qty"`
	AmountMinor int64  `json:"amountMinor"`
}

// ClaimSessionInput is the input to CreateClaimSession.
type ClaimSessionInput struct {
	GroupID              string
	CreatorParticipantID string
	Title                string
	Currency             string
	Items                []ClaimItem
	TaxMinor             int64
	FeesMinor            int64
	DiscountMinor        int64
	RoundoffMinor        int64
}

// Claim is one participant's weighted claim on one item.
type Claim struct {
	ItemIdx       int     `json:"itemIdx"`
	ParticipantID string  `json:"participantId"`
	Weight        float64 `json:"weight"`
}

// ClaimSession is a "claim what you ate" session: a locked-in receipt (items + tax/fees/discount/
// roundoff) that group members claim weighted shares of, until the creator finalizes it into an
// expense.
type ClaimSession struct {
	ID                   string      `json:"id"`
	GroupID              string      `json:"groupId"`
	CreatorParticipantID string      `json:"creatorParticipantId"`
	Title                string      `json:"title"`
	Currency             string      `json:"currency"`
	Status               string      `json:"status"`
	Items                []ClaimItem `json:"items"`
	TaxMinor             int64       `json:"taxMinor"`
	FeesMinor            int64       `json:"feesMinor"`
	DiscountMinor        int64       `json:"discountMinor"`
	RoundoffMinor        int64       `json:"roundoffMinor"`
	Version              int         `json:"version"`
	ExpenseID            *string     `json:"expenseId"`
	Claims               []Claim     `json:"claims"`
}

// CreateClaimSession inserts a new open claim session (version 1) with the given items and charge
// pot components.
func (s *Store) CreateClaimSession(ctx context.Context, in ClaimSessionInput) (ClaimSession, error) {
	items := in.Items
	if items == nil {
		items = []ClaimItem{}
	}
	itemsJSON, err := json.Marshal(items)
	if err != nil {
		return ClaimSession{}, err
	}

	sid := id.New()
	_, err = s.pool.Exec(ctx,
		`INSERT INTO claim_sessions (id, group_id, creator_participant_id, title, currency, items,
		                              tax_minor, fees_minor, discount_minor, roundoff_minor)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
		sid, in.GroupID, in.CreatorParticipantID, in.Title, in.Currency, itemsJSON,
		in.TaxMinor, in.FeesMinor, in.DiscountMinor, in.RoundoffMinor)
	if err != nil {
		return ClaimSession{}, err
	}

	cs, err := s.GetClaimSession(ctx, sid)
	if err != nil {
		return ClaimSession{}, err
	}
	return *cs, nil
}

// GetClaimSession loads a claim session and its claims, or (nil, nil) if it doesn't exist.
func (s *Store) GetClaimSession(ctx context.Context, sid string) (*ClaimSession, error) {
	var cs ClaimSession
	var itemsJSON []byte
	err := s.pool.QueryRow(ctx,
		`SELECT id, group_id, creator_participant_id, title, currency, items,
		        tax_minor, fees_minor, discount_minor, roundoff_minor, status, version, expense_id
		 FROM claim_sessions WHERE id=$1`, sid).
		Scan(&cs.ID, &cs.GroupID, &cs.CreatorParticipantID, &cs.Title, &cs.Currency, &itemsJSON,
			&cs.TaxMinor, &cs.FeesMinor, &cs.DiscountMinor, &cs.RoundoffMinor, &cs.Status, &cs.Version, &cs.ExpenseID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(itemsJSON, &cs.Items); err != nil {
		return nil, err
	}

	claims, err := s.claimsFor(ctx, sid)
	if err != nil {
		return nil, err
	}
	cs.Claims = claims
	return &cs, nil
}

func (s *Store) claimsFor(ctx context.Context, sid string) ([]Claim, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT item_idx, participant_id, weight FROM claims WHERE session_id=$1 ORDER BY item_idx, participant_id`, sid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Claim{}
	for rows.Next() {
		var c Claim
		if err := rows.Scan(&c.ItemIdx, &c.ParticipantID, &c.Weight); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
