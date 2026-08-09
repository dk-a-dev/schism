package store

import (
	"context"
	"errors"
	"slices"
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
	Phone  *string
}

// NormalizePhone keeps digits only and compares by the last 10 (mobile length), so
// "+91 95557-13188", "09555713188" and "9555713188" all normalize identically.
func NormalizePhone(raw string) string {
	digits := make([]rune, 0, len(raw))
	for _, r := range raw {
		if r >= '0' && r <= '9' {
			digits = append(digits, r)
		}
	}
	s := string(digits)
	if len(s) > 10 {
		s = s[len(s)-10:]
	}
	return s
}

func normalizePhonePtr(p *string) any {
	if p == nil {
		return nil
	}
	n := NormalizePhone(*p)
	if n == "" {
		return nil
	}
	return n
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
	return s.createGroup(ctx, in)
}

// CreateGroupForUser creates a group with exactly one participant linked to the
// authenticated creator. A matching participant supplied by the client is preferred;
// otherwise the first participant becomes the creator. All other user links are ignored.
func (s *Store) CreateGroupForUser(ctx context.Context, in GroupInput, userID string) (Group, error) {
	parts := append([]ParticipantInput(nil), in.Participants...)
	creator := 0
	for i, p := range parts {
		if p.UserID != nil && *p.UserID == userID {
			creator = i
			break
		}
	}
	for i := range parts {
		parts[i].UserID = nil
	}
	if len(parts) > 0 {
		parts[creator].UserID = &userID
	}
	in.Participants = parts
	return s.createGroup(ctx, in)
}

func (s *Store) createGroup(ctx context.Context, in GroupInput) (Group, error) {
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
			`INSERT INTO participants (id, group_id, name, user_id, phone) VALUES ($1,$2,$3,$4,$5)`,
			id.New(), gid, p.Name, nullifyPtr(p.UserID), normalizePhonePtr(p.Phone)); err != nil {
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

// AuthorizedGroupIDs returns only groups in which userID has a linked participant.
// When requested is non-empty its order is preserved and duplicates are removed.
func (s *Store) AuthorizedGroupIDs(ctx context.Context, userID string, requested []string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT DISTINCT group_id FROM participants WHERE user_id=$1 ORDER BY group_id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	membership := make(map[string]struct{})
	for rows.Next() {
		var groupID string
		if err := rows.Scan(&groupID); err != nil {
			return nil, err
		}
		membership[groupID] = struct{}{}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(requested) == 0 {
		out := make([]string, 0, len(membership))
		for groupID := range membership {
			out = append(out, groupID)
		}
		// The query is ordered, but maps are not. Re-querying is unnecessary; the
		// public API only needs stable output, so sort the small membership list.
		slices.Sort(out)
		return out, nil
	}
	out := make([]string, 0, len(requested))
	seen := make(map[string]struct{}, len(requested))
	for _, groupID := range requested {
		if _, duplicate := seen[groupID]; duplicate {
			continue
		}
		seen[groupID] = struct{}{}
		if _, ok := membership[groupID]; ok {
			out = append(out, groupID)
		}
	}
	return out, nil
}

// UpdateGroup updates group fields and reconciles participants: those with an ID are updated,
// those without are inserted, and existing participants absent from the input are deleted.
// ErrParticipantHasHistory is returned when a group update would remove a participant who appears
// on an expense. Callers surface it as 409: the request is well-formed, but honouring it would
// cascade-delete financial records rather than just edit the member list.
var ErrParticipantHasHistory = errors.New("participant has expense history")

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
				// COALESCE keeps an existing phone when an edit doesn't resend it.
				`UPDATE participants SET name=$2, phone=COALESCE($4, phone)
				 WHERE id=$1 AND group_id=$3`,
				*p.ID, p.Name, gid, normalizePhonePtr(p.Phone)); err != nil {
				return nil, err
			}
		} else {
			newID := id.New()
			keep[newID] = true
			if _, err := tx.Exec(ctx,
				`INSERT INTO participants (id, group_id, name, user_id, phone) VALUES ($1,$2,$3,NULL,$4)`,
				newID, gid, p.Name, normalizePhonePtr(p.Phone)); err != nil {
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
	// A participant with any financial history is never deleted here.
	//
	// expenses.paid_by_id and expense_paid_for.participant_id are both ON DELETE CASCADE, so
	// removing such a participant does not just drop them from the member list — it deletes every
	// expense they paid for outright, and silently rewrites the split (and therefore everyone
	// else's balance) on the expenses that survive.
	//
	// Two ways that fires, and the second is the more likely one:
	//   - any group member could aim it at any other member; the only previous guard was "you
	//     cannot remove yourself", so this was one PUT away from destroying someone else's ledger.
	//   - a client that fetched the group before a new member joined and then PUT its stale
	//     snapshot would remove that member by simple absence — no attacker required.
	//
	// Deleting a participant who has never appeared on an expense is still allowed: that is the
	// "added them by mistake" case, and it destroys nothing.
	for _, pid := range existing {
		if keep[pid] {
			continue
		}
		var referenced bool
		if err := tx.QueryRow(ctx, `
            SELECT EXISTS (
                SELECT 1 FROM expenses WHERE paid_by_id = $1
                UNION ALL
                SELECT 1 FROM expense_paid_for WHERE participant_id = $1
            )`, pid).Scan(&referenced); err != nil {
			return nil, err
		}
		if referenced {
			return nil, ErrParticipantHasHistory
		}
		if _, err := tx.Exec(ctx, `DELETE FROM participants WHERE id=$1`, pid); err != nil {
			return nil, err
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
