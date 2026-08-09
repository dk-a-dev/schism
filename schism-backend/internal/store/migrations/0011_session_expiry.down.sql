DROP INDEX IF EXISTS tokens_hash_expiry_idx;

ALTER TABLE tokens
  DROP COLUMN IF EXISTS expires_at,
  DROP COLUMN IF EXISTS last_used_at;
