package com.grayvines.roche

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
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
        composeRule.setContent {
            RocheTheme {
                MainScreen(
                    state =
                        MainUiState(visibleEntries = listOf(sample), allEntries = listOf(sample)),
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
        composeRule.onNodeWithTag("bottom_tab_settings").assertExists()
        composeRule.onNodeWithText(sample.title).assertExists()
        composeRule.onNodeWithTag("star_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("archive_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("save_fab").assertExists()
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
