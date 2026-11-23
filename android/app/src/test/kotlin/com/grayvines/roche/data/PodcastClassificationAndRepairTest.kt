@file:Suppress("LongMethod", "TooManyFunctions", "LargeClass")

package com.grayvines.roche.data

import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncEntryContentDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.SyncFeedSourceDto
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.feeds.resolveCandidateContentType
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.media.mergeHydratedItemDetail
import com.grayvines.roche.media.resolveOfflineAudioUrl
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodcastClassificationAndRepairTest {
    @Test
    fun `polling rss feed with audio keeps rss type and podcast text post stays article`() =
        runTest {
            assertEquals("article", resolveCandidateContentType("podcast", "https://ex.com/post"))
            assertEquals("audio", resolveCandidateContentType("rss", "https://ex.com/track.mp3"))

            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val rssFeed =
                    FeedRow(
                        id = "f-blog",
                        title = "Newsletter",
                        url = "https://blog.example.com/feed",
                        feedType = "rss",
                    )
                val podcastFeed =
                    FeedRow(
                        id = "f-pod",
                        title = "Real Podcast",
                        url = "https://pod.example.com/feed",
                        feedType = "podcast",
                    )
                dao.upsertFeed(rssFeed)
                dao.upsertFeed(podcastFeed)

                val fetcher = buildTestFeedFetcher(rssFeed.url, podcastFeed.url)
                val pollNow = Instant.parse("2026-11-22T12:00:00Z")
                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = fetcher,
                            feed = rssFeed,
                            localPeerId = "android-test",
                            nowInstant = pollNow,
                        )
                        .isSuccess
                )
                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = fetcher,
                            feed = podcastFeed,
                            localPeerId = "android-test",
                            nowInstant = pollNow,
                        )
                        .isSuccess
                )

                assertEquals("rss", dao.getFeedById(rssFeed.id)?.feedType)
                val blogItems =
                    dao.getItemsForFeed(rssFeed.id, rssFeed.url).associateBy { it.title }
                assertEquals("article", blogItems.getValue("Audio Interview").contentType)
                assertEquals("article", blogItems.getValue("Audio Interview").entryKind)
                assertEquals(
                    "https://cdn.example.com/interview.mp3",
                    blogItems.getValue("Audio Interview").audioUrl,
                )
                assertEquals("article", blogItems.getValue("Text Essay").contentType)

                val podItems =
                    dao.getItemsForFeed(podcastFeed.id, podcastFeed.url).associateBy { it.title }
                assertEquals("article", podItems.getValue("Text Announcement").contentType)
                assertNull(podItems.getValue("Text Announcement").audioUrl)
                assertNull(podItems.getValue("Text Announcement").duration)
            }
        }

    @Test
    fun `buildSnapshot never overrides rss feed type to podcast based on audio entries`() {
        val feed =
            FeedRow(
                id = "f-blog",
                title = "Newsletter",
                url = "https://blog.example.com/feed",
                feedType = "rss",
            )
        val audioEntry =
            InboxItemRow(
                id = "i-audio",
                feedId = feed.id,
                feedTitle = feed.title,
                title = "Audio Interview",
                url = "https://blog.example.com/p/interview",
                author = null,
                summary = null,
                contentText = null,
                contentHtml = null,
                contentType = "audio",
                status = "unread",
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = null,
                createdAt = "2026-11-22T10:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = null,
                audioUrl = "https://cdn.example.com/interview.mp3",
            )
        val snap =
            buildSnapshot(
                items = listOf(audioEntry),
                feeds = listOf(feed),
                tags = emptyList(),
                itemTags = emptyList(),
            )
        assertEquals(FeedType.RSS, snap.feeds.single().feedType)
        assertEquals(ContentType.AUDIO, snap.entries.single().contentType)
    }

    @Test
    fun `reconcilePeerSyncResponse reverts podcast feed and bogus audio on newer HLC only`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val oldHlc = "001794000000000-0000-android"
                val newerHlc = "001796000000000-0000-server"
                val ts = "2026-11-01T00:00:00Z"
                seedPodcastFeedFixture(
                    dao,
                    "f-blog",
                    "Newsletter",
                    "https://blog.example.com/feed",
                    ts,
                    oldHlc,
                )
                seedAudioItemFixture(
                    dao = dao,
                    feedId = "f-blog",
                    id = "i-essay",
                    url = "https://blog.example.com/p/essay",
                    audioUrl = "https://blog.example.com/p/essay",
                    ts = ts,
                    hlc = oldHlc,
                )

                reconcilePeerSyncResponse(
                    dao = dao,
                    response = buildRepairedSyncResponse(newerHlc),
                )
                assertEquals("rss", dao.getFeedSourceById("f-blog")?.sourceType)
                assertEquals("publication", dao.getEntryById("f-blog")?.kind)
                assertEquals("article", dao.getEntryById("i-essay")?.kind)
                assertEquals("article", dao.getEntryContentById("i-essay")?.contentType)
                assertNull(dao.getEntryContentById("i-essay")?.audioUrl)

                // Replaying stale older HLC podcast/audio records must not re-corrupt repaired rows
                reconcilePeerSyncResponse(
                    dao = dao,
                    response = buildStaleCorruptedSyncResponse(oldHlc),
                )
                assertEquals("rss", dao.getFeedSourceById("f-blog")?.sourceType)
                assertEquals("article", dao.getEntryById("i-essay")?.kind)
                assertEquals("article", dao.getEntryContentById("i-essay")?.contentType)
                assertNull(dao.getEntryContentById("i-essay")?.audioUrl)
            }
        }

    @Test
    fun `CatalogSeeder one-time repair fixes bogus audio URLs orphan episodes and blog feeds`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                seedCorruptedPodcastFixtures(dao)

                val store = CatalogStore(dao = dao, writeLock = managed.database.writeLock)
                store.ensureSystemSeeded()

                assertEquals("rss", dao.getFeedSourceById("f-blog")?.sourceType)
                assertEquals("publication", dao.getEntryById("f-blog")?.kind)
                assertEquals("article", dao.getEntryById("i-real-audio")?.kind)
                assertEquals("article", dao.getEntryContentById("i-real-audio")?.contentType)
                assertEquals(
                    "https://cdn.example.com/ep.mp3",
                    dao.getEntryContentById("i-real-audio")?.audioUrl,
                )

                for (id in listOf("i-bogus-audio", "i-orphan-audio")) {
                    assertEquals("article", dao.getEntryById(id)?.kind)
                    assertEquals("article", dao.getEntryContentById(id)?.contentType)
                    assertNull(dao.getEntryContentById(id)?.audioUrl)
                }

                assertEquals("podcast", dao.getFeedSourceById("f-true-pod")?.sourceType)
                assertEquals("podcast", dao.getEntryById("f-true-pod")?.kind)
                assertEquals("podcast", dao.getFeedSourceById("f-empty-pod")?.sourceType)
                assertEquals("podcast_episode", dao.getEntryById("i-manual-ep")?.kind)
            }
        }

    @Test
    fun `repair preserves HLC order deleted history linkless audio heals split brain and pushes`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val futureWallMs = System.currentTimeMillis() + 600_000L
                val futureHlc = HlcTimestamp.create(futureWallMs, 3, "server").encode()
                val ts = "2026-11-17T08:00:00Z"

                seedPodcastFeedFixture(
                    dao,
                    "f-blog",
                    "Blog",
                    "https://blog.example.com/feed",
                    ts,
                    futureHlc,
                )
                seedAudioItemFixture(
                    dao = dao,
                    feedId = "f-blog",
                    id = "i-short-orphan",
                    url = "https://blog.example.com/p/short",
                    audioUrl = null,
                    ts = ts,
                    hlc = futureHlc,
                    duration = "42:15",
                )
                val longBody = "<p>" + "word ".repeat(600) + "</p>"
                seedAudioItemFixture(
                    dao = dao,
                    feedId = "f-blog",
                    id = "i-long-orphan",
                    url = "https://blog.example.com/p/long",
                    audioUrl = null,
                    ts = ts,
                    hlc = futureHlc,
                    duration = "35:00",
                    contentHtml = longBody,
                )

                // Genuine podcast with 1 linkless extensionless episode + 3 deleted-with-history
                // rows
                seedPodcastFeedFixture(
                    dao,
                    "f-pod-hist",
                    "History Pod",
                    "https://pod-hist.example.com/feed",
                    ts,
                    futureHlc,
                )
                seedAudioItemFixture(
                    dao = dao,
                    feedId = "f-pod-hist",
                    id = "i-linkless-ep",
                    url = "https://stream.example.com/episodes/42",
                    audioUrl = "https://stream.example.com/episodes/42",
                    ts = ts,
                    hlc = futureHlc,
                    duration = "18:30",
                )
                for (idx in 1..3) {
                    val histId = "i-deleted-hist-$idx"
                    dao.upsertEntry(
                        EntryEntity(
                            id = histId,
                            kind = "podcast_episode",
                            title = "Deleted Ep $idx",
                            url = "https://pod-hist.example.com/ep-$idx",
                            createdAt = ts,
                            updatedAt = ts,
                            hlc = futureHlc,
                        )
                    )
                    dao.upsertCollectionEntry(
                        CollectionEntryEntity(
                            collectionId = "f-pod-hist",
                            entryId = histId,
                            addedAt = ts,
                            hlc = futureHlc,
                        )
                    )
                }

                // Split-brain RSS feed whose entries.kind is accidentally "podcast"
                dao.upsertEntry(
                    EntryEntity(
                        id = "f-split",
                        kind = "podcast",
                        title = "Split Feed",
                        url = "https://split.example.com/feed",
                        createdAt = ts,
                        updatedAt = ts,
                        hlc = futureHlc,
                    )
                )
                dao.upsertCollectionSpec(
                    CollectionSpecEntity(collectionId = "f-split", slug = null, role = "feed")
                )
                dao.upsertFeedSource(
                    FeedSourceEntity(
                        collectionId = "f-split",
                        feedUrl = "https://split.example.com/feed",
                        sourceType = "rss",
                        feedTitle = "Split Feed",
                        hlc = futureHlc,
                    )
                )

                val store = CatalogStore(dao = dao, writeLock = managed.database.writeLock)
                store.ensureSystemSeeded()

                // HLC monotonicity strictly ahead of futureHlc despite missing FLAG_LOCAL_HLC
                val repairedBlogEntry = dao.getEntryById("f-blog")!!
                val repairedBlogFs = dao.getFeedSourceById("f-blog")!!
                assertEquals("rss", repairedBlogFs.sourceType)
                assertEquals("publication", repairedBlogEntry.kind)
                assertTrue(repairedBlogFs.hlc > futureHlc)
                assertTrue(repairedBlogEntry.hlc > futureHlc)

                // Reverted audio durations cleared / recomputed cleanly
                val shortContent = dao.getEntryContentById("i-short-orphan")!!
                assertEquals("article", shortContent.contentType)
                assertNull(shortContent.duration)
                assertTrue(dao.getEntryById("i-short-orphan")!!.hlc > futureHlc)

                val longContent = dao.getEntryContentById("i-long-orphan")!!
                assertEquals("article", longContent.contentType)
                assertEquals("3m", longContent.duration)

                // Linkless extensionless episode & deleted-with-history podcast preserved
                val linkless = dao.getEntryContentById("i-linkless-ep")!!
                assertEquals("audio", linkless.contentType)
                assertEquals("https://stream.example.com/episodes/42", linkless.audioUrl)
                assertEquals("18:30", linkless.duration)
                assertEquals("podcast", dao.getFeedSourceById("f-pod-hist")?.sourceType)
                assertEquals("podcast", dao.getEntryById("f-pod-hist")?.kind)

                // Split-brain RSS feed healed
                assertEquals("publication", dao.getEntryById("f-split")?.kind)

                // Incremental sync push (sinceSeq > 0) emits repaired dirty feeds & items
                val batch =
                    buildTrackedPeerSyncRequest(
                        dao = dao,
                        config =
                            PeerSyncRequestConfig(
                                peerId = "android-test",
                                deviceName = "Pixel",
                                sinceSeq = 10L,
                            ),
                    )
                val pushedById = batch.request.entries.associateBy { it.id }
                assertEquals("rss", pushedById["f-blog"]?.feedSource?.sourceType)
                assertEquals("publication", pushedById["f-blog"]?.kind)
                assertEquals("publication", pushedById["f-split"]?.kind)
                assertEquals("article", pushedById["i-short-orphan"]?.content?.contentType)
                assertNull(pushedById["i-short-orphan"]?.content?.audioUrl)
                assertNull(pushedById["i-short-orphan"]?.content?.duration)

                // Idempotent on second ensureSystemSeeded()
                val hlcBeforeSecondRun = repairedBlogEntry.hlc
                store.ensureSystemSeeded()
                assertEquals(hlcBeforeSecondRun, dao.getEntryById("f-blog")?.hlc)
            }
        }

    @Test
    fun `reconcile and offline helpers clear stale duration and keep feed kind aligned`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val hlcT1 = "001794000000000-0000-android"
                val hlcT2 = "001795000000000-0000-server"
                val hlcT3 = "001796000000000-0000-server"
                val hlcT4 = "001797000000000-0000-android"
                val ts = "2026-11-01T00:00:00Z"

                seedPodcastFeedFixture(
                    dao,
                    "f-rename",
                    "Renamed Blog",
                    "https://rename.example.com/feed",
                    ts,
                    hlcT1,
                )
                // Simulate newer entry HLC (e.g. feed title rename) than feed_source HLC
                dao.upsertEntry(dao.getEntryById("f-rename")!!.copy(hlc = hlcT4))
                seedAudioItemFixture(
                    dao = dao,
                    feedId = "f-rename",
                    id = "i-dur-bug",
                    url = "https://rename.example.com/p/post",
                    audioUrl = "https://rename.example.com/p/post",
                    ts = ts,
                    hlc = hlcT1,
                    duration = "42:15",
                )

                // Feed source HLC (t3 > t1) wins while entry HLC (t2 < t4) loses -> kind still
                // aligns!
                // Item reverts audio -> article even with leftover wire duration=600 -> duration
                // cleared!
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = "f-rename",
                                        kind = EntryKind.PUBLICATION.wireValue,
                                        title = "Old Title",
                                        url = "https://rename.example.com/feed",
                                        hlc = hlcT2,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = "https://rename.example.com/feed",
                                                sourceType = "rss",
                                                feedTitle = "Old Title",
                                                hlc = hlcT3,
                                            ),
                                    ),
                                    SyncEntryRecordDto(
                                        id = "i-dur-bug",
                                        kind = EntryKind.ARTICLE.wireValue,
                                        title = "Post",
                                        url = "https://rename.example.com/p/post",
                                        hlc = hlcT3,
                                        content =
                                            SyncEntryContentDto(
                                                contentType = "article",
                                                contentHtml = "<p>Short post</p>",
                                                audioUrl = null,
                                                duration = 600L,
                                            ),
                                    ),
                                )
                        ),
                )

                assertEquals("rss", dao.getFeedSourceById("f-rename")?.sourceType)
                assertEquals("publication", dao.getEntryById("f-rename")?.kind)
                assertEquals("Renamed Blog", dao.getEntryById("f-rename")?.title)
                assertEquals("article", dao.getEntryContentById("i-dur-bug")?.contentType)
                assertNull(dao.getEntryContentById("i-dur-bug")?.audioUrl)
                assertNull(dao.getEntryContentById("i-dur-bug")?.duration)

                // OfflineCacheManager helpers: never fall back to non-audio webpage URL,
                // and clear stale audio metadata when hydrated detail says article.
                val bogusAudioRow =
                    InboxItemRow(
                        id = "i-offline",
                        feedId = "f-rename",
                        feedTitle = "Blog",
                        title = "Post",
                        url = "https://rename.example.com/p/post",
                        author = null,
                        summary = null,
                        contentText = null,
                        contentHtml = null,
                        contentType = "audio",
                        status = "unread",
                        isFavorite = false,
                        isCachedOffline = false,
                        publishedAt = null,
                        createdAt = ts,
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "42:15",
                        audioUrl = null,
                    )
                assertNull(resolveOfflineAudioUrl(hydrated = null, latest = bogusAudioRow))
                assertEquals(
                    "https://cdn.example.com/track.mp3",
                    resolveOfflineAudioUrl(
                        hydrated = null,
                        latest = bogusAudioRow.copy(url = "https://cdn.example.com/track.mp3"),
                    ),
                )
                val hydrated =
                    mergeHydratedItemDetail(
                        local = bogusAudioRow.copy(audioUrl = "https://rename.example.com/p/post"),
                        detail =
                            RemoteItemDto(
                                id = "i-offline",
                                feedId = "f-rename",
                                feedTitle = "Blog",
                                title = "Post",
                                url = "https://rename.example.com/p/post",
                                author = null,
                                summary = null,
                                contentText = null,
                                contentHtml = "<p>Body</p>",
                                contentType = "article",
                                status = "unread",
                                isFavorite = false,
                                publishedAt = null,
                                createdAt = ts,
                                readProgress = 0.0,
                                thumbnailMediaId = null,
                                duration = null,
                                audioUrl = null,
                            ),
                    )
                assertEquals("article", hydrated.contentType)
                assertNull(hydrated.audioUrl)
                assertNull(hydrated.duration)

                // A blank-id / non-authoritative response must not clobber valid audio metadata
                val validAudio = bogusAudioRow.copy(audioUrl = "https://cdn.example.com/track.mp3")
                val nonAuthHydrated =
                    mergeHydratedItemDetail(
                        local = validAudio,
                        detail =
                            RemoteItemDto(
                                id = "",
                                feedId = null,
                                feedTitle = null,
                                title = "",
                                url = "",
                                author = null,
                                summary = null,
                                contentText = null,
                                contentHtml = null,
                                contentType = "article",
                                status = "unread",
                                isFavorite = false,
                                publishedAt = null,
                                createdAt = "",
                                readProgress = 0.0,
                                thumbnailMediaId = null,
                                duration = null,
                                audioUrl = null,
                            ),
                    )
                assertEquals("audio", nonAuthHydrated.contentType)
                assertEquals("https://cdn.example.com/track.mp3", nonAuthHydrated.audioUrl)
                assertEquals("42:15", nonAuthHydrated.duration)
            }
        }

    private fun buildTestFeedFetcher(rssUrl: String, podUrl: String): PublicWebFetcher {
        val rssXml =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<rss version="2.0"><channel><title>Newsletter</title>""" +
                "<item><title>Audio Interview</title>" +
                "<link>https://blog.example.com/p/interview</link>" +
                """<enclosure url="https://cdn.example.com/interview.mp3" """ +
                """type="audio/mpeg" length="1234"/></item>""" +
                "<item><title>Text Essay</title>" +
                "<link>https://blog.example.com/p/essay</link></item>" +
                "</channel></rss>"
        val podXml =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">""" +
                "<channel><title>Real Podcast</title>" +
                "<item><title>Text Announcement</title>" +
                "<link>https://pod.example.com/p/announcement</link>" +
                "<itunes:duration>12:34</itunes:duration></item>" +
                "</channel></rss>"
        val xmlByUrl = mapOf(rssUrl to rssXml, podUrl to podXml)
        return PublicWebFetcher(
            transport = { req ->
                HttpResponse(
                    statusCode = 200,
                    body = xmlByUrl.getValue(req.url),
                    headers = mapOf("Content-Type" to "application/rss+xml"),
                )
            }
        )
    }

    private fun buildRepairedSyncResponse(hlc: String): PeerSyncResponseDto =
        PeerSyncResponseDto(
            entries =
                listOf(
                    SyncEntryRecordDto(
                        id = "f-blog",
                        kind = EntryKind.PUBLICATION.wireValue,
                        title = "Newsletter",
                        url = "https://blog.example.com/feed",
                        hlc = hlc,
                        feedSource =
                            SyncFeedSourceDto(
                                feedUrl = "https://blog.example.com/feed",
                                sourceType = "rss",
                                feedTitle = "Newsletter",
                                hlc = hlc,
                            ),
                    ),
                    SyncEntryRecordDto(
                        id = "i-essay",
                        kind = EntryKind.ARTICLE.wireValue,
                        title = "Essay",
                        url = "https://blog.example.com/p/essay",
                        hlc = hlc,
                        content =
                            SyncEntryContentDto(
                                contentType = "article",
                                contentHtml = "<p>Essay</p>",
                                audioUrl = null,
                            ),
                    ),
                )
        )

    private fun buildStaleCorruptedSyncResponse(hlc: String): PeerSyncResponseDto =
        PeerSyncResponseDto(
            entries =
                listOf(
                    SyncEntryRecordDto(
                        id = "f-blog",
                        kind = EntryKind.PODCAST.wireValue,
                        title = "Newsletter",
                        url = "https://blog.example.com/feed",
                        hlc = hlc,
                        feedSource =
                            SyncFeedSourceDto(
                                feedUrl = "https://blog.example.com/feed",
                                sourceType = "podcast",
                                feedTitle = "Newsletter",
                                hlc = hlc,
                            ),
                    ),
                    SyncEntryRecordDto(
                        id = "i-essay",
                        kind = EntryKind.PODCAST_EPISODE.wireValue,
                        title = "Essay",
                        url = "https://blog.example.com/p/essay",
                        hlc = hlc,
                        content =
                            SyncEntryContentDto(
                                contentType = "audio",
                                contentHtml = "<p>Essay</p>",
                                audioUrl = "https://blog.example.com/p/essay",
                            ),
                    ),
                )
        )

    private suspend fun seedCorruptedPodcastFixtures(dao: RocheDao) {
        val ts = "2026-11-17T08:00:00Z"
        val hlc = "001795000000000-0000-android"
        seedPodcastFeedFixture(dao, "f-blog", "Blog", "https://blog.example.com/feed", ts, hlc)
        seedPodcastFeedFixture(dao, "f-true-pod", "Pod", "https://pod.example.com/feed", ts, hlc)
        seedPodcastFeedFixture(
            dao,
            "f-empty-pod",
            "Empty",
            "https://empty.example.com/feed",
            ts,
            hlc,
        )

        seedAudioItemFixture(
            dao = dao,
            feedId = "f-blog",
            id = "i-real-audio",
            url = "https://blog.example.com/p/real",
            audioUrl = "https://cdn.example.com/ep.mp3",
            ts = ts,
            hlc = hlc,
        )
        seedAudioItemFixture(
            dao = dao,
            feedId = "f-blog",
            id = "i-bogus-audio",
            url = "https://blog.example.com/p/bogus",
            audioUrl = "https://blog.example.com/p/bogus",
            ts = ts,
            hlc = hlc,
        )
        seedAudioItemFixture(
            dao = dao,
            feedId = "f-blog",
            id = "i-orphan-audio",
            url = "https://blog.example.com/p/orphan",
            audioUrl = null,
            ts = ts,
            hlc = hlc,
        )
        seedAudioItemFixture(
            dao = dao,
            feedId = "f-true-pod",
            id = "i-pod-ep1",
            url = "https://pod.example.com/ep1",
            audioUrl = "https://cdn.example.com/pod-ep1.mp3",
            ts = ts,
            hlc = hlc,
        )
        dao.upsertEntry(
            EntryEntity(
                id = "i-manual-ep",
                kind = "podcast_episode",
                title = "Manual Catalog Episode",
                createdAt = ts,
                updatedAt = ts,
                hlc = hlc,
            )
        )
    }

    @Suppress("LongParameterList")
    private suspend fun seedPodcastFeedFixture(
        dao: RocheDao,
        id: String,
        title: String,
        url: String,
        ts: String,
        hlc: String,
    ) {
        dao.upsertEntry(
            EntryEntity(
                id = id,
                kind = "podcast",
                title = title,
                url = url,
                createdAt = ts,
                updatedAt = ts,
                hlc = hlc,
            )
        )
        dao.upsertCollectionSpec(
            CollectionSpecEntity(
                collectionId = id,
                slug = null,
                role = "feed",
            )
        )
        dao.upsertFeedSource(
            FeedSourceEntity(
                collectionId = id,
                feedUrl = url,
                sourceType = "podcast",
                feedTitle = title,
                hlc = hlc,
            )
        )
    }

    @Suppress("LongParameterList")
    private suspend fun seedAudioItemFixture(
        dao: RocheDao,
        feedId: String,
        id: String,
        url: String,
        audioUrl: String?,
        ts: String,
        hlc: String,
        duration: String? = null,
        contentHtml: String = "<p>$id</p>",
    ) {
        dao.upsertEntry(
            EntryEntity(
                id = id,
                kind = "podcast_episode",
                title = id,
                url = url,
                createdAt = ts,
                updatedAt = ts,
                hlc = hlc,
            )
        )
        dao.upsertEntryContent(
            EntryContentEntity(
                entryId = id,
                contentType = "audio",
                contentHtml = contentHtml,
                audioUrl = audioUrl,
                duration = duration,
            )
        )
        dao.upsertCollectionEntry(
            CollectionEntryEntity(collectionId = feedId, entryId = id, addedAt = ts, hlc = hlc)
        )
    }
}
