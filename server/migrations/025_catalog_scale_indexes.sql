-- Scale indexes for large catalogs (30k+ songs, 200k+ consumption_log rows, large manual lists).

-- Fast artist lookup by LOWER(title) and alphabetical pagination for dynamic kind lists.
CREATE INDEX IF NOT EXISTS idx_entries_kind_lower_title_id
    ON entries(kind, LOWER(title), id)
    WHERE merged_into_id IS NULL;

-- Fast recent thumbnail previews for dynamic kind lists without scanning consumption_log.
CREATE INDEX IF NOT EXISTS idx_entries_kind_thumb_updated
    ON entries(kind, updated_at DESC, id)
    WHERE merged_into_id IS NULL AND thumbnail_url IS NOT NULL;

-- Fast bounded entry detail play history and entries-driven Diary queries.
CREATE INDEX IF NOT EXISTS idx_consumption_log_entry_ts_iso
    ON consumption_log(entry_id, strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC);

-- Fast status-filtered chronological Diary queries (e.g. Songs / All with status filter).
CREATE INDEX IF NOT EXISTS idx_consumption_log_status_ts_iso
    ON consumption_log(status, strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC);

-- Fast notes-search branch in search_diary_logs without scanning rows where notes IS NULL.
CREATE INDEX IF NOT EXISTS idx_consumption_log_notes_id
    ON consumption_log(id)
    WHERE notes IS NOT NULL;
