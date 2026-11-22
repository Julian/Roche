package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.ENTRY_KIND_PODCAST
import com.grayvines.roche.core.ENTRY_KIND_PODCAST_EPISODE
import com.grayvines.roche.core.FLAG_LOCAL_HLC
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.inferContentTypeWireValue
import com.grayvines.roche.core.isDirectAudioUrl
import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.core.resolveArticleDuration
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryContentPreviewRow
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_SUMMARY_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.entryKindForFeedTypeWire
import com.grayvines.roche.data.local.entryKindForItemWire
import com.grayvines.roche.data.local.nextLocalHlc

internal const val FLAG_REPAIRED_IMPLICIT_PODCASTS = "repaired_implicit_podcasts_v1"

private const val CONTENT_TYPE_AUDIO = "audio"
private const val CONTENT_TYPE_ARTICLE = "article"
private const val FEED_TYPE_RSS = "rss"
private const val PODCAST_MAJORITY_DENOMINATOR = 2
private const val SQLITE_IN_CHUNK_SIZE = 450

internal suspend fun repairPodcastDataInTx(
    dao: RocheDao,
    nowIso: String,
    nodeId: String,
) {
    val entries = dao.getAllEntries()
    val feedSources = dao.getAllFeedSources()
    val maxExistingHlc =
        (entries.asSequence().map { it.hlc } + feedSources.asSequence().map { it.hlc })
            .mapNotNull { HlcTimestamp.parse(it) }
            .maxOrNull()
            ?.encode()
    if (maxExistingHlc != null) {
        dao.upsertLocalSyncFlagIfGreater(FLAG_LOCAL_HLC, maxExistingHlc)
    }
    val entriesById = entries.associateBy { it.id }
    val previews = dao.getAllEntryContentPreviews()
    val revertedIds = repairAudioEntriesInTx(dao, entriesById, previews, nowIso, nodeId)
    repairPodcastFeedsInTx(dao, feedSources, entriesById, previews, revertedIds, nowIso, nodeId)
}

private suspend fun repairAudioEntriesInTx(
    dao: RocheDao,
    entriesById: Map<String, EntryEntity>,
    previews: List<EntryContentPreviewRow>,
    nowIso: String,
    nodeId: String,
): Set<String> {
    val candidateIds =
        previews
            .filter { row ->
                val entry = entriesById[row.entryId]
                entry != null &&
                    entry.mergedIntoId.isNullOrEmpty() &&
                    (isAudioContentType(row.contentType) ||
                        isBogusWebpageAudioUrl(row.audioUrl, entry.url, row.duration))
            }
            .map { it.entryId }
    if (candidateIds.isEmpty()) return emptySet()

    val fullContents =
        candidateIds.chunked(SQLITE_IN_CHUNK_SIZE).flatMap { chunk ->
            dao.getEntryContentsByIds(chunk)
        }
    val reverted = HashSet<String>()
    for (content in fullContents) {
        val entry = entriesById[content.entryId]
        if (entry != null && repairSingleAudioContent(dao, entry, content, nowIso, nodeId)) {
            reverted.add(entry.id)
        }
    }
    return reverted
}

private suspend fun repairSingleAudioContent(
    dao: RocheDao,
    entry: EntryEntity,
    content: EntryContentEntity,
    nowIso: String,
    nodeId: String,
): Boolean {
    val cleanedAudio =
        content.audioUrl?.trim()?.takeIf {
            it.isNotEmpty() && !isBogusWebpageAudioUrl(it, entry.url, content.duration)
        }
    val audioCleared = cleanedAudio == null && !content.audioUrl.isNullOrBlank()
    if (isAudioContentType(content.contentType) && cleanedAudio == null) {
        val cleanUrl = entry.url?.trim()?.takeIf { it.isNotEmpty() }
        val inferred =
            if (cleanUrl != null) inferContentTypeWireValue(cleanUrl) else CONTENT_TYPE_ARTICLE
        if (inferred != CONTENT_TYPE_AUDIO) {
            applyEntryReclassification(dao, entry, content, inferred, nowIso, nodeId)
            return true
        }
    }
    if (audioCleared) {
        dao.upsertEntryContent(content.copy(audioUrl = null))
        val hlc = nextLocalHlc(dao, nodeId)
        dao.upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true))
    }
    return false
}

