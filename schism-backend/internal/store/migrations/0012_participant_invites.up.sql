CREATE TABLE participant_invites (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  participant_id TEXT NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
  creator_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  redeemed_at TIMESTAMPTZ,
  redeemed_by_user_id TEXT REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX participant_invites_participant_idx ON participant_invites(participant_id);
