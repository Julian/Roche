-- 010_unified_entries_model.sql
-- Unifies feeds, items, tags, item_tags, and media into the universal entries & collections model,
-- and adds native support for collection_generators, consumption_log, and k-way comparisons.

DROP TRIGGER IF EXISTS items_fts_ai;
DROP TRIGGER IF EXISTS items_fts_ad;
DROP TRIGGER IF EXISTS items_fts_au;
DROP TABLE IF EXISTS items_fts;

CREATE TABLE entries (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN (
        'article', 'paper', 'video', 'podcast_episode', 'song', 'movie', 'book', 'game',
        'album', 'tv', 'artist', 'podcast', 'publication', 'channel', 'collection'
    )),
    title TEXT NOT NULL,
    creators_json TEXT NOT NULL DEFAULT '[]',
    url TEXT,
    published_at DATETIME,
    release_year INTEGER,
    summary TEXT,
    thumbnail_url TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    merged_into_id TEXT REFERENCES entries(id) ON DELETE SET NULL,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE INDEX idx_entries_kind ON entries(kind) WHERE merged_into_id IS NULL;
CREATE INDEX idx_entries_published_sort ON entries(COALESCE(published_at, created_at) DESC, id DESC);
CREATE INDEX idx_entries_updated_at ON entries(updated_at, id);
CREATE INDEX idx_entries_url ON entries(url) WHERE url IS NOT NULL;
CREATE INDEX idx_entries_merged_into ON entries(merged_into_id) WHERE merged_into_id IS NOT NULL;

CREATE TABLE entry_identifiers (
    scheme TEXT NOT NULL,
    value TEXT NOT NULL,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    PRIMARY KEY (scheme, value)
);

CREATE INDEX idx_entry_identifiers_entry_id ON entry_identifiers(entry_id);

CREATE TABLE entry_content (
    entry_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    content_html TEXT,
    content_type TEXT NOT NULL DEFAULT 'article' CHECK (content_type IN ('article', 'video', 'paper', 'audio', 'pdf'))
);

-- Note: entry_id intentionally omits ON DELETE CASCADE so WorkerManager::run_media_cleaner
-- can discover orphaned local cached files (local_path IS NOT NULL), unlink them on disk,
-- and then delete the rows. Remote-only enclosures (local_path IS NULL) are cleaned up via trigger.
CREATE TABLE entry_media (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('video', 'audio', 'pdf', 'thumbnail')),
    remote_url TEXT,
    local_path TEXT,
    file_size INTEGER CHECK (file_size IS NULL OR file_size >= 0),
    duration INTEGER CHECK (duration IS NULL OR duration >= 0),
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE INDEX idx_entry_media_entry_id ON entry_media(entry_id);
CREATE INDEX idx_entry_media_local_path ON entry_media(entry_id) WHERE local_path IS NOT NULL;

CREATE TABLE entry_state (
    entry_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    inbox_state TEXT NOT NULL DEFAULT 'none' CHECK (inbox_state IN ('none', 'unread', 'dismissed')),
    read_at DATETIME,
    progress REAL NOT NULL DEFAULT 0.0 CHECK (progress >= 0.0 AND progress <= 1.0),
    interaction_updated_at DATETIME,
    updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE INDEX idx_entry_state_inbox ON entry_state(inbox_state, read_at);

CREATE TABLE collection_specs (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    slug TEXT UNIQUE,
    mode TEXT NOT NULL CHECK (mode IN ('manual', 'feed', 'dynamic')),
    collection_role TEXT NOT NULL DEFAULT 'list' CHECK (collection_role IN ('list', 'want', 'system', 'tag', 'feed')),
    rule_json TEXT,
    show_in_lists BOOLEAN NOT NULL DEFAULT 0,
    color TEXT
);

CREATE INDEX idx_collection_specs_role ON collection_specs(collection_role, mode);

CREATE TABLE collection_entries (
    collection_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    position REAL,
    added_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    PRIMARY KEY (collection_id, entry_id)
);

CREATE INDEX idx_collection_entries_entry_id ON collection_entries(entry_id);
CREATE INDEX idx_collection_entries_pos ON collection_entries(collection_id, position, added_at);

CREATE TABLE feed_sources (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    feed_url TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL CHECK (source_type IN ('rss', 'podcast', 'youtube', 'arxiv')),
    feed_title TEXT,
    language TEXT,
    poll_interval INTEGER NOT NULL DEFAULT 30 CHECK (poll_interval > 0),
    is_active BOOLEAN NOT NULL DEFAULT 1,
    last_polled_at DATETIME,
    last_polled_by TEXT,
    last_error TEXT,
    last_error_by TEXT
);

CREATE TABLE collection_generators (
    collection_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT 0,
    current_entry_id TEXT REFERENCES entries(id) ON DELETE SET NULL,
    drawn_at DATETIME,
    updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE TABLE consumption_log (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    ts DATETIME NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('started', 'consumed', 'abandoned')),
    notes TEXT,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE INDEX idx_consumption_log_entry_status ON consumption_log(entry_id, status);
CREATE INDEX idx_consumption_log_ts ON consumption_log(ts DESC);
CREATE INDEX idx_consumption_log_created_at ON consumption_log(created_at);

CREATE TABLE comparisons (
    id TEXT PRIMARY KEY,
    ts DATETIME NOT NULL,
    scope_collection_id TEXT REFERENCES entries(id) ON DELETE CASCADE,
    ordering_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

CREATE INDEX idx_comparisons_scope_ts ON comparisons(scope_collection_id, ts);
CREATE INDEX idx_comparisons_created_at ON comparisons(created_at);

-- Broaden activity_log and sync_tombstones entity/action constraints for unified catalog entities
CREATE TABLE activity_log_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('feed', 'item', 'tag', 'media', 'entry', 'collection', 'consumption_log', 'comparison')),
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('create', 'update', 'archive', 'unarchive', 'favorite', 'unfavorite', 'tag_add', 'tag_remove', 'delete')),
    timestamp DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))
);

