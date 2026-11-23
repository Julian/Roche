-- 023_boards.sql
--
-- Introduces Boards: ephemeral, snapshot-based external sources (starting with Hacker News)
-- isolated from permanent `entries` / `entries_fts` / Feed Inbox until explicitly promoted.

-- 1. Rebuild `sync_tombstones` to allow `'board'` in `entity_type CHECK`, preserving all rows,
--    indexes, and pure `sync_seq`-only triggers (matching `020_rust_driven_hlc.sql`).
CREATE TABLE sync_tombstones_new (
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'board',
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
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;

CREATE TRIGGER sync_tombstones_sync_seq_au
AFTER UPDATE OF deleted_at, hlc ON sync_tombstones
WHEN old.deleted_at IS NOT new.deleted_at OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE sync_tombstones
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;

-- 2. Rebuild `activity_log` to allow `'board'` in `entity_type CHECK`, preserving rows and sequence.
CREATE TABLE activity_log_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL CHECK (entity_type IN (
        'feed',
        'board',
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

-- 3. `boards` table (config + polling telemetry + atomic snapshot watermark)
CREATE TABLE boards (
    id TEXT PRIMARY KEY,
    adapter_type TEXT NOT NULL CHECK (adapter_type IN ('hacker_news')),
    source_key TEXT NOT NULL,
    source_url TEXT NOT NULL,
    source_config_json TEXT NOT NULL DEFAULT '{}',
    title TEXT NOT NULL,
    order_mode TEXT NOT NULL DEFAULT 'ranked' CHECK (order_mode IN ('ranked', 'chronological')),
    poll_interval INTEGER NOT NULL DEFAULT 30 CHECK (poll_interval > 0),
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    last_polled_at TEXT,
    last_polled_by TEXT,
    last_error TEXT,
    last_error_by TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    snapshot_fetched_at TEXT,
    snapshot_hlc TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    hlc TEXT NOT NULL DEFAULT '',
    sync_seq INTEGER NOT NULL DEFAULT 0,
    UNIQUE(adapter_type, source_key)
) STRICT;

CREATE INDEX idx_boards_sync_seq ON boards(sync_seq);
CREATE INDEX idx_boards_active ON boards(is_active);

CREATE TRIGGER boards_sync_seq_ai AFTER INSERT ON boards
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE boards
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

CREATE TRIGGER boards_sync_seq_au
AFTER UPDATE OF
    adapter_type, source_key, source_url, source_config_json, title, order_mode,
    poll_interval, is_active, last_polled_at, last_polled_by, last_error, last_error_by,
    consecutive_failures, snapshot_fetched_at, snapshot_hlc, updated_at, hlc
ON boards
WHEN old.adapter_type IS NOT new.adapter_type
  OR old.source_key IS NOT new.source_key
  OR old.source_url IS NOT new.source_url
  OR old.source_config_json IS NOT new.source_config_json
  OR old.title IS NOT new.title
  OR old.order_mode IS NOT new.order_mode
  OR old.poll_interval IS NOT new.poll_interval
  OR old.is_active IS NOT new.is_active
  OR old.last_polled_at IS NOT new.last_polled_at
  OR old.last_polled_by IS NOT new.last_polled_by
  OR old.last_error IS NOT new.last_error
  OR old.last_error_by IS NOT new.last_error_by
  OR old.consecutive_failures IS NOT new.consecutive_failures
  OR old.snapshot_fetched_at IS NOT new.snapshot_fetched_at
  OR old.snapshot_hlc IS NOT new.snapshot_hlc
  OR old.updated_at IS NOT new.updated_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE boards
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

-- 4. `board_items` table (ephemeral snapshot cache; synced atomically via `boards.snapshot_hlc`)
CREATE TABLE board_items (
    board_id TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    rank INTEGER NOT NULL CHECK (rank >= 1),
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    discussion_url TEXT,
    author TEXT,
    domain TEXT,
    published_at TEXT,
    score INTEGER,
    comment_count INTEGER,
    summary TEXT,
    self_text_html TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    fetched_at TEXT NOT NULL,
    PRIMARY KEY (board_id, external_id)
) STRICT;

CREATE INDEX idx_board_items_rank ON board_items(board_id, rank ASC);
CREATE INDEX idx_board_items_url ON board_items(url);

-- 5. `board_item_states` table (ephemeral user seen/dismissed state with 48h grace pruning)
CREATE TABLE board_item_states (
    board_id TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    seen_at TEXT,
    dismissed_at TEXT,
    last_seen_in_snapshot_at TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    hlc TEXT NOT NULL DEFAULT '',
    sync_seq INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (board_id, external_id)
) STRICT;

CREATE INDEX idx_board_item_states_sync_seq ON board_item_states(sync_seq);
CREATE INDEX idx_board_item_states_prune ON board_item_states(last_seen_in_snapshot_at);

CREATE TRIGGER board_item_states_sync_seq_ai AFTER INSERT ON board_item_states
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE board_item_states
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE board_id = new.board_id AND external_id = new.external_id;
END;

CREATE TRIGGER board_item_states_sync_seq_au
AFTER UPDATE OF seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc
ON board_item_states
WHEN old.seen_at IS NOT new.seen_at
  OR old.dismissed_at IS NOT new.dismissed_at
  OR old.last_seen_in_snapshot_at IS NOT new.last_seen_in_snapshot_at
  OR old.updated_at IS NOT new.updated_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE board_item_states
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE board_id = new.board_id AND external_id = new.external_id;
END;
