package com.grayvines.roche

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.grayvines.roche.data.local.SeedData
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
        composeRule.onNodeWithText(sample.title).assertExists()
        composeRule.onNodeWithTag("star_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("archive_btn_${sample.id}").assertExists()
        composeRule.onNodeWithTag("save_fab").assertExists()
    }

    private fun createSampleEntry(): InboxEntry {
        val entity = SeedData.items.first()
        return InboxEntry(
            id = entity.id,
            feedId = entity.feedId,
            feedTitle = entity.feedTitle,
            title = entity.title,
            url = entity.url,
            author = entity.author,
            summary = entity.summary,
            contentText = entity.contentText,
            contentHtml = entity.contentHtml,
            contentType = ContentType.fromWireValue(entity.contentType),
            status = ItemStatus.fromWireValue(entity.status),
            isFavorite = entity.isFavorite,
            isCachedOffline = entity.isCachedOffline,
            publishedAt = entity.publishedAt,
            createdAt = entity.createdAt,
            readProgress = entity.readProgress,
            thumbnailMediaId = entity.thumbnailMediaId,
            duration = entity.duration,
            tags = listOf("llm-inference"),
        )
    }
}
