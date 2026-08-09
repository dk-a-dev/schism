-- Move the cloud-extraction limit from (user, group) to (user), and from one call to a counted
-- window.
--
-- The per-group key was not a spend bound at all: POST /v1/groups has no rate limit, so a single
-- account could loop create-group -> extract -> create-group and issue unlimited paid calls to
-- Gemini/Groq. The cost is ours, not the caller's, so the ceiling has to be per user.
--
-- A single last_extract_at cannot express "2 per hour", so the row now carries the window start and
-- how many calls have been spent inside it. This is a fixed window rather than a sliding one: it is
-- one row and one atomic statement, and the failure mode (up to 2N calls across a window boundary)
-- is bounded and cheap. A sliding window would need one row per call and a reaper.
--
-- Dropping rather than migrating: cloud extraction has never been enabled in any deployment
-- (RECEIPT_AI_ENABLED defaults off and the route is not even registered without a provider key), so
-- this table is empty everywhere. Preserving rows here would be pretending to migrate data that
-- does not exist.
DROP TABLE IF EXISTS receipt_extractions;

CREATE TABLE receipt_extractions (
  user_id           TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  window_started_at TIMESTAMPTZ NOT NULL,
  used              INTEGER NOT NULL CHECK (used >= 0)
);
