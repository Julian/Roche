package com.grayvines.roche.data.local

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: String,
)

@Entity(
    tableName = "items",
    indices =
        [
            Index(value = ["feedId"]),
            Index(value = ["status"]),
            Index(value = ["contentType"]),
            Index(value = ["isFavorite"]),
        ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val feedId: String?,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String, val name: String)

@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    indices = [Index(value = ["itemId"]), Index(value = ["tagId"])],
)
data class ItemTagEntity(val itemId: String, val tagId: String)
