package com.grayvines.roche.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun SearchTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        SearchHeaderSection(state = state, onAction = onAction)
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (state.searchResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No entries match \"${state.searchQuery}\".",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("search_results_list"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(items = state.searchResults, key = { it.id }) { entry ->
                    InboxEntryRowItem(
                        entry = entry,
                        ultraDense = state.settings.ultraDenseRows,
                        swipeToArchiveEnabled = state.settings.swipeToArchive,
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                    )
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun SearchHeaderSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RocheTopHeader(
            title = "Search",
            subtitle = "${state.searchResults.size} matches",
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onAction(MainAction.UpdateSearchQuery(it)) },
                placeholder = { Text("Search titles, authors, feeds, #tags…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SearchQuickTagStrip(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SearchQuickTagStrip(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.searchQuery.isNotBlank()) {
            FilterChipPill(
                label = "Clear ✕",
                selected = true,
                onClick = { onAction(MainAction.UpdateSearchQuery("")) },
            )
        }
        state.tags.forEach { tag ->
            val tagToken = "#${tag.name}"
            val isSelected = state.searchQuery.trim().equals(tagToken, ignoreCase = true)
            FilterChipPill(
                label = tagToken,
                selected = isSelected,
                onClick = {
                    val nextQuery = if (isSelected) "" else tagToken
                    onAction(MainAction.UpdateSearchQuery(nextQuery))
                },
            )
        }
    }
}

@Composable
fun FeedsAndTagsTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Feeds & Tags",
            subtitle = "${state.feeds.size} feeds",
        ) {
            HeaderActionButton(label = "Sync", onClick = { onAction(MainAction.SyncNow) })
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            TagsSection(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(20.dp))
            SubscribedFeedsSection(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
internal fun TagsSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Text(
        text = "TAGS",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (state.tags.isEmpty()) {
        Text(
            text = "No tags yet. Add tags when saving a link or via an item's menu.",
            color = colors.textMuted,
            fontSize = 12.5.sp,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.tags.forEach { tag ->
                FilterChipPill(
                    label = "#${tag.name} (${tag.unreadCount})",
                    selected = state.selectedTag == tag.name,
                    onClick = { onAction(MainAction.FilterByTag(tag.name)) },
                )
            }
        }
    }
}

@Composable
internal fun SubscribedFeedsSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Text(
        text = "SUBSCRIBED FEEDS",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (state.feeds.isEmpty()) {
        Text(
            text = "No subscribed feeds yet. Tap + to subscribe to RSS, arXiv, or YouTube.",
            color = colors.textMuted,
            fontSize = 12.5.sp,
        )
    } else {
        state.feeds.forEach { feed ->
            SubscribedFeedRow(
                feed = feed,
                onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

@Composable
private fun SubscribedFeedRow(feed: FeedItem, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${feed.feedType.wireValue.uppercase()} · ${feed.url}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = feed.unreadCount.toString(),
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun OfflineTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    val offlineEntries = state.offlineEntries

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Offline Vault",
            subtitle = "${offlineEntries.size} cached",
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (offlineEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No offline entries cached on this device.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                items(items = offlineEntries, key = { it.id }) { entry ->
                    OfflineVaultEntryRow(entry = entry, onAction = onAction)
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun OfflineVaultEntryRow(
    entry: com.grayvines.roche.model.InboxEntry,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onAction(MainAction.OpenReader(entry.id)) }
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    "${entry.feedTitle} · ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        HeaderActionButton(
            label = "Evict",
            onClick = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
        )
    }
}

@Composable
fun SettingsTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(title = "Settings")
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppearanceTogglesSection(state = state, onAction = onAction)
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            ServerConfigSection(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearanceTogglesSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    SettingToggleRow(
        title = "Paper Light Mode",
        subtitle = "Timberwolf editorial paper (#E4DFDA) vs warm charcoal",
        checked = state.settings.themeMode == ThemeMode.LIGHT,
        onCheckedChange = { light ->
            val mode = if (light) ThemeMode.LIGHT else ThemeMode.DARK
            onAction(MainAction.SetThemeMode(mode))
        },
    )
    SettingToggleRow(
        title = "Serif Headlines",
        subtitle = "Editorial serif headlines vs clean sans-serif",
        checked = state.settings.headlineFont == HeadlineFont.SERIF,
        onCheckedChange = { serif ->
            val font = if (serif) HeadlineFont.SERIF else HeadlineFont.SANS
            onAction(MainAction.SetHeadlineFont(font))
        },
    )
    SettingToggleRow(
        title = "Ultra-Dense 1-Line Rows",
        subtitle = "Hide abstract snippet for high-volume triage",
        checked = state.settings.ultraDenseRows,
        onCheckedChange = { onAction(MainAction.SetUltraDenseRows(it)) },
    )
    SettingToggleRow(
        title = "Single Swipe-Left to Archive",
        subtitle = "Optional swipe gesture alongside the archive button",
        checked = state.settings.swipeToArchive,
        onCheckedChange = { onAction(MainAction.SetSwipeToArchive(it)) },
    )
}

@Composable
private fun ServerConfigSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    var serverUrl by
        rememberSaveable(state.settings.serverUrl) { mutableStateOf(state.settings.serverUrl) }
    var bearerToken by
        rememberSaveable(state.settings.bearerToken) { mutableStateOf(state.settings.bearerToken) }
    var pairingCode by rememberSaveable { mutableStateOf("") }

    Text(
        text = "SELF-HOSTED SERVER",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = { Text("Roche Server URL (Tailscale / LAN)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
    )
    PairingCodeRow(
        pairingCode = pairingCode,
        onPairingCodeChange = { pairingCode = it },
        onPairClick = { onAction(MainAction.PairWithServer(serverUrl, pairingCode)) },
    )
    OutlinedTextField(
        value = bearerToken,
        onValueChange = { bearerToken = it },
        label = { Text("Bearer Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("bearer_token_input"),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "Save Settings",
            style = RocheButtonStyle.PRIMARY,
            onClick = { onAction(MainAction.UpdateServerConfig(serverUrl, bearerToken)) },
        )
        HeaderActionButton(
            label = "Discover on Tailnet",
            onClick = { onAction(MainAction.DiscoverServer) },
        )
        HeaderActionButton(label = "Sync Now", onClick = { onAction(MainAction.SyncNow) })
    }
}

@Composable
private fun PairingCodeRow(
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onPairClick: () -> Unit,
) {
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
            modifier = Modifier.weight(1f).testTag("pairing_code_input"),
        )
        HeaderActionButton(
            label = "Pair Device",
            style = RocheButtonStyle.PRIMARY,
            onClick = onPairClick,
        )
    }
}
