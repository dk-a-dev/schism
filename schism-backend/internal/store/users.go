package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/schism/schism-backend/internal/id"
)

type User struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Phone     string    `json:"phone"`
	CreatedAt time.Time `json:"createdAt"`
}

// TokenHash returns the sha256 hex of a raw bearer token. The store only ever persists this hash so a
// leaked database can't be used to impersonate anyone; the raw token lives only on the device.
func TokenHash(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

// dbExecer is satisfied by both *pgxpool.Pool and pgx.Tx, letting insertToken run standalone (a
// fresh login) or as part of the caller's transaction (the very first token, alongside the new user
// row) without duplicating the insert.
type dbExecer interface {
	Exec(ctx context.Context, sql string, arguments ...any) (pgconn.CommandTag, error)
}

// insertToken records a new session — a bearer token's hash — for userID. Each call adds a row
// rather than replacing one, so a user can hold many concurrent sessions (one per device).
func (s *Store) insertToken(ctx context.Context, db dbExecer, userID, tokenHash string) error {
	_, err := db.Exec(ctx,
		`INSERT INTO tokens (id, user_id, token_hash) VALUES ($1,$2,$3)`, id.New(), userID, tokenHash)
	return err
}

// CreateUser registers a device owner's (unverified) identity and mints a secret bearer token. Email
// is deliberately NOT treated as a unique/lookup key — without verification, upserting by email would
// let anyone claim or overwrite another person's account — so every call inserts a distinct user.
// The raw token is returned ONCE (never stored); only its hash is persisted, both on the legacy
// users.token_hash column and as this session's row in tokens (the table auth actually reads from).
func (s *Store) CreateUser(ctx context.Context, name, email, phone string) (User, string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return User{}, "", err
	}
	raw := base64.RawURLEncoding.EncodeToString(b)
	tokenHash := TokenHash(raw)

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return User{}, "", err
	}
	defer tx.Rollback(ctx)

	var u User
	err = tx.QueryRow(ctx,
		`INSERT INTO users (id, name, email, phone, token_hash) VALUES ($1,$2,$3,$4,$5)
		 RETURNING id, name, email, phone, created_at`,
		id.New(), name, email, phone, tokenHash).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if err != nil {
		return User{}, "", err
	}
	if err := s.insertToken(ctx, tx, u.ID, tokenHash); err != nil {
		return User{}, "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return User{}, "", err
	}
	return u, raw, nil
}

// UserByToken resolves a raw bearer token to its user via the tokens table (one row per session), or
// (nil, nil) if no session matches.
func (s *Store) UserByToken(ctx context.Context, rawToken string) (*User, error) {
	var u User
	err := s.pool.QueryRow(ctx,
		`SELECT u.id, u.name, u.email, u.phone, u.created_at FROM users u
		 JOIN tokens t ON t.user_id = u.id WHERE t.token_hash=$1`, TokenHash(rawToken)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// DeleteToken ends a single session (logout): only the session matching rawToken is removed, so
// other devices/logins for the same user stay signed in.
func (s *Store) DeleteToken(ctx context.Context, rawToken string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM tokens WHERE token_hash=$1`, TokenHash(rawToken))
	return err
}

// DeleteUser removes the account, unlinking their participants first (their group history stays).
func (s *Store) DeleteUser(ctx context.Context, userID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE participants SET user_id = NULL WHERE user_id = $1`, userID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
