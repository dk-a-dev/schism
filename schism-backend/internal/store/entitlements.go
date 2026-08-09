package store

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
)

// FreeLiveSplitsPerMonth is how many hosted Live Splits a free account may create per UTC calendar
// month. Joining and every operation on an existing session stay free and ungated.
const FreeLiveSplitsPerMonth = 3

// ErrPurchaseOwnedByAnotherUser is returned when a purchase token already belongs to a different
// Schism account — a replay attempt. The token itself is never included in the error.
var ErrPurchaseOwnedByAnotherUser = errors.New("purchase already linked to another account")

// ErrBillingKeyMissing is returned when purchase persistence is attempted without BILLING_TOKEN_KEY.
var ErrBillingKeyMissing = errors.New("billing token key not configured")

// Entitlement is the server's answer to "is this account Plus right now". It is derived only from
// verified purchase records, never from anything a client sent.
type Entitlement struct {
	Active       bool      `json:"active"`
	ProductID    string    `json:"productId"`
	ExpiresAt    time.Time `json:"expiresAt"`
	AutoRenewing bool      `json:"autoRenewing"`
}

// PlusRequired describes a refused free-allowance consumption: how much of the free monthly quota was
// already used and when it resets.
type PlusRequired struct {
	Used     int       `json:"used"`
	Limit    int       `json:"limit"`
	ResetsAt time.Time `json:"resetsAt"`
}

// PurchaseRecord is one server-verified Play purchase, as persisted.
type PurchaseRecord struct {
	ID           string
	UserID       string
	ProductID    string
	State        string
	ExpiresAt    time.Time
	AutoRenewing bool
	Acknowledged bool
	VerifiedAt   time.Time
}

// monthStart truncates to the first day of now's UTC calendar month; ResetsAt is the next one.
func monthStart(now time.Time) time.Time {
	utc := now.UTC()
	return time.Date(utc.Year(), utc.Month(), 1, 0, 0, 0, 0, time.UTC)
}

