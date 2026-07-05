-- Users are a lightweight, UNVERIFIED identity for a no-auth app: email/phone are not
-- authenticated, so email is intentionally NOT a unique key (that would let anyone claim an
-- address). Each registration creates a distinct user; the device stores its own id.
CREATE TABLE users (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL DEFAULT '',
  email      TEXT NOT NULL DEFAULT '',
  phone      TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE participants ADD COLUMN user_id TEXT;
