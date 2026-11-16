use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite};

use super::{
    ITEM_SELECT_FULL, ITEM_SELECT_LIST, ITEM_SELECT_METADATA_ONLY, Item, ItemFilter, ItemPage,
    ItemRepo,
};
use crate::db::{
    InboxState, ItemSortField, ItemStatus, ItemStatusFilter, SortOrder, sync::STARRED_COLLECTION_ID,
};

impl ItemRepo<'_> {
    pub async fn get(&self, id: &str) -> Result<Option<Item>> {
        let mut qb = QueryBuilder::<Sqlite>::new(ITEM_SELECT_FULL);
        qb.push(" WHERE i.id = ")
            .push_bind(id)
            .push(" AND i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");
        Ok(qb.build_query_as::<Item>().fetch_optional(self.pool).await?)
    }

    pub async fn list(
        &self,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        let mut qb = QueryBuilder::<Sqlite>::new(ITEM_SELECT_LIST);
        qb.push(" WHERE i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");
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

        Ok(qb.build_query_as::<Item>().fetch_all(self.pool).await?)
    }

    #[cfg(test)]
    pub async fn list_filtered(&self, filter: &ItemFilter) -> Result<Vec<Item>> {
        Ok(self.list_filtered_page(filter).await?.items)
    }

    pub async fn list_filtered_page(&self, filter: &ItemFilter) -> Result<ItemPage> {
        let trimmed_search = filter.search.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let search_terms = trimmed_search.map_or_default(ItemFilter::tokenize_search_terms);
        let fts_query = trimmed_search.and_then(ItemFilter::build_fts_query);

        let base_select = if filter.include_content == Some(false) {
            ITEM_SELECT_METADATA_ONLY
        } else {
            ITEM_SELECT_LIST
        };
        let mut qb = QueryBuilder::<Sqlite>::new(base_select);
        if let Some(fts_match) = &fts_query {
            qb.push(
                " LEFT JOIN (SELECT rowid AS fts_rowid, bm25(entries_fts, 0.0, 10.0, 5.0, 2.0, 1.0) AS fts_rank FROM entries_fts WHERE entries_fts MATCH ",
            )
            .push_bind(fts_match.clone())
            .push(") fts ON fts.fts_rowid = i.rowid");
        }
        qb.push(" WHERE i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");

        if let Some(s) = filter.status.and_then(ItemStatusFilter::into_status) {
            qb.push(" AND es.inbox_state = ").push_bind(InboxState::from(s));
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
        if let Some(t) = &filter.tag_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_tf WHERE ce_tf.entry_id = i.id AND ce_tf.collection_id = ",
            )
            .push_bind(t)
            .push(")");
        }
        if let Some(ct) = filter.content_type {
            qb.push(" AND COALESCE(ec.content_type, 'article') = ").push_bind(ct);
        }
        if let Some(fav) = filter.is_favorite {
            if fav {
                qb.push(
                    " AND EXISTS (SELECT 1 FROM collection_entries ce_fav WHERE ce_fav.collection_id = ",
                )
                .push_bind(STARRED_COLLECTION_ID)
                .push(" AND ce_fav.entry_id = i.id)");
            } else {
                qb.push(
                    " AND NOT EXISTS (SELECT 1 FROM collection_entries ce_fav WHERE ce_fav.collection_id = ",
                )
                .push_bind(STARRED_COLLECTION_ID)
                .push(" AND ce_fav.entry_id = i.id)");
            }
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
        } else if sort_by_relevance {
            let rank_dir = match filter.order.unwrap_or_default() {
                SortOrder::Desc => "ASC",
                SortOrder::Asc => "DESC",
            };
            qb.push(format_args!(
                " ORDER BY CASE WHEN fts.fts_rank IS NULL THEN 1 ELSE 0 END ASC, fts.fts_rank {rank_dir}, COALESCE(i.published_at, i.created_at) DESC, i.id DESC"
            ));
        } else {
            let sort_col = filter.sort.unwrap_or_default().as_sql_expr();
            let sort_dir = filter.order.unwrap_or_default().as_sql();
            qb.push(format_args!(" ORDER BY {sort_col} {sort_dir}, i.id DESC"));
        }

        let per_page = filter.per_page.unwrap_or(50).clamp(1, 200);
        let page = filter.page.unwrap_or(1).max(1);
        let offset = page.saturating_sub(1).saturating_mul(per_page);
        let fetch_limit = per_page.saturating_add(1);
        qb.push(" LIMIT ")
            .push_bind(i64::try_from(fetch_limit).unwrap_or(51))
            .push(" OFFSET ")
            .push_bind(i64::try_from(offset).unwrap_or(0));

        let mut items = qb.build_query_as::<Item>().fetch_all(self.pool).await?;
        let has_more = items.len() > per_page;
        items.truncate(per_page);
        Ok(ItemPage { items, page, per_page, has_more })
    }

    /// Single keyset-paginated query for peer sync (both initial and delta sync), using
    /// `idx_entries_updated_at_iso` without `OFFSET` scans.
    /// Returns `(items, has_more, last_item_sqlite_iso_updated_at)`.
    pub(crate) async fn list_for_peer_sync(
        &self,
        updated_after: Option<DateTime<Utc>>,
        after_id: Option<&str>,
        limit: usize,
    ) -> Result<(Vec<Item>, bool, Option<DateTime<Utc>>)> {
        let cap = limit.max(1);
        let fetch_limit = cap.saturating_add(1);
        let mut qb = QueryBuilder::<Sqlite>::new(ITEM_SELECT_LIST);
        qb.push(" WHERE i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");
        Self::append_updated_after_where_clause(&mut qb, updated_after, after_id);
        qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) ASC, i.id ASC LIMIT ")
            .push_bind(i64::try_from(fetch_limit).unwrap_or(i64::MAX));

        let mut items = qb.build_query_as::<Item>().fetch_all(self.pool).await?;
        let has_more = items.len() > cap;
        items.truncate(cap);

        let boundary_ts = if has_more && let Some(last_item) = items.last() {
            let iso_str: Option<String> = sqlx::query_scalar!(
                "SELECT strftime('%Y-%m-%dT%H:%M:%fZ', updated_at) AS \"iso?: String\" FROM entries WHERE id = ?",
                last_item.id,
            )
            .fetch_optional(self.pool)
            .await?
            .flatten();
            iso_str
                .as_deref()
                .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
                .map(|dt| dt.with_timezone(&Utc))
        } else {
            None
        };

        Ok((items, has_more, boundary_ts))
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
        Ok(sqlx::query_scalar!(
            "SELECT COUNT(*) AS \"count!: i64\"
             FROM entry_state es
             JOIN entries e ON e.id = es.entry_id
             WHERE e.merged_into_id IS NULL AND es.inbox_state = ?",
            inbox_state as _,
        )
        .fetch_one(self.pool)
        .await?)
    }

    pub async fn count_unread(&self) -> Result<i64> {
        Ok(sqlx::query_scalar!(
            "SELECT COUNT(*) AS \"count!: i64\"
             FROM entry_state es
             JOIN entries e ON e.id = es.entry_id
             WHERE e.merged_into_id IS NULL
               AND es.inbox_state = 'unread'",
        )
        .fetch_one(self.pool)
        .await?)
    }
}
