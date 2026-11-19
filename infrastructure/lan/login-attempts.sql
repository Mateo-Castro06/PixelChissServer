-- Optional additive migration when Hibernate schema updates are managed externally.
-- Does not touch users, refresh_tokens, Game, Redis or MongoDB.
CREATE TABLE IF NOT EXISTS login_attempts (
    id varchar(255) PRIMARY KEY,
    purpose varchar(255) NOT NULL,
    challenge varchar(255) NOT NULL,
    nonce varchar(255) NOT NULL,
    redirect_uri varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    user_id bigint,
    consumed_at timestamptz
);
CREATE INDEX IF NOT EXISTS login_attempt_expiry ON login_attempts (expires_at);
