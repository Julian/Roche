@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grayvines.roche.BuildConfig
import com.grayvines.roche.core.isLoopbackHost
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.isCrossPeerRescueError
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SettingsServerPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
    onRevealSavedBearerToken: suspend () -> String = { "" },
) {
    val isConfigured = state.settings.serverUrl.isNotBlank() && state.hasBearerToken
    val pageState = rememberServerPageFormState(state, isConfigured)
    val scope = rememberCoroutineScope()

    ServerPageEffects(state = state, form = pageState)

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageHeader(title = "Server & Pairing", onBack = onBack)
        ServerPageScrollBody(
            state = state,
            isConfigured = isConfigured,
            serverUrl = pageState.serverUrl,
            bearerToken = pageState.bearerTokenDraft,
            tokenEdited = pageState.tokenEdited,
            pairingCode = pageState.pairingCode,
            revealToken = pageState.revealToken,
            revealPairingCode = pageState.revealPairingCode,
            showSetupCards = pageState.showSetupCards,
            onToggleSetupCards = { pageState.showSetupCards = !pageState.showSetupCards },
            onRequestDisconnect = { pageState.showDisconnectConfirm = true },
            onServerUrlChange = { pageState.serverUrl = it },
            onBearerTokenChange = pageState::updateBearerTokenDraft,
            onPairingCodeChange = { pageState.pairingCode = it },
            onToggleRevealPairingCode = {
                pageState.revealPairingCode = !pageState.revealPairingCode
            },
            onToggleRevealToken = {
                toggleSavedTokenReveal(
                    form = pageState,
                    hasSavedBearerToken = state.hasBearerToken,
                    scope = scope,
                    onRevealSavedBearerToken = onRevealSavedBearerToken,
                )
            },
            onSaveConfig = {
                val explicitToken = if (pageState.tokenEdited) pageState.bearerTokenDraft else null
                onAction(MainAction.UpdateServerConfig(pageState.serverUrl, explicitToken))
                pageState.resetBearerTokenDraft()
            },
            onSyncNow = {
                val explicitToken = if (pageState.tokenEdited) pageState.bearerTokenDraft else null
                onAction(MainAction.SyncWithServerConfig(pageState.serverUrl, explicitToken))
                if (pageState.tokenEdited) {
                    pageState.resetBearerTokenDraft()
                }
            },
            onAction = onAction,
        )
    }

    if (pageState.showDisconnectConfirm) {
        DisconnectServerConfirmDialog(
            serverUrl = state.settings.serverUrl,
            onDismiss = { pageState.showDisconnectConfirm = false },
            onConfirm = {
                pageState.confirmDisconnect()
                onAction(MainAction.DisconnectServer)
            },
        )
    }
}

private class ServerPageFormState(
    initialServerUrl: String,
    initialShowSetupCards: Boolean,
) {
    var serverUrl by mutableStateOf(initialServerUrl)
    var bearerTokenDraft by mutableStateOf("")
    var tokenEdited by mutableStateOf(false)
    var pairingCode by mutableStateOf("")
    var revealToken by mutableStateOf(false)
    var revealPairingCode by mutableStateOf(false)
    var showSetupCards by mutableStateOf(initialShowSetupCards)
    var showDisconnectConfirm by mutableStateOf(false)
    var revealTokenJob: Job? = null

    fun cancelRevealJob() {
        revealTokenJob?.cancel()
        revealTokenJob = null
    }

    fun updateBearerTokenDraft(next: String) {
        cancelRevealJob()
        bearerTokenDraft = next
        tokenEdited = true
    }

    fun resetBearerTokenDraft() {
        cancelRevealJob()
        tokenEdited = false
        revealToken = false
        bearerTokenDraft = ""
    }

    fun clearSecretsOnPause() {
        cancelRevealJob()
        revealToken = false
        revealPairingCode = false
        if (!tokenEdited) {
            bearerTokenDraft = ""
        }
    }

    fun confirmDisconnect() {
        showDisconnectConfirm = false
        resetBearerTokenDraft()
        showSetupCards = true
    }
}

