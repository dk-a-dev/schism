DROP INDEX IF EXISTS participants_user_id_idx;
DROP INDEX IF EXISTS participants_phone_idx;
ALTER TABLE participants DROP COLUMN IF EXISTS phone;
