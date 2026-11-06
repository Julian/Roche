CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
    title,
    author,
    summary,
    content_html,
    content='items',
    content_rowid='rowid',
    tokenize='porter unicode61 remove_diacritics 2'
);

INSERT INTO items_fts(items_fts) VALUES('rebuild');

CREATE TRIGGER IF NOT EXISTS items_fts_ai AFTER INSERT ON items BEGIN
    INSERT INTO items_fts(rowid, title, author, summary, content_html)
    VALUES (new.rowid, new.title, new.author, new.summary, new.content_html);
END;

CREATE TRIGGER IF NOT EXISTS items_fts_ad AFTER DELETE ON items BEGIN
    INSERT INTO items_fts(items_fts, rowid, title, author, summary, content_html)
    VALUES ('delete', old.rowid, old.title, old.author, old.summary, old.content_html);
END;

CREATE TRIGGER IF NOT EXISTS items_fts_au AFTER UPDATE OF title, author, summary, content_html ON items BEGIN
    INSERT INTO items_fts(items_fts, rowid, title, author, summary, content_html)
    VALUES ('delete', old.rowid, old.title, old.author, old.summary, old.content_html);
    INSERT INTO items_fts(rowid, title, author, summary, content_html)
    VALUES (new.rowid, new.title, new.author, new.summary, new.content_html);
END;
