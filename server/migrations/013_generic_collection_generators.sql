-- Convert legacy built-in 1001_albums placeholder into generic manual list semantics:
-- * If untouched (0 members and generator disabled with no active draw), remove the empty
--   system placeholder so fresh/unused databases carry zero hardcoded 1001_albums rows.
-- * If already populated or active, promote it from `system` (`show_in_lists = 0`) to a
--   normal `list` (`show_in_lists = 1`) so it syncs and behaves like any manual list.

DELETE FROM entries
WHERE id = '9eb44de7-bdfb-5bea-85d1-be913c542d8b'
  AND NOT EXISTS (
      SELECT 1 FROM collection_entries
      WHERE collection_id = '9eb44de7-bdfb-5bea-85d1-be913c542d8b'
  )
  AND NOT EXISTS (
      SELECT 1 FROM collection_generators
      WHERE collection_id = '9eb44de7-bdfb-5bea-85d1-be913c542d8b'
        AND (enabled != 0 OR current_entry_id IS NOT NULL)
  );

UPDATE collection_specs
SET collection_role = 'list',
    show_in_lists = 1
WHERE collection_id = '9eb44de7-bdfb-5bea-85d1-be913c542d8b';

INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
SELECT 'slug', '1001_albums', id
FROM entries
WHERE id = '9eb44de7-bdfb-5bea-85d1-be913c542d8b';
