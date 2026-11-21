-- 019_comparison_tombstones.sql
--
-- Adds `comparison` to `sync_tombstones.entity_type` so undoing a ranking comparison
-- propagates cleanly across peers instead of failing the CHECK constraint.

CREATE TABLE sync_tombstones_new (
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'item',
        'tag',
        'entry',
        'collection_entry',
        'collection_generator',
        'consumption_log',
        'comparison'
    )),
    natural_key TEXT NOT NULL,
    deleted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    hlc TEXT NOT NULL DEFAULT '',
    sync_seq INTEGER NOT NULL DEFAULT 0,
    recorded_at TEXT,
    PRIMARY KEY (entity_type, natural_key)
) STRICT;

INSERT INTO sync_tombstones_new (entity_type, natural_key, deleted_at, hlc, sync_seq, recorded_at)
SELECT entity_type,
       natural_key,
       deleted_at,
       hlc,
       sync_seq,
       COALESCE(recorded_at, deleted_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
FROM sync_tombstones;

DROP TABLE sync_tombstones;
ALTER TABLE sync_tombstones_new RENAME TO sync_tombstones;

CREATE INDEX idx_sync_tombstones_deleted_at ON sync_tombstones(deleted_at);
CREATE INDEX idx_sync_tombstones_deleted_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) DESC);
CREATE INDEX idx_sync_tombstones_recorded_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(recorded_at, deleted_at)) DESC);
CREATE INDEX idx_sync_tombstones_sync_seq ON sync_tombstones(sync_seq);

CREATE TRIGGER sync_tombstones_sync_seq_ai AFTER INSERT ON sync_tombstones
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE sync_tombstones
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.deleted_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;

CREATE TRIGGER sync_tombstones_sync_seq_au
AFTER UPDATE OF deleted_at, hlc ON sync_tombstones
WHEN old.deleted_at IS NOT new.deleted_at OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE sync_tombstones
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' AND new.hlc IS NOT old.hlc THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    COALESCE(
                        CAST(ROUND((julianday(new.deleted_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    ),
                    CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
                )
            )
        END
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;
