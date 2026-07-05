-- Password auth: users can now register with an email + password. Legacy token-only users keep
-- an empty password_hash; the unique-email rule applies only to password accounts so it doesn't
-- clash with the old unverified identities.
ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT '';
CREATE UNIQUE INDEX users_email_unique ON users (lower(email)) WHERE password_hash <> '';
