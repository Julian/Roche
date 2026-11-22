package com.grayvines.roche.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.mockRepository
import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.tailnetNetworkTierProvider
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.playback.AudioBackendListener
import com.grayvines.roche.playback.AudioLoadRequest
import com.grayvines.roche.playback.AudioPlaybackMetadata
import com.grayvines.roche.playback.AudioPlayerBackend
import com.grayvines.roche.playback.Media3AudioBackend
import com.grayvines.roche.playback.Media3SessionClient
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.PodcastPlayerController
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.playback.SafeRedirectHttpDataSource
import com.grayvines.roche.playback.createManagedPlayerController
import com.grayvines.roche.playback.formatAdjustedRemaining
import com.grayvines.roche.playback.isAllowedServiceMediaUri
import com.grayvines.roche.playback.isPlayerCmdAllowedForCaller
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
import kotlinx.coroutines.launch
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

    private var managedDbOrNull: com.grayvines.roche.data.ManagedTestDatabase? = null
    private var dataStoreScopeOrNull: CoroutineScope? = null
    private var settingsOrNull: SettingsRepository? = null

    private val database: RocheDatabase
        get() =
            (managedDbOrNull
                    ?: com.grayvines.roche.data
                        .ManagedTestDatabase(allowMainThreadQueries = true)
                        .also { managedDbOrNull = it })
                .database

    private val settings: SettingsRepository
        get() =
            settingsOrNull
                ?: run {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    dataStoreScopeOrNull = scope
                    val dataStore =
                        PreferenceDataStoreFactory.create(
                            scope = scope,
                            produceFile = { File(tempDir, "settings.preferences_pb") },
                        )
                    SettingsRepository(dataStore).also { settingsOrNull = it }
                }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        dataStoreScopeOrNull?.let { scope ->
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        }
        dataStoreScopeOrNull = null
        settingsOrNull = null
        managedDbOrNull?.close()
        managedDbOrNull = null
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

            val backend = InspectableAudioBackend().apply { skipSilenceEnabled = true }
            val repo =
                mockRepository(
                    database = database,
                    settings = settings,
                    transport = { HttpResponse(statusCode = 200, body = PEER_SYNC_OK_BODY) },
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
        val harness = FakeSessionClientHarness(durationMs = 420_000L)
        val preparedDurations = mutableListOf<Long>()
        val backend =
            Media3AudioBackend(
                appContext = null,
                allowedLocalDirs = listOf(tempDir),
                parentScope = parentScope,
                mainDispatcher = mainDispatcher,
                uriResolver = { null },
                customClientConnector = { Futures.immediateFuture(harness) },
            )
        backend.bindListener(
            object : AudioBackendListener {
                override fun onPrepared(durationMs: Long) {
                    preparedDurations.add(durationMs)
                }

                override fun onPlayStateChanged(
                    isPlaying: Boolean,
                    isBuffering: Boolean,
                    positionMs: Long,
                    durationMs: Long,
                ) = Unit

                override fun onPositionSeeked(positionMs: Long) = Unit

                override fun onSpeedChanged(speed: Float) = Unit

                override fun onCompletion() = Unit

                override fun onError() = Unit
            }
        )

        try {
            startEpisodeOnBackend(backend, "https://pod.example.com/ep1.mp3", "ep-1", "Episode One")
            runOffMain { backend.release() }
            assertEquals(0, harness.stopAndReleaseCalls.get())

            startEpisodeOnBackend(backend, "https://pod.example.com/ep2.mp3", "ep-2", "Episode Two")
            mainDispatcher.drainQueue()

            assertEquals(2, harness.prepareCalls.get())
            assertEquals(0, harness.stopAndReleaseCalls.get())
            assertTrue(backend.isPlaying())
            assertEquals("ep-2", backend.latestMetadata?.entryId)
            backend.handlePlayerReady(harness.player)
            assertEquals(listOf(420_000L), preparedDurations)

            runOffMain { backend.release() }
            mainDispatcher.drainQueue()
            assertEquals(1, harness.stopAndReleaseCalls.get())
            assertEquals(1, harness.disconnectCalls.get())
            assertFalse(backend.isPlaying())
            assertNull(backend.latestMetadata)
        } finally {
            parentScope.coroutineContext[Job]?.cancel()
            mainDispatcher.drainQueue()
            runBlocking { parentScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @Test
    fun `controller disconnect clears and releases active clientFuture without clobbering reconnect`() {
        val mainDispatcher = EventLoopMainDispatcher(mainThread = Thread.currentThread())
        val parentScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val backend =
            Media3AudioBackend(
                appContext = null,
                allowedLocalDirs = listOf(tempDir),
                parentScope = parentScope,
                mainDispatcher = mainDispatcher,
                uriResolver = { null },
            )
        try {
            val firstFuture = FakeSessionClientFuture()
            val secondFuture = FakeSessionClientFuture()
            backend.clientFuture = firstFuture

            backend.clearClientOnDisconnect(firstFuture)
            assertNull(backend.clientFuture)
            assertTrue(firstFuture.isCancelled)

            backend.clientFuture = secondFuture
            backend.clearClientOnDisconnect(firstFuture)
            assertEquals(secondFuture, backend.clientFuture)
            assertFalse(secondFuture.isCancelled)
        } finally {
            parentScope.coroutineContext[Job]?.cancel()
            mainDispatcher.drainQueue()
            runBlocking { parentScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @Test
    fun `formatAdjustedRemaining fixes zero and sub-minute remaining formatting`() {
        assertEquals("0m left", formatAdjustedRemaining(0L, 1.0f))
        assertEquals("< 1m left", formatAdjustedRemaining(20_000L, 1.0f))
        assertEquals("2m left", formatAdjustedRemaining(95_000L, 1.0f))
        assertEquals("50m left", formatAdjustedRemaining(4_500_000L, 1.5f))
        assertEquals("2h 0m left", formatAdjustedRemaining(7_200_000L, 1.0f))

        val finishedState =
            PodcastPlayerState(
                currentEntryId = "ep-1",
                positionMs = 600_000L,
                durationMs = 600_000L,
                playbackSpeed = 1.0f,
            )
        assertEquals("-00:00", finishedState.formattedRemaining)
        assertEquals("0m left", finishedState.formattedMiniRemaining)
        assertEquals("0m left (at 1.0x)", finishedState.formattedSpeedAdjustedRemaining)
    }

    @Test
    fun `isAllowedServiceMediaUri rejects untrusted callers and unsafe URIs`() {
        val allowedDir = File(tempDir, "items").apply { mkdirs() }
        val localFile = File(allowedDir, "ep.mp3").apply { writeText("mp3") }

        assertFalse(
            isAllowedServiceMediaUri(
                isTrustedCaller = false,
                uriString = "https://cdn.example.com/ep.mp3",
                allowedLocalDirs = listOf(allowedDir),
            )
        )
        assertFalse(
            isAllowedServiceMediaUri(
                isTrustedCaller = true,
                uriString = "http://127.0.0.1/secret.mp3",
                allowedLocalDirs = listOf(allowedDir),
            )
        )
        assertFalse(
            isAllowedServiceMediaUri(
                isTrustedCaller = true,
                uriString = null,
                allowedLocalDirs = listOf(allowedDir),
            )
        )
        assertTrue(
            isAllowedServiceMediaUri(
                isTrustedCaller = true,
                uriString = "https://cdn.example.com/ep.mp3",
                allowedLocalDirs = listOf(allowedDir),
            )
        )
        assertTrue(
            isAllowedServiceMediaUri(
                isTrustedCaller = true,
                uriString = localFile.toURI().toString(),
                allowedLocalDirs = listOf(allowedDir),
            )
        )
    }

    @Test
    fun `SelectPlayerDrawerTab keeps drawer open on active tab re-tap and closes on null`() {
        val opened =
            reduceUiAction(
                TransientUiState(),
                MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.UP_NEXT),
            )
        assertEquals(PlayerDrawerTab.UP_NEXT, opened.activePlayerDrawerTab)

        val retapped =
            reduceUiAction(
                opened,
                MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.UP_NEXT),
            )
        assertEquals(PlayerDrawerTab.UP_NEXT, retapped.activePlayerDrawerTab)

        val closed = reduceUiAction(retapped, MainAction.SelectPlayerDrawerTab(null))
        assertNull(closed.activePlayerDrawerTab)
        assertTrue(MainUiState(activePlayerDrawerTab = PlayerDrawerTab.UP_NEXT).hasOpenModalSheet)
        assertFalse(MainUiState(activePlayerDrawerTab = null).hasOpenModalSheet)
    }

    @Test
    fun `isPlayerCmdAllowedForCaller strips playlist mutation commands for external callers`() {
        assertTrue(isPlayerCmdAllowedForCaller(true, Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(isPlayerCmdAllowedForCaller(true, Player.COMMAND_CHANGE_MEDIA_ITEMS))

        assertFalse(isPlayerCmdAllowedForCaller(false, Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(isPlayerCmdAllowedForCaller(false, Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(isPlayerCmdAllowedForCaller(false, Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun `Media3AudioBackend preserves pending seek and speed while connecting and recovers from failed future`() {
        val mainDispatcher = EventLoopMainDispatcher(mainThread = Thread.currentThread())
        val parentScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val pendingFuture = CompletableSessionFuture()
        val harness = FakeSessionClientHarness(durationMs = 420_000L)
        var connectAttempt = 0
        val errorCount = AtomicInteger(0)
        val playStateEvents = mutableListOf<Boolean>()
        val backend =
            Media3AudioBackend(
                appContext = null,
                allowedLocalDirs = listOf(tempDir),
                parentScope = parentScope,
                mainDispatcher = mainDispatcher,
                uriResolver = { null },
                customClientConnector = {
                    connectAttempt++
                    if (connectAttempt == 1) {
                        Futures.immediateCancelledFuture()
                    } else {
                        pendingFuture
                    }
                },
            )
        backend.bindListener(
            object : AudioBackendListener {
                override fun onPrepared(durationMs: Long) = Unit

                override fun onPlayStateChanged(
                    isPlaying: Boolean,
                    isBuffering: Boolean,
                    positionMs: Long,
                    durationMs: Long,
                ) {
                    playStateEvents.add(isPlaying)
                }

                override fun onPositionSeeked(positionMs: Long) = Unit

                override fun onSpeedChanged(speed: Float) = Unit

                override fun onCompletion() = Unit

                override fun onError() {
                    errorCount.incrementAndGet()
                }
            }
        )
        try {
            // Attempt 1: cancelled future triggers onError and clears clientFuture for recovery
            startEpisodeOnBackend(backend, "https://pod.example.com/ep1.mp3", "ep-1", "Ep 1")
            mainDispatcher.drainQueue()
            assertEquals(1, errorCount.get())
            assertNull(backend.clientFuture)

            // Attempt 2: seek and speed changes queued while connecting are applied on connect
            startEpisodeOnBackend(backend, "https://pod.example.com/ep2.mp3", "ep-2", "Ep 2")
            backend.seekTo(123_000L)
            backend.setSpeed(1.85f)
            pendingFuture.complete(harness)
            mainDispatcher.drainQueue()

            assertEquals(123_000L, harness.lastSetMediaItemPositionMs)
            assertEquals(1.85f, harness.lastSpeed, 1e-3f)
            assertEquals(123_000L, backend.currentPositionMs())
            assertTrue(playStateEvents.isEmpty())

            // Idle player with 0 items does not wipe cachedPositionMs
            harness.playbackState = Player.STATE_IDLE
            harness.mediaItemCount = 0
            harness.currentPositionMs = 0L
            backend.syncVolatileStateFromPlayer(harness.player)
            assertEquals(123_000L, backend.currentPositionMs())
        } finally {
            parentScope.coroutineContext[Job]?.cancel()
            mainDispatcher.drainQueue()
            runBlocking { parentScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @Test
    fun `background playback observers sync settings and queue state without MainViewModel`() =
        runTest {
            seedPodcastEpisode()
            val backend = InspectableAudioBackend()
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val repo =
                mockRepository(
                    database = database,
                    settings = settings,
                    transport = { HttpResponse(statusCode = 200, body = PEER_SYNC_OK_BODY) },
                    cacheDir = File(tempDir, "bg_observer_cache"),
                    networkTierProvider = tailnetNetworkTierProvider(),
                    backgroundScope = bgScope,
                )
            val controller =
                createManagedPlayerController(
                    scope = bgScope,
                    repository = repo,
                    settingsRepository = settings,
                    playerBackend = backend,
                    observerScope = bgScope,
                )
            try {
                assertTrue(controller.hasBackgroundObserver)
                controller.state.first { it.skipSilenceEnabled }
                assertTrue(backend.skipSilenceEnabled)

                settings.update { it.copy(skipSilenceEnabled = false, smartRewindEnabled = false) }
                controller.state.first { !it.skipSilenceEnabled && !it.smartRewindEnabled }
                assertFalse(backend.skipSilenceEnabled)
            } finally {
                controller.releaseController()
                bgScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `player controller orders backend sync and suppresses synchronous seek and speed echoes`() =
        runTest {
            val backend = InspectableAudioBackend().apply { echoSeekAndSpeedSynchronously = true }
            val controllerScope =
                CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            var progressSaveCount = 0
            var sessionSpeedCallbacks = 0
            val controller =
                PodcastPlayerController(
                    scope = controllerScope,
                    backend = backend,
                    onProgressSaved = { _, _, _ -> progressSaveCount++ },
                    onSpeedChangedFromSession = { _, _ -> sessionSpeedCallbacks++ },
                )
            var observedReleaseBeforeClear = false
            val collectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    controller.state.collect { state ->
                        if (backend.releaseCount > 0) {
                            assertFalse(backend.isPlaying())
                            observedReleaseBeforeClear = true
                        } else {
                            assertEquals(state.skipSilenceEnabled, backend.skipSilenceEnabled)
                            assertEquals(state.hasNextQueuedAudio, backend.hasNextQueuedAudio)
                        }
                    }
                }
            try {
                controller.playEntry(
                    entry = sampleAudioEntry("ep-order"),
                    sourceUriOrPath = "https://pod.example.com/ep-order.mp3",
                    speed = 1.0f,
                    skipSilenceEnabled = false,
                )
                controller.cancelAutoTickerForTest()
                controller.setPlaybackSpeed(1.75f)
                controller.seekToMs(120_000L)
                controller.setSkipSilence(true)
                controller.setSkipSilence(false)
                controller.updateHasNextQueuedAudio(true)
                controller.updateHasNextQueuedAudio(false)
                controller.stopAndClear()
                assertEquals(1.75f, backend.lastSpeed, 1e-3f)
                assertEquals(0, sessionSpeedCallbacks)
                assertEquals(1, progressSaveCount)
                assertFalse(backend.skipSilenceEnabled)
                assertFalse(backend.hasNextQueuedAudio)
                assertTrue(observedReleaseBeforeClear)
            } finally {
                collectJob.cancelAndJoin()
                controller.releaseController()
                controllerScope.coroutineContext[Job]?.cancelAndJoin()
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
        seedPodcastEpisode()
        val backend = InspectableAudioBackend()
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val repo =
            mockRepository(
                database = database,
                settings = settings,
                transport = { request ->
                    if (request.url.endsWith("/api/v1/sync/peer")) syncRequests.add(request)
                    HttpResponse(statusCode = 200, body = PEER_SYNC_OK_BODY)
                },
                cacheDir = File(tempDir, "cache"),
                networkTierProvider = tailnetNetworkTierProvider(),
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

    private fun sampleAudioEntry(id: String): InboxEntry =
        InboxEntry(
            id = id,
            feedId = "f-pod",
            feedTitle = "Deep Tech",
            title = "Episode $id",
            url = "https://pod.example.com/$id",
            author = "Host",
            summary = "Summary",
            contentText = null,
            contentHtml = null,
            contentType = ContentType.AUDIO,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-06-01T12:00:00Z",
            createdAt = "2026-06-01T12:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = "10:00",
            tags = emptyList(),
            audioUrl = "https://pod.example.com/$id.mp3",
        )

    private class InspectableAudioBackend : AudioPlayerBackend {
        @Volatile var echoSeekAndSpeedSynchronously: Boolean = false
        @Volatile var skipSilenceEnabled: Boolean = false
        @Volatile var lastSpeed: Float = 1.0f
        @Volatile var hasNextQueuedAudio: Boolean = false
        @Volatile var releaseCount: Int = 0
        @Volatile var simulatedPositionMs: Long = 0L
        @Volatile private var durationMs: Long = 600_000L
        @Volatile private var playing: Boolean = false
        @Volatile private var listener: AudioBackendListener? = null

        fun emitExternalPlayState(isNowPlaying: Boolean) {
            playing = isNowPlaying
            listener?.onPlayStateChanged(
                isPlaying = isNowPlaying,
                isBuffering = false,
                positionMs = simulatedPositionMs,
                durationMs = durationMs,
            )
        }

        override fun bindListener(listener: AudioBackendListener?) {
            this.listener = listener
        }

        override fun loadAndPlay(request: AudioLoadRequest) {
            this.skipSilenceEnabled = request.skipSilenceEnabled
            this.lastSpeed = request.speed
            this.hasNextQueuedAudio = request.hasNextQueuedAudio
            this.durationMs = request.fallbackDurationMs
            this.simulatedPositionMs = request.initialPositionMs
            this.playing = request.playWhenReady
            listener?.onPrepared(durationMs)
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            simulatedPositionMs = positionMs
            if (echoSeekAndSpeedSynchronously) {
                listener?.onPositionSeeked(positionMs)
            }
        }

        override fun setSpeed(speed: Float) {
            lastSpeed = speed
            if (echoSeekAndSpeedSynchronously) {
                listener?.onSpeedChanged(speed)
            }
        }

        override fun setSkipSilence(enabled: Boolean) {
            skipSilenceEnabled = enabled
        }

        override fun updateHasNextQueuedAudio(hasNext: Boolean) {
            hasNextQueuedAudio = hasNext
        }

        override fun currentPositionMs(): Long = simulatedPositionMs

        override fun durationMs(): Long = durationMs

        override fun isPlaying(): Boolean = playing

        override fun release() {
            playing = false
            releaseCount++
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

    private class FakeSessionClientHarness(private val durationMs: Long) : Media3SessionClient {
        val prepareCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val clearMediaItemsCalls = AtomicInteger(0)
        val stopAndReleaseCalls = AtomicInteger(0)
        val disconnectCalls = AtomicInteger(0)
        var playbackState: Int = Player.STATE_READY
        var mediaItemCount: Int = 1
        var currentPositionMs: Long = 0L
        var lastSetMediaItemPositionMs: Long = -1L
        var lastSpeed: Float = 1.0f
        private var playWhenReadyState: Boolean = false

        override val player: ExoPlayer =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { proxy, method, args ->
                handleCall(proxy, method.name, method.returnType, args)
            } as ExoPlayer

        override fun sendSkipSilence(enabled: Boolean) = Unit

        override fun sendHasNextQueued(hasNext: Boolean) = Unit

        override fun sendStopAndRelease() {
            stopAndReleaseCalls.incrementAndGet()
            playWhenReadyState = false
        }

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
        }

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
                "getCurrentPosition" -> currentPositionMs
                "getMediaItemCount" -> mediaItemCount
                "getPlaybackState" -> playbackState
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
                    mediaItemCount = 0
                    true
                }
                "setMediaItem" -> {
                    mediaItemCount = 1
                    if (args != null && args.size >= 2 && args[1] is Long) {
                        lastSetMediaItemPositionMs = args[1] as Long
                        currentPositionMs = lastSetMediaItemPositionMs
                    }
                    true
                }
                "setPlaybackParameters" -> {
                    val params = args?.firstOrNull() as? androidx.media3.common.PlaybackParameters
                    if (params != null) {
                        lastSpeed = params.speed
                    }
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
    backend: Media3AudioBackend,
    source: String,
    entryId: String,
    title: String,
) {
    backend.loadAndPlay(
        AudioLoadRequest(
            source = source,
            initialPositionMs = 0L,
            fallbackDurationMs = 300_000L,
            speed = 1.25f,
            skipSilenceEnabled = true,
            hasNextQueuedAudio = false,
            metadata =
                AudioPlaybackMetadata(
                    entryId = entryId,
                    episodeTitle = title,
                    podcastTitle = "Deep Tech",
                    artworkUrl = null,
                    durationMs = 420_000L,
                ),
            playWhenReady = true,
        )
    )
}

private fun runOffMain(block: () -> Unit) {
    val thread = Thread(block)
    thread.start()
    thread.join()
}

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

private class CompletableSessionFuture : ListenableFuture<Media3SessionClient> {
    @Volatile private var value: Media3SessionClient? = null
    private val listeners = CopyOnWriteArrayList<Pair<Runnable, Executor>>()

    fun complete(client: Media3SessionClient) {
        value = client
        listeners.forEach { (r, exec) -> exec.execute(r) }
        listeners.clear()
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = value != null

    override fun get(): Media3SessionClient = checkNotNull(value)

    override fun get(timeout: Long, unit: TimeUnit): Media3SessionClient = checkNotNull(value)

    override fun addListener(listener: Runnable, executor: Executor) {
        if (value != null) {
            executor.execute(listener)
        } else {
            listeners.add(listener to executor)
        }
    }
}

private class FakeSessionClientFuture : ListenableFuture<Media3SessionClient> {
    private val cancelled = AtomicBoolean(false)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
        cancelled.compareAndSet(false, true)

    override fun isCancelled(): Boolean = cancelled.get()

    override fun isDone(): Boolean = cancelled.get()

    override fun get(): Media3SessionClient = error("FakeSessionClientFuture never resolves")

    override fun get(timeout: Long, unit: TimeUnit): Media3SessionClient =
        error("FakeSessionClientFuture never resolves")

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
