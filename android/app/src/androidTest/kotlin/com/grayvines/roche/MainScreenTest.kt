@file:Suppress("LongMethod")

package com.grayvines.roche

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainUiState
import com.grayvines.roche.ui.theme.RocheTheme
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersInboxHeaderPrimaryRowActionsAndFab() {
        val sample = createSampleEntry()
        val tag = TagItem(id = "tag-1", name = "llm-inference", unreadCount = 1)
        val unpinnedSettingsNav =
            listOf(AppTab.FEEDS, AppTab.STARRED, AppTab.SEARCH, AppTab.OFFLINE)
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            visibleEntries = listOf(sample),
                            allEntries = listOf(sample),
                            tags = listOf(tag),
                            settings =
                                com.grayvines.roche.data.settings.Settings(
                                    bottomNavTabs = unpinnedSettingsNav
                                ),
                        ),
                    onAction = {},
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithText("Roche").assertExists()
        composeRule.onNodeWithText("Search").assertExists()
        composeRule.onNodeWithTag("bottom_nav_bar").assertExists()
        composeRule.onNodeWithTag("bottom_tab_inbox").assertExists()
        composeRule.onNodeWithTag("bottom_tab_search").assertExists()
        composeRule.onNodeWithTag("bottom_tab_feeds").assertExists()
        composeRule.onNodeWithTag("bottom_tab_starred").assertExists()
        composeRule.onNodeWithTag("bottom_tab_offline").assertExists()
        composeRule.onNodeWithTag("inbox_spare_settings_btn").assertExists()
        composeRule.onNodeWithText(sample.title).assertExists()
        composeRule.onNodeWithTag("star_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("archive_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("save_fab").assertExists()

        composeRule.onNodeWithTag("inbox_tag_filter_drawer").assertDoesNotExist()
        composeRule.onNodeWithTag("inbox_tag_filter_btn").performClick()
        composeRule.onNodeWithTag("inbox_tag_filter_drawer").assertExists()
        composeRule.onNodeWithText("#llm-inference (1)").assertExists()
    }

    @Test
    fun feedsTabUsesCleanHeaderAndHidesTagsBehindFilterButton() {
        val feed =
            FeedItem(
                id = "feed-1",
                title = "arXiv cs.LG",
                url = "https://export.arxiv.org/rss/cs.LG",
                siteUrl = "https://arxiv.org",
                feedType = FeedType.ARXIV,
                unreadCount = 3,
            )
        val archivedFeed =
            FeedItem(
                id = "feed-archived-1",
                title = "Legacy Blog",
                url = "https://legacy.example.com/rss.xml",
                siteUrl = "https://legacy.example.com",
                feedType = FeedType.RSS,
                unreadCount = 1,
                isActive = false,
            )
        val tag = TagItem(id = "tag-1", name = "ml-systems", unreadCount = 2)
        val dispatched = mutableListOf<com.grayvines.roche.ui.MainAction>()

        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.FEEDS,
                            feeds = listOf(feed, archivedFeed),
                            tags = listOf(tag),
                        ),
                    onAction = { dispatched.add(it) },
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithText("Feeds & Tags").assertDoesNotExist()
        composeRule.onNodeWithTag("feeds_filter_drawer").assertDoesNotExist()
        composeRule.onNodeWithTag("feed_row_feed-1").assertExists()
        composeRule.onNodeWithText("arxiv.org").assertExists()
        composeRule.onNodeWithTag("archived_feeds_header").assertExists()
        composeRule.onNodeWithTag("archived_feed_row_feed-archived-1").assertDoesNotExist()

        composeRule.onNodeWithTag("archived_feeds_header").performClick()
        composeRule.onNodeWithTag("archived_feed_row_feed-archived-1").assertExists()
        composeRule.onNodeWithText("legacy.example.com · 1 unread").assertExists()
        composeRule.onNodeWithTag("unarchive_feed_feed-archived-1").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(
                com.grayvines.roche.ui.MainAction.ToggleFeedArchive("feed-archived-1")
            )
        )

        composeRule.onNodeWithTag("feeds_filter_btn").performClick()
        composeRule.onNodeWithTag("feeds_filter_drawer").assertExists()
        composeRule.onNodeWithText("#ml-systems (2)").assertExists()
    }

    @Test
    fun settingsTabNavigatesBetweenHubAndVisualDockAndServerSubpages() {
        val dispatched = mutableListOf<com.grayvines.roche.ui.MainAction>()
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state = MainUiState(activeTab = AppTab.SETTINGS),
                    onAction = { dispatched.add(it) },
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_server_row").assertExists()
        composeRule.onNodeWithTag("settings_nav_row").assertExists()
        composeRule.onNodeWithTag("import_opml_btn").assertExists()
        composeRule.onNodeWithTag("export_opml_btn").assertExists()

        composeRule.onNodeWithTag("settings_nav_row").performClick()
        composeRule.onNodeWithText("Navigation Bar").assertExists()
        composeRule.onNodeWithTag("dock_slot_1").performClick()
        composeRule.onNodeWithTag("dock_candidate_offline").performClick()
        composeRule.onNodeWithTag("reset_bottom_nav_btn").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(
                com.grayvines.roche.ui.MainAction.SetBottomNavSlot(1, AppTab.OFFLINE)
            )
        )
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.ResetBottomNavTabs)
        )
        composeRule.onNodeWithTag("settings_subpage_back_btn").performClick()

        composeRule.onNodeWithTag("settings_server_row").performClick()
        composeRule.onNodeWithText("Server & Pairing").assertExists()
        composeRule.onNodeWithTag("server_url_input").assertExists()
        composeRule.onNodeWithTag("pairing_code_input").assertExists()
        composeRule.onNodeWithTag("bearer_token_input").assertExists()
    }

    @Test
    fun readerTypographyDrawerAndSearchScopeFiltersRenderAndDispatch() {
        val dispatched = mutableListOf<com.grayvines.roche.ui.MainAction>()
        val entry =
            createSampleEntry()
                .copy(contentHtml = "<h2>Architecture</h2><p>Autoregressive decoding details.</p>")
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.SEARCH,
                            searchQuery = "Wait-free",
                            searchResults = listOf(entry),
                            allEntries = listOf(entry),
                            selectedReaderEntryId = entry.id,
                        ),
                    onAction = { dispatched.add(it) },
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("reader_typography_btn").performClick()
        composeRule.onNodeWithTag("reader_typography_drawer").assertExists()
        composeRule.onNodeWithText("§ Architecture").assertExists()
        composeRule.onNodeWithTag("reader_font_larger_btn").performScrollTo().performClick()
        org.junit.Assert.assertTrue(
            dispatched.any { it is com.grayvines.roche.ui.MainAction.SetReaderFontScale }
        )
    }

    @Test
    fun searchScopeStripAndMatchSnippetRenderOnSearchTab() {
        val entry = createSampleEntry()
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.SEARCH,
                            searchQuery = "Wait-free",
                            searchResults = listOf(entry),
                        ),
                    onAction = {},
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("search_scope_papers").assertExists()
        composeRule.onNodeWithTag("search_match_snippet_${entry.id}").assertExists()
        composeRule.onNodeWithTag("search_scope_videos").performScrollTo().performClick()
        composeRule.onNodeWithText("No results in Videos for \"Wait-free\".").assertExists()
    }

    @Test
    fun feedsTabShowsArchivedOnlyEmptyMessageWhenAllFeedsAreArchived() {
        val archivedFeed =
            FeedItem(
                id = "feed-archived-only",
                title = "Archived Journal",
                url = "https://journal.example.com/rss.xml",
                siteUrl = "https://journal.example.com",
                feedType = FeedType.RSS,
                unreadCount = 0,
                isActive = false,
            )

        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.FEEDS,
                            feeds = listOf(archivedFeed),
                        ),
                    onAction = {},
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule
            .onNodeWithText("No active feeds. Expand Archived Feeds below to unarchive.")
            .assertExists()
        composeRule.onNodeWithTag("archived_feeds_header").assertExists()
    }

    @Test
    fun inboxShowsLoadingStateInsteadOfEmptyMessageDuringInitialDbLoad() {
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state = MainUiState(isInitialLoading = true),
                    onAction = {},
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("inbox_loading_state").assertExists()
        composeRule.onNodeWithText("Loading inbox…").assertExists()
        composeRule.onNodeWithText("Loading…").assertExists()
        composeRule.onNodeWithText("Your inbox is empty.").assertDoesNotExist()
    }

    @Test
    fun queueTabMiniPlayerAndFullscreenPodcastPlayerRenderAndDispatchActions() {
        val dispatched = mutableListOf<com.grayvines.roche.ui.MainAction>()
        val audioEntry =
            createSampleEntry()
                .copy(
                    id = "ep-audio-1",
                    feedId = "feed-podcast-1",
                    feedTitle = "Oxide and Friends",
                    title = "Async Rust in Production",
                    contentType = ContentType.AUDIO,
                    audioUrl = "https://cdn.oxide.computer/ep1.mp3",
                    duration = "01:12:00",
                    isCachedOffline = false,
                )
        val articleEntry =
            createSampleEntry()
                .copy(
                    id = "art-queued-1",
                    title = "Wait-Free Ring Buffers",
                    contentType = ContentType.ARTICLE,
                )

        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.QUEUE,
                            allEntries = listOf(audioEntry, articleEntry),
                            visibleEntries = listOf(audioEntry, articleEntry),
                            settings =
                                com.grayvines.roche.data.settings.Settings(
                                    queueItemIds = listOf(audioEntry.id, articleEntry.id)
                                ),
                            playerState =
                                com.grayvines.roche.ui.PodcastPlayerState(
                                    currentEntryId = audioEntry.id,
                                    isPlaying = true,
                                    positionMs = 120_000L,
                                    durationMs = 3_600_000L,
                                    playbackSpeed = 2.65f,
                                    isFullscreenOpen = true,
                                ),
                        ),
                    onAction = { dispatched.add(it) },
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        // Fullscreen player renders album art (tap to toggle play/pause), 5 transport buttons, and
        // top bar
        composeRule.onNodeWithTag("fullscreen_podcast_player").assertExists()
        composeRule.onNodeWithTag("player_album_artwork").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.TogglePlayPause)
        )
        composeRule.onNodeWithTag("player_speed_btn").assertExists()
        composeRule.onNodeWithTag("player_rewind_btn").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.SkipPlayerBySeconds(-15))
        )
        composeRule.onNodeWithTag("player_ff_btn").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.SkipPlayerBySeconds(30))
        )
        composeRule.onNodeWithTag("player_skip_next_btn").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.SkipToNextQueuedAudio)
        )
        composeRule.onNodeWithTag("player_star_btn").performClick()
        org.junit.Assert.assertTrue(
            dispatched.contains(com.grayvines.roche.ui.MainAction.ToggleStar(audioEntry.id))
        )
    }

    @Test
    fun queueTabListeningHistoryModeDisplaysPlayedEpisodesAndTimestamps() {
        val playedEpisode =
            createSampleEntry()
                .copy(
                    id = "ep-history-1",
                    feedId = "feed-podcast-1",
                    feedTitle = "Oxide and Friends",
                    title = "Ep 42: Compiler Backends",
                    contentType = ContentType.AUDIO,
                    audioUrl = "https://cdn.oxide.computer/ep42.mp3",
                    duration = "58:10",
                    status = ItemStatus.ARCHIVED,
                    readProgress = 1.0,
                    readAt = java.time.Instant.now().toString(),
                )

        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(
                            activeTab = AppTab.QUEUE,
                            allEntries = listOf(playedEpisode),
                            visibleEntries = emptyList(),
                        ),
                    onAction = {},
                    onShareEntry = {},
                    onOpenExternalUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("queue_mode_up_next").assertExists()
        composeRule.onNodeWithTag("queue_mode_history").performClick()
        composeRule.onNodeWithTag("listening_history_list").assertExists()
        composeRule.onNodeWithTag("history_row_ep-history-1").assertExists()
        composeRule
            .onNodeWithTag("history_timestamp_ep-history-1", useUnmergedTree = true)
            .assertExists()
    }

    private fun createSampleEntry(): InboxEntry =
        InboxEntry(
            id = "item-1",
            feedId = "feed-arxiv-cs-lg",
            feedTitle = "arXiv cs.LG",
            title = "Speculative Decoding with Quantized Draft Models on Edge NPU Targets",
            url = "https://arxiv.org/abs/2603.11842",
            author = "Chen, Alvarez, K. Sato",
            summary = "Wait-free verification schedules reduce token latency.",
            contentText = null,
            contentHtml = "<p>Autoregressive decoding from large language models.</p>",
            contentType = ContentType.PDF,
            status = ItemStatus.UNREAD,
            isFavorite = true,
            isCachedOffline = true,
            publishedAt = "18m",
            createdAt = "2026-03-29T10:00:00Z",
            readProgress = 0.35,
            thumbnailMediaId = "media-paper-1",
            duration = null,
            tags = listOf("llm-inference"),
        )
}
