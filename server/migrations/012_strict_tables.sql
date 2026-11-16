-- no-transaction
-- 012_strict_tables.sql
--
-- Rebuilds all 17 regular SQLite tables with SQLite's `STRICT` table modifier so column storage
-- classes (`TEXT`, `INTEGER`, `REAL`) are strictly enforced by SQLite rather than type-affinity
-- coerced. Preserves `entries.rowid` alignment with `entries_fts`, `sqlite_sequence` for
-- `activity_log`, all foreign keys, all 27 secondary/expression indexes, and all 6 FTS5 triggers.

PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;

DROP TRIGGER IF EXISTS entries_fts_ai;
DROP TRIGGER IF EXISTS entries_fts_ad;
DROP TRIGGER IF EXISTS entries_fts_au;
DROP TRIGGER IF EXISTS entry_content_fts_ai;
DROP TRIGGER IF EXISTS entry_content_fts_au;
DROP TRIGGER IF EXISTS entry_content_fts_ad;

CREATE TABLE device_tokens_new (
    id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    last_used_at TEXT
) STRICT;

INSERT INTO device_tokens_new (id, device_name, token, created_at, last_used_at)
SELECT id, device_name, token, CAST(created_at AS TEXT), CAST(last_used_at AS TEXT)
FROM device_tokens;

DROP TABLE device_tokens;
ALTER TABLE device_tokens_new RENAME TO device_tokens;

CREATE TABLE pairing_codes_new (
    code TEXT PRIMARY KEY,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    expires_at TEXT NOT NULL
) STRICT;

INSERT INTO pairing_codes_new (code, failed_attempts, created_at, expires_at)
SELECT code, CAST(failed_attempts AS INTEGER), CAST(created_at AS TEXT), CAST(expires_at AS TEXT)
FROM pairing_codes;

DROP TABLE pairing_codes;
ALTER TABLE pairing_codes_new RENAME TO pairing_codes;

