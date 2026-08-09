-- Server-owned Plus entitlements. A row per verified Google Play purchase; the raw purchase token is
-- only ever stored AES-256-GCM encrypted, and token_hash exists so a replay of the same token by a
-- different Schism account can be detected without ever comparing plaintext.
CREATE TABLE purchases (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  token_ciphertext BYTEA NOT NULL,
  state TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  auto_renewing BOOLEAN NOT NULL DEFAULT false,
  acknowledged BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX purchases_user_idx ON purchases(user_id);

-- Free hosted Live Splits consumed per UTC calendar month. One row per (user, month); the row is the
-- lock that serialises a user's concurrent create-session requests.
CREATE TABLE live_split_usage (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month_start DATE NOT NULL,
  used INT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, month_start)
);

-- Idempotency keys for allowance consumption, recorded separately from the counter so a retried
-- create returns the original session instead of burning a second allowance.
CREATE TABLE live_split_grants (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  idempotency_key TEXT NOT NULL,
  session_id TEXT NOT NULL REFERENCES claim_sessions(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idempotency_key)
);
