-- Cloud receipt extraction is rate limited to one call per user per group per hour. The limit lives
-- in Postgres rather than in process memory because the backend can run more than one instance: an
-- in-memory limiter would grant one extraction per replica per hour instead of one overall.
--
-- Only the timestamp is kept. The photo, the model's answer, and anything derived from either are
-- never persisted -- this table exists solely to answer "may they call again yet".
CREATE TABLE receipt_extractions (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  last_extract_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, group_id)
);
