-- Server-assigned retention timestamp for tombstones so peer clock skew (e.g. deleted_at = 1970)
-- cannot cause premature pruning and raise `min_retained_seq` into forced full-resync storms.
ALTER TABLE sync_tombstones ADD COLUMN recorded_at TEXT;

UPDATE sync_tombstones
SET recorded_at = COALESCE(deleted_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
WHERE recorded_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sync_tombstones_recorded_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(recorded_at, deleted_at)) DESC);

-- Bind each paired bearer token to its first-seen sync peer_id to prevent peer impersonation
-- and unbounded peer_nodes table growth without breaking existing paired clients.
ALTER TABLE device_tokens ADD COLUMN bound_peer_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_tokens_bound_peer_id
    ON device_tokens(bound_peer_id)
    WHERE bound_peer_id IS NOT NULL;
