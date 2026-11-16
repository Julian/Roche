use chrono::{DateTime, Utc};
use rand::RngExt;
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{CatalogEntry, CatalogError, CatalogResult, Generator1001State},
};
use crate::{
    albums_pool::bundled_1001_albums,
    db::{
        sync::{ALBUMS_1001_COLLECTION_ID, SyncRepo},
        types::{ActivityAction, ActivityEntityType, ConsumptionStatus},
    },
};

impl CatalogRepo<'_> {
    /// Idempotently seeds the bundled 1,101 albums into `entries`, `entry_identifiers`, and
    /// `collection_entries(ALBUMS_1001_COLLECTION_ID)`. Fast no-op once already seeded.
    pub async fn ensure_1001_albums_seeded(&self) -> CatalogResult<usize> {
        let pool_albums = bundled_1001_albums();
        let current_count = sqlx::query_scalar!(
            r#"SELECT COUNT(*) as "count!: i64"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ? AND e.merged_into_id IS NULL"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(self.pool)
        .await?;
        if usize::try_from(current_count).unwrap_or(0) >= pool_albums.len() {
            return Ok(0);
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let mut seeded = 0usize;

        for (idx, album) in pool_albums.iter().enumerate() {
            let creators_json =
                serde_json::to_string(&[album.artist.as_str()]).unwrap_or_else(|_| "[]".to_owned());
            let metadata_json = serde_json::json!({
                "genres": album.genres,
                "styles": album.styles,
            })
            .to_string();
            let spotify_uri = format!("spotify:album:{}", album.spotify_id);

            // Follow full merge chains if an album with this Spotify URI or pool ID was already merged
            let raw_owner = sqlx::query_scalar!(
                r#"SELECT entry_id as "entry_id!" FROM entry_identifiers
                   WHERE scheme = 'spotify' AND value = ?
                   LIMIT 1"#,
                &spotify_uri,
            )
            .fetch_optional(&mut *tx)
            .await?;

            let mut existing_canon = if let Some(owner_id) = raw_owner {
                Self::follow_merged_id_in_tx(&mut tx, &owner_id).await?
            } else {
                None
            };
            if existing_canon.is_none() {
                existing_canon = Self::follow_merged_id_in_tx(&mut tx, &album.id).await?;
            }

            let target_id = if let Some(eid) = existing_canon {
                eid
            } else {
                let res = sqlx::query!(
                    "INSERT OR IGNORE INTO entries
                     (id, kind, title, creators_json, url, release_year, thumbnail_url,
                      metadata_json, created_at, updated_at)
                     VALUES (?, 'album', ?, ?, NULL, ?, ?, ?, ?, ?)",
                    &album.id,
                    &album.title,
                    &creators_json,
                    album.year as _,
                    &album.cover_url as _,
                    &metadata_json,
                    now as _,
                    now as _,
                )
                .execute(&mut *tx)
                .await?;
                if res.rows_affected() > 0 {
                    seeded += 1;
                }
                album.id.clone()
            };

            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('spotify', ?, ?), ('1001albums', ?, ?)",
                &spotify_uri,
                &target_id,
                &album.slug,
                &target_id,
            )
            .execute(&mut *tx)
            .await?;

            #[allow(clippy::cast_precision_loss)]
            let pos = (idx + 1) as f64;
            sqlx::query!(
                "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)",
                ALBUMS_1001_COLLECTION_ID,
                &target_id,
                pos,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(seeded)
    }

    pub async fn get_1001_generator_state(&self) -> CatalogResult<Generator1001State> {
        self.ensure_1001_albums_seeded().await?;
        let gen_row = sqlx::query!(
            r#"SELECT enabled as "enabled!: bool",
                      current_entry_id as "current_entry_id?",
                      drawn_at as "drawn_at?: DateTime<Utc>",
                      updated_at as "updated_at!: DateTime<Utc>"
               FROM collection_generators
               WHERE collection_id = ?"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(self.pool)
        .await?;
        let enabled = gen_row.enabled;
        let raw_current_id = gen_row.current_entry_id;
        let drawn_at = gen_row.drawn_at;
        let updated_at = gen_row.updated_at;

        let current_entry = match raw_current_id {
            Some(cid) => self.get_entry(&cid).await?,
            None => None,
        };

        let total_pool = sqlx::query_scalar!(
            r#"SELECT COUNT(*) as "count!: i64"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ? AND e.merged_into_id IS NULL"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(self.pool)
        .await?;

        let consumed_count = sqlx::query_scalar!(
            r#"SELECT COUNT(DISTINCT ce.entry_id) as "count!: i64"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ?
                 AND e.merged_into_id IS NULL
                 AND EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                 )"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(self.pool)
        .await?;

        let abandoned_count = sqlx::query_scalar!(
            r#"SELECT COUNT(DISTINCT ce.entry_id) as "count!: i64"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ?
                 AND e.merged_into_id IS NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                 )
                 AND EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'abandoned'
                 )"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(self.pool)
        .await?;

        let total = usize::try_from(total_pool).unwrap_or(0);
        let consumed = usize::try_from(consumed_count).unwrap_or(0);
        let abandoned = usize::try_from(abandoned_count).unwrap_or(0);
        let remaining = total.saturating_sub(consumed + abandoned);

        Ok(Generator1001State {
            collection_id: ALBUMS_1001_COLLECTION_ID.to_owned(),
            enabled,
            current_entry,
            drawn_at,
            updated_at,
            total_pool: total,
            consumed_count: consumed,
            abandoned_count: abandoned,
            remaining_count: remaining,
        })
    }

    /// Enables or disables the `1001 Albums` generator.
    /// Never auto-rotates: if `current_entry_id` is already drawn (and still unconsumed/unabandoned),
    /// enabling preserves that exact active album; only draws when `current_entry_id IS NULL`.
    pub async fn set_1001_generator_enabled(
        &self,
        enabled: bool,
    ) -> CatalogResult<Generator1001State> {
        self.ensure_1001_albums_seeded().await?;
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let cur = sqlx::query!(
            r#"SELECT current_entry_id as "current_entry_id?",
                      drawn_at as "drawn_at?: DateTime<Utc>"
               FROM collection_generators
               WHERE collection_id = ?"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(&mut *tx)
        .await?;
        let mut drawn_at = cur.drawn_at;
        let mut current_id = match cur.current_entry_id {
            Some(cid) => Self::follow_merged_id_in_tx(&mut tx, &cid).await?,
            None => None,
        };

        // If `current_id` was already finished or abandoned externally (e.g. via CLI `roche consumed`),
        // clear it so enabling draws a fresh album; otherwise strictly preserve `current_id`.
        if let Some(cid) = &current_id {
            let done = sqlx::query_scalar!(
                r#"SELECT 1 as "found!: i64" FROM consumption_log
                   WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                   LIMIT 1"#,
                cid,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if done.is_some() {
                current_id = None;
            }
        }

        if enabled && current_id.is_none() {
            current_id = Self::draw_random_remaining_1001_in_tx(&mut tx).await?;
            if current_id.is_some() {
                drawn_at = Some(now);
            }
        }

        let current_id_ref = current_id.as_deref();
        sqlx::query!(
            "UPDATE collection_generators
             SET enabled = ?, current_entry_id = ?, drawn_at = ?, updated_at = ?
             WHERE collection_id = ?",
            enabled as _,
            current_id_ref,
            drawn_at as _,
            now as _,
            ALBUMS_1001_COLLECTION_ID,
        )
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Entry,
            ALBUMS_1001_COLLECTION_ID,
            ActivityAction::Update,
            now,
        )
        .await?;

        tx.commit().await?;
        self.get_1001_generator_state().await
    }

    pub async fn finish_active_1001_album(
        &self,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.advance_active_1001_album(ConsumptionStatus::Consumed, notes).await
    }

    pub async fn abandon_active_1001_album(
        &self,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.advance_active_1001_album(ConsumptionStatus::Abandoned, notes).await
    }

    async fn advance_active_1001_album(
        &self,
        status: ConsumptionStatus,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.ensure_1001_albums_seeded().await?;
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let gen_row = sqlx::query!(
            r#"SELECT enabled as "enabled!: bool",
                      current_entry_id as "current_entry_id?"
               FROM collection_generators
               WHERE collection_id = ?"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_one(&mut *tx)
        .await?;
        let enabled = gen_row.enabled;
        let active_id = gen_row.current_entry_id.ok_or_else(|| {
            CatalogError::InvalidInput("No active 1001 Album is currently drawn".to_owned())
        })?;

        let (_, canon_finished_id) =
            Self::log_consumption_in_tx(&mut tx, &active_id, status, notes, now, now).await?;

        let next_id =
            if enabled { Self::draw_random_remaining_1001_in_tx(&mut tx).await? } else { None };
        let next_drawn_at = next_id.as_ref().map(|_| now);
        let next_id_ref = next_id.as_deref();

        sqlx::query!(
            "UPDATE collection_generators
             SET current_entry_id = ?, drawn_at = ?, updated_at = ?
             WHERE collection_id = ?",
            next_id_ref,
            next_drawn_at as _,
            now as _,
            ALBUMS_1001_COLLECTION_ID,
        )
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Entry,
            ALBUMS_1001_COLLECTION_ID,
            ActivityAction::Update,
            now,
        )
        .await?;

        tx.commit().await?;

        let finished_entry = self
            .get_entry(&canon_finished_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_finished_id}'")))?;
        let next_state = self.get_1001_generator_state().await?;
        Ok((finished_entry, next_state))
    }

    pub(crate) async fn draw_random_remaining_1001_in_tx(
        tx: &mut SqliteConnection,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let remaining = sqlx::query_scalar!(
            r#"SELECT ce.entry_id as "entry_id!"
               FROM collection_entries ce
               JOIN entries e ON e.id = ce.entry_id
               WHERE ce.collection_id = ?
                 AND e.merged_into_id IS NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status IN ('consumed', 'abandoned')
                 )
               ORDER BY ce.position ASC, ce.entry_id ASC"#,
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_all(&mut *tx)
        .await?;

        if remaining.is_empty() {
            return Ok(None);
        }
        let mut rng = rand::rng();
        let idx = rng.random_range(0..remaining.len());
        Ok(Some(remaining[idx].clone()))
    }
}
