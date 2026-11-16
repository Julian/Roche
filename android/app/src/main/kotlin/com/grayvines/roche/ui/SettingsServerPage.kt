package com.grayvines.roche.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.isLoopbackHost
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.isCrossPeerRescueError
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.net.URI
import kotlinx.coroutines.delay

@Composable
internal fun SettingsServerPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalRocheColors.current
    var serverUrl by rememberSaveable { mutableStateOf(state.settings.serverUrl) }
    var bearerToken by
        remember(state.settings.bearerToken) {
            mutableStateOf(state.settings.bearerToken)
        }
    var pairingCode by remember { mutableStateOf("") }
    var revealToken by remember { mutableStateOf(false) }

    LaunchedEffect(state.settings.serverUrl) {
        if (normalizeServerUrl(serverUrl) != state.settings.serverUrl) {
            serverUrl = state.settings.serverUrl
        }
    }
    ServerConfigAutoSaveEffect(
        serverUrl = serverUrl,
        bearerToken = bearerToken,
        savedServerUrl = state.settings.serverUrl,
        savedBearerToken = state.settings.bearerToken,
        onAction = onAction,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Server & Pairing",
            navigationAction = {
                HeaderActionButton(
                    label = "←",
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_subpage_back_btn"),
                )
            },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        ServerPageScrollBody(
            state = state,
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            pairingCode = pairingCode,
            revealToken = revealToken,
            onServerUrlChange = { serverUrl = it },
            onBearerTokenChange = { bearerToken = it },
            onPairingCodeChange = { pairingCode = it },
            onToggleRevealToken = { revealToken = !revealToken },
            onAction = onAction,
        )
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ServerPageScrollBody(
    state: MainUiState,
    serverUrl: String,
    bearerToken: String,
    pairingCode: String,
    revealToken: Boolean,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onToggleRevealToken: () -> Unit,
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
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            onAction = onAction,
            onHideKeyboard = { keyboardController?.hide() },
        )
        ServerPairingCard(
            serverUrl = serverUrl,
            pairingCode = pairingCode,
            onPairingCodeChange = onPairingCodeChange,
            onAction = onAction,
            onHideKeyboard = { keyboardController?.hide() },
        )
        ServerManualCredentialsCard(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            revealToken = revealToken,
            onServerUrlChange = onServerUrlChange,
            onBearerTokenChange = onBearerTokenChange,
            onToggleRevealToken = onToggleRevealToken,
            onSaveClick = {
                keyboardController?.hide()
                onAction(MainAction.UpdateServerConfig(serverUrl, bearerToken))
            },
        )
        PeerMeshStatusCard(
            state = state,
            onAction = onAction,
        )
        Spacer(modifier = Modifier.height(24.dp))
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
internal fun ServerConnectionStatusCard(
    serverUrl: String,
    bearerToken: String,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isConfigured = serverUrl.isNotBlank() && bearerToken.isNotBlank()
    val dotColor = if (isConfigured) colors.accentVerdigris else colors.accentEcru
    val statusText = if (isConfigured) "Connected to Tailnet Server" else "Server Not Yet Paired"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Connection Status")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                Text(
                    text = serverUrl.ifBlank { "No server URL configured" },
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeaderActionButton(
                        label = "Sync Now",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = {
                            onHideKeyboard()
                            onAction(MainAction.SyncWithServerConfig(serverUrl, bearerToken))
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
        }
    }
}

@Composable
internal fun ServerPairingCard(
    serverUrl: String,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = onPairingCodeChange,
                        label = { Text("Pairing Code (from roche pair)") },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                        modifier = Modifier.weight(1f).testTag("pairing_code_input"),
                    )
                    HeaderActionButton(
                        label = "Pair Device",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = {
                            onHideKeyboard()
                            onAction(MainAction.PairWithServer(serverUrl, pairingCode))
                        },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ServerManualCredentialsCard(
    serverUrl: String,
    bearerToken: String,
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
                    )
                }
                Text(
                    text = "Changes auto-save after 350ms.",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun ServerConfigAutoSaveEffect(
    serverUrl: String,
    bearerToken: String,
    savedServerUrl: String,
    savedBearerToken: String,
    onAction: (MainAction) -> Unit,
) {
    LaunchedEffect(serverUrl, bearerToken) {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        val isIncompleteUrl = serverUrl.isNotBlank() && normalizedUrl.isEmpty()
        val trimmedToken = bearerToken.trim()
        val hasChanges = normalizedUrl != savedServerUrl || trimmedToken != savedBearerToken
        if (!isIncompleteUrl && hasChanges) {
            delay(SERVER_CONFIG_AUTOSAVE_DELAY_MS)
            onAction(
                MainAction.UpdateServerConfig(
                    serverUrl = serverUrl,
                    bearerToken = bearerToken,
                    feedback = ConfigSaveFeedback.SILENT,
                )
            )
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
    revealToken: Boolean,
    onBearerTokenChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = bearerToken,
        onValueChange = onBearerTokenChange,
        label = { Text("Bearer Token") },
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

internal fun isCleartextRemoteServerInput(rawServerUrl: String): Boolean {
    val normalized = normalizeServerUrl(rawServerUrl)
    if (!normalized.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { URI(normalized).host?.lowercase() }.getOrNull().orEmpty()
    return host.isNotEmpty() && !isLoopbackHost(host)
}

private const val SERVER_CONFIG_AUTOSAVE_DELAY_MS = 350L