INSERT INTO activity_log_new (id, entity_type, entity_id, action, timestamp)
SELECT id, entity_type, entity_id, action, timestamp FROM activity_log;

DROP TABLE activity_log;
ALTER TABLE activity_log_new RENAME TO activity_log;
CREATE INDEX idx_activity_log_entity ON activity_log(entity_type, entity_id);
CREATE INDEX idx_activity_log_timestamp ON activity_log(timestamp);

CREATE TABLE sync_tombstones_new (
    entity_type TEXT NOT NULL CHECK (entity_type IN ('feed', 'item', 'tag', 'entry', 'collection_entry', 'consumption_log')),
    natural_key TEXT NOT NULL,
    deleted_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')),
    PRIMARY KEY (entity_type, natural_key)
);

INSERT INTO sync_tombstones_new (entity_type, natural_key, deleted_at)
SELECT entity_type, natural_key, deleted_at FROM sync_tombstones;

DROP TABLE sync_tombstones;
ALTER TABLE sync_tombstones_new RENAME TO sync_tombstones;
CREATE INDEX idx_sync_tombstones_deleted_at ON sync_tombstones(deleted_at);

-- Seed system manual collections (deterministic UUIDs from roche:entity:v1:system_collection:<slug>)
INSERT INTO entries (id, kind, title) VALUES
    ('533b1315-6e2e-57d3-ba85-dc88531c5a0b', 'collection', 'Starred'),
    ('3864ff37-5ed3-5256-9380-f85ff8054f1c', 'collection', 'Up Next'),
    ('9eb44de7-bdfb-5bea-85d1-be913c542d8b', 'collection', '1001 Albums');

INSERT INTO collection_specs (collection_id, slug, mode, collection_role, rule_json, show_in_lists) VALUES
    ('533b1315-6e2e-57d3-ba85-dc88531c5a0b', 'starred', 'manual', 'system', NULL, 0),
    ('3864ff37-5ed3-5256-9380-f85ff8054f1c', 'up_next', 'manual', 'system', NULL, 0),
    ('9eb44de7-bdfb-5bea-85d1-be913c542d8b', '1001_albums', 'manual', 'system', NULL, 0);

INSERT INTO collection_generators (collection_id, enabled) VALUES
    ('9eb44de7-bdfb-5bea-85d1-be913c542d8b', 0);

