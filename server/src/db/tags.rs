use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};

use super::{
    ActivityAction, ActivityEntityType, HexColor, SyncRepo,
    sync::{deterministic_tag_id, tag_natural_key},
};

macro_rules! tag_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT e.id, e.title AS name, cs.color, e.created_at,
       (SELECT COUNT(*)
        FROM collection_entries ce
        JOIN entry_state es ON es.entry_id = ce.entry_id
        JOIN entries ie ON ie.id = ce.entry_id
        WHERE ce.collection_id = e.id
          AND ie.merged_into_id IS NULL
          AND es.inbox_state = 'unread'
          AND es.read_at IS NULL) AS unread_count
FROM entries e
JOIN collection_specs cs ON cs.collection_id = e.id
WHERE cs.collection_role = 'tag' AND e.merged_into_id IS NULL ",
            $suffix
        )
    };
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub color: Option<HexColor>,
    pub created_at: DateTime<Utc>,
    pub unread_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ItemTag {
    pub item_id: String,
    pub tag_id: String,
}

pub struct TagRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> TagRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn create(&self, name: &str, color: Option<&HexColor>) -> Result<Tag> {
        let id = deterministic_tag_id(name);
        let nat_key = tag_natural_key(name);
        let slug = format!("tag:{nat_key}");
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(&mut tx, ActivityEntityType::Tag, &nat_key).await?;

        let existing: Option<String> = sqlx::query_scalar(
            "SELECT e.id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND (e.id = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
        )
        .bind(&id)
        .bind(name)
        .fetch_optional(&mut *tx)
        .await?;
        if existing.is_some() {
            anyhow::bail!("UNIQUE constraint failed: tags.name");
        }

