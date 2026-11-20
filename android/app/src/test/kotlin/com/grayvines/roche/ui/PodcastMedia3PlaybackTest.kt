package com.grayvines.roche.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.google.common.util.concurrent.ListenableFuture
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.playback.AndroidPodcastMediaSessionBridge
import com.grayvines.roche.playback.AudioPlayerBackend
import com.grayvines.roche.playback.ExoPlayerAudioBackend
import com.grayvines.roche.playback.MediaSessionMetadataSnapshot
import com.grayvines.roche.playback.PodcastPlayerController
import com.grayvines.roche.playback.SafeRedirectHttpDataSource
import com.grayvines.roche.playback.createManagedPlayerController
import com.grayvines.roche.playback.isSafeAudioPlaybackSource
import com.grayvines.roche.playback.resolveSafeHttpPlaybackUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class PodcastMedia3PlaybackTest {
    @TempDir lateinit var tempDir: File

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>()
                .setDriver(BundledSQLiteDriver())
                .allowMainThreadQueries()
                .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, "settings.preferences_pb") },
            )
        settings = SettingsRepository(dataStore)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { dataStoreScope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `isSafeAudioPlaybackSource allows https and canonical cache files while rejecting arbitrary URIs`() {
        val allowedCache = File(tempDir, "offline_media").apply { mkdirs() }
        val secretDir = File(tempDir, "shared_prefs").apply { mkdirs() }
        val validAudioFile = File(allowedCache, "ep-1.mp3").apply { writeText("audio") }
        val secretFile = File(secretDir, "auth.xml").apply { writeText("secret") }

        assertTrue(
            isSafeAudioPlaybackSource("https://cdn.example.com/ep1.mp3", listOf(allowedCache))
        )
        assertTrue(isSafeAudioPlaybackSource(validAudioFile.absolutePath, listOf(allowedCache)))
        assertTrue(
            isSafeAudioPlaybackSource(validAudioFile.toURI().toString(), listOf(allowedCache))
        )

        assertFalse(isSafeAudioPlaybackSource("", listOf(allowedCache)))
        assertFalse(isSafeAudioPlaybackSource("javascript:alert(1)", listOf(allowedCache)))
        assertFalse(
            isSafeAudioPlaybackSource(
                "content://com.grayvines.roche.provider/db",
                listOf(allowedCache),
            )
        )
        assertFalse(isSafeAudioPlaybackSource(secretFile.absolutePath, listOf(allowedCache)))
        assertFalse(
            isSafeAudioPlaybackSource(
                File(allowedCache, "../shared_prefs/auth.xml").path,
                listOf(allowedCache),
            )
        )
        assertFalse(isSafeAudioPlaybackSource("http://127.0.0.1/ep.mp3", listOf(allowedCache)))
        assertFalse(isSafeAudioPlaybackSource("http://localhost/ep.mp3", listOf(allowedCache)))
        assertFalse(isSafeAudioPlaybackSource("http://10.0.0.5/ep.mp3", listOf(allowedCache)))
        assertFalse(
            isSafeAudioPlaybackSource(
                "http://169.254.169.254/latest/meta-data",
                listOf(allowedCache),
            )
        )

        val publicAddr =
            java.net.InetAddress.getByAddress("cdn.example.com", byteArrayOf(93, -72, -40, 34))
        val loopbackAddr =
            java.net.InetAddress.getByAddress("rebind.example.com", byteArrayOf(127, 0, 0, 1))
        assertEquals(
            "https://cdn.example.com/ep1.mp3",
            resolveSafeHttpPlaybackUrl(
                rawUrl = "http://cdn.example.com/ep1.mp3",
                resolveAddresses = { arrayOf(publicAddr) },
                isCleartextPermitted = { false },
            ),
        )
        val rebindErr = runCatching {
            resolveSafeHttpPlaybackUrl(
                rawUrl = "https://rebind.example.com/ep1.mp3",
                resolveAddresses = { arrayOf(loopbackAddr) },
                isCleartextPermitted = { false },
            )
        }
            .exceptionOrNull()
        assertTrue(rebindErr is java.io.IOException)
    }

    @Test
    fun `SafeRedirectHttpDataSource upgrades http redirect hops blocks DNS rebinding and blocks private IP SSRF`() {
        verifySafeRedirectUpgradeAndRebind()
        verifyBlockedPrivateRedirectHop()
    }

    @Test
    internal fun `SafeRedirectHttpDataSource strips cross-origin auth headers rewrites 303 to GET and sanitizes spaces`() {
        verifyCrossOriginRedirectStripsSensitiveHeaders()
        verifySeeOtherRewriteAndSpaceSanitization()
    }

    @Test
    fun `skipSilence propagates to backend and periodic ticks debounce HTTP PATCH`() = runTest {
        val syncRequests = CopyOnWriteArrayList<HttpRequest>()
        var fakeClockMs = 100_000L
        withPlayerHarness(syncRequests, clockMs = { fakeClockMs }) {
            vm,
            controller,
            backend,
            drainJobs ->
            assertTrue(backend.skipSilenceEnabled)
            vm.dispatch(MainAction.SetSkipSilence(false))
            vm.playerState.first { !it.skipSilenceEnabled }
            assertFalse(backend.skipSilenceEnabled)
            vm.dispatch(MainAction.SetSkipSilence(true))
            vm.playerState.first { it.skipSilenceEnabled }
            assertTrue(backend.skipSilenceEnabled)

            val initialReadAt =
                vm.uiState
                    .first { !it.allEntries.first().readAt.isNullOrBlank() }
                    .allEntries
                    .first()
                    .readAt
            drainJobs()
            syncRequests.clear()

            backend.simulatedPositionMs = 30_000L
            controller.advanceSingleTick(tickCount = 5)
            drainJobs()
            assertEquals(0, syncRequests.size)
            assertEquals(initialReadAt, database.dao().getItemById("ep-1")?.readAt)

            backend.simulatedPositionMs = 90_000L
            controller.advanceSingleTick(tickCount = 15)
            drainJobs()
            assertEquals(1, syncRequests.size)
            assertTrue(
                syncRequests.single().body.orEmpty().contains(""""read_at":"$initialReadAt"""")
            )
            assertEquals(initialReadAt, database.dao().getItemById("ep-1")?.readAt)

            fakeClockMs += 15_000L
            backend.simulatedPositionMs = 180_000L
            controller.advanceSingleTick(tickCount = 30)
            drainJobs()
            assertEquals(1, syncRequests.size)
            assertEquals(0.3, database.dao().getItemById("ep-1")?.readProgress ?: 0.0, 1e-2)
        }
    }

    @Test
    fun `persisted skipSilenceEnabled false syncs immediately into initial uiState and backend`() =
        runTest {
            settings.update {
                it.copy(
                    skipSilenceEnabled = false,
                    smartRewindEnabled = false,
                )
            }
            settings.prewarm()

            val apiClient = RocheApiClient {
                HttpResponse(statusCode = 200, body = PEER_SYNC_OK_BODY)
            }
            val backend = InspectableAudioBackend().apply { skipSilenceEnabled = true }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = apiClient,
                    settings = settings,
                    cacheDir = File(tempDir, "cache_skip_init"),
                )
            val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller =
                createManagedPlayerController(
                    scope = playerScope,
                    repository = repo,
                    settingsRepository = settings,
                    playerBackend = backend,
                )
            val vm = MainViewModel(repo, settings, sharedPlayerController = controller)
            val collectJob = collectInBackground(vm.uiState)
            try {
                assertFalse(vm.playerState.value.skipSilenceEnabled)
                vm.uiState.first { !it.isInitialLoading }
                vm.playerState.first { !it.skipSilenceEnabled }
                assertFalse(backend.skipSilenceEnabled)

                settings.update { it.copy(skipSilenceEnabled = true) }
                vm.playerState.first { it.skipSilenceEnabled }
                assertTrue(backend.skipSilenceEnabled)
            } finally {
                controller.releaseController()
                playerScope.coroutineContext[Job]?.cancelAndJoin()
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                collectJob.cancelAndJoin()
            }
        }

    @Test
    fun `crossing 98 percent auto-logs once and external pause coalesces read_at`() = runTest {
        val syncRequests = CopyOnWriteArrayList<HttpRequest>()
        var fakeClockMs = 100_000L
        withPlayerHarness(syncRequests, clockMs = { fakeClockMs }) {
            _,
            controller,
            backend,
            drainJobs ->
            fakeClockMs += 45_000L
            backend.simulatedPositionMs = 590_000L
            controller.advanceSingleTick(tickCount = 15)
            drainJobs()

            val logsAfter98 = database.dao().getAllConsumptionLogs().filter { it.entryId == "ep-1" }
            assertEquals(1, logsAfter98.size)
            val entryAfterFirst = database.dao().getEntryById("ep-1")
            assertNotNull(entryAfterFirst)
            val sentinelUpdatedAt = "2026-01-01T00:00:00Z"
            database.dao().upsertEntry(entryAfterFirst!!.copy(updatedAt = sentinelUpdatedAt))

            fakeClockMs += 35_000L
            backend.simulatedPositionMs = 595_000L
            controller.advanceSingleTick(tickCount = 30)
            drainJobs()
            assertEquals(1, database.dao().getAllConsumptionLogs().count { it.entryId == "ep-1" })
            assertEquals(
                sentinelUpdatedAt,
                database.dao().getEntryById("ep-1")?.updatedAt,
            )

            syncRequests.clear()
            backend.emitExternalPlayState(false)
            drainJobs()
            assertFalse(controller.state.value.isPlaying)
            assertEquals(1, syncRequests.size)
            assertTrue(syncRequests.single().body.orEmpty().contains("\"progress\":"))
            assertTrue(syncRequests.single().body.orEmpty().contains("\"read_at\":"))
        }
    }

    @Test
    fun `off-Main release queued before Main-thread loadAndPlay does not stop newly started episode`() {
        val mainDispatcher = EventLoopMainDispatcher(mainThread = Thread.currentThread())
        val parentScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val harness = FakeExoPlayerHarness(durationMs = 420_000L)
        val backend =
            ExoPlayerAudioBackend(
                    appContext = null,
                    allowedLocalDirs = listOf(tempDir),
                    parentScope = parentScope,
                    mainDispatcher = mainDispatcher,
                    uriResolver = { null },
                )
                .also { it.exoPlayer = harness.player }
        val bridge =
            AndroidPodcastMediaSessionBridge(
                appContext = null,
                backend = null,
                scope = parentScope,
                mainDispatcher = mainDispatcher,
            )

        try {
            startEpisodeOnBackend(backend, "https://pod.example.com/ep1.mp3")
            bridge.updatePlaybackSession(sampleSessionSnapshot("ep-1", "Episode One"))
            runOffMain {
                backend.release()
                bridge.release()
            }
            assertEquals(0, harness.stopCalls.get())

            val preparedDurations = mutableListOf<Long>()
            startEpisodeOnBackend(
                backend,
                "https://pod.example.com/ep2.mp3",
                preparedDurations::add,
            )
            bridge.updatePlaybackSession(sampleSessionSnapshot("ep-2", "Episode Two"))
            mainDispatcher.drainQueue()

            assertEquals(2, harness.prepareCalls.get())
            assertEquals(0, harness.stopCalls.get())
            assertEquals(0, harness.clearMediaItemsCalls.get())
            assertTrue(backend.isPlaying())
            assertEquals("ep-2", bridge.currentSnapshot()?.entryId)
            backend.handlePlayerReady(harness.player)
            assertEquals(listOf(420_000L), preparedDurations)

            runOffMain {
                backend.release()
                bridge.release()
            }
            mainDispatcher.drainQueue()
            assertEquals(1, harness.stopCalls.get())
            assertFalse(backend.isPlaying())
            assertNull(bridge.currentSnapshot())
        } finally {
            parentScope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `owned-scope media session bridge release cleans up controllerFuture even when updatePlaybackSession races`() {
        val mainDispatcher = EventLoopMainDispatcher(mainThread = Thread.currentThread())
        val bridge =
            AndroidPodcastMediaSessionBridge(
                appContext = null,
                backend = null,
                scope = null,
                mainDispatcher = mainDispatcher,
            )
        val controllerFuture = FakeControllerFuture()
        bridge.updatePlaybackSession(sampleSessionSnapshot("ep-1", "Episode One"))
        bridge.controllerFuture = controllerFuture

        runOffMain {
            bridge.release()
            bridge.updatePlaybackSession(sampleSessionSnapshot("ep-2", "Episode Two"))
        }
        mainDispatcher.drainQueue()

        assertNull(bridge.controllerFuture)
        assertTrue(controllerFuture.isCancelled)
        assertNull(bridge.currentSnapshot())

        // Subsequent updates after terminal release on an owned-scope bridge are ignored.
        bridge.updatePlaybackSession(sampleSessionSnapshot("ep-3", "Episode Three"))
        mainDispatcher.drainQueue()
        assertNull(bridge.currentSnapshot())
    }

    @Test
    fun `controller disconnect clears and releases active controllerFuture without clobbering reconnect`() {
        val mainDispatcher = EventLoopMainDispatcher(mainThread = Thread.currentThread())
        val parentScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val bridge =
            AndroidPodcastMediaSessionBridge(
                appContext = null,
                backend = null,
                scope = parentScope,
                mainDispatcher = mainDispatcher,
            )
        try {
            val firstFuture = FakeControllerFuture()
            val secondFuture = FakeControllerFuture()
            assertNotNull(bridge.createControllerListener { firstFuture })
            bridge.controllerFuture = firstFuture

            bridge.clearControllerOnDisconnect(firstFuture)
            assertNull(bridge.controllerFuture)
            assertTrue(firstFuture.isCancelled)

            bridge.controllerFuture = secondFuture
            bridge.clearControllerOnDisconnect(firstFuture)
            assertEquals(secondFuture, bridge.controllerFuture)
            assertFalse(secondFuture.isCancelled)
        } finally {
            parentScope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withPlayerHarness(
        syncRequests: CopyOnWriteArrayList<HttpRequest>,
        clockMs: () -> Long,
        block:
            suspend (
                MainViewModel,
                PodcastPlayerController,
                InspectableAudioBackend,
                suspend () -> Unit,
            ) -> Unit,
    ) {
        val apiClient = RocheApiClient { request ->
            if (request.url.endsWith("/api/v1/sync/peer")) syncRequests.add(request)
            HttpResponse(statusCode = 200, body = PEER_SYNC_OK_BODY)
        }
        seedPodcastEpisode()
        val backend = InspectableAudioBackend()
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val repo =
            RocheRepository(
                database = database,
                apiClient = apiClient,
                settings = settings,
                cacheDir = File(tempDir, "cache"),
                backgroundScope = playerScope,
            )
        val controller =
            createManagedPlayerController(
                scope = playerScope,
                repository = repo,
                settingsRepository = settings,
                playerBackend = backend,
                clockMs = clockMs,
            )
        val vm = MainViewModel(repo, settings, sharedPlayerController = controller)
        val drainJobs: suspend () -> Unit = {
            while (true) {
                val activeJobs =
                    playerScope.coroutineContext[Job]
                        ?.children
                        ?.filter { !it.isCompleted }
                        ?.toList()
                        .orEmpty()
                if (activeJobs.isEmpty()) break
                activeJobs.joinAll()
            }
        }
        val collectJob = collectInBackground(vm.uiState)
        try {
            vm.uiState.first { it.allEntries.size == 1 }
            vm.dispatch(MainAction.PlayAudioEntry("ep-1"))
            vm.playerState.first {
                it.currentEntryId == "ep-1" && it.isPlaying && !it.isBuffering
            }
            vm.uiState.first { !it.allEntries.first().readAt.isNullOrBlank() }
            controller.cancelAutoTickerForTest()
            drainJobs()
            syncRequests.clear()
            block(vm, controller, backend, drainJobs)
        } finally {
            controller.releaseController()
            drainJobs()
            playerScope.coroutineContext[Job]?.cancelAndJoin()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            collectJob.cancelAndJoin()
        }
    }

    private suspend fun seedPodcastEpisode() {
        settings.update {
            it.copy(
                serverUrl = "https://roche.tail9876.ts.net",
                bearerToken = "tok-valid",
                skipSilenceEnabled = true,
            )
        }
        database
            .dao()
            .upsertFeeds(
                listOf(
                    FeedRow(
                        id = "f-pod",
                        title = "Deep Tech",
                        url = "https://pod.example.com/rss.xml",
                        siteUrl = "https://pod.example.com",
                        feedType = "podcast",
                        lastPolled = "2099-01-01T00:00:00Z",
                    )
                )
            )
        database
            .dao()
            .upsertItems(
                listOf(
                    InboxItemRow(
                        id = "ep-1",
                        feedId = "f-pod",
                        feedTitle = "Deep Tech",
                        title = "Episode One",
                        url = "https://pod.example.com/ep1",
                        author = "Host",
                        summary = "Summary",
                        contentText = null,
                        contentHtml = null,
                        contentType = "audio",
                        status = "unread",
                        isFavorite = false,
                        isCachedOffline = false,
                        publishedAt = "2026-06-01T12:00:00Z",
                        createdAt = "2026-06-01T12:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "10:00",
                        audioUrl = "https://pod.example.com/ep1.mp3",
                    )
                )
            )
    }

    private class InspectableAudioBackend : AudioPlayerBackend {
        var skipSilenceEnabled: Boolean = false
        var simulatedPositionMs: Long = 0L
        private var durationMs: Long = 600_000L
        private var playing: Boolean = false
        private var externalListener: ((Boolean) -> Unit)? = null

        fun emitExternalPlayState(isNowPlaying: Boolean) {
            playing = isNowPlaying
            externalListener?.invoke(isNowPlaying)
        }

        override fun bindExternalPlayStateListener(listener: ((Boolean) -> Unit)?) {
            externalListener = listener
        }

        override fun loadAndPlay(
            source: String,
            initialPositionMs: Long,
            fallbackDurationMs: Long,
            speed: Float,
            skipSilenceEnabled: Boolean,
            playWhenReady: Boolean,
            onPrepared: (durationMs: Long) -> Unit,
            onCompletion: () -> Unit,
            onError: () -> Unit,
        ) {
            this.skipSilenceEnabled = skipSilenceEnabled
            this.durationMs = fallbackDurationMs
            this.simulatedPositionMs = initialPositionMs
            this.playing = playWhenReady
            onPrepared(durationMs)
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            simulatedPositionMs = positionMs
        }

        override fun setSpeed(speed: Float) = Unit

        override fun setSkipSilence(enabled: Boolean) {
            skipSilenceEnabled = enabled
        }

        override fun currentPositionMs(): Long = simulatedPositionMs

        override fun durationMs(): Long = durationMs

        override fun isPlaying(): Boolean = playing

        override fun release() {
            playing = false
        }
    }

    private class EventLoopMainDispatcher(private val mainThread: Thread) : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            Thread.currentThread() !== mainThread

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drainQueue() {
            while (true) {
                val next = queue.poll() ?: break
                next.run()
            }
        }
    }

    private class FakeExoPlayerHarness(private val durationMs: Long) {
        val prepareCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val clearMediaItemsCalls = AtomicInteger(0)
        private var playWhenReadyState: Boolean = false

        val player: ExoPlayer =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { proxy, method, args ->
                handleCall(proxy, method.name, method.returnType, args)
            } as ExoPlayer

        private fun handleCall(
            proxy: Any,
            name: String,
            returnType: Class<*>,
            args: Array<out Any?>?,
        ): Any? {
            if (handleMutatingCall(name, args)) return null
            return when (name) {
                "getPlayWhenReady",
                "isPlaying" -> playWhenReadyState
                "getDuration" -> durationMs
                "getPlaybackState" -> Player.STATE_READY
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeExoPlayer"
                else -> defaultPrimitiveReturn(returnType)
            }
        }

        private fun handleMutatingCall(name: String, args: Array<out Any?>?): Boolean =
            when (name) {
                "prepare" -> {
                    prepareCalls.incrementAndGet()
                    true
                }
                "stop" -> {
                    stopCalls.incrementAndGet()
                    playWhenReadyState = false
                    true
                }
                "clearMediaItems" -> {
                    clearMediaItemsCalls.incrementAndGet()
                    true
                }
                "setPlayWhenReady" -> {
                    playWhenReadyState = args?.firstOrNull() as? Boolean ?: false
                    true
                }
                else -> {
                    false
                }
            }
    }
}

private fun startEpisodeOnBackend(
    backend: ExoPlayerAudioBackend,
    source: String,
    onPrepared: (Long) -> Unit = {},
) {
    backend.loadAndPlay(
        source = source,
        initialPositionMs = 0L,
        fallbackDurationMs = 300_000L,
        speed = 1.25f,
        skipSilenceEnabled = true,
        playWhenReady = true,
        onPrepared = onPrepared,
        onCompletion = {},
        onError = {},
    )
}

private fun runOffMain(block: () -> Unit) {
    val thread = Thread(block)
    thread.start()
    thread.join()
}

private fun sampleSessionSnapshot(
    entryId: String,
    title: String,
): MediaSessionMetadataSnapshot =
    MediaSessionMetadataSnapshot(
        entryId = entryId,
        episodeTitle = title,
        podcastTitle = "Deep Tech",
        artworkUrl = null,
        isPlaying = true,
        isBuffering = false,
        positionMs = 0L,
        durationMs = 420_000L,
        playbackSpeed = 1.25f,
        hasNextQueuedAudio = false,
    )

private fun defaultPrimitiveReturn(returnType: Class<*>): Any? =
    when (returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 1f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

private const val PEER_SYNC_OK_BODY =
    """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000000000-0001-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[]}"""

private class FakeControllerFuture : ListenableFuture<MediaController> {
    private val cancelled = AtomicBoolean(false)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
        cancelled.compareAndSet(false, true)

    override fun isCancelled(): Boolean = cancelled.get()

    override fun isDone(): Boolean = cancelled.get()

    override fun get(): MediaController = error("FakeControllerFuture never resolves")

    override fun get(timeout: Long, unit: TimeUnit): MediaController =
        error("FakeControllerFuture never resolves")

    override fun addListener(listener: Runnable, executor: Executor) = Unit
}

private class TestAudioHttpURLConnection(
    url: URL,
    private val code: Int,
    private val headers: Map<String, List<String>> = emptyMap(),
) : HttpURLConnection(url) {
    val capturedRequestHeaders = LinkedHashMap<String, String>()

    override fun setRequestProperty(key: String?, value: String?) {
        if (key != null && value != null) {
            capturedRequestHeaders[key] = value
        }
        super.setRequestProperty(key, value)
    }

    override fun connect() {
        connected = true
    }

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = code

    override fun getResponseMessage(): String = "OK"

    override fun getHeaderField(name: String?): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun getHeaderFields(): MutableMap<String, MutableList<String>> =
        headers.mapValues { it.value.toMutableList() }.toMutableMap()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun verifySafeRedirectUpgradeAndRebind() {
    val publicAddr =
        java.net.InetAddress.getByAddress("cdn.example.com", byteArrayOf(93, -72, -40, 34))
    val loopbackAddr =
        java.net.InetAddress.getByAddress("cdn.example.com", byteArrayOf(127, 0, 0, 1))
    var cdnReboundToLoopback = false
    val connectedUrls = mutableListOf<String>()
    val dataSource =
        SafeRedirectHttpDataSource(
            openConnection = { url ->
                val urlStr = url.toString()
                connectedUrls += urlStr
                if (urlStr == "https://pod.example.com/track/ep1.mp3") {
                    TestAudioHttpURLConnection(
                        url = url,
                        code = 302,
                        headers =
                            mapOf("Location" to listOf("http://cdn.example.com/media/ep1.mp3")),
                    )
                } else {
                    TestAudioHttpURLConnection(url = url, code = 200)
                }
            },
            validateUrl = { url ->
                resolveSafeHttpPlaybackUrl(
                    rawUrl = url,
                    resolveAddresses = { host ->
                        if (host == "cdn.example.com" && cdnReboundToLoopback) {
                            arrayOf(loopbackAddr)
                        } else {
                            arrayOf(publicAddr)
                        }
                    },
                    isCleartextPermitted = { false },
                )
            },
        )
    val (_, resolved) = dataSource.openValidatedUrl("http://pod.example.com/track/ep1.mp3")
    assertEquals("https://cdn.example.com/media/ep1.mp3", resolved)
    assertEquals(
        listOf(
            "https://pod.example.com/track/ep1.mp3",
            "https://cdn.example.com/media/ep1.mp3",
        ),
        connectedUrls,
    )

    cdnReboundToLoopback = true
    connectedUrls.clear()
    val rebindErr = runCatching {
        dataSource.openValidatedUrl("http://pod.example.com/track/ep1.mp3")
    }
        .exceptionOrNull()
    assertTrue(rebindErr is java.io.IOException)
    assertEquals(listOf("https://pod.example.com/track/ep1.mp3"), connectedUrls)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun verifyBlockedPrivateRedirectHop() {
    val publicAddr =
        java.net.InetAddress.getByAddress("pod.example.com", byteArrayOf(93, -72, -40, 34))
    val privateAddr =
        java.net.InetAddress.getByAddress("lan.internal.test", byteArrayOf(10, 0, 0, 5))
    val connectedUrls = mutableListOf<String>()
    val dataSource =
        SafeRedirectHttpDataSource(
            openConnection = { url ->
                val urlStr = url.toString()
                connectedUrls += urlStr
                if (urlStr == "https://pod.example.com/evil.mp3") {
                    TestAudioHttpURLConnection(
                        url = url,
                        code = 302,
                        headers = mapOf("Location" to listOf("https://lan.internal.test/admin")),
                    )
                } else {
                    error("Should never connect to private redirect target: $urlStr")
                }
            },
            validateUrl = { url ->
                resolveSafeHttpPlaybackUrl(
                    rawUrl = url,
                    resolveAddresses = { host ->
                        if (host.contains("lan.internal")) {
                            arrayOf(privateAddr)
                        } else {
                            arrayOf(publicAddr)
                        }
                    },
                    isCleartextPermitted = { false },
                )
            },
        )
    val ssrfErr = runCatching {
        dataSource.openValidatedUrl("https://pod.example.com/evil.mp3")
    }
        .exceptionOrNull()
    assertTrue(ssrfErr is java.io.IOException)
    assertEquals(listOf("https://pod.example.com/evil.mp3"), connectedUrls)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun verifyCrossOriginRedirectStripsSensitiveHeaders() {
    val connections = mutableListOf<TestAudioHttpURLConnection>()
    val dataSource =
        SafeRedirectHttpDataSource(
            openConnection = { url ->
                val conn =
                    when (url.toString()) {
                        "https://pod.example.com/start.mp3" ->
                            TestAudioHttpURLConnection(
                                url = url,
                                code = 302,
                                headers = mapOf("Location" to listOf("/same-origin.mp3")),
                            )
                        "https://pod.example.com/same-origin.mp3" ->
                            TestAudioHttpURLConnection(
                                url = url,
                                code = 302,
                                headers =
                                    mapOf("Location" to listOf("https://cdn.other.example/ep.mp3")),
                            )
                        else -> TestAudioHttpURLConnection(url = url, code = 200)
                    }
                connections += conn
                conn
            },
            validateUrl = { it },
        )

    val (_, finalUrl) =
        dataSource.openValidatedUrl(
            rawUrl = "https://pod.example.com/start.mp3",
            requestHeaders =
                mapOf(
                    "Authorization" to "Bearer secret-tok",
                    "Cookie" to "sid=abc",
                    "Proxy-Authorization" to "Basic xyz",
                    "X-Trace" to "trace-1",
                ),
        )
    assertEquals("https://cdn.other.example/ep.mp3", finalUrl)
    assertEquals(3, connections.size)

    val sameOriginHeaders = connections[1].capturedRequestHeaders
    assertEquals("Bearer secret-tok", sameOriginHeaders["Authorization"])
    assertEquals("sid=abc", sameOriginHeaders["Cookie"])
    assertEquals("Basic xyz", sameOriginHeaders["Proxy-Authorization"])
    assertEquals("trace-1", sameOriginHeaders["X-Trace"])

    val crossOriginHeaders = connections[2].capturedRequestHeaders
    assertFalse(crossOriginHeaders.containsKey("Authorization"))
    assertFalse(crossOriginHeaders.containsKey("Cookie"))
    assertFalse(crossOriginHeaders.containsKey("Proxy-Authorization"))
    assertEquals("trace-1", crossOriginHeaders["X-Trace"])
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun verifySeeOtherRewriteAndSpaceSanitization() {
    val connections = mutableListOf<TestAudioHttpURLConnection>()
    val dataSource =
        SafeRedirectHttpDataSource(
            openConnection = { url ->
                val conn =
                    if (url.toString() == "https://pod.example.com/upload") {
                        TestAudioHttpURLConnection(
                            url = url,
                            code = 303,
                            headers = mapOf("Location" to listOf("/my podcast ep 1.mp3")),
                        )
                    } else {
                        TestAudioHttpURLConnection(url = url, code = 200)
                    }
                connections += conn
                conn
            },
            validateUrl = { it },
        )

    val (_, resolved) =
        dataSource.openValidatedUrl(
            rawUrl = "https://pod.example.com/upload",
            httpMethod = "PUT",
            httpBody = "payload".toByteArray(),
            requestHeaders =
                mapOf(
                    "Content-Type" to "audio/mpeg",
                    "Content-Length" to "7",
                    "Authorization" to "Bearer same-origin",
                ),
        )
    assertEquals("https://pod.example.com/my%20podcast%20ep%201.mp3", resolved)
    assertEquals(2, connections.size)
    assertEquals("PUT", connections[0].requestMethod)
    assertEquals("GET", connections[1].requestMethod)
    assertFalse(connections[1].capturedRequestHeaders.containsKey("Content-Type"))
    assertFalse(connections[1].capturedRequestHeaders.containsKey("Content-Length"))
    assertEquals("Bearer same-origin", connections[1].capturedRequestHeaders["Authorization"])

    val directUrls = mutableListOf<String>()
    val spaceSource =
        SafeRedirectHttpDataSource(
            openConnection = { url ->
                directUrls += url.toString()
                TestAudioHttpURLConnection(url = url, code = 200)
            },
            validateUrl = { it },
        )
    spaceSource.openValidatedUrl("https://cdn.example.com/episode 42 final.mp3")
    assertEquals(listOf("https://cdn.example.com/episode%2042%20final.mp3"), directUrls)
}