-- Seed system dynamic lists (all consumed entries of each concrete kind)
INSERT INTO entries (id, kind, title) VALUES
    ('0d74bce5-771c-5122-bf2d-d7dc3f68a1d4', 'collection', 'Movies'),
    ('bffa52e5-0677-5308-aa74-201cac815feb', 'collection', 'TV'),
    ('d2aa0f2d-1cf3-5dbe-bf23-34d17cdad750', 'collection', 'Artists'),
    ('636837ac-f2e1-51fb-ad08-5b46140ac321', 'collection', 'Albums'),
    ('175c211c-17bf-523e-a1a9-6a22193b15e9', 'collection', 'Songs'),
    ('1c56766b-e5a9-56c9-ae4a-c673b5b11de6', 'collection', 'Books'),
    ('352c21a8-5ca1-5cc9-bfa1-c86f4f6b3b4c', 'collection', 'Podcasts'),
    ('7193be63-2096-5374-a56d-a5f259f61a52', 'collection', 'Podcast Episodes'),
    ('f0755a78-43d6-56aa-b2e5-949a40a5f7af', 'collection', 'Games'),
    ('8221ef29-2a87-5e0d-a26d-486fdec2e7fb', 'collection', 'Articles'),
    ('f735d41a-f818-5604-968d-3be29348e322', 'collection', 'Papers'),
    ('797f9bbe-7e01-5d2d-9770-4c0c6ef4acee', 'collection', 'Videos'),
    ('364c283b-0f86-517b-bbe1-08d47489b42f', 'collection', 'Channels'),
    ('6504f243-80e9-5d20-9f7a-30f82c68a3b3', 'collection', 'Publications');

INSERT INTO collection_specs (collection_id, slug, mode, collection_role, rule_json, show_in_lists) VALUES
    ('0d74bce5-771c-5122-bf2d-d7dc3f68a1d4', 'movies', 'dynamic', 'list', '{"kind":"movie","consumed":true}', 1),
    ('bffa52e5-0677-5308-aa74-201cac815feb', 'tv', 'dynamic', 'list', '{"kind":"tv","consumed":true}', 1),
    ('d2aa0f2d-1cf3-5dbe-bf23-34d17cdad750', 'artists', 'dynamic', 'list', '{"kind":"artist","consumed":true}', 1),
    ('636837ac-f2e1-51fb-ad08-5b46140ac321', 'albums', 'dynamic', 'list', '{"kind":"album","consumed":true}', 1),
    ('175c211c-17bf-523e-a1a9-6a22193b15e9', 'songs', 'dynamic', 'list', '{"kind":"song","consumed":true}', 1),
    ('1c56766b-e5a9-56c9-ae4a-c673b5b11de6', 'books', 'dynamic', 'list', '{"kind":"book","consumed":true}', 1),
    ('352c21a8-5ca1-5cc9-bfa1-c86f4f6b3b4c', 'podcasts', 'dynamic', 'list', '{"kind":"podcast","consumed":true}', 1),
    ('7193be63-2096-5374-a56d-a5f259f61a52', 'podcast_episodes', 'dynamic', 'list', '{"kind":"podcast_episode","consumed":true}', 1),
    ('f0755a78-43d6-56aa-b2e5-949a40a5f7af', 'games', 'dynamic', 'list', '{"kind":"game","consumed":true}', 1),
    ('8221ef29-2a87-5e0d-a26d-486fdec2e7fb', 'articles', 'dynamic', 'list', '{"kind":"article","consumed":true}', 1),
    ('f735d41a-f818-5604-968d-3be29348e322', 'papers', 'dynamic', 'list', '{"kind":"paper","consumed":true}', 1),
    ('797f9bbe-7e01-5d2d-9770-4c0c6ef4acee', 'videos', 'dynamic', 'list', '{"kind":"video","consumed":true}', 1),
    ('364c283b-0f86-517b-bbe1-08d47489b42f', 'channels', 'dynamic', 'list', '{"kind":"channel","consumed":true}', 1),
    ('6504f243-80e9-5d20-9f7a-30f82c68a3b3', 'publications', 'dynamic', 'list', '{"kind":"publication","consumed":true}', 1);

INSERT INTO entry_identifiers (scheme, value, entry_id)
SELECT 'slug', slug, collection_id FROM collection_specs WHERE slug IS NOT NULL;

-- Migrate existing feeds into entries + collection_specs + feed_sources
INSERT INTO entries (id, kind, title, summary, created_at, updated_at)
SELECT
    f.id,
    CASE f.feed_type
        WHEN 'youtube' THEN 'channel'
        WHEN 'arxiv' THEN 'collection'
        ELSE CASE
            WHEN EXISTS (
                SELECT 1 FROM items i
                WHERE i.feed_id = f.id AND (i.content_type = 'audio' OR i.audio_url IS NOT NULL)
            ) THEN 'podcast'
            ELSE 'publication'
        END
    END,
    COALESCE(NULLIF(TRIM(f.title), ''), f.url),
    f.description,
    f.created_at,
    f.updated_at
