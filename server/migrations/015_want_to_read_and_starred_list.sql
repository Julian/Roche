-- 015_want_to_read_and_starred_list.sql
--
-- 1. Seeds the manual "Want to Read" (`want:read`) collection for explicitly saved URLs, PDFs,
--    papers, and media items.
-- 2. Promotes "Starred" (`starred`) from a bespoke `system` collection to a normal `list`
--    (`collection_role = 'list'`, `show_in_lists = 1`).
-- 3. Migrates pre-existing manual non-feed Inbox items into `want:read` with `inbox_state = 'none'`
--    so the Feed Inbox remains a strictly read-only view of subscribed feeds.

CREATE TEMP TABLE _mig015_clock AS
SELECT current_seq AS prev_seq FROM sync_clock WHERE id = 1;

-- Seed `want:read` ("Want to Read", deterministic UUID for `system_collection:want:read`)
INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at, hlc, sync_seq)
VALUES (
    '2ef71101-a716-5331-9515-cb7b5ce8b3c3',
    'collection',
    'Want to Read',
    '1970-01-01T00:00:00+00:00',
    '1970-01-01T00:00:00+00:00',
    '000000000000000-0000-server',
    0
);

INSERT INTO collection_specs (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
VALUES ('2ef71101-a716-5331-9515-cb7b5ce8b3c3', 'want:read', 'manual', 'want', NULL, 0)
ON CONFLICT(collection_id) DO UPDATE SET
    slug = 'want:read',
    mode = 'manual',
    collection_role = 'want',
    show_in_lists = 0;

-- Promote `starred` to a normal manual list (`collection_role = 'list'`, `show_in_lists = 1`)
UPDATE collection_specs
SET collection_role = 'list',
    show_in_lists = 1
WHERE collection_id = '533b1315-6e2e-57d3-ba85-dc88531c5a0b';

-- Ensure `slug` entry_identifiers exist for seeded manual collections
INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id) VALUES
    ('slug', 'want:read', '2ef71101-a716-5331-9515-cb7b5ce8b3c3'),
    ('slug', 'starred', '533b1315-6e2e-57d3-ba85-dc88531c5a0b'),
    ('slug', 'up_next', '3864ff37-5ed3-5256-9380-f85ff8054f1c');

-- Keep seeded baseline collections at `sync_seq = 0` when untouched so fresh DBs start at `current_seq = 0`
UPDATE entries
SET sync_seq = 0
WHERE id IN ('2ef71101-a716-5331-9515-cb7b5ce8b3c3', '533b1315-6e2e-57d3-ba85-dc88531c5a0b')
  AND (SELECT prev_seq FROM _mig015_clock) = 0;

UPDATE sync_clock
SET current_seq = (SELECT prev_seq FROM _mig015_clock)
WHERE id = 1
  AND (SELECT prev_seq FROM _mig015_clock) = 0;

DROP TABLE _mig015_clock;

-- Migrate pre-existing manual non-feed unread Inbox items into `want:read`
INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
SELECT '2ef71101-a716-5331-9515-cb7b5ce8b3c3', es.entry_id, COALESCE(es.updated_at, e.created_at)
FROM entry_state es
JOIN entries e ON e.id = es.entry_id
WHERE e.merged_into_id IS NULL
  AND es.inbox_state = 'unread'
  AND NOT EXISTS (
      SELECT 1 FROM collection_entries ce_f
      LEFT JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
      LEFT JOIN collection_specs cs_f ON cs_f.collection_id = ce_f.collection_id
      WHERE ce_f.entry_id = es.entry_id
        AND (fs_f.collection_id IS NOT NULL OR cs_f.collection_role = 'feed')
  );

-- Clear `inbox_state` on all non-feed entries so Feed Inbox only holds feed-emitted items
UPDATE entry_state
SET inbox_state = 'none'
WHERE inbox_state IN ('unread', 'dismissed')
  AND NOT EXISTS (
      SELECT 1 FROM collection_entries ce_f
      LEFT JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
      LEFT JOIN collection_specs cs_f ON cs_f.collection_id = ce_f.collection_id
      WHERE ce_f.entry_id = entry_state.entry_id
        AND (fs_f.collection_id IS NOT NULL OR cs_f.collection_role = 'feed')
  );
