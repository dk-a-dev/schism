package store

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"time"

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

// UnclaimedResolution tells ComputeClaimSplit how to synthesize a weight for an item nobody claimed.
// Mode is one of "assign" (give it entirely to ParticipantID), "split" (split evenly across every
// participant), or "cover" (the creator eats it).
type UnclaimedResolution struct {
	ItemIdx       int
	Mode          string
	ParticipantID string
}

// ComputeClaimSplit is the pure, DB-free proportional-split math shared by FinalizeClaimSession and
// the API's live "owesPreview". It mirrors the Android buildItemizedExpenseRequest algorithm:
//
//  1. Per item, gather claimed weights (participantID -> weight). An item with no claims uses its
//     UnclaimedResolution (if any) to synthesize a weight of 1 for the relevant participant(s); an
//     item with neither claims nor a resolution contributes to nobody.
//  2. The item's amount splits proportionally to weight, with the last sharer (by participant id,
//     for determinism) absorbing the rounding remainder so the item's split is always exact.
//  3. The net charge pot (tax + fees - discount + roundoff) splits proportionally to each
//     participant's claimed subtotal, again with the last participant absorbing the remainder.
//
// Weights are scaled to integer "centi-weights" (NUMERIC(6,2) has 2 decimal places) so the split uses
// exact integer division throughout, matching money's int64-minor-units discipline.
func ComputeClaimSplit(items []ClaimItem, claims []Claim, tax, fees, discount, roundoff int64,
	resolutions []UnclaimedResolution, allParticipantIDs []string, creatorID string) map[string]int64 {

	claimsByItem := map[int]map[string]float64{}
	for _, c := range claims {
		if claimsByItem[c.ItemIdx] == nil {
			claimsByItem[c.ItemIdx] = map[string]float64{}
		}
		claimsByItem[c.ItemIdx][c.ParticipantID] += c.Weight
	}
	resByItem := map[int]UnclaimedResolution{}
	for _, r := range resolutions {
		resByItem[r.ItemIdx] = r
	}

	owed := map[string]int64{}

	for _, item := range items {
		weights := claimsByItem[item.Idx]
		hasClaims := false
		for _, w := range weights {
			if w > 0 {
				hasClaims = true
				break
			}
		}
		if !hasClaims {
			res, ok := resByItem[item.Idx]
			if !ok {
				continue // nobody claimed it and nothing resolved it
			}
			weights = map[string]float64{}
			switch res.Mode {
			case "assign":
				if res.ParticipantID != "" {
					weights[res.ParticipantID] = 1
				}
			case "split":
				for _, pid := range allParticipantIDs {
					weights[pid] = 1
				}
			case "cover":
				if creatorID != "" {
					weights[creatorID] = 1
				}
			}
		}

		scaled := map[string]int64{}
		var total int64
		for pid, w := range weights {
			if w <= 0 {
				continue
			}
			cw := int64(math.Round(w * 100))
			if cw <= 0 {
				continue
			}
			scaled[pid] = cw
			total += cw
		}
		if total <= 0 {
			continue
		}
		pids := make([]string, 0, len(scaled))
		for pid := range scaled {
			pids = append(pids, pid)
		}
		sort.Strings(pids)

		var distributed int64
		for i, pid := range pids {
			var part int64
			if i == len(pids)-1 {
				part = item.AmountMinor - distributed
			} else {
				part = item.AmountMinor * scaled[pid] / total
			}
			distributed += part
			owed[pid] += part
		}
	}

	chargePot := tax + fees - discount + roundoff
	var assignedSubtotal int64
	for _, v := range owed {
		assignedSubtotal += v
	}
	if chargePot != 0 && assignedSubtotal > 0 {
		pids := make([]string, 0, len(owed))
		for pid := range owed {
			pids = append(pids, pid)
		}
		sort.Strings(pids)

		remaining := chargePot
		for i, pid := range pids {
			var share int64
			if i == len(pids)-1 {
				share = remaining
			} else {
				share = chargePot * owed[pid] / assignedSubtotal
			}
			remaining -= share
			owed[pid] += share
		}
	}

	return owed
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

// UpsertClaims replaces one participant's claim rows on a session: inside a row-locked tx, the
// session's status/version are checked (ErrClaimLocked if not open, ErrClaimStale if the caller's
// expectedVersion is behind), then the participant's existing rows are deleted and replaced with one
// row per weight > 0 (a weight of 0 means "no claim").
func (s *Store) UpsertClaims(ctx context.Context, sid, participantID string, expectedVersion int, weights map[int]float64) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var status string
	var version int
	err = tx.QueryRow(ctx, `SELECT status, version FROM claim_sessions WHERE id=$1 FOR UPDATE`, sid).
		Scan(&status, &version)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrClaimLocked
	}
	if err != nil {
		return err
	}
	if status != "open" {
		return ErrClaimLocked
	}
	if version != expectedVersion {
		return ErrClaimStale
	}

	if _, err := tx.Exec(ctx, `DELETE FROM claims WHERE session_id=$1 AND participant_id=$2`, sid, participantID); err != nil {
		return err
	}
	for itemIdx, weight := range weights {
		if weight <= 0 {
			continue
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO claims (session_id, item_idx, participant_id, weight) VALUES ($1,$2,$3,$4)`,
			sid, itemIdx, participantID, weight); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// EditItems replaces a session's items (creator-only edit), dropping claims on any item whose
// idx/name/qty/amount changed or that was removed entirely (a stale claim on a re-priced item would
// otherwise silently misrepresent what the person agreed to), and bumps the version so pollers refetch.
// Returns ErrClaimLocked if the session isn't open. Returns the new version.
func (s *Store) EditItems(ctx context.Context, sid string, items []ClaimItem) (int, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	var status string
	var version int
	var oldItemsJSON []byte
	err = tx.QueryRow(ctx, `SELECT status, version, items FROM claim_sessions WHERE id=$1 FOR UPDATE`, sid).
		Scan(&status, &version, &oldItemsJSON)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrClaimLocked
	}
	if err != nil {
		return 0, err
	}
	if status != "open" {
		return 0, ErrClaimLocked
	}

	var oldItems []ClaimItem
	if err := json.Unmarshal(oldItemsJSON, &oldItems); err != nil {
		return 0, err
	}
	oldByIdx := make(map[int]ClaimItem, len(oldItems))
	for _, it := range oldItems {
		oldByIdx[it.Idx] = it
	}
	newByIdx := make(map[int]ClaimItem, len(items))
	for _, it := range items {
		newByIdx[it.Idx] = it
	}

	var changed []int
	for idx, old := range oldByIdx {
		nw, stillPresent := newByIdx[idx]
		if !stillPresent || nw != old {
			changed = append(changed, idx)
		}
	}

	if len(changed) > 0 {
		if _, err := tx.Exec(ctx, `DELETE FROM claims WHERE session_id=$1 AND item_idx = ANY($2)`, sid, changed); err != nil {
			return 0, err
		}
	}

	newItemsJSON, err := json.Marshal(items)
	if err != nil {
		return 0, err
	}
	newVersion := version + 1
	if _, err := tx.Exec(ctx, `UPDATE claim_sessions SET items=$2, version=$3 WHERE id=$1`, sid, newItemsJSON, newVersion); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return newVersion, nil
}

// FinalizeClaimSession locks a session into a group expense. Inside a row-locked tx: if the session
// is already finalized it returns the existing expense id (idempotent); if cancelled it errors; if
// the caller's expectedVersion is behind it returns ErrClaimStale. Otherwise it loads the claims and
// group participants, runs ComputeClaimSplit, builds a BY_AMOUNT expense (paidBy = creator, paidFor =
// each owed participant, amount = sum), inserts it via insertExpenseTx, marks the session finalized,
// and returns the new expense id.
func (s *Store) FinalizeClaimSession(ctx context.Context, sid string, expectedVersion int, resolutions []UnclaimedResolution) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	var status, groupID, creatorID, title string
	var version int
	var tax, fees, discount, roundoff int64
	var itemsJSON []byte
	var existingExpenseID *string
	err = tx.QueryRow(ctx,
		`SELECT status, group_id, creator_participant_id, title, items,
		        tax_minor, fees_minor, discount_minor, roundoff_minor, version, expense_id
		 FROM claim_sessions WHERE id=$1 FOR UPDATE`, sid).
		Scan(&status, &groupID, &creatorID, &title, &itemsJSON,
			&tax, &fees, &discount, &roundoff, &version, &existingExpenseID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrClaimLocked
	}
	if err != nil {
		return "", err
	}

	if status == "finalized" {
		if existingExpenseID != nil {
			return *existingExpenseID, nil
		}
		return "", errors.New("finalized session missing expense id")
	}
	if status == "cancelled" {
		return "", ErrClaimLocked
	}
	if version != expectedVersion {
		return "", ErrClaimStale
	}

	var items []ClaimItem
	if err := json.Unmarshal(itemsJSON, &items); err != nil {
		return "", err
	}

	// Load this session's claims inside the tx (consistent with the FOR UPDATE lock).
	rows, err := tx.Query(ctx,
		`SELECT item_idx, participant_id, weight FROM claims WHERE session_id=$1 ORDER BY item_idx, participant_id`, sid)
	if err != nil {
		return "", err
	}
	var claims []Claim
	for rows.Next() {
		var c Claim
		if err := rows.Scan(&c.ItemIdx, &c.ParticipantID, &c.Weight); err != nil {
			rows.Close()
			return "", err
		}
		claims = append(claims, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return "", err
	}

	// Load group participants (for split/cover resolutions over "everyone").
	pRows, err := tx.Query(ctx, `SELECT id FROM participants WHERE group_id=$1 ORDER BY id`, groupID)
	if err != nil {
		return "", err
	}
	var allParticipantIDs []string
	for pRows.Next() {
		var pid string
		if err := pRows.Scan(&pid); err != nil {
			pRows.Close()
			return "", err
		}
		allParticipantIDs = append(allParticipantIDs, pid)
	}
	pRows.Close()
	if err := pRows.Err(); err != nil {
		return "", err
	}

	owed := ComputeClaimSplit(items, claims, tax, fees, discount, roundoff, resolutions, allParticipantIDs, creatorID)

	pids := make([]string, 0, len(owed))
	for pid := range owed {
		pids = append(pids, pid)
	}
	sort.Strings(pids)
	var amount int64
	paidFor := make([]PaidForInput, 0, len(pids))
	for _, pid := range pids {
		share := owed[pid]
		if share == 0 {
			continue
		}
		amount += share
		paidFor = append(paidFor, PaidForInput{ParticipantID: pid, Shares: share})
	}

	expTitle := title
	if expTitle == "" {
		expTitle = "Claimed bill"
	}
	eid, err := s.insertExpenseTx(ctx, tx, groupID, ExpenseInput{
		Title:       expTitle,
		Amount:      amount,
		ExpenseDate: time.Now(),
		PaidByID:    creatorID,
		SplitMode:   "BY_AMOUNT",
		AddedBy:     creatorID,
		PaidFor:     paidFor,
	})
	if err != nil {
		return "", err
	}

	if _, err := tx.Exec(ctx,
		`UPDATE claim_sessions SET status='finalized', expense_id=$2 WHERE id=$1`, sid, eid); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return eid, nil
}

// CancelClaimSession marks an open session cancelled (a no-op if it isn't open).
func (s *Store) CancelClaimSession(ctx context.Context, sid string) error {
	_, err := s.pool.Exec(ctx, `UPDATE claim_sessions SET status='cancelled' WHERE id=$1 AND status='open'`, sid)
	return err
}

// ParticipantForUserInGroup returns the participant id linked to userID within groupID, or "" when no
// participant in that group is linked to the user. Used for auth: a caller may act only as their own
// participant.
func (s *Store) ParticipantForUserInGroup(ctx context.Context, userID, groupID string) (string, error) {
	var pid string
	err := s.pool.QueryRow(ctx,
		`SELECT id FROM participants WHERE group_id=$1 AND user_id=$2 LIMIT 1`, groupID, userID).Scan(&pid)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return pid, nil
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