FROM feeds f;

INSERT INTO collection_specs (collection_id, mode, collection_role, show_in_lists)
SELECT f.id, 'feed', 'feed', 0 FROM feeds f;

INSERT INTO feed_sources (
    collection_id, feed_url, source_type, feed_title, language, poll_interval,
    is_active, last_polled_at, last_polled_by, last_error, last_error_by
)
SELECT
    f.id,
    f.url,
    CASE
        WHEN f.feed_type = 'rss' AND EXISTS (
            SELECT 1 FROM items i
            WHERE i.feed_id = f.id AND (i.content_type = 'audio' OR i.audio_url IS NOT NULL)
        ) THEN 'podcast'
        ELSE f.feed_type
    END,
    f.title,
    f.language,
    f.poll_interval,
    f.is_active,
    f.last_polled,
    f.last_polled_by,
    f.last_error,
    f.last_error_by
FROM feeds f;

INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
SELECT 'feed_url', LOWER(TRIM(f.url)), f.id FROM feeds f;

-- Migrate existing tags into entries + collection_specs
INSERT INTO entries (id, kind, title, created_at, updated_at)
SELECT t.id, 'collection', t.name, t.created_at, t.created_at FROM tags t;

INSERT INTO collection_specs (collection_id, slug, mode, collection_role, show_in_lists, color)
SELECT t.id, 'tag:' || LOWER(TRIM(t.name)), 'manual', 'tag', 0, t.color FROM tags t;

INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
SELECT 'tag', LOWER(TRIM(t.name)), t.id FROM tags t;

-- Migrate existing items into entries + entry_content + entry_state + collection_entries + entry_media
INSERT INTO entries (
    id, kind, title, creators_json, url, published_at, summary, thumbnail_url, created_at, updated_at
)
SELECT
    i.id,
    CASE i.content_type
        WHEN 'video' THEN 'video'
        WHEN 'paper' THEN 'paper'
        WHEN 'pdf' THEN 'paper'
        WHEN 'audio' THEN 'podcast_episode'
        ELSE 'article'
    END,
    i.title,
    CASE
        WHEN i.author IS NOT NULL AND TRIM(i.author) != '' THEN json_array(TRIM(i.author))
        ELSE '[]'
    END,
    i.url,
    i.published_at,
    i.summary,
    i.thumbnail_url,
    i.created_at,
    i.updated_at
FROM items i;

INSERT INTO entry_content (entry_id, content_html, content_type)
SELECT i.id, i.content_html, i.content_type FROM items i;

INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
SELECT
    i.id,
    CASE i.status WHEN 'archived' THEN 'dismissed' ELSE 'unread' END,
    i.read_at,
    i.read_progress,
    i.interaction_updated_at,
    i.updated_at
FROM items i;

INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
SELECT 'url', LOWER(TRIM(i.url)), i.id FROM items i;

INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
SELECT i.feed_id, i.id, i.created_at FROM items i WHERE i.feed_id IS NOT NULL;

INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
SELECT '533b1315-6e2e-57d3-ba85-dc88531c5a0b', i.id, COALESCE(i.interaction_updated_at, i.updated_at)
FROM items i
WHERE i.is_favorite = 1;

INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
SELECT it.tag_id, it.item_id, strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now')
FROM item_tags it;

INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
SELECT
    'enclosure:' || i.id,
    i.id,
    'audio',
    i.audio_url,
    NULL,
    NULL,
    i.duration,
    i.created_at
FROM items i
WHERE i.audio_url IS NOT NULL OR i.duration IS NOT NULL;

INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
SELECT m.id, m.item_id, m.media_type, NULL, m.path, m.file_size, m.duration, m.created_at
FROM media m;

DROP TABLE item_tags;
DROP TABLE tags;
DROP TABLE media;
DROP TABLE items;
DROP TABLE feeds;

-- Unified FTS5 index synced via O(log N) rowid triggers over entries + entry_content
CREATE VIRTUAL TABLE entries_fts USING fts5(
    entry_id UNINDEXED,
    title,
    creators,
    summary,
    content_html,
    tokenize='porter unicode61 remove_diacritics 2'
);

INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
SELECT
    e.rowid,
    e.id,
    e.title,
    e.creators_json,
    COALESCE(e.summary, ''),
    COALESCE(ec.content_html, '')
FROM entries e
LEFT JOIN entry_content ec ON ec.entry_id = e.id
WHERE e.merged_into_id IS NULL;

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
