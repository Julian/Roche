package com.grayvines.roche.data.local

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single reentrant coroutine write lock shared across all local database write entrypoints
 * (`RocheRepository`, `CatalogStore`, `PeerSyncIn`, `LocalFeedPoller`, `FeedDiscovery`, and
 * `OfflineCacheManager`). Cross-coroutine writers serialize strictly; same-coroutine nested calls
 * re-enter without deadlocking.
 */
class DbWriteLock {
    private val mutex = Mutex()

    private class HeldElement(val owner: DbWriteLock) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HeldElement>
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldElement]
        if (held != null && held.owner === this) {
            return block()
        }
        return mutex.withLock { withContext(HeldElement(this)) { block() } }
    }
}
