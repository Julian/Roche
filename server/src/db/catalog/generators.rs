use chrono::{DateTime, Utc};
use rand::RngExt;
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, CollectionGeneratorState, RawCollectionRow,
    },
};
use crate::db::{
    WriteTx,
    sync::SyncRepo,
    types::{
        ActivityAction, ActivityEntityType, CollectionMode, CollectionRole, ConsumptionStatus,
    },
};

#[derive(sqlx::FromRow)]
struct RawGeneratorRow {
    collection_id: String,
    collection_slug: Option<String>,
    collection_title: String,
    enabled: bool,
    current_entry_id: Option<String>,
    drawn_at: Option<DateTime<Utc>>,
    updated_at: DateTime<Utc>,
    total_pool: i64,
    consumed_count: i64,
    abandoned_count: i64,
}

#[derive(sqlx::FromRow)]
struct RawExistingGenRow {
    enabled: bool,
    current_entry_id: Option<String>,
    drawn_at: Option<DateTime<Utc>>,
}

#[derive(sqlx::FromRow)]
struct RawGeneratorStateRow {
    enabled: bool,
    current_entry_id: Option<String>,
    drawn_at: Option<DateTime<Utc>>,
    updated_at: DateTime<Utc>,
}

macro_rules! list_generators_sql {
    ($extra_where:literal, $order_by:literal) => {
        concat!(
            "SELECT cg.collection_id, cs.slug AS collection_slug, e.title AS collection_title,
                    cg.enabled, cg.current_entry_id, cg.drawn_at, cg.updated_at,
                    COALESCE(stats.total_pool, 0) AS total_pool,
                    COALESCE(stats.consumed_count, 0) AS consumed_count,
                    COALESCE(stats.abandoned_count, 0) AS abandoned_count
             FROM collection_generators cg
             JOIN collection_specs cs ON cs.collection_id = cg.collection_id
             JOIN entries e ON e.id = cg.collection_id
             LEFT JOIN (
                 SELECT ce.collection_id,
                        COUNT(*) AS total_pool,
                        COALESCE(SUM(CASE WHEN EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                        ) THEN 1 ELSE 0 END), 0) AS consumed_count,
                        COALESCE(SUM(CASE WHEN NOT EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                        ) AND EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = ce.entry_id AND cl.status = 'abandoned'
                        ) THEN 1 ELSE 0 END), 0) AS abandoned_count
                 FROM collection_entries ce
                 JOIN entries m ON m.id = ce.entry_id
                 WHERE m.merged_into_id IS NULL
                 GROUP BY ce.collection_id
             ) stats ON stats.collection_id = cg.collection_id
             WHERE e.merged_into_id IS NULL
               AND cs.mode = 'manual'
               AND cs.collection_role = 'list'
               AND cg.collection_id != ? ",
            $extra_where,
            " ",
            $order_by
        )
    };
}

const LIST_ENABLED_GENERATORS_SQL: &str =
    list_generators_sql!("AND cg.enabled = 1", "ORDER BY LOWER(e.title) ASC, cg.collection_id ASC");
const LIST_ALL_GENERATORS_SQL: &str =
    list_generators_sql!("", "ORDER BY cg.enabled DESC, LOWER(e.title) ASC, cg.collection_id ASC");