// sealToken encrypts a raw Play purchase token with AES-256-GCM, returning nonce||ciphertext. The
// plaintext token never reaches the database, a log line, or an API response.
func sealToken(key []byte, token string) ([]byte, error) {
	gcm, err := tokenGCM(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return gcm.Seal(nonce, nonce, []byte(token), nil), nil
}

// openToken reverses sealToken.
func openToken(key []byte, blob []byte) (string, error) {
	gcm, err := tokenGCM(key)
	if err != nil {
		return "", err
	}
	if len(blob) < gcm.NonceSize() {
		return "", errors.New("purchase token ciphertext truncated")
	}
	plain, err := gcm.Open(nil, blob[:gcm.NonceSize()], blob[gcm.NonceSize():], nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

func tokenGCM(key []byte) (cipher.AEAD, error) {
	if len(key) != 32 {
		return nil, ErrBillingKeyMissing
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// EntitlementStatus reports whether the user holds an active Plus subscription at now. A purchase
// counts only while its state is "PURCHASED" and its expiry is still in the future, so a refund or a
// lapsed renewal drops the account back to free without any extra bookkeeping.
func (s *Store) EntitlementStatus(ctx context.Context, userID string, now time.Time) (Entitlement, error) {
	return entitlementStatus(ctx, s.pool, userID, now)
}

// dbQuerier is satisfied by both *pgxpool.Pool and pgx.Tx so the entitlement read can run standalone
// or inside the create-session transaction.
type dbQuerier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func entitlementStatus(ctx context.Context, db dbQuerier, userID string, now time.Time) (Entitlement, error) {
	var e Entitlement
	err := db.QueryRow(ctx,
		`SELECT product_id, expires_at, auto_renewing FROM purchases
		 WHERE user_id=$1 AND state='PURCHASED' AND expires_at > $2
		 ORDER BY expires_at DESC LIMIT 1`, userID, now.UTC()).
		Scan(&e.ProductID, &e.ExpiresAt, &e.AutoRenewing)
	if errors.Is(err, pgx.ErrNoRows) {
		return Entitlement{}, nil
	}
	if err != nil {
		return Entitlement{}, err
	}
	e.Active = true
	return e, nil
}

// UpsertPurchase records (or refreshes) a server-verified purchase. The raw token is stored only as
// AES-256-GCM ciphertext plus its sha256 hash; the hash is what detects the same Play token being
// replayed against a second Schism account.
func (s *Store) UpsertPurchase(ctx context.Context, key []byte, userID, rawToken string, in PurchaseRecord) (PurchaseRecord, error) {
	ciphertext, err := sealToken(key, rawToken)
	if err != nil {
		return PurchaseRecord{}, err
	}
	hash := TokenHash(rawToken)

	var owner string
	err = s.pool.QueryRow(ctx, `SELECT user_id FROM purchases WHERE token_hash=$1`, hash).Scan(&owner)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return PurchaseRecord{}, err
	}
	if err == nil && owner != userID {
		return PurchaseRecord{}, ErrPurchaseOwnedByAnotherUser
	}

	out := in
	out.UserID = userID
	err = s.pool.QueryRow(ctx,
		`INSERT INTO purchases (id, user_id, product_id, token_hash, token_ciphertext, state,
		                        expires_at, auto_renewing, acknowledged)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
		 ON CONFLICT (token_hash) DO UPDATE SET
		   product_id=EXCLUDED.product_id, token_ciphertext=EXCLUDED.token_ciphertext,
		   state=EXCLUDED.state, expires_at=EXCLUDED.expires_at,
		   auto_renewing=EXCLUDED.auto_renewing, acknowledged=EXCLUDED.acknowledged,
		   verified_at=now()
		 RETURNING id, verified_at`,
		id.New(), userID, in.ProductID, hash, ciphertext, in.State, in.ExpiresAt.UTC(),
		in.AutoRenewing, in.Acknowledged).
		Scan(&out.ID, &out.VerifiedAt)
	if err != nil {
		return PurchaseRecord{}, err
	}
	return out, nil
}

// MarkAcknowledged flips a stored purchase to acknowledged after Play confirmed it.
func (s *Store) MarkAcknowledged(ctx context.Context, recordID string) error {
	_, err := s.pool.Exec(ctx, `UPDATE purchases SET acknowledged=true WHERE id=$1`, recordID)
	return err
}

// StalePurchases lists the user's purchase records whose Play state should be re-checked: verified
// more than staleAfter ago, or already past their recorded expiry. Tokens come back decrypted, for
// immediate use against the Play API only.
//
// A negative staleAfter pushes the cutoff into the future, i.e. selects every re-checkable record —
// that is what an explicit "restore purchases" asks for. Terminal states (expired, refunded) are
// never re-checked: a resubscribe arrives as a fresh token.
func (s *Store) StalePurchases(ctx context.Context, key []byte, userID string, now time.Time, staleAfter time.Duration) ([]PurchaseRecord, []string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, product_id, token_ciphertext, state, expires_at, auto_renewing, acknowledged, verified_at
		 FROM purchases
		 WHERE user_id=$1 AND state IN ('PURCHASED','PENDING')
		   AND (verified_at < $2 OR expires_at <= $3)
		 ORDER BY verified_at`, userID, now.UTC().Add(-staleAfter), now.UTC())
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	var records []PurchaseRecord
	var tokens []string
	for rows.Next() {
		var rec PurchaseRecord
		var blob []byte
		if err := rows.Scan(&rec.ID, &rec.ProductID, &blob, &rec.State, &rec.ExpiresAt,
			&rec.AutoRenewing, &rec.Acknowledged, &rec.VerifiedAt); err != nil {
			return nil, nil, err
		}
		token, err := openToken(key, blob)
		if err != nil {
			return nil, nil, err
		}
		rec.UserID = userID
		records = append(records, rec)
		tokens = append(tokens, token)
	}
	return records, tokens, rows.Err()
}

// LiveSplitUsage reports how many free hosted Live Splits the user has consumed in now's UTC month.
func (s *Store) LiveSplitUsage(ctx context.Context, userID string, now time.Time) (PlusRequired, error) {
	start := monthStart(now)
	used := 0
	err := s.pool.QueryRow(ctx,
		`SELECT used FROM live_split_usage WHERE user_id=$1 AND month_start=$2`, userID, start).Scan(&used)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return PlusRequired{}, err
	}
	return PlusRequired{Used: used, Limit: FreeLiveSplitsPerMonth, ResetsAt: start.AddDate(0, 1, 0)}, nil
}

// CreateClaimSessionGated creates a claim session and consumes one free monthly Live Split allowance
// in a single transaction, so a refused create never leaves a session behind and a granted one never
// leaves the counter behind. Plus accounts skip the counter entirely.
//
// A repeat call with the same idempotencyKey returns the session the first call created and consumes
// nothing. When the free allowance is exhausted it returns a non-nil *PlusRequired and no session.
//
// ponytail: the (user, month) usage row is locked first, which serialises one user's concurrent
// creates. That is what makes "never a fourth free split" and idempotency both hold; move to an
// advisory lock only if a single account's create throughput ever matters.
func (s *Store) CreateClaimSessionGated(ctx context.Context, in ClaimSessionInput, userID, idempotencyKey string, now time.Time) (*ClaimSession, *PlusRequired, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback(ctx)

	start := monthStart(now)
	if _, err := tx.Exec(ctx,
		`INSERT INTO live_split_usage (user_id, month_start, used) VALUES ($1,$2,0)
		 ON CONFLICT (user_id, month_start) DO NOTHING`, userID, start); err != nil {
		return nil, nil, err
	}
	var used int
	if err := tx.QueryRow(ctx,
		`SELECT used FROM live_split_usage WHERE user_id=$1 AND month_start=$2 FOR UPDATE`,
		userID, start).Scan(&used); err != nil {
		return nil, nil, err
	}

	var priorSession string
	err = tx.QueryRow(ctx,
		`SELECT session_id FROM live_split_grants WHERE user_id=$1 AND idempotency_key=$2`,
		userID, idempotencyKey).Scan(&priorSession)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, err
	}
	if err == nil {
		if err := tx.Commit(ctx); err != nil {
			return nil, nil, err
		}
		cs, err := s.GetClaimSession(ctx, priorSession)
		return cs, nil, err
	}

	entitlement, err := entitlementStatus(ctx, tx, userID, now)
	if err != nil {
		return nil, nil, err
	}
	if !entitlement.Active {
		if used >= FreeLiveSplitsPerMonth {
			return nil, &PlusRequired{
				Used: used, Limit: FreeLiveSplitsPerMonth, ResetsAt: start.AddDate(0, 1, 0),
			}, nil
		}
		if _, err := tx.Exec(ctx,
			`UPDATE live_split_usage SET used=used+1 WHERE user_id=$1 AND month_start=$2`,
			userID, start); err != nil {
			return nil, nil, err
		}
	}

	sid, err := insertClaimSession(ctx, tx, in)
	if err != nil {
		return nil, nil, err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO live_split_grants (user_id, idempotency_key, session_id) VALUES ($1,$2,$3)`,
		userID, idempotencyKey, sid); err != nil {
		return nil, nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	cs, err := s.GetClaimSession(ctx, sid)
	return cs, nil, err
}

// claimSessionRow marshals a ClaimSessionInput into the column values shared by the gated and
// ungated create paths. Declared here (rather than duplicating the INSERT) so the two paths can never
// drift apart.
func claimSessionRow(in ClaimSessionInput) (itemsJSON, taxesJSON []byte, taxMinor int64, err error) {
	items := in.Items
	if items == nil {
		items = []ClaimItem{}
	}
	if itemsJSON, err = json.Marshal(items); err != nil {
		return nil, nil, 0, err
	}
	taxes := in.Taxes
	if taxes == nil {
		taxes = []TaxLine{}
	}
	// The labelled breakdown, when given, is the source of truth for the scalar tax_minor: this keeps
	// ComputeClaimSplit's math (which only ever reads the scalar) unchanged whether or not the caller
	// sent a breakdown. Behaves exactly as today when taxes is empty.
	taxMinor = in.TaxMinor
	if len(taxes) > 0 {
		taxMinor = 0
		for _, t := range taxes {
			taxMinor += t.AmountMinor
		}
	}
	if taxesJSON, err = json.Marshal(taxes); err != nil {
		return nil, nil, 0, err
	}
	return itemsJSON, taxesJSON, taxMinor, nil
}

func insertClaimSession(ctx context.Context, db dbExecer, in ClaimSessionInput) (string, error) {
	itemsJSON, taxesJSON, taxMinor, err := claimSessionRow(in)
	if err != nil {
		return "", err
	}
	sid := id.New()
	_, err = db.Exec(ctx,
		`INSERT INTO claim_sessions (id, group_id, creator_participant_id, title, currency, items,
		                              tax_minor, fees_minor, discount_minor, roundoff_minor, taxes)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
		sid, in.GroupID, in.CreatorParticipantID, in.Title, in.Currency, itemsJSON,
		taxMinor, in.FeesMinor, in.DiscountMinor, in.RoundoffMinor, taxesJSON)
	if err != nil {
		return "", err
	}
	return sid, nil
}
