package com.grayvines.roche

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.readBoundedText
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.sync.PeerSyncScheduler
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PodcastPlaybackService
import com.grayvines.roche.ui.CompanionSubTab
import com.grayvines.roche.ui.LocalOfflineCacheDir
import com.grayvines.roche.ui.MainAction
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.RocheTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var musicDrawResolveJob: Job? = null

    private val viewModel: MainViewModel by viewModels {
        val graph = (application as RocheApp).graph
        MainViewModel.Factory(
            repository = graph.repository,
            settings = graph.settings,
            mediaSessionBridge = graph.mediaSessionBridge,
            sharedPlayerController = graph.playerController,
            sharedPlaybackOrchestrator = graph.playbackOrchestrator,
        )
    }

    private val importOpmlLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importOpmlFromUri(uri)
            }
        }

    private val exportOpmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri: Uri?
            ->
            if (uri != null) {
                exportOpmlToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
            viewModel.triggerStartupSyncIfConfigured()
        }
        val appGraph = (application as RocheApp).graph
        val thumbnailLoader = appGraph.thumbnailLoader
        val offlineCacheDir = appGraph.offlineCacheDir

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.isInitialLoading, state.settings.serverUrl) {
                if (!state.isInitialLoading) {
                    PeerSyncScheduler.schedulePeriodicSync(
                        context = this@MainActivity,
                        serverUrl = state.settings.serverUrl,
                    )
                }
            }
            CompositionLocalProvider(
                LocalThumbnailLoader provides thumbnailLoader,
                LocalOfflineCacheDir provides offlineCacheDir,
            ) {
                RocheTheme(
                    themeMode = state.settings.themeMode,
                    headlineFont = state.settings.headlineFont,
                    readerFontScale = state.settings.readerFontScale,
                ) {
                    MainScreen(
                        state = state,
                        playerStateFlow = viewModel.playerState,
                        onAction = viewModel::dispatch,
                        onShareEntry = ::shareEntryViaSystemSheet,
                        onOpenExternalUrl = ::openUrlInBrowser,
                        onImportOpml = { importOpmlLauncher.launch(OPML_MIME_TYPES) },
                        onExportOpml = { exportOpmlLauncher.launch("roche-feeds.opml") },
                        onOpenCollectionDraw = ::openCollectionDraw,
                        onPlayMusicTrack = { track ->
                            launchMusicTrack(this@MainActivity, track, ::openUrlInBrowser)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        val currentIntent = intent ?: return
        if (
            currentIntent.action == PodcastPlaybackService.ACTION_OPEN_PLAYER ||
                currentIntent.getBooleanExtra(
                    PodcastPlaybackService.EXTRA_OPEN_FULLSCREEN_PLAYER,
                    false,
                )
        ) {
            currentIntent.removeExtra(PodcastPlaybackService.EXTRA_OPEN_FULLSCREEN_PLAYER)
            currentIntent.action = Intent.ACTION_MAIN
            viewModel.dispatch(MainAction.OpenFullscreenPlayer)
        }
    }

    private fun shareEntryViaSystemSheet(entry: InboxEntry) {
        if (isLocalPdfUrl(entry.url)) {
            viewModel.showStatusMessage("Local PDF has no public web URL to share")
            return
        }
        runCatching {
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, entry.title)
                    putExtra(Intent.EXTRA_TEXT, entry.url)
                }
            startActivity(Intent.createChooser(sendIntent, entry.title))
        }
    }

    private fun openCollectionDraw(draw: CollectionDrawSnapshot) {
        if (draw.isMusicKind) {
            openMusicCollectionDraw(draw)
            return
        }
        val readableIds = viewModel.uiState.value.allEntries.mapTo(HashSet()) { it.id }
        val fallback: (() -> Unit)? =
            draw.collectionId
                .takeIf { it.isNotBlank() }
                ?.let { colId ->
                    {
                        viewModel.dispatch(
                            MainAction.OpenCompanionSection(CompanionSubTab.LISTS, colId)
                        )
                    }
                }
        resolveCatalogEntryOpenAction(
                entryId = draw.entryId,
                externalUrl = draw.primaryExternalUrl,
                readableIds = readableIds,
                onOpenReader = { entryId -> viewModel.dispatch(MainAction.OpenReader(entryId)) },
                onOpenExternalUrl = ::openUrlInBrowser,
                onFallback = fallback,
            )
            ?.invoke()
    }

    private fun openMusicCollectionDraw(draw: CollectionDrawSnapshot) {
        musicDrawResolveJob?.cancel()
        musicDrawResolveJob = lifecycleScope.launch {
            when (val plan = resolveInitialAlbumLaunchPlan(this@MainActivity, draw)) {
                is InitialAlbumLaunchPlan.LaunchDirect -> {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        launchStreamingResolution(this@MainActivity, draw, plan.resolution)
                    }
                }
                InitialAlbumLaunchPlan.FallbackSearchOrBrowser -> {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        launchAlbumSearchOrFallback(this@MainActivity, draw, ::openUrlInBrowser)
                    }
                }
                InitialAlbumLaunchPlan.NeedsEnrichment -> {
                    enrichAndLaunchAlbum(draw)
                }
            }
        }
    }

    private suspend fun enrichAndLaunchAlbum(draw: CollectionDrawSnapshot) {
        val repo = (application as RocheApp).graph.repository
        val enriched =
            withTimeoutOrNull(ALBUM_RESOLVE_TIMEOUT_MS) {
                repo.resolveCollectionDrawStreaming(draw)
            } ?: draw
        val resolution = resolveEnrichedAlbumLaunch(this@MainActivity, enriched)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (!launchStreamingResolution(this@MainActivity, enriched, resolution)) {
            launchAlbumSearchOrFallback(this@MainActivity, enriched, ::openUrlInBrowser)
        }
    }

    private fun openUrlInBrowser(url: String) {
        runCatching {
            val uri = url.trim().toUri()
            if (uri.scheme?.lowercase() in ALLOWED_EXTERNAL_SCHEMES) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }

    private fun importOpmlFromUri(uri: Uri) {
        lifecycleScope.launch {
            val xml =
                withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.let { stream ->
                            readBoundedText(stream)
                        }
                    }
                        .getOrNull()
                }
            if (xml.isNullOrBlank()) {
                viewModel.showStatusMessage("Could not read OPML file")
            } else {
                viewModel.dispatch(MainAction.ImportOpml(xml))
            }
        }
    }

    private fun exportOpmlToUri(uri: Uri) {
        lifecycleScope.launch {
            viewModel
                .exportOpmlXml()
                .onSuccess { xml ->
                    val written =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    contentResolver.openOutputStream(uri)?.use { stream ->
                                        stream.write(xml.toByteArray(Charsets.UTF_8))
                                    } != null
                                }
                                .getOrDefault(false)
                        }
                    val msg =
                        if (written) "Exported OPML subscriptions" else "Failed to write OPML file"
                    viewModel.showStatusMessage(msg)
                }
                .onFailure { err ->
                    viewModel.showStatusMessage("Export failed (${err.message ?: "error"})")
                }
        }
    }

    private companion object {
        private const val ALBUM_RESOLVE_TIMEOUT_MS = 5_000L
        private val ALLOWED_EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
        private val OPML_MIME_TYPES = arrayOf("text/xml", "application/xml", "text/x-opml", "*/*")
    }
}