CREATE TABLE peer_nodes_new (
    peer_id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    network_tier TEXT NOT NULL DEFAULT 'wifi' CHECK (network_tier IN ('wired', 'wifi', 'cellular', 'offline')),
    weight REAL NOT NULL DEFAULT 0.6,
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO peer_nodes_new (peer_id, device_name, network_tier, weight, last_seen_at)
SELECT peer_id, device_name, network_tier, CAST(weight AS REAL), CAST(last_seen_at AS TEXT)
FROM peer_nodes;

DROP TABLE peer_nodes;
ALTER TABLE peer_nodes_new RENAME TO peer_nodes;

CREATE TABLE maintenance_runs_new (
    task_name TEXT PRIMARY KEY,
    completed_at TEXT NOT NULL
) STRICT;

INSERT INTO maintenance_runs_new (task_name, completed_at)
SELECT task_name, CAST(completed_at AS TEXT)
FROM maintenance_runs;

DROP TABLE maintenance_runs;
ALTER TABLE maintenance_runs_new RENAME TO maintenance_runs;

CREATE TABLE entries_new (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN (
        'article', 'paper', 'video', 'podcast_episode', 'song', 'movie', 'book', 'game',
        'album', 'tv', 'artist', 'podcast', 'publication', 'channel', 'collection'
    )),
    title TEXT NOT NULL,
    creators_json TEXT NOT NULL DEFAULT '[]',
    url TEXT,
    published_at TEXT,
    release_year INTEGER,
    summary TEXT,
    thumbnail_url TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    merged_into_id TEXT REFERENCES entries(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO entries_new (
    rowid, id, kind, title, creators_json, url, published_at, release_year,
    summary, thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
)
SELECT
    rowid, id, kind, title, creators_json, url, CAST(published_at AS TEXT),
    CAST(release_year AS INTEGER), summary, thumbnail_url, metadata_json,
    CASE WHEN merged_into_id IN (SELECT id FROM entries) THEN merged_into_id ELSE NULL END,
    CAST(created_at AS TEXT), CAST(updated_at AS TEXT)
FROM entries;

DROP TABLE entries;
ALTER TABLE entries_new RENAME TO entries;

CREATE INDEX idx_entries_kind ON entries(kind) WHERE merged_into_id IS NULL;
CREATE INDEX idx_entries_published_sort ON entries(COALESCE(published_at, created_at) DESC, id DESC);
CREATE INDEX idx_entries_updated_at ON entries(updated_at, id);
CREATE INDEX idx_entries_url ON entries(url) WHERE url IS NOT NULL;
CREATE INDEX idx_entries_merged_into ON entries(merged_into_id) WHERE merged_into_id IS NOT NULL;
CREATE INDEX idx_entries_updated_at_iso ON entries(strftime('%Y-%m-%dT%H:%M:%fZ', updated_at), id);

CREATE TABLE entry_identifiers_new (
    scheme TEXT NOT NULL,
    value TEXT NOT NULL,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    PRIMARY KEY (scheme, value)
) STRICT;

INSERT INTO entry_identifiers_new (scheme, value, entry_id)
SELECT scheme, value, entry_id
FROM entry_identifiers
WHERE entry_id IN (SELECT id FROM entries);

DROP TABLE entry_identifiers;
ALTER TABLE entry_identifiers_new RENAME TO entry_identifiers;

CREATE INDEX idx_entry_identifiers_entry_id ON entry_identifiers(entry_id);

CREATE TABLE entry_content_new (
    entry_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    content_html TEXT,
    content_type TEXT NOT NULL DEFAULT 'article' CHECK (content_type IN ('article', 'video', 'paper', 'audio', 'pdf'))
) STRICT;

INSERT INTO entry_content_new (entry_id, content_html, content_type)
SELECT entry_id, content_html, content_type
FROM entry_content
WHERE entry_id IN (SELECT id FROM entries);

DROP TABLE entry_content;
ALTER TABLE entry_content_new RENAME TO entry_content;

CREATE TABLE entry_media_new (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('video', 'audio', 'pdf', 'thumbnail')),
    remote_url TEXT,
    local_path TEXT,
    file_size INTEGER CHECK (file_size IS NULL OR file_size >= 0),
    duration INTEGER CHECK (duration IS NULL OR duration >= 0),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO entry_media_new (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
SELECT
    id, entry_id, media_type, remote_url, local_path,
    CASE WHEN file_size IS NULL THEN NULL ELSE MAX(0, CAST(file_size AS INTEGER)) END,
    CASE WHEN duration IS NULL THEN NULL ELSE MAX(0, CAST(duration AS INTEGER)) END,
    CAST(created_at AS TEXT)
FROM entry_media;

DROP TABLE entry_media;
ALTER TABLE entry_media_new RENAME TO entry_media;

CREATE INDEX idx_entry_media_entry_id ON entry_media(entry_id);
CREATE INDEX idx_entry_media_local_path ON entry_media(entry_id) WHERE local_path IS NOT NULL;

CREATE TABLE entry_state_new (
    entry_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    inbox_state TEXT NOT NULL DEFAULT 'none' CHECK (inbox_state IN ('none', 'unread', 'dismissed')),
    read_at TEXT,
    progress REAL NOT NULL DEFAULT 0.0 CHECK (progress >= 0.0 AND progress <= 1.0),
    interaction_updated_at TEXT,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO entry_state_new (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
SELECT
    entry_id, inbox_state, CAST(read_at AS TEXT),
    MIN(1.0, MAX(0.0, COALESCE(CAST(progress AS REAL), 0.0))),
    CAST(interaction_updated_at AS TEXT), CAST(updated_at AS TEXT)
FROM entry_state
WHERE entry_id IN (SELECT id FROM entries);

DROP TABLE entry_state;
ALTER TABLE entry_state_new RENAME TO entry_state;

CREATE INDEX idx_entry_state_inbox ON entry_state(inbox_state, read_at);

CREATE TABLE collection_specs_new (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    slug TEXT UNIQUE,
    mode TEXT NOT NULL CHECK (mode IN ('manual', 'feed', 'dynamic')),
    collection_role TEXT NOT NULL DEFAULT 'list' CHECK (collection_role IN ('list', 'want', 'system', 'tag', 'feed')),
    rule_json TEXT,
    show_in_lists INTEGER NOT NULL DEFAULT 0 CHECK (show_in_lists IN (0, 1)),
    color TEXT
) STRICT;

INSERT INTO collection_specs_new (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
SELECT
    collection_id, slug, mode, collection_role, rule_json,
    CASE WHEN show_in_lists != 0 THEN 1 ELSE 0 END, color
FROM collection_specs
WHERE collection_id IN (SELECT id FROM entries);

DROP TABLE collection_specs;
ALTER TABLE collection_specs_new RENAME TO collection_specs;

CREATE INDEX idx_collection_specs_role ON collection_specs(collection_role, mode);

CREATE TABLE collection_entries_new (
    collection_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    position REAL,
    added_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    PRIMARY KEY (collection_id, entry_id)
) STRICT;

INSERT INTO collection_entries_new (collection_id, entry_id, position, added_at)
SELECT collection_id, entry_id, CAST(position AS REAL), CAST(added_at AS TEXT)
FROM collection_entries
WHERE collection_id IN (SELECT id FROM entries)
  AND entry_id IN (SELECT id FROM entries);

DROP TABLE collection_entries;
ALTER TABLE collection_entries_new RENAME TO collection_entries;

CREATE INDEX idx_collection_entries_entry_id ON collection_entries(entry_id);
CREATE INDEX idx_collection_entries_pos ON collection_entries(collection_id, position, added_at);
CREATE INDEX idx_collection_entries_added_at_iso
    ON collection_entries(strftime('%Y-%m-%dT%H:%M:%fZ', added_at), collection_id, entry_id);

CREATE TABLE feed_sources_new (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    feed_url TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL CHECK (source_type IN ('rss', 'podcast', 'youtube', 'arxiv')),
    feed_title TEXT,
    language TEXT,
    poll_interval INTEGER NOT NULL DEFAULT 30 CHECK (poll_interval > 0),
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    last_polled_at TEXT,
    last_polled_by TEXT,
    last_error TEXT,
    last_error_by TEXT
) STRICT;

INSERT INTO feed_sources_new (
    collection_id, feed_url, source_type, feed_title, language, poll_interval,
    is_active, last_polled_at, last_polled_by, last_error, last_error_by
)
SELECT
    collection_id, feed_url, source_type, feed_title, language,
    CASE WHEN CAST(poll_interval AS INTEGER) > 0 THEN CAST(poll_interval AS INTEGER) ELSE 30 END,
    CASE WHEN is_active != 0 THEN 1 ELSE 0 END,
    CAST(last_polled_at AS TEXT), last_polled_by, last_error, last_error_by
FROM feed_sources
WHERE collection_id IN (SELECT id FROM entries);

DROP TABLE feed_sources;
ALTER TABLE feed_sources_new RENAME TO feed_sources;

CREATE TABLE collection_generators_new (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    current_entry_id TEXT REFERENCES entries(id) ON DELETE SET NULL,
    drawn_at TEXT,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO collection_generators_new (collection_id, enabled, current_entry_id, drawn_at, updated_at)
SELECT
    collection_id, CASE WHEN enabled != 0 THEN 1 ELSE 0 END,
    CASE WHEN current_entry_id IN (SELECT id FROM entries) THEN current_entry_id ELSE NULL END,
    CAST(drawn_at AS TEXT), CAST(updated_at AS TEXT)
FROM collection_generators
WHERE collection_id IN (SELECT id FROM entries);

DROP TABLE collection_generators;
ALTER TABLE collection_generators_new RENAME TO collection_generators;

CREATE TABLE consumption_log_new (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    ts TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('started', 'consumed', 'abandoned')),
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO consumption_log_new (id, entry_id, ts, status, notes, created_at)
SELECT id, entry_id, CAST(ts AS TEXT), status, notes, CAST(created_at AS TEXT)
FROM consumption_log
WHERE entry_id IN (SELECT id FROM entries);

DROP TABLE consumption_log;
ALTER TABLE consumption_log_new RENAME TO consumption_log;

CREATE INDEX idx_consumption_log_entry_status ON consumption_log(entry_id, status);
CREATE INDEX idx_consumption_log_ts ON consumption_log(ts DESC);
CREATE INDEX idx_consumption_log_created_at ON consumption_log(created_at);
CREATE INDEX idx_consumption_log_created_at_iso
    ON consumption_log(strftime('%Y-%m-%dT%H:%M:%fZ', created_at), id);
CREATE INDEX idx_consumption_log_ts_iso
    ON consumption_log(strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC);

CREATE TABLE comparisons_new (
    id TEXT PRIMARY KEY,
    ts TEXT NOT NULL,
    scope_collection_id TEXT REFERENCES entries(id) ON DELETE CASCADE,
    ordering_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO comparisons_new (id, ts, scope_collection_id, ordering_json, created_at)
SELECT
    id, CAST(ts AS TEXT),
    CASE WHEN scope_collection_id IN (SELECT id FROM entries) THEN scope_collection_id ELSE NULL END,
    ordering_json, CAST(created_at AS TEXT)
FROM comparisons;

DROP TABLE comparisons;
ALTER TABLE comparisons_new RENAME TO comparisons;

CREATE INDEX idx_comparisons_scope_ts ON comparisons(scope_collection_id, ts);
CREATE INDEX idx_comparisons_created_at ON comparisons(created_at);
CREATE INDEX idx_comparisons_created_at_iso
    ON comparisons(strftime('%Y-%m-%dT%H:%M:%fZ', created_at), id);

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
    timestamp TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
) STRICT;

INSERT INTO activity_log_new (id, entity_type, entity_id, action, timestamp)
SELECT id, entity_type, entity_id, action, CAST(timestamp AS TEXT)
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

CREATE TABLE sync_tombstones_new (
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'item',
        'tag',
        'entry',
        'collection_entry',
        'consumption_log'
    )),
    natural_key TEXT NOT NULL,
    deleted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    PRIMARY KEY (entity_type, natural_key)
) STRICT;

INSERT INTO sync_tombstones_new (entity_type, natural_key, deleted_at)
SELECT entity_type, natural_key, CAST(deleted_at AS TEXT)
FROM sync_tombstones;

DROP TABLE sync_tombstones;
ALTER TABLE sync_tombstones_new RENAME TO sync_tombstones;

CREATE INDEX idx_sync_tombstones_deleted_at ON sync_tombstones(deleted_at);
CREATE INDEX idx_sync_tombstones_deleted_at_iso
    ON sync_tombstones(strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) DESC);

CREATE TRIGGER entries_fts_ai AFTER INSERT ON entries
WHEN new.merged_into_id IS NULL
BEGIN
    INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
    VALUES (
        new.rowid,
        new.id,
        new.title,
        new.creators_json,
        COALESCE(new.summary, ''),
        COALESCE((SELECT content_html FROM entry_content WHERE entry_id = new.id), '')
    );
END;

CREATE TRIGGER entries_fts_ad AFTER DELETE ON entries BEGIN
    DELETE FROM entries_fts WHERE rowid = old.rowid;
    DELETE FROM entry_media WHERE entry_id = old.id AND local_path IS NULL;
END;

CREATE TRIGGER entries_fts_au AFTER UPDATE OF title, creators_json, summary, merged_into_id ON entries BEGIN
    DELETE FROM entries_fts WHERE rowid = old.rowid;
    INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
    SELECT
        new.rowid,
        new.id,
        new.title,
        new.creators_json,
        COALESCE(new.summary, ''),
        COALESCE((SELECT content_html FROM entry_content WHERE entry_id = new.id), '')
    WHERE new.merged_into_id IS NULL;
END;

CREATE TRIGGER entry_content_fts_ai AFTER INSERT ON entry_content BEGIN
    DELETE FROM entries_fts WHERE rowid = (SELECT rowid FROM entries WHERE id = new.entry_id);
    INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
    SELECT
        e.rowid,
        e.id,
        e.title,
        e.creators_json,
        COALESCE(e.summary, ''),
        COALESCE(new.content_html, '')
    FROM entries e
    WHERE e.id = new.entry_id AND e.merged_into_id IS NULL;
END;

CREATE TRIGGER entry_content_fts_au AFTER UPDATE OF content_html ON entry_content BEGIN
    DELETE FROM entries_fts WHERE rowid = (SELECT rowid FROM entries WHERE id = new.entry_id);
    INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
    SELECT
        e.rowid,
        e.id,
        e.title,
        e.creators_json,
        COALESCE(e.summary, ''),
        COALESCE(new.content_html, '')
    FROM entries e
    WHERE e.id = new.entry_id AND e.merged_into_id IS NULL;
END;

CREATE TRIGGER entry_content_fts_ad AFTER DELETE ON entry_content BEGIN
    DELETE FROM entries_fts WHERE rowid = (SELECT rowid FROM entries WHERE id = old.entry_id);
    INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
    SELECT
        e.rowid,
        e.id,
        e.title,
        e.creators_json,
        COALESCE(e.summary, ''),
        ''
    FROM entries e
    WHERE e.id = old.entry_id AND e.merged_into_id IS NULL;
END;

COMMIT;
PRAGMA foreign_keys = ON;
