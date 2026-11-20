use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::QueryBuilder;
use url::Url;

use super::{ItemRepo, mutations::maybe_auto_log_feed_completion_in_tx};
use crate::domain::{ActivityAction, ActivityEntityType, ArchiveBatchScope, EntryKind, InboxState};

impl ItemRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub async fn archive_batch(&self, scope: &ArchiveBatchScope) -> Result<u64> {
        const MAX_BATCH_IDS: usize = 5_000;
        const CHUNK_SIZE: usize = 400;

        let candidate_ids: Vec<String> = match scope {
            ArchiveBatchScope::Items { item_ids, feed_id } => {
                let mut unique_ids = Vec::new();
                let mut seen = HashSet::new();
                for raw_id in item_ids {
                    let trimmed = raw_id.trim();
                    if !trimmed.is_empty() && seen.insert(trimmed) {
                        unique_ids.push(trimmed);
                        if unique_ids.len() >= MAX_BATCH_IDS {
                            break;
                        }
                    }
                }
                if unique_ids.is_empty() {
                    return Ok(0);
                }
                let mut matched = Vec::new();
                for chunk in unique_ids.chunks(CHUNK_SIZE) {
                    let mut qb = QueryBuilder::new(
                        "SELECT es.entry_id FROM entry_state es
                         JOIN entries e ON e.id = es.entry_id
                         WHERE e.merged_into_id IS NULL
                           AND es.inbox_state = 'unread'
                           AND es.entry_id IN (",
                    );
                    let mut sep = qb.separated(", ");
                    for id in chunk {
                        sep.push_bind(*id);
                    }
                    sep.push_unseparated(")");
                    if let Some(fid) = feed_id.as_deref() {
                        qb.push(
                            " AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.entry_id = es.entry_id AND ce.collection_id = ",
                        )
                        .push_bind(fid)
                        .push(")");
                    }
                    let rows: Vec<String> = qb.build_query_scalar().fetch_all(self.pool).await?;
                    matched.extend(rows);
                }
                matched
            }
            ArchiveBatchScope::Feed { feed_id } => {
                let max_batch = i64::try_from(MAX_BATCH_IDS).unwrap_or(5_000);
                sqlx::query_scalar!(
                    "SELECT es.entry_id FROM entry_state es
                     JOIN entries e ON e.id = es.entry_id
                     WHERE e.merged_into_id IS NULL
                       AND es.inbox_state = 'unread'
                       AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.entry_id = es.entry_id AND ce.collection_id = ?)
                     LIMIT ?",
                    feed_id,
                    max_batch,
                )
                .fetch_all(self.pool)
                .await?
            }
            ArchiveBatchScope::AllUnread => {
                let max_batch = i64::try_from(MAX_BATCH_IDS).unwrap_or(5_000);
                sqlx::query_scalar(
                    "SELECT es.entry_id FROM entry_state es
                     JOIN entries e ON e.id = es.entry_id
                     WHERE e.merged_into_id IS NULL
                       AND es.inbox_state = 'unread'
                       AND EXISTS (
                           SELECT 1 FROM collection_entries ce
                           JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                           WHERE ce.entry_id = es.entry_id
                       )
                     LIMIT ?",
                )
                .bind(max_batch)
                .fetch_all(self.pool)
                .await?
            }
        };

        if candidate_ids.is_empty() {
            return Ok(0);
        }

        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let mut archived_ids = Vec::with_capacity(candidate_ids.len());
        let mut high_progress: Vec<(String, f64, Option<DateTime<Utc>>)> = Vec::new();

