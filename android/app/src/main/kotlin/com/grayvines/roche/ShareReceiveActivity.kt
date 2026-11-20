package com.grayvines.roche

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.core.net.toUri
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.sync.PeerSyncScheduler
import java.io.InputStream
import java.io.PushbackInputStream
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
        if (payload.pdfStreamUri != null && pdfStream == null && isLocalPdfUrl(payload.url)) {
            showShareToast(appContext, "Could not open shared PDF")
            return
        }
        val saveResult = runSuspendCatching {
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
        val currentSettings =
            runSuspendCatching { graph.settings.settings.first() }.getOrNull() ?: return
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
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
        if (!isAllowedSharedUri(uri)) return null
        return runCatching {
            val rawStream = contentResolver.openInputStream(uri) ?: return null
            openValidatedPdfStream(rawStream)
        }
            .getOrNull()
    }

    private fun queryStreamDisplayName(uri: Uri): String? {
        if (!isAllowedSharedUri(uri)) return null
        return runCatching {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIdx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(colIdx)
                    } else {
                        null
                    }
                }
        }
            .getOrNull()
    }

    private fun isAllowedSharedUri(uri: Uri): Boolean =
        uri.userInfo == null &&
            uri.port == -1 &&
            uri.encodedAuthority?.let { '@' !in it && ':' !in it && '%' !in it } == true &&
            isAllowedSharedContentUri(
                scheme = uri.scheme,
                authority = uri.authority,
                ownPackageName = packageName,
                resolveProviderPackage = { auth ->
                    runCatching { packageManager.resolveContentProvider(auth, 0)?.packageName }
                        .getOrNull()
                },
            )

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

internal fun isAllowedSharedContentUri(
    scheme: String?,
    authority: String?,
    ownPackageName: String,
    resolveProviderPackage: (String) -> String? = { null },
): Boolean {
    if (!scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
    val cleanAuthority = authority?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val hasUnsafeAuthorityChars =
        '@' in cleanAuthority ||
            ':' in cleanAuthority ||
            '%' in cleanAuthority ||
            cleanAuthority.any { it.isWhitespace() || it.isISOControl() }
    val isOwnAuthority =
        hasUnsafeAuthorityChars ||
            cleanAuthority.equals(ownPackageName, ignoreCase = true) ||
            cleanAuthority.startsWith("$ownPackageName.", ignoreCase = true)
    return !isOwnAuthority &&
        !ownPackageName.equals(resolveProviderPackage(cleanAuthority), ignoreCase = true)
}

internal fun openValidatedPdfStream(stream: InputStream): InputStream? {
    val pushback = PushbackInputStream(stream, PDF_MAGIC_BYTES.size)
    val header = ByteArray(PDF_MAGIC_BYTES.size)
    var totalRead = 0
    while (totalRead < PDF_MAGIC_BYTES.size) {
        val read = pushback.read(header, totalRead, PDF_MAGIC_BYTES.size - totalRead)
        if (read == -1) break
        totalRead += read
    }
    if (!hasPdfMagicHeader(header, totalRead)) {
        runCatching { pushback.close() }
        return null
    }
    pushback.unread(header, 0, totalRead)
    return pushback
}
