package store

import (
	"context"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

// TestLoginDoesNotInvalidateOtherSessions is the core multi-session assertion for the tokens-table
// cutover: logging in again (a second device) must NOT kick out the first session.
func TestLoginDoesNotInvalidateOtherSessions(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	email := "multisession-" + id.New() + "@example.com"
	u, registerToken, err := s.RegisterUser(ctx, "Multi", email, "password1", "")
	require.NoError(t, err)

	got, err := s.UserByToken(ctx, registerToken)
	require.NoError(t, err)
	require.NotNil(t, got)
	require.Equal(t, u.ID, got.ID)

	// Login again (e.g. a second device) — must not invalidate the first token.
	_, loginToken, err := s.LoginUser(ctx, email, "password1")
	require.NoError(t, err)
	require.NotEqual(t, registerToken, loginToken)

	// BOTH tokens still authenticate.
	stillGood, err := s.UserByToken(ctx, registerToken)
	require.NoError(t, err)
	require.NotNil(t, stillGood, "original session should still be valid after a new login")
	require.Equal(t, u.ID, stillGood.ID)

	newGood, err := s.UserByToken(ctx, loginToken)
	require.NoError(t, err)
	require.NotNil(t, newGood)
	require.Equal(t, u.ID, newGood.ID)
}

func TestRegisterTokenAuthenticates(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()
	u, token, err := s.CreateUser(ctx, "Anon", "", "")
	require.NoError(t, err)
	got, err := s.UserByToken(ctx, token)
	require.NoError(t, err)
	require.NotNil(t, got)
	require.Equal(t, u.ID, got.ID)
}

// TestBackfilledLegacyTokenStillWorks proves the 0010 migration's backfill INSERT keeps a
// pre-migration session (users.token_hash only, no tokens row yet) alive once mirrored into tokens.
func TestBackfilledLegacyTokenStillWorks(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	raw := "legacy-raw-token-" + id.New()
	hash := TokenHash(raw)
	uid := id.New()
	_, err := s.pool.Exec(ctx,
		`INSERT INTO users (id, name, email, phone, token_hash) VALUES ($1,$2,$3,$4,$5)`,
		uid, "Legacy", "legacy-"+id.New()+"@example.com", "", hash)
	require.NoError(t, err)

	// The exact backfill statement from 0010_tokens.up.sql, scoped to this one row.
	_, err = s.pool.Exec(ctx,
		`INSERT INTO tokens (id, user_id, token_hash)
		 SELECT gen_random_uuid()::text, id, token_hash FROM users WHERE id=$1 AND token_hash <> ''`, uid)
	require.NoError(t, err)

	got, err := s.UserByToken(ctx, raw)
	require.NoError(t, err)
	require.NotNil(t, got)
	require.Equal(t, uid, got.ID)
}

func TestLogoutDeletesOnlyThatToken(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	email := "logout-" + id.New() + "@example.com"
	u, tokenA, err := s.RegisterUser(ctx, "Logout", email, "password1", "")
	require.NoError(t, err)
	_, tokenB, err := s.LoginUser(ctx, email, "password1")
	require.NoError(t, err)

	require.NoError(t, s.DeleteToken(ctx, tokenA))

	gone, err := s.UserByToken(ctx, tokenA)
	require.NoError(t, err)
	require.Nil(t, gone)

	still, err := s.UserByToken(ctx, tokenB)
	require.NoError(t, err)
	require.NotNil(t, still)
	require.Equal(t, u.ID, still.ID)
}

func TestDeleteUserCascadesTokens(t *testing.T) {
	s := newTestStore(t)
	ctx := context.Background()

	email := "cascade-" + id.New() + "@example.com"
	u, token, err := s.RegisterUser(ctx, "Cascade", email, "password1", "")
	require.NoError(t, err)

	require.NoError(t, s.DeleteUser(ctx, u.ID))

	gone, err := s.UserByToken(ctx, token)
	require.NoError(t, err)
	require.Nil(t, gone)

	var count int
	require.NoError(t, s.pool.QueryRow(ctx, `SELECT count(*) FROM tokens WHERE user_id=$1`, u.ID).Scan(&count))
	require.Equal(t, 0, count)
}
