use std::collections::{BTreeMap, HashMap, HashSet};

use chrono::{DateTime, Datelike, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use roche_core::identity_norm::{
    choose_deterministic_entry_id, normalize_identifier_scheme, normalize_identifier_value,
    normalize_spotify_identifier, strip_album_edition_suffix,
};

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, ConsumptionLogRow, EntryDetailView,
        EntryIdentifier, RawEntryRow, UpdateEntryInput, UpsertEntryInput,
        collection_immutable_reason, is_dynamic_rule, raw_entry_columns,
    },
};
use crate::db::{
    WriteTx,
    entries_core::{self, EntryInsertParams},
    sync::{SyncRepo, deterministic_title_entry_id},
    types::{
        ActivityAction, ActivityEntityType, CollectionRole, ConsumptionStatus, ContentType,
        EntryKind,
    },
};

impl CatalogRepo<'_> {
    pub async fn get_entry(&self, id: &str) -> CatalogResult<Option<CatalogEntry>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let entry = Self::get_entry_in_tx(tx.conn(), id).await?;
        tx.commit().await?;
        Ok(entry)
    }

    pub(super) async fn get_entry_in_tx(
        conn: &mut SqliteConnection,
        id: &str,
    ) -> CatalogResult<Option<CatalogEntry>> {
        let Some(raw) = Self::get_entry_raw_in_tx(&mut *conn, id).await? else {
            return Ok(None);
        };
        let idents = Self::load_identifiers_for_one_in_tx(&mut *conn, &raw.id).await?;
        Ok(Some(raw.into_entry(idents)))
    }

    pub(super) async fn get_entry_raw_in_tx(
        conn: &mut SqliteConnection,
        id: &str,
    ) -> CatalogResult<Option<RawEntryRow>> {
        let Some(target_id) = Self::follow_merged_id_in_tx(&mut *conn, id).await? else {
            return Ok(None);
        };
        let row: Option<RawEntryRow> = sqlx::query_as(concat!(
            "SELECT ",
            raw_entry_columns!(),
            " FROM entries WHERE id = ? AND merged_into_id IS NULL"
        ))
        .bind(&target_id)
        .fetch_optional(&mut *conn)
        .await?;
        Ok(row)
    }

    pub async fn get_entry_detail(&self, id: &str) -> CatalogResult<Option<EntryDetailView>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let Some(entry) = Self::get_entry_in_tx(tx.conn(), id).await? else {
            return Ok(None);
        };
        let child_rows: Vec<RawEntryRow> = sqlx::query_as(concat!(
            "SELECT ",
            raw_entry_columns!(e),
            " FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
             ORDER BY ce.position ASC NULLS LAST, COALESCE(e.release_year, 0) DESC, LOWER(e.title) ASC"
        ))
        .bind(&entry.id)
        .fetch_all(tx.conn())
        .await?;
        let children = Self::hydrate_entries_in_tx(tx.conn(), child_rows).await?;

        let parent_rows: Vec<RawEntryRow> = sqlx::query_as(concat!(
            "SELECT ",
            raw_entry_columns!(e),
            " FROM collection_entries ce
             JOIN entries e ON e.id = ce.collection_id
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE ce.entry_id = ?
               AND e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'tag'))
             ORDER BY LOWER(e.title) ASC"
        ))
        .bind(&entry.id)
        .fetch_all(tx.conn())
        .await?;
        let parents = Self::hydrate_entries_in_tx(tx.conn(), parent_rows).await?;

        let log_history = sqlx::query_as!(
            ConsumptionLogRow,
            r#"SELECT id as "id!", entry_id as "entry_id!", ts as "ts!: DateTime<Utc>",
                      status as "status!: ConsumptionStatus", notes as "notes?",
                      created_at as "created_at!: DateTime<Utc>"
               FROM consumption_log
               WHERE entry_id = ?
               ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC"#,
            &entry.id,
        )
        .fetch_all(tx.conn())
        .await?;

        tx.commit().await?;
        Ok(Some(EntryDetailView { entry, children, parents, log_history }))
    }

    pub async fn search_entries(
        &self,
        query: Option<&str>,
        kind: Option<EntryKind>,
        limit: usize,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        let cap = i64::try_from(limit.clamp(1, 500)).unwrap_or(100);
        let trimmed = query.map(str::trim).filter(|s| !s.is_empty());
        let mut qb = QueryBuilder::<Sqlite>::new(concat!(
            "SELECT ",
            raw_entry_columns!(e),
            " FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))"
        ));
        if let Some(k) = kind {
            qb.push(" AND e.kind = ").push_bind(k);
        }
        if let Some(q) = trimmed {
            let terms = crate::db::ItemFilter::tokenize_search_terms(q);
            if terms.is_empty() {
                return Ok(Vec::new());
            }
            let fts_query = terms
                .iter()
                .map(|t| format!("{{title creators summary}}: {}", t.fts_clause))
                .collect::<Vec<_>>()
                .join(" AND ");
            let bounded_q: String = q.chars().take(256).collect();
            let escaped = crate::db::escape_like_literal(&bounded_q);
            let ident_pattern = format!("%{escaped}%");
            qb.push(" AND (e.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ")
                .push_bind(fts_query)
                .push(") OR EXISTS (SELECT 1 FROM entry_identifiers ei WHERE ei.entry_id = e.id AND ei.value LIKE ")
                .push_bind(ident_pattern)
                .push(" ESCAPE '\\'))");
        }
        qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', e.updated_at) DESC, LOWER(e.title) ASC LIMIT ")
            .push_bind(cap);
        let mut tx = crate::db::begin_read(self.pool).await?;
        let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(tx.conn()).await?;
        let entries = Self::hydrate_entries_in_tx(tx.conn(), rows).await?;
        tx.commit().await?;
        Ok(entries)
    }

    pub async fn find_artist_ids_by_names(
        &self,
        names: &[String],
    ) -> CatalogResult<HashMap<String, String>> {
        let mut seen = HashSet::new();
        for name in names {
            let trimmed = name.trim();
            if !trimmed.is_empty() {
                seen.insert(trimmed.to_lowercase());
            }
        }
        if seen.is_empty() {
            return Ok(HashMap::new());
        }

        // Fold titles with Rust's full Unicode `.to_lowercase()` rather than SQLite's ASCII-only
        // `LOWER()` so non-ASCII artists (e.g. `"Édith Piaf"`, `"Ólafur Arnalds"`) match reliably.
        let rows = sqlx::query!(
            r#"SELECT title as "title!", id as "id!" FROM entries
               WHERE kind = 'artist' AND merged_into_id IS NULL
               ORDER BY updated_at DESC"#,
        )
        .fetch_all(self.pool)
        .await?;

        let mut out = HashMap::with_capacity(seen.len());
        for row in rows {
            let lower_title = row.title.trim().to_lowercase();
            if seen.contains(&lower_title) {
                out.entry(lower_title).or_insert(row.id);
            }
        }
        Ok(out)
    }

    /// Resolves an entry by UUID, external identifier value, or exact case-insensitive title,
    /// mirroring Great's `resolve_item` semantics (with optional `kind` and `only_collection_id` scoping).
    pub async fn resolve_entry(
        &self,
        query: &str,
        kind: Option<EntryKind>,
        only_collection_id: Option<&str>,
    ) -> CatalogResult<CatalogEntry> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw = Self::resolve_entry_raw_in_tx(tx.conn(), query, kind, only_collection_id).await?;
        let idents = Self::load_identifiers_for_one_in_tx(tx.conn(), &raw.id).await?;
        tx.commit().await?;
        Ok(raw.into_entry(idents))
    }

    pub(super) async fn resolve_entry_raw_in_tx(
        conn: &mut SqliteConnection,
        query: &str,
        kind: Option<EntryKind>,
        only_collection_id: Option<&str>,
    ) -> CatalogResult<RawEntryRow> {
        let q = query.trim();
        if q.is_empty() {
            return Err(CatalogError::InvalidInput("Lookup query cannot be empty".to_owned()));
        }

        if let Some(by_id) = Self::get_entry_raw_in_tx(&mut *conn, q).await?
            && kind.is_none_or(|k| by_id.kind == k)
        {
            if let Some(col_id) = only_collection_id {
                let in_col = sqlx::query_scalar!(
                    r#"SELECT 1 as "found!: i64" FROM collection_entries WHERE collection_id = ? AND entry_id = ?"#,
                    col_id,
                    &by_id.id,
                )
                .fetch_optional(&mut *conn)
                .await?;
                if in_col.is_some() {
                    return Ok(by_id);
                }
            } else {
                return Ok(by_id);
            }
        }

        let mut qb = QueryBuilder::<Sqlite>::new(concat!(
            "SELECT DISTINCT ",
            raw_entry_columns!(e),
            " FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))"
        ));
        if let Some(k) = kind {
            qb.push(" AND e.kind = ").push_bind(k);
        }
        if let Some(col_id) = only_collection_id {
            qb.push(" AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.collection_id = ")
                .push_bind(col_id)
                .push(" AND ce.entry_id = e.id)");
        }
        let norm_spotify = kind.map(|k| normalize_spotify_identifier(k, q));
        let norm_url = (q.starts_with("http://") || q.starts_with("https://"))
            .then(|| normalize_identifier_value(kind.unwrap_or(EntryKind::Article), "url", q));
        qb.push(" AND (LOWER(e.title) = LOWER(").push_bind(q).push(")");
        qb.push(" OR EXISTS (SELECT 1 FROM entry_identifiers ei WHERE ei.entry_id = e.id AND (ei.value = ")
            .push_bind(q)
            .push(" OR LOWER(ei.value) = LOWER(")
            .push_bind(q)
            .push(")");
        if let Some(sp) = norm_spotify {
            qb.push(" OR ei.value = ").push_bind(sp);
        }
        if let Some(cu) = norm_url {
            qb.push(" OR ei.value = ").push_bind(cu);
        }
        qb.push("))) ORDER BY e.created_at ASC, e.id ASC");

        let mut candidates: Vec<RawEntryRow> = qb.build_query_as().fetch_all(&mut *conn).await?;

        // Fallback for album edition-stripped title lookup
        if candidates.is_empty() && kind == Some(EntryKind::Album) {
            candidates =
                Self::resolve_album_edition_fallback_in_tx(&mut *conn, q, only_collection_id)
                    .await?;
        }

        if candidates.is_empty() {
            return Err(CatalogError::NotFound(format!("Entry '{q}'")));
        }
        if candidates.len() > 1 {
            let matched_candidates = candidates
                .iter()
                .map(|c| format!("'{}' (kind={}, id={})", c.title, c.kind.as_str(), c.id))
                .collect();
            return Err(CatalogError::AmbiguousLookup {
                query: q.to_owned(),
                candidates: matched_candidates,
            });
        }
        candidates.into_iter().next().ok_or_else(|| CatalogError::NotFound(format!("Entry '{q}'")))
    }

    async fn resolve_album_edition_fallback_in_tx(
        conn: &mut SqliteConnection,
        query: &str,
        only_collection_id: Option<&str>,
    ) -> CatalogResult<Vec<RawEntryRow>> {
        let stripped_q = strip_album_edition_suffix(query).to_lowercase();
        let mut aqb = QueryBuilder::<Sqlite>::new(concat!(
            "SELECT ",
            raw_entry_columns!(),
            " FROM entries WHERE kind = 'album' AND merged_into_id IS NULL"
        ));
        if let Some(col_id) = only_collection_id {
            aqb.push(" AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.collection_id = ")
                .push_bind(col_id)
                .push(" AND ce.entry_id = entries.id)");
        }
        let albums: Vec<RawEntryRow> = aqb.build_query_as().fetch_all(&mut *conn).await?;
        Ok(albums
            .into_iter()
            .filter(|a| strip_album_edition_suffix(&a.title).to_lowercase() == stripped_q)
            .collect())
    }

    /// Upserts a catalog entry with upfront `entry_identifiers` deduplication, album edition-variant
    /// matching, and automatic `artist` synthesis for music works.
    pub async fn upsert_entry(&self, input: &UpsertEntryInput) -> CatalogResult<CatalogEntry> {
        let kind = input
            .kind
            .ok_or_else(|| CatalogError::InvalidInput("Entry kind is required".to_owned()))?;
        let title = input.title.trim();
        if title.is_empty() {
            return Err(CatalogError::InvalidInput("Entry title cannot be empty".to_owned()));
        }

        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let entry_id = Self::upsert_entry_in_tx(&mut tx, input, kind, title, now).await?;
        tx.commit().await?;

        self.get_entry(&entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))
    }

    /// Explicitly edits bibliographic metadata on an existing non-collection catalog entry.
    ///
    /// Stamps `"edited_hlc"` in `metadata_json` so explicit user corrections (including clearing
    /// creators, dates, summary, URL, or identifiers) win deterministically during peer sync and
    /// are protected from being clobbered by stale feed repolls.
    #[allow(clippy::too_many_lines)]
    pub async fn update_entry(
        &self,
        id: &str,
        input: &UpdateEntryInput,
    ) -> CatalogResult<EntryDetailView> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let Some(canon_id) = Self::follow_merged_id_in_tx(tx.conn(), id).await? else {
            return Err(CatalogError::NotFound(format!("Entry '{id}'")));
        };
        if crate::db::is_protected_system_collection_id(&canon_id) {
            return Err(CatalogError::Conflict(format!(
                "Protected system collection '{canon_id}' cannot be edited"
            )));
        }
        let Some(raw) = Self::get_entry_raw_in_tx(tx.conn(), &canon_id).await? else {
            return Err(CatalogError::NotFound(format!("Entry '{id}'")));
        };
        if raw.kind == EntryKind::Collection {
            return Err(CatalogError::Conflict(
                "Collections cannot be edited via entry update".to_owned(),
            ));
        }
        let is_collection_or_feed: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_specs WHERE collection_id = ?
             UNION ALL
             SELECT 1 FROM feed_sources WHERE collection_id = ?
             LIMIT 1",
        )
        .bind(&canon_id)
        .bind(&canon_id)
        .fetch_optional(tx.conn())
        .await?;
        if is_collection_or_feed.is_some() {
            return Err(CatalogError::Conflict(
                "Collections and feeds cannot be edited via entry update".to_owned(),
            ));
        }

        let target_kind = match input.kind {
            Some(EntryKind::Collection) => {
                return Err(CatalogError::InvalidInput(
                    "Entry kind cannot be changed to collection".to_owned(),
                ));
            }
            Some(k) => k,
            None => raw.kind,
        };

        let target_title = match input.title.as_deref() {
            Some(t) => {
                let trimmed = t.trim();
                if trimmed.is_empty() {
                    return Err(CatalogError::InvalidInput(
                        "Entry title cannot be empty".to_owned(),
                    ));
                }
                trimmed.to_owned()
            }
            None => raw.title.clone(),
        };

        let (target_creators_json, parsed_creators) = match &input.creators {
            Some(list) => {
                let mut deduped = Vec::new();
                let mut seen = HashSet::new();
                for c in list {
                    let trimmed = c.trim();
                    if !trimmed.is_empty() && seen.insert(trimmed.to_lowercase()) {
                        deduped.push(trimmed.to_owned());
                    }
                }
                let json = serde_json::to_string(&deduped).unwrap_or_else(|_| "[]".to_owned());
                (json, Some(deduped))
            }
            None => (raw.creators_json.clone(), None),
        };

        let target_url = match &input.url {
            Some(opt) => match opt.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                Some(u) => {
                    validate_editable_entry_url(u)?;
                    Some(u.to_owned())
                }
                None => None,
            },
            None => raw.url.clone(),
        };

        if let Some(Some(yr)) = input.release_year
            && !(1000..=2100).contains(&yr)
        {
            return Err(CatalogError::InvalidInput(
                "Release year must be between 1000 and 2100".to_owned(),
            ));
        }

        let (target_published_at, target_release_year) = harmonize_updated_publication_dates(
            raw.published_at,
            raw.release_year,
            input.published_at,
            input.release_year,
        );

        let target_summary = match &input.summary {
            Some(opt) => {
                opt.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned)
            }
            None => raw.summary.clone(),
        };

        let target_thumb = match &input.thumbnail_url {
            Some(opt) => match opt.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                Some(u) => {
                    validate_http_url(u, "Thumbnail URL")?;
                    Some(u.to_owned())
                }
                None => None,
            },
            None => raw.thumbnail_url.clone(),
        };

        // Validate and deduplicate identifier updates before mutating anything
        let mut ident_patches: BTreeMap<String, Option<String>> = BTreeMap::new();
        for (raw_scheme, raw_val) in &input.identifier_updates {
            let scheme = normalize_identifier_scheme(raw_scheme);
            if scheme.is_empty() {
                return Err(CatalogError::InvalidInput(
                    "Identifier scheme cannot be empty".to_owned(),
                ));
            }
            if matches!(scheme.as_str(), "slug" | "tag" | "feed_url" | "url") {
                return Err(CatalogError::InvalidInput(format!(
                    "Identifier scheme '{scheme}' cannot be edited directly"
                )));
            }
            let normalized_val = match raw_val.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                None => None,
                Some(raw_v) => {
                    let validated =
                        match scheme.as_str() {
                            "doi" => roche_core::document_meta::normalize_doi(raw_v).ok_or_else(
                                || CatalogError::InvalidInput(format!("Invalid DOI: {raw_v}")),
                            )?,
                            "arxiv" => crate::util::media_urls::extract_arxiv_id(raw_v)
                                .ok_or_else(|| {
                                    CatalogError::InvalidInput(format!("Invalid arXiv ID: {raw_v}"))
                                })?,
                            "isbn" => roche_core::document_meta::normalize_isbn(raw_v).ok_or_else(
                                || CatalogError::InvalidInput(format!("Invalid ISBN: {raw_v}")),
                            )?,
                            _ => normalize_identifier_value(target_kind, &scheme, raw_v),
                        };
                    (!validated.is_empty()).then_some(validated)
                }
            };
            if let Some(ref val) = normalized_val
                && let Some(owner_raw) =
                    entries_core::find_entry_id_by_identifier_in_tx(tx.conn(), &scheme, val).await?
                && let Some(owner_canon) =
                    Self::follow_merged_id_in_tx(tx.conn(), &owner_raw).await?
                && owner_canon != canon_id
            {
                return Err(CatalogError::Conflict(format!(
                    "Identifier {scheme}:{val} is already assigned to another entry"
                )));
            }
            ident_patches.insert(scheme, normalized_val);
        }

        // Check URL ownership conflict only when URL is explicitly being changed
        if input.url.is_some()
            && target_url != raw.url
            && let Some(ref new_u) = target_url
        {
            let new_norm = normalize_identifier_value(target_kind, "url", new_u);
            if !new_norm.is_empty()
                && let Some(owner_raw) =
                    entries_core::find_entry_id_by_identifier_in_tx(tx.conn(), "url", &new_norm)
                        .await?
                && let Some(owner_canon) =
                    Self::follow_merged_id_in_tx(tx.conn(), &owner_raw).await?
                && owner_canon != canon_id
            {
                let owner_kind: Option<EntryKind> =
                    sqlx::query_scalar("SELECT kind FROM entries WHERE id = ?")
                        .bind(&owner_canon)
                        .fetch_optional(tx.conn())
                        .await?;
                if owner_kind == Some(target_kind) {
                    return Err(CatalogError::Conflict(format!(
                        "URL '{new_u}' is already assigned to another entry"
                    )));
                }
            }
        }

        let edit_hlc = tx.next_hlc().await?;
        let next_metadata_json =
            roche_core::crdt::stamp_entry_edited_hlc(&raw.metadata_json, &edit_hlc);

        sqlx::query(
            "UPDATE entries
             SET kind = ?,
                 title = ?,
                 creators_json = ?,
                 url = ?,
                 published_at = ?,
                 release_year = ?,
                 summary = ?,
                 thumbnail_url = ?,
                 metadata_json = ?,
                 updated_at = ?,
                 hlc = ?
             WHERE id = ?",
        )
        .bind(target_kind)
        .bind(&target_title)
        .bind(&target_creators_json)
        .bind(target_url.as_deref())
        .bind(target_published_at)
        .bind(target_release_year)
        .bind(target_summary.as_deref())
        .bind(target_thumb.as_deref())
        .bind(&next_metadata_json)
        .bind(now)
        .bind(&edit_hlc)
        .bind(&canon_id)
        .execute(tx.conn())
        .await?;

        // Sync URL identifier when URL was explicitly patched
        if input.url.is_some() && target_url != raw.url {
            if let Some(ref old_url) = raw.url {
                let old_norm = normalize_identifier_value(raw.kind, "url", old_url);
                if !old_norm.is_empty() {
                    sqlx::query(
                        "DELETE FROM entry_identifiers
                         WHERE entry_id = ? AND scheme = 'url' AND value = ?",
                    )
                    .bind(&canon_id)
                    .bind(&old_norm)
                    .execute(tx.conn())
                    .await?;
                }
            }
            if let Some(ref new_u) = target_url {
                let new_norm = normalize_identifier_value(target_kind, "url", new_u);
                entries_core::upsert_entry_identifier_in_tx(tx.conn(), &canon_id, "url", &new_norm)
                    .await?;
                if !ident_patches.contains_key("arxiv")
                    && let Some(aid) = crate::util::media_urls::extract_arxiv_id(new_u)
                {
                    let norm_aid = normalize_identifier_value(target_kind, "arxiv", &aid);
                    entries_core::upsert_entry_identifier_in_tx(
                        tx.conn(),
                        &canon_id,
                        "arxiv",
                        &norm_aid,
                    )
                    .await?;
                }
            }
        }

        // Apply explicit identifier replacements / deletions
        for (scheme, val_opt) in ident_patches {
            sqlx::query("DELETE FROM entry_identifiers WHERE entry_id = ? AND scheme = ?")
                .bind(&canon_id)
                .bind(&scheme)
                .execute(tx.conn())
                .await?;
            if let Some(val) = val_opt {
                entries_core::upsert_entry_identifier_in_tx(tx.conn(), &canon_id, &scheme, &val)
                    .await?;
            }
        }

        // Keep `entry_content.content_type` coherent if entry kind changed
        if target_kind != raw.kind {
            Self::align_entry_content_type_for_kind_in_tx(tx.conn(), &canon_id, target_kind)
                .await?;
        }

        // Synthesize artist links when editing music works
        if matches!(target_kind, EntryKind::Album | EntryKind::Song)
            && let Some(ref creators) = parsed_creators
        {
            for creator in creators {
                let artist_id = Self::ensure_artist_entry_in_tx(&mut tx, creator, now).await?;
                let ce_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
                     VALUES (?, ?, ?, ?)",
                )
                .bind(&artist_id)
                .bind(&canon_id)
                .bind(now)
                .bind(&ce_hlc)
                .execute(tx.conn())
                .await?;
            }
        }

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            &canon_id,
            ActivityAction::Update,
            now,
        )
        .await?;

        tx.commit().await?;

        self.get_entry_detail(&canon_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_id}'")))
    }

    async fn align_entry_content_type_for_kind_in_tx(
        conn: &mut SqliteConnection,
        entry_id: &str,
        target_kind: EntryKind,
    ) -> std::result::Result<(), sqlx::Error> {
        let existing_ct: Option<ContentType> =
            sqlx::query_scalar("SELECT content_type FROM entry_content WHERE entry_id = ?")
                .bind(entry_id)
                .fetch_optional(&mut *conn)
                .await?;
        let Some(cur_ct) = existing_ct else {
            return Ok(());
        };
        if cur_ct == ContentType::Pdf {
            return Ok(());
        }
        let next_ct = match target_kind {
            EntryKind::Paper if cur_ct == ContentType::Article => Some(ContentType::Paper),
            EntryKind::Article if cur_ct == ContentType::Paper => Some(ContentType::Article),
            EntryKind::Video => Some(ContentType::Video),
            EntryKind::PodcastEpisode => Some(ContentType::Audio),
            _ => None,
        };
        if let Some(ct) = next_ct
            && ct != cur_ct
        {
            sqlx::query("UPDATE entry_content SET content_type = ? WHERE entry_id = ?")
                .bind(ct)
                .bind(entry_id)
                .execute(&mut *conn)
                .await?;
        }
        Ok(())
    }

    pub(crate) async fn upsert_entry_in_tx(
        tx: &mut WriteTx<'_>,
        input: &UpsertEntryInput,
        kind: EntryKind,
        title: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let (id, _) =
            Self::upsert_entry_with_created_in_tx(tx, input, kind, title, now, None, None).await?;
        Ok(id)
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) async fn upsert_entry_with_created_in_tx(
        tx: &mut WriteTx<'_>,
        input: &UpsertEntryInput,
        kind: EntryKind,
        title: &str,
        now: DateTime<Utc>,
        explicit_created_at: Option<DateTime<Utc>>,
        explicit_hlc: Option<&str>,
    ) -> CatalogResult<(String, bool)> {
        if kind == EntryKind::Collection {
            return Err(CatalogError::InvalidInput(
                "Collections must be created via collection endpoints".to_owned(),
            ));
        }
        let clean_creators: Vec<String> = input
            .creators
            .iter()
            .map(|c| c.trim())
            .filter(|c| !c.is_empty())
            .map(ToOwned::to_owned)
            .collect();
        let clean_url =
            input.url.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned);

        let mut normalized_idents: Vec<EntryIdentifier> = Vec::new();
        let mut seen_idents: HashSet<(String, String)> = HashSet::new();
        if let Some(u) = &clean_url {
            let val = normalize_identifier_value(kind, "url", u);
            if !val.is_empty() && seen_idents.insert(("url".to_owned(), val.clone())) {
                normalized_idents.push(EntryIdentifier { scheme: "url".to_owned(), value: val });
            }
            if let Some(arxiv_id) = crate::util::media_urls::extract_arxiv_id(u) {
                let norm_arxiv = normalize_identifier_value(kind, "arxiv", &arxiv_id);
                if !norm_arxiv.is_empty()
                    && seen_idents.insert(("arxiv".to_owned(), norm_arxiv.clone()))
                {
                    normalized_idents
                        .push(EntryIdentifier { scheme: "arxiv".to_owned(), value: norm_arxiv });
                }
            }
        }
        for ident in &input.identifiers {
            let scheme = normalize_identifier_scheme(&ident.scheme);
            if matches!(scheme.as_str(), "slug" | "tag" | "feed_url") {
                return Err(CatalogError::InvalidInput(format!(
                    "Identifier scheme '{scheme}' is reserved for internal collections and feeds"
                )));
            }
            let val = normalize_identifier_value(kind, &scheme, &ident.value);
            if !scheme.is_empty()
                && !val.is_empty()
                && seen_idents.insert((scheme.clone(), val.clone()))
            {
                normalized_idents.push(EntryIdentifier { scheme, value: val });
            }
        }

        // 1. Check explicit ID (following multi-hop merged_into_id)
        let mut matched_id = if let Some(explicit_id) =
            input.id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            if crate::db::is_protected_system_collection_id(explicit_id) {
                return Err(CatalogError::Conflict(format!(
                    "Protected system collection '{explicit_id}' cannot be modified via entry upsert"
                )));
            }
            Self::follow_merged_id_in_tx(tx.conn(), explicit_id).await?
        } else {
            None
        };

        // 2. Upfront deduplication across all normalized identifiers (walking merged chains)
        if matched_id.is_none() {
            for ident in &normalized_idents {
                let raw_owner = entries_core::find_entry_id_by_identifier_in_tx(
                    tx.conn(),
                    &ident.scheme,
                    &ident.value,
                )
                .await?;
                if let Some(owner_id) = raw_owner
                    && let Some(canon_owner) =
                        Self::follow_merged_id_in_tx(tx.conn(), &owner_id).await?
                {
                    if crate::db::is_protected_system_collection_id(&canon_owner) {
                        continue;
                    }
                    // Avoid colliding a podcast episode with a podcast/feed when both mention feed_url
                    let owner_kind = sqlx::query_scalar!(
                        r#"SELECT kind as "kind!: EntryKind" FROM entries WHERE id = ?"#,
                        &canon_owner,
                    )
                    .fetch_optional(tx.conn())
                    .await?;
                    let compatible = match owner_kind {
                        Some(EntryKind::Collection) => kind == EntryKind::Collection,
                        Some(ok) if ident.scheme == "feed_url" => ok == kind,
                        Some(_) => kind != EntryKind::Collection,
                        None => false,
                    };
                    if compatible {
                        matched_id = Some(canon_owner);
                        break;
                    }
                }
            }
        }

        if let Some(ref existing_id) = matched_id {
            if crate::db::is_protected_system_collection_id(existing_id) {
                return Err(CatalogError::Conflict(format!(
                    "Protected system collection '{existing_id}' cannot be modified via entry upsert"
                )));
            }
            let existing_spec: Option<(String, Option<String>)> = sqlx::query_as(
                "SELECT collection_role, rule_json FROM collection_specs WHERE collection_id = ?",
            )
            .bind(existing_id)
            .fetch_optional(tx.conn())
            .await?;
            if let Some((role, rule_json)) = existing_spec
                && (is_dynamic_rule(rule_json.as_deref())
                    || matches!(role.as_str(), "system" | "feed" | "tag")
                    || kind != EntryKind::Collection)
            {
                return Err(CatalogError::Conflict(format!(
                    "Collection '{existing_id}' cannot be modified via entry upsert"
                )));
            }
        }

        // 3. Album edition-variant matching (`(artist.casefold(), strip_album_edition_suffix(title).casefold())`)
        if matched_id.is_none()
            && kind == EntryKind::Album
            && let Some(primary_artist) = clean_creators.first()
        {
            let target_artist = primary_artist.to_lowercase();
            let target_stripped = strip_album_edition_suffix(title).to_lowercase();
            let escaped_prefix = crate::db::escape_like_literal(&target_stripped);
            let title_like = format!("{escaped_prefix}%");
            let existing_albums: Vec<(String, String, String)> = sqlx::query_as(
                "SELECT id, title, creators_json
                 FROM entries
                 WHERE kind = 'album'
                   AND merged_into_id IS NULL
                   AND LOWER(TRIM(title)) LIKE ? ESCAPE '\\'",
            )
            .bind(&title_like)
            .fetch_all(tx.conn())
            .await?;
            for (cand_id, cand_title, cand_creators_json) in existing_albums {
                let cand_creators: Vec<String> =
                    serde_json::from_str(&cand_creators_json).unwrap_or_default();
                let same_artist = cand_creators
                    .first()
                    .map(|a| a.trim())
                    .filter(|a| !a.is_empty())
                    .is_some_and(|a| a.to_lowercase() == target_artist);
                if same_artist
                    && strip_album_edition_suffix(&cand_title).to_lowercase() == target_stripped
                {
                    matched_id = Some(cand_id);
                    break;
                }
            }
        }

        // 4. Exact title match for Artists when creators/identifiers are absent
        if matched_id.is_none() && kind == EntryKind::Artist {
            matched_id = sqlx::query_scalar!(
                r#"SELECT id as "id!" FROM entries
                   WHERE kind = 'artist' AND merged_into_id IS NULL AND LOWER(title) = LOWER(?)
                   LIMIT 1"#,
                title,
            )
            .fetch_optional(tx.conn())
            .await?;
        }

        // Validate `parent_collection_id` upfront before mutating `entries`
        let resolved_parent_id = if let Some(parent_id) =
            input.parent_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let canon_parent =
                Self::follow_merged_id_in_tx(tx.conn(), parent_id).await?.ok_or_else(|| {
                    CatalogError::NotFound(format!("Parent collection '{parent_id}'"))
                })?;
            let spec_row = sqlx::query!(
                r#"SELECT e.title as "title!",
                          cs.collection_role as "collection_role!: CollectionRole",
                          cs.rule_json as "rule_json?"
                   FROM collection_specs cs
                   JOIN entries e ON e.id = cs.collection_id
                   WHERE cs.collection_id = ?
                   LIMIT 1"#,
                &canon_parent,
            )
            .fetch_optional(tx.conn())
            .await?;
            if let Some(spec) = spec_row
                && let Some(reason) =
                    collection_immutable_reason(spec.collection_role, spec.rule_json.as_deref())
            {
                return Err(CatalogError::ImmutableCollection { title: spec.title, reason });
            }
            Some(canon_parent)
        } else {
            None
        };

        let creators_json =
            serde_json::to_string(&clean_creators).unwrap_or_else(|_| "[]".to_owned());
        let metadata_json = input
            .metadata
            .as_ref()
            .filter(|v| v.is_object())
            .and_then(|v| serde_json::to_string(v).ok())
            .unwrap_or_else(|| "{}".to_owned());
        let clean_url_ref = clean_url.as_deref();
        let clean_summary = input.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_thumb = input.thumbnail_url.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let effective_release_year =
            input.release_year.or_else(|| input.published_at.map(|dt| dt.year()));

        let (final_id, activity_action) = if let Some(existing_id) = matched_id {
            let clean_existing_title = if kind == EntryKind::Album {
                let current_title = sqlx::query_scalar!(
                    r#"SELECT title as "title!" FROM entries WHERE id = ?"#,
                    &existing_id,
                )
                .fetch_one(tx.conn())
                .await?;
                let cur_stripped = strip_album_edition_suffix(&current_title);
                let inc_stripped = strip_album_edition_suffix(title);
                if current_title.len() > cur_stripped.len() && title.len() == inc_stripped.len() {
                    Some(title)
                } else {
                    None
                }
            } else {
                None
            };

            let update_hlc = match explicit_hlc {
                Some(h) if !h.is_empty() => h.to_owned(),
                _ => tx.next_hlc().await?,
            };
            let use_max_hlc = explicit_hlc.is_some_and(|h| !h.is_empty());
            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE(?, title),
                     creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                     url = COALESCE(url, ?),
                     published_at = COALESCE(published_at, ?),
                     release_year = COALESCE(release_year, ?),
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                     updated_at = ?,
                     hlc = CASE
                         WHEN ? THEN CASE WHEN ? > hlc THEN ? ELSE hlc END
                         WHEN ? IS NOT NULL
                           OR (creators_json = '[]' AND ? != '[]')
                           OR (url IS NULL AND ? IS NOT NULL)
                           OR (published_at IS NULL AND ? IS NOT NULL)
                           OR (release_year IS NULL AND ? IS NOT NULL)
                           OR (summary IS NULL AND ? IS NOT NULL)
                           OR (thumbnail_url IS NULL AND ? IS NOT NULL)
                           OR (metadata_json = '{}' AND ? != '{}')
                         THEN ?
                         ELSE hlc
                     END
                 WHERE id = ?",
            )
            .bind(clean_existing_title)
            .bind(&creators_json)
            .bind(&creators_json)
            .bind(clean_url_ref)
            .bind(input.published_at)
            .bind(effective_release_year)
            .bind(clean_summary)
            .bind(clean_thumb)
            .bind(&metadata_json)
            .bind(&metadata_json)
            .bind(now)
            .bind(use_max_hlc)
            .bind(&update_hlc)
            .bind(&update_hlc)
            .bind(clean_existing_title)
            .bind(&creators_json)
            .bind(clean_url_ref)
            .bind(input.published_at)
            .bind(effective_release_year)
            .bind(clean_summary)
            .bind(clean_thumb)
            .bind(&metadata_json)
            .bind(&update_hlc)
            .bind(&existing_id)
            .execute(tx.conn())
            .await?;

            if let Some(serde_json::Value::Object(inc_map)) = &input.metadata
                && !inc_map.is_empty()
            {
                let cur_meta_str: String =
                    sqlx::query_scalar("SELECT metadata_json FROM entries WHERE id = ?")
                        .bind(&existing_id)
                        .fetch_one(tx.conn())
                        .await?;
                let merged_json = roche_core::crdt::merge_entry_metadata_json(
                    &cur_meta_str,
                    input.metadata.as_ref(),
                    false,
                );
                if merged_json != cur_meta_str {
                    let meta_hlc = match explicit_hlc {
                        Some(h) if !h.is_empty() => h.to_owned(),
                        _ => tx.next_hlc().await?,
                    };
                    sqlx::query(
                        "UPDATE entries
                         SET metadata_json = ?,
                             hlc = CASE WHEN ? THEN CASE WHEN ? > hlc THEN ? ELSE hlc END ELSE ? END
                         WHERE id = ?",
                    )
                    .bind(&merged_json)
                    .bind(use_max_hlc)
                    .bind(&meta_hlc)
                    .bind(&meta_hlc)
                    .bind(&meta_hlc)
                    .bind(&existing_id)
                    .execute(tx.conn())
                    .await?;
                }
            }

            (existing_id, ActivityAction::Update)
        } else {
            let det_id = choose_deterministic_entry_id(
                input.id.as_deref(),
                kind,
                title,
                &clean_creators,
                &normalized_idents,
            );
            if let Some(found_det) = Self::follow_merged_id_in_tx(tx.conn(), &det_id).await? {
                if let Some(h) = explicit_hlc.filter(|s| !s.is_empty()) {
                    sqlx::query(
                        "UPDATE entries SET hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END WHERE id = ?",
                    )
                    .bind(h)
                    .bind(h)
                    .bind(&found_det)
                    .execute(tx.conn())
                    .await?;
                }
                (found_det, ActivityAction::Update)
            } else {
                entries_core::insert_entry_row_in_tx(
                    tx,
                    &EntryInsertParams {
                        creators_json: &creators_json,
                        url: clean_url_ref,
                        published_at: input.published_at,
                        release_year: effective_release_year,
                        summary: clean_summary,
                        thumbnail_url: clean_thumb,
                        metadata_json: &metadata_json,
                        hlc: explicit_hlc,
                        ..EntryInsertParams::minimal(
                            &det_id,
                            kind,
                            title,
                            explicit_created_at.unwrap_or(now),
                            now,
                        )
                    },
                )
                .await?;
                (det_id, ActivityAction::Create)
            }
        };

        for ident in &normalized_idents {
            entries_core::upsert_entry_identifier_in_tx(
                tx.conn(),
                &final_id,
                &ident.scheme,
                &ident.value,
            )
            .await?;
        }

        if let Some(canon_parent) = resolved_parent_id {
            Self::attach_collection_member_in_tx(
                tx,
                &canon_parent,
                &final_id,
                input.parent_position,
                now,
                false,
            )
            .await?;
        }

        // Synthesize `artist` entries for music `album` and `song` creators and link them
        if matches!(kind, EntryKind::Album | EntryKind::Song) {
            for creator in &clean_creators {
                let artist_id = Self::ensure_artist_entry_in_tx(tx, creator, now).await?;
                let ce_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
                     VALUES (?, ?, ?, ?)",
                )
                .bind(&artist_id)
                .bind(&final_id)
                .bind(now)
                .bind(&ce_hlc)
                .execute(tx.conn())
                .await?;
            }
        }

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            &final_id,
            activity_action,
            now,
        )
        .await?;

        Ok((final_id, activity_action == ActivityAction::Create))
    }

    pub(crate) async fn ensure_artist_entry_in_tx(
        tx: &mut WriteTx<'_>,
        artist_name: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let trimmed = artist_name.trim();
        let lower_name = trimmed.to_lowercase();
        let det_artist_id = deterministic_title_entry_id("artist", &lower_name);
        if let Some(existing) = sqlx::query_scalar!(
            r#"SELECT id as "id!" FROM entries
               WHERE kind = 'artist' AND merged_into_id IS NULL
                 AND (id = ? OR LOWER(title) = LOWER(?))
               LIMIT 1"#,
            &det_artist_id,
            trimmed,
        )
        .fetch_optional(tx.conn())
        .await?
        {
            return Ok(existing);
        }
        if !trimmed.is_ascii() {
            let artist_rows = sqlx::query!(
                r#"SELECT id as "id!", title as "title!" FROM entries
                   WHERE kind = 'artist' AND merged_into_id IS NULL
                   ORDER BY updated_at DESC"#,
            )
            .fetch_all(tx.conn())
            .await?;
            for row in artist_rows {
                if row.title.trim().to_lowercase() == lower_name {
                    return Ok(row.id);
                }
            }
        }

        // If this deterministic artist was previously merged into a differently-named canonical
        // artist, follow the merge chain instead of linking to a dead tombstone!
        if let Some(canon_artist_id) =
            Self::follow_merged_id_in_tx(tx.conn(), &det_artist_id).await?
        {
            return Ok(canon_artist_id);
        }

        let inserted = entries_core::insert_entry_row_or_ignore_in_tx(
            tx,
            &EntryInsertParams::minimal(&det_artist_id, EntryKind::Artist, trimmed, now, now),
        )
        .await?;
        if inserted {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                &det_artist_id,
                ActivityAction::Create,
                now,
            )
            .await?;
        }
        Ok(det_artist_id)
    }

    pub(super) async fn follow_merged_id(&self, id: &str) -> CatalogResult<Option<String>> {
        let mut conn = self.pool.acquire().await?;
        Ok(Self::follow_merged_id_in_tx(&mut conn, id).await?)
    }

    pub(crate) async fn follow_merged_id_in_tx(
        conn: &mut SqliteConnection,
        id: &str,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let mut cur = id.trim().to_owned();
        if cur.is_empty() {
            return Ok(None);
        }
        let mut visited = HashSet::with_capacity(4);
        for _ in 0..32 {
            if !visited.insert(cur.clone()) {
                return Ok(None);
            }
            let row = sqlx::query!(
                r#"SELECT id as "id!", merged_into_id as "merged_into_id?" FROM entries WHERE id = ?"#,
                &cur,
            )
            .fetch_optional(&mut *conn)
            .await?;
            match row {
                None => return Ok(None),
                Some(r) => match r.merged_into_id {
                    None => return Ok(Some(r.id)),
                    Some(next_id) => cur = next_id,
                },
            }
        }
        Ok(None)
    }

    pub(super) async fn load_identifiers_for_one_in_tx(
        conn: &mut SqliteConnection,
        entry_id: &str,
    ) -> CatalogResult<Vec<EntryIdentifier>> {
        Ok(sqlx::query_as!(
            EntryIdentifier,
            r#"SELECT scheme as "scheme!", value as "value!" FROM entry_identifiers
               WHERE entry_id = ?
               ORDER BY scheme ASC, value ASC"#,
            entry_id,
        )
        .fetch_all(&mut *conn)
        .await?)
    }

    pub(super) async fn hydrate_entries_in_tx(
        conn: &mut SqliteConnection,
        raw_rows: Vec<RawEntryRow>,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        if raw_rows.is_empty() {
            return Ok(Vec::new());
        }
        let ids: Vec<&str> = raw_rows.iter().map(|r| r.id.as_str()).collect();
        let mut ident_map: BTreeMap<String, Vec<EntryIdentifier>> = BTreeMap::new();
        for chunk in ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT entry_id, scheme, value FROM entry_identifiers WHERE entry_id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(") ORDER BY scheme ASC, value ASC");
            let rows: Vec<(String, String, String)> =
                qb.build_query_as().fetch_all(&mut *conn).await?;
            for (eid, scheme, value) in rows {
                ident_map.entry(eid).or_default().push(EntryIdentifier { scheme, value });
            }
        }

        Ok(raw_rows
            .into_iter()
            .map(|r| {
                let idents = ident_map.remove(&r.id).unwrap_or_default();
                r.into_entry(idents)
            })
            .collect())
    }

    pub(super) async fn load_entries_by_ids_in_tx(
        conn: &mut SqliteConnection,
        ids: &[String],
    ) -> CatalogResult<HashMap<String, CatalogEntry>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let mut raw_rows = Vec::with_capacity(ids.len());
        for chunk in ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new(concat!(
                "SELECT ",
                raw_entry_columns!(),
                " FROM entries WHERE id IN ("
            ));
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(&mut *conn).await?;
            raw_rows.extend(rows);
        }
        let hydrated = Self::hydrate_entries_in_tx(&mut *conn, raw_rows).await?;
        Ok(hydrated.into_iter().map(|e| (e.id.clone(), e)).collect())
    }
}

