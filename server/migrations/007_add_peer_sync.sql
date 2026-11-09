ALTER TABLE feeds ADD COLUMN last_polled_by TEXT;
ALTER TABLE feeds ADD COLUMN last_error_by TEXT;

ALTER TABLE items ADD COLUMN interaction_updated_at DATETIME;

CREATE TABLE IF NOT EXISTS sync_tombstones (
    entity_type TEXT NOT NULL CHECK (entity_type IN ('feed', 'item', 'tag')),
    natural_key TEXT NOT NULL,
    deleted_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    PRIMARY KEY (entity_type, natural_key)
);

CREATE INDEX IF NOT EXISTS idx_sync_tombstones_deleted_at ON sync_tombstones(deleted_at);

CREATE TABLE IF NOT EXISTS peer_nodes (
    peer_id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    network_tier TEXT NOT NULL DEFAULT 'wifi' CHECK (network_tier IN ('wired', 'wifi', 'cellular', 'offline')),
    weight REAL NOT NULL DEFAULT 0.6,
    last_seen_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);
