ALTER TABLE tokens
  ADD COLUMN last_used_at TIMESTAMPTZ,
  ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE tokens
SET last_used_at = created_at,
    expires_at = now() + interval '90 days';

ALTER TABLE tokens
  ALTER COLUMN last_used_at SET DEFAULT now(),
  ALTER COLUMN last_used_at SET NOT NULL,
  ALTER COLUMN expires_at SET DEFAULT (now() + interval '90 days'),
  ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX tokens_hash_expiry_idx ON tokens (token_hash, expires_at);
