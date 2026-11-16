use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};

use super::{
    ActivityAction, ActivityEntityType, HexColor, SyncRepo,
    sync::{deterministic_tag_id, tag_natural_key},
};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub color: Option<HexColor>,
    pub created_at: DateTime<Utc>,
    pub unread_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
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
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(&mut tx, ActivityEntityType::Tag, &nat_key).await?;

        let existing = sqlx::query_scalar!(
            "SELECT e.id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND (e.id = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
            id,
            name,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if existing.is_some() {
            return Err(super::ConflictError(format!("Tag '{name}' already exists")).into());
        }

        let id = Self::get_or_create_in_tx(&mut tx, name, color, now, now).await?;

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
        Ok(sqlx::query_as!(
            Tag,
            "SELECT e.id,
                    e.title AS name,
                    cs.color AS \"color: HexColor\",
                    e.created_at AS \"created_at: DateTime<Utc>\",
                    (SELECT COUNT(*)
                     FROM collection_entries ce
                     JOIN entry_state es ON es.entry_id = ce.entry_id
                     JOIN entries ie ON ie.id = ce.entry_id
                     WHERE ce.collection_id = e.id
                       AND ie.merged_into_id IS NULL
                       AND es.inbox_state = 'unread') AS \"unread_count!: i64\"
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND e.merged_into_id IS NULL AND e.id = ?",
            id,
        )
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn list(&self) -> Result<Vec<Tag>> {
        Ok(sqlx::query_as!(
            Tag,
            "SELECT e.id,
                    e.title AS name,
                    cs.color AS \"color: HexColor\",
                    e.created_at AS \"created_at: DateTime<Utc>\",
                    (SELECT COUNT(*)
                     FROM collection_entries ce
                     JOIN entry_state es ON es.entry_id = ce.entry_id
                     JOIN entries ie ON ie.id = ce.entry_id
                     WHERE ce.collection_id = e.id
                       AND ie.merged_into_id IS NULL
                       AND es.inbox_state = 'unread') AS \"unread_count!: i64\"
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND e.merged_into_id IS NULL
             ORDER BY e.title",
        )
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

        let exists = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM collection_specs WHERE collection_id = ? AND collection_role = 'tag'",
            id,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if exists.is_none() {
            return Ok(false);
        }

        if let Some(n) = name {
            let nat_key = tag_natural_key(n);
            let slug = format!("tag:{nat_key}");
            let duplicate = sqlx::query_scalar!(
                "SELECT e.id
                 FROM entries e
                 JOIN collection_specs cs ON cs.collection_id = e.id
                 WHERE cs.collection_role = 'tag'
                   AND e.id != ?
                   AND (cs.slug = ? OR LOWER(e.title) = LOWER(?))
                 LIMIT 1",
                id,
                slug,
                n,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if duplicate.is_some() {
                return Err(super::ConflictError(format!("Tag '{n}' already exists")).into());
            }
            sqlx::query!(
                "UPDATE entries SET title = ?, updated_at = ? WHERE id = ?",
                n,
                now as _,
                id
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!("UPDATE collection_specs SET slug = ? WHERE collection_id = ?", slug, id)
                .execute(&mut *tx)
                .await?;
            sqlx::query!("DELETE FROM entry_identifiers WHERE scheme = 'tag' AND entry_id = ?", id)
                .execute(&mut *tx)
                .await?;
            sqlx::query!(
                "INSERT OR REPLACE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('tag', ?, ?)",
                nat_key,
                id,
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!(
                "UPDATE entries SET updated_at = ?
                 WHERE id IN (SELECT entry_id FROM collection_entries WHERE collection_id = ?)",
                now as _,
                id,
            )
            .execute(&mut *tx)
            .await?;
        }

        if let Some(c) = color {
            sqlx::query!(
                "UPDATE collection_specs SET color = ? WHERE collection_id = ?",
                c as _,
                id,
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, id)
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
        let exists = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM collection_specs WHERE collection_id = ? AND collection_role = 'tag'",
            id,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if exists.is_none() {
            return Ok(false);
        }

        sqlx::query!(
            "UPDATE entries SET updated_at = ?
             WHERE id IN (SELECT entry_id FROM collection_entries WHERE collection_id = ?)",
            now as _,
            id,
        )
        .execute(&mut *tx)
        .await?;

        let deleted_name = sqlx::query_scalar!(
            "DELETE FROM entries WHERE id = ? RETURNING title AS \"title!: String\"",
            id,
        )
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

    pub async fn get_or_create_in_tx(
        conn: &mut SqliteConnection,
        name: &str,
        color: Option<&HexColor>,
        created_at: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> std::result::Result<String, sqlx::Error> {
        let new_tag_id = deterministic_tag_id(name);
        let nat_key = tag_natural_key(name);
        let slug = format!("tag:{nat_key}");
        SyncRepo::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &nat_key).await?;

        let existing_id = sqlx::query_scalar!(
            "SELECT e.id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag'
               AND (e.id = ? OR cs.slug = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
            new_tag_id,
            slug,
            name,
        )
        .fetch_optional(&mut *conn)
        .await?;

        if let Some(id) = existing_id {
            return Ok(id);
        }

        let tag_insert = sqlx::query!(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?)",
            new_tag_id,
            name,
            created_at as _,
            now as _,
        )
        .execute(&mut *conn)
        .await?;

        if tag_insert.rows_affected() > 0 {
            sqlx::query!(
                "INSERT OR IGNORE INTO collection_specs (collection_id, slug, mode, collection_role, show_in_lists, color)
                 VALUES (?, ?, 'manual', 'tag', 0, ?)",
                new_tag_id,
                slug,
                color as _,
            )
            .execute(&mut *conn)
            .await?;
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('tag', ?, ?)",
                nat_key,
                new_tag_id,
            )
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
        sqlx::query_scalar!(
            "SELECT e.id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag'
               AND (e.id = ? OR cs.slug = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
            new_tag_id,
            slug,
            name,
        )
        .fetch_one(&mut *conn)
        .await
    }

    pub async fn get_or_create_and_attach_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        name: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<Tag, sqlx::Error> {
        let tag_id = Self::get_or_create_in_tx(&mut *conn, name, None, now, now).await?;
        Self::attach_in_tx(&mut *conn, item_id, &tag_id, now).await?;

        let tag = sqlx::query_as!(
            Tag,
            "SELECT e.id,
                    e.title AS name,
                    cs.color AS \"color: HexColor\",
                    e.created_at AS \"created_at: DateTime<Utc>\",
                    (SELECT COUNT(*)
                     FROM collection_entries ce
                     JOIN entry_state es ON es.entry_id = ce.entry_id
                     JOIN entries ie ON ie.id = ce.entry_id
                     WHERE ce.collection_id = e.id
                       AND ie.merged_into_id IS NULL
                       AND es.inbox_state = 'unread') AS \"unread_count!: i64\"
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag' AND e.merged_into_id IS NULL AND e.id = ?",
            tag_id,
        )
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
        let res = sqlx::query!(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
             SELECT cs.collection_id, ?, ?
             FROM collection_specs cs
             WHERE cs.collection_id = ? AND cs.collection_role = 'tag'",
            item_id,
            now as _,
            tag_id,
        )
        .execute(&mut *conn)
        .await?;
        if res.rows_affected() > 0 {
            sqlx::query!(
                "UPDATE entry_state SET interaction_updated_at = ?, updated_at = ? WHERE entry_id = ?",
                now as _,
                now as _,
                item_id,
            )
            .execute(&mut *conn)
            .await?;
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, item_id)
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
        let res = sqlx::query!(
            "DELETE FROM collection_entries
             WHERE collection_id = ?
               AND entry_id = ?
               AND EXISTS (
                   SELECT 1 FROM collection_specs cs
                   WHERE cs.collection_id = collection_entries.collection_id
                     AND cs.collection_role = 'tag'
               )",
            tag_id,
            item_id,
        )
        .execute(&mut *tx)
        .await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        sqlx::query!(
            "UPDATE entry_state SET interaction_updated_at = ?, updated_at = ? WHERE entry_id = ?",
            now as _,
            now as _,
            item_id,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, item_id)
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
        Ok(sqlx::query_as!(
            Tag,
            "SELECT e.id,
                    e.title AS name,
                    cs.color AS \"color: HexColor\",
                    e.created_at AS \"created_at: DateTime<Utc>\",
                    (SELECT COUNT(*)
                     FROM collection_entries ce
                     JOIN entry_state es ON es.entry_id = ce.entry_id
                     JOIN entries ie ON ie.id = ce.entry_id
                     WHERE ce.collection_id = e.id
                       AND ie.merged_into_id IS NULL
                       AND es.inbox_state = 'unread') AS \"unread_count!: i64\"
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag'
               AND e.merged_into_id IS NULL
               AND EXISTS (
                   SELECT 1 FROM collection_entries ce_item
                   WHERE ce_item.collection_id = e.id AND ce_item.entry_id = ?
               )
             ORDER BY e.title",
            item_id,
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn list_all_item_tags(&self) -> Result<Vec<ItemTag>> {
        Ok(sqlx::query_as!(
            ItemTag,
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