@Suppress("LongParameterList")
private suspend fun applyEntryReclassification(
    dao: RocheDao,
    entry: EntryEntity,
    content: EntryContentEntity,
    reclassifiedType: String,
    nowIso: String,
    nodeId: String,
) {
    val targetKind =
        if (entry.kind == ENTRY_KIND_PODCAST_EPISODE) {
            entryKindForItemWire(reclassifiedType, null)
        } else {
            entry.kind
        }
    val recomputedDuration =
        resolveArticleDuration(
            existingDuration = null,
            isArticle = reclassifiedType == CONTENT_TYPE_ARTICLE,
            hasExtendedContent =
                content.contentHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS ||
                    content.contentText.orEmpty().length > LIST_PREVIEW_TEXT_CHARS ||
                    entry.summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS,
            fallbackTextSource =
                content.contentText ?: content.contentHtml ?: entry.summary.orEmpty(),
        )
    dao.upsertEntryContent(
        content.copy(
            contentType = reclassifiedType,
            audioUrl = null,
            duration = recomputedDuration,
        )
    )
    val hlc = nextLocalHlc(dao, nodeId)
    dao.upsertEntry(
        entry.copy(
            kind = targetKind,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
}

@Suppress("LongParameterList")
private suspend fun repairPodcastFeedsInTx(
    dao: RocheDao,
    feedSources: List<FeedSourceEntity>,
    entriesById: Map<String, EntryEntity>,
    previews: List<EntryContentPreviewRow>,
    revertedIds: Set<String>,
    nowIso: String,
    nodeId: String,
) {
    val liveFeeds =
        feedSources.filter { fs ->
            val feedEntry = entriesById[fs.collectionId]
            feedEntry != null && feedEntry.mergedIntoId.isNullOrEmpty()
        }
    if (liveFeeds.isEmpty()) return

    val previewsById = previews.associateBy { it.entryId }
    val membersByCollection = dao.getAllCollectionEntries().groupBy { it.collectionId }
    for (fs in liveFeeds) {
        val feedEntry = entriesById[fs.collectionId] ?: continue
        val targetSourceType =
            resolveTargetFeedSourceType(
                fs = fs,
                members = membersByCollection[fs.collectionId].orEmpty().map { it.entryId },
                entriesById = entriesById,
                previewsById = previewsById,
                revertedIds = revertedIds,
            )
        val expectedKind = entryKindForFeedTypeWire(targetSourceType)
        if (targetSourceType != fs.sourceType || feedEntry.kind != expectedKind) {
            alignFeedTypeAndKindInTx(
                dao = dao,
                fs = fs,
                feedEntry = feedEntry,
                targetSourceType = targetSourceType,
                expectedKind = expectedKind,
                nowIso = nowIso,
                nodeId = nodeId,
            )
        }
    }
}

private fun resolveTargetFeedSourceType(
    fs: FeedSourceEntity,
    members: List<String>,
    entriesById: Map<String, EntryEntity>,
    previewsById: Map<String, EntryContentPreviewRow>,
    revertedIds: Set<String>,
): String {
    if (!fs.sourceType.equals(ENTRY_KIND_PODCAST, ignoreCase = true)) return fs.sourceType
    val liveMemberIds =
        members.distinct().filter { id ->
            val member = entriesById[id]
            member != null && member.mergedIntoId.isNullOrEmpty() && previewsById[id] != null
        }
    val audioCount = liveMemberIds.count { id ->
        id !in revertedIds && hasRealAudioContent(entriesById[id], previewsById[id])
    }
    val majorityNonAudio =
        liveMemberIds.isNotEmpty() && audioCount * PODCAST_MAJORITY_DENOMINATOR < liveMemberIds.size
    return if (majorityNonAudio) FEED_TYPE_RSS else fs.sourceType
}

@Suppress("LongParameterList")
private suspend fun alignFeedTypeAndKindInTx(
    dao: RocheDao,
    fs: FeedSourceEntity,
    feedEntry: EntryEntity,
    targetSourceType: String,
    expectedKind: String,
    nowIso: String,
    nodeId: String,
) {
    if (!fs.sourceType.equals(targetSourceType, ignoreCase = true)) {
        val fsHlc = nextLocalHlc(dao, nodeId)
        dao.upsertFeedSource(
            fs.copy(sourceType = targetSourceType, hlc = fsHlc, isLocalDirty = true)
        )
    }
    if (feedEntry.kind != expectedKind) {
        val entryHlc = nextLocalHlc(dao, nodeId)
        dao.upsertEntry(
            feedEntry.copy(
                kind = expectedKind,
                updatedAt = nowIso,
                hlc = entryHlc,
                isLocalDirty = true,
            )
        )
    }
}

private fun isAudioContentType(raw: String): Boolean =
    raw.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
        raw.equals(ENTRY_KIND_PODCAST, ignoreCase = true)

private fun isBogusWebpageAudioUrl(
    audioUrl: String?,
    entryUrl: String?,
    duration: String?,
): Boolean {
    if (parseDurationToSecondsLong(duration) != null) return false
    val cleanAudio = audioUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val cleanEntry = entryUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return canonicalizeSyncUrl(cleanAudio) == canonicalizeSyncUrl(cleanEntry) &&
        !isDirectAudioUrl(cleanAudio)
}

private fun hasRealAudioContent(
    entry: EntryEntity?,
    preview: EntryContentPreviewRow?,
): Boolean {
    if (entry == null || preview == null) return false
    val hasAudioUrl =
        preview.audioUrl?.trim()?.let {
            it.isNotEmpty() && !isBogusWebpageAudioUrl(it, entry.url, preview.duration)
        } == true
    return hasAudioUrl || isAudioContentType(preview.contentType)
}
