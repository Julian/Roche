package com.grayvines.roche

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.sync.PeerSyncScheduler
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheTheme
import java.io.InputStream
import java.io.PushbackInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ShareReceiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finishQuietly()
            return
        }
        val appContext = applicationContext
        val graph = (application as RocheApp).graph
        val incomingIntent = intent
        val payload = extractSharedContentFromIntent(incomingIntent, ::queryStreamDisplayName)
        if (payload == null) {
            Toast.makeText(appContext, "No shareable URL or PDF found", Toast.LENGTH_SHORT).show()
            finishQuietly()
            return
        }
        val componentClass = incomingIntent?.component?.className.orEmpty()
        val isSubscribeFeedTarget =
            componentClass.endsWith(SUBSCRIBE_FEED_ALIAS_SUFFIX, ignoreCase = true)
        val requiresConfirmation = isSubscribeFeedTarget || payload.pdfStreamUri != null
        if (!requiresConfirmation) {
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            graph.appScope.launch(Dispatchers.IO) {
                executeInstantWantToReadSave(appContext, graph, payload)
            }
            finishQuietly()
            return
        }
        renderConfirmationDialog(
            appContext = appContext,
            graph = graph,
            payload = payload,
            isSubscribeFeedTarget = isSubscribeFeedTarget,
        )
    }

    private fun renderConfirmationDialog(
        appContext: Context,
        graph: AppGraph,
        payload: SharedContentPayload,
        isSubscribeFeedTarget: Boolean,
    ) {
        val themeMode = graph.settings.latestSettings?.themeMode
        setContent {
            RocheTheme(themeMode = themeMode ?: com.grayvines.roche.model.ThemeMode.DARK) {
                var isBusy by remember { mutableStateOf(false) }
                ShareConfirmationDialog(
                    payload = payload,
                    isSubscribeFeedTarget = isSubscribeFeedTarget,
                    isBusy = isBusy,
                    onDismiss = {
                        if (!isBusy) {
                            finishQuietly()
                        }
                    },
                    onConfirm = {
                        isBusy = true
                        lifecycleScope.launch {
                            if (isSubscribeFeedTarget) {
                                executeConfirmedFeedSubscribe(appContext, graph, payload)
                            } else {
                                executeConfirmedPdfSave(appContext, graph, payload)
                            }
                            finishQuietly()
                        }
                    },
                )
            }
        }
    }

    private suspend fun executeConfirmedPdfSave(
        appContext: Context,
        graph: AppGraph,
        payload: SharedContentPayload,
    ) {
        val result =
            withContext(Dispatchers.IO) {
                val pdfStream = openPdfStreamIfPresent(appContext, payload.pdfStreamUri)
                if (
                    payload.pdfStreamUri != null && pdfStream == null && isLocalPdfUrl(payload.url)
                ) {
                    Result.failure(IllegalStateException("Could not open shared PDF"))
                } else {
                    runSuspendCatching {
                        graph.repository.addUrlToWantToRead(
                            inputUrl = payload.url,
                            titleOverride = payload.title,
                            forcedContentType = payload.forcedContentType,
                            pdfInputStream = pdfStream,
                            awaitPdfCacheCopy = true,
                        )
                    }
                        .onFailure { runCatching { pdfStream?.close() } }
                }
            }
        result
            .onSuccess { saved ->
                showShareToast(appContext, formatWantToReadSuccessToast(saved.title))
                enqueueSyncIfConfigured(appContext, graph)
            }
            .onFailure { err ->
                val msg = err.message ?: "Could not save shared PDF"
                if (isLocalPdfUrl(payload.url)) {
                    showShareToast(appContext, msg)
                } else {
                    launchRecoveryInMainActivity(appContext, payload, msg)
                }
            }
    }

    private suspend fun executeConfirmedFeedSubscribe(
        appContext: Context,
        graph: AppGraph,
        payload: SharedContentPayload,
    ) {
        val result =
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(SHARE_DISCOVERY_TIMEOUT_MS) {
                    graph.repository.discoverAndSubscribeFeed(
                        inputUrl = payload.url,
                        titleOverride = payload.title,
                    )
                } ?: Result.failure(IllegalStateException("Timed out finding RSS feed"))
            }
        result
            .onSuccess { saved ->
                enqueueSyncIfConfigured(appContext, graph)
                showShareToast(appContext, "Subscribed to \"${saved.title.take(TOAST_TITLE_MAX)}\"")
            }
            .onFailure { err ->
                val msg = err.message ?: "Could not subscribe to feed"
                launchRecoveryInMainActivity(
                    appContext = appContext,
                    payload = payload,
                    errorMessage = msg,
                    preferSubscribeFeed = true,
                )
            }
    }

    private fun queryStreamDisplayName(uri: Uri): String? {
        if (!isAllowedSharedUri(applicationContext, uri)) return null
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

    private fun finishQuietly() {
        if (!isFinishing) {
            finish()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    companion object {
        private const val SHARE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val TOAST_TITLE_MAX = 48
        internal const val WANT_TO_READ_ALIAS_SUFFIX = ".ShareWantToReadAlias"
        internal const val SUBSCRIBE_FEED_ALIAS_SUFFIX = ".ShareSubscribeFeedAlias"
    }
}

@Composable
internal fun ShareConfirmationDialog(
    payload: SharedContentPayload,
    isSubscribeFeedTarget: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val title = if (isSubscribeFeedTarget) "Subscribe to Feed?" else "Save Shared PDF?"
    val confirmLabel =
        when {
            isBusy && isSubscribeFeedTarget -> "Finding feed…"
            isBusy -> "Saving…"
            isSubscribeFeedTarget -> "Subscribe"
            else -> "Save PDF"
        }
    val detailTarget =
        if (isLocalPdfUrl(payload.url)) {
            payload.title?.takeIf { it.isNotBlank() } ?: "Shared PDF document"
        } else {
            payload.url
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!payload.title.isNullOrBlank() && !isLocalPdfUrl(payload.url)) {
                    Text(
                        text = payload.title,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = detailTarget,
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isBusy) {
                Text(confirmLabel, color = colors.accentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

internal suspend fun executeInstantWantToReadSave(
    appContext: Context,
    graph: AppGraph,
    payload: SharedContentPayload,
) {
    val saveResult = runSuspendCatching {
        graph.repository.addUrlToWantToRead(
            inputUrl = payload.url,
            titleOverride = payload.title,
            forcedContentType = payload.forcedContentType,
        )
    }
    saveResult
        .onSuccess { saved ->
            showShareToast(appContext, formatWantToReadSuccessToast(saved.title))
            enqueueSyncIfConfigured(appContext, graph)
        }
        .onFailure { err ->
            val msg = err.message ?: "Could not save shared item"
            launchRecoveryInMainActivity(appContext, payload, msg)
        }
}

internal fun formatWantToReadSuccessToast(savedTitle: String): String {
    val clean = savedTitle.trim()
    return if (clean.isEmpty()) {
        "Added to Want to Read"
    } else {
        val clipped = if (clean.length > 48) "${clean.take(47)}…" else clean
        "Saved \"$clipped\" to Want to Read"
    }
}

internal suspend fun launchRecoveryInMainActivity(
    appContext: Context,
    payload: SharedContentPayload,
    errorMessage: String,
    preferSubscribeFeed: Boolean = false,
) {
    withContext(Dispatchers.Main) {
        val launchIntent =
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_SHARE_PREFILL_URL, payload.url)
                putExtra(MainActivity.EXTRA_SHARE_PREFILL_TITLE, payload.title.orEmpty())
                putExtra(MainActivity.EXTRA_SHARE_ERROR_MESSAGE, errorMessage)
                putExtra(MainActivity.EXTRA_SHARE_PREFILL_IS_FEED, preferSubscribeFeed)
            }
        runCatching { appContext.startActivity(launchIntent) }
            .onFailure { Toast.makeText(appContext, errorMessage, Toast.LENGTH_LONG).show() }
    }
}

internal suspend fun enqueueSyncIfConfigured(
    appContext: Context,
    graph: AppGraph,
) {
    val currentSettings =
        runSuspendCatching { graph.settings.settings.first() }.getOrNull() ?: return
    if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
        PeerSyncScheduler.scheduleImmediateSync(appContext, currentSettings.serverUrl)
    }
}

internal suspend fun showShareToast(appContext: Context, message: String) {
    withContext(Dispatchers.Main) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}

internal fun openPdfStreamIfPresent(appContext: Context, rawUri: String?): InputStream? {
    if (rawUri.isNullOrBlank()) return null
    val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
    if (!isAllowedSharedUri(appContext, uri)) return null
    return runCatching {
        val rawStream = appContext.contentResolver.openInputStream(uri) ?: return null
        openValidatedPdfStream(rawStream)
    }
        .getOrNull()
}

internal fun isAllowedSharedUri(appContext: Context, uri: Uri): Boolean =
    uri.userInfo == null &&
        uri.port == -1 &&
        uri.encodedAuthority?.let { '@' !in it && ':' !in it && '%' !in it } == true &&
        isAllowedSharedContentUri(
            scheme = uri.scheme,
            authority = uri.authority,
            ownPackageName = appContext.packageName,
            resolveProviderPackage = { auth ->
                runCatching {
                    appContext.packageManager.resolveContentProvider(auth, 0)?.packageName
                }
                    .getOrNull()
            },
        )

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
