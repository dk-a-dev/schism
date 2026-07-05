package store

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/schism/schism-backend/internal/id"
	"golang.org/x/crypto/bcrypt"
)

var (
	// ErrEmailTaken is returned when registering an email that already has a password account.
	ErrEmailTaken = errors.New("email already registered")
	// ErrInvalidLogin is returned for a wrong email/password (kept vague to avoid user enumeration).
	ErrInvalidLogin = errors.New("invalid email or password")
)

func newToken() (raw, hash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", err
	}
	raw = base64.RawURLEncoding.EncodeToString(b)
	return raw, TokenHash(raw), nil
}

// RegisterUser creates a password account and mints a bearer token. Email must be unique among
// password accounts. The raw token is returned once; only its hash is stored.
func (s *Store) RegisterUser(ctx context.Context, name, email, password string) (User, string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return User{}, "", err
	}
	raw, tokenHash, err := newToken()
	if err != nil {
		return User{}, "", err
	}
	var u User
	err = s.pool.QueryRow(ctx,
		`INSERT INTO users (id, name, email, phone, token_hash, password_hash)
		 VALUES ($1,$2,$3,'',$4,$5)
		 RETURNING id, name, email, phone, created_at`,
		id.New(), name, strings.TrimSpace(email), tokenHash, string(hash)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return User{}, "", ErrEmailTaken
		}
		return User{}, "", err
	}
	return u, raw, nil
}

// LoginUser verifies credentials and mints a fresh session token.
func (s *Store) LoginUser(ctx context.Context, email, password string) (User, string, error) {
	var u User
	var passHash string
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, email, phone, created_at, password_hash FROM users
		 WHERE lower(email) = lower($1) AND password_hash <> '' LIMIT 1`, strings.TrimSpace(email)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt, &passHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, "", ErrInvalidLogin
	}
	if err != nil {
		return User{}, "", err
	}
	if bcrypt.CompareHashAndPassword([]byte(passHash), []byte(password)) != nil {
		return User{}, "", ErrInvalidLogin
	}
	raw, tokenHash, err := newToken()
	if err != nil {
		return User{}, "", err
	}
	if _, err := s.pool.Exec(ctx, `UPDATE users SET token_hash = $2 WHERE id = $1`, u.ID, tokenHash); err != nil {
		return User{}, "", err
	}
	return u, raw, nil
}
