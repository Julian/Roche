-- Scoped, expiring browser sessions isolated from long-lived API device tokens.
CREATE TABLE IF NOT EXISTS web_sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    last_used_at TEXT,
    expires_at TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS idx_web_sessions_expires_at
    ON web_sessions (expires_at);

CREATE INDEX IF NOT EXISTS idx_web_sessions_label
    ON web_sessions (label);
