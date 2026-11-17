-- 014_unified_sync_seq_and_hlc.sql
--
-- Adds monotonic server sequence watermarks (`sync_seq`) and causal Hybrid Logical Clock (`hlc`)
-- columns across the unified `entries` sync graph, replacing wall-clock `updated_after` sync
-- windowing and unifying `feeds`, `items`, `tags`, and `catalog` onto a single sync cursor.

CREATE TABLE sync_clock (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    current_seq INTEGER NOT NULL DEFAULT 0,
    min_retained_seq INTEGER NOT NULL DEFAULT 0,
    last_hlc TEXT NOT NULL DEFAULT ''
) STRICT;

INSERT INTO sync_clock (id, current_seq, min_retained_seq, last_hlc)
VALUES (1, 0, 0, '');

ALTER TABLE entries ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE entries ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

ALTER TABLE entry_state ADD COLUMN hlc TEXT NOT NULL DEFAULT '';

ALTER TABLE feed_sources ADD COLUMN hlc TEXT NOT NULL DEFAULT '';

ALTER TABLE collection_entries ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE collection_entries ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

ALTER TABLE collection_generators ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE collection_generators ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

ALTER TABLE consumption_log ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE consumption_log ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

ALTER TABLE comparisons ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE comparisons ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

ALTER TABLE sync_tombstones ADD COLUMN hlc TEXT NOT NULL DEFAULT '';
ALTER TABLE sync_tombstones ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_entries_sync_seq ON entries(sync_seq);
CREATE INDEX idx_collection_entries_sync_seq ON collection_entries(sync_seq);
CREATE INDEX idx_collection_generators_sync_seq ON collection_generators(sync_seq);
CREATE INDEX idx_consumption_log_sync_seq ON consumption_log(sync_seq);
CREATE INDEX idx_comparisons_sync_seq ON comparisons(sync_seq);
CREATE INDEX idx_sync_tombstones_sync_seq ON sync_tombstones(sync_seq);

