use std::collections::{HashMap, HashSet};

use chrono::{DateTime, SubsecRound, Utc};
use sqlx::SqliteConnection;

use super::{InsertedItemCandidate, PeerItemRecord};
use crate::{
    content::fetcher::{HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient, is_direct_audio_url},
    db::{
        ActivityAction, ActivityEntityType, CatalogRepo, ContentType, EntryKind, InboxState,
        ItemStatus, MediaRepo, PollItemCandidate, TagRepo,
        feeds::resolve_dedup_entry_id,
        items::set_favorite_membership_in_tx,
        sync::{
            SyncRepo,
            identity::{
                STARRED_COLLECTION_ID, canonicalize_sync_url, deterministic_feed_id,
                deterministic_item_id, item_natural_key, tag_natural_key,
            },
        },
    },
    feeds::arxiv::ArxivParser,
};

#[derive(Debug)]
pub(super) struct PreparedPeerItem {
    record: PeerItemRecord,
    final_title: String,
    clean_author: Option<String>,
    clean_summary: Option<String>,
    clean_html: Option<String>,
    clean_thumb: Option<String>,
    clean_audio_url: Option<String>,
    clean_duration: Option<i64>,
    resolved_content_type: ContentType,
    clamped_progress: f64,
    canon_url: String,
    arxiv_id: Option<String>,
}

impl PreparedPeerItem {
    pub(super) fn from_record(inc: PeerItemRecord) -> Self {
        let clean_title = inc.title.trim();
        let final_title = if clean_title.is_empty() {
            inc.url.as_str().to_owned()
        } else {
            clean_title.to_owned()
        };
        let clean_author =
            inc.author.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let clean_html = inc
            .content_html
            .as_deref()
            .map(|h| HtmlSanitizer::clean_with_base(h, Some(&inc.url)))
            .filter(|h| !h.trim().is_empty());
        let clean_summary = inc
            .summary
            .as_deref()
            .map(|s| HtmlSanitizer::plain_text_excerpt(s, MAX_SUMMARY_CHARS))
            .filter(|s| !s.is_empty())
            .or_else(|| {
                clean_html
                    .as_deref()
                    .map(|h| HtmlSanitizer::plain_text_excerpt_from_clean(h, MAX_SUMMARY_CHARS))
                    .filter(|s| !s.is_empty())
            });
        let clean_thumb = inc
            .thumbnail_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| HtmlSanitizer::resolve_and_validate_thumbnail_url(u, Some(&inc.url)))
            .or_else(|| {
                clean_html.as_deref().and_then(|h| {
                    HtmlSanitizer::extract_first_image_url_with_base(h, Some(&inc.url))
                })
            });
        let clean_audio_url = inc
            .audio_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| SafeHttpClient::parse_url(u).ok())
            .map(String::from)
            .or_else(|| {
                (inc.content_type == ContentType::Audio && is_direct_audio_url(&inc.url))
                    .then(|| inc.url.as_str().to_owned())
            });
        let clean_duration = inc.duration.filter(|&d| d > 0);
        let resolved_content_type =
            if clean_audio_url.is_some() { ContentType::Audio } else { inc.content_type };
        let clamped_progress =
            if inc.read_progress.is_finite() { inc.read_progress.clamp(0.0, 1.0) } else { 0.0 };
        let canon_url = canonicalize_sync_url(inc.url.as_str());
        let arxiv_id = ArxivParser::extract_arxiv_id(inc.url.as_str());

        Self {
            record: inc,
            final_title,
            clean_author,
            clean_summary,
            clean_html,
            clean_thumb,
            clean_audio_url,
            clean_duration,
            resolved_content_type,
            clamped_progress,
            canon_url,
            arxiv_id,
        }
    }
}

