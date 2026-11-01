pub mod arxiv;
pub mod opml;
pub mod rss;
pub mod youtube;

pub use arxiv::ArxivParser;
use chrono::{DateTime, SubsecRound, Utc};
pub use opml::{OpmlDocument, OpmlOutline};
pub use rss::RssParser;
use url::Url;
pub use youtube::YouTubeParser;

use crate::content::fetcher::SafeHttpClient;

/// Shared helper to pick the preferred (`rel="alternate"`) validated HTTP(S) link from a `feed-rs` entry.
#[must_use]
pub fn select_entry_link(links: &[feed_rs::model::Link]) -> Option<Url> {
    links
        .iter()
        .find(|l| l.rel.as_deref().is_none_or(|r| r.eq_ignore_ascii_case("alternate")))
        .or_else(|| links.first())
        .and_then(|l| SafeHttpClient::parse_url(&l.href).ok())
}

/// Shared helper to extract the UTC publication/update timestamp (truncated to whole seconds) from a `feed-rs` entry.
#[must_use]
pub fn extract_entry_timestamp(entry: &feed_rs::model::Entry) -> Option<DateTime<Utc>> {
    entry.published.or(entry.updated).map(|dt| dt.with_timezone(&Utc).trunc_subsecs(0))
}
