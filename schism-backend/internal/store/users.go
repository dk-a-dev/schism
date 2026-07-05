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

// CreateUser registers a device owner's (unverified) identity and mints a secret bearer token. Email
// is deliberately NOT treated as a unique/lookup key — without verification, upserting by email would
// let anyone claim or overwrite another person's account — so every call inserts a distinct user.
// The raw token is returned ONCE (never stored); only its hash is persisted.
func (s *Store) CreateUser(ctx context.Context, name, email, phone string) (User, string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return User{}, "", err
	}
	raw := base64.RawURLEncoding.EncodeToString(b)

	var u User
	err := s.pool.QueryRow(ctx,
		`INSERT INTO users (id, name, email, phone, token_hash) VALUES ($1,$2,$3,$4,$5)
		 RETURNING id, name, email, phone, created_at`,
		id.New(), name, email, phone, TokenHash(raw)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if err != nil {
		return User{}, "", err
	}
	return u, raw, nil
}

// UserByToken resolves a raw bearer token to its user, or (nil, nil) if no user matches.
func (s *Store) UserByToken(ctx context.Context, rawToken string) (*User, error) {
	var u User
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, email, phone, created_at FROM users
		 WHERE token_hash=$1 AND token_hash <> ''`, TokenHash(rawToken)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}
