package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
)

// ErrInviteRevoked is returned for a group link the organizer has rotated or revoked. It is kept
// distinct from expiry so the app can say why the link is dead without leaking anything else.
var ErrInviteRevoked = errors.New("invite revoked")

// GroupInvitePreview is everything an unauthenticated holder of a link may see: no ids, no members,
// no expenses. Just enough to recognise the group before signing in.
type GroupInvitePreview struct {
	GroupName   string `json:"groupName"`
	MemberCount int    `json:"memberCount"`
}

func isGroupMember(ctx context.Context, tx pgx.Tx, groupID, userID string) (bool, error) {
	var member bool
	err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM participants WHERE group_id=$1 AND user_id=$2)`, groupID, userID).Scan(&member)
	return member, err
}

// CreateGroupInvite mints the group's shareable link, revoking any link already live for it — one
// live link per group, enforced by group_invites_one_live_per_group. The raw token is returned once.
func (s *Store) CreateGroupInvite(ctx context.Context, groupID, creatorUserID string) (string, time.Time, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", time.Time{}, err
	}
	defer tx.Rollback(ctx)

	member, err := isGroupMember(ctx, tx, groupID, creatorUserID)
	if err != nil {
		return "", time.Time{}, err
	}
	if !member {
		return "", time.Time{}, ErrNotGroupMember
	}
	raw, hash, err := newToken()
	if err != nil {
		return "", time.Time{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE group_invites SET revoked_at=now() WHERE group_id=$1 AND revoked_at IS NULL`, groupID); err != nil {
		return "", time.Time{}, err
	}
	expiresAt := time.Now().UTC().Add(7 * 24 * time.Hour)
	if _, err := tx.Exec(ctx,
		`INSERT INTO group_invites (id, token_hash, group_id, creator_user_id, expires_at)
		 VALUES ($1,$2,$3,$4,$5)`, id.New(), hash, groupID, creatorUserID, expiresAt); err != nil {
		return "", time.Time{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", time.Time{}, err
	}
	return raw, expiresAt, nil
}

// RevokeGroupInvite kills the group's live link. Revoking when none is live is a no-op, so a double
// tap is harmless.
func (s *Store) RevokeGroupInvite(ctx context.Context, groupID, userID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	member, err := isGroupMember(ctx, tx, groupID, userID)
	if err != nil {
		return err
	}
	if !member {
		return ErrNotGroupMember
	}
	if _, err := tx.Exec(ctx, `UPDATE group_invites SET revoked_at=now() WHERE group_id=$1 AND revoked_at IS NULL`, groupID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) PreviewGroupInvite(ctx context.Context, raw string) (*GroupInvitePreview, error) {
	var preview GroupInvitePreview
	var expiresAt time.Time
	var revokedAt *time.Time
	err := s.pool.QueryRow(ctx,
		`SELECT g.name, (SELECT count(*) FROM participants p WHERE p.group_id=i.group_id), i.expires_at, i.revoked_at
		 FROM group_invites i JOIN groups g ON g.id=i.group_id
		 WHERE i.token_hash=$1`, TokenHash(raw)).
		Scan(&preview.GroupName, &preview.MemberCount, &expiresAt, &revokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrInviteNotFound
	}
	if err != nil {
		return nil, err
	}
	if revokedAt != nil {
		return nil, ErrInviteRevoked
	}
	if !expiresAt.After(time.Now()) {
		return nil, ErrInviteExpired
	}
	return &preview, nil
}

// RedeemGroupInvite joins the caller to the group, creating their participant named from their
// account. Already-a-member is a no-op returning the same group, so a second tap (or a second
// concurrent tap — the invite row is locked FOR UPDATE, serialising redeems of one token) can never
// mint a duplicate participant.
func (s *Store) RedeemGroupInvite(ctx context.Context, raw, userID string) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	var groupID string
	var expiresAt time.Time
	var revokedAt *time.Time
	err = tx.QueryRow(ctx,
		`SELECT group_id, expires_at, revoked_at FROM group_invites WHERE token_hash=$1 FOR UPDATE`, TokenHash(raw)).
		Scan(&groupID, &expiresAt, &revokedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrInviteNotFound
	}
	if err != nil {
		return "", err
	}
	if revokedAt != nil {
		return "", ErrInviteRevoked
	}
	if !expiresAt.After(time.Now()) {
		return "", ErrInviteExpired
	}
	member, err := isGroupMember(ctx, tx, groupID, userID)
	if err != nil {
		return "", err
	}
	if member {
		return groupID, tx.Commit(ctx)
	}
	var name string
	if err := tx.QueryRow(ctx, `SELECT name FROM users WHERE id=$1`, userID).Scan(&name); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO participants (id, group_id, name, user_id, phone) VALUES ($1,$2,$3,$4,NULL)`,
		id.New(), groupID, name, userID); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return groupID, nil
}
