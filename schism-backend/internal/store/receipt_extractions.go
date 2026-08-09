package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	// ReceiptExtractWindow is the length of one rate-limit window.
	ReceiptExtractWindow = time.Hour
	// ReceiptExtractLimit is how many cloud extractions one user may spend inside a window.
	ReceiptExtractLimit = 2
)

// ClaimReceiptExtraction reserves one of the caller's cloud-extraction slots. It returns
// granted=true when the call may proceed, or granted=false with the instant the current window
// expires.
//
// The limit is per USER, not per (user, group). Group scope looked tighter but bounded nothing:
// creating a group is unlimited, so a single account could loop create-group -> extract and spend
// our provider keys without any ceiling. The cost lands on us, so the ceiling belongs on the payer.
//
// The whole limiter is one statement. Postgres serialises concurrent writers on the primary key,
// so replicas racing the same user cannot both be granted: the WHERE on DO UPDATE admits a writer
// only when the window has rolled over or a slot is genuinely free, and a refused claim updates
// nothing and returns no row.
//
// ponytail: fixed window, not sliding. A user can spend 2 late in one window and 2 early in the
// next, so the true worst case is 4 in quick succession — bounded, cheap, and worth one row per
// user instead of one row per call plus a reaper.
//
// ponytail: the slot is spent when the call is made, not when it succeeds — a provider failure
// still burned an upstream request, and the limit exists to bound spend. If failed extractions
// strand users in practice, refund the slot on ErrUnavailable only.
func (s *Store) ClaimReceiptExtraction(ctx context.Context, userID string, now time.Time) (time.Time, bool, error) {
	now = now.UTC()
	windowFloor := now.Add(-ReceiptExtractWindow)

	var windowStart time.Time
	err := s.pool.QueryRow(ctx,
		`INSERT INTO receipt_extractions (user_id, window_started_at, used)
		 VALUES ($1, $2, 1)
		 ON CONFLICT (user_id) DO UPDATE
		   SET window_started_at = CASE
		         WHEN receipt_extractions.window_started_at <= $3 THEN $2
		         ELSE receipt_extractions.window_started_at END,
		       used = CASE
		         WHEN receipt_extractions.window_started_at <= $3 THEN 1
		         ELSE receipt_extractions.used + 1 END
		   WHERE receipt_extractions.window_started_at <= $3
		      OR receipt_extractions.used < $4
		 RETURNING window_started_at`,
		userID, now, windowFloor, ReceiptExtractLimit).Scan(&windowStart)
	if err == nil {
		return time.Time{}, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, false, err
	}

	// Refused: the window is live and every slot in it is spent. Report when it rolls over.
	var current time.Time
	if err := s.pool.QueryRow(ctx,
		`SELECT window_started_at FROM receipt_extractions WHERE user_id=$1`, userID).Scan(&current); err != nil {
		return time.Time{}, false, err
	}
	return current.UTC().Add(ReceiptExtractWindow), false, nil
}
