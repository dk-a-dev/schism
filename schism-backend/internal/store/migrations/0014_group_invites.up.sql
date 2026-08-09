-- A shareable group link. Unlike participant_invites (which binds one named, organizer-created
-- participant), this one is redeemable by anyone holding it and creates the redeemer's participant
-- on the spot -- so an organizer can drop a link in a chat instead of pre-entering everyone.
--
-- The blast radius of a leaked link is bounded by expiry plus explicit revocation, and only one
-- link is live per group at a time: minting a new one revokes the old.
CREATE TABLE group_invites (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  creator_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ
);

-- Partial unique index: at most one live link per group; revoked/expired rows stay for audit.
CREATE UNIQUE INDEX group_invites_one_live_per_group
  ON group_invites (group_id)
  WHERE revoked_at IS NULL;