@Composable
private fun rememberServerPageFormState(
    state: MainUiState,
    isConfigured: Boolean,
): ServerPageFormState =
    rememberSaveable(
        saver =
            listSaver(
                save = { listOf(it.serverUrl, it.showSetupCards) },
                restore = { saved ->
                    ServerPageFormState(
                        initialServerUrl = saved[0] as String,
                        initialShowSetupCards = saved[1] as Boolean,
                    )
                },
            )
    ) {
        ServerPageFormState(
            initialServerUrl = state.settings.serverUrl,
            initialShowSetupCards = !isConfigured,
        )
    }

@Composable
private fun ServerPageEffects(state: MainUiState, form: ServerPageFormState) {
    SecureServerWindowEffect(onPauseClearSecrets = form::clearSecretsOnPause)
    LaunchedEffect(state.settings.serverUrl) {
        if (normalizeServerUrl(form.serverUrl) != state.settings.serverUrl) {
            form.serverUrl = state.settings.serverUrl
        }
    }
    LaunchedEffect(state.hasBearerToken, state.settings.serverUrl) {
        if (state.hasBearerToken) {
            form.pairingCode = ""
        }
        if (!form.tokenEdited) {
            form.bearerTokenDraft = ""
            form.revealToken = false
        }
    }
}

private fun toggleSavedTokenReveal(
    form: ServerPageFormState,
    hasSavedBearerToken: Boolean,
    scope: CoroutineScope,
    onRevealSavedBearerToken: suspend () -> String,
) {
    form.cancelRevealJob()
    if (form.revealToken) {
        form.revealToken = false
        if (!form.tokenEdited) {
            form.bearerTokenDraft = ""
        }
    } else {
        form.revealToken = true
        if (!form.tokenEdited && hasSavedBearerToken) {
            form.revealTokenJob = scope.launch {
                val revealed = onRevealSavedBearerToken()
                if (form.revealToken && !form.tokenEdited) {
                    form.bearerTokenDraft = revealed
                }
            }
        }
    }
}

@Composable
private fun SecureServerWindowEffect(onPauseClearSecrets: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context) {
        val window = context.findHostActivity()?.window
        val hadSecureFlag =
            window != null &&
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (window != null && !hadSecureFlag) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null && !hadSecureFlag) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                onPauseClearSecrets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private tailrec fun Context.findHostActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }

