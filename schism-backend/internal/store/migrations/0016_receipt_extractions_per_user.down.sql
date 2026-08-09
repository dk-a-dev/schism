-- Restores the per-(user, group) single-call table from 0015. Counts are not carried back: the
-- shapes do not correspond, and the table is a rate-limit ledger with no historical value.
DROP TABLE IF EXISTS receipt_extractions;

CREATE TABLE receipt_extractions (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  last_extract_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, group_id)
);
