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
// password accounts. The raw token is returned once; only its hash is stored (both on the legacy
// users.token_hash column and as this session's row in tokens). When a phone is provided,
// participants added earlier by friends under that number are claimed for this account, so their
// groups show up the moment the user joins the platform.
func (s *Store) RegisterUser(ctx context.Context, name, email, password, phone string) (User, string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return User{}, "", err
	}
	raw, tokenHash, err := newToken()
	if err != nil {
		return User{}, "", err
	}
	normPhone := NormalizePhone(phone)

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return User{}, "", err
	}
	defer tx.Rollback(ctx)

	var u User
	err = tx.QueryRow(ctx,
		`INSERT INTO users (id, name, email, phone, token_hash, password_hash)
		 VALUES ($1,$2,$3,$4,$5,$6)
		 RETURNING id, name, email, phone, created_at`,
		id.New(), name, strings.TrimSpace(email), normPhone, tokenHash, string(hash)).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return User{}, "", ErrEmailTaken
		}
		return User{}, "", err
	}
	if err := s.insertToken(ctx, tx, u.ID, tokenHash); err != nil {
		return User{}, "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return User{}, "", err
	}
	_ = s.ClaimParticipantsByPhone(ctx, u.ID, normPhone)
	return u, raw, nil
}

// ClaimParticipantsByPhone links unclaimed participants carrying [phone] to [userID].
func (s *Store) ClaimParticipantsByPhone(ctx context.Context, userID, phone string) error {
	if phone == "" {
		return nil
	}
	_, err := s.pool.Exec(ctx,
		`UPDATE participants SET user_id = $1 WHERE phone = $2 AND user_id IS NULL`, userID, phone)
	return err
}

// GroupIDsForUser lists the ids of every group where a participant is linked to [userID].
func (s *Store) GroupIDsForUser(ctx context.Context, userID string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT DISTINCT group_id FROM participants WHERE user_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var gid string
		if err := rows.Scan(&gid); err != nil {
			return nil, err
		}
		out = append(out, gid)
	}
	return out, rows.Err()
}

// LoginUser verifies credentials and mints a fresh session token. This ADDS a new session (a tokens
// row) rather than rotating the account's single token, so existing sessions on other devices stay
// valid — logging in here no longer signs anyone else out.
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
	if err := s.insertToken(ctx, s.pool, u.ID, tokenHash); err != nil {
		return User{}, "", err
	}
	// Claim any participants friends added under this phone since the last session.
	_ = s.ClaimParticipantsByPhone(ctx, u.ID, NormalizePhone(u.Phone))
	return u, raw, nil
}
