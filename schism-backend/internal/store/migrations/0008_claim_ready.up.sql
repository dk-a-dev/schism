CREATE TABLE claim_ready (
    session_id     TEXT NOT NULL REFERENCES claim_sessions(id) ON DELETE CASCADE,
    participant_id TEXT NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    PRIMARY KEY (session_id, participant_id)
);
