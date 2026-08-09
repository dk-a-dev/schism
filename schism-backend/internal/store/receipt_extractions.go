package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ReceiptExtractWindow is how long a user must wait between cloud receipt extractions in one group.
const ReceiptExtractWindow = time.Hour

// ClaimReceiptExtraction reserves the caller's extraction slot for a group. It returns granted=true
// when the call may proceed, or granted=false with the instant the next one becomes available.
//
// The conditional upsert is the whole limiter: the WHERE on DO UPDATE means a row is only rewritten
// once the window has elapsed, and Postgres serialises concurrent writers on the primary key — so
// two replicas racing the same user cannot both be granted.
//
// ponytail: the slot is spent when the call is made, not when it succeeds — a provider failure still
// burned an upstream request, and the limit exists to bound spend. If failed extractions turn out to
// strand users, refund the slot on ErrUnavailable only.
func (s *Store) ClaimReceiptExtraction(ctx context.Context, userID, groupID string, now time.Time) (time.Time, bool, error) {
	now = now.UTC()
	var granted time.Time
	err := s.pool.QueryRow(ctx,
		`INSERT INTO receipt_extractions (user_id, group_id, last_extract_at) VALUES ($1,$2,$3)
		 ON CONFLICT (user_id, group_id) DO UPDATE SET last_extract_at = EXCLUDED.last_extract_at
		   WHERE receipt_extractions.last_extract_at <= $4
		 RETURNING last_extract_at`,
		userID, groupID, now, now.Add(-ReceiptExtractWindow)).Scan(&granted)
	if err == nil {
		return time.Time{}, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, false, err
	}
	// Refused: report when the existing reservation expires.
	var last time.Time
	if err := s.pool.QueryRow(ctx,
		`SELECT last_extract_at FROM receipt_extractions WHERE user_id=$1 AND group_id=$2`,
		userID, groupID).Scan(&last); err != nil {
		return time.Time{}, false, err
	}
	return last.UTC().Add(ReceiptExtractWindow), false, nil
}
