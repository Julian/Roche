package com.grayvines.roche

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.ui.MainScreen
import com.grayvines.roche.ui.MainUiState
import com.grayvines.roche.ui.theme.RocheTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Runs Google's Accessibility Test Framework (ATF) checks—including rendered text/icon contrast,
 * minimum 48dp touch targets, and content descriptions—against populated [MainScreen] tabs and
 * overlays across dark mode, light mode, and large font scaling (1.5x and 2.0x).
 *
 * Note: [enableAccessibilityChecks] throws on ATF ERROR findings. Every test asserts expected
 * visible content before auditing so an unintended empty fallback state cannot silently pass.
 */
class AccessibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Before
    fun setupAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun inboxPassesAccessibilityChecksInDarkAndLightThemes() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.DARK,
        )
    }

    @Test
    fun inboxPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun inboxPassesAccessibilityChecksAtLargeFontScales() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.DARK,
            fontScale = 1.5f,
        )
    }

    @Test
    fun inboxPassesAccessibilityChecksAtMaxFontScaleInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.LIGHT,
            fontScale = 2.0f,
        )
    }

    @Test
    fun homePassesAccessibilityChecksInDarkTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.HOME, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.DARK,
            scrollToTagAndAudit = "home_widget_recent_diary",
        )
    }

    @Test
    fun homePassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.HOME, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.LIGHT,
            scrollToTagAndAudit = "home_widget_recent_diary",
        )
    }

    @Test
    fun feedsPassesAccessibilityChecksInDarkTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.FEEDS, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_FEED_TITLE,
            themeMode = ThemeMode.DARK,
        )
    }

    @Test
    fun feedsPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.FEEDS, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = SAMPLE_FEED_TITLE,
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun starredPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.STARRED, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun offlinePassesAccessibilityChecksInDarkTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.OFFLINE, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.DARK,
        )
    }

    @Test
    fun queuePassesAccessibilityChecksInDarkTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.QUEUE, themeMode = ThemeMode.DARK),
            expectedVisibleText = SAMPLE_PODCAST_TITLE,
            themeMode = ThemeMode.DARK,
        )
    }

    @Test
    fun listsPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.LISTS, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = "Lists",
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun settingsPassesAccessibilityChecksInDarkTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.SETTINGS, themeMode = ThemeMode.DARK),
            expectedVisibleText = "Settings",
            themeMode = ThemeMode.DARK,
        )
    }

    @Test
    fun settingsPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state = populatedState(activeTab = AppTab.SETTINGS, themeMode = ThemeMode.LIGHT),
            expectedVisibleText = "Settings",
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun readerPassesAccessibilityChecksInLightTheme() {
        renderAndAudit(
            state =
                populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.LIGHT)
                    .copy(selectedReaderEntryId = SAMPLE_ARTICLE_ID),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.LIGHT,
        )
    }

    @Test
    fun searchOverlayPassesAccessibilityChecksInDarkTheme() {
        val base = populatedState(activeTab = AppTab.INBOX, themeMode = ThemeMode.DARK)
        renderAndAudit(
            state =
                base.copy(
                    isSearchOpen = true,
                    searchQuery = "Accessible",
                    searchResults = base.allEntries.take(1),
                ),
            expectedVisibleText = SAMPLE_ARTICLE_TITLE,
            themeMode = ThemeMode.DARK,
        )
    }

    private fun renderAndAudit(
        state: MainUiState,
        expectedVisibleText: String,
        themeMode: ThemeMode,
        fontScale: Float = 1.0f,
        scrollToTagAndAudit: String? = null,
    ) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = fontScale)
            ) {
                RocheTheme(themeMode = themeMode) {
                    MainScreen(
                        state = state,
                        onAction = {},
                        onShareEntry = {},
                        onOpenExternalUrl = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithText(expectedVisibleText, substring = true, ignoreCase = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.onRoot().tryPerformAccessibilityChecks()
        if (scrollToTagAndAudit != null) {
            composeRule.onNodeWithTag(scrollToTagAndAudit).performScrollTo().assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }

    private fun populatedState(activeTab: AppTab, themeMode: ThemeMode): MainUiState {
        val article = sampleArticleEntry()
        val archived = sampleArchivedEntry()
        val podcast = samplePodcastEntry()
        val allEntries = listOf(article, archived, podcast)
        val feeds = listOf(sampleActiveFeed(), sampleArchivedFeed())
        val tags = listOf(TagItem(id = "tag-1", name = "accessibility", unreadCount = 2))
        return MainUiState(
            activeTab = activeTab,
            visibleEntries = allEntries,
            allEntries = allEntries,
            feeds = feeds,
            tags = tags,
            settings =
                Settings(
                    themeMode = themeMode,
                    queueItemIds = listOf(podcast.id),
                ),
        )
    }

    private fun sampleArticleEntry(): InboxEntry =
        InboxEntry(
            id = SAMPLE_ARTICLE_ID,
            feedId = SAMPLE_FEED_ID,
            feedTitle = SAMPLE_FEED_TITLE,
            title = SAMPLE_ARTICLE_TITLE,
            url = "https://example.com/accessible-reading",
            author = "Ada Lovelace",
            summary = "Practical guidelines for color contrast, typography, and touch targets.",
            contentText = null,
            contentHtml =
                "<h2>Contrast Ratios</h2><p>Ensure readable foreground and background pairs.</p>",
            contentType = ContentType.ARTICLE,
            status = ItemStatus.UNREAD,
            isFavorite = true,
            isCachedOffline = true,
            publishedAt = "18m",
            createdAt = "2026-03-29T10:00:00Z",
            readProgress = 0.25,
            thumbnailMediaId = null,
            duration = null,
            tags = listOf("accessibility"),
        )

    private fun sampleArchivedEntry(): InboxEntry =
        InboxEntry(
            id = "entry-a11y-archived",
            feedId = SAMPLE_FEED_ID,
            feedTitle = SAMPLE_FEED_TITLE,
            title = "Archived Typography Notes",
            url = "https://example.com/archived-typography",
            author = "Robert Bringhurst",
            summary = "Archived items must still preserve readable WCAG AA contrast.",
            contentText = null,
            contentHtml = "<p>Archived items preserve contrast without low-alpha opacity.</p>",
            contentType = ContentType.ARTICLE,
            status = ItemStatus.ARCHIVED,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2d",
            createdAt = "2026-03-27T10:00:00Z",
            readProgress = 1.0,
            thumbnailMediaId = null,
            duration = null,
            tags = listOf("accessibility"),
        )

    private fun samplePodcastEntry(): InboxEntry =
        InboxEntry(
            id = "entry-a11y-podcast",
            feedId = "feed-a11y-podcast",
            feedTitle = "Design Systems Podcast",
            title = SAMPLE_PODCAST_TITLE,
            url = "https://example.com/podcast/inclusive-mobile",
            author = "Design Team",
            summary = "Discussing TalkBack navigation and minimum touch target ergonomics.",
            contentText = null,
            contentHtml = "<p>Episode show notes.</p>",
            contentType = ContentType.AUDIO,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "1h",
            createdAt = "2026-03-29T09:00:00Z",
            readProgress = 0.15,
            thumbnailMediaId = null,
            duration = "30:00",
            tags = emptyList(),
        )

    private fun sampleActiveFeed(): FeedItem =
        FeedItem(
            id = SAMPLE_FEED_ID,
            title = SAMPLE_FEED_TITLE,
            url = "https://example.com/rss.xml",
            siteUrl = "https://example.com",
            feedType = FeedType.RSS,
            unreadCount = 4,
            isActive = true,
        )

    private fun sampleArchivedFeed(): FeedItem =
        FeedItem(
            id = "feed-a11y-archived",
            title = "Paused Design Digest",
            url = "https://paused.example.com/rss.xml",
            siteUrl = "https://paused.example.com",
            feedType = FeedType.RSS,
            unreadCount = 0,
            isActive = false,
        )

    companion object {
        private const val SAMPLE_FEED_ID = "feed-a11y-1"
        private const val SAMPLE_FEED_TITLE = "Editorial Weekly"
        private const val SAMPLE_ARTICLE_ID = "entry-a11y-1"
        private const val SAMPLE_ARTICLE_TITLE = "Designing Accessible Reading Interfaces"
        private const val SAMPLE_PODCAST_TITLE = "Inclusive Mobile Ergonomics"
    }
}
