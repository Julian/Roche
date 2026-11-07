-- Speed up default chronological sorting by effective publication timestamp.
CREATE INDEX IF NOT EXISTS idx_items_effective_published
    ON items(COALESCE(published_at, created_at) DESC, id DESC);
