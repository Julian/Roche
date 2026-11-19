package com.grayvines.roche.media

import com.grayvines.roche.core.moveFileAtomically
import com.grayvines.roche.core.resolveSafeChildFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ThumbnailDiskCache(
    internal val cacheDir: File?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxFiles: Int = MAX_DISK_CACHED_FILES,
    private val maxBytes: Long = MAX_DISK_CACHE_BYTES,
) {
    private val keyLocksGuard = Any()
    private val keyLocks = HashMap<String, KeyMutex>()
    private val diskWriteLock = Any()
    private var estimatedDiskFileCount: Int = -1
    private var writesSinceLastPrune: Int = 0

    internal suspend fun <T> withKeyLock(lockKey: String, block: suspend () -> T): T {
        val entry =
            synchronized(keyLocksGuard) {
                val existing = keyLocks.getOrPut(lockKey) { KeyMutex() }
                existing.waiters++
                existing
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(keyLocksGuard) {
                entry.waiters--
                if (entry.waiters == 0) {
                    keyLocks.remove(lockKey, entry)
                }
            }
        }
    }

    internal fun findCachedFile(mediaId: String): File? =
        findDiskCachedThumbnailFile(cacheDir, mediaId)

    internal fun createStagingFile(fileKey: String): File? {
        val thumbsDir = resolveOrCreateThumbsDir() ?: return null
        val targetFile = resolveSafeChildFile(thumbsDir, fileKey)
        return File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}$TMP_SUFFIX")
    }

    internal fun commitStagedFile(
        fileKey: String,
        tmpFile: File,
        timestampMs: Long = clockMs(),
    ): File? {
        val thumbsDir = resolveOrCreateThumbsDir() ?: return null
        return commitStagedFileLocked(thumbsDir, fileKey, tmpFile, timestampMs)
    }

    internal suspend fun writeBytes(
        mediaOrUrl: String,
        bytes: ByteArray,
    ): File? {
        if (cacheDir == null || bytes.isEmpty() || bytes.size > MAX_THUMBNAIL_BYTES) return null
        val fileKey = diskCacheKey(mediaOrUrl)
        return withKeyLock(fileKey) {
            withContext(Dispatchers.IO) {
                writeStagedAndCommit(fileKey) { tmpFile -> tmpFile.writeBytes(bytes) }
            }
        }
    }

    internal suspend fun copyFromFile(
        mediaOrUrl: String,
        sourceFile: File,
    ): File? {
        if (
            cacheDir == null || !sourceFile.isFile || sourceFile.length() !in 1..MAX_THUMBNAIL_BYTES
        ) {
            return null
        }
        val fileKey = diskCacheKey(mediaOrUrl)
        return withKeyLock(fileKey) {
            withContext(Dispatchers.IO) {
                writeStagedAndCommit(fileKey) { tmpFile ->
                    sourceFile.copyTo(tmpFile, overwrite = true)
                }
            }
        }
    }

    internal fun touchFile(file: File, timestampMs: Long = clockMs()) {
        runCatching { file.setLastModified(timestampMs) }
    }

    internal fun deleteCorruptFile(file: File) {
        synchronized(diskWriteLock) {
            val deleted = file.exists() && runCatching { file.delete() }.getOrDefault(false)
            if (deleted && estimatedDiskFileCount > 0) {
                estimatedDiskFileCount--
            }
        }
    }

    private fun writeStagedAndCommit(fileKey: String, populateTmp: (File) -> Unit): File? {
        val tmpFile = createStagingFile(fileKey) ?: return null
        return try {
            populateTmp(tmpFile)
            if (tmpFile.isFile && tmpFile.length() in 1..MAX_THUMBNAIL_BYTES) {
                commitStagedFile(fileKey, tmpFile)
            } else {
                null
            }
        } finally {
            if (tmpFile.exists()) {
                runCatching { tmpFile.delete() }
            }
        }
    }

    private fun commitStagedFileLocked(
        thumbsDir: File,
        fileKey: String,
        tmpFile: File,
        timestampMs: Long,
    ): File? =
        synchronized(diskWriteLock) {
            val targetFile = resolveSafeChildFile(thumbsDir, fileKey)
            val existedBefore = targetFile.isFile
            try {
                runCatching { moveFileAtomically(tmpFile, targetFile) }
                    .onFailure { tmpFile.copyTo(targetFile, overwrite = true) }
            } finally {
                if (tmpFile.exists()) {
                    runCatching { tmpFile.delete() }
                }
            }
            targetFile.setLastModified(timestampMs)
            updateCountsAndPruneLocked(thumbsDir, existedBefore)
            targetFile.takeIf { it.isFile && it.length() in 1..MAX_THUMBNAIL_BYTES }
        }

    private fun updateCountsAndPruneLocked(thumbsDir: File, existedBefore: Boolean) {
        if (estimatedDiskFileCount < 0) {
            estimatedDiskFileCount =
                thumbsDir.listFiles()?.count { it.isFile && !it.name.endsWith(TMP_SUFFIX) } ?: 1
        } else if (!existedBefore) {
            estimatedDiskFileCount++
        }
        writesSinceLastPrune++
        if (estimatedDiskFileCount > maxFiles || writesSinceLastPrune >= PRUNE_WRITE_INTERVAL) {
            estimatedDiskFileCount = pruneThumbnailDiskCacheFiles(thumbsDir, maxFiles, maxBytes)
            writesSinceLastPrune = 0
        }
    }

    private fun resolveOrCreateThumbsDir(): File? {
        val rootDir = cacheDir ?: return null
        return File(rootDir, THUMBNAILS_SUBDIR).apply { mkdirs() }
    }

    internal companion object {
        internal const val MAX_DISK_CACHED_FILES = 512
        internal const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L
        internal const val MAX_THUMBNAIL_BYTES = 10L * 1024L * 1024L
        private const val PRUNE_WRITE_INTERVAL = 32
    }
}

private const val TMP_SUFFIX = ".tmp"

private class KeyMutex {
    val mutex = Mutex()
    var waiters: Int = 0
}

internal fun findDiskCachedThumbnailFile(cacheDir: File?, mediaId: String): File? = runCatching {
    val rootDir = cacheDir ?: return null
    val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
    if (!thumbsDir.exists()) return null
    val thumbFile = resolveSafeChildFile(thumbsDir, diskCacheKey(mediaId))
    if (thumbFile.isFile && thumbFile.length() in 1..ThumbnailDiskCache.MAX_THUMBNAIL_BYTES) {
        thumbFile
    } else {
        null
    }
}
    .getOrNull()

internal fun pruneThumbnailDiskCacheFiles(
    thumbsDir: File,
    maxFiles: Int = ThumbnailDiskCache.MAX_DISK_CACHED_FILES,
    maxBytes: Long = ThumbnailDiskCache.MAX_DISK_CACHE_BYTES,
): Int {
    val files =
        thumbsDir.listFiles()?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) } ?: return 0
    var remainingCount = files.size
    var totalBytes = files.sumOf { it.length() }
    if (remainingCount <= maxFiles && totalBytes <= maxBytes) return remainingCount

    val sortedOldestFirst =
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    for (file in sortedOldestFirst) {
        if (remainingCount <= maxFiles && totalBytes <= maxBytes) break
        val fileLen = file.length()
        if (file.delete() || !file.exists()) {
            remainingCount--
            totalBytes = (totalBytes - fileLen).coerceAtLeast(0L)
        }
    }
    return remainingCount
}
