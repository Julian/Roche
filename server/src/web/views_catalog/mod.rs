mod diary_queue;
mod entry;
mod lists;

use std::{collections::HashSet, fmt::Write as _};

use serde::Deserialize;

use super::html::{FlashQuery, de_opt_from_str, esc};
use crate::{
    db::CatalogEntry,
    domain::{ConsumptionStatus, EntryKind},
    service::RocheService,
};

pub(super) use diary_queue::{
    diary_get, post_quick_log, post_want_complete, post_want_remove, queue_get,
};
pub(super) use entry::{
    entry_detail_get, post_artist_add_album, post_entry_list_add, post_entry_log, post_entry_merge,
};
pub(super) use lists::{
    list_detail_get, lists_get, post_list_add_entry, post_list_create, post_list_remove_entry,
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListsOverviewQuery {
    pub q: Option<String>,
    pub kind: Option<String>,
    #[serde(flatten)]
    pub flash: FlashQuery,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListPageQuery {
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub page: Option<usize>,
    #[serde(flatten)]
    pub flash: FlashQuery,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct DiaryFilterQuery {
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub year: Option<i32>,
    pub status: Option<String>,
    #[serde(flatten)]
    pub flash: FlashQuery,
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateListForm {
    pub title: String,
    #[serde(default)]
    pub summary: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddListEntryForm {
    pub title_or_id: String,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub creators: Option<String>,
    #[serde(default)]
    pub release_year: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryLogForm {
    pub status: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryAddToListForm {
    pub list_slug_or_id: String,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryMergeForm {
    pub canonical_query: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddArtistAlbumForm {
    pub title: String,
    #[serde(default)]
    pub release_year: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub apple_music_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct QuickLogForm {
    pub title: String,
    pub kind: String,
    #[serde(default)]
    pub creators: Option<String>,
    #[serde(default)]
    pub release_year: Option<String>,
    #[serde(default)]
    pub identifier: Option<String>,
    pub action: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct WantCompleteForm {
    #[serde(default)]
    pub rank_after: Option<String>,
}

pub(super) fn render_kind_options(selected: EntryKind) -> String {
    const LOGGABLE: [EntryKind; 10] = [
        EntryKind::Movie,
        EntryKind::Album,
        EntryKind::Book,
        EntryKind::Tv,
        EntryKind::Game,
        EntryKind::Song,
        EntryKind::Article,
        EntryKind::Paper,
        EntryKind::Video,
        EntryKind::PodcastEpisode,
    ];
    let mut out = String::with_capacity(512);
    for k in LOGGABLE {
        let sel = if k == selected { " selected" } else { "" };
        let _ = write!(
            out,
            "<option value=\"{}\"{sel}>{}</option>",
            k.as_str(),
            esc(k.display_label())
        );
    }
    out
}

pub(super) fn render_search_kind_options(selected: Option<EntryKind>) -> String {
    const SEARCHABLE: [EntryKind; 14] = [
        EntryKind::Album,
        EntryKind::Artist,
        EntryKind::Movie,
        EntryKind::Book,
        EntryKind::Tv,
        EntryKind::Game,
        EntryKind::Song,
        EntryKind::Article,
        EntryKind::Paper,
        EntryKind::Podcast,
        EntryKind::PodcastEpisode,
        EntryKind::Video,
        EntryKind::Publication,
        EntryKind::Channel,
    ];
    let mut out = String::with_capacity(640);
    let all_sel = if selected.is_none() { " selected" } else { "" };
    let _ = write!(out, "<option value=\"\"{all_sel}>All kinds</option>");
    for k in SEARCHABLE {
        let sel = if selected == Some(k) { " selected" } else { "" };
        let _ = write!(
            out,
            "<option value=\"{}\"{sel}>{}</option>",
            k.as_str(),
            esc(k.display_label())
        );
    }
    out
}

pub(super) fn parse_optional_year(raw: Option<&str>) -> Option<i32> {
    raw.map(str::trim).filter(|s| !s.is_empty()).and_then(|s| s.parse::<i32>().ok())
}

pub(super) fn parse_consumption_status(raw: &str) -> ConsumptionStatus {
    match raw.trim().to_ascii_lowercase().as_str() {
        "started" => ConsumptionStatus::Started,
        "abandoned" => ConsumptionStatus::Abandoned,
        _ => ConsumptionStatus::Consumed,
    }
}

pub(super) async fn load_existing_reader_item_ids<'a>(
    service: &RocheService,
    entries: impl IntoIterator<Item = &'a CatalogEntry>,
) -> HashSet<String> {
    let candidate_ids: Vec<String> = entries
        .into_iter()
        .filter(|entry| entry.kind.is_reader_openable() && entry.url.is_some())
        .map(|entry| entry.id.clone())
        .collect();
    if candidate_ids.is_empty() {
        return HashSet::new();
    }
    service.items().existing_ids(&candidate_ids).await.unwrap_or_default()
}
