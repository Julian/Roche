use anyhow::Result;
use sqlx::{Sqlite, SqliteConnection, SqlitePool, Transaction};

use super::{
    ActivityEntry, CatalogExportSnapshot, CatalogRepo, CatalogResult, Feed, FeedRepo, HlcClock,
    HlcTimestamp, Item, ItemRepo, ItemStatus, ItemTag, SERVER_PEER_ID, SyncRepo, Tag, TagRepo,
};

/// Serialized SQLite write transaction (`BEGIN IMMEDIATE`) that owns a lazy causal [`HlcClock`]
/// synchronized with `sync_clock.last_hlc` once per commit.
///
/// All local writes and peer-sync merges run through `WriteTx` so every HLC-tracked row uses one
/// causally monotonic clock across threads, connections, and restarts.
pub struct WriteTx<'a> {
    tx: Transaction<'a, Sqlite>,
    clock: Option<HlcClock>,
    clock_dirty: bool,
}

impl<'a> WriteTx<'a> {
    /// Begins a `BEGIN IMMEDIATE` write transaction on `pool`.
    pub(crate) async fn begin(pool: &'a SqlitePool) -> std::result::Result<Self, sqlx::Error> {
        let tx = pool.begin_with("BEGIN IMMEDIATE").await?;
        Ok(Self { tx, clock: None, clock_dirty: false })
    }

    /// Exposes the underlying SQLite connection for direct queries inside this transaction.
    #[inline]
    pub(crate) fn conn(&mut self) -> &mut SqliteConnection {
        &mut self.tx
    }

    async fn ensure_clock(&mut self) -> std::result::Result<&mut HlcClock, sqlx::Error> {
        if let Some(ref mut clock) = self.clock {
            return Ok(clock);
        }
        let stored_hlc: String = sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
            .fetch_one(&mut *self.tx)
            .await?;
        let initial = (!stored_hlc.is_empty()).then_some(stored_hlc.as_str());
        Ok(self.clock.insert(HlcClock::new(SERVER_PEER_ID, initial)))
    }

    /// Advances the server HLC clock for a local write at the current wall-clock time.
    pub(crate) async fn next_hlc(&mut self) -> std::result::Result<String, sqlx::Error> {
        let now_ms = crate::util::clock::now_utc().timestamp_millis();
        self.next_hlc_at(now_ms).await
    }

    /// Advances the server HLC clock for a local write at explicit `now_ms`.
    pub(crate) async fn next_hlc_at(
        &mut self,
        now_ms: i64,
    ) -> std::result::Result<String, sqlx::Error> {
        let clock = self.ensure_clock().await?;
        let encoded = clock.tick(now_ms).encode();
        self.clock_dirty = true;
        Ok(encoded)
    }

    /// Witnesses an incoming peer HLC string into this transaction's clock, ensuring subsequent
    /// ticks in this transaction (and after commit) causally dominate `raw`.
    pub(crate) async fn witness_hlc(
        &mut self,
        raw: &str,
        now_ms: i64,
    ) -> std::result::Result<(), sqlx::Error> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Ok(());
        }
        let clock = self.ensure_clock().await?;
        let _ = clock.witness_str(trimmed, now_ms);
        self.clock_dirty = true;
        Ok(())
    }

    /// Persists `sync_clock.last_hlc` (if advanced during this transaction) and commits atomically.
    pub(crate) async fn commit(mut self) -> std::result::Result<(), sqlx::Error> {
        if self.clock_dirty
            && let Some(last_hlc) =
                self.clock.as_ref().and_then(HlcClock::current).map(HlcTimestamp::encode)
        {
            sqlx::query("UPDATE sync_clock SET last_hlc = ? WHERE id = 1")
                .bind(&last_hlc)
                .execute(&mut *self.tx)
                .await?;
        }
        self.tx.commit().await
    }

    /// Rolls back the write transaction without persisting any HLC clock advance.
    pub(crate) async fn rollback(self) -> std::result::Result<(), sqlx::Error> {
        self.tx.rollback().await
    }
}

/// Consistent WAL read-snapshot transaction (`BEGIN DEFERRED`) for multi-SELECT reads such as
/// full data exports, entry detail hydration, and catalog list views.
pub struct ReadTx<'a> {
    tx: Transaction<'a, Sqlite>,
}

impl<'a> ReadTx<'a> {
    /// Begins a `BEGIN DEFERRED` read transaction on `pool`.
    pub(crate) async fn begin(pool: &'a SqlitePool) -> std::result::Result<Self, sqlx::Error> {
        let tx = pool.begin().await?;
        Ok(Self { tx })
    }

    /// Exposes the underlying SQLite connection for read queries inside this snapshot.
    #[inline]
    pub(crate) fn conn(&mut self) -> &mut SqliteConnection {
        &mut self.tx
    }

    /// Commits/closes the read snapshot cleanly.
    pub(crate) async fn commit(self) -> std::result::Result<(), sqlx::Error> {
        self.tx.commit().await
    }

    pub(crate) async fn list_feeds(&mut self) -> Result<Vec<Feed>> {
        FeedRepo::list_in_tx(self.conn()).await
    }

    pub(crate) async fn list_items(
        &mut self,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        ItemRepo::list_in_tx(self.conn(), status, feed_id, limit).await
    }

    pub(crate) async fn list_tags(&mut self) -> Result<Vec<Tag>> {
        TagRepo::list_in_tx(self.conn()).await
    }

    pub(crate) async fn list_item_tags_for_items(
        &mut self,
        item_ids: &[String],
    ) -> Result<Vec<ItemTag>> {
        TagRepo::list_item_tags_for_items_in_tx(self.conn(), item_ids).await
    }

    pub(crate) async fn list_activity(&mut self) -> Result<Vec<ActivityEntry>> {
        SyncRepo::list_activity_in_tx(self.conn()).await
    }

    pub(crate) async fn export_catalog_snapshot(&mut self) -> CatalogResult<CatalogExportSnapshot> {
        CatalogRepo::export_catalog_snapshot_in_tx(self.conn()).await
    }
}
