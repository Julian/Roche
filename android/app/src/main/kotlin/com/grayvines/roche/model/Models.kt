package com.grayvines.roche.model

enum class ContentType(val wireValue: String) {
    ARTICLE("article"),
    PDF("pdf"),
    VIDEO("video");

    companion object {
        fun fromWireValue(value: String): ContentType =
            when {
                value.equals("paper", ignoreCase = true) ||
                    value.equals("pdf", ignoreCase = true) -> PDF
                value.equals("video", ignoreCase = true) -> VIDEO
                else -> ARTICLE
            }
    }
}

enum class ItemStatus(val wireValue: String) {
    UNREAD("unread"),
    READ("read"),
    ARCHIVED("archived");

    companion object {
        fun fromWireValue(value: String): ItemStatus =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: UNREAD
    }
}

enum class FeedType(val wireValue: String) {
    RSS("rss"),
    ARXIV("arxiv"),
    YOUTUBE("youtube"),
    SAVED("saved");

    companion object {
        fun fromWireValue(value: String): FeedType =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: RSS
    }
}

enum class ThemeMode {
    DARK,
    LIGHT,
}

enum class HeadlineFont {
    SERIF,
    SANS,
}

enum class InboxFilter(val label: String) {
    UNREAD("Unread"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
    STARRED("Starred"),
    ARCHIVED("Archived"),
}

enum class AppTab(val label: String) {
    INBOX("Inbox"),
    SEARCH("Search"),
    FEEDS("Feeds"),
    OFFLINE("Offline"),
    SETTINGS("Settings"),
}

data class FeedItem(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: FeedType,
    val unreadCount: Int,
)

data class TagItem(val id: String, val name: String, val unreadCount: Int)

data class InboxEntry(
    val id: String,
    val feedId: String?,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: ContentType,
    val status: ItemStatus,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
    val tags: List<String>,
) {
    val isArchived: Boolean
        get() = status == ItemStatus.ARCHIVED

    val isUnread: Boolean
        get() = status == ItemStatus.UNREAD

    val showsThumbnailPreview: Boolean
        get() = contentType != ContentType.ARTICLE

    val readEstimateLabel: String
        get() =
            when (contentType) {
                ContentType.VIDEO -> duration ?: "Video"
                ContentType.PDF -> "PDF"
                ContentType.ARTICLE ->
                    formatArticleMinutes(contentText ?: contentHtml ?: summary.orEmpty())
            }
}

internal fun formatArticleMinutes(text: String): String {
    val wordCount = text.split(WHITESPACE_REGEX_TOP).count { it.isNotBlank() }
    val minutes = (wordCount / ARTICLE_WORDS_PER_MINUTE).coerceAtLeast(1)
    return "${minutes}m"
}

private const val ARTICLE_WORDS_PER_MINUTE = 180
private val WHITESPACE_REGEX_TOP = Regex("""\s+""")
