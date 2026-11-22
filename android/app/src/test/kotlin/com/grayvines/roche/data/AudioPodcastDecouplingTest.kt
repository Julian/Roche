@file:Suppress("LongMethod", "TooManyFunctions", "LargeClass", "MagicNumber")

package com.grayvines.roche.data

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncEntryContentDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.feeds.buildLocalOpmlXml
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.media.mergeHydratedItemDetail
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.playback.findNextQueuedAudioEntry
import com.grayvines.roche.playback.hasNextQueuedAudio
import com.grayvines.roche.ui.MainAction
import com.grayvines.roche.ui.MainUiState
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.resolveInboxStripFilters
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPodcastDecouplingTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `explicit podcast subscribe and live RSS-Podcast toggle reclassify audio items cleanly`() =
        runTest {
            val rssXml = buildAudioAndTextFeedXml("Newsletter Show", "https://show.example.com")
            val rssTransport: (com.grayvines.roche.data.remote.HttpRequest) -> HttpResponse = {
                HttpResponse(
                    statusCode = 200,
                    body = rssXml,
                    headers = mapOf("Content-Type" to "application/rss+xml"),
                )
            }
            val fetcher = PublicWebFetcher(transport = rssTransport)
            val env = TestRocheEnv(tempDir, "decouple_toggle.preferences_pb")
            try {
                val dao = env.database.dao()
                val repo =
                    mockRepository(
                        database = env.database,
                        settings = env.settings,
                        transport = rssTransport,
                    )

                // 1. Default RSS subscription keeps narrated post as article with audio & duration
                val rssSub =
                    repo.discoverAndSubscribeFeed(
                        inputUrl = "https://show.example.com/feed.xml",
                        feedType = FeedType.RSS,
                    )
                assertTrue(rssSub.isSuccess)
                val feed = dao.getAllFeeds().single()
                assertEquals("rss", feed.feedType)
                assertEquals("publication", dao.getEntryById(feed.id)?.kind)

                val initialByTitle = dao.getItemsForFeed(feed.id, feed.url).associateBy { it.title }
                val narrated = initialByTitle.getValue("Narrated Essay")
                val textOnly = initialByTitle.getValue("Text Only Note")
                assertEquals("article", narrated.contentType)
                assertEquals("article", narrated.entryKind)
                assertEquals("https://cdn.example.com/narrated.mp3", narrated.audioUrl)
                assertEquals("25:00", narrated.duration)
                assertEquals("article", textOnly.contentType)
                assertEquals("article", textOnly.entryKind)
                assertNull(textOnly.audioUrl)

                // Re-polling an RSS feed upgrades synthetic "5m" duration to audio duration and
                // keeps article
                dao.upsertEntryContent(dao.getEntryContentById(narrated.id)!!.copy(duration = "5m"))
                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = fetcher,
                            feed = feed,
                            localPeerId = "android-test",
                            nowInstant = Instant.parse("2026-11-22T13:00:00Z"),
                        )
                        .isSuccess
                )
                assertEquals("article", dao.getEntryContentById(narrated.id)?.contentType)
                assertEquals("25:00", dao.getEntryContentById(narrated.id)?.duration)

                // Seed a Video item with an audio URL on the RSS feed; RSS -> Podcast must NOT
                // corrupt it
                dao.upsertItem(
                    InboxItemRow(
                        id = "i-video-with-audio",
                        feedId = feed.id,
                        feedTitle = feed.title,
                        title = "Video Talk",
                        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        author = null,
                        summary = null,
                        contentText = null,
                        contentHtml = "<p>Video</p>",
                        contentType = "video",
                        status = "unread",
                        isFavorite = false,
                        isCachedOffline = false,
                        publishedAt = null,
                        createdAt = "2026-11-22T12:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "10:00",
                        audioUrl = "https://cdn.example.com/video-track.mp3",
                    )
                )

                // 2. Toggle RSS -> Podcast promotes audio article only; text-only & video stay
                // native
                val toPodcast = repo.setFeedType(feed.id, FeedType.PODCAST)
                assertTrue(toPodcast.isSuccess)
                assertEquals("podcast", dao.getFeedSourceById(feed.id)?.sourceType)
                assertEquals("podcast", dao.getEntryById(feed.id)?.kind)
                assertEquals("podcast_episode", dao.getEntryById(narrated.id)?.kind)
                assertEquals("audio", dao.getEntryContentById(narrated.id)?.contentType)
                assertEquals(
                    "https://cdn.example.com/narrated.mp3",
                    dao.getEntryContentById(narrated.id)?.audioUrl,
                )
                assertEquals("25:00", dao.getEntryContentById(narrated.id)?.duration)
                assertEquals("article", dao.getEntryById(textOnly.id)?.kind)
                assertEquals("article", dao.getEntryContentById(textOnly.id)?.contentType)
                assertEquals("video", dao.getEntryById("i-video-with-audio")?.kind)
                assertEquals("video", dao.getEntryContentById("i-video-with-audio")?.contentType)

                // 2b. Re-subscribing an ALREADY ACTIVE Podcast feed with default FeedType.RSS must
                // NOT demote it
                val resubDefault =
                    repo.discoverAndSubscribeFeed(
                        inputUrl = "https://show.example.com/feed.xml",
                        feedType = FeedType.RSS,
                    )
                assertTrue(resubDefault.isSuccess)
                assertEquals("podcast", dao.getFeedSourceById(feed.id)?.sourceType)
                assertEquals("podcast", dao.getEntryById(feed.id)?.kind)
                assertEquals("podcast_episode", dao.getEntryById(narrated.id)?.kind)

                // 2c. Re-polling an explicit Podcast feed where a text post gains audio promotes
                // kind -> podcast_episode
                val updatedPodXml =
                    """<?xml version="1.0" encoding="UTF-8"?>""" +
                        """<rss version="2.0"><channel><title>Newsletter Show</title>""" +
                        "<item><title>Text Only Note</title><link>https://show.example.com/p/text</link>" +
                        """<enclosure url="https://cdn.example.com/late-audio.mp3" type="audio/mpeg" length="999"/>""" +
                        "</item></channel></rss>"
                val lateAudioFetcher =
                    PublicWebFetcher(
                        transport = {
                            HttpResponse(
                                statusCode = 200,
                                body = updatedPodXml,
                                headers = mapOf("Content-Type" to "application/rss+xml"),
                            )
                        }
                    )
                val activePodFeed = dao.getFeedById(feed.id)!!
                assertTrue(
                    LocalFeedPoller.pollFeed(
                            dao = dao,
                            publicWebFetcher = lateAudioFetcher,
                            feed = activePodFeed,
                            localPeerId = "android-test",
                            nowInstant = Instant.parse("2026-11-22T14:00:00Z"),
                        )
                        .isSuccess
                )
                assertEquals("audio", dao.getEntryContentById(textOnly.id)?.contentType)
                assertEquals("podcast_episode", dao.getEntryById(textOnly.id)?.kind)

                // Seed a second explicit podcast feed sharing a different audio episode
                val otherPod =
                    FeedRow(
                        id = "f-other-pod",
                        title = "Other Podcast",
                        url = "https://other-pod.example.com/feed.xml",
                        feedType = "podcast",
                    )
                dao.upsertFeed(otherPod)
                val sharedId = "i-shared-ep"
                dao.upsertItem(
                    InboxItemRow(
                        id = sharedId,
                        feedId = otherPod.id,
                        feedTitle = otherPod.title,
                        title = "Cross-Posted Episode",
                        url = "https://other-pod.example.com/ep-shared",
                        author = null,
                        summary = null,
                        contentText = null,
                        contentHtml = "<p>Shared</p>",
                        contentType = "audio",
                        status = "unread",
                        isFavorite = false,
                        isCachedOffline = false,
                        publishedAt = null,
                        createdAt = "2026-11-22T12:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "30:00",
                        audioUrl = "https://cdn.example.com/shared.mp3",
                    )
                )
                dao.upsertCollectionEntry(
                    CollectionEntryEntity(
                        collectionId = feed.id,
                        entryId = sharedId,
                        addedAt = "2026-11-22T12:00:00Z",
                        hlc = "001795000000000-0000-test",
                    )
                )

                // 3. Toggle Podcast -> RSS demotes exclusive audio item, skips shared podcast ep
                val backToRss = repo.setFeedType(feed.id, FeedType.RSS)
                assertTrue(backToRss.isSuccess)
                assertEquals("rss", dao.getFeedSourceById(feed.id)?.sourceType)
                assertEquals("publication", dao.getEntryById(feed.id)?.kind)
                assertEquals("article", dao.getEntryById(narrated.id)?.kind)
                assertEquals("article", dao.getEntryContentById(narrated.id)?.contentType)
                assertEquals(
                    "https://cdn.example.com/narrated.mp3",
                    dao.getEntryContentById(narrated.id)?.audioUrl,
                )
                assertEquals("25:00", dao.getEntryContentById(narrated.id)?.duration)
                // Shared item stays podcast_episode / audio because f-other-pod is still podcast
                assertEquals("podcast_episode", dao.getEntryById(sharedId)?.kind)
                assertEquals("audio", dao.getEntryContentById(sharedId)?.contentType)

                // 4. Direct explicit Podcast subscription via discoverAndSubscribeFeed
                val podXml = buildAudioAndTextFeedXml("Brand New Pod", "https://newpod.example.com")
                val podEnv = TestRocheEnv(tempDir, "decouple_podsub.preferences_pb")
                try {
                    val podRepo =
                        mockRepository(
                            database = podEnv.database,
                            settings = podEnv.settings,
                            transport = {
                                HttpResponse(
                                    statusCode = 200,
                                    body = podXml,
                                    headers = mapOf("Content-Type" to "application/rss+xml"),
                                )
                            },
                        )
                    val podSub =
                        podRepo.discoverAndSubscribeFeed(
                            inputUrl = "https://newpod.example.com/feed.xml",
                            feedType = FeedType.PODCAST,
                        )
                    assertTrue(podSub.isSuccess)
                    val podDao = podEnv.database.dao()
                    val podFeed = podDao.getAllFeeds().single()
                    assertEquals("podcast", podFeed.feedType)
                    assertEquals("podcast", podDao.getEntryById(podFeed.id)?.kind)
                    val podItems =
                        podDao.getItemsForFeed(podFeed.id, podFeed.url).associateBy { it.title }
                    assertEquals("audio", podItems.getValue("Narrated Essay").contentType)
                    assertEquals("podcast_episode", podItems.getValue("Narrated Essay").entryKind)
                    assertEquals("article", podItems.getValue("Text Only Note").contentType)
                    assertEquals("article", podItems.getValue("Text Only Note").entryKind)
                } finally {
                    podEnv.close()
                }
            } finally {
                env.close()
            }
        }

    @Test
    fun `CatalogSeeder decouple repair reclassifies legacy RSS audio while preserving media and podcasts`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val futureHlc =
                    HlcTimestamp.create(System.currentTimeMillis() + 600_000L, 2, "server").encode()
                val ts = "2026-11-20T00:00:00Z"

                seedFeedSource(
                    dao,
                    "f-rss",
                    "rss",
                    "publication",
                    "https://rss.example.com/feed",
                    ts,
                    futureHlc,
                )
                seedFeedSource(
                    dao,
                    "f-pod",
                    "podcast",
                    "podcast",
                    "https://pod.example.com/feed",
                    ts,
                    futureHlc,
                )

                // Legacy RSS item that had audio enclosure -> previously stored as
                // podcast_episode/audio
                seedAudioFeedItem(
                    dao = dao,
                    feedId = "f-rss",
                    id = "i-rss-mp3",
                    url = "https://rss.example.com/p/substack-audio",
                    audioUrl = "https://cdn.example.com/substack-audio.mp3",
                    duration = "18:30",
                    ts = ts,
                    hlc = futureHlc,
                )
                // Explicit podcast episode must stay podcast_episode/audio
                seedAudioFeedItem(
                    dao = dao,
                    feedId = "f-pod",
                    id = "i-pod-ep",
                    url = "https://pod.example.com/ep-1",
                    audioUrl = "https://cdn.example.com/pod-ep1.mp3",
                    duration = "45:00",
                    ts = ts,
                    hlc = futureHlc,
                )
                // Standalone non-feed audio item (e.g. saved direct audio) must NOT be touched
                dao.upsertEntry(
                    EntryEntity(
                        id = "i-standalone",
                        kind = "podcast_episode",
                        title = "Standalone Audio",
                        url = "https://standalone.example.com/talk",
                        createdAt = ts,
                        updatedAt = ts,
                        hlc = futureHlc,
                    )
                )
                dao.upsertEntryContent(
                    EntryContentEntity(
                        entryId = "i-standalone",
                        contentType = "audio",
                        audioUrl = "https://cdn.example.com/talk.mp3",
                        duration = "12:00",
                    )
                )

                val store = CatalogStore(dao = dao, writeLock = managed.database.writeLock)
                store.ensureSystemSeeded()

                // RSS audio item decoupled to article/article, audioUrl + duration preserved, HLC
                // bumped
                val rssEntry = dao.getEntryById("i-rss-mp3")!!
                val rssContent = dao.getEntryContentById("i-rss-mp3")!!
                assertEquals("article", rssEntry.kind)
                assertTrue(rssEntry.hlc > futureHlc)
                assertTrue(rssEntry.isLocalDirty)
                assertEquals("article", rssContent.contentType)
                assertEquals("https://cdn.example.com/substack-audio.mp3", rssContent.audioUrl)
                assertEquals("18:30", rssContent.duration)

                // Podcast episode & standalone audio untouched
                assertEquals("podcast_episode", dao.getEntryById("i-pod-ep")?.kind)
                assertEquals("audio", dao.getEntryContentById("i-pod-ep")?.contentType)
                assertEquals("podcast_episode", dao.getEntryById("i-standalone")?.kind)
                assertEquals("audio", dao.getEntryContentById("i-standalone")?.contentType)

                // Incremental sync push includes decoupled RSS item with audio + duration intact
                val batch =
                    buildTrackedPeerSyncRequest(
                        dao = dao,
                        config =
                            PeerSyncRequestConfig(
                                peerId = "android-test",
                                deviceName = "Pixel",
                                sinceSeq = 5L,
                            ),
                    )
                val pushed = batch.request.entries.associateBy { it.id }["i-rss-mp3"]!!
                assertEquals("article", pushed.kind)
                assertEquals("article", pushed.content?.contentType)
                assertEquals("https://cdn.example.com/substack-audio.mp3", pushed.content?.audioUrl)
                assertEquals(1110L, pushed.content?.duration)

                // Idempotent on second startup
                val hlcAfterFirst = rssEntry.hlc
                store.ensureSystemSeeded()
                assertEquals(hlcAfterFirst, dao.getEntryById("i-rss-mp3")?.hlc)
            }
        }

    @Test
    fun `PeerSyncIn and OfflineCacheManager preserve narrated article audio and clear on removal`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val hlc1 = "001795000000000-0000-server"
                val hlc2 = "001796000000000-0000-server"

                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = "i-narrated",
                                        kind = EntryKind.ARTICLE.wireValue,
                                        title = "Narrated Substack Post",
                                        url = "https://newsletter.example.com/p/post-1",
                                        hlc = hlc1,
                                        content =
                                            SyncEntryContentDto(
                                                contentType = "article",
                                                contentHtml = "<p>Read or listen</p>",
                                                audioUrl = "https://cdn.example.com/post-1.mp3",
                                                duration = 900L,
                                            ),
                                    )
                                )
                        ),
                )
                assertEquals("article", dao.getEntryById("i-narrated")?.kind)
                val syncedContent = dao.getEntryContentById("i-narrated")!!
                assertEquals("article", syncedContent.contentType)
                assertEquals("https://cdn.example.com/post-1.mp3", syncedContent.audioUrl)
                assertEquals("15:00", syncedContent.duration)

                // Offline detail merge preserves narrated article audioUrl + duration
                val localRow = dao.getItemById("i-narrated")!!
                val hydrated =
                    mergeHydratedItemDetail(
                        local = localRow,
                        detail =
                            RemoteItemDto(
                                id = "i-narrated",
                                feedId = null,
                                feedTitle = "Newsletter",
                                title = "Narrated Substack Post",
                                url = "https://newsletter.example.com/p/post-1",
                                author = null,
                                summary = null,
                                contentText = "Read or listen",
                                contentHtml = "<p>Read or listen</p>",
                                contentType = "article",
                                status = "unread",
                                isFavorite = false,
                                publishedAt = null,
                                createdAt = "2026-11-22T00:00:00Z",
                                readProgress = 0.0,
                                thumbnailMediaId = null,
                                duration = "15:00",
                                audioUrl = "https://cdn.example.com/post-1.mp3",
                            ),
                    )
                assertEquals("article", hydrated.contentType)
                assertEquals("https://cdn.example.com/post-1.mp3", hydrated.audioUrl)
                assertEquals("15:00", hydrated.duration)

                // Stale-HLC sync delta with older duration must NOT overwrite winning local
                // duration
                val staleHlc = "001794000000000-0000-server"
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = "i-narrated",
                                        kind = EntryKind.ARTICLE.wireValue,
                                        title = "Narrated Substack Post",
                                        url = "https://newsletter.example.com/p/post-1",
                                        hlc = staleHlc,
                                        content =
                                            SyncEntryContentDto(
                                                contentType = "article",
                                                contentHtml = "<p>Read or listen</p>",
                                                audioUrl = "https://cdn.example.com/post-1.mp3",
                                                duration = 300L,
                                            ),
                                    )
                                )
                        ),
                )
                assertEquals("15:00", dao.getEntryContentById("i-narrated")?.duration)

                // Newer HLC removing audio enclosure clears audioUrl and stale audio duration
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = "i-narrated",
                                        kind = EntryKind.ARTICLE.wireValue,
                                        title = "Narrated Substack Post",
                                        url = "https://newsletter.example.com/p/post-1",
                                        hlc = hlc2,
                                        content =
                                            SyncEntryContentDto(
                                                contentType = "article",
                                                contentHtml = "<p>Read only now</p>",
                                                audioUrl = null,
                                                duration = null,
                                            ),
                                    )
                                )
                        ),
                )
                assertNull(dao.getEntryContentById("i-narrated")?.audioUrl)
                assertNull(dao.getEntryContentById("i-narrated")?.duration)
            }
        }

    @Test
    fun `split Podcasts and Audio filters queue helpers and OPML export reflect decoupled semantics`() {
        val plainArticle =
            sampleInboxEntry(
                id = "e-art",
                contentType = ContentType.ARTICLE,
                entryKind = EntryKind.ARTICLE,
                audioUrl = null,
            )
        val narratedArticle =
            sampleInboxEntry(
                id = "e-narrated",
                contentType = ContentType.ARTICLE,
                entryKind = EntryKind.ARTICLE,
                audioUrl = "https://cdn.example.com/narrated.mp3",
                duration = "25:00",
            )
        val podcastEp =
            sampleInboxEntry(
                id = "e-pod",
                contentType = ContentType.AUDIO,
                entryKind = EntryKind.PODCAST_EPISODE,
                audioUrl = "https://cdn.example.com/ep.mp3",
                duration = "45:00",
            )
        val directMp3 =
            sampleInboxEntry(
                id = "e-mp3",
                contentType = ContentType.AUDIO,
                entryKind = EntryKind.ARTICLE,
                audioUrl = "https://cdn.example.com/track.mp3",
                duration = "05:00",
            )
        val all = listOf(plainArticle, narratedArticle, podcastEp, directMp3)

        assertTrue(narratedArticle.isPlayableAudio)
        assertFalse(narratedArticle.isPodcastEpisode)
        assertEquals("25m", narratedArticle.readEstimateLabel)
        assertTrue(podcastEp.isPodcastEpisode)
        assertFalse(directMp3.isPodcastEpisode)
        assertTrue(directMp3.isPlayableAudio)

        assertEquals(
            listOf("e-art", "e-narrated"),
            RocheRepository.filterEntries(all, InboxFilter.ARTICLES).map(InboxEntry::id),
        )
        assertEquals(
            listOf("e-pod"),
            RocheRepository.filterEntries(all, InboxFilter.PODCASTS).map(InboxEntry::id),
        )
        assertEquals(
            listOf("e-narrated", "e-pod", "e-mp3"),
            RocheRepository.filterEntries(all, InboxFilter.AUDIO).map(InboxEntry::id),
        )

        assertTrue(matchesSearchScopeFilter(podcastEp, SearchScopeFilter.PODCASTS))
        assertFalse(matchesSearchScopeFilter(narratedArticle, SearchScopeFilter.PODCASTS))
        assertTrue(matchesSearchScopeFilter(narratedArticle, SearchScopeFilter.AUDIO))
        assertTrue(matchesSearchScopeFilter(directMp3, SearchScopeFilter.AUDIO))

        // Mixed feed with plain + narrated articles keeps format strip visible
        val mixedRssFeed =
            MainUiState(
                selectedFeedId = "f-1",
                activeFilter = InboxFilter.ALL,
                allEntries = listOf(plainArticle, narratedArticle),
            )
        assertEquals(InboxFilter.inboxStripFilters, resolveInboxStripFilters(mixedRssFeed))

        // Queue helpers treat narrated articles as playable audio
        val queueIds = listOf("e-art", "e-narrated", "e-pod")
        assertTrue(hasNextQueuedAudio(all, queueIds, excludeEntryId = "e-pod"))
        assertEquals(
            "e-narrated",
            findNextQueuedAudioEntry(all, queueIds, excludeEntryId = "e-pod")?.id,
        )

        // OPML export tags only explicit podcast feeds with rocheFeedType="podcast"
        val opml =
            buildLocalOpmlXml(
                listOf(
                    FeedRow(
                        id = "f-1",
                        title = "Blog",
                        url = "https://blog.example.com/rss",
                        feedType = "rss",
                    ),
                    FeedRow(
                        id = "f-2",
                        title = "Show",
                        url = "https://pod.example.com/rss",
                        feedType = "podcast",
                    ),
                )
            )
        assertTrue(opml.contains("""<outline type="rss" rocheFeedType="podcast" text="Show""""))
        assertTrue(opml.contains("""<outline type="rss" text="Blog""""))
    }

    @Test
    fun `narrated article progress source guards auto-log and active audio scroll updates`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                val env = TestRocheEnv(tempDir, "decouple_progress.preferences_pb")
                try {
                    val dao = env.database.dao()
                    val repo = env.repository
                    dao.upsertFeed(
                        FeedRow(
                            id = "f-news",
                            title = "Newsletter",
                            url = "https://news.example.com/rss",
                            feedType = "rss",
                        )
                    )
                    dao.upsertItem(
                        InboxItemRow(
                            id = "i-narrated",
                            feedId = "f-news",
                            feedTitle = "Newsletter",
                            title = "Narrated Post",
                            url = "https://news.example.com/p/1",
                            author = null,
                            summary = "Summary",
                            contentText = "Body text",
                            contentHtml = "<p>Body text</p>",
                            contentType = "article",
                            status = "unread",
                            isFavorite = false,
                            isCachedOffline = false,
                            publishedAt = "2026-11-22T10:00:00Z",
                            createdAt = "2026-11-22T10:00:00Z",
                            readProgress = 0.0,
                            thumbnailMediaId = null,
                            duration = "20:00",
                            audioUrl = "https://cdn.example.com/p1.mp3",
                        )
                    )

                    // Scroll progress = 1.0 on UNREAD narrated article must NOT auto-log
                    repo.updateReadProgress("i-narrated", 1.0, fromAudioPlayback = false)
                    assertTrue(dao.getAllConsumptionLogs().none { it.entryId == "i-narrated" })

                    // Scroll progress = 1.0 (fromAudioPlayback = false) on UNREAD podcast episode
                    // must NOT auto-log
                    dao.upsertFeed(
                        FeedRow(
                            id = "f-pod-prog",
                            title = "Real Pod",
                            url = "https://realpod.example.com/rss",
                            feedType = "podcast",
                        )
                    )
                    dao.upsertItem(
                        InboxItemRow(
                            id = "i-pod-prog",
                            feedId = "f-pod-prog",
                            feedTitle = "Real Pod",
                            title = "Episode 1",
                            url = "https://realpod.example.com/ep1",
                            author = null,
                            summary = "Show notes",
                            contentText = "Show notes",
                            contentHtml = "<p>Show notes</p>",
                            contentType = "audio",
                            status = "unread",
                            isFavorite = false,
                            isCachedOffline = false,
                            publishedAt = "2026-11-22T10:00:00Z",
                            createdAt = "2026-11-22T10:00:00Z",
                            readProgress = 0.0,
                            thumbnailMediaId = null,
                            duration = "30:00",
                            audioUrl = "https://cdn.example.com/ep1.mp3",
                        )
                    )
                    repo.updateReadProgress("i-pod-prog", 1.0, fromAudioPlayback = false)
                    assertTrue(dao.getAllConsumptionLogs().none { it.entryId == "i-pod-prog" })
                    repo.updateReadProgress("i-pod-prog", 0.99, fromAudioPlayback = true)
                    assertTrue(dao.getAllConsumptionLogs().any { it.entryId == "i-pod-prog" })

                    // Audio playback progress >= 0.98 DOES auto-log under EntryKind.ARTICLE
                    repo.updateReadProgress("i-narrated", 0.99, fromAudioPlayback = true)
                    assertTrue(dao.getAllConsumptionLogs().any { it.entryId == "i-narrated" })
                    assertEquals("article", dao.getEntryById("i-narrated")?.kind)

                    // ViewModel: OpenReader opens narrated article in Reader (not fullscreen
                    // player),
                    // and active audio playback blocks Reader scroll updates from clobbering
                    // progress
                    dao.upsertItem(
                        dao.getItemById("i-narrated")!!.copy(
                            id = "i-narrated-2",
                            readProgress = 0.25,
                        )
                    )
                    val vm = MainViewModel(repository = repo, settingsRepository = env.settings)
                    try {
                        val collectJob = collectInBackground(vm.uiState)
                        vm.uiState.first { it.allEntries.any { e -> e.id == "i-narrated-2" } }

                        vm.dispatch(MainAction.OpenReader("i-narrated-2"))
                        val opened = vm.uiState.first { it.selectedReaderEntryId == "i-narrated-2" }
                        assertFalse(opened.isPlayerFullscreenOpen)

                        vm.dispatch(
                            MainAction.PlayAudioEntry("i-narrated-2", openFullscreen = false)
                        )
                        vm.playerState.first {
                            it.currentEntryId == "i-narrated-2" && !it.isBuffering
                        }
                        vm.playerController.cancelAutoTickerForTest()

                        val progressBeforeScroll = dao.getItemById("i-narrated-2")!!.readProgress
                        vm.dispatch(MainAction.UpdateReadProgress("i-narrated-2", 0.85))
                        assertEquals(
                            progressBeforeScroll,
                            dao.getItemById("i-narrated-2")!!.readProgress,
                            1e-6,
                        )
                        collectJob.cancel()
                    } finally {
                        vm.playerController.releaseController()
                        runBlocking { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                    }
                } finally {
                    env.close()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun buildAudioAndTextFeedXml(title: String, baseUrl: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">""" +
            "<channel><title>$title</title><link>$baseUrl</link>" +
            "<item><title>Narrated Essay</title><link>$baseUrl/p/narrated</link>" +
            """<enclosure url="https://cdn.example.com/narrated.mp3" type="audio/mpeg" length="999"/>""" +
            "<itunes:duration>25:00</itunes:duration></item>" +
            "<item><title>Text Only Note</title><link>$baseUrl/p/text</link>" +
            "<description>Plain text post</description></item>" +
            "</channel></rss>"

    private suspend fun seedFeedSource(
        dao: RocheDao,
        id: String,
        sourceType: String,
        entryKind: String,
        url: String,
        ts: String,
        hlc: String,
    ) {
        dao.upsertEntry(
            EntryEntity(
                id = id,
                kind = entryKind,
                title = id,
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
                mode = "feed",
                role = "feed",
                showInLists = false,
            )
        )
        dao.upsertFeedSource(
            FeedSourceEntity(
                collectionId = id,
                feedUrl = url,
                sourceType = sourceType,
                feedTitle = id,
                hlc = hlc,
            )
        )
    }

    private suspend fun seedAudioFeedItem(
        dao: RocheDao,
        feedId: String,
        id: String,
        url: String,
        audioUrl: String,
        duration: String,
        ts: String,
        hlc: String,
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
                contentHtml = "<p>$id</p>",
                audioUrl = audioUrl,
                duration = duration,
            )
        )
        dao.upsertCollectionEntry(
            CollectionEntryEntity(collectionId = feedId, entryId = id, addedAt = ts, hlc = hlc)
        )
    }

    private fun sampleInboxEntry(
        id: String,
        contentType: ContentType,
        entryKind: EntryKind,
        audioUrl: String?,
        duration: String? = null,
    ): InboxEntry =
        InboxEntry(
            id = id,
            feedId = "f-1",
            feedTitle = "Feed",
            title = "Entry $id",
            url = "https://example.com/$id",
            author = null,
            summary = "Summary",
            contentText = "Body",
            contentHtml = "<p>Body</p>",
            contentType = contentType,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "1h",
            createdAt = Instant.parse("2026-11-22T10:00:00Z").toString(),
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = duration,
            tags = emptyList(),
            audioUrl = audioUrl,
            entryKind = entryKind,
        )
}
