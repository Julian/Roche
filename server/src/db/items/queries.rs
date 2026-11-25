use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    IS_FEED_INBOX_ITEM_SQL, IS_LIVE_ITEM_SQL, Item, ItemContentProjection, ItemFilter, ItemPage,
    ItemRepo, STARRED_COLLECTION_ID, WANT_TO_READ_COLLECTION_ID, item_select_query_builder,
    push_item_select_projection,
};
use crate::db::{
    IN_PROGRESS_MIN_PROGRESS, InboxState, ItemSortField, ItemStatus, ItemStatusFilter,
    READER_AUTO_READ_THRESHOLD, SortOrder,
};

impl ItemRepo<'_> {
    pub async fn get(&self, id: &str) -> Result<Option<Item>> {
        let mut qb = item_select_query_builder(ItemContentProjection::Full);
        qb.push(" WHERE i.id = ")
            .push_bind(id)
            .push(" AND i.merged_into_id IS NULL AND ")
            .push(IS_LIVE_ITEM_SQL);
        Ok(qb.build_query_as::<Item>().fetch_optional(self.pool).await?)
    }

    /// Returns the subset of `ids` that currently exist as live reader items (matching `get`).
    pub async fn existing_ids(&self, ids: &[String]) -> Result<HashSet<String>> {
        if ids.is_empty() {
            return Ok(HashSet::new());
        }
        let mut unique_ids: Vec<&str> = Vec::with_capacity(ids.len());
        let mut seen = HashSet::with_capacity(ids.len());
        for id in ids {
            let trimmed = id.trim();
            if !trimmed.is_empty() && seen.insert(trimmed) {
                unique_ids.push(trimmed);
            }
        }
        if unique_ids.is_empty() {
            return Ok(HashSet::new());
        }

        let mut existing = HashSet::with_capacity(unique_ids.len());
        for chunk in unique_ids.chunks(500) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT i.id
                 FROM entries i
                 LEFT JOIN entry_state es ON es.entry_id = i.id
                 LEFT JOIN entry_content ec ON ec.entry_id = i.id
                 WHERE i.id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            qb.push(") AND i.merged_into_id IS NULL AND ").push(IS_LIVE_ITEM_SQL);
            let found: Vec<String> = qb.build_query_scalar().fetch_all(self.pool).await?;
            existing.extend(found);
        }
        Ok(existing)
    }

    pub async fn list(
        &self,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        let mut conn = self.pool.acquire().await?;
        Self::list_in_tx(&mut conn, status, feed_id, limit).await
    }

    pub(crate) async fn list_in_tx(
        conn: &mut SqliteConnection,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        let mut qb = item_select_query_builder(ItemContentProjection::List);
        qb.push(" WHERE i.merged_into_id IS NULL AND ");
        if status.is_some() || feed_id.is_some() {
            qb.push("es.inbox_state IN ('unread', 'dismissed')");
        } else {
            qb.push(IS_LIVE_ITEM_SQL);
        }
        if let Some(s) = status {
            qb.push(" AND es.inbox_state = ").push_bind(InboxState::from(s));
        }
        if let Some(f) = feed_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_f WHERE ce_f.entry_id = i.id AND ce_f.collection_id = ",
            )
            .push_bind(f)
            .push(")");
        }
        qb.push(" ORDER BY COALESCE(i.published_at, i.created_at) DESC, i.id DESC");
        if let Some(l) = limit {
            qb.push(" LIMIT ").push_bind(i64::try_from(l).unwrap_or(i64::MAX));
        }

        Ok(qb.build_query_as::<Item>().fetch_all(conn).await?)
    }

    #[cfg(test)]
    pub async fn list_filtered(&self, filter: &ItemFilter) -> Result<Vec<Item>> {
        Ok(self.list_filtered_page(filter).await?.items)
    }

    #[allow(clippy::too_many_lines)]
    pub async fn list_filtered_page(&self, filter: &ItemFilter) -> Result<ItemPage> {
        let trimmed_search = filter.search.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let search_terms = trimmed_search.map_or_default(ItemFilter::tokenize_search_terms);
        let fts_query = trimmed_search.and_then(ItemFilter::build_fts_query);

        let proj = if filter.include_content == Some(false) {
            ItemContentProjection::MetadataOnly
        } else {
            ItemContentProjection::List
        };
        // Two-phase paginate-then-hydrate CTE: filter, sort, and LIMIT to at most `per_page + 1`
        // IDs first using only the joins needed by the active predicates, then hydrate correlated
        // subqueries (`fs_primary`, `entry_media`, `tag_names`, `json_each`, `entry_content`) only
        // for the returned page rows instead of every matching row in the inbox.
        let needs_ec = filter.inbox_only != Some(true)
            || filter.content_type.is_some()
            || filter.has_audio.is_some();
        let needs_wtr = filter.inbox_only != Some(true)
            && matches!(
                filter.status,
                Some(
                    ItemStatusFilter::Unread
                        | ItemStatusFilter::Dismissed
                        | ItemStatusFilter::Archived
                )
            );
        let needs_fav = filter.is_favorite.is_some();

        let mut qb = QueryBuilder::<Sqlite>::new(
            "WITH page_ids(id, ord) AS MATERIALIZED (
                 SELECT page_inner.id, ROW_NUMBER() OVER () AS ord
                 FROM (
                     SELECT i.id
                     FROM entries i
                     LEFT JOIN entry_state es ON es.entry_id = i.id",
        );
        if needs_ec {
            qb.push(" LEFT JOIN entry_content ec ON ec.entry_id = i.id");
        }
        if needs_wtr {
            qb.push(" LEFT JOIN collection_entries ce_wtr ON ce_wtr.collection_id = ")
                .push_bind(WANT_TO_READ_COLLECTION_ID)
                .push(" AND ce_wtr.entry_id = i.id");
        }
        if needs_fav {
            qb.push(" LEFT JOIN collection_entries ce_fav ON ce_fav.collection_id = ")
                .push_bind(STARRED_COLLECTION_ID)
                .push(" AND ce_fav.entry_id = i.id");
        }
        if let Some(fts_match) = &fts_query {
            qb.push(
                " LEFT JOIN (SELECT rowid AS fts_rowid, bm25(entries_fts, 0.0, 10.0, 5.0, 2.0, 1.0) AS fts_rank FROM entries_fts WHERE entries_fts MATCH ",
            )
            .push_bind(fts_match.clone())
            .push(") fts ON fts.fts_rowid = i.rowid");
        }
        qb.push(" WHERE i.merged_into_id IS NULL AND ");
        if filter.inbox_only == Some(true) {
            qb.push(IS_FEED_INBOX_ITEM_SQL);
        } else {
            qb.push(IS_LIVE_ITEM_SQL);
        }

        match filter.status {
            Some(ItemStatusFilter::Unread) => {
                if filter.inbox_only == Some(true) {
                    qb.push(" AND es.inbox_state = 'unread'");
                } else {
                    qb.push(
                        " AND (es.inbox_state = 'unread' OR (COALESCE(es.inbox_state, 'none') = 'none' AND (es.read_at IS NULL OR ce_wtr.entry_id IS NOT NULL)))",
                    );
                }
                qb.push(
                    " AND NOT EXISTS (SELECT 1 FROM consumption_log cl_f WHERE cl_f.entry_id = i.id AND cl_f.status = 'consumed')",
                );
            }
            Some(ItemStatusFilter::Finished) => {
                qb.push(
                    " AND EXISTS (SELECT 1 FROM consumption_log cl_f WHERE cl_f.entry_id = i.id AND cl_f.status = 'consumed')",
                );
            }
            Some(ItemStatusFilter::Dismissed) => {
                if filter.inbox_only == Some(true) {
                    qb.push(" AND es.inbox_state = 'dismissed'");
                } else {
                    qb.push(
                        " AND (es.inbox_state = 'dismissed' OR (COALESCE(es.inbox_state, 'none') = 'none' AND es.read_at IS NOT NULL AND ce_wtr.entry_id IS NULL))",
                    );
                }
                qb.push(
                    " AND NOT EXISTS (SELECT 1 FROM consumption_log cl_f WHERE cl_f.entry_id = i.id AND cl_f.status = 'consumed')",
                );
            }
            Some(ItemStatusFilter::Archived) => {
                if filter.inbox_only == Some(true) {
                    qb.push(" AND es.inbox_state = 'dismissed'");
                } else {
                    qb.push(
                        " AND (es.inbox_state = 'dismissed' OR (COALESCE(es.inbox_state, 'none') = 'none' AND es.read_at IS NOT NULL AND ce_wtr.entry_id IS NULL))",
                    );
                }
            }
            Some(ItemStatusFilter::All) | None => {}
        }
        if let Some(is_read) = filter.is_read {
            if is_read {
                qb.push(" AND es.read_at IS NOT NULL");
            } else {
                qb.push(" AND es.read_at IS NULL");
            }
        }
        if let Some(f) = &filter.feed_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_ff WHERE ce_ff.entry_id = i.id AND ce_ff.collection_id = ",
            )
            .push_bind(f)
            .push(")");
        }
        if let Some(col_id) = &filter.collection_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_cf WHERE ce_cf.entry_id = i.id AND ce_cf.collection_id = ",
            )
            .push_bind(col_id)
            .push(")");
        }
        if let Some(t) = &filter.tag_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_tf WHERE ce_tf.entry_id = i.id AND ce_tf.collection_id = ",
            )
            .push_bind(t)
            .push(")");
        }
        if let Some(ct) = filter.content_type {
            qb.push(
                " AND COALESCE(ec.content_type, CASE i.kind WHEN 'video' THEN 'video' WHEN 'paper' THEN 'paper' WHEN 'podcast_episode' THEN 'audio' ELSE 'article' END) = ",
            )
            .push_bind(ct);
        }
        if let Some(podcast_only) = filter.podcast_only {
            if podcast_only {
                qb.push(" AND i.kind = 'podcast_episode'");
            } else {
                qb.push(" AND i.kind != 'podcast_episode'");
            }
        }
        if let Some(has_audio) = filter.has_audio {
            let audio_expr = "(COALESCE(ec.content_type, CASE i.kind WHEN 'podcast_episode' THEN 'audio' ELSE 'article' END) = 'audio' OR EXISTS (SELECT 1 FROM entry_media m_aud WHERE m_aud.entry_id = i.id AND m_aud.media_type = 'audio' AND (m_aud.remote_url IS NOT NULL OR m_aud.local_path IS NOT NULL)))";
            if has_audio {
                qb.push(" AND ").push(audio_expr);
            } else {
                qb.push(" AND NOT ").push(audio_expr);
            }
        }
        if let Some(fav) = filter.is_favorite {
            if fav {
                qb.push(" AND ce_fav.entry_id IS NOT NULL");
            } else {
                qb.push(" AND ce_fav.entry_id IS NULL");
            }
        }
        if filter.in_progress_only == Some(true) {
            qb.push(" AND COALESCE(es.progress, 0.0) >= ")
                .push_bind(IN_PROGRESS_MIN_PROGRESS)
                .push(" AND COALESCE(es.progress, 0.0) < ")
                .push_bind(READER_AUTO_READ_THRESHOLD);
        }
        Self::append_updated_after_where_clause(
            &mut qb,
            filter.updated_after,
            filter.updated_after_item_id.as_deref(),
        );
        if let Some(search) = trimmed_search {
            Self::append_search_where_clause(&mut qb, search, &search_terms);
        }

        let sort_by_relevance =
            fts_query.is_some() && matches!(filter.sort, None | Some(ItemSortField::Relevance));
        if filter.sort_updated_asc {
            qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) ASC, i.id ASC");
        } else if filter.in_progress_only == Some(true) && filter.sort.is_none() {
            qb.push(
                " ORDER BY COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ', es.interaction_updated_at), strftime('%Y-%m-%dT%H:%M:%fZ', es.read_at), strftime('%Y-%m-%dT%H:%M:%fZ', i.published_at), strftime('%Y-%m-%dT%H:%M:%fZ', i.created_at)) DESC, i.id DESC",
            );
        } else if sort_by_relevance {
            let rank_dir = match filter.order.unwrap_or_default() {
                SortOrder::Desc => "ASC",
                SortOrder::Asc => "DESC",
            };
            qb.push(format_args!(
                " ORDER BY CASE WHEN fts.fts_rank IS NULL THEN 1 ELSE 0 END ASC, fts.fts_rank {rank_dir}, COALESCE(i.published_at, i.created_at) DESC, i.id DESC"
            ));
        } else {
            let sort_col = item_sort_sql_expr(filter.sort.unwrap_or_default());
            let sort_dir = sort_order_sql(filter.order.unwrap_or_default());
            qb.push(format_args!(" ORDER BY {sort_col} {sort_dir}, i.id DESC"));
        }

        let per_page = filter.per_page.unwrap_or(50).clamp(1, 200);
        let page = filter.page.unwrap_or(1).max(1);
        let offset = page.saturating_sub(1).saturating_mul(per_page);
        let fetch_limit = per_page.saturating_add(1);
        qb.push(" LIMIT ")
            .push_bind(i64::try_from(fetch_limit).unwrap_or(51))
            .push(" OFFSET ")
            .push_bind(i64::try_from(offset).unwrap_or(0))
            .push(") page_inner) ");

        push_item_select_projection(&mut qb, proj, "FROM page_ids p JOIN entries i ON i.id = p.id");
        qb.push(" ORDER BY p.ord ASC");

        let mut items = qb.build_query_as::<Item>().fetch_all(self.pool).await?;
        let has_more = items.len() > per_page;
        items.truncate(per_page);
        Ok(ItemPage { items, page, per_page, has_more })
    }

    /// Appends a sargable `idx_entries_updated_at_iso` keyset predicate.
    fn append_updated_after_where_clause(
        qb: &mut QueryBuilder<Sqlite>,
        updated_after: Option<DateTime<Utc>>,
        updated_after_item_id: Option<&str>,
    ) {
        let Some(ts) = updated_after else {
            return;
        };
        if let Some(after_id) = updated_after_item_id.map(str::trim).filter(|s| !s.is_empty()) {
            qb.push(
                " AND strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(
                ") AND (strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(") OR i.id > ")
            .push_bind(after_id)
            .push(")");
        } else {
            qb.push(
                " AND strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(")");
        }
    }

    pub async fn count_by_status(&self, status: ItemStatus) -> Result<i64> {
        let inbox_state = InboxState::from(status);
        let sql = match status {
            ItemStatus::Unread => {
                "SELECT COUNT(*)
                 FROM entry_state es
                 JOIN entries e ON e.id = es.entry_id
                 WHERE e.merged_into_id IS NULL
                   AND es.inbox_state = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM consumption_log cl_f
                       WHERE cl_f.entry_id = e.id AND cl_f.status = 'consumed'
                   )
                   AND EXISTS (
                       SELECT 1 FROM collection_entries ce_f
                       JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
                       WHERE ce_f.entry_id = e.id
                   )"
            }
            ItemStatus::Archived => {
                "SELECT COUNT(*)
                 FROM entry_state es
                 JOIN entries e ON e.id = es.entry_id
                 WHERE e.merged_into_id IS NULL
                   AND es.inbox_state = ?
                   AND EXISTS (
                       SELECT 1 FROM collection_entries ce_f
                       JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
                       WHERE ce_f.entry_id = e.id
                   )"
            }
        };
        Ok(sqlx::query_scalar(sql).bind(inbox_state).fetch_one(self.pool).await?)
    }

    pub async fn count_unread(&self) -> Result<i64> {
        self.count_by_status(ItemStatus::Unread).await
    }
}

const fn item_sort_sql_expr(field: ItemSortField) -> &'static str {
    match field {
        ItemSortField::CreatedAt => "i.created_at",
        ItemSortField::PublishedAt | ItemSortField::Relevance => {
            "COALESCE(i.published_at, i.created_at)"
        }
        ItemSortField::Title => "i.title COLLATE NOCASE",
    }
}

const fn sort_order_sql(order: SortOrder) -> &'static str {
    match order {
        SortOrder::Asc => "ASC",
        SortOrder::Desc => "DESC",
    }
}
