package com.grayvines.roche

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.MainAction
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.theme.RocheTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val graph = (application as RocheApp).graph
        MainViewModel.Factory(repository = graph.repository, settings = graph.settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
        }
        val thumbnailLoader = (application as RocheApp).graph.thumbnailLoader

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalThumbnailLoader provides thumbnailLoader) {
                RocheTheme(
                    themeMode = state.settings.themeMode,
                    headlineFont = state.settings.headlineFont,
                ) {
                    MainScreen(
                        state = state,
                        onAction = viewModel::dispatch,
                        onShareEntry = ::shareEntryViaSystemSheet,
                        onOpenExternalUrl = ::openUrlInBrowser,
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

    private companion object {
        private val ALLOWED_HTTP_SCHEMES = setOf("http", "https")
    }
}
