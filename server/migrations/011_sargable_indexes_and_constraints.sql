-- 011_sargable_indexes_and_constraints.sql
--
-- 1. Broaden `activity_log.entity_type` CHECK constraint to include `'collection_entry'`
--    (matching `ActivityEntityType::CollectionEntry` in Rust) while preserving `sqlite_sequence`.
-- 2. Add canonical ISO-8601 UTC millisecond expression indexes (`%Y-%m-%dT%H:%M:%fZ`) so delta
--    sync, keyset item pagination, tombstone queries, and Diary year filters use O(log N) B-tree
--    index seeks regardless of whether timestamps were stored with RFC3339 `T` or SQLite `' '`.

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
    timestamp DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

INSERT INTO activity_log_new (id, entity_type, entity_id, action, timestamp)
SELECT id, entity_type, entity_id, action, timestamp FROM activity_log;

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

CREATE INDEX IF NOT EXISTS idx_entries_updated_at_iso
    ON entries(strftime('%Y-%m-%dT%H:%M:%fZ', updated_at), id);

CREATE INDEX IF NOT EXISTS idx_collection_entries_added_at_iso
    ON collection_entries(strftime('%Y-%m-%dT%H:%M:%fZ', added_at), collection_id, entry_id);

CREATE INDEX IF NOT EXISTS idx_consumption_log_created_at_iso
    ON consumption_log(strftime('%Y-%m-%dT%H:%M:%fZ', created_at), id);

CREATE INDEX IF NOT EXISTS idx_consumption_log_ts_iso
    ON consumption_log(strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_comparisons_created_at_iso
    ON comparisons(strftime('%Y-%m-%dT%H:%M:%fZ', created_at), id);

CREATE INDEX IF NOT EXISTS idx_sync_tombstones_deleted_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) DESC);
