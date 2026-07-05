-- A registration mints a secret bearer token, returned once and stored only as a hash. The client
-- sends it as `Authorization: Bearer <token>` so the server can resolve which user is calling —
-- authenticating identity so participant linking can't be spoofed. The raw token is never stored.
ALTER TABLE users ADD COLUMN token_hash TEXT NOT NULL DEFAULT '';
CREATE INDEX users_token_hash_idx ON users (token_hash) WHERE token_hash <> '';