impl CatalogRepo<'_> {
    pub async fn list_collection_generators(
        &self,
        only_enabled: bool,
    ) -> CatalogResult<Vec<CollectionGeneratorState>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let sql = if only_enabled { LIST_ENABLED_GENERATORS_SQL } else { LIST_ALL_GENERATORS_SQL };
        let rows: Vec<RawGeneratorRow> =
            sqlx::query_as(sql).bind(crate::db::STARRED_COLLECTION_ID).fetch_all(tx.conn()).await?;
        let current_ids: Vec<String> =
            rows.iter().filter_map(|r| r.current_entry_id.clone()).collect();
        let entries_by_id = if current_ids.is_empty() {
            std::collections::HashMap::new()
        } else {
            Self::load_entries_by_ids_in_tx(tx.conn(), &current_ids).await?
        };
        let mut out = Vec::with_capacity(rows.len());
        for row in rows {
            let current_entry = match row.current_entry_id.as_deref() {
                Some(cid) => match entries_by_id.get(cid) {
                    Some(found) => Some(found.clone()),
                    None => Self::get_entry_in_tx(tx.conn(), cid).await?,
                },
                None => None,
            };
            out.push(Self::build_generator_state(row, current_entry));
        }
        tx.commit().await?;
        Ok(out)
    }

    #[must_use]
    pub(crate) fn is_generator_eligible(
        collection_id: &str,
        mode: CollectionMode,
        role: CollectionRole,
    ) -> bool {
        collection_id != crate::db::STARRED_COLLECTION_ID
            && mode == CollectionMode::Manual
            && role == CollectionRole::List
    }

    pub(super) fn ensure_generator_eligible(
        collection_id: &str,
        collection_title: &str,
        mode: CollectionMode,
        role: CollectionRole,
    ) -> CatalogResult<()> {
        if !Self::is_generator_eligible(collection_id, mode, role) {
            return Err(CatalogError::InvalidInput(format!(
                "Collection '{collection_title}' cannot use a generator (must be a custom manual list)"
            )));
        }
        Ok(())
    }

    pub async fn get_collection_generator(
        &self,
        slug_or_id: &str,
    ) -> CatalogResult<CollectionGeneratorState> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let col = Self::lookup_generator_eligible_collection_in_tx(tx.conn(), slug_or_id).await?;
        let gen_row: Option<RawGeneratorStateRow> = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at, updated_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(&col.id)
        .fetch_optional(tx.conn())
        .await?;

        let (enabled, current_entry_id, drawn_at, updated_at) = gen_row.map_or_else(
            || (false, None, None, col.updated_at),
            |row| (row.enabled, row.current_entry_id, row.drawn_at, row.updated_at),
        );

        let (total_pool, consumed_count, abandoned_count): (i64, i64, i64) = sqlx::query_as(
            "SELECT
                 COUNT(*),
                 COALESCE(SUM(CASE WHEN EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                 ) THEN 1 ELSE 0 END), 0),
                 COALESCE(SUM(CASE WHEN NOT EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
                 ) AND EXISTS (
                     SELECT 1 FROM consumption_log cl
                     WHERE cl.entry_id = ce.entry_id AND cl.status = 'abandoned'
                 ) THEN 1 ELSE 0 END), 0)
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ? AND e.merged_into_id IS NULL",
        )
        .bind(&col.id)
        .fetch_one(tx.conn())
        .await?;

        let current_entry = match current_entry_id.as_deref() {
            Some(cid) => Self::get_entry_in_tx(tx.conn(), cid).await?,
            None => None,
        };
        tx.commit().await?;

        Ok(Self::build_generator_state(
            RawGeneratorRow {
                collection_id: col.id,
                collection_slug: col.slug,
                collection_title: col.title,
                enabled,
                current_entry_id,
                drawn_at,
                updated_at,
                total_pool,
                consumed_count,
                abandoned_count,
            },
            current_entry,
        ))
    }

    pub async fn set_collection_generator_enabled(
        &self,
        slug_or_id: &str,
        enabled: bool,
    ) -> CatalogResult<CollectionGeneratorState> {
        self.update_collection_generator(slug_or_id, Some(enabled), None, None).await
    }

    pub async fn update_collection_generator(
        &self,
        slug_or_id: &str,
        enabled: Option<bool>,
        explicit_entry_id: Option<&str>,
        explicit_drawn_at: Option<DateTime<Utc>>,
    ) -> CatalogResult<CollectionGeneratorState> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let col = Self::lookup_generator_eligible_collection_in_tx(tx.conn(), slug_or_id).await?;
        Self::update_collection_generator_in_tx(
            &mut tx,
            &col.id,
            &col.title,
            enabled,
            explicit_entry_id,
            explicit_drawn_at,
            now,
        )
        .await?;
        tx.commit().await?;
        self.get_collection_generator(&col.id).await
    }

    pub async fn remove_collection_generator(&self, slug_or_id: &str) -> CatalogResult<bool> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let col = Self::lookup_generator_eligible_collection_in_tx(tx.conn(), slug_or_id).await?;
        let res = sqlx::query("DELETE FROM collection_generators WHERE collection_id = ?")
            .bind(&col.id)
            .execute(tx.conn())
            .await?;
        let removed = res.rows_affected() > 0;
        if removed {
            SyncRepo::record_tombstone(
                &mut tx,
                ActivityEntityType::CollectionGenerator,
                &col.id,
                now,
            )
            .await?;
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::CollectionGenerator,
                &col.id,
                ActivityAction::Delete,
                now,
            )
            .await?;
        }
        tx.commit().await?;
        Ok(removed)
    }

    pub async fn finish_active_generator_entry(
        &self,
        slug_or_id: &str,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, CollectionGeneratorState)> {
        self.advance_active_generator_entry(slug_or_id, ConsumptionStatus::Consumed, notes).await
    }

    pub async fn abandon_active_generator_entry(
        &self,
        slug_or_id: &str,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, CollectionGeneratorState)> {
        self.advance_active_generator_entry(slug_or_id, ConsumptionStatus::Abandoned, notes).await
    }

    async fn advance_active_generator_entry(
        &self,
        slug_or_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, CollectionGeneratorState)> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let col = Self::lookup_generator_eligible_collection_in_tx(tx.conn(), slug_or_id).await?;

        let active_opt: Option<(Option<String>,)> = sqlx::query_as(
            "SELECT current_entry_id FROM collection_generators WHERE collection_id = ?",
        )
        .bind(&col.id)
        .fetch_optional(tx.conn())
        .await?;

        let active_id = active_opt.and_then(|r| r.0).ok_or_else(|| {
            CatalogError::InvalidInput(format!(
                "No active entry is currently drawn for '{}'",
                col.title
            ))
        })?;

        // `log_consumption_in_tx` automatically advances all generators holding `active_id`.
        let (_, canon_finished_id) =
            Self::log_consumption_in_tx(&mut tx, &active_id, status, notes, now, now).await?;

        tx.commit().await?;

        let finished_entry = self
            .get_entry(&canon_finished_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_finished_id}'")))?;
        let next_state = self.get_collection_generator(&col.id).await?;
        Ok((finished_entry, next_state))
    }

    async fn lookup_generator_eligible_collection_in_tx(
        conn: &mut SqliteConnection,
        slug_or_id: &str,
    ) -> CatalogResult<RawCollectionRow> {
        let col = Self::lookup_collection_row_in_tx(conn, slug_or_id, false).await?;
        Self::ensure_generator_eligible(&col.id, &col.title, col.mode, col.collection_role)?;
        Ok(col)
    }

    fn build_generator_state(
        raw: RawGeneratorRow,
        current_entry: Option<CatalogEntry>,
    ) -> CollectionGeneratorState {
        let total = usize::try_from(raw.total_pool).unwrap_or(0);
        let consumed = usize::try_from(raw.consumed_count).unwrap_or(0);
        let abandoned = usize::try_from(raw.abandoned_count).unwrap_or(0);
        let remaining = total.saturating_sub(consumed + abandoned);

        CollectionGeneratorState {
            collection_id: raw.collection_id,
            collection_slug: raw.collection_slug,
            collection_title: raw.collection_title,
            enabled: raw.enabled,
            current_entry,
            drawn_at: raw.drawn_at,
            updated_at: raw.updated_at,
            total_pool: total,
            consumed_count: consumed,
            abandoned_count: abandoned,
            remaining_count: remaining,
        }
    }

    pub(super) async fn update_collection_generator_in_tx(
        tx: &mut WriteTx<'_>,
        collection_id: &str,
        collection_title: &str,
        enabled_override: Option<bool>,
        explicit_entry_id: Option<&str>,
        explicit_drawn_at: Option<DateTime<Utc>>,
        now: DateTime<Utc>,
    ) -> CatalogResult<Option<String>> {
        let cur: Option<RawExistingGenRow> = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(collection_id)
        .fetch_optional(tx.conn())
        .await?;

        let enabled = enabled_override.unwrap_or_else(|| cur.as_ref().is_some_and(|c| c.enabled));
        let mut drawn_at = cur.as_ref().and_then(|c| c.drawn_at);
        let mut current_id = match cur.and_then(|c| c.current_entry_id) {
            Some(cid) => Self::follow_merged_id_in_tx(tx.conn(), &cid).await?,
            None => None,
        };

        if let Some(cid) = &current_id {
            let in_pool: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            )
            .bind(collection_id)
            .bind(cid)
            .fetch_optional(tx.conn())
            .await?;
            if in_pool.is_none() || Self::is_entry_terminal_in_tx(tx.conn(), cid).await? {
                current_id = None;
                drawn_at = None;
            }
        }

        Self::apply_explicit_generator_entry_in_tx(
            tx.conn(),
            collection_id,
            collection_title,
            explicit_entry_id,
            explicit_drawn_at,
            now,
            &mut current_id,
            &mut drawn_at,
        )
        .await?;

        if enabled && current_id.is_none() {
            current_id = Self::draw_random_remaining_in_tx(tx.conn(), collection_id).await?;
            if current_id.is_some() {
                drawn_at = Some(now);
            }
        }

        SyncRepo::clear_tombstone(
            tx.conn(),
            ActivityEntityType::CollectionGenerator,
            collection_id,
        )
        .await?;

        let gen_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT INTO collection_generators
             (collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(collection_id) DO UPDATE SET
                enabled = excluded.enabled,
                current_entry_id = excluded.current_entry_id,
                drawn_at = excluded.drawn_at,
                updated_at = excluded.updated_at,
                hlc = excluded.hlc",
        )
        .bind(collection_id)
        .bind(enabled)
        .bind(current_id.as_deref())
        .bind(drawn_at)
        .bind(now)
        .bind(&gen_hlc)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            collection_id,
            ActivityAction::Update,
            now,
        )
        .await?;

        Ok(current_id)
    }

    #[allow(clippy::too_many_arguments)]
    async fn apply_explicit_generator_entry_in_tx(
        tx: &mut SqliteConnection,
        collection_id: &str,
        collection_title: &str,
        explicit_entry_id: Option<&str>,
        explicit_drawn_at: Option<DateTime<Utc>>,
        now: DateTime<Utc>,
        current_id: &mut Option<String>,
        drawn_at: &mut Option<DateTime<Utc>>,
    ) -> CatalogResult<()> {
        if let Some(raw_explicit) = explicit_entry_id.map(str::trim).filter(|s| !s.is_empty()) {
            let canon_explicit =
                Self::follow_merged_id_in_tx(tx, raw_explicit).await?.ok_or_else(|| {
                    CatalogError::NotFound(format!("Generator entry '{raw_explicit}'"))
                })?;
            let in_pool: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            )
            .bind(collection_id)
            .bind(&canon_explicit)
            .fetch_optional(&mut *tx)
            .await?;
            if in_pool.is_none() {
                return Err(CatalogError::InvalidInput(format!(
                    "Entry '{canon_explicit}' is not a member of '{collection_title}'"
                )));
            }
            if !Self::is_entry_terminal_in_tx(tx, &canon_explicit).await? {
                *current_id = Some(canon_explicit);
                *drawn_at = Some(explicit_drawn_at.unwrap_or(now));
            }
        } else if let Some(ts) = explicit_drawn_at
            && current_id.is_some()
        {
            *drawn_at = Some(ts);
        }
        Ok(())
    }

    pub(crate) async fn is_entry_terminal_in_tx(
        tx: &mut SqliteConnection,
        entry_id: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        let done: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM consumption_log
             WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
             LIMIT 1",
        )
        .bind(entry_id)
        .fetch_optional(&mut *tx)
        .await?;
        Ok(done.is_some())
    }

    pub(crate) async fn advance_generators_for_entry_in_tx(
        tx: &mut WriteTx<'_>,
        canon_entry_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let affected: Vec<(String, bool)> = sqlx::query_as(
            "SELECT collection_id, enabled
             FROM collection_generators
             WHERE current_entry_id = ?",
        )
        .bind(canon_entry_id)
        .fetch_all(tx.conn())
        .await?;

        for (col_id, enabled) in affected {
            let next_id = if enabled {
                Self::draw_random_remaining_in_tx(tx.conn(), &col_id).await?
            } else {
                None
            };
            let next_drawn = next_id.as_ref().map(|_| now);
            let gen_hlc = tx.next_hlc().await?;
            sqlx::query(
                "UPDATE collection_generators
                 SET current_entry_id = ?, drawn_at = ?, updated_at = ?, hlc = ?
                 WHERE collection_id = ?",
            )
            .bind(next_id.as_deref())
            .bind(next_drawn)
            .bind(now)
            .bind(&gen_hlc)
            .bind(&col_id)
            .execute(tx.conn())
            .await?;

            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                &col_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        Ok(())
    }

    /// Clears or redraws a collection's generator if `current_entry_id` points to `removed_entry_id`.
    pub(crate) async fn advance_generator_if_drawing_in_tx(
        tx: &mut WriteTx<'_>,
        collection_id: &str,
        removed_entry_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let enabled_opt: Option<bool> = sqlx::query_scalar(
            "SELECT enabled
             FROM collection_generators
             WHERE collection_id = ? AND current_entry_id = ?",
        )
        .bind(collection_id)
        .bind(removed_entry_id)
        .fetch_optional(tx.conn())
        .await?;

        let Some(enabled) = enabled_opt else {
            return Ok(());
        };

        let next_id = if enabled {
            Self::draw_random_remaining_in_tx(tx.conn(), collection_id).await?
        } else {
            None
        };
        let next_drawn = next_id.as_ref().map(|_| now);
        let gen_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE collection_generators
             SET current_entry_id = ?, drawn_at = ?, updated_at = ?, hlc = ?
             WHERE collection_id = ?",
        )
        .bind(next_id.as_deref())
        .bind(next_drawn)
        .bind(now)
        .bind(&gen_hlc)
        .bind(collection_id)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::CollectionGenerator,
            collection_id,
            ActivityAction::Update,
            now,
        )
        .await?;
        Ok(())
    }

    pub(crate) async fn draw_random_remaining_in_tx(
        tx: &mut SqliteConnection,
        collection_id: &str,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let remaining: Vec<String> = sqlx::query_scalar(
            "SELECT ce.entry_id
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ?
               AND e.merged_into_id IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = ce.entry_id AND cl.status IN ('consumed', 'abandoned')
               )
             ORDER BY ce.position ASC, ce.entry_id ASC",
        )
        .bind(collection_id)
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