fn validate_http_url(raw: &str, label: &str) -> CatalogResult<()> {
    let parsed = url::Url::parse(raw)
        .map_err(|_| CatalogError::InvalidInput(format!("{label} must be a valid URL")))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err(CatalogError::InvalidInput(format!("{label} must use http or https")));
    }
    Ok(())
}

fn validate_editable_entry_url(raw: &str) -> CatalogResult<()> {
    if raw.starts_with("local-pdf://") {
        return Ok(());
    }
    validate_http_url(raw, "Entry URL")
}

#[allow(clippy::option_option)]
fn harmonize_updated_publication_dates(
    existing_published_at: Option<DateTime<Utc>>,
    existing_release_year: Option<i32>,
    patch_published_at: Option<Option<DateTime<Utc>>>,
    patch_release_year: Option<Option<i32>>,
) -> (Option<DateTime<Utc>>, Option<i32>) {
    match patch_published_at {
        Some(Some(dt)) => match patch_release_year {
            Some(Some(yr)) if yr != dt.year() => {
                let same_date_as_existing =
                    existing_published_at.is_some_and(|ex| ex.date_naive() == dt.date_naive());
                let year_changed = existing_release_year != Some(yr);
                if same_date_as_existing && year_changed {
                    // User changed only the pre-filled Year input while Date stayed unchanged
                    (None, Some(yr))
                } else {
                    // User changed Date (or both); keep release_year aligned with published_at
                    (Some(dt), Some(dt.year()))
                }
            }
            Some(Some(yr)) => (Some(dt), Some(yr)),
            _ => (Some(dt), Some(dt.year())),
        },
        Some(None) => {
            let year = patch_release_year.unwrap_or(existing_release_year);
            (None, year)
        }
        None => match patch_release_year {
            Some(Some(yr)) => {
                let pub_at = existing_published_at.filter(|dt| dt.year() == yr);
                (pub_at, Some(yr))
            }
            Some(None) => (None, None),
            None => (existing_published_at, existing_release_year),
        },
    }
}
