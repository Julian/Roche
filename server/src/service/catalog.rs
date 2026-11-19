use std::{collections::HashMap, ops::Deref};

use crate::{
    db::{
        CatalogEntry, CatalogError, CatalogRepo, CatalogResult, ClusterSelectFilter,
        CollectionSummary, Db, EntryIdentifier, UpsertEntryInput,
    },
    ranking::Score,
};

const MAX_COVERED_CLUSTER_RETRIES: usize = 4;

/// Domain service wrapping [`CatalogRepo`] with shared cross-surface orchestration
/// used identically by the REST API, SSR Web UI, and CLI/TUI.
#[derive(Clone, Copy)]
pub struct CatalogService<'a> {
    repo: CatalogRepo<'a>,
}

impl<'a> CatalogService<'a> {
    #[must_use]
    pub const fn new(db: &'a Db) -> Self {
        Self { repo: db.catalog() }
    }

    /// Returns `true` when `slug_or_id` contains at least 2 rankable entries.
    pub async fn can_rank_collection(&self, slug_or_id: &str, want: bool) -> CatalogResult<bool> {
        Ok(self.repo.count_rankable_members(slug_or_id, want).await? >= 2)
    }

    /// Determines whether an entry just logged as `consumed` can immediately enter a
    /// focused k-way ranking session (`Some(default_list_slug)` iff `>= 2` items exist).
    pub async fn rank_target_after_consumption(
        &self,
        entry: &CatalogEntry,
    ) -> CatalogResult<Option<String>> {
        let Some(slug) = entry.kind.default_list_slug() else {
            return Ok(None);
        };
        if self.can_rank_collection(slug, false).await? {
            Ok(Some(slug.to_owned()))
        } else {
            Ok(None)
        }
    }

    /// Selects the next k-way comparison cluster for `slug_or_id`, retrying with random
    /// seed selection if `is_covered` reports that the candidate cluster is already covered
    /// by clusters judged earlier in the current session.
    pub async fn select_next_uncovered_cluster<F>(
        &self,
        slug_or_id: &str,
        want: bool,
        base_filter: &ClusterSelectFilter,
        is_covered: F,
    ) -> CatalogResult<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>)>
    where
        F: Fn(&[CatalogEntry]) -> bool,
    {
        self.repo
            .select_next_cluster_with_retries(
                slug_or_id,
                want,
                base_filter,
                MAX_COVERED_CLUSTER_RETRIES,
                is_covered,
            )
            .await
    }

    /// Adds an entry to a Want queue, first attempting to resolve an existing catalog entry
    /// when `has_explicit_metadata` is `false` so `roche want movie <id_or_title>` reuses
    /// existing entries instead of creating duplicate title-keyed placeholders.
    pub async fn want_resolved_or_upsert(
        &self,
        input: &UpsertEntryInput,
        has_explicit_metadata: bool,
        notes: Option<&str>,
        queue: Option<&str>,
    ) -> CatalogResult<(CollectionSummary, CatalogEntry)> {
        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());
        if !has_explicit_metadata
            && let Ok(existing) = self.repo.resolve_entry(&input.title, input.kind, None).await
        {
            let mut merged_meta = input.metadata.clone().unwrap_or_else(|| serde_json::json!({}));
            if let Some(note_text) = clean_notes
                && let Some(obj) = merged_meta.as_object_mut()
            {
                obj.insert(
                    "want_notes".to_owned(),
                    serde_json::Value::String(note_text.to_owned()),
                );
            }
            let resolved_input = UpsertEntryInput {
                id: Some(existing.id),
                kind: Some(existing.kind),
                title: existing.title,
                creators: existing.creators,
                url: existing.url,
                published_at: existing.published_at,
                release_year: existing.release_year,
                summary: existing.summary,
                thumbnail_url: existing.thumbnail_url,
                metadata: clean_notes.map(|_| merged_meta),
                identifiers: Vec::new(),
                parent_collection_id: None,
                parent_position: None,
            };
            return self.repo.want_entry(&resolved_input, queue).await;
        }

        let mut enriched = input.clone();
        if let Some(note_text) = clean_notes {
            let mut meta = enriched.metadata.take().unwrap_or_else(|| serde_json::json!({}));
            if let Some(obj) = meta.as_object_mut() {
                obj.insert(
                    "want_notes".to_owned(),
                    serde_json::Value::String(note_text.to_owned()),
                );
            }
            enriched.metadata = Some(meta);
        }
        self.repo.want_entry(&enriched, queue).await
    }

    /// Parses an explicit CLI `--id` flag (`scheme=value`, `spotify:...`, or `scheme:value`).
    pub fn parse_cli_identifier(raw: &str) -> Result<EntryIdentifier, CatalogError> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Err(CatalogError::InvalidInput("Identifier cannot be empty".to_owned()));
        }
        if let Some((scheme, val)) = trimmed.split_once('=') {
            let s = scheme.trim();
            let v = val.trim();
            if s.is_empty() || v.is_empty() {
                return Err(CatalogError::InvalidInput(format!(
                    "Identifier '{raw}' must have non-empty scheme and value"
                )));
            }
            return Ok(EntryIdentifier { scheme: s.to_owned(), value: v.to_owned() });
        }
        if trimmed.starts_with("spotify:") {
            return Ok(EntryIdentifier { scheme: "spotify".to_owned(), value: trimmed.to_owned() });
        }
        if let Some((scheme, val)) = trimmed.split_once(':')
            && !matches!(scheme.to_ascii_lowercase().as_str(), "http" | "https")
        {
            let s = scheme.trim();
            let v = val.trim();
            if !s.is_empty() && !v.is_empty() {
                return Ok(EntryIdentifier { scheme: s.to_owned(), value: v.to_owned() });
            }
        }
        Err(CatalogError::InvalidInput(format!(
            "Identifier '{raw}' must use `scheme=value` format"
        )))
    }

    /// Parses a free-form web/quick-log identifier field (HTTP URL, Spotify URI/URL, `IMDb` `tt...`,
    /// ISBN-10/13, `scheme=value`, `scheme:value`, or bare external ID).
    #[must_use]
    pub fn parse_smart_identifier(raw: Option<&str>) -> (Option<String>, Vec<EntryIdentifier>) {
        let Some(s) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
            return (None, Vec::new());
        };
        if s.starts_with("spotify:") || s.contains("open.spotify.com/") {
            let url_opt =
                (s.starts_with("https://") || s.starts_with("http://")).then(|| s.to_owned());
            return (
                url_opt,
                vec![EntryIdentifier { scheme: "spotify".to_owned(), value: s.to_owned() }],
            );
        }
        if s.starts_with("https://") || s.starts_with("http://") {
            return (
                Some(s.to_owned()),
                vec![EntryIdentifier { scheme: "url".to_owned(), value: s.to_owned() }],
            );
        }
        if s.starts_with("tt") && s.len() >= 5 && s[2..].chars().all(|c| c.is_ascii_digit()) {
            return (
                None,
                vec![EntryIdentifier { scheme: "imdb".to_owned(), value: s.to_owned() }],
            );
        }
        if looks_like_isbn(s) {
            return (
                None,
                vec![EntryIdentifier { scheme: "isbn".to_owned(), value: s.to_owned() }],
            );
        }
        if let Some((scheme, val)) = s.split_once('=') {
            let sc = scheme.trim();
            let vl = val.trim();
            if !sc.is_empty() && !vl.is_empty() {
                return (
                    None,
                    vec![EntryIdentifier { scheme: sc.to_owned(), value: vl.to_owned() }],
                );
            }
        }
        if let Some((scheme, val)) = s.split_once(':') {
            let sc = scheme.trim();
            let vl = val.trim();
            if !sc.is_empty() && !vl.is_empty() {
                return (
                    None,
                    vec![EntryIdentifier { scheme: sc.to_owned(), value: vl.to_owned() }],
                );
            }
        }
        (None, vec![EntryIdentifier { scheme: "external".to_owned(), value: s.to_owned() }])
    }
}

