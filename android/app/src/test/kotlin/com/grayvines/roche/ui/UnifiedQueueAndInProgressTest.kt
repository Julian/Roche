@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedQueueAndInProgressTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestRocheEnv(tempDir, "unified_queue_in_progress_test.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `active started query respects tie-breakers, skips, completions, and re-reads`() = runTest {
        seedActiveStartedFixture()

        val active = env.database.dao().getActiveStartedEntries(50)
        assertEquals(listOf("book-reread", "book-active"), active.map { it.entryId })

        val snap = env.repository.catalog.getSnapshot()
        assertEquals(
            listOf("book-reread", "book-active"),
            snap.activeStartedEntries.map { it.entryId },
        )
        assertTrue(snap.recentDiaryEntries.all { it.status == ConsumptionStatus.CONSUMED })
        assertTrue(snap.recentDiaryEntries.none { it.entryId == "book-active" })
        assertTrue(snap.wantQueues.none { it.collectionId == UP_NEXT_COLLECTION_ID })
    }

    @Test
    fun `unified inProgress merges reader progress with active started and migrates legacy local queue once`() =
        runTest {
            val bookId = seedMixedLegacyQueueFixture()
            val viewModel =
                MainViewModel(repository = env.repository, settingsRepository = env.settings)
            try {
                val collectJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
                val migrated =
                    viewModel.uiState.first { state ->
                        state.settings.migratedNonAudioQueueToWantRead &&
                            "art-prog" in state.catalog.wantToReadEntryIds &&
                            state.inProgressItems.size == 2
                    }
                assertMigratedUnifiedState(migrated, bookId)
                assertQueueToggleAndSkipMutations(viewModel, bookId)
                collectJob.cancel()
            } finally {
                viewModel.playerController.releaseController()
                runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }

    private fun assertMigratedUnifiedState(migrated: MainUiState, bookId: String) {
        assertTrue(migrated.isEntryInQueue("art-prog"))
        assertFalse(migrated.isEntryInAudioUpNext("art-prog"))
        assertEquals(listOf("ep-audio"), migrated.queueEntries.map { it.id })
        assertTrue(migrated.isEntryInAudioUpNext("ep-audio"))
        assertEquals(setOf("art-prog", bookId), migrated.inProgressItems.map { it.entryId }.toSet())
        assertTrue(
            migrated.inProgressItems.any {
                it is InProgressItem.ReaderItem && it.entryId == "art-prog"
            }
        )
        assertTrue(
            migrated.inProgressItems.any {
                it is InProgressItem.StartedCatalogItem && it.entryId == bookId
            }
        )
        assertEquals(ConsumptionStatus.CONSUMED, migrated.diary.statusFilter)
    }

    private suspend fun assertQueueToggleAndSkipMutations(
        viewModel: MainViewModel,
        bookId: String,
    ) {
        viewModel.dispatch(MainAction.ToggleWantToRead("art-prog"))
        val afterToggleOff =
            viewModel.uiState.first { state ->
                !state.isEntryInQueue("art-prog") &&
                    state.undoEvent?.kind is UndoActionKind.RestoreCollectionEntry
            }
        assertFalse("art-prog" in afterToggleOff.catalog.wantToReadEntryIds)

        viewModel.dispatch(MainAction.SkipStartedEntry(bookId))
        val afterSkip =
            viewModel.uiState.first { state ->
                state.inProgressItems.none { it.entryId == bookId }
            }
        assertEquals(listOf("art-prog"), afterSkip.inProgressItems.map { it.entryId })

        viewModel.dispatch(MainAction.ToggleWantToRead("art-prog"))
        val afterToggleOn =
            viewModel.uiState.first { state -> "art-prog" in state.catalog.wantToReadEntryIds }
        assertTrue(
            WANT_TO_READ_COLLECTION_ID in
                env.repository.catalog.findWantCollectionsForEntry("art-prog")
        )
        assertTrue(afterToggleOn.isEntryInQueue("art-prog"))
    }

    private suspend fun seedActiveStartedFixture() {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        val baseTs = "2026-01-01T00:00:00Z"
        val books =
            listOf(
                "book-active" to "Active Book",
                "book-finished" to "Finished Book",
                "book-skipped" to "Skipped Book",
                "book-tie" to "Same Timestamp Tie",
                "book-reread" to "Reread Book",
            )
        dao.upsertEntries(
            books.map { (id, title) ->
                EntryEntity(
                    id = id,
                    kind = "book",
                    title = title,
                    createdAt = baseTs,
                    updatedAt = baseTs,
                )
            }
        )
        dao.insertConsumptionLogsIgnore(
            listOf(
                log("l1", "book-active", "2026-05-01T10:00:00Z", "started"),
                log("l2", "book-finished", "2026-05-01T10:00:00Z", "started"),
                log("l3", "book-finished", "2026-05-02T10:00:00Z", "consumed"),
                log("l4", "book-skipped", "2026-05-01T10:00:00Z", "started"),
                log("l5", "book-skipped", "2026-05-02T10:00:00Z", "abandoned"),
                log("l6", "book-tie", "2026-05-03T10:00:00Z", "started"),
                log("l7", "book-tie", "2026-05-03T10:00:00Z", "consumed"),
                log("l8", "book-reread", "2025-01-01T10:00:00Z", "consumed"),
                log("l9", "book-reread", "2026-05-04T10:00:00Z", "started"),
            )
        )
    }

    private suspend fun seedMixedLegacyQueueFixture(): String {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        dao.upsertFeed(
            FeedRow(
                id = "f-mix",
                url = "https://example.com/mix.xml",
                title = "Mixed Feed",
                siteUrl = "https://example.com",
            )
        )
        seedReaderProgressEntry(dao)
        seedAudioBelowThresholdEntry(dao)
        val bookId =
            env.repository.catalog.logEntry(
                UpsertCatalogEntryInput(
                    kind = EntryKind.BOOK,
                    title = "Dune",
                    creators = listOf("Frank Herbert"),
                    releaseYear = 1965,
                ),
                ConsumptionStatus.STARTED,
                notes = "Part 2",
            )
        env.settings.update {
            it.copy(
                queueItemIds = listOf("art-prog", "ep-audio"),
                migratedNonAudioQueueToWantRead = false,
            )
        }
        return bookId
    }

    private suspend fun seedReaderProgressEntry(dao: com.grayvines.roche.data.local.RocheDao) {
        dao.upsertItem(
            InboxItemRow(
                id = "art-prog",
                feedId = "f-mix",
                feedTitle = "Mixed Feed",
                title = "Halfway Article",
                url = "https://example.com/halfway",
                author = null,
                summary = null,
                contentText = null,
                contentHtml = "<p>Body</p>",
                contentType = "article",
                status = "unread",
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = "2026-05-01T09:00:00Z",
                createdAt = "2026-05-01T09:00:00Z",
                readProgress = 0.42,
                thumbnailMediaId = null,
                duration = null,
            )
        )
        dao.upsertEntryState(
            EntryStateEntity(
                entryId = "art-prog",
                inboxState = "unread",
                progress = 0.42,
                updatedAt = "2026-05-05T12:00:00Z",
            )
        )
    }

    private suspend fun seedAudioBelowThresholdEntry(dao: com.grayvines.roche.data.local.RocheDao) {
        dao.upsertItem(
            InboxItemRow(
                id = "ep-audio",
                feedId = "f-mix",
                feedTitle = "Mixed Feed",
                title = "Podcast Ep",
                url = "https://example.com/ep",
                author = null,
                summary = null,
                contentText = null,
                contentHtml = null,
                contentType = "audio",
                status = "unread",
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = "2026-05-01T08:00:00Z",
                createdAt = "2026-05-01T08:00:00Z",
                readProgress = 0.01,
                thumbnailMediaId = null,
                duration = "30:00",
                audioUrl = "https://example.com/ep.mp3",
            )
        )
    }

    @Test
    fun `up_next legacy collection is isolated from wantQueues, wantEntryIds, and findWantCollectionsForEntry`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val dao = env.database.dao()
            dao.upsertEntries(
                listOf(
                    EntryEntity(
                        id = "legacy-up-next-work",
                        kind = "article",
                        title = "Legacy Up Next Item",
                        createdAt = "2026-05-01T00:00:00Z",
                        updatedAt = "2026-05-01T00:00:00Z",
                    )
                )
            )
            env.repository.catalog.addCollectionEntry(UP_NEXT_COLLECTION_ID, "legacy-up-next-work")

            val snapBefore = env.repository.catalog.getSnapshot()
            assertTrue(snapBefore.wantQueues.none { it.collectionId == UP_NEXT_COLLECTION_ID })
            assertFalse("legacy-up-next-work" in snapBefore.wantEntryIds)
            assertTrue(
                env.repository.catalog.findWantCollectionsForEntry("legacy-up-next-work").isEmpty()
            )

            env.repository.catalog.addCollectionEntry(
                WANT_TO_READ_COLLECTION_ID,
                "legacy-up-next-work",
            )
            val snapAfter = env.repository.catalog.getSnapshot()
            assertTrue("legacy-up-next-work" in snapAfter.wantEntryIds)
            assertEquals(
                listOf(WANT_TO_READ_COLLECTION_ID),
                env.repository.catalog.findWantCollectionsForEntry("legacy-up-next-work"),
            )
        }

    @Test
    fun `active started query excludes merged, non-diary kinds, duplicates, same-ts skips, and dismissed items`() =
        runTest {
            seedEdgeCaseActiveStartedFixture()
            val active = env.database.dao().getActiveStartedEntries(50)
            assertEquals(listOf("art-restarted", "book-dup"), active.map { it.entryId })
        }

    @Test
    fun `computeInProgressItems handles boundaries, overlap boosting, and equal-instant tie-break`() {
        val entries =
            listOf(
                sampleEntry("b-below", progress = 0.029, ts = "2026-05-04T10:00:00Z"),
                sampleEntry("b-min", progress = 0.03, ts = "2026-05-03T12:00:00+02:00"),
                sampleEntry("b-max", progress = 0.979, ts = "2026-05-03T10:00:00Z"),
                sampleEntry("b-above", progress = 0.98, ts = "2026-05-04T10:00:00Z"),
                sampleEntry("b-overlap", progress = 0.40, ts = "2026-05-01T10:00:00Z"),
            )
        val started =
            listOf(
                DiaryEntryItem(
                    id = "log-overlap",
                    entryId = "b-overlap",
                    kind = EntryKind.ARTICLE,
                    title = "Overlap",
                    creators = emptyList(),
                    releaseYear = null,
                    ts = "2026-05-05T10:00:00Z",
                    monthKey = "2026-05",
                    dateLabel = "May 5",
                    status = ConsumptionStatus.STARTED,
                    notes = null,
                    tier = null,
                )
            )
        val result = computeInProgressItems(entries, started)
        // Equal instant 2026-05-03T10:00:00Z: "b-min" > "b-max" so "b-min" sorts ahead of "b-max"
        assertEquals(listOf("b-overlap", "b-min", "b-max"), result.map { it.entryId })
        assertTrue(result[0] is InProgressItem.ReaderItem)
        assertEquals("2026-05-05T10:00:00Z", result[0].activityTs)
    }

    @Test
    fun `toggleWantToRead removes all want memberships atomically and undo restores all of them`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val watchQueueId =
                env.repository.catalog.createCustomList("Want to Watch", CollectionRole.WANT)
            seedReaderProgressEntry(env.database.dao())
            env.repository.catalog.addCollectionEntry(WANT_TO_READ_COLLECTION_ID, "art-prog")
            env.repository.catalog.addCollectionEntry(watchQueueId, "art-prog")
            env.settings.update { it.copy(migratedNonAudioQueueToWantRead = true) }

            withViewModel { vm ->
                vm.uiState.first { "art-prog" in it.catalog.wantEntryIds }
                vm.dispatch(MainAction.ToggleWantToRead("art-prog"))
                val afterRemove =
                    vm.uiState.first {
                        "art-prog" !in it.catalog.wantEntryIds &&
                            it.undoEvent?.kind is UndoActionKind.RestoreCollectionEntry
                    }
                val undoKind = afterRemove.undoEvent?.kind as UndoActionKind.RestoreCollectionEntry
                assertEquals(
                    setOf(WANT_TO_READ_COLLECTION_ID, watchQueueId),
                    undoKind.collectionIds.toSet(),
                )
                assertTrue(env.repository.catalog.findWantCollectionsForEntry("art-prog").isEmpty())

                vm.dispatch(MainAction.UndoLastArchive)
                vm.uiState.first {
                    "art-prog" in it.catalog.wantEntryIds && it.statusBanner == "Restored to Queue"
                }
                assertEquals(
                    setOf(WANT_TO_READ_COLLECTION_ID, watchQueueId),
                    env.repository.catalog.findWantCollectionsForEntry("art-prog").toSet(),
                )
            }
        }

    @Test
    fun `completeWantEntry on live audio item archives entry_state and removes from audio up next`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val dao = env.database.dao()
            dao.upsertFeed(
                FeedRow("f-pod", "https://example.com/pod.xml", "Pod", "https://example.com")
            )
            seedAudioBelowThresholdEntry(dao)
            env.repository.catalog.addCollectionEntry(WANT_TO_READ_COLLECTION_ID, "ep-audio")
            env.settings.update {
                it.copy(
                    queueItemIds = listOf("ep-audio"),
                    migratedNonAudioQueueToWantRead = true,
                )
            }

            withViewModel { vm ->
                vm.uiState.first { "ep-audio" in it.catalog.wantToReadEntryIds }
                vm.dispatch(MainAction.CompleteWantEntry("ep-audio", rankImmediately = false))
                val finishedState =
                    vm.uiState.first { state ->
                        val ep = state.allEntries.firstOrNull { it.id == "ep-audio" }
                        ep != null &&
                            ep.isArchived &&
                            ep.isFinished &&
                            "ep-audio" !in state.settings.queueItemIds &&
                            "ep-audio" !in state.catalog.wantToReadEntryIds
                    }
                assertFalse(finishedState.isEntryInAudioUpNext("ep-audio"))
            }
        }

    @Test
    fun `migrateLegacyNonAudioQueue waits in-session on empty DB and resolves archived and merged ids`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val initialQueue = listOf("art-dup", "art-arch", "ep-dup")
            env.settings.update {
                it.copy(queueItemIds = initialQueue, migratedNonAudioQueueToWantRead = false)
            }
            // Single ViewModel session: stays unmigrated while Room is empty, then migrates
            // in-session once entries arrive, rewriting merged audio aliases to canonical IDs.
            withViewModel { vm ->
                vm.uiState.first { !it.isInitialLoading }
                val afterEmpty = env.settings.settings.first()
                assertFalse(afterEmpty.migratedNonAudioQueueToWantRead)
                assertEquals(initialQueue, afterEmpty.queueItemIds)

                seedLegacyMigrationEdgesFixture()
                val done =
                    vm.uiState.first {
                        it.settings.migratedNonAudioQueueToWantRead &&
                            "art-prog" in it.catalog.wantToReadEntryIds
                    }
                assertEquals(listOf("ep-audio"), done.settings.queueItemIds)
                assertTrue("art-prog" in done.catalog.wantToReadEntryIds)
                assertFalse("art-arch" in done.catalog.wantToReadEntryIds)
            }
        }

    @Test
    fun `restoreSavedNavigationState tolerates legacy JSON containing removed queueSubTab field`() {
        val handle =
            SavedStateHandle(
                mapOf(
                    MainViewModel.SAVED_NAV_JSON_KEY to
                        """{"activeTab":"QUEUE","queueSubTab":"UP_NEXT"}"""
                )
            )
        val restored = restoreSavedNavigationState(handle)
        assertEquals(AppTab.QUEUE, restored?.activeTab)
    }

    private suspend fun CoroutineScope.withViewModel(block: suspend (MainViewModel) -> Unit) {
        val viewModel =
            MainViewModel(repository = env.repository, settingsRepository = env.settings)
        try {
            val collectJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
            block(viewModel)
            collectJob.cancel()
        } finally {
            viewModel.playerController.releaseController()
            runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    private suspend fun seedEdgeCaseActiveStartedFixture() {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        seedEdgeCaseEntries(dao)
        seedEdgeCaseLogs(dao)
    }

    private suspend fun seedEdgeCaseEntries(dao: com.grayvines.roche.data.local.RocheDao) {
        val baseTs = "2026-01-01T00:00:00Z"
        dao.upsertEntries(
            listOf(
                EntryEntity("book-dup", "book", "Dup", createdAt = baseTs, updatedAt = baseTs),
                EntryEntity(
                    id = "book-merged",
                    kind = "book",
                    title = "Merged",
                    mergedIntoId = "book-dup",
                    createdAt = baseTs,
                    updatedAt = baseTs,
                ),
                EntryEntity("artist-1", "artist", "Miles", createdAt = baseTs, updatedAt = baseTs),
                EntryEntity("podcast-1", "podcast", "Pod", createdAt = baseTs, updatedAt = baseTs),
                EntryEntity("game-tie-skip", "game", "Tie", createdAt = baseTs, updatedAt = baseTs),
                EntryEntity(
                    "art-dismissed",
                    "article",
                    "Dis",
                    createdAt = baseTs,
                    updatedAt = baseTs,
                ),
                EntryEntity(
                    "art-restarted",
                    "article",
                    "Re",
                    createdAt = baseTs,
                    updatedAt = baseTs,
                ),
                EntryEntity(
                    "saved-dismissed",
                    "article",
                    "Sav",
                    createdAt = baseTs,
                    updatedAt = baseTs,
                ),
                EntryEntity(
                    "art-deleted",
                    "article",
                    "Del",
                    createdAt = baseTs,
                    updatedAt = baseTs,
                ),
            )
        )
        seedEdgeCaseEntryStates(dao)
    }

    private suspend fun seedEdgeCaseEntryStates(dao: com.grayvines.roche.data.local.RocheDao) {
        val states =
            listOf(
                EntryStateEntity(
                    entryId = "art-dismissed",
                    inboxState = "dismissed",
                    progress = 0.5,
                    updatedAt = "2026-05-06T10:00:00Z",
                ),
                EntryStateEntity(
                    entryId = "art-restarted",
                    inboxState = "dismissed",
                    progress = 1.0,
                    readAt = "2026-05-01T10:00:00Z",
                    interactionUpdatedAt = "2026-05-01T10:00:00Z",
                    updatedAt = "2026-05-01T10:00:00Z",
                ),
                EntryStateEntity(
                    entryId = "saved-dismissed",
                    inboxState = "none",
                    progress = 0.3,
                    readAt = "2026-05-06T10:00:00Z",
                    interactionUpdatedAt = "2026-05-06T10:00:00Z",
                    updatedAt = "2026-05-06T10:00:00Z",
                ),
                EntryStateEntity(
                    entryId = "art-deleted",
                    inboxState = "deleted",
                    progress = 0.2,
                    updatedAt = "2026-05-01T10:00:00Z",
                ),
            )
        states.forEach { dao.upsertEntryState(it) }
    }

    private suspend fun seedEdgeCaseLogs(dao: com.grayvines.roche.data.local.RocheDao) {
        dao.insertConsumptionLogsIgnore(
            listOf(
                log("dup-1", "book-dup", "2026-05-01T10:00:00Z", "started"),
                log("dup-2", "book-dup", "2026-05-01T10:00:00Z", "started"),
                log("m-1", "book-merged", "2026-05-02T10:00:00Z", "started"),
                log("a-1", "artist-1", "2026-05-02T10:00:00Z", "started"),
                log("p-1", "podcast-1", "2026-05-02T10:00:00Z", "started"),
                log("g-1", "game-tie-skip", "2026-05-03T10:00:00Z", "started"),
                log("g-2", "game-tie-skip", "2026-05-03T10:00:00Z", "abandoned"),
                log("d-1", "art-dismissed", "2026-05-05T10:00:00Z", "started"),
                log("r-1", "art-restarted", "2026-05-01T10:00:00Z", "consumed"),
                log("r-2", "art-restarted", "2026-05-07T10:00:00Z", "started"),
                log("s-1", "saved-dismissed", "2026-05-05T10:00:00Z", "started"),
                log("del-1", "art-deleted", "2026-05-08T10:00:00Z", "started"),
            )
        )
    }

    private suspend fun seedLegacyMigrationEdgesFixture() {
        val dao = env.database.dao()
        dao.executeInTransaction {
            dao.upsertFeed(
                FeedRow("f-mig", "https://example.com/mig.xml", "Mig", "https://example.com")
            )
            seedReaderProgressEntry(dao)
            seedAudioBelowThresholdEntry(dao)
            dao.upsertEntries(
                listOf(
                    EntryEntity(
                        id = "art-dup",
                        kind = "article",
                        title = "Dup",
                        mergedIntoId = "art-prog",
                        createdAt = "2026-05-01T00:00:00Z",
                        updatedAt = "2026-05-01T00:00:00Z",
                    ),
                    EntryEntity(
                        id = "ep-dup",
                        kind = "podcast_episode",
                        title = "Ep Dup",
                        mergedIntoId = "ep-audio",
                        createdAt = "2026-05-01T00:00:00Z",
                        updatedAt = "2026-05-01T00:00:00Z",
                    ),
                )
            )
            dao.upsertItem(
                InboxItemRow(
                    id = "art-arch",
                    feedId = "f-mig",
                    feedTitle = "Mig",
                    title = "Archived Article",
                    url = "https://example.com/arch",
                    author = null,
                    summary = null,
                    contentText = null,
                    contentHtml = "<p>Old</p>",
                    contentType = "article",
                    status = "archived",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-05-01T07:00:00Z",
                    createdAt = "2026-05-01T07:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                )
            )
        }
    }

    private fun sampleEntry(id: String, progress: Double, ts: String): InboxEntry =
        InboxEntry(
            id = id,
            feedId = "f-1",
            feedTitle = "Feed",
            title = "Title $id",
            url = "https://example.com/$id",
            author = null,
            summary = null,
            contentText = null,
            contentHtml = "<p>Body</p>",
            contentType = ContentType.ARTICLE,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = ts,
            createdAt = ts,
            readProgress = progress,
            interactionUpdatedAt = ts,
            thumbnailMediaId = null,
            duration = null,
            tags = emptyList(),
        )

    private fun log(id: String, entryId: String, ts: String, status: String) =
        ConsumptionLogEntity(id = id, entryId = entryId, ts = ts, status = status, createdAt = ts)
}
