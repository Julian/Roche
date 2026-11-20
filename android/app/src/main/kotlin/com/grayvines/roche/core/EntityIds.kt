package com.grayvines.roche.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

private const val DIGEST_ALGORITHM_SHA256 = "SHA-256"
private const val UUID_BYTE_LENGTH = 16
private const val UUID_VERSION_BYTE_INDEX = 6
private const val UUID_VARIANT_BYTE_INDEX = 8
private const val UUID_VERSION_MASK = 0x0f
private const val UUID_VERSION_5_BITS = 0x50
private const val UUID_VARIANT_MASK = 0x3f
private const val UUID_VARIANT_RFC4122_BITS = 0x80

internal const val DEFAULT_LOCAL_HLC_NODE = "android"
internal const val FLAG_LOCAL_HLC = "local_hlc"
internal const val SEED_EPOCH_ISO = "1970-01-01T00:00:00Z"

internal const val ENTRY_KIND_COLLECTION = "collection"
internal const val ENTRY_KIND_CHANNEL = "channel"
internal const val ENTRY_KIND_PODCAST = "podcast"
internal const val ENTRY_KIND_PUBLICATION = "publication"
internal const val ENTRY_KIND_PODCAST_EPISODE = "podcast_episode"
internal const val ENTRY_KIND_PAPER = "paper"
internal const val ENTRY_KIND_VIDEO = "video"
internal const val ENTRY_KIND_ARTICLE = "article"

internal const val STARRED_SLUG = "starred"
internal const val STARRED_TITLE = "Starred"
internal const val STARRED_COLLECTION_ID = "533b1315-6e2e-57d3-ba85-dc88531c5a0b"

internal const val UP_NEXT_SLUG = "up_next"
internal const val UP_NEXT_TITLE = "Up Next"
internal const val UP_NEXT_COLLECTION_ID = "3864ff37-5ed3-5256-9380-f85ff8054f1c"

const val WANT_TO_READ_SLUG = "want:read"
const val WANT_TO_READ_TITLE = "Want to Read"
const val WANT_TO_READ_COLLECTION_ID = "2ef71101-a716-5331-9515-cb7b5ce8b3c3"

internal val PROTECTED_SYSTEM_COLLECTION_IDS: Set<String> =
    setOf(STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID, WANT_TO_READ_COLLECTION_ID)

internal const val TOMBSTONE_ENTITY_ENTRY = "entry"
internal const val TOMBSTONE_ENTITY_FEED = "feed"
internal const val TOMBSTONE_ENTITY_ITEM = "item"
internal const val TOMBSTONE_ENTITY_TAG = "tag"
internal const val TOMBSTONE_ENTITY_COLLECTION_ENTRY = "collection_entry"
internal const val TOMBSTONE_ENTITY_COLLECTION_GENERATOR = "collection_generator"
internal const val TOMBSTONE_ENTITY_CONSUMPTION_LOG = "consumption_log"

internal const val MAX_CONSECUTIVE_FAILURES = 20

internal fun normalizeConsecutiveFailures(lastError: String?, consecutiveFailures: Int): Int =
    when {
        lastError.isNullOrBlank() -> 0
        consecutiveFailures <= 0 -> 1
        else -> consecutiveFailures.coerceAtMost(MAX_CONSECUTIVE_FAILURES)
    }

/**
 * Generates a deterministic RFC 4122 v5-shaped UUID from `(kind, naturalKey)` using SHA-256 so
 * every peer (Rust, Android, CLI, Web) derives the exact same UUID for the same entity.
 */
internal fun deterministicEntityUuid(kind: String, naturalKey: String): String {
    val digest =
        MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256).run {
            update("roche:entity:v1:".toByteArray(StandardCharsets.UTF_8))
            update(kind.toByteArray(StandardCharsets.UTF_8))
            update(":".toByteArray(StandardCharsets.UTF_8))
            update(naturalKey.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    val bytes = digest.copyOfRange(0, UUID_BYTE_LENGTH)
    bytes[UUID_VERSION_BYTE_INDEX] =
        (bytes[UUID_VERSION_BYTE_INDEX].toInt() and UUID_VERSION_MASK or UUID_VERSION_5_BITS)
            .toByte()
    bytes[UUID_VARIANT_BYTE_INDEX] =
        (bytes[UUID_VARIANT_BYTE_INDEX].toInt() and UUID_VARIANT_MASK or UUID_VARIANT_RFC4122_BITS)
            .toByte()
    val buffer = ByteBuffer.wrap(bytes)
    val msb = buffer.long
    val lsb = buffer.long
    return UUID(msb, lsb).toString()
}

internal fun feedNaturalKey(url: String): String = canonicalizeSyncUrl(url)

internal fun itemNaturalKey(feedUrl: String?, itemUrl: String): String {
    val cleanFeedUrl = feedUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val cleanItemUrl = canonicalizeSyncUrl(itemUrl)
    return if (cleanFeedUrl != null) {
        "$cleanFeedUrl\u0000$cleanItemUrl"
    } else {
        "manual\u0000$cleanItemUrl"
    }
}

internal fun tagNaturalKey(name: String): String = name.trim().lowercase()

internal fun collectionEntryNaturalKey(collectionId: String, entryId: String): String =
    "${collectionId.trim()}\u0000${entryId.trim()}"

internal fun deterministicFeedId(url: String): String =
    deterministicEntityUuid("feed", feedNaturalKey(url))

internal fun deterministicItemId(feedUrl: String?, itemUrl: String): String =
    deterministicEntityUuid("item", itemNaturalKey(feedUrl, itemUrl))

internal fun deterministicTagId(name: String): String =
    deterministicEntityUuid("tag", tagNaturalKey(name))

internal fun systemCollectionUuid(slug: String): String =
    deterministicEntityUuid("system_collection", slug.trim().lowercase())

internal fun deterministicCatalogEntryId(kind: String, scheme: String, value: String): String =
    deterministicEntityUuid(
        "entry",
        "${kind.trim().lowercase()}\u0000${scheme.trim().lowercase()}\u0000${value.trim()}",
    )

internal fun deterministicTitleEntryId(kind: String, title: String): String =
    deterministicEntityUuid(
        "entry_title",
        "${kind.trim().lowercase()}\u0000${title.trim().lowercase()}",
    )

internal fun deterministicConsumptionLogId(
    entryId: String,
    tsMillis: Long,
    status: String,
): String =
    deterministicEntityUuid(
        "consumption_log",
        "${entryId.trim()}\u0000$tsMillis\u0000${status.trim().lowercase()}",
    )

internal fun deterministicAutoLogId(entryId: String): String =
    deterministicEntityUuid("auto_consumption_log", entryId.trim())

internal fun canonicalOrderingJson(ordering: List<List<String>>): String =
    ordering.joinToString(prefix = "[", postfix = "]", separator = ",") { group ->
        group
            .map { it.trim() }
            .sorted()
            .joinToString(prefix = "[", postfix = "]", separator = ",") { id ->
                buildString(id.length + 2) {
                    append('"')
                    for (ch in id) {
                        when (ch) {
                            '\\' -> append("\\\\")
                            '"' -> append("\\\"")
                            else -> append(ch)
                        }
                    }
                    append('"')
                }
            }
    }

internal fun deterministicComparisonId(
    scopeCollectionId: String?,
    tsMillis: Long,
    ordering: List<List<String>>,
): String {
    val scopeKey = scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() } ?: "global"
    val ordJson = canonicalOrderingJson(ordering)
    return deterministicEntityUuid("comparison", "$scopeKey\u0000$tsMillis\u0000$ordJson")
}