fn looks_like_isbn(raw: &str) -> bool {
    if !raw.chars().all(|c| c.is_ascii_digit() || c == '-' || c == ' ' || c == 'X' || c == 'x') {
        return false;
    }
    let stripped: String = raw.chars().filter(|c| *c != '-' && *c != ' ').collect();
    match stripped.len() {
        10 => {
            stripped[..9].chars().all(|c| c.is_ascii_digit())
                && matches!(stripped.as_bytes()[9], b'0'..=b'9' | b'X' | b'x')
        }
        13 => {
            (stripped.starts_with("978") || stripped.starts_with("979"))
                && stripped.chars().all(|c| c.is_ascii_digit())
        }
        _ => false,
    }
}

impl<'a> Deref for CatalogService<'a> {
    type Target = CatalogRepo<'a>;

    fn deref(&self) -> &Self::Target {
        &self.repo
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::{ConsumptionStatus, Db, EntryKind};

    #[tokio::test]
    async fn test_rank_target_after_consumption_requires_at_least_two_items() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let m1 = svc
            .upsert_entry(&UpsertEntryInput {
                id: None,
                kind: Some(EntryKind::Movie),
                title: "Stalker".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1979),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                parent_collection_id: None,
                parent_position: None,
            })
            .await
            .unwrap();
        svc.log_consumption(&m1.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // First movie ever logged: cannot rank against itself!
        assert_eq!(svc.rank_target_after_consumption(&m1).await.unwrap(), None);

        let m2 = svc
            .upsert_entry(&UpsertEntryInput {
                id: None,
                kind: Some(EntryKind::Movie),
                title: "Solaris".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1972),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                parent_collection_id: None,
                parent_position: None,
            })
            .await
            .unwrap();
        svc.log_consumption(&m2.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // Second movie logged: ranking is now eligible!
        assert_eq!(
            svc.rank_target_after_consumption(&m2).await.unwrap(),
            Some("movies".to_owned())
        );
    }

    #[tokio::test]
    async fn test_want_resolved_or_upsert_reuses_existing_entry_and_stores_notes() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let existing = svc
            .upsert_entry(&UpsertEntryInput {
                id: None,
                kind: Some(EntryKind::Movie),
                title: "Blade Runner".to_owned(),
                creators: vec!["Ridley Scott".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1982),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: vec![EntryIdentifier {
                    scheme: "imdb".to_owned(),
                    value: "tt0083658".to_owned(),
                }],
                parent_collection_id: None,
                parent_position: None,
            })
            .await
            .unwrap();

        let bare_want_input = UpsertEntryInput {
            id: None,
            kind: Some(EntryKind::Movie),
            title: "tt0083658".to_owned(),
            creators: Vec::new(),
            url: None,
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: None,
            identifiers: Vec::new(),
            parent_collection_id: None,
            parent_position: None,
        };

        let (_, queued) = svc
            .want_resolved_or_upsert(&bare_want_input, false, Some("Watch Final Cut in 4K"), None)
            .await
            .unwrap();
        assert_eq!(queued.id, existing.id);
        assert_eq!(queued.title, "Blade Runner");
        assert_eq!(
            queued.metadata.get("want_notes").and_then(|v| v.as_str()),
            Some("Watch Final Cut in 4K")
        );
    }
}
