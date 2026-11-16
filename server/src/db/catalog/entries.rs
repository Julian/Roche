use std::collections::{BTreeMap, HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    CatalogRepo,
    identity_norm::{
        choose_deterministic_entry_id, normalize_identifier_scheme, normalize_identifier_value,
        normalize_spotify_identifier, strip_album_edition_suffix,
    },
    models::{
        CatalogEntry, CatalogError, CatalogResult, ConsumptionLogRow, EntryDetailView,
        EntryIdentifier, RawEntryRow, UpsertEntryInput,
    },
};
use crate::db::{
    sync::{SyncRepo, collection_entry_natural_key, deterministic_title_entry_id},
    types::{ActivityAction, ActivityEntityType, CollectionMode, ConsumptionStatus, EntryKind},
};

impl CatalogRepo<'_> {
    pub async fn get_entry(&self, id: &str) -> CatalogResult<Option<CatalogEntry>> {
        let canonical_id = self.follow_merged_id(id).await?;
        let Some(target_id) = canonical_id else {
            return Ok(None);
        };
        let row = sqlx::query_as!(
            RawEntryRow,
            r#"SELECT id as "id!", kind as "kind!: EntryKind", title as "title!",
                      creators_json as "creators_json!", url as "url?",
                      published_at as "published_at?: DateTime<Utc>",
                      release_year as "release_year?: i32", summary as "summary?",
                      thumbnail_url as "thumbnail_url?", metadata_json as "metadata_json!",
                      merged_into_id as "merged_into_id?",
                      created_at as "created_at!: DateTime<Utc>",
                      updated_at as "updated_at!: DateTime<Utc>"
               FROM entries WHERE id = ? AND merged_into_id IS NULL"#,
            &target_id,
        )
        .fetch_optional(self.pool)
        .await?;
        let Some(raw) = row else {
            return Ok(None);
        };
        let idents = self.load_identifiers_for_one(&raw.id).await?;
        Ok(Some(raw.into_entry(idents)))
    }

    pub async fn get_entry_detail(&self, id: &str) -> CatalogResult<Option<EntryDetailView>> {
        let Some(entry) = self.get_entry(id).await? else {
            return Ok(None);
        };
        let child_rows = sqlx::query_as!(
            RawEntryRow,
            r#"SELECT e.id as "id!", e.kind as "kind!: EntryKind", e.title as "title!",
                      e.creators_json as "creators_json!", e.url as "url?",
                      e.published_at as "published_at?: DateTime<Utc>",
                      e.release_year as "release_year?: i32", e.summary as "summary?",
                      e.thumbnail_url as "thumbnail_url?", e.metadata_json as "metadata_json!",
                      e.merged_into_id as "merged_into_id?",
                      e.created_at as "created_at!: DateTime<Utc>",
                      e.updated_at as "updated_at!: DateTime<Utc>"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
               ORDER BY ce.position ASC NULLS LAST, COALESCE(e.release_year, 0) DESC, LOWER(e.title) ASC"#,
            &entry.id,
        )
        .fetch_all(self.pool)
        .await?;
        let children = self.hydrate_entries(child_rows).await?;

        let parent_rows = sqlx::query_as!(
            RawEntryRow,
            r#"SELECT e.id as "id!", e.kind as "kind!: EntryKind", e.title as "title!",
                      e.creators_json as "creators_json!", e.url as "url?",
                      e.published_at as "published_at?: DateTime<Utc>",
                      e.release_year as "release_year?: i32", e.summary as "summary?",
                      e.thumbnail_url as "thumbnail_url?", e.metadata_json as "metadata_json!",
                      e.merged_into_id as "merged_into_id?",
                      e.created_at as "created_at!: DateTime<Utc>",
                      e.updated_at as "updated_at!: DateTime<Utc>"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.collection_id
               LEFT JOIN collection_specs cs ON cs.collection_id = e.id
               WHERE ce.entry_id = ?
                 AND e.merged_into_id IS NULL
                 AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'tag'))
               ORDER BY LOWER(e.title) ASC"#,
            &entry.id,
        )
        .fetch_all(self.pool)
        .await?;
        let parents = self.hydrate_entries(parent_rows).await?;

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
        .fetch_all(self.pool)
        .await?;

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
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                    e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
             FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))",
        );
        if let Some(k) = kind {
            qb.push(" AND e.kind = ").push_bind(k);
        }
        if let Some(q) = trimmed {
            let escaped = q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
            let pattern = format!("%{escaped}%");
            qb.push(" AND (e.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR e.creators_json LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM entry_identifiers ei WHERE ei.entry_id = e.id AND ei.value LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\'))");
        }
        qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', e.updated_at) DESC, LOWER(e.title) ASC LIMIT ")
            .push_bind(cap);
        let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;
        self.hydrate_entries(rows).await
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
        let q = query.trim();
        if q.is_empty() {
            return Err(CatalogError::InvalidInput("Lookup query cannot be empty".to_owned()));
        }

        if let Some(by_id) = self.get_entry(q).await?
            && kind.is_none_or(|k| by_id.kind == k)
        {
            if let Some(col_id) = only_collection_id {
                let in_col = sqlx::query_scalar!(
                    r#"SELECT 1 as "found!: i64" FROM collection_entries WHERE collection_id = ? AND entry_id = ?"#,
                    col_id,
                    &by_id.id,
                )
                .fetch_optional(self.pool)
                .await?;
                if in_col.is_some() {
                    return Ok(by_id);
                }
            } else {
                return Ok(by_id);
            }
        }

        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT DISTINCT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                    e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                    e.merged_into_id, e.created_at, e.updated_at
             FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))",
        );
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

        let mut candidates: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;

        // Fallback for album edition-stripped title lookup
        if candidates.is_empty() && kind == Some(EntryKind::Album) {
            let stripped_q = strip_album_edition_suffix(q).to_lowercase();
            let mut aqb = QueryBuilder::<Sqlite>::new(
                "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                        thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
                 FROM entries WHERE kind = 'album' AND merged_into_id IS NULL",
            );
            if let Some(col_id) = only_collection_id {
                aqb.push(
                    " AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.collection_id = ",
                )
                .push_bind(col_id)
                .push(" AND ce.entry_id = entries.id)");
            }
            let albums: Vec<RawEntryRow> = aqb.build_query_as().fetch_all(self.pool).await?;
            candidates = albums
                .into_iter()
                .filter(|a| strip_album_edition_suffix(&a.title).to_lowercase() == stripped_q)
                .collect();
        }

        if candidates.is_empty() {
            return Err(CatalogError::NotFound(format!("Entry '{q}'")));
        }
        if candidates.len() > 1 {
            let matches = candidates
                .iter()
                .map(|c| format!("'{}' (kind={}, id={})", c.title, c.kind.as_str(), c.id))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(CatalogError::AmbiguousLookup { query: q.to_owned(), matches });
        }
        let hydrated = self.hydrate_entries(candidates).await?;
        hydrated.into_iter().next().ok_or_else(|| CatalogError::NotFound(format!("Entry '{q}'")))
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

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let entry_id = Self::upsert_entry_in_tx(&mut tx, input, kind, title, now).await?;
        tx.commit().await?;

        self.get_entry(&entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) async fn upsert_entry_in_tx(
        tx: &mut SqliteConnection,
        input: &UpsertEntryInput,
        kind: EntryKind,
        title: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
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
            if let Some(arxiv_id) = crate::feeds::arxiv::ArxivParser::extract_arxiv_id(u) {
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
            Self::follow_merged_id_in_tx(tx, explicit_id).await?
        } else {
            None
        };

        // 2. Upfront deduplication across all normalized identifiers (walking merged chains)
        if matched_id.is_none() {
            for ident in &normalized_idents {
                let raw_owner = sqlx::query_scalar!(
                    r#"SELECT entry_id as "entry_id!" FROM entry_identifiers
                       WHERE scheme = ? AND value = ?
                       LIMIT 1"#,
                    &ident.scheme,
                    &ident.value,
                )
                .fetch_optional(&mut *tx)
                .await?;
                if let Some(owner_id) = raw_owner
                    && let Some(canon_owner) = Self::follow_merged_id_in_tx(tx, &owner_id).await?
                {
                    // Avoid colliding a podcast episode with a podcast/feed when both mention feed_url
                    let owner_kind = sqlx::query_scalar!(
                        r#"SELECT kind as "kind!: EntryKind" FROM entries WHERE id = ?"#,
                        &canon_owner,
                    )
                    .fetch_optional(&mut *tx)
                    .await?;
                    let compatible = match owner_kind {
                        Some(ok) if ident.scheme == "feed_url" => ok == kind,
                        Some(_) => true,
                        None => false,
                    };
                    if compatible {
                        matched_id = Some(canon_owner);
                        break;
                    }
                }
            }
        }

        // 3. Album edition-variant matching (`(artist.casefold(), strip_album_edition_suffix(title).casefold())`)
        if matched_id.is_none()
            && kind == EntryKind::Album
            && let Some(primary_artist) = clean_creators.first()
        {
            let target_artist = primary_artist.to_lowercase();
            let target_stripped = strip_album_edition_suffix(title).to_lowercase();
            let existing_albums = sqlx::query!(
                r#"SELECT id as "id!", title as "title!", creators_json as "creators_json!"
                   FROM entries
                   WHERE kind = 'album' AND merged_into_id IS NULL"#,
            )
            .fetch_all(&mut *tx)
            .await?;
            for cand in existing_albums {
                let cand_creators: Vec<String> =
                    serde_json::from_str(&cand.creators_json).unwrap_or_default();
                let same_artist = cand_creators
                    .first()
                    .map(|a| a.trim())
                    .filter(|a| !a.is_empty())
                    .is_some_and(|a| a.to_lowercase() == target_artist);
                if same_artist
                    && strip_album_edition_suffix(&cand.title).to_lowercase() == target_stripped
                {
                    matched_id = Some(cand.id);
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
            .fetch_optional(&mut *tx)
            .await?;
        }

        // Validate `parent_collection_id` upfront before mutating `entries`
        let resolved_parent_id = if let Some(parent_id) =
            input.parent_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let canon_parent =
                Self::follow_merged_id_in_tx(tx, parent_id).await?.ok_or_else(|| {
                    CatalogError::NotFound(format!("Parent collection '{parent_id}'"))
                })?;
            let spec_row = sqlx::query!(
                r#"SELECT e.title as "title!", cs.mode as "mode!: CollectionMode"
                   FROM collection_specs cs
                   JOIN entries e ON e.id = cs.collection_id
                   WHERE cs.collection_id = ?
                   LIMIT 1"#,
                &canon_parent,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(spec) = spec_row
                && spec.mode != CollectionMode::Manual
            {
                return Err(CatalogError::ImmutableCollection {
                    title: spec.title,
                    mode: spec.mode,
                });
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

        let (final_id, activity_action) = if let Some(existing_id) = matched_id {
            let clean_existing_title = if kind == EntryKind::Album {
                let current_title = sqlx::query_scalar!(
                    r#"SELECT title as "title!" FROM entries WHERE id = ?"#,
                    &existing_id,
                )
                .fetch_one(&mut *tx)
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

            sqlx::query!(
                "UPDATE entries
                 SET title = COALESCE(?, title),
                     creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                     url = COALESCE(url, ?),
                     published_at = COALESCE(published_at, ?),
                     release_year = COALESCE(release_year, ?),
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                     updated_at = ?
                 WHERE id = ?",
                clean_existing_title,
                &creators_json,
                &creators_json,
                clean_url_ref,
                input.published_at as _,
                input.release_year as _,
                clean_summary,
                clean_thumb,
                &metadata_json,
                &metadata_json,
                now as _,
                &existing_id,
            )
            .execute(&mut *tx)
            .await?;

            if let Some(serde_json::Value::Object(inc_map)) = &input.metadata
                && !inc_map.is_empty()
            {
                let cur_meta_str: String =
                    sqlx::query_scalar("SELECT metadata_json FROM entries WHERE id = ?")
                        .bind(&existing_id)
                        .fetch_one(&mut *tx)
                        .await?;
                let mut merged_obj = serde_json::from_str::<serde_json::Value>(&cur_meta_str)
                    .ok()
                    .and_then(|v| v.as_object().cloned())
                    .unwrap_or_default();
                let mut changed = false;
                for (k, v) in inc_map {
                    if !merged_obj.contains_key(k) {
                        merged_obj.insert(k.clone(), v.clone());
                        changed = true;
                    }
                }
                if changed {
                    let merged_json = serde_json::Value::Object(merged_obj).to_string();
                    sqlx::query("UPDATE entries SET metadata_json = ? WHERE id = ?")
                        .bind(&merged_json)
                        .bind(&existing_id)
                        .execute(&mut *tx)
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
            if let Some(found_det) = Self::follow_merged_id_in_tx(tx, &det_id).await? {
                (found_det, ActivityAction::Update)
            } else {
                sqlx::query!(
                    "INSERT INTO entries
                     (id, kind, title, creators_json, url, published_at, release_year, summary,
                      thumbnail_url, metadata_json, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    &det_id,
                    kind as _,
                    title,
                    &creators_json,
                    clean_url_ref,
                    input.published_at as _,
                    input.release_year as _,
                    clean_summary,
                    clean_thumb,
                    &metadata_json,
                    now as _,
                    now as _,
                )
                .execute(&mut *tx)
                .await?;
                (det_id, ActivityAction::Create)
            }
        };

        for ident in &normalized_idents {
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES (?, ?, ?)",
                &ident.scheme,
                &ident.value,
                &final_id,
            )
            .execute(&mut *tx)
            .await?;
        }

        if let Some(canon_parent) = resolved_parent_id {
            if canon_parent == final_id {
                return Err(CatalogError::InvalidInput(
                    "A collection cannot contain itself".to_owned(),
                ));
            }
            if Self::would_introduce_collection_cycle_in_tx(tx, &canon_parent, &final_id).await? {
                return Err(CatalogError::InvalidInput(
                    "Adding entry to parent collection would create a cycle".to_owned(),
                ));
            }
            let nat_key = collection_entry_natural_key(&canon_parent, &final_id);
            SyncRepo::clear_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key).await?;
            sqlx::query!(
                "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                    position = COALESCE(excluded.position, collection_entries.position)",
                &canon_parent,
                &final_id,
                input.parent_position,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
            SyncRepo::record_activity(
                tx,
                ActivityEntityType::CollectionEntry,
                &nat_key,
                ActivityAction::Create,
                now,
            )
            .await?;
        }

        // Synthesize `artist` entries for music `album` and `song` creators and link them
        if matches!(kind, EntryKind::Album | EntryKind::Song) {
            for creator in &clean_creators {
                let artist_id = Self::ensure_artist_entry_in_tx(tx, creator, now).await?;
                sqlx::query!(
                    "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
                     VALUES (?, ?, ?)",
                    &artist_id,
                    &final_id,
                    now as _,
                )
                .execute(&mut *tx)
                .await?;
            }
        }

        SyncRepo::record_activity(tx, ActivityEntityType::Entry, &final_id, activity_action, now)
            .await?;

        Ok(final_id)
    }

    pub(crate) async fn ensure_artist_entry_in_tx(
        tx: &mut SqliteConnection,
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
        .fetch_optional(&mut *tx)
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
            .fetch_all(&mut *tx)
            .await?;
            for row in artist_rows {
                if row.title.trim().to_lowercase() == lower_name {
                    return Ok(row.id);
                }
            }
        }

        // If this deterministic artist was previously merged into a differently-named canonical
        // artist, follow the merge chain instead of linking to a dead tombstone!
        if let Some(canon_artist_id) = Self::follow_merged_id_in_tx(tx, &det_artist_id).await? {
            return Ok(canon_artist_id);
        }

        let res = sqlx::query!(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'artist', ?, ?, ?)",
            &det_artist_id,
            trimmed,
            now as _,
            now as _,
        )
        .execute(&mut *tx)
        .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_activity(
                tx,
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
        for _ in 0..8 {
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
        Ok(Some(cur))
    }

    pub(super) async fn load_identifiers_for_one(
        &self,
        entry_id: &str,
    ) -> CatalogResult<Vec<EntryIdentifier>> {
        Ok(sqlx::query_as!(
            EntryIdentifier,
            r#"SELECT scheme as "scheme!", value as "value!" FROM entry_identifiers
               WHERE entry_id = ?
               ORDER BY scheme ASC, value ASC"#,
            entry_id,
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub(super) async fn hydrate_entries(
        &self,
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
                qb.build_query_as().fetch_all(self.pool).await?;
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

    pub(super) async fn load_entries_by_ids(
        &self,
        ids: &[String],
    ) -> CatalogResult<HashMap<String, CatalogEntry>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let mut raw_rows = Vec::with_capacity(ids.len());
        for chunk in ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                        thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
                 FROM entries WHERE id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;
            raw_rows.extend(rows);
        }
        let hydrated = self.hydrate_entries(raw_rows).await?;
        Ok(hydrated.into_iter().map(|e| (e.id.clone(), e)).collect())
    }
}