@Composable
private fun DisconnectServerConfirmDialog(
    serverUrl: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRocheColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = { Text("Disconnect Server?") },
        text = {
            Text(
                "This clears your saved server URL ($serverUrl) and bearer token on this device. " +
                    "Local feeds, inbox items, and catalog data remain on-device."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_disconnect_btn")) {
                Text("Disconnect", color = colors.accentIndianRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Composable
@Suppress("LongParameterList")
internal fun ServerPageScrollBody(
    state: MainUiState,
    isConfigured: Boolean,
    serverUrl: String,
    bearerToken: String,
    tokenEdited: Boolean,
    pairingCode: String,
    revealToken: Boolean,
    revealPairingCode: Boolean,
    showSetupCards: Boolean,
    onToggleSetupCards: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onToggleRevealPairingCode: () -> Unit,
    onToggleRevealToken: () -> Unit,
    onSaveConfig: () -> Unit,
    onSyncNow: () -> Unit = {},
    onAction: (MainAction) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ServerConnectionStatusCard(
            state = state,
            serverUrl = serverUrl,
            isConfigured = isConfigured,
            onRequestDisconnect = onRequestDisconnect,
            onSyncNow = onSyncNow,
            onAction = onAction,
            onHideKeyboard = { keyboardController?.hide() },
        )
        if (isConfigured) {
            SetupDisclosureRow(expanded = showSetupCards, onToggle = onToggleSetupCards)
        }
        if (!isConfigured || showSetupCards) {
            ServerPairingCard(
                serverUrl = serverUrl,
                pairingCode = pairingCode,
                revealPairingCode = revealPairingCode,
                isSyncing = state.isSyncing,
                onPairingCodeChange = onPairingCodeChange,
                onToggleRevealPairingCode = onToggleRevealPairingCode,
                onAction = onAction,
                onHideKeyboard = { keyboardController?.hide() },
            )
            ServerManualCredentialsCard(
                serverUrl = serverUrl,
                bearerToken = bearerToken,
                hasSavedBearerToken = state.hasBearerToken,
                tokenEdited = tokenEdited,
                revealToken = revealToken,
                onServerUrlChange = onServerUrlChange,
                onBearerTokenChange = onBearerTokenChange,
                onToggleRevealToken = onToggleRevealToken,
                onSaveClick = {
                    keyboardController?.hide()
                    onSaveConfig()
                },
            )
        }
        PeerMeshStatusCard(state = state, onAction = onAction)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SetupDisclosureRow(expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("server_advanced_disclosure"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Re-pair / Advanced Credentials",
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (expanded) "Hide ▴" else "Show ▾",
                color = colors.accentBlue,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun PeerMeshStatusCard(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val localPeerId = state.settings.peerId
    val activeFeeds = state.activeFeeds
    val onDevicePolledCount = activeFeeds.count { it.lastPolledBy == localPeerId }
    val rescueReadyCount = activeFeeds.count { it.isCrossPeerRescueError(localPeerId) }
    val tierLabel =
        if (state.localNetworkTier == NetworkTier.OFFLINE) {
            "Offline"
        } else {
            "Online · Dynamic cooperative polling"
        }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Cooperative Peer Mesh")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PeerMeshLocalHeaderRow(
                    deviceName = state.settings.deviceName,
                    tierLabel = tierLabel,
                    onPollOnDevice = { onAction(MainAction.PollFeedsOnDeviceNow) },
                )
                Text(
                    text =
                        buildPeerMeshHealthSummary(
                            totalFeeds = activeFeeds.size,
                            onDevicePolledCount = onDevicePolledCount,
                            rescueReadyCount = rescueReadyCount,
                        ),
                    color = if (rescueReadyCount > 0) colors.accentEcru else colors.textSecondary,
                    fontSize = 12.sp,
                )
                if (state.activePeers.isNotEmpty()) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                    state.activePeers.forEach { peer ->
                        val polledByPeer = activeFeeds.count { it.lastPolledBy == peer.peerId }
                        PeerMeshNodeRow(
                            peer = peer,
                            isLocalPeer = peer.peerId == localPeerId,
                            polledFeedCount = polledByPeer,
                            totalFeeds = activeFeeds.size,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PeerMeshLocalHeaderRow(
    deviceName: String,
    tierLabel: String,
    onPollOnDevice: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$deviceName (This Device)",
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tierLabel,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        HeaderActionButton(
            label = "Poll On-Device",
            onClick = onPollOnDevice,
            modifier = Modifier.testTag("poll_feeds_on_device_btn"),
        )
    }
}

@Composable
internal fun PeerMeshNodeRow(
    peer: PeerNodeDto,
    isLocalPeer: Boolean,
    polledFeedCount: Int = 0,
    totalFeeds: Int = 0,
) {
    val colors = LocalRocheColors.current
    val tier = NetworkTier.fromWireValue(peer.networkTier)
    val dotColor =
        if (tier == NetworkTier.OFFLINE) colors.accentIndianRed else colors.accentVerdigris
    val nameLabel = if (isLocalPeer) "${peer.deviceName} · local" else peer.deviceName
    val statusLabel =
        when {
            tier == NetworkTier.OFFLINE -> "Offline"
            totalFeeds > 0 -> "$polledFeedCount/$totalFeeds feeds"
            else -> "Online"
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Text(
                text = nameLabel,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = statusLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

internal fun buildPeerMeshHealthSummary(
    totalFeeds: Int,
    onDevicePolledCount: Int,
    rescueReadyCount: Int,
): String {
    if (totalFeeds == 0) {
        return "Equal-peer CRDT sync & dynamic cooperative polling active."
    }
    val base = "$onDevicePolledCount of $totalFeeds feeds last polled on-device"
    return if (rescueReadyCount > 0) {
        "$base · $rescueReadyCount peer error rescue ready"
    } else {
        "$base · Background sync every 30m"
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ServerConnectionStatusCard(
    state: MainUiState,
    serverUrl: String,
    isConfigured: Boolean,
    onRequestDisconnect: () -> Unit,
    onSyncNow: () -> Unit = {},
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val (dotColor, statusText) = resolveServerStatusBadge(state, isConfigured)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Connection Status")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerStatusHeaderRow(dotColor = dotColor, statusText = statusText)
                Text(
                    text = serverUrl.ifBlank { "No server URL configured" },
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
                ServerConnectionActionRow(
                    onSyncNow = onSyncNow,
                    onAction = onAction,
                    onHideKeyboard = onHideKeyboard,
                )
                if (isConfigured || state.settings.serverUrl.isNotBlank()) {
                    TextButton(
                        onClick = onRequestDisconnect,
                        modifier = Modifier.fillMaxWidth().testTag("disconnect_server_btn"),
                    ) {
                        Text(
                            text = "Disconnect Server",
                            color = colors.accentIndianRed,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun resolveServerStatusBadge(
    state: MainUiState,
    isConfigured: Boolean,
): Pair<Color, String> {
    val colors = LocalRocheColors.current
    val isOffline = state.localNetworkTier == NetworkTier.OFFLINE
    val dotColor =
        when {
            state.settings.hasUndecryptableToken -> colors.accentIndianRed
            !isConfigured -> colors.accentEcru
            isOffline -> colors.accentIndianRed
            else -> colors.accentVerdigris
        }
    val statusText =
        when {
            state.settings.hasUndecryptableToken -> "Saved Token Unavailable — Re-pair"
            !isConfigured -> "Server Not Yet Paired"
            isOffline -> "Offline · Server Paired"
            else -> "Paired with Tailnet Server"
        }
    return dotColor to statusText
}

@Composable
private fun ServerStatusHeaderRow(
    dotColor: Color,
    statusText: String,
) {
    val colors = LocalRocheColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(
            text = statusText,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ServerConnectionActionRow(
    onSyncNow: () -> Unit,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderActionButton(
            label = "Sync Now",
            style = RocheButtonStyle.PRIMARY,
            onClick = {
                onHideKeyboard()
                onSyncNow()
            },
            modifier = Modifier.weight(1f),
        )
        HeaderActionButton(
            label = "Discover on Tailnet",
            onClick = {
                onHideKeyboard()
                onAction(MainAction.DiscoverServer)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ServerPairingCard(
    serverUrl: String,
    pairingCode: String,
    revealPairingCode: Boolean,
    isSyncing: Boolean,
    onPairingCodeChange: (String) -> Unit,
    onToggleRevealPairingCode: () -> Unit,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val submitPair = {
        if (pairingCode.isNotBlank() && !isSyncing) {
            onHideKeyboard()
            onAction(MainAction.PairWithServer(serverUrl, pairingCode))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Pair New Device (roche pair)")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text =
                        "Run roche pair on your server and paste the one-time code (or host#code) below:",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                PairingCodeInputField(
                    pairingCode = pairingCode,
                    revealPairingCode = revealPairingCode,
                    onPairingCodeChange = onPairingCodeChange,
                    onToggleRevealPairingCode = onToggleRevealPairingCode,
                    onSubmitPair = submitPair,
                )
                HeaderActionButton(
                    label = if (isSyncing) "Pairing…" else "Pair Device",
                    style = RocheButtonStyle.PRIMARY,
                    enabled = pairingCode.isNotBlank() && !isSyncing,
                    onClick = submitPair,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PairingCodeInputField(
    pairingCode: String,
    revealPairingCode: Boolean,
    onPairingCodeChange: (String) -> Unit,
    onToggleRevealPairingCode: () -> Unit,
    onSubmitPair: () -> Unit,
) {
    val colors = LocalRocheColors.current
    OutlinedTextField(
        value = pairingCode,
        onValueChange = onPairingCodeChange,
        label = { Text("Pairing Code (from roche pair)") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onSubmitPair() }),
        visualTransformation =
            if (revealPairingCode) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            TextButton(
                onClick = onToggleRevealPairingCode,
                modifier = Modifier.testTag("toggle_pairing_code_visibility_btn"),
            ) {
                Text(
                    text = if (revealPairingCode) "Hide" else "Show",
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("pairing_code_input"),
    )
}

@Composable
@Suppress("LongParameterList")
internal fun ServerManualCredentialsCard(
    serverUrl: String,
    bearerToken: String,
    hasSavedBearerToken: Boolean,
    tokenEdited: Boolean,
    revealToken: Boolean,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onToggleRevealToken: () -> Unit,
    onSaveClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Manual Endpoint & Credentials")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("Roche Server URL (https://roche.<tailnet>.ts.net)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                )
                CleartextServerWarning(serverUrl = serverUrl)
                BearerTokenInputField(
                    bearerToken = bearerToken,
                    hasSavedBearerToken = hasSavedBearerToken,
                    tokenEdited = tokenEdited,
                    revealToken = revealToken,
                    onBearerTokenChange = onBearerTokenChange,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderActionButton(
                        label = if (revealToken) "Hide Token" else "Reveal Token",
                        onClick = onToggleRevealToken,
                    )
                    HeaderActionButton(
                        label = "Save Settings",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = onSaveClick,
                        modifier = Modifier.testTag("save_server_settings_btn"),
                    )
                }
                Text(
                    text =
                        "Changing server host automatically clears any previous token unless you enter a new one.",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CleartextServerWarning(serverUrl: String) {
    if (!isCleartextRemoteServerInput(serverUrl)) return
    val colors = LocalRocheColors.current
    Text(
        text =
            "Cleartext HTTP is disabled. Run 'roche serve' and tap " +
                "'Discover on Tailnet' (or switch URL to https://...).",
        color = colors.accentEcru,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun BearerTokenInputField(
    bearerToken: String,
    hasSavedBearerToken: Boolean,
    tokenEdited: Boolean,
    revealToken: Boolean,
    onBearerTokenChange: (String) -> Unit,
) {
    val placeholderText =
        if (hasSavedBearerToken && !tokenEdited && bearerToken.isEmpty()) {
            "Saved token (tap Reveal to inspect or type to replace)"
        } else {
            null
        }
    OutlinedTextField(
        value = bearerToken,
        onValueChange = onBearerTokenChange,
        label = { Text("Bearer Token") },
        placeholder = placeholderText?.let { text -> { Text(text, fontSize = 12.sp) } },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
        visualTransformation =
            if (revealToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        modifier = Modifier.fillMaxWidth().testTag("bearer_token_input"),
    )
}

internal data class ServerCredentialSyncResult(
    val lastSavedBearerToken: String,
    val bearerToken: String,
    val pairingCode: String,
)

internal fun syncSavedBearerTokenChange(
    savedBearerToken: String,
    lastSavedBearerToken: String,
    currentBearerToken: String,
    currentPairingCode: String,
): ServerCredentialSyncResult {
    if (savedBearerToken == lastSavedBearerToken) {
        return ServerCredentialSyncResult(
            lastSavedBearerToken = lastSavedBearerToken,
            bearerToken = currentBearerToken,
            pairingCode = currentPairingCode,
        )
    }
    val nextBearerToken =
        if (currentBearerToken.trim() != savedBearerToken) {
            savedBearerToken
        } else {
            currentBearerToken
        }
    val nextPairingCode = if (savedBearerToken.isNotBlank()) "" else currentPairingCode
    return ServerCredentialSyncResult(
        lastSavedBearerToken = savedBearerToken,
        bearerToken = nextBearerToken,
        pairingCode = nextPairingCode,
    )
}

internal fun isCleartextRemoteServerInput(
    rawServerUrl: String,
    allowCleartextLoopback: Boolean = BuildConfig.DEBUG,
): Boolean {
    val normalized = normalizeServerUrl(rawServerUrl)
    if (!normalized.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { URI(normalized).host?.lowercase() }.getOrNull().orEmpty()
    return host.isNotEmpty() && !(allowCleartextLoopback && isLoopbackHost(host))
}
