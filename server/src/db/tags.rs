use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqliteConnection, SqlitePool};

use super::{
    ActivityAction, ActivityEntityType, HexColor, SyncRepo,
    sync::{deterministic_tag_id, tag_natural_key},
};

macro_rules! tag_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT t.id, t.name, t.color,
       (SELECT COUNT(*) FROM item_tags it JOIN items i ON i.id = it.item_id WHERE it.tag_id = t.id AND i.status = 'unread' AND i.read_at IS NULL) AS unread_count
FROM tags t ",
            $suffix
        )
    };
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub color: Option<HexColor>,
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
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(&mut tx, ActivityEntityType::Tag, &tag_natural_key(name)).await?;

        sqlx::query("INSERT INTO tags (id, name, color, created_at) VALUES (?, ?, ?, ?)")
            .bind(&id)
            .bind(name)
            .bind(color)
            .bind(now)
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

        Ok(Tag { id, name: name.to_string(), color: color.cloned(), unread_count: 0 })
    }

    pub async fn get(&self, id: &str) -> Result<Option<Tag>> {
        Ok(sqlx::query_as::<_, Tag>(tag_select_sql!("WHERE t.id = ?"))
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list(&self) -> Result<Vec<Tag>> {
        Ok(sqlx::query_as::<_, Tag>(tag_select_sql!("ORDER BY t.name"))
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

        let mut qb = QueryBuilder::new("UPDATE tags SET ");
        let mut separated = qb.separated(", ");
        if let Some(n) = name {
            separated.push("name = ").push_bind_unseparated(n);
        }
        if let Some(c) = color {
            separated.push("color = ").push_bind_unseparated(c);
        }
        qb.push(" WHERE id = ").push_bind(id);

        let res = qb.build().execute(&mut *tx).await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        if name.is_some() {
            sqlx::query(
                "UPDATE items SET updated_at = ?
                 WHERE id IN (SELECT item_id FROM item_tags WHERE tag_id = ?)",
            )
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
        sqlx::query(
            "UPDATE items SET updated_at = ?
             WHERE id IN (SELECT item_id FROM item_tags WHERE tag_id = ?)",
        )
        .bind(now)
        .bind(id)
        .execute(&mut *tx)
        .await?;
        let deleted_name: Option<String> =
            sqlx::query_scalar("DELETE FROM tags WHERE id = ? RETURNING name")
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
        SyncRepo::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &tag_natural_key(name))
            .await?;

        let existing_id: Option<String> = sqlx::query_scalar(
            "SELECT id FROM tags WHERE id = ? OR LOWER(name) = LOWER(?) LIMIT 1",
        )
        .bind(&new_tag_id)
        .bind(name)
        .fetch_optional(&mut *conn)
        .await?;

        let tag_id = if let Some(id) = existing_id {
            id
        } else {
            let tag_insert = sqlx::query(
                "INSERT OR IGNORE INTO tags (id, name, color, created_at) VALUES (?, ?, NULL, ?)",
            )
            .bind(&new_tag_id)
            .bind(name)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            if tag_insert.rows_affected() > 0 {
                SyncRepo::record_activity(
                    &mut *conn,
                    ActivityEntityType::Tag,
                    &new_tag_id,
                    ActivityAction::Create,
                    now,
                )
                .await?;
            }
            sqlx::query_scalar("SELECT id FROM tags WHERE id = ? OR LOWER(name) = LOWER(?) LIMIT 1")
                .bind(&new_tag_id)
                .bind(name)
                .fetch_one(&mut *conn)
                .await?
        };

        Self::attach_in_tx(&mut *conn, item_id, &tag_id, now).await?;

        let tag: Tag = sqlx::query_as(tag_select_sql!("WHERE t.id = ?"))
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
        let res = sqlx::query("INSERT OR IGNORE INTO item_tags (item_id, tag_id) VALUES (?, ?)")
            .bind(item_id)
            .bind(tag_id)
            .execute(&mut *conn)
            .await?;
        if res.rows_affected() > 0 {
            sqlx::query("UPDATE items SET interaction_updated_at = ?, updated_at = ? WHERE id = ?")
                .bind(now)
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
        let res = sqlx::query("DELETE FROM item_tags WHERE item_id = ? AND tag_id = ?")
            .bind(item_id)
            .bind(tag_id)
            .execute(&mut *tx)
            .await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        sqlx::query("UPDATE items SET interaction_updated_at = ?, updated_at = ? WHERE id = ?")
            .bind(now)
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
            "JOIN item_tags it ON t.id = it.tag_id
             WHERE it.item_id = ?
             ORDER BY t.name"
        ))
        .bind(item_id)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn list_all_item_tags(&self) -> Result<Vec<ItemTag>> {
        Ok(sqlx::query_as::<_, ItemTag>(
            "SELECT item_id, tag_id FROM item_tags ORDER BY item_id, tag_id LIMIT 20000",
        )
        .fetch_all(self.pool)
        .await?)
    }
}
