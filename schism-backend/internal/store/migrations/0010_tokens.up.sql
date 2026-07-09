-- Move from a single token_hash column on users (one live session per account — every login kicked
-- every other device off) to a tokens table (one row per session), so logging in on a new device no
-- longer invalidates the others. Existing sessions are backfilled below so nobody is logged out by
-- this migration. users.token_hash is left in place (legacy; no longer read for auth).
CREATE TABLE tokens (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tokens_user_idx ON tokens(user_id);

INSERT INTO tokens (id, user_id, token_hash)
SELECT gen_random_uuid()::text, id, token_hash FROM users WHERE token_hash IS NOT NULL AND token_hash <> '';
