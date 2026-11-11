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
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grayvines.roche.data.PeerSyncScheduler
import com.grayvines.roche.data.remote.readBoundedText
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.MainAction
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.PodcastPlaybackService
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.theme.RocheTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val graph = (application as RocheApp).graph
        MainViewModel.Factory(
            repository = graph.repository,
            settings = graph.settings,
            mediaSessionBridge = graph.mediaSessionBridge,
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
        PeerSyncScheduler.schedulePeriodicSync(this)
        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
            viewModel.triggerStartupSyncIfConfigured()
        }
        val thumbnailLoader = (application as RocheApp).graph.thumbnailLoader

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalThumbnailLoader provides thumbnailLoader) {
                RocheTheme(
                    themeMode = state.settings.themeMode,
                    headlineFont = state.settings.headlineFont,
                    readerFontScale = state.settings.readerFontScale,
                ) {
                    MainScreen(
                        state = state,
                        onAction = viewModel::dispatch,
                        onShareEntry = ::shareEntryViaSystemSheet,
                        onOpenExternalUrl = ::openUrlInBrowser,
                        onImportOpml = { importOpmlLauncher.launch(OPML_MIME_TYPES) },
                        onExportOpml = { exportOpmlLauncher.launch("roche-feeds.opml") },
                        onMoveTaskToBack = { moveTaskToBack(true) },
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
            return
        }
        val payload =
            extractSharedLink(
                action = currentIntent.action,
                mimeType = currentIntent.type,
                extraText = currentIntent.getStringExtra(Intent.EXTRA_TEXT),
                extraSubject = currentIntent.getStringExtra(Intent.EXTRA_SUBJECT),
                extraTitle = currentIntent.getStringExtra(Intent.EXTRA_TITLE),
            )
        if (payload != null) {
            currentIntent.removeExtra(Intent.EXTRA_TEXT)
            currentIntent.removeExtra(Intent.EXTRA_SUBJECT)
            currentIntent.removeExtra(Intent.EXTRA_TITLE)
            currentIntent.action = Intent.ACTION_MAIN
            viewModel.dispatch(
                MainAction.OpenAddSheet(
                    prefilledUrl = payload.url,
                    prefilledTitle = payload.title.orEmpty(),
                )
            )
        }
    }

    private fun shareEntryViaSystemSheet(entry: InboxEntry) {
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

    private fun openUrlInBrowser(url: String) {
        runCatching {
            val uri = url.trim().toUri()
            if (uri.scheme?.lowercase() in ALLOWED_HTTP_SCHEMES) {
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
        private val ALLOWED_HTTP_SCHEMES = setOf("http", "https")
        private val OPML_MIME_TYPES = arrayOf("text/xml", "application/xml", "text/x-opml", "*/*")
    }
}
