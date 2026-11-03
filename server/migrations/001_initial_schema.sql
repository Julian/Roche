CREATE TABLE IF NOT EXISTS feeds (
    id TEXT PRIMARY KEY,
    feed_type TEXT NOT NULL CHECK (feed_type IN ('rss', 'youtube', 'arxiv')),
    url TEXT NOT NULL UNIQUE,
    title TEXT,
    description TEXT,
    language TEXT,
    poll_interval INTEGER NOT NULL DEFAULT 30 CHECK (poll_interval > 0),
    last_polled DATETIME,
    last_error TEXT,
    is_active BOOLEAN NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    feed_id TEXT REFERENCES feeds(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    author TEXT,
    published_at DATETIME,
    summary TEXT,
    content_html TEXT,
    content_type TEXT NOT NULL DEFAULT 'article' CHECK (content_type IN ('article', 'video', 'paper', 'audio', 'pdf')),
    status TEXT NOT NULL DEFAULT 'unread' CHECK (status IN ('unread', 'archived')),
    is_favorite BOOLEAN NOT NULL DEFAULT 0,
    read_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_items_feed_url ON items(feed_id, url) WHERE feed_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_items_manual_url ON items(url) WHERE feed_id IS NULL;

CREATE TABLE IF NOT EXISTS tags (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    color TEXT,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE TABLE IF NOT EXISTS item_tags (
    item_id TEXT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, tag_id)
);

-- Note: item_id intentionally omits ON DELETE CASCADE so WorkerManager::run_media_cleaner
-- can discover orphaned media records, unlink their files on disk, and then delete the rows.
CREATE TABLE IF NOT EXISTS media (
    id TEXT PRIMARY KEY,
    item_id TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('video', 'audio', 'pdf', 'thumbnail')),
    path TEXT NOT NULL,
    file_size INTEGER CHECK (file_size IS NULL OR file_size >= 0),
    duration INTEGER CHECK (duration IS NULL OR duration >= 0),
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE TABLE IF NOT EXISTS activity_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('feed', 'item', 'tag', 'media')),
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('create', 'update', 'archive', 'unarchive', 'favorite', 'unfavorite', 'tag_add', 'tag_remove', 'delete')),
    timestamp DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE TABLE IF NOT EXISTS device_tokens (
    id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    last_used_at DATETIME
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    code TEXT PRIMARY KEY,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    expires_at DATETIME NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_items_feed_id ON items(feed_id);
CREATE INDEX IF NOT EXISTS idx_items_status ON items(status);
CREATE INDEX IF NOT EXISTS idx_items_published_at ON items(published_at);
CREATE INDEX IF NOT EXISTS idx_items_created_at ON items(created_at);
CREATE INDEX IF NOT EXISTS idx_item_tags_tag_id ON item_tags(tag_id);
CREATE INDEX IF NOT EXISTS idx_activity_log_entity ON activity_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_activity_log_timestamp ON activity_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_media_item_id ON media(item_id);
