mod feeds;
mod inbox;
mod reader;

use std::fmt::Write as _;

use serde::Deserialize;

use super::html::{esc, urlencode};
use crate::db::{ContentType, Item};

pub(super) use feeds::{
    export_opml_get, feeds_get, post_feed_create, post_feed_delete, post_feed_opml,
    post_feed_refresh, post_feed_refresh_all, post_feed_toggle_active, refresh_status_get,
};
pub(super) use inbox::{
    inbox_get, post_item_archive, post_item_archive_batch, post_item_save, post_item_star,
    post_item_unarchive, starred_get,
};
pub(super) use reader::{
    item_audio_get, post_item_finish, post_item_progress, post_item_tag_add, post_item_tag_remove,
    post_item_unfinish, reader_get, web_media_get,
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct InboxQuery {
    pub status: Option<String>,
    pub kind: Option<String>,
    pub feed: Option<String>,
    pub tag: Option<String>,
    pub q: Option<String>,
    pub page: Option<usize>,
    pub notice: Option<String>,
    pub err: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct SaveUrlForm {
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub tags: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ArchiveBatchForm {
    #[serde(default)]
    pub feed_id: Option<String>,
    #[serde(default)]
    pub ids: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ItemReturnForm {
    #[serde(default)]
    pub progress: Option<f64>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ProgressForm {
    pub progress: f64,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct FinishItemForm {
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddTagForm {
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateFeedForm {
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub feed_type: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct ImportOpmlForm {
    pub opml_xml: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct FeedToggleForm {
    pub active: String,
}

pub(super) fn render_audio_play_button(out: &mut String, item: &Item) {
    if item.content_type != ContentType::Audio && item.audio_url.is_none() {
        return;
    }
    let feed_label = item.feed_title.as_deref().unwrap_or("Podcast");
    let dur = item.duration.unwrap_or(0);
    let _ = write!(
        out,
        "<button type=\"button\" class=\"btn btn-small js-play-audio\" \
         data-item-id=\"{}\" data-title=\"{}\" data-feed=\"{}\" \
         data-src=\"/web/items/{}/audio\" data-progress=\"{:.4}\" data-duration=\"{dur}\" \
         aria-label=\"Play {}\" aria-pressed=\"false\">\
         <span aria-hidden=\"true\">&#9654;</span> Play</button>",
        esc(&item.id),
        esc(&item.title),
        esc(feed_label),
        esc(&urlencode(&item.id)),
        item.read_progress.clamp(0.0, 1.0),
        esc(&item.title),
    );
}
