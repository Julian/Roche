-- 022_simplify_collection_specs.sql
--
-- Simplifies `collection_specs` by removing the redundant `mode` and `show_in_lists` columns:
-- * Dynamic collections are uniquely identified by `rule_json IS NOT NULL` (non-blank).
-- * Feed collections are uniquely identified by `collection_role = 'feed'` (paired with `feed_sources`).
-- * Lists shown on `/lists` are uniquely identified by `collection_role = 'list'`.

CREATE TABLE collection_specs_new (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    slug TEXT UNIQUE,
    collection_role TEXT NOT NULL DEFAULT 'list'
        CHECK (collection_role IN ('list', 'want', 'system', 'tag', 'feed')),
    rule_json TEXT CHECK (rule_json IS NULL OR LENGTH(TRIM(rule_json)) > 0),
    color TEXT
) STRICT;

INSERT INTO collection_specs_new (collection_id, slug, collection_role, rule_json, color)
SELECT
    collection_id,
    slug,
    collection_role,
    CASE
        WHEN mode = 'dynamic' THEN NULLIF(TRIM(rule_json), '')
        ELSE NULL
    END,
    color
FROM collection_specs
WHERE collection_id IN (SELECT id FROM entries);

DROP TABLE collection_specs;
ALTER TABLE collection_specs_new RENAME TO collection_specs;

CREATE INDEX idx_collection_specs_role ON collection_specs(collection_role);

-- Recreate satellite `collection_specs` triggers (bump parent `entries.sync_seq`)
CREATE TRIGGER collection_specs_sync_seq_ai AFTER INSERT ON collection_specs
WHEN new.rule_json IS NULL AND new.collection_role != 'system'
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;

CREATE TRIGGER collection_specs_sync_seq_au
AFTER UPDATE OF slug, collection_role, rule_json, color ON collection_specs
WHEN old.slug IS NOT new.slug
  OR old.collection_role IS NOT new.collection_role
  OR old.rule_json IS NOT new.rule_json
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
