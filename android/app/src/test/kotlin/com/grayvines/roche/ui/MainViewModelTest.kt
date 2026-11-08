package com.grayvines.roche.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @TempDir lateinit var tempDir: File

    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "vm_test_settings.preferences_pb") }
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
        viewModel = MainViewModel(repository = repository, settingsRepository = settings)
    }

    @AfterEach
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `OpenAddSheet populates prefilled url and title and CloseAddSheet resets both`() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

        viewModel.dispatch(MainAction.OpenContextSheet("item-1"))
        viewModel.dispatch(
            MainAction.OpenAddSheet(
                prefilledUrl = "https://example.com/shared",
                prefilledTitle = "Shared Headline",
            )
        )

        val opened = viewModel.uiState.first { it.isAddSheetOpen }
        assertTrue(opened.isAddSheetOpen)
        assertEquals("https://example.com/shared", opened.prefilledShareUrl)
        assertEquals("Shared Headline", opened.prefilledShareTitle)
        assertNull(opened.contextSheetEntryId)

        viewModel.dispatch(MainAction.CloseAddSheet)
        val closed = viewModel.uiState.first { !it.isAddSheetOpen }
        assertFalse(closed.isAddSheetOpen)
        assertEquals("", closed.prefilledShareUrl)
        assertEquals("", closed.prefilledShareTitle)

        collectJob.cancel()
    }

    @Test
    fun `SaveUrlOrFeed saves entry with prefilled title and clears reader and add sheet state`() =
        runTest {
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            viewModel.dispatch(MainAction.OpenReader("item-1"))
            viewModel.dispatch(
                MainAction.OpenAddSheet(
                    prefilledUrl = "https://example.com/shared-article",
                    prefilledTitle = "Shared Article Title",
                )
            )
            viewModel.dispatch(
                MainAction.SaveUrlOrFeed(
                    url = "https://example.com/shared-article",
                    titleOverride = "Shared Article Title",
                    tagCsv = "systems, reading",
                )
            )

            val state = viewModel.uiState.first { it.statusBanner != null }
            assertFalse(state.isAddSheetOpen)
            assertEquals("", state.prefilledShareUrl)
            assertEquals("", state.prefilledShareTitle)
            assertNull(state.selectedReaderEntryId)

            val savedEntry =
                repository.snapshot
                    .first { snap ->
                        snap.entries.any {
                            it.url == "https://example.com/shared-article" && it.tags.isNotEmpty()
                        }
                    }
                    .entries
                    .first { it.url == "https://example.com/shared-article" }
            assertEquals("Shared Article Title", savedEntry.title)
            assertEquals(listOf("reading", "systems"), savedEntry.tags)

            collectJob.cancel()
        }

    @Test
    fun `UpdateReadProgress persists clamped progress to repository`() = runTest {
        database.dao().upsertItems(SeedData.items)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

        viewModel.dispatch(MainAction.UpdateReadProgress(entryId = "item-1", progress = 0.65))

        val updated =
            repository.snapshot
                .first { snap ->
                    snap.entries.any { it.id == "item-1" && abs(it.readProgress - 0.65) < 1e-6 }
                }
                .entries
                .first { it.id == "item-1" }
        assertEquals(0.65, updated.readProgress, 1e-6)

        viewModel.dispatch(MainAction.UpdateReadProgress(entryId = "item-1", progress = 1.4))

        val clamped =
            repository.snapshot
                .first { snap ->
                    snap.entries.any { it.id == "item-1" && abs(it.readProgress - 1.0) < 1e-6 }
                }
                .entries
                .first { it.id == "item-1" }
        assertEquals(1.0, clamped.readProgress, 1e-6)

        collectJob.cancel()
    }

    @Test
    internal fun `DeleteEntry DeleteFeed RefreshFeed and SyncNow update UI state and isSyncing`() =
        runTest {
            database.dao().upsertFeeds(SeedData.feeds)
            database.dao().upsertItems(SeedData.items)
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            // Select a feed and open reader + context sheet for item-1
            viewModel.dispatch(MainAction.FilterByFeed("feed-arxiv-cs-lg"))
            viewModel.dispatch(MainAction.OpenReader("item-1"))
            viewModel.dispatch(MainAction.OpenContextSheet("item-1"))
            val beforeDelete =
                viewModel.uiState.first { state ->
                    state.allEntries.isNotEmpty() && state.selectedReaderEntryId == "item-1"
                }
            assertEquals("item-1", beforeDelete.selectedReaderEntryId)

            // Archive sets undoEvent, and DeleteEntry clears undoEvent while setting statusBanner
            val item2 = beforeDelete.allEntries.first { it.id == "item-2" }
            viewModel.dispatch(MainAction.ToggleArchive(item2))
            val afterArchive = viewModel.uiState.first { it.undoEvent != null }
            assertEquals("item-2", afterArchive.undoEvent?.itemId)
            assertNull(afterArchive.statusBanner)

            // DeleteEntry closes reader & context sheet, clears undoEvent, and removes entry
            viewModel.dispatch(MainAction.DeleteEntry("item-1"))
            val afterDeleteEntry =
                viewModel.uiState.first { state ->
                    state.allEntries.none { it.id == "item-1" } &&
                        state.statusBanner?.startsWith("Deleted") == true
                }
            assertNull(afterDeleteEntry.selectedReaderEntryId)
            assertNull(afterDeleteEntry.contextSheetEntryId)
            assertNull(afterDeleteEntry.undoEvent)
            assertTrue(afterDeleteEntry.statusBanner?.startsWith("Deleted") == true)

            // DeleteFeed clears selectedFeedId when that feed was selected
            viewModel.dispatch(MainAction.DeleteFeed("feed-arxiv-cs-lg"))
            val afterDeleteFeed =
                viewModel.uiState.first { state ->
                    state.feeds.none { it.id == "feed-arxiv-cs-lg" } &&
                        state.statusBanner?.startsWith("Unsubscribed") == true
                }
            assertNull(afterDeleteFeed.selectedFeedId)
            assertTrue(afterDeleteFeed.statusBanner?.startsWith("Unsubscribed") == true)

            // StartupSyncIfConfigured is silent when unconfigured or offline, and resets isSyncing
            viewModel.dispatch(MainAction.DismissMessages)
            val dismissed = viewModel.uiState.first { it.statusBanner == null }
            assertFalse(dismissed.isSyncing)
            viewModel.triggerStartupSyncIfConfigured()
            assertFalse(viewModel.uiState.value.isSyncing)
            assertNull(viewModel.uiState.value.statusBanner)

            // SyncNow sets statusBanner and resets isSyncing to false in finally block
            viewModel.dispatch(MainAction.SyncNow)
            val afterSync =
                viewModel.uiState.first { it.statusBanner?.startsWith("Sync offline") == true }
            assertFalse(afterSync.isSyncing)
            assertTrue(afterSync.statusBanner?.startsWith("Sync offline") == true)

            // RefreshFeed sets statusBanner and resets isSyncing to false in finally block
            viewModel.dispatch(MainAction.DismissMessages)
            viewModel.uiState.first { it.statusBanner == null }
            viewModel.dispatch(MainAction.RefreshFeed("feed-2"))
            val afterRefresh =
                viewModel.uiState.first { it.statusBanner?.startsWith("Refresh failed") == true }
            assertFalse(afterRefresh.isSyncing)

            collectJob.cancel()
        }

    @Test
    internal fun `OpenReader marks entry read SelectTab Inbox clears feed scope and SetBottomNavSlot swaps slots`() =
        runTest {
            database.dao().upsertFeeds(SeedData.feeds)
            database.dao().upsertItems(SeedData.items)
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            // Scope Inbox to a feed, then tap Inbox tab to clear the feed filter
            viewModel.dispatch(MainAction.FilterByFeed("feed-arxiv-cs-lg"))
            val scoped = viewModel.uiState.first { it.selectedFeedId == "feed-arxiv-cs-lg" }
            assertEquals("feed-arxiv-cs-lg", scoped.selectedFeedId)

            viewModel.dispatch(MainAction.SelectTab(com.grayvines.roche.model.AppTab.INBOX))
            val unscoped = viewModel.uiState.first { it.selectedFeedId == null }
            assertNull(unscoped.selectedFeedId)
            assertNull(unscoped.selectedTag)

            // OpenReader marks item-1 as read while keeping it in InboxFilter.ALL
            viewModel.dispatch(MainAction.OpenReader("item-1"))
            val afterRead =
                viewModel.uiState.first { state ->
                    state.allEntries.any { it.id == "item-1" && it.isRead && !it.isUnread }
                }
            assertTrue(afterRead.visibleEntries.any { it.id == "item-1" })
            assertTrue(!afterRead.allEntries.first { it.id == "item-1" }.readAt.isNullOrBlank())

            // ToggleReadState marks item-1 back as unread
            viewModel.dispatch(MainAction.ToggleReadState("item-1"))
            val afterUnread =
                viewModel.uiState.first { state ->
                    state.allEntries.any { it.id == "item-1" && !it.isRead && it.isUnread }
                }
            assertEquals("Marked as unread", afterUnread.statusBanner)
            assertNull(afterUnread.allEntries.first { it.id == "item-1" }.readAt)

            // Customize bottom navigation slot 1 to OFFLINE
            viewModel.dispatch(
                MainAction.SetBottomNavSlot(
                    slotIndex = 1,
                    tab = com.grayvines.roche.model.AppTab.OFFLINE,
                )
            )
            val afterNavUpdate =
                viewModel.uiState.first { state ->
                    state.settings.bottomNavTabs[1] == com.grayvines.roche.model.AppTab.OFFLINE
                }
            assertEquals(
                listOf(
                    com.grayvines.roche.model.AppTab.FEEDS,
                    com.grayvines.roche.model.AppTab.OFFLINE,
                    com.grayvines.roche.model.AppTab.SEARCH,
                    com.grayvines.roche.model.AppTab.SETTINGS,
                ),
                afterNavUpdate.settings.bottomNavTabs,
            )

            collectJob.cancel()
        }
}
