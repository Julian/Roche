-- 020_rust_driven_hlc.sql
--
-- Moves causal HLC synthesis exclusively into Rust (`WriteTx` / `HlcClock`) so local writes and
-- peer merges share a single causally monotonic `sync_clock.last_hlc` high-water mark, even when
-- peers exhibit clock skew. SQLite triggers remain responsible solely for monotonic `sync_seq`
-- watermarks.

-- 1. Backfill `sync_clock.last_hlc` to the highest HLC across all HLC-tracked tables so existing
--    databases preserve causal monotonicity across the transition.
UPDATE sync_clock
SET last_hlc = MAX(
    last_hlc,
    COALESCE((SELECT MAX(hlc) FROM entries), ''),
    COALESCE((SELECT MAX(hlc) FROM entry_state), ''),
    COALESCE((SELECT MAX(hlc) FROM feed_sources), ''),
    COALESCE((SELECT MAX(hlc) FROM collection_entries), ''),
    COALESCE((SELECT MAX(hlc) FROM collection_generators), ''),
    COALESCE((SELECT MAX(hlc) FROM consumption_log), ''),
    COALESCE((SELECT MAX(hlc) FROM comparisons), ''),
    COALESCE((SELECT MAX(hlc) FROM sync_tombstones), '')
)
WHERE id = 1;

-- 2. Replace all HLC-synthesizing triggers with pure `sync_seq`-only triggers.

-- `entries`
DROP TRIGGER IF EXISTS entries_sync_seq_ai;
CREATE TRIGGER entries_sync_seq_ai AFTER INSERT ON entries
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

DROP TRIGGER IF EXISTS entries_sync_seq_au;
CREATE TRIGGER entries_sync_seq_au
AFTER UPDATE OF
    kind, title, creators_json, url, published_at, release_year,
    summary, thumbnail_url, metadata_json, merged_into_id, updated_at, hlc
ON entries
WHEN old.kind IS NOT new.kind
  OR old.title IS NOT new.title
  OR old.creators_json IS NOT new.creators_json
  OR old.url IS NOT new.url
  OR old.published_at IS NOT new.published_at
  OR old.release_year IS NOT new.release_year
  OR old.summary IS NOT new.summary
  OR old.thumbnail_url IS NOT new.thumbnail_url
  OR old.metadata_json IS NOT new.metadata_json
  OR old.merged_into_id IS NOT new.merged_into_id
  OR old.hlc IS NOT new.hlc
  OR old.updated_at IS NOT new.updated_at
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

-- `entry_state` (bumps parent `entries.sync_seq`)
DROP TRIGGER IF EXISTS entry_state_sync_seq_ai;
CREATE TRIGGER entry_state_sync_seq_ai AFTER INSERT ON entry_state
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

DROP TRIGGER IF EXISTS entry_state_sync_seq_au;
CREATE TRIGGER entry_state_sync_seq_au
AFTER UPDATE OF inbox_state, read_at, progress, interaction_updated_at, hlc ON entry_state
WHEN old.inbox_state IS NOT new.inbox_state
  OR old.read_at IS NOT new.read_at
  OR old.progress IS NOT new.progress
  OR old.interaction_updated_at IS NOT new.interaction_updated_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

-- `feed_sources` (bumps parent `entries.sync_seq`)
DROP TRIGGER IF EXISTS feed_sources_sync_seq_ai;
CREATE TRIGGER feed_sources_sync_seq_ai AFTER INSERT ON feed_sources
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

DROP TRIGGER IF EXISTS feed_sources_sync_seq_au;
CREATE TRIGGER feed_sources_sync_seq_au
AFTER UPDATE OF
    feed_url, source_type, feed_title, language, poll_interval, is_active,
    last_polled_at, last_polled_by, last_error, last_error_by, consecutive_failures, hlc
ON feed_sources
WHEN old.feed_url IS NOT new.feed_url
  OR old.source_type IS NOT new.source_type
  OR old.feed_title IS NOT new.feed_title
  OR old.language IS NOT new.language
  OR old.poll_interval IS NOT new.poll_interval
  OR old.is_active IS NOT new.is_active
  OR old.last_polled_at IS NOT new.last_polled_at
  OR old.last_polled_by IS NOT new.last_polled_by
  OR old.last_error IS NOT new.last_error
  OR old.last_error_by IS NOT new.last_error_by
  OR old.consecutive_failures IS NOT new.consecutive_failures
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

-- `collection_entries`
DROP TRIGGER IF EXISTS collection_entries_sync_seq_ai;
CREATE TRIGGER collection_entries_sync_seq_ai AFTER INSERT ON collection_entries
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE collection_id = new.collection_id AND entry_id = new.entry_id;
END;

DROP TRIGGER IF EXISTS collection_entries_sync_seq_au;
CREATE TRIGGER collection_entries_sync_seq_au
AFTER UPDATE OF position, added_at, hlc ON collection_entries
WHEN old.position IS NOT new.position
  OR old.added_at IS NOT new.added_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE collection_id = new.collection_id AND entry_id = new.entry_id;
END;

-- `collection_generators`
DROP TRIGGER IF EXISTS collection_generators_sync_seq_ai;
CREATE TRIGGER collection_generators_sync_seq_ai AFTER INSERT ON collection_generators
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_generators
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE collection_id = new.collection_id;
END;

DROP TRIGGER IF EXISTS collection_generators_sync_seq_au;
CREATE TRIGGER collection_generators_sync_seq_au
AFTER UPDATE OF enabled, current_entry_id, drawn_at, updated_at, hlc ON collection_generators
WHEN old.enabled IS NOT new.enabled
  OR old.current_entry_id IS NOT new.current_entry_id
  OR old.drawn_at IS NOT new.drawn_at
  OR old.updated_at IS NOT new.updated_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_generators
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE collection_id = new.collection_id;
END;

-- `consumption_log`
DROP TRIGGER IF EXISTS consumption_log_sync_seq_ai;
CREATE TRIGGER consumption_log_sync_seq_ai AFTER INSERT ON consumption_log
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE consumption_log
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

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
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

-- `comparisons`
DROP TRIGGER IF EXISTS comparisons_sync_seq_ai;
CREATE TRIGGER comparisons_sync_seq_ai AFTER INSERT ON comparisons
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE comparisons
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

DROP TRIGGER IF EXISTS comparisons_sync_seq_au;
CREATE TRIGGER comparisons_sync_seq_au
AFTER UPDATE OF ts, scope_collection_id, ordering_json, hlc ON comparisons
WHEN old.ts IS NOT new.ts
  OR old.scope_collection_id IS NOT new.scope_collection_id
  OR old.ordering_json IS NOT new.ordering_json
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE comparisons
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.id;
END;

-- `sync_tombstones`
DROP TRIGGER IF EXISTS sync_tombstones_sync_seq_ai;
CREATE TRIGGER sync_tombstones_sync_seq_ai AFTER INSERT ON sync_tombstones
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE sync_tombstones
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;

DROP TRIGGER IF EXISTS sync_tombstones_sync_seq_au;
CREATE TRIGGER sync_tombstones_sync_seq_au
AFTER UPDATE OF deleted_at, hlc ON sync_tombstones
WHEN old.deleted_at IS NOT new.deleted_at OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE sync_tombstones
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE entity_type = new.entity_type AND natural_key = new.natural_key;
END;