-- Backfill deterministic initial HLCs from existing row timestamps prior to installing triggers
UPDATE entries
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(updated_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

UPDATE entry_state
SET hlc = printf(
    '%015d-0000-server',
    MAX(
        0,
        COALESCE(
            CAST(ROUND((julianday(COALESCE(interaction_updated_at, updated_at)) - 2440587.5) * 86400000.0) AS INTEGER),
            0
        )
    )
)
WHERE hlc = '';

UPDATE feed_sources
SET hlc = printf(
    '%015d-0000-server',
    MAX(
        0,
        COALESCE(
            CAST(
                ROUND(
                    (
                        julianday((SELECT e.created_at FROM entries e WHERE e.id = feed_sources.collection_id))
                        - 2440587.5
                    ) * 86400000.0
                ) AS INTEGER
            ),
            0
        )
    )
)
WHERE hlc = '';

UPDATE collection_entries
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(added_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

UPDATE collection_generators
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(updated_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

UPDATE consumption_log
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(created_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

UPDATE comparisons
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(created_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

UPDATE sync_tombstones
SET hlc = printf(
    '%015d-0000-server',
    MAX(0, COALESCE(CAST(ROUND((julianday(deleted_at) - 2440587.5) * 86400000.0) AS INTEGER), 0))
)
WHERE hlc = '';

-- Assign unique initial `sync_seq` values across existing non-seed rows
CREATE TABLE _init_sync_seq (
    seq INTEGER PRIMARY KEY,
    tbl TEXT NOT NULL,
    k1 TEXT NOT NULL,
    k2 TEXT NOT NULL DEFAULT ''
) STRICT;

INSERT INTO _init_sync_seq (tbl, k1)
SELECT 'entries', e.id
FROM entries e
WHERE NOT EXISTS (
    SELECT 1 FROM collection_specs cs
    WHERE cs.collection_id = e.id
      AND (cs.mode = 'dynamic' OR cs.collection_role = 'system')
)
ORDER BY e.created_at ASC, e.id ASC;

INSERT INTO _init_sync_seq (tbl, k1, k2)
SELECT 'collection_entries', ce.collection_id, ce.entry_id
FROM collection_entries ce
ORDER BY ce.added_at ASC, ce.collection_id ASC, ce.entry_id ASC;

INSERT INTO _init_sync_seq (tbl, k1)
SELECT 'collection_generators', cg.collection_id
FROM collection_generators cg
ORDER BY cg.updated_at ASC, cg.collection_id ASC;

INSERT INTO _init_sync_seq (tbl, k1)
SELECT 'consumption_log', cl.id
FROM consumption_log cl
ORDER BY cl.created_at ASC, cl.id ASC;

INSERT INTO _init_sync_seq (tbl, k1)
SELECT 'comparisons', c.id
FROM comparisons c
ORDER BY c.created_at ASC, c.id ASC;

INSERT INTO _init_sync_seq (tbl, k1, k2)
SELECT 'sync_tombstones', st.entity_type, st.natural_key
FROM sync_tombstones st
ORDER BY st.deleted_at ASC, st.entity_type ASC, st.natural_key ASC;

UPDATE entries
SET sync_seq = COALESCE(
    (SELECT s.seq FROM _init_sync_seq s WHERE s.tbl = 'entries' AND s.k1 = entries.id),
    0
);

UPDATE collection_entries
SET sync_seq = COALESCE(
    (
        SELECT s.seq FROM _init_sync_seq s
        WHERE s.tbl = 'collection_entries'
          AND s.k1 = collection_entries.collection_id
          AND s.k2 = collection_entries.entry_id
    ),
    0
);

UPDATE collection_generators
SET sync_seq = COALESCE(
    (
        SELECT s.seq FROM _init_sync_seq s
        WHERE s.tbl = 'collection_generators' AND s.k1 = collection_generators.collection_id
    ),
    0
);

UPDATE consumption_log
SET sync_seq = COALESCE(
    (SELECT s.seq FROM _init_sync_seq s WHERE s.tbl = 'consumption_log' AND s.k1 = consumption_log.id),
    0
);

UPDATE comparisons
SET sync_seq = COALESCE(
    (SELECT s.seq FROM _init_sync_seq s WHERE s.tbl = 'comparisons' AND s.k1 = comparisons.id),
    0
);

UPDATE sync_tombstones
SET sync_seq = COALESCE(
    (
        SELECT s.seq FROM _init_sync_seq s
        WHERE s.tbl = 'sync_tombstones'
          AND s.k1 = sync_tombstones.entity_type
          AND s.k2 = sync_tombstones.natural_key
    ),
    0
);

UPDATE sync_clock
SET current_seq = COALESCE((SELECT MAX(seq) FROM _init_sync_seq), 0)
WHERE id = 1;

DROP TABLE _init_sync_seq;

-- 1. Root `entries` triggers
CREATE TRIGGER entries_sync_seq_ai AFTER INSERT ON entries
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.updated_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE id = new.id;
END;

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
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' AND new.hlc IS NOT old.hlc THEN new.hlc
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
              OR new.hlc = ''
            THEN printf(
                '%015d-0000-server',
                MAX(
                    COALESCE(
                        CAST(ROUND((julianday(new.updated_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    ),
                    CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
                )
            )
            ELSE new.hlc
        END
    WHERE id = new.id;
END;

-- 2. Satellite `entry_content` triggers (bump parent `entries.sync_seq`)
CREATE TRIGGER entry_content_sync_seq_ai AFTER INSERT ON entry_content
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

CREATE TRIGGER entry_content_sync_seq_au
AFTER UPDATE OF content_html, content_type ON entry_content
WHEN old.content_html IS NOT new.content_html OR old.content_type IS NOT new.content_type
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

CREATE TRIGGER entry_content_sync_seq_ad AFTER DELETE ON entry_content
WHEN EXISTS (SELECT 1 FROM entries WHERE id = old.entry_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = old.entry_id;
END;

-- 3. Satellite `entry_state` triggers (maintain `entry_state.hlc` + bump parent `entries.sync_seq`)
CREATE TRIGGER entry_state_sync_seq_ai AFTER INSERT ON entry_state
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entry_state
    SET hlc = CASE
        WHEN new.hlc != '' THEN new.hlc
        ELSE printf(
            '%015d-0000-server',
            MAX(
                0,
                COALESCE(
                    CAST(ROUND((julianday(COALESCE(new.interaction_updated_at, new.updated_at)) - 2440587.5) * 86400000.0) AS INTEGER),
                    CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                )
            )
        )
    END
    WHERE entry_id = new.entry_id AND new.hlc = '';
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

CREATE TRIGGER entry_state_sync_seq_au
AFTER UPDATE OF inbox_state, read_at, progress, interaction_updated_at, hlc ON entry_state
WHEN old.inbox_state IS NOT new.inbox_state
  OR old.read_at IS NOT new.read_at
  OR old.progress IS NOT new.progress
  OR old.interaction_updated_at IS NOT new.interaction_updated_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entry_state
    SET hlc = printf(
        '%015d-0000-server',
        MAX(
            COALESCE(
                CAST(ROUND((julianday(COALESCE(new.interaction_updated_at, new.updated_at)) - 2440587.5) * 86400000.0) AS INTEGER),
                CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
            ),
            CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
        )
    )
    WHERE entry_id = new.entry_id AND (new.hlc = '' OR new.hlc IS old.hlc);
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

-- 4. Satellite `entry_identifiers` triggers (bump parent `entries.sync_seq`)
CREATE TRIGGER entry_identifiers_sync_seq_ai AFTER INSERT ON entry_identifiers
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

CREATE TRIGGER entry_identifiers_sync_seq_au
AFTER UPDATE OF scheme, value, entry_id ON entry_identifiers
WHEN old.scheme IS NOT new.scheme OR old.value IS NOT new.value OR old.entry_id IS NOT new.entry_id
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id IN (old.entry_id, new.entry_id);
END;

CREATE TRIGGER entry_identifiers_sync_seq_ad AFTER DELETE ON entry_identifiers
WHEN EXISTS (SELECT 1 FROM entries WHERE id = old.entry_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = old.entry_id;
END;

-- 5. Satellite `collection_specs` triggers (bump parent `entries.sync_seq`)
CREATE TRIGGER collection_specs_sync_seq_ai AFTER INSERT ON collection_specs
WHEN new.mode != 'dynamic' AND new.collection_role != 'system'
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

CREATE TRIGGER collection_specs_sync_seq_au
AFTER UPDATE OF slug, mode, collection_role, rule_json, show_in_lists, color ON collection_specs
WHEN old.slug IS NOT new.slug
  OR old.mode IS NOT new.mode
  OR old.collection_role IS NOT new.collection_role
  OR old.rule_json IS NOT new.rule_json
  OR old.show_in_lists IS NOT new.show_in_lists
  OR old.color IS NOT new.color
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

CREATE TRIGGER collection_specs_sync_seq_ad AFTER DELETE ON collection_specs
WHEN EXISTS (SELECT 1 FROM entries WHERE id = old.collection_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = old.collection_id;
END;

-- 6. Satellite `feed_sources` triggers (separate config `hlc` from poll telemetry; bump `entries.sync_seq`)
CREATE TRIGGER feed_sources_sync_seq_ai AFTER INSERT ON feed_sources
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE feed_sources
    SET hlc = printf(
        '%015d-0000-server',
        MAX(0, CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER))
    )
    WHERE collection_id = new.collection_id AND new.hlc = '';
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

CREATE TRIGGER feed_sources_sync_seq_au
AFTER UPDATE OF
    feed_url, source_type, feed_title, language, poll_interval, is_active,
    last_polled_at, last_polled_by, last_error, last_error_by, hlc
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
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE feed_sources
    SET hlc = printf(
        '%015d-0000-server',
        MAX(
            CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER),
            CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
        )
    )
    WHERE collection_id = new.collection_id
      AND (new.hlc = '' OR new.hlc IS old.hlc)
      AND (
          old.feed_url IS NOT new.feed_url
          OR old.source_type IS NOT new.source_type
          OR old.feed_title IS NOT new.feed_title
          OR old.language IS NOT new.language
          OR old.poll_interval IS NOT new.poll_interval
          OR old.is_active IS NOT new.is_active
          OR new.hlc = ''
      );
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

CREATE TRIGGER feed_sources_sync_seq_ad AFTER DELETE ON feed_sources
WHEN EXISTS (SELECT 1 FROM entries WHERE id = old.collection_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = old.collection_id;
END;

-- 7. Satellite remote `entry_media` (`local_path IS NULL` audio enclosures) triggers
CREATE TRIGGER entry_media_sync_seq_ai AFTER INSERT ON entry_media
WHEN new.local_path IS NULL AND EXISTS (SELECT 1 FROM entries WHERE id = new.entry_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

CREATE TRIGGER entry_media_sync_seq_au
AFTER UPDATE OF remote_url, duration, local_path ON entry_media
WHEN (old.local_path IS NULL OR new.local_path IS NULL)
  AND (old.remote_url IS NOT new.remote_url OR old.duration IS NOT new.duration)
  AND EXISTS (SELECT 1 FROM entries WHERE id = new.entry_id)
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.entry_id;
END;

-- 8. `collection_entries` triggers
CREATE TRIGGER collection_entries_sync_seq_ai AFTER INSERT ON collection_entries
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.added_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE collection_id = new.collection_id AND entry_id = new.entry_id;
END;

CREATE TRIGGER collection_entries_sync_seq_au
AFTER UPDATE OF position, added_at, hlc ON collection_entries
WHEN old.position IS NOT new.position
  OR old.added_at IS NOT new.added_at
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' AND new.hlc IS NOT old.hlc THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    COALESCE(
                        CAST(ROUND((julianday(new.added_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    ),
                    CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
                )
            )
        END
    WHERE collection_id = new.collection_id AND entry_id = new.entry_id;
END;

-- 9. `collection_generators` triggers
CREATE TRIGGER collection_generators_sync_seq_ai AFTER INSERT ON collection_generators
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE collection_generators
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.updated_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE collection_id = new.collection_id;
END;

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
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' AND new.hlc IS NOT old.hlc THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    COALESCE(
                        CAST(ROUND((julianday(new.updated_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    ),
                    CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
                )
            )
        END
    WHERE collection_id = new.collection_id;
END;

-- 10. `consumption_log` triggers
CREATE TRIGGER consumption_log_sync_seq_ai AFTER INSERT ON consumption_log
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE consumption_log
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.created_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE id = new.id;
END;

CREATE TRIGGER consumption_log_sync_seq_au
AFTER UPDATE OF ts, status, notes, hlc ON consumption_log
WHEN old.ts IS NOT new.ts
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

-- 11. `comparisons` triggers
CREATE TRIGGER comparisons_sync_seq_ai AFTER INSERT ON comparisons
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE comparisons
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1),
        hlc = CASE
            WHEN new.hlc != '' THEN new.hlc
            ELSE printf(
                '%015d-0000-server',
                MAX(
                    0,
                    COALESCE(
                        CAST(ROUND((julianday(new.created_at) - 2440587.5) * 86400000.0) AS INTEGER),
                        CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER)
                    )
                )
            )
        END
    WHERE id = new.id;
END;

-- 12. `sync_tombstones` triggers
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
