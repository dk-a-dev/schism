DROP INDEX users_token_hash_idx;
ALTER TABLE users DROP COLUMN token_hash;
