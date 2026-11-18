package com.grayvines.roche

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.core.net.toUri
import com.grayvines.roche.data.PeerSyncScheduler
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ShareReceiveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        if (savedInstanceState != null) {
            finishQuietly()
            return
        }
        val appContext = applicationContext
        val graph = (application as RocheApp).graph
        val incomingIntent = intent
        graph.appScope.launch(Dispatchers.IO) {
            try {
                processIncomingShare(appContext, graph, incomingIntent)
            } finally {
                withContext(Dispatchers.Main) { finishQuietly() }
            }
        }
    }

    private suspend fun processIncomingShare(
        appContext: android.content.Context,
        graph: AppGraph,
        incomingIntent: android.content.Intent?,
    ) {
        val payload = extractSharedContentFromIntent(incomingIntent, ::queryStreamDisplayName)
        if (payload == null) {
            showShareToast(appContext, "No shareable URL or PDF found")
            return
        }
        val componentClass = incomingIntent?.component?.className.orEmpty()
        val isSubscribeFeedTarget =
            componentClass.endsWith(SUBSCRIBE_FEED_ALIAS_SUFFIX, ignoreCase = true)
        if (isSubscribeFeedTarget) {
            handleSubscribeFeedShare(appContext, graph, payload)
        } else {
            handleWantToReadShare(appContext, graph, payload)
        }
    }

    private suspend fun handleWantToReadShare(
        appContext: android.content.Context,
        graph: AppGraph,
        payload: SharedContentPayload,
    ) {
        val pdfStream = openPdfStreamIfPresent(payload.pdfStreamUri)
        val saveResult = runCatching {
            graph.repository.addUrlToWantToRead(
                inputUrl = payload.url,
                titleOverride = payload.title,
                forcedContentType = payload.forcedContentType,
                pdfInputStream = pdfStream,
                awaitPdfCacheCopy = true,
            )
        }
        saveResult
            .onSuccess {
                showShareToast(appContext, "Added to Want to Read")
                enqueueSyncIfConfigured(appContext, graph)
            }
            .onFailure { err ->
                runCatching { pdfStream?.close() }
                showShareToast(appContext, err.message ?: "Could not save shared item")
            }
    }

    private suspend fun handleSubscribeFeedShare(
        appContext: android.content.Context,
        graph: AppGraph,
        payload: SharedContentPayload,
    ) {
        showShareToast(appContext, "Finding RSS feed…")
        val result =
            withTimeoutOrNull(SHARE_DISCOVERY_TIMEOUT_MS) {
                graph.repository.discoverAndSubscribeFeed(
                    inputUrl = payload.url,
                    titleOverride = payload.title,
                )
            } ?: Result.failure(IllegalStateException("Timed out finding RSS feed"))
        val msg =
            result.fold(
                onSuccess = { saved ->
                    enqueueSyncIfConfigured(appContext, graph)
                    "Subscribed to \"${saved.title}\""
                },
                onFailure = { err -> err.message ?: "Could not subscribe to feed" },
            )
        showShareToast(appContext, msg)
    }

    private suspend fun enqueueSyncIfConfigured(
        appContext: android.content.Context,
        graph: AppGraph,
    ) {
        val currentSettings = runCatching { graph.settings.settings.first() }.getOrNull() ?: return
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            PeerSyncScheduler.scheduleImmediateSync(appContext, currentSettings.serverUrl)
        }
    }

    private suspend fun showShareToast(appContext: android.content.Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPdfStreamIfPresent(rawUri: String?): InputStream? {
        if (rawUri.isNullOrBlank()) return null
        return runCatching { contentResolver.openInputStream(rawUri.toUri()) }.getOrNull()
    }

    private fun queryStreamDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            cursor ->
            val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (colIdx >= 0 && cursor.moveToFirst()) {
                cursor.getString(colIdx)
            } else {
                null
            }
        }
    }
        .getOrNull()

    private fun finishQuietly() {
        if (!isFinishing) {
            finish()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    companion object {
        private const val SHARE_DISCOVERY_TIMEOUT_MS = 15_000L
        internal const val WANT_TO_READ_ALIAS_SUFFIX = ".ShareWantToReadAlias"
        internal const val SUBSCRIBE_FEED_ALIAS_SUFFIX = ".ShareSubscribeFeedAlias"
    }
}
