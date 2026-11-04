package com.grayvines.roche

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.MainAction
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.theme.RocheTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val graph = (application as RocheApp).graph
        MainViewModel.Factory(repository = graph.repository, settings = graph.settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShareIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (sharedText.isNotBlank()) {
                viewModel.dispatch(MainAction.OpenAddSheet(prefilledUrl = sharedText))
            }
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
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        }
    }
}
