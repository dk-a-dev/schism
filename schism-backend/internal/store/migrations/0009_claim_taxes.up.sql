ALTER TABLE claim_sessions ADD COLUMN taxes JSONB NOT NULL DEFAULT '[]';  -- ordered [{label,amountMinor}]
