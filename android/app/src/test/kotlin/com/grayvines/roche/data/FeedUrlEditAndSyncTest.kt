@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "StringLiteralDuplication")

package com.grayvines.roche.data

import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.deterministicFeedId
import com.grayvines.roche.core.feedNaturalKey
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.data.catalog.EntryIdentifierDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.SyncFeedSourceDto
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FeedUrlEditAndSyncTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `updateFeedUrl preserves id, deduplicates repolled items, migrates tombstones, and rejects invalid or conflicting urls`() =
        runTest {
            val env = TestRocheEnv(tempDir, "feed_url_edit.preferences_pb")
            try {
                val dao = env.database.dao()
                val oldUrl = "https://oldsite.example.com/rss.xml"
                val newUrl = "https://newsite.example.com/atom.xml"
                val feedXml =
                    """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>""" +
                        """<title>Editorial Feed</title><link>https://oldsite.example.com/</link>""" +
                        """<item><title>First Essay</title><link>https://essays.example.com/1</link></item>""" +
                        """<item><title>Second Essay</title><link>https://essays.example.com/2</link></item>""" +
                        """</channel></rss>"""
                val transport =
                    com.grayvines.roche.data.remote.HttpTransport {
                        HttpResponse(
                            statusCode = 200,
                            body = feedXml,
                            headers = mapOf("Content-Type" to "application/rss+xml"),
                        )
                    }
                val fetcher = PublicWebFetcher(transport = transport)
                val repo =
                    mockRepository(
                        database = env.database,
                        settings = env.settings,
                        transport = transport,
                    )

                val feedId = deterministicFeedId(oldUrl)
                dao.upsertFeed(
                    FeedRow(
                        id = feedId,
                        title = "Editorial Feed",
                        url = oldUrl,
                        siteUrl = "https://oldsite.example.com/",
                        feedType = "rss",
                        lastPolled = "2026-11-20T00:00:00Z",
                        lastError = "HTTP 404",
                        lastErrorBy = "phone-1",
                        consecutiveFailures = 3,
                    )
                )

                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = fetcher,
                            feed = dao.getFeedById(feedId)!!,
                            localPeerId = "phone-1",
                            writeLock = env.database.writeLock,
                        )
                        .isSuccess
                )
                val initialItems = dao.getItemsForFeed(feedId, oldUrl).associateBy { it.title }
                assertEquals(2, initialItems.size)
                val firstId = initialItems.getValue("First Essay").id
                val secondId = initialItems.getValue("Second Essay").id

                // Hard-delete Second Essay and simulate a subsequent poll error before editing URL.
                assertTrue(repo.deleteItem(secondId).isSuccess)
                dao.upsertFeedSource(
                    dao.getFeedSourceById(feedId)!!.copy(
                        lastError = "HTTP 410",
                        lastErrorBy = "phone-1",
                        consecutiveFailures = 2,
                    )
                )

                // Edit URL using bare host syntax -> normalizes to https:// and preserves feedId.
                val updated = repo.updateFeedUrl(feedId, "newsite.example.com/atom.xml")
                assertTrue(updated.isSuccess)
                assertEquals(newUrl to true, updated.getOrThrow())

                // Re-submitting the canonical equivalent URL reports unchanged (changed = false).
                val unchanged = repo.updateFeedUrl(feedId, "HTTPS://NEWSITE.EXAMPLE.COM/atom.xml")
                assertTrue(unchanged.isSuccess)
                assertEquals(newUrl to false, unchanged.getOrThrow())

                val afterEdit = dao.getFeedById(feedId)!!
                assertEquals(feedId, afterEdit.id)
                assertEquals(newUrl, afterEdit.url)
                assertNull(afterEdit.siteUrl)
                assertNull(afterEdit.lastPolled)
                assertNull(afterEdit.lastError)
                assertEquals(0, afterEdit.consecutiveFailures)
                assertTrue(dao.getFeedSourceById(feedId)!!.isLocalDirty)

                val feedIdents =
                    dao.getIdentifiersForEntry(feedId).filter { it.scheme == "feed_url" }
                assertEquals(listOf(feedNaturalKey(newUrl)), feedIdents.map { it.value })

                // Re-polling newUrl keeps First Essay deduplicated and skips tombstoned Second
                // Essay.
                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = fetcher,
                            feed = afterEdit,
                            localPeerId = "phone-1",
                            writeLock = env.database.writeLock,
                        )
                        .isSuccess
                )
                val afterRepoll = dao.getItemsForFeed(feedId, newUrl)
                assertEquals(listOf(firstId), afterRepoll.map { it.id })

                // Invalid, SSRF/private, single-label, and incompatible source URLs are rejected.
                assertTrue(repo.updateFeedUrl(feedId, "   ").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "ftp://example.com/feed.xml").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "http://127.0.0.1:8080/rss.xml").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "http://192.168.1.5/rss.xml").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "intranet").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "https://rss.arxiv.org/rss/cs.AI").isFailure)
                assertTrue(repo.updateFeedUrl(feedId, "UCX6OQ3DkcsbYNE6H8uQQuVA").isFailure)
                assertTrue(
                    repo
                        .updateFeedUrl(
                            feedId,
                            "https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA",
                        )
                        .isFailure
                )

                // YouTube feed rejects non-feed watch URLs on edit.
                val ytUrl =
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA"
                val ytId = deterministicFeedId(ytUrl)
                dao.upsertFeed(FeedRow(ytId, "YT", ytUrl, null, "youtube"))
                assertTrue(
                    repo
                        .updateFeedUrl(ytId, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .isFailure
                )

                // Case-sensitive paths on the same host remain distinct feeds.
                val upperPath = "https://case.example.com/FeedA"
                val lowerPath = "https://case.example.com/feeda"
                val caseId = deterministicFeedId(upperPath)
                dao.upsertFeed(FeedRow(caseId, "Case Upper", upperPath, null, "rss"))
                val lowerSub = repo.discoverAndSubscribeFeed(lowerPath)
                assertTrue(lowerSub.isSuccess)
                assertNotEquals(caseId, dao.getAllFeeds().first { it.url == lowerPath }.id)

                // Re-subscribing to oldUrl succeeds with a distinct collectionId instead of
                // colliding.
                val resub = repo.discoverAndSubscribeFeed(oldUrl)
                assertTrue(resub.isSuccess)
                val oldAgain = dao.getAllFeeds().first { it.url == oldUrl }
                assertNotEquals(feedId, oldAgain.id)

                // Editing oldAgain to newUrl fails due to uniqueness conflict.
                val conflict = repo.updateFeedUrl(oldAgain.id, newUrl)
                assertTrue(conflict.isFailure)
            } finally {
                env.close()
            }
        }

    @Test
    fun `peer sync merges feed url edits with HLC LWW, guards collisions, and deletes moved feeds via id tombstone`() =
        runTest {
            val env = TestRocheEnv(tempDir, "feed_url_sync.preferences_pb")
            try {
                val dao = env.database.dao()
                val oldUrl = "https://sync-old.example.com/rss.xml"
                val newUrl = "https://sync-new.example.com/feed.xml"
                val otherUrl = "https://sync-other.example.com/rss.xml"
                val feedId = deterministicFeedId(oldUrl)
                val otherId = deterministicFeedId(otherUrl)
                val baseHlc = HlcTimestamp.create(1_780_000_000_000L, 1, "server").encode()
                val newerHlc = HlcTimestamp.create(1_780_000_060_000L, 1, "server").encode()
                val newestHlc = HlcTimestamp.create(1_780_000_120_000L, 1, "server").encode()
                val staleHlc = HlcTimestamp.create(1_779_999_000_000L, 1, "server").encode()

                dao.upsertFeed(
                    FeedRow(
                        id = feedId,
                        title = "Sync Feed",
                        url = oldUrl,
                        siteUrl = "https://sync-old.example.com",
                        feedType = "rss",
                        lastPolled = "2026-11-20T00:00:00Z",
                        lastError = "HTTP 410",
                        lastErrorBy = "phone-1",
                        consecutiveFailures = 2,
                    ),
                    hlcOverride = baseHlc,
                )
                dao.upsertFeed(
                    FeedRow(
                        id = otherId,
                        title = "Other Feed",
                        url = otherUrl,
                        siteUrl = null,
                        feedType = "rss",
                        lastPolled = null,
                    ),
                    hlcOverride = baseHlc,
                )

                // 1. Newer incoming HLC updates feedUrl, clears stale error & siteUrl, updates
                // identifier.
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 1,
                            serverHlc = newerHlc,
                            syncTimestamp = "2026-11-20T00:01:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = feedId,
                                        kind = "publication",
                                        title = "Sync Feed",
                                        hlc = newerHlc,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = newUrl,
                                                sourceType = "rss",
                                                feedTitle = "Sync Feed",
                                                isActive = true,
                                                hlc = newerHlc,
                                            ),
                                    )
                                ),
                        ),
                )
                val moved = dao.getFeedById(feedId)!!
                assertEquals(newUrl, moved.url)
                assertNull(moved.siteUrl)
                assertNull(moved.lastError)
                assertEquals(0, moved.consecutiveFailures)
                assertEquals(
                    listOf(feedNaturalKey(newUrl)),
                    dao.getIdentifiersForEntry(feedId)
                        .filter { it.scheme == "feed_url" }
                        .map { it.value },
                )

                // 2. Stale incoming HLC with oldUrl cannot revert feedUrl.
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 2,
                            serverHlc = newerHlc,
                            syncTimestamp = "2026-11-20T00:01:30Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = feedId,
                                        kind = "publication",
                                        title = "Sync Feed",
                                        hlc = staleHlc,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = oldUrl,
                                                sourceType = "rss",
                                                feedTitle = "Sync Feed",
                                                isActive = true,
                                                hlc = staleHlc,
                                            ),
                                    )
                                ),
                        ),
                )
                assertEquals(newUrl, dao.getFeedById(feedId)!!.url)

                // 3. Incoming URL change colliding with another subscribed feed does not overwrite
                // or delete either.
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 3,
                            serverHlc = newestHlc,
                            syncTimestamp = "2026-11-20T00:02:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = feedId,
                                        kind = "publication",
                                        title = "Sync Feed",
                                        hlc = newestHlc,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = otherUrl,
                                                sourceType = "rss",
                                                feedTitle = "Sync Feed",
                                                isActive = true,
                                                hlc = newestHlc,
                                            ),
                                    )
                                ),
                        ),
                )
                assertEquals(newUrl, dao.getFeedById(feedId)!!.url)
                assertEquals(otherUrl, dao.getFeedById(otherId)!!.url)

                // 4. Deleting a moved feed records BOTH url and collectionId tombstones.
                val repo = env.repository
                assertTrue(repo.deleteFeed(feedId).isSuccess)
                val tombKeys =
                    dao.getAllTombstones().filter { it.entityType == "feed" }.map { it.naturalKey }
                assertTrue(feedNaturalKey(newUrl) in tombKeys)
                assertTrue(feedId in tombKeys)

                // 5. Another peer still holding `(feedId, oldUrl)` deletes it upon receiving
                // `feedId` tombstone.
                val peerEnv = TestRocheEnv(tempDir, "feed_url_sync_peer2.preferences_pb")
                try {
                    val peerDao = peerEnv.database.dao()
                    peerDao.upsertFeed(
                        FeedRow(
                            id = feedId,
                            title = "Sync Feed",
                            url = oldUrl,
                            siteUrl = null,
                            feedType = "rss",
                            lastPolled = null,
                        ),
                        hlcOverride = baseHlc,
                    )
                    val deleteHlc = HlcTimestamp.create(1_780_000_180_000L, 1, "server").encode()
                    reconcilePeerSyncResponse(
                        dao = peerDao,
                        response =
                            PeerSyncResponseDto(
                                serverPeerId = "server",
                                syncSeq = 4,
                                serverHlc = deleteHlc,
                                syncTimestamp = "2026-11-20T00:03:00Z",
                                tombstones =
                                    listOf(
                                        SyncTombstoneDto(
                                            entityType = "feed",
                                            naturalKey = feedNaturalKey(newUrl),
                                            deletedAt = "2026-11-20T00:03:00Z",
                                            hlc = deleteHlc,
                                        ),
                                        SyncTombstoneDto(
                                            entityType = "feed",
                                            naturalKey = feedId,
                                            deletedAt = "2026-11-20T00:03:00Z",
                                            hlc = deleteHlc,
                                        ),
                                    ),
                            ),
                    )
                    assertNull(peerDao.getFeedById(feedId))
                } finally {
                    peerEnv.close()
                }
            } finally {
                env.close()
            }
        }

    @Test
    fun `peer sync handles resubscribed old url, chained swaps, safe tombstones, and stale poll guards`() =
        runTest {
            val env = TestRocheEnv(tempDir, "feed_url_sync_edges.preferences_pb")
            try {
                val dao = env.database.dao()
                val u1 = "https://edge-one.example.com/rss.xml"
                val u2 = "https://edge-two.example.com/rss.xml"
                val u3 = "https://edge-three.example.com/rss.xml"
                val f1Id = deterministicFeedId(u1)
                val f2Id = UUID.randomUUID().toString()
                val fBId = deterministicFeedId(u2)
                val hlcBase = HlcTimestamp.create(1_780_100_000_000L, 1, "server").encode()
                val hlcMove = HlcTimestamp.create(1_780_100_060_000L, 1, "server").encode()
                val hlcDel = HlcTimestamp.create(1_780_100_120_000L, 1, "server").encode()
                val hlcStale = HlcTimestamp.create(1_780_099_000_000L, 1, "peer-old").encode()

                dao.upsertFeed(FeedRow(f1Id, "Feed One", u1, null, "rss"), hlcOverride = hlcBase)
                dao.upsertFeed(FeedRow(fBId, "Feed B", u2, null, "rss"), hlcOverride = hlcBase)

                // 1. In one batch: F2(random id, u1) arrives BEFORE F1(u1->u2) and B(u2->u3).
                //    Existing feeds run first with deferred collision retry, so A->u2, B->u3,
                //    and new F2->u1 all converge without clobbering or merging!
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 1,
                            serverHlc = hlcMove,
                            syncTimestamp = "2026-11-20T01:01:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = f2Id,
                                        kind = "publication",
                                        title = "Resubscribed U1",
                                        hlc = hlcMove,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = u1,
                                                sourceType = "rss",
                                                feedTitle = "Resubscribed U1",
                                                isActive = true,
                                                hlc = hlcMove,
                                            ),
                                    ),
                                    SyncEntryRecordDto(
                                        id = f1Id,
                                        kind = "publication",
                                        title = "Feed One",
                                        hlc = hlcMove,
                                        identifiers = listOf(EntryIdentifierDto("feed_url", u1)),
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = u2,
                                                sourceType = "rss",
                                                feedTitle = "Feed One",
                                                isActive = true,
                                                hlc = hlcMove,
                                            ),
                                    ),
                                    SyncEntryRecordDto(
                                        id = fBId,
                                        kind = "publication",
                                        title = "Feed B",
                                        hlc = hlcMove,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = u3,
                                                sourceType = "rss",
                                                feedTitle = "Feed B",
                                                isActive = true,
                                                hlc = hlcMove,
                                            ),
                                    ),
                                ),
                        ),
                )
                assertEquals(u2, dao.getFeedById(f1Id)!!.url)
                assertEquals(u3, dao.getFeedById(fBId)!!.url)
                assertEquals(u1, dao.getFeedById(f2Id)!!.url)
                assertEquals(
                    listOf(feedNaturalKey(u2)),
                    dao.getIdentifiersForEntry(f1Id)
                        .filter { it.scheme == "feed_url" }
                        .map { it.value },
                )

                // 2. Stale-URL poll telemetry on u1 cannot overwrite moved F1(u2) health.
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 2,
                            serverHlc = hlcMove,
                            syncTimestamp = "2026-11-20T01:02:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = f1Id,
                                        kind = "publication",
                                        title = "Feed One",
                                        hlc = hlcStale,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = u1,
                                                sourceType = "rss",
                                                feedTitle = "Feed One",
                                                isActive = true,
                                                lastPolledAt = "2026-11-20T01:05:00Z",
                                                lastPolledBy = "peer-old",
                                                lastError = "HTTP 404",
                                                lastErrorBy = "peer-old",
                                                consecutiveFailures = 4,
                                                hlc = hlcStale,
                                            ),
                                    )
                                ),
                        ),
                )
                val f1AfterStalePoll = dao.getFeedById(f1Id)!!
                assertEquals(u2, f1AfterStalePoll.url)
                assertNull(f1AfterStalePoll.lastPolled)
                assertNull(f1AfterStalePoll.lastError)
                assertEquals(0, f1AfterStalePoll.consecutiveFailures)

                // 3. Deleting F2(f2Id, u1) via tombstones (u1 + f2Id) must NOT delete moved
                //    F1(det(u1), u2).
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 3,
                            serverHlc = hlcDel,
                            syncTimestamp = "2026-11-20T01:03:00Z",
                            tombstones =
                                listOf(
                                    SyncTombstoneDto(
                                        entityType = "feed",
                                        naturalKey = feedNaturalKey(u1),
                                        deletedAt = "2026-11-20T01:03:00Z",
                                        hlc = hlcDel,
                                    ),
                                    SyncTombstoneDto(
                                        entityType = "feed",
                                        naturalKey = f2Id,
                                        deletedAt = "2026-11-20T01:03:00Z",
                                        hlc = hlcDel,
                                    ),
                                ),
                        ),
                )
                assertNull(dao.getFeedById(f2Id))
                assertNotNull(dao.getFeedById(f1Id))
                assertEquals(u2, dao.getFeedById(f1Id)!!.url)

                // 4. Late item tombstone under old u1 copies to u2 for moved F1(det(u1), u2).
                val itemUrl = "https://edge-one.example.com/posts/late-delete"
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 4,
                            serverHlc = hlcDel,
                            syncTimestamp = "2026-11-20T01:04:00Z",
                            tombstones =
                                listOf(
                                    SyncTombstoneDto(
                                        entityType = "item",
                                        naturalKey = itemNaturalKey(u1, itemUrl),
                                        deletedAt = "2026-11-20T01:04:00Z",
                                        hlc = hlcDel,
                                    )
                                ),
                        ),
                )
                val itemTombs =
                    dao.getAllTombstones().filter { it.entityType == "item" }.map { it.naturalKey }
                assertTrue(itemNaturalKey(u1, itemUrl) in itemTombs)
                assertTrue(itemNaturalKey(u2, itemUrl) in itemTombs)
            } finally {
                env.close()
            }
        }
}
