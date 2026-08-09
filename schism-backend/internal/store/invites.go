package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
)

var (
	ErrInviteNotFound      = errors.New("invite not found")
	ErrInviteExpired       = errors.New("invite expired")
	ErrInviteUsed          = errors.New("invite already redeemed")
	ErrParticipantLinked   = errors.New("participant already linked")
	ErrNotGroupMember      = errors.New("not a member of this group")
	ErrAlreadyGroupMember  = errors.New("user is already a group member")
	ErrParticipantNotFound = errors.New("participant not found")
)

type InvitePreview struct {
	GroupID         string    `json:"groupId"`
	GroupName       string    `json:"groupName"`
	Currency        string    `json:"currency"`
	ParticipantID   string    `json:"participantId"`
	ParticipantName string    `json:"participantName"`
	ExpiresAt       time.Time `json:"expiresAt"`
}

func (s *Store) CreateParticipantInvite(ctx context.Context, groupID, participantID, creatorUserID string) (string, time.Time, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", time.Time{}, err
	}
	defer tx.Rollback(ctx)

	var member bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM participants WHERE group_id=$1 AND user_id=$2)`, groupID, creatorUserID).Scan(&member)
	if err != nil {
		return "", time.Time{}, err
	}
	if !member {
		return "", time.Time{}, ErrNotGroupMember
	}
	var linkedUserID *string
	err = tx.QueryRow(ctx, `SELECT user_id FROM participants WHERE id=$1 AND group_id=$2 FOR UPDATE`, participantID, groupID).Scan(&linkedUserID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", time.Time{}, ErrParticipantNotFound
	}
	if err != nil {
		return "", time.Time{}, err
	}
	if linkedUserID != nil {
		return "", time.Time{}, ErrParticipantLinked
	}
	raw, hash, err := newToken()
	if err != nil {
		return "", time.Time{}, err
	}
	expiresAt := time.Now().UTC().Add(7 * 24 * time.Hour)
	if _, err := tx.Exec(ctx, `DELETE FROM participant_invites WHERE participant_id=$1 AND redeemed_at IS NULL`, participantID); err != nil {
		return "", time.Time{}, err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO participant_invites (id, token_hash, group_id, participant_id, creator_user_id, expires_at)
		 VALUES ($1,$2,$3,$4,$5,$6)`, id.New(), hash, groupID, participantID, creatorUserID, expiresAt); err != nil {
		return "", time.Time{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", time.Time{}, err
	}
	return raw, expiresAt, nil
}

func (s *Store) PreviewParticipantInvite(ctx context.Context, raw string) (*InvitePreview, error) {
	var preview InvitePreview
	var redeemedAt *time.Time
	err := s.pool.QueryRow(ctx,
		`SELECT i.group_id, g.name, g.currency, i.participant_id, p.name, i.expires_at, i.redeemed_at
		 FROM participant_invites i
		 JOIN groups g ON g.id=i.group_id
		 JOIN participants p ON p.id=i.participant_id AND p.group_id=i.group_id
		 WHERE i.token_hash=$1`, TokenHash(raw)).
		Scan(&preview.GroupID, &preview.GroupName, &preview.Currency, &preview.ParticipantID, &preview.ParticipantName, &preview.ExpiresAt, &redeemedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrInviteNotFound
	}
	if err != nil {
		return nil, err
	}
	if redeemedAt != nil {
		return nil, ErrInviteUsed
	}
	if !preview.ExpiresAt.After(time.Now()) {
		return nil, ErrInviteExpired
	}
	return &preview, nil
}

func (s *Store) RedeemParticipantInvite(ctx context.Context, raw, userID string) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)
	var groupID, participantID string
	var expiresAt time.Time
	var redeemedAt *time.Time
	err = tx.QueryRow(ctx,
		`SELECT group_id, participant_id, expires_at, redeemed_at FROM participant_invites
		 WHERE token_hash=$1 FOR UPDATE`, TokenHash(raw)).
		Scan(&groupID, &participantID, &expiresAt, &redeemedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrInviteNotFound
	}
	if err != nil {
		return "", err
	}
	if redeemedAt != nil {
		return "", ErrInviteUsed
	}
	if !expiresAt.After(time.Now()) {
		return "", ErrInviteExpired
	}
	var linkedUserID *string
	if err := tx.QueryRow(ctx, `SELECT user_id FROM participants WHERE id=$1 AND group_id=$2 FOR UPDATE`, participantID, groupID).Scan(&linkedUserID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", ErrParticipantNotFound
		}
		return "", err
	}
	if linkedUserID != nil {
		return "", ErrParticipantLinked
	}
	var alreadyMember bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM participants WHERE group_id=$1 AND user_id=$2)`, groupID, userID).Scan(&alreadyMember); err != nil {
		return "", err
	}
	if alreadyMember {
		return "", ErrAlreadyGroupMember
	}
	if _, err := tx.Exec(ctx, `UPDATE participants SET user_id=$2 WHERE id=$1`, participantID, userID); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `UPDATE participant_invites SET redeemed_at=now(), redeemed_by_user_id=$2 WHERE token_hash=$1`, TokenHash(raw), userID); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return groupID, nil
}
