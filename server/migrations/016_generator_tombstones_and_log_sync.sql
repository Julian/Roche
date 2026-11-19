-- 016_generator_tombstones_and_log_sync.sql
--
-- 1. Adds `collection_generator` to `sync_tombstones.entity_type` and `activity_log.entity_type`
--    so deleting a collection generator syncs across peers instead of being resurrected.
-- 2. Recreates `consumption_log_sync_seq_au` to include `entry_id`, ensuring entry merges
--    that re-point consumption logs bump `sync_seq` and `hlc` for incremental sync.

CREATE TABLE sync_tombstones_new (
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'item',
        'tag',
        'entry',
        'collection_entry',
        'collection_generator',
        'consumption_log'
    )),
    natural_key TEXT NOT NULL,
    deleted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    hlc TEXT NOT NULL DEFAULT '',
    sync_seq INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (entity_type, natural_key)
) STRICT;

INSERT INTO sync_tombstones_new (entity_type, natural_key, deleted_at, hlc, sync_seq)
SELECT entity_type, natural_key, deleted_at, hlc, sync_seq
FROM sync_tombstones;

DROP TABLE sync_tombstones;
ALTER TABLE sync_tombstones_new RENAME TO sync_tombstones;

CREATE INDEX idx_sync_tombstones_deleted_at ON sync_tombstones(deleted_at);
CREATE INDEX idx_sync_tombstones_deleted_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) DESC);
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

CREATE TABLE activity_log_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'item',
        'tag',
        'media',
        'entry',
        'collection',
        'collection_entry',
        'collection_generator',
        'consumption_log',
        'comparison'
    )),
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN (
        'create',
        'update',
        'archive',
        'unarchive',
        'favorite',
        'unfavorite',
        'tag_add',
        'tag_remove',
        'delete'
    )),
    timestamp TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO activity_log_new (id, entity_type, entity_id, action, timestamp)
SELECT id, entity_type, entity_id, action, timestamp
FROM activity_log;

UPDATE sqlite_sequence
SET seq = (SELECT seq FROM sqlite_sequence WHERE name = 'activity_log')
WHERE name = 'activity_log_new'
  AND seq < (SELECT seq FROM sqlite_sequence WHERE name = 'activity_log');

INSERT INTO sqlite_sequence (name, seq)
SELECT 'activity_log_new', seq
FROM sqlite_sequence
WHERE name = 'activity_log'
  AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'activity_log_new');

DROP TABLE activity_log;
ALTER TABLE activity_log_new RENAME TO activity_log;

CREATE INDEX idx_activity_log_entity ON activity_log(entity_type, entity_id);
CREATE INDEX idx_activity_log_timestamp ON activity_log(timestamp);
CREATE INDEX idx_activity_log_timestamp_iso
    ON activity_log(strftime('%Y-%m-%dT%H:%M:%fZ', timestamp));

DROP TRIGGER IF EXISTS consumption_log_sync_seq_au;
CREATE TRIGGER consumption_log_sync_seq_au
AFTER UPDATE OF entry_id, ts, status, notes, hlc ON consumption_log
WHEN old.entry_id IS NOT new.entry_id
  OR old.ts IS NOT new.ts
  OR old.status IS NOT new.status
  OR old.notes IS NOT new.notes
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE consumption_log
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' AND new.hlc IS NOT old.hlc THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER),
                    CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
                )
            )
        END
    WHERE id = new.id;
END;