        for chunk in candidate_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new(
                "UPDATE entry_state SET inbox_state = 'dismissed', read_at = COALESCE(read_at, ",
            );
            qb.push_bind(now)
                .push("), interaction_updated_at = ")
                .push_bind(now)
                .push(", updated_at = ")
                .push_bind(now)
                .push(" WHERE inbox_state = 'unread' AND entry_id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id.as_str());
            }
            sep.push_unseparated(") RETURNING entry_id, progress, read_at");
            let updated: Vec<(String, f64, Option<DateTime<Utc>>)> =
                qb.build_query_as().fetch_all(&mut *tx).await?;
            for (id, progress, read_at) in updated {
                if progress >= 0.9 {
                    high_progress.push((id.clone(), progress, read_at));
                }
                archived_ids.push(id);
            }
        }

        for chunk in archived_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new("UPDATE entries SET updated_at = ");
            qb.push_bind(now).push(" WHERE id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id.as_str());
            }
            sep.push_unseparated(")");
            qb.build().execute(&mut *tx).await?;
        }

        // Batch-archived items whose progress had already reached >= 0.9 are auto-logged,
        // while unopened / skimmed bulk-archived items (progress < 0.9) never touch consumption_log.
        if !high_progress.is_empty() {
            let mut kind_map = std::collections::HashMap::with_capacity(high_progress.len());
            for chunk in high_progress.chunks(CHUNK_SIZE) {
                let mut qb = QueryBuilder::new("SELECT id, kind FROM entries WHERE id IN (");
                let mut sep = qb.separated(", ");
                for (id, _, _) in chunk {
                    sep.push_bind(id.as_str());
                }
                sep.push_unseparated(")");
                let kinds: Vec<(String, EntryKind)> =
                    qb.build_query_as().fetch_all(&mut *tx).await?;
                for (id, kind) in kinds {
                    kind_map.insert(id, kind);
                }
            }
            for (id, progress, read_at) in high_progress {
                if let Some(&kind) = kind_map.get(&id) {
                    maybe_auto_log_feed_completion_in_tx(
                        &mut tx,
                        &id,
                        kind,
                        InboxState::Dismissed,
                        progress,
                        read_at.unwrap_or(now),
                    )
                    .await?;
                }
            }
        }

        for chunk in archived_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new(
                "INSERT INTO activity_log (entity_type, entity_id, action, timestamp) ",
            );
            qb.push_values(chunk, |mut b, id| {
                b.push_bind(ActivityEntityType::Item)
                    .push_bind(id.as_str())
                    .push_bind(ActivityAction::Archive)
                    .push_bind(now);
            });
            qb.build().execute(&mut *tx).await?;
        }

        tx.commit().await?;
        Ok(u64::try_from(archived_ids.len()).unwrap_or(u64::MAX))
    }

    pub async fn normalize_legacy_html_summaries(
        &self,
        normalize: fn(&str, Option<&Url>) -> (String, Option<String>),
    ) -> Result<u64> {
        let candidates = sqlx::query!(
            "SELECT e.id,
                    COALESCE(e.url, '') AS \"url!: String\",
                    e.summary AS \"summary!: String\"
             FROM entries e
             LEFT JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.summary IS NOT NULL
               AND (
                 length(e.summary) > 320
                 OR (
                   (ec.content_html IS NULL OR ec.content_html = '' OR e.summary = ec.content_html)
                   AND (e.summary LIKE '%<%' OR e.summary LIKE '%&%;%')
                 )
               )",
        )
        .fetch_all(self.pool)
        .await?;

        if candidates.is_empty() {
            return Ok(0);
        }

        let normalized: Vec<(String, String, Option<String>)> =
            tokio::task::spawn_blocking(move || {
                candidates
                    .into_iter()
                    .filter_map(|row| {
                        let id = row.id;
                        let url_str = row.url;
                        let raw_summary = row.summary;
                        let base_url = Url::parse(&url_str).ok();
                        let (excerpt, thumb) = normalize(&raw_summary, base_url.as_ref());
                        if excerpt == raw_summary { None } else { Some((id, excerpt, thumb)) }
                    })
                    .collect()
            })
            .await?;

        if normalized.is_empty() {
            return Ok(0);
        }

        let count = u64::try_from(normalized.len()).unwrap_or(0);
        let mut tx = crate::db::begin_write(self.pool).await?;
        for (id, excerpt, thumb) in normalized {
            sqlx::query!(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 SELECT id, summary, 'article' FROM entries WHERE id = ?
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(NULLIF(entry_content.content_html, ''), (SELECT summary FROM entries WHERE id = ?))",
                id,
                id,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "UPDATE entries
                 SET thumbnail_url = COALESCE(thumbnail_url, ?),
                     summary = ?
                 WHERE id = ?",
                thumb,
                excerpt,
                id,
            )
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok(count)
    }

    pub async fn backfill_missing_thumbnails(
        &self,
        extract_thumb: fn(Option<&str>, Option<&Url>) -> Option<String>,
    ) -> Result<u64> {
        let candidates = sqlx::query!(
            "SELECT e.id,
                    COALESCE(e.url, '') AS \"url!: String\",
                    ec.content_html AS \"content_html?: String\",
                    e.thumbnail_url
             FROM entries e
             LEFT JOIN entry_content ec ON ec.entry_id = e.id
             WHERE (
                 e.thumbnail_url IS NULL
                 AND (
                   (ec.content_html IS NOT NULL AND (ec.content_html LIKE '%<img%' OR ec.content_html LIKE '%<IMG%'))
                   OR e.url LIKE '%youtube.com/%'
                   OR e.url LIKE '%youtu.be/%'
                 )
               )
               OR e.thumbnail_url LIKE '%.svg'
               OR e.thumbnail_url LIKE '%.svg?%'
               OR e.thumbnail_url LIKE '%/images/core/emoji/%'
               OR e.thumbnail_url LIKE '%/wpcom-smileys/%'
               OR e.thumbnail_url LIKE '%/twemoji/%'",
        )
        .fetch_all(self.pool)
        .await?;

        if candidates.is_empty() {
            return Ok(0);
        }

        let updates: Vec<(String, Option<String>)> = tokio::task::spawn_blocking(move || {
            candidates
                .into_iter()
                .filter_map(|row| {
                    let id = row.id;
                    let url_str = row.url;
                    let html_opt = row.content_html;
                    let old_thumb = row.thumbnail_url;
                    let base_url = Url::parse(&url_str).ok();
                    let new_thumb = extract_thumb(html_opt.as_deref(), base_url.as_ref());
                    (new_thumb != old_thumb).then_some((id, new_thumb))
                })
                .collect()
        })
        .await?;

        if updates.is_empty() {
            return Ok(0);
        }

        let count = u64::try_from(updates.len()).unwrap_or(0);
        let mut tx = crate::db::begin_write(self.pool).await?;
        for (id, thumb) in updates {
            sqlx::query!("UPDATE entries SET thumbnail_url = ? WHERE id = ?", thumb, id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok(count)
    }
}
