use std::collections::HashMap;

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{PeerFeedRecord, PeerTagRecord};
use crate::db::{
    ActivityAction, ActivityEntityType, EntryKind, FeedRepo, FeedType, HexColor, TagRepo,
    feeds::NewFeedRow,
    sync::{
        SyncRepo,
        identity::{
            deterministic_feed_id, deterministic_tag_id, feed_natural_key, tag_natural_key,
        },
    },
};

impl SyncRepo<'_> {
    pub(super) async fn merge_incoming_tags(
        conn: &mut SqliteConnection,
        tags: &[PeerTagRecord],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for t in tags {
            let clean_name = t.name.trim();
            if clean_name.is_empty() {
                continue;
            }
            let nat_key = tag_natural_key(clean_name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                if t.created_at.is_none_or(|c| c <= del_at) {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &nat_key).await?;
            }

            let det_id = deterministic_tag_id(clean_name);
            let tag_slug = format!("tag:{nat_key}");
            let existing: Option<(String, Option<HexColor>)> = sqlx::query_as(
                "SELECT e.id, cs.color
                 FROM entries e
                 JOIN collection_specs cs ON cs.collection_id = e.id
                 WHERE cs.collection_role = 'tag'
                   AND (e.id = ? OR cs.slug = ? OR LOWER(e.title) = LOWER(?))
                 LIMIT 1",
            )
            .bind(&det_id)
            .bind(&tag_slug)
            .bind(clean_name)
            .fetch_optional(&mut *conn)
            .await?;

            if let Some((tag_id, existing_color)) = existing {
                if t.color.is_some() && existing_color != t.color {
                    sqlx::query("UPDATE collection_specs SET color = ? WHERE collection_id = ?")
                        .bind(&t.color)
                        .bind(&tag_id)
                        .execute(&mut *conn)
                        .await?;
                    sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                        .bind(now)
                        .bind(&tag_id)
                        .execute(&mut *conn)
                        .await?;
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &tag_id,
                        ActivityAction::Update,
                        now,
                    )
                    .await?;
                }
            } else {
                let created_at = t.created_at.unwrap_or(now);
                let _ = TagRepo::get_or_create_in_tx(
                    &mut *conn,
                    clean_name,
                    t.color.as_ref(),
                    created_at,
                    now,
                )
                .await?;
            }
        }
        Ok(())
    }

    pub(super) async fn merge_incoming_feeds(
        conn: &mut SqliteConnection,
        feeds: &[PeerFeedRecord],
        req_updated_after: Option<DateTime<Utc>>,
        now: DateTime<Utc>,
    ) -> std::result::Result<HashMap<String, (String, String)>, sqlx::Error> {
        let mut feed_map = HashMap::new();
        for f in feeds {
            Self::merge_single_feed_in_tx(&mut *conn, f, req_updated_after, &mut feed_map, now)
                .await?;
        }
        Ok(feed_map)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_feed_in_tx(
        conn: &mut SqliteConnection,
        f: &PeerFeedRecord,
        req_updated_after: Option<DateTime<Utc>>,
        feed_map: &mut HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        type ExistingFeedRow = (String, String, FeedType, Option<DateTime<Utc>>, bool);
        let feed_url = f.url.as_str();
        let nat_key = feed_natural_key(feed_url);
        let det_id = deterministic_feed_id(feed_url);
        let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
            "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'feed' AND natural_key = ?",
        )
        .bind(&nat_key)
        .fetch_optional(&mut *conn)
        .await?;
        if let Some(del_at) = tomb_ts {
            if f.created_at.is_none_or(|c| c <= del_at) {
                return Ok(());
            }
            Self::clear_tombstone(&mut *conn, ActivityEntityType::Feed, &nat_key).await?;
        }

        let existing: Option<ExistingFeedRow> = sqlx::query_as(
            "SELECT collection_id, feed_url, source_type, last_polled_at, is_active
             FROM feed_sources
             WHERE feed_url = ? OR collection_id = ? OR collection_id = ?
             LIMIT 1",
        )
        .bind(feed_url)
        .bind(&f.id)
        .bind(&det_id)
        .fetch_optional(&mut *conn)
        .await?;

        let clean_title = f.title.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_desc = f.description.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_lang = f.language.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err = f.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_polled_by = f.last_polled_by.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err_by = if clean_err.is_some() {
            f.last_error_by.as_deref().map(str::trim).filter(|s| !s.is_empty())
        } else {
            None
        };

        if let Some((local_id, local_url, local_feed_type, local_last_polled, local_is_active)) =
            existing
        {
            Self::update_existing_feed_in_tx(
                &mut *conn,
                &local_id,
                f,
                local_feed_type,
                local_last_polled,
                local_is_active,
                req_updated_after,
                clean_title,
                clean_desc,
                clean_lang,
                clean_err,
                clean_polled_by,
                clean_err_by,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), local_url.clone()));
            }
            feed_map.insert(local_id.clone(), (local_id, local_url));
        } else {
            let local_id = det_id;
            let interval = f.poll_interval.clamp(5, 1440);
            let created_at = f.created_at.unwrap_or(now);

            FeedRepo::insert_feed_in_tx(
                &mut *conn,
                &NewFeedRow {
                    id: &local_id,
                    feed_url,
                    feed_type: f.feed_type,
                    title: clean_title,
                    description: clean_desc,
                    language: clean_lang,
                    poll_interval: interval,
                    is_active: f.is_active,
                    last_polled: f.last_polled,
                    last_polled_by: clean_polled_by,
                    last_error: clean_err,
                    last_error_by: clean_err_by,
                    created_at,
                    updated_at: now,
                },
            )
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Feed,
                &local_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), feed_url.to_owned()));
            }
            feed_map.insert(local_id.clone(), (local_id, feed_url.to_owned()));
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    async fn update_existing_feed_in_tx(
        conn: &mut SqliteConnection,
        local_id: &str,
        f: &PeerFeedRecord,
        local_feed_type: FeedType,
        local_last_polled: Option<DateTime<Utc>>,
        local_is_active: bool,
        req_updated_after: Option<DateTime<Utc>>,
        clean_title: Option<&str>,
        clean_desc: Option<&str>,
        clean_lang: Option<&str>,
        clean_err: Option<&str>,
        clean_polled_by: Option<&str>,
        clean_err_by: Option<&str>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let upgrade_to_podcast =
            local_feed_type == FeedType::Rss && f.feed_type == FeedType::Podcast;
        if upgrade_to_podcast {
            sqlx::query("UPDATE feed_sources SET source_type = ? WHERE collection_id = ?")
                .bind(FeedType::Podcast)
                .bind(local_id)
                .execute(&mut *conn)
                .await?;
            sqlx::query("UPDATE entries SET kind = ?, updated_at = ? WHERE id = ?")
                .bind(EntryKind::Podcast)
                .bind(now)
                .bind(local_id)
                .execute(&mut *conn)
                .await?;
        }

        let incoming_poll_newer = match (f.last_polled, local_last_polled) {
            (Some(inc), Some(loc)) => inc > loc,
            (Some(_), None) => true,
            _ => false,
        };
        if incoming_poll_newer {
            sqlx::query(
                "UPDATE feed_sources
                 SET feed_title = COALESCE(feed_title, ?),
                     language = COALESCE(?, language),
                     last_polled_at = ?,
                     last_polled_by = ?,
                     last_error = ?,
                     last_error_by = ?
                 WHERE collection_id = ?",
            )
            .bind(clean_title)
            .bind(clean_lang)
            .bind(f.last_polled)
            .bind(clean_polled_by)
            .bind(clean_err)
            .bind(clean_err_by)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;

            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE((SELECT feed_title FROM feed_sources WHERE collection_id = ?), title),
                     summary = COALESCE(?, summary),
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(local_id)
            .bind(clean_desc)
            .bind(now)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        } else {
            sqlx::query(
                "UPDATE feed_sources
                 SET feed_title = COALESCE(feed_title, ?),
                     language = COALESCE(language, ?)
                 WHERE collection_id = ?",
            )
            .bind(clean_title)
            .bind(clean_lang)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;

            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE((SELECT feed_title FROM feed_sources WHERE collection_id = ?), title),
                     summary = COALESCE(summary, ?)
                 WHERE id = ?",
            )
            .bind(local_id)
            .bind(clean_desc)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        }

        if f.is_active != local_is_active {
            let last_archive_change_at: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT MAX(timestamp) FROM activity_log
                 WHERE entity_type = 'feed' AND entity_id = ? AND action IN ('archive', 'unarchive')",
            )
            .bind(local_id)
            .fetch_optional(&mut *conn)
            .await?
            .flatten();

            let should_update_active = match (req_updated_after, last_archive_change_at) {
                (Some(ua), Some(server_ts)) => server_ts <= ua,
                (Some(_), None) => true,
                (None, None) => !f.is_active,
                (None, Some(_)) => false,
            };

            if should_update_active {
                sqlx::query("UPDATE feed_sources SET is_active = ? WHERE collection_id = ?")
                    .bind(f.is_active)
                    .bind(local_id)
                    .execute(&mut *conn)
                    .await?;
                sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                    .bind(now)
                    .bind(local_id)
                    .execute(&mut *conn)
                    .await?;
                let action =
                    if f.is_active { ActivityAction::Unarchive } else { ActivityAction::Archive };
                Self::record_activity(&mut *conn, ActivityEntityType::Feed, local_id, action, now)
                    .await?;
            }
        }
        Ok(())
    }
}
