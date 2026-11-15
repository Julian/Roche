package com.grayvines.roche.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BackgroundMutationDispatcher(
    private val scope: CoroutineScope? = null,
    private val syncMutex: Mutex? = null,
) {
    private class TrackedMutex(val mutex: Mutex = Mutex(), var activeCount: Int = 0)

    private val itemMutexes =
        LinkedHashMap<String, TrackedMutex>(INITIAL_MUTEX_CAPACITY, LOAD_FACTOR, true)

    val isAsync: Boolean
        get() = scope != null

    suspend fun dispatchOrRun(itemId: String, block: suspend () -> Unit) {
        val mutex = retainMutex(itemId)
        if (scope == null) {
            try {
                mutex.withLock { block() }
            } finally {
                releaseMutex(itemId)
            }
        } else {
            scope.launch {
                try {
                    mutex.withLock {
                        if (syncMutex != null) {
                            syncMutex.withLock { block() }
                        } else {
                            block()
                        }
                    }
                } finally {
                    releaseMutex(itemId)
                }
            }
        }
    }

    @Synchronized internal fun cachedMutexCountForTest(): Int = itemMutexes.size

    @Synchronized
    private fun retainMutex(itemId: String): Mutex {
        val existing = itemMutexes[itemId]
        if (existing != null) {
            existing.activeCount++
            return existing.mutex
        }
        if (itemMutexes.size >= MAX_CACHED_ITEM_MUTEXES) {
            val iterator = itemMutexes.entries.iterator()
            while (iterator.hasNext() && itemMutexes.size >= MAX_CACHED_ITEM_MUTEXES) {
                val candidate = iterator.next().value
                if (candidate.activeCount == 0 && !candidate.mutex.isLocked) {
                    iterator.remove()
                }
            }
        }
        val created = TrackedMutex(activeCount = 1)
        itemMutexes[itemId] = created
        return created.mutex
    }

    @Synchronized
    private fun releaseMutex(itemId: String) {
        val tracked = itemMutexes[itemId] ?: return
        if (tracked.activeCount > 0) {
            tracked.activeCount--
        }
    }

    private companion object {
        private const val INITIAL_MUTEX_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_CACHED_ITEM_MUTEXES = 256
    }
}