impl SyncRepo<'_> {
    async fn resolve_peer_item_feed(
        conn: &mut SqliteConnection,
        inc: &PeerItemRecord,
        feed_map: &HashMap<String, (String, String)>,
    ) -> std::result::Result<Option<(Option<String>, Option<String>)>, sqlx::Error> {
        if inc.feed_url.is_none() && inc.feed_id.is_none() {
            return Ok(Some((None, None)));
        }
        if let Some(ref f_url) = inc.feed_url {
            let det_fid = deterministic_feed_id(f_url.as_str());
            let found: Option<(String, String)> = sqlx::query_as(
                "SELECT collection_id, feed_url FROM feed_sources
                 WHERE feed_url = ? OR collection_id = ?
                 LIMIT 1",
            )
            .bind(f_url.as_str())
            .bind(&det_fid)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some((fid, url)) = found {
                return Ok(Some((Some(fid), Some(url))));
            }
        }
        if let Some(ref fid) = inc.feed_id {
            if let Some((mapped_id, mapped_url)) = feed_map.get(fid) {
                return Ok(Some((Some(mapped_id.clone()), Some(mapped_url.clone()))));
            }
            let found: Option<(String, String)> = sqlx::query_as(
                "SELECT collection_id, feed_url FROM feed_sources WHERE collection_id = ? LIMIT 1",
            )
            .bind(fid)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some((local_fid, url)) = found {
                return Ok(Some((Some(local_fid), Some(url))));
            }
        }
        Ok(None)
    }

    #[allow(clippy::too_many_lines)]
    pub(super) async fn merge_incoming_items(
        conn: &mut SqliteConnection,
        items: &[PreparedPeerItem],
        feed_map: &HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<Vec<InsertedItemCandidate>, sqlx::Error> {
        let mut inserted_candidates = Vec::new();

        for prep in items {
            let inc = &prep.record;
            let Some((resolved_feed_id, resolved_feed_url)) =
                Self::resolve_peer_item_feed(&mut *conn, inc, feed_map).await?
            else {
                continue;
            };

            let nat_key = item_natural_key(resolved_feed_url.as_deref(), inc.url.as_str());
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                let inc_created = inc.created_at.unwrap_or(del_at);
                let inc_interacted = inc.interaction_updated_at.unwrap_or(del_at);
                if inc_created <= del_at && inc_interacted <= del_at {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Item, &nat_key).await?;
            }

            let det_item_id = deterministic_item_id(resolved_feed_url.as_deref(), inc.url.as_str());

            let matched_id: Option<String> = {
                let by_inc = if inc.id.trim().is_empty() {
                    None
                } else {
                    CatalogRepo::follow_merged_id_in_tx(&mut *conn, &inc.id).await?
                };
                if by_inc.is_some() {
                    by_inc
                } else {
                    resolve_dedup_entry_id(
                        &mut *conn,
                        &det_item_id,
                        &prep.canon_url,
                        prep.arxiv_id.as_deref(),
                    )
                    .await?
                }
            };

            let existing: Option<ExistingItemRow> = if let Some(ref eid) = matched_id {
                sqlx::query_as(
                    "SELECT e.id,
                            e.kind,
                            e.title,
                            (SELECT group_concat(value, ', ') FROM json_each(e.creators_json)) AS author,
                            e.published_at,
                            e.summary,
                            ec.content_html,
                            e.thumbnail_url,
                            (SELECT m.remote_url FROM entry_media m WHERE m.entry_id = e.id AND m.media_type = 'audio' AND m.remote_url IS NOT NULL ORDER BY m.created_at DESC LIMIT 1) AS audio_url,
                            (SELECT m.duration FROM entry_media m WHERE m.entry_id = e.id AND m.duration IS NOT NULL ORDER BY m.created_at DESC LIMIT 1) AS duration,
                            COALESCE(ec.content_type, 'article') AS content_type,
                            CASE COALESCE(es.inbox_state, 'unread') WHEN 'dismissed' THEN 'archived' ELSE 'unread' END AS status,
                            EXISTS (SELECT 1 FROM collection_entries ce_fav WHERE ce_fav.collection_id = '533b1315-6e2e-57d3-ba85-dc88531c5a0b' AND ce_fav.entry_id = e.id) AS is_favorite,
                            es.read_at,
                            COALESCE(es.progress, 0.0) AS read_progress,
                            es.interaction_updated_at,
                            ec.entry_id IS NOT NULL AS has_entry_content,
                            es.inbox_state AS raw_inbox_state
                     FROM entries e
                     LEFT JOIN entry_content ec ON ec.entry_id = e.id
                     LEFT JOIN entry_state es ON es.entry_id = e.id
                     WHERE e.id = ?",
                )
                .bind(eid)
                .fetch_optional(&mut *conn)
                .await?
            } else {
                None
            };

            if let Some(ex) = existing {
                if let Some(ref fid) = resolved_feed_id {
                    sqlx::query(
                        "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
                         VALUES (?, ?, ?)",
                    )
                    .bind(fid)
                    .bind(&ex.id)
                    .bind(now)
                    .execute(&mut *conn)
                    .await?;
                }
                Self::merge_existing_item_in_tx(&mut *conn, &ex, prep, now).await?;
            } else {
                let item_id = det_item_id;
                let created_at = inc.created_at.unwrap_or(now);
                let insert_inter_ts = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
                let insert_read_at = if inc.status == ItemStatus::Archived {
                    inc.read_at.or(insert_inter_ts).or(Some(now))
                } else {
                    inc.read_at
                };
                let entry_kind = EntryKind::from_content_type(prep.resolved_content_type);
                let creators_json = prep
                    .clean_author
                    .as_deref()
                    .and_then(|a| serde_json::to_string(&[a]).ok())
                    .unwrap_or_else(|| "[]".to_owned());
                let inbox_state = InboxState::from(inc.status);

                let res = sqlx::query(
                    "INSERT OR IGNORE INTO entries
                     (id, kind, title, creators_json, url, published_at, summary, thumbnail_url, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(&item_id)
                .bind(entry_kind)
                .bind(&prep.final_title)
                .bind(&creators_json)
                .bind(inc.url.as_str())
                .bind(inc.published_at)
                .bind(prep.clean_summary.as_deref())
                .bind(prep.clean_thumb.as_deref())
                .bind(created_at)
                .bind(now)
                .execute(&mut *conn)
                .await?;

                if res.rows_affected() > 0 {
                    sqlx::query(
                        "INSERT INTO entry_content (entry_id, content_html, content_type)
                         VALUES (?, ?, ?)",
                    )
                    .bind(&item_id)
                    .bind(prep.clean_html.as_deref())
                    .bind(prep.resolved_content_type)
                    .execute(&mut *conn)
                    .await?;

                    sqlx::query(
                        "INSERT INTO entry_state
                         (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?)",
                    )
                    .bind(&item_id)
                    .bind(inbox_state)
                    .bind(insert_read_at)
                    .bind(prep.clamped_progress)
                    .bind(insert_inter_ts)
                    .bind(now)
                    .execute(&mut *conn)
                    .await?;

                    sqlx::query(
                        "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                         VALUES ('url', ?, ?)",
                    )
                    .bind(&prep.canon_url)
                    .bind(&item_id)
                    .execute(&mut *conn)
                    .await?;

                    if let Some(ref aid) = prep.arxiv_id {
                        sqlx::query(
                            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                             VALUES ('arxiv', ?, ?)",
                        )
                        .bind(aid)
                        .bind(&item_id)
                        .execute(&mut *conn)
                        .await?;
                    }

                    if let Some(ref fid) = resolved_feed_id {
                        sqlx::query(
                            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
                             VALUES (?, ?, ?)",
                        )
                        .bind(fid)
                        .bind(&item_id)
                        .bind(created_at)
                        .execute(&mut *conn)
                        .await?;
                    }

                    if inc.is_favorite {
                        set_favorite_membership_in_tx(&mut *conn, &item_id, true, now).await?;
                    }

                    MediaRepo::upsert_audio_enclosure_in_tx(
                        &mut *conn,
                        &item_id,
                        prep.clean_audio_url.as_deref(),
                        prep.clean_duration,
                        created_at,
                    )
                    .await?;

                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Item,
                        &item_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                    Self::replace_item_tags_in_tx(
                        &mut *conn,
                        &item_id,
                        &inc.tag_names,
                        now,
                        false,
                        insert_inter_ts,
                    )
                    .await?;
                    inserted_candidates.push((
                        item_id,
                        PollItemCandidate {
                            title: prep.final_title.clone(),
                            url: inc.url.clone(),
                            author: prep.clean_author.clone(),
                            published_at: inc.published_at,
                            summary: prep.clean_summary.clone().unwrap_or_default(),
                            content_html: prep.clean_html.clone(),
                            thumbnail_url: prep.clean_thumb.clone(),
                            audio_url: prep.clean_audio_url.clone(),
                            duration: prep.clean_duration,
                            content_type: prep.resolved_content_type,
                        },
                    ));
                }
            }
        }

        Ok(inserted_candidates)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_existing_item_in_tx(
        conn: &mut SqliteConnection,
        ex: &ExistingItemRow,
        prep: &PreparedPeerItem,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let inc = &prep.record;
        let final_title = prep.final_title.as_str();
        let clean_author = prep.clean_author.as_deref();
        let clean_summary = prep.clean_summary.as_deref();
        let clean_html = prep.clean_html.as_deref();
        let clean_thumb = prep.clean_thumb.as_deref();
        let clean_audio_url = prep.clean_audio_url.as_deref();
        let clean_duration = prep.clean_duration;
        let resolved_content_type = prep.resolved_content_type;
        let clamped_progress = prep.clamped_progress;

        let merged_title = if (ex.title.trim().is_empty() || ex.title == inc.url.as_str())
            && !final_title.is_empty()
        {
            final_title
        } else {
            ex.title.as_str()
        };
        let merged_author = ex.author.as_deref().or(clean_author);
        let merged_pub = ex.published_at.or(inc.published_at);
        let merged_summary = pick_richer_text(ex.summary.as_deref(), clean_summary);
        let merged_html = pick_richer_text(ex.content_html.as_deref(), clean_html);
        let merged_thumb = ex.thumbnail_url.as_deref().or(clean_thumb);
        let merged_audio_url = ex.audio_url.as_deref().or(clean_audio_url);
        let merged_duration = ex.duration.or(clean_duration);
        let merged_content_type = if merged_audio_url.is_some()
            || ex.content_type == ContentType::Audio
            || resolved_content_type == ContentType::Audio
        {
            ContentType::Audio
        } else {
            ex.content_type
        };

        let content_changed = merged_title != ex.title
            || merged_author != ex.author.as_deref()
            || merged_pub != ex.published_at
            || merged_summary != ex.summary.as_deref()
            || merged_html != ex.content_html.as_deref()
            || merged_thumb != ex.thumbnail_url.as_deref()
            || merged_audio_url != ex.audio_url.as_deref()
            || merged_duration != ex.duration
            || merged_content_type != ex.content_type;

        let (target_status, target_fav, target_read_at, target_prog, target_inter_ts, tag_mode) =
            resolve_merged_interaction_state(ex, inc, clamped_progress, now);
        let target_inbox = InboxState::from(target_status);
        let target_kind =
            if merged_content_type == ContentType::Audio && ex.kind == EntryKind::Article {
                EntryKind::PodcastEpisode
            } else {
                ex.kind
            };

        let interaction_changed = target_status != ex.status
            || target_fav != ex.is_favorite
            || target_read_at != ex.read_at
            || (target_prog - ex.read_progress).abs() > f64::EPSILON
            || target_inter_ts != ex.interaction_updated_at;
        let state_missing_or_mismatch =
            !ex.has_entry_content || ex.raw_inbox_state != Some(target_inbox);

        if content_changed || interaction_changed || state_missing_or_mismatch {
            let creators_json = merged_author
                .and_then(|a| serde_json::to_string(&[a]).ok())
                .unwrap_or_else(|| "[]".to_owned());

            sqlx::query(
                "UPDATE entries
                 SET kind = ?,
                     title = ?,
                     creators_json = CASE WHEN creators_json = '[]' THEN ? ELSE creators_json END,
                     published_at = ?,
                     summary = ?,
                     thumbnail_url = ?,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(target_kind)
            .bind(merged_title)
            .bind(&creators_json)
            .bind(merged_pub)
            .bind(merged_summary)
            .bind(merged_thumb)
            .bind(now)
            .bind(&ex.id)
            .execute(&mut *conn)
            .await?;

            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = excluded.content_html,
                     content_type = excluded.content_type",
            )
            .bind(&ex.id)
            .bind(merged_html)
            .bind(merged_content_type)
            .execute(&mut *conn)
            .await?;

            sqlx::query(
                "INSERT INTO entry_state
                 (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = excluded.inbox_state,
                     read_at = excluded.read_at,
                     progress = excluded.progress,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at",
            )
            .bind(&ex.id)
            .bind(target_inbox)
            .bind(target_read_at)
            .bind(target_prog)
            .bind(target_inter_ts)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            if target_fav != ex.is_favorite {
                set_favorite_membership_in_tx(&mut *conn, &ex.id, target_fav, now).await?;
            }

            MediaRepo::upsert_audio_enclosure_in_tx(
                &mut *conn,
                &ex.id,
                merged_audio_url,
                merged_duration,
                now,
            )
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Item,
                &ex.id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        if let TagMergeMode::Replace | TagMergeMode::Union = tag_mode {
            let clear_unlisted = matches!(tag_mode, TagMergeMode::Replace);
            Self::replace_item_tags_in_tx(
                &mut *conn,
                &ex.id,
                &inc.tag_names,
                now,
                clear_unlisted,
                target_inter_ts,
            )
            .await?;
        }

        let _ = STARRED_COLLECTION_ID;
        Ok(())
    }

    async fn replace_item_tags_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        tag_names: &[String],
        now: DateTime<Utc>,
        clear_unlisted: bool,
        preserved_interaction_ts: Option<DateTime<Utc>>,
    ) -> std::result::Result<(), sqlx::Error> {
        let mut normalized_set = HashSet::new();
        let mut clean_names = Vec::new();
        for raw in tag_names {
            let trimmed = raw.trim();
            if !trimmed.is_empty() && normalized_set.insert(trimmed.to_lowercase()) {
                clean_names.push(trimmed.to_owned());
            }
        }

        let mut surviving_names = Vec::new();
        let mut surviving_lower = HashSet::new();
        for name in clean_names {
            let nat_key = tag_natural_key(&name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts
                && preserved_interaction_ts.is_none_or(|ts| ts <= del_at)
            {
                continue;
            }
            surviving_lower.insert(name.to_lowercase());
            surviving_names.push(name);
        }

        let existing_tag_names: Vec<String> = sqlx::query_scalar(
            "SELECT e.title
             FROM collection_entries ce
             JOIN collection_specs cs ON cs.collection_id = ce.collection_id
             JOIN entries e ON e.id = ce.collection_id
             WHERE ce.entry_id = ? AND cs.collection_role = 'tag' AND e.merged_into_id IS NULL",
        )
        .bind(item_id)
        .fetch_all(&mut *conn)
        .await?;
        let existing_lower: HashSet<String> =
            existing_tag_names.into_iter().map(|t| t.to_lowercase()).collect();

        let tags_changed = if clear_unlisted {
            existing_lower != surviving_lower
        } else {
            surviving_lower.iter().any(|t| !existing_lower.contains(t))
        };

        let current_inter_ts: Option<DateTime<Utc>> =
            sqlx::query_scalar("SELECT interaction_updated_at FROM entry_state WHERE entry_id = ?")
                .bind(item_id)
                .fetch_optional(&mut *conn)
                .await?
                .flatten();
        let inter_ts_changed = current_inter_ts.map(|t| t.trunc_subsecs(3))
            != preserved_interaction_ts.map(|t| t.trunc_subsecs(3));

        if !tags_changed && !inter_ts_changed {
            return Ok(());
        }

        if clear_unlisted && tags_changed {
            sqlx::query(
                "DELETE FROM collection_entries
                 WHERE entry_id = ?
                   AND collection_id IN (
                       SELECT collection_id FROM collection_specs WHERE collection_role = 'tag'
                   )",
            )
            .bind(item_id)
            .execute(&mut *conn)
            .await?;
        }

        if tags_changed {
            for name in surviving_names {
                let _ = TagRepo::get_or_create_and_attach_in_tx(&mut *conn, item_id, &name, now)
                    .await?;
            }
        }

        sqlx::query(
            "UPDATE entry_state SET interaction_updated_at = ?, updated_at = ? WHERE entry_id = ?",
        )
        .bind(preserved_interaction_ts)
        .bind(now)
        .bind(item_id)
        .execute(&mut *conn)
        .await?;
        sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
            .bind(now)
            .bind(item_id)
            .execute(&mut *conn)
            .await?;
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TagMergeMode {
    Replace,
    Union,
    KeepExisting,
}

#[derive(Debug, sqlx::FromRow)]
struct ExistingItemRow {
    id: String,
    kind: EntryKind,
    title: String,
    author: Option<String>,
    published_at: Option<DateTime<Utc>>,
    summary: Option<String>,
    content_html: Option<String>,
    thumbnail_url: Option<String>,
    audio_url: Option<String>,
    duration: Option<i64>,
    content_type: ContentType,
    status: ItemStatus,
    is_favorite: bool,
    read_at: Option<DateTime<Utc>>,
    read_progress: f64,
    interaction_updated_at: Option<DateTime<Utc>>,
    has_entry_content: bool,
    raw_inbox_state: Option<InboxState>,
}

fn pick_richer_text<'a>(existing: Option<&'a str>, incoming: Option<&'a str>) -> Option<&'a str> {
    match (
        existing.map(str::trim).filter(|s| !s.is_empty()),
        incoming.map(str::trim).filter(|s| !s.is_empty()),
    ) {
        (Some(ex), Some(inc)) => {
            if inc.len() > ex.len() {
                incoming
            } else {
                existing
            }
        }
        (Some(_), None) => existing,
        (None, Some(_)) => incoming,
        (None, None) => None,
    }
}

type MergedInteractionState =
    (ItemStatus, bool, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, TagMergeMode);

fn resolve_merged_interaction_state(
    ex: &ExistingItemRow,
    inc: &PeerItemRecord,
    clamped_progress: f64,
    now: DateTime<Utc>,
) -> MergedInteractionState {
    let inc_norm = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let loc_norm = ex.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let (status, fav, raw_read_at, prog, inter_ts, tag_mode) = match (inc_norm, loc_norm) {
        (Some(inc_ts), None) => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts > loc_ts => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts == loc_ts => (
            if ex.status == ItemStatus::Archived || inc.status == ItemStatus::Archived {
                ItemStatus::Archived
            } else {
                ItemStatus::Unread
            },
            ex.is_favorite || inc.is_favorite,
            ex.read_at.max(inc.read_at),
            ex.read_progress.max(clamped_progress),
            Some(loc_ts),
            TagMergeMode::Union,
        ),
        _ => (
            ex.status,
            ex.is_favorite,
            ex.read_at,
            ex.read_progress,
            ex.interaction_updated_at,
            if ex.interaction_updated_at.is_none() && !inc.tag_names.is_empty() {
                TagMergeMode::Union
            } else {
                TagMergeMode::KeepExisting
            },
        ),
    };
    let read_at = if status == ItemStatus::Archived {
        raw_read_at.or(ex.read_at).or(inc.read_at).or(inter_ts).or(Some(now))
    } else {
        raw_read_at
    };
    (status, fav, read_at, prog, tag_mode_inter_ts(inter_ts), tag_mode)
}

const fn tag_mode_inter_ts(inter_ts: Option<DateTime<Utc>>) -> Option<DateTime<Utc>> {
    inter_ts
}
