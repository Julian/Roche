package com.grayvines.roche

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.net.toUri
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShareReceiveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            dispatchIncomingShare()
        }
        finishQuietly()
    }

    private fun dispatchIncomingShare() {
        val payload = extractSharedContentFromIntent(intent, ::queryStreamDisplayName)
        if (payload == null) {
            Toast.makeText(applicationContext, "No shareable URL or PDF found", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val componentClass = intent?.component?.className.orEmpty()
        val isSubscribeFeedTarget =
            componentClass.endsWith(SUBSCRIBE_FEED_ALIAS_SUFFIX, ignoreCase = true)
        if (isSubscribeFeedTarget) {
            handleSubscribeFeedShare(payload)
        } else {
            handleWantToReadShare(payload)
        }
    }

    private fun handleWantToReadShare(payload: SharedContentPayload) {
        val appContext = applicationContext
        val graph = (application as RocheApp).graph
        val pdfStream = openPdfStreamIfPresent(payload.pdfStreamUri)
        Toast.makeText(appContext, "Added to Want to Read", Toast.LENGTH_SHORT).show()
        graph.appScope.launch(Dispatchers.IO) {
            runCatching {
                graph.repository.addUrlToWantToRead(
                    inputUrl = payload.url,
                    titleOverride = payload.title,
                    forcedContentType = payload.forcedContentType,
                    pdfInputStream = pdfStream,
                )
            }
                .onFailure {
                    runCatching { pdfStream?.close() }
                }
        }
    }

    private fun handleSubscribeFeedShare(payload: SharedContentPayload) {
        val appContext = applicationContext
        val graph = (application as RocheApp).graph
        Toast.makeText(appContext, "Finding RSS feed…", Toast.LENGTH_SHORT).show()
        graph.repository.subscribeFeedInBackground(
            inputUrl = payload.url,
            titleOverride = payload.title,
        ) { result ->
            graph.appScope.launch(Dispatchers.Main) {
                val msg =
                    result.fold(
                        onSuccess = { saved -> "Subscribed to \"${saved.title}\"" },
                        onFailure = { err -> err.message ?: "Could not subscribe to feed" },
                    )
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
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
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        internal const val WANT_TO_READ_ALIAS_SUFFIX = ".ShareWantToReadAlias"
        internal const val SUBSCRIBE_FEED_ALIAS_SUFFIX = ".ShareSubscribeFeedAlias"
    }
}
