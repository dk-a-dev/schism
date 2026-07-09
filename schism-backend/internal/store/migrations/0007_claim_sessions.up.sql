CREATE TABLE claim_sessions (
    id                     TEXT PRIMARY KEY,
    group_id               TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    creator_participant_id TEXT NOT NULL,
    title                  TEXT NOT NULL DEFAULT '',
    currency               TEXT NOT NULL DEFAULT '',
    items                  JSONB NOT NULL,          -- [{idx,name,qty,amountMinor}]
    tax_minor              BIGINT NOT NULL DEFAULT 0,
    fees_minor             BIGINT NOT NULL DEFAULT 0,
    discount_minor         BIGINT NOT NULL DEFAULT 0,
    roundoff_minor         BIGINT NOT NULL DEFAULT 0,
    status                 TEXT NOT NULL DEFAULT 'open',   -- open | finalized | cancelled
    version                INT  NOT NULL DEFAULT 1,
    expense_id             TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claims (
    session_id     TEXT NOT NULL REFERENCES claim_sessions(id) ON DELETE CASCADE,
    item_idx       INT  NOT NULL,
    participant_id TEXT NOT NULL,
    weight         NUMERIC(6,2) NOT NULL CHECK (weight > 0),
    PRIMARY KEY (session_id, item_idx, participant_id)
);
CREATE INDEX claims_session_idx ON claims (session_id);