        sqlx::query(
            "INSERT INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?)",
        )
        .bind(&id)
        .bind(name)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO collection_specs (collection_id, slug, mode, collection_role, show_in_lists, color)
             VALUES (?, ?, 'manual', 'tag', 0, ?)",
        )
        .bind(&id)
        .bind(&slug)
        .bind(color)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT OR REPLACE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('tag', ?, ?)",
        )
        .bind(&nat_key)
        .bind(&id)
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Tag,
            &id,
            ActivityAction::Create,
            now,
        )
        .await?;

        tx.commit().await?;

        Ok(Tag {
            id,
            name: name.to_string(),
            color: color.cloned(),
            created_at: now,
            unread_count: 0,
        })
    }

    pub async fn get(&self, id: &str) -> Result<Option<Tag>> {
        Ok(sqlx::query_as::<_, Tag>(tag_select_sql!("AND e.id = ?"))
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list(&self) -> Result<Vec<Tag>> {
        Ok(sqlx::query_as::<_, Tag>(tag_select_sql!("ORDER BY e.title"))
            .fetch_all(self.pool)
            .await?)
    }

    pub async fn update(
        &self,
        id: &str,
        name: Option<&str>,
        color: Option<&HexColor>,
    ) -> Result<bool> {
        if name.is_none() && color.is_none() {
            return Ok(self.get(id).await?.is_some());
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let exists: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_specs WHERE collection_id = ? AND collection_role = 'tag'",
        )
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        if exists.is_none() {
            return Ok(false);
        }

        if let Some(n) = name {
            let nat_key = tag_natural_key(n);
            let slug = format!("tag:{nat_key}");
            sqlx::query("UPDATE entries SET title = ?, updated_at = ? WHERE id = ?")
                .bind(n)
                .bind(now)
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE collection_specs SET slug = ? WHERE collection_id = ?")
                .bind(&slug)
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("DELETE FROM entry_identifiers WHERE scheme = 'tag' AND entry_id = ?")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "INSERT OR REPLACE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('tag', ?, ?)",
            )
            .bind(&nat_key)
            .bind(id)
            .execute(&mut *tx)
            .await?;
            sqlx::query(
                "UPDATE entries SET updated_at = ?
                 WHERE id IN (SELECT entry_id FROM collection_entries WHERE collection_id = ?)",
            )
            .bind(now)
            .bind(id)
            .execute(&mut *tx)
            .await?;
        }

        if let Some(c) = color {
            sqlx::query("UPDATE collection_specs SET color = ? WHERE collection_id = ?")
                .bind(c)
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(id)
                .execute(&mut *tx)
                .await?;
        }

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Tag,
            id,
            ActivityAction::Update,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let exists: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_specs WHERE collection_id = ? AND collection_role = 'tag'",
        )
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        if exists.is_none() {
            return Ok(false);
        }

        sqlx::query(
            "UPDATE entries SET updated_at = ?
             WHERE id IN (SELECT entry_id FROM collection_entries WHERE collection_id = ?)",
        )
        .bind(now)
        .bind(id)
        .execute(&mut *tx)
        .await?;

        let deleted_name: Option<String> =
            sqlx::query_scalar("DELETE FROM entries WHERE id = ? RETURNING title")
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?;
        let Some(tag_name) = deleted_name else {
            return Ok(false);
        };

        SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Tag,
            &tag_natural_key(&tag_name),
            now,
        )
        .await?;
        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Tag,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn get_or_create_and_attach_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        name: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<Tag, sqlx::Error> {
        let new_tag_id = deterministic_tag_id(name);
        let nat_key = tag_natural_key(name);
        SyncRepo::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &nat_key).await?;

        let existing_id: Option<String> = sqlx::query_scalar(
            "SELECT e.id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND (e.id = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
        )
        .bind(&new_tag_id)
        .bind(name)
        .fetch_optional(&mut *conn)
        .await?;

        let tag_id = if let Some(id) = existing_id {
            id
        } else {
            let slug = format!("tag:{nat_key}");
            let tag_insert = sqlx::query(
                "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
                 VALUES (?, 'collection', ?, ?, ?)",
            )
            .bind(&new_tag_id)
            .bind(name)
            .bind(now)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            if tag_insert.rows_affected() > 0 {
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_specs (collection_id, slug, mode, collection_role, show_in_lists, color)
                     VALUES (?, ?, 'manual', 'tag', 0, NULL)",
                )
                .bind(&new_tag_id)
                .bind(&slug)
                .execute(&mut *conn)
                .await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('tag', ?, ?)",
                )
                .bind(&nat_key)
                .bind(&new_tag_id)
                .execute(&mut *conn)
                .await?;
                SyncRepo::record_activity(
                    &mut *conn,
                    ActivityEntityType::Tag,
                    &new_tag_id,
                    ActivityAction::Create,
                    now,
                )
                .await?;
            }
            sqlx::query_scalar(
                "SELECT e.id
                 FROM entries e
                 JOIN collection_specs cs ON cs.collection_id = e.id
                 WHERE cs.collection_role = 'tag' AND (e.id = ? OR LOWER(e.title) = LOWER(?))
                 LIMIT 1",
            )
            .bind(&new_tag_id)
            .bind(name)
            .fetch_one(&mut *conn)
            .await?
        };

        Self::attach_in_tx(&mut *conn, item_id, &tag_id, now).await?;

        let tag: Tag = sqlx::query_as(tag_select_sql!("AND e.id = ?"))
            .bind(&tag_id)
            .fetch_one(&mut *conn)
            .await?;
        Ok(tag)
    }

    pub async fn attach_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        tag_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let res = sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
             VALUES (?, ?, ?)",
        )
        .bind(tag_id)
        .bind(item_id)
        .bind(now)
        .execute(&mut *conn)
        .await?;
        if res.rows_affected() > 0 {
            sqlx::query(
                "UPDATE entry_state SET interaction_updated_at = ?, updated_at = ? WHERE entry_id = ?",
            )
            .bind(now)
            .bind(now)
            .bind(item_id)
            .execute(&mut *conn)
            .await?;
            sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            SyncRepo::record_activity(
                &mut *conn,
                ActivityEntityType::Item,
                item_id,
                ActivityAction::TagAdd,
                now,
            )
            .await?;
        }
        Ok(())
    }

    pub async fn add_to_item(&self, item_id: &str, tag_id: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        Self::attach_in_tx(&mut tx, item_id, tag_id, now).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn add_named_to_item(&self, item_id: &str, name: &str) -> Result<Tag> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let tag = Self::get_or_create_and_attach_in_tx(&mut tx, item_id, name, now).await?;
        tx.commit().await?;
        Ok(tag)
    }

    pub async fn remove_from_item(&self, item_id: &str, tag_id: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res =
            sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
                .bind(tag_id)
                .bind(item_id)
                .execute(&mut *tx)
                .await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        sqlx::query(
            "UPDATE entry_state SET interaction_updated_at = ?, updated_at = ? WHERE entry_id = ?",
        )
        .bind(now)
        .bind(now)
        .bind(item_id)
        .execute(&mut *tx)
        .await?;
        sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
            .bind(now)
            .bind(item_id)
            .execute(&mut *tx)
            .await?;
        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Item,
            item_id,
            ActivityAction::TagRemove,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn list_for_item(&self, item_id: &str) -> Result<Vec<Tag>> {
        Ok(sqlx::query_as::<_, Tag>(tag_select_sql!(
            "AND EXISTS (
                SELECT 1 FROM collection_entries ce_item
                WHERE ce_item.collection_id = e.id AND ce_item.entry_id = ?
            )
            ORDER BY e.title"
        ))
        .bind(item_id)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn list_all_item_tags(&self) -> Result<Vec<ItemTag>> {
        Ok(sqlx::query_as::<_, ItemTag>(
            "SELECT ce.entry_id AS item_id, ce.collection_id AS tag_id
             FROM collection_entries ce
             JOIN collection_specs cs ON cs.collection_id = ce.collection_id
             WHERE cs.collection_role = 'tag'
             ORDER BY ce.entry_id, ce.collection_id",
        )
        .fetch_all(self.pool)
        .await?)
    }
}
