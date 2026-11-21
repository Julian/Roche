package com.grayvines.roche.data.feeds

import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.moveFileAtomically
import com.grayvines.roche.core.resolveSafeChildFile
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.sanitizeCacheFileName
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.media.ITEMS_SUBDIR
import com.grayvines.roche.media.SHARED_PDF_FILENAME
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PDF_COPY_BUFFER_BYTES = 16 * 1024
private const val MAX_SHARED_PDF_BYTES = 50L * 1024L * 1024L
internal const val MAX_TOTAL_SHARED_PDF_BYTES = 250L * 1024L * 1024L
private val sharedPdfWriteMutex = Mutex()

internal suspend fun copySharedPdfStreamToCache(
    dao: RocheDao,
    cacheDir: File?,
    itemId: String,
    stream: InputStream,
    allowOverwriteExisting: Boolean = false,
): Boolean =
    withContext(Dispatchers.IO) {
        val copied =
            if (cacheDir == null) {
                runCatching { stream.close() }
                false
            } else {
                writeSharedPdfToItemDir(
                    rootDir = cacheDir,
                    itemId = itemId,
                    stream = stream,
                    allowOverwriteExisting = allowOverwriteExisting,
                )
            }
        if (copied) {
            dao.updateItemCachedOffline(itemId, true)
        }
        copied
    }

internal suspend fun writeSharedPdfToItemDir(
    rootDir: File,
    itemId: String,
    stream: InputStream,
    allowOverwriteExisting: Boolean = false,
    maxTotalPdfBytes: Long = MAX_TOTAL_SHARED_PDF_BYTES,
): Boolean = runSuspendCatching {
    stream.use { input ->
        sharedPdfWriteMutex.withLock {
            writeSharedPdfStreamLocked(
                rootDir = rootDir,
                itemId = itemId,
                input = input,
                allowOverwriteExisting = allowOverwriteExisting,
                maxTotalPdfBytes = maxTotalPdfBytes,
            )
        }
    }
}
    .getOrDefault(false)

private fun writeSharedPdfStreamLocked(
    rootDir: File,
    itemId: String,
    input: InputStream,
    allowOverwriteExisting: Boolean,
    maxTotalPdfBytes: Long,
): Boolean {
    val safeItemId = sanitizeCacheFileName(itemId)
    if (safeItemId != itemId.trim()) return false
    val itemsRoot = File(rootDir, ITEMS_SUBDIR).apply { mkdirs() }
    val itemDir = resolveSafeChildFile(itemsRoot, safeItemId).apply { mkdirs() }
    val targetFile = File(itemDir, SHARED_PDF_FILENAME).canonicalFile
    check(targetFile.parentFile == itemDir.canonicalFile) {
        "Resolved PDF path escapes item directory"
    }
    if (!allowOverwriteExisting && targetFile.exists()) return false
    val existingBytes = totalCachedPdfBytes(itemsRoot, excludeFile = targetFile)
    val remainingQuota = (maxTotalPdfBytes - existingBytes).coerceAtLeast(0L)
    val effectiveMaxBytes = minOf(MAX_SHARED_PDF_BYTES, remainingQuota)
    return effectiveMaxBytes > 0L &&
        writeBoundedTempPdfAndMove(input, itemDir, targetFile, effectiveMaxBytes)
}

private fun writeBoundedTempPdfAndMove(
    input: InputStream,
    itemDir: File,
    targetFile: File,
    maxBytes: Long,
): Boolean {
    val tempFile = File(itemDir, "$SHARED_PDF_FILENAME.${System.nanoTime()}.tmp")
    return try {
        val totalBytes = copyBoundedStreamToFile(input, tempFile, maxBytes)
        if (totalBytes <= 0L) {
            false
        } else {
            moveFileAtomically(tempFile, targetFile)
            true
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

internal fun totalCachedPdfBytes(itemsRoot: File, excludeFile: File? = null): Long {
    val excludedCanonical = excludeFile?.canonicalPath
    var total = 0L
    itemsRoot.listFiles().orEmpty().forEach { itemDir ->
        if (itemDir.isDirectory) {
            itemDir.listFiles().orEmpty().forEach { file ->
                val isPdf = file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
                if (isPdf && file.canonicalPath != excludedCanonical) {
                    total += file.length().coerceAtLeast(0L)
                }
            }
        }
    }
    return total
}

private fun copyBoundedStreamToFile(
    input: InputStream,
    target: File,
    maxBytes: Long = MAX_SHARED_PDF_BYTES,
): Long {
    var totalBytes = 0L
    val header = ByteArray(PDF_MAGIC_BYTES.size)
    var headerRead = 0
    target.outputStream().use { output ->
        val buffer = ByteArray(PDF_COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read != -1) {
            if (read > 0) {
                headerRead = accumulateAndVerifyPdfHeader(buffer, read, header, headerRead)
                totalBytes += read
                check(totalBytes <= maxBytes) { "Shared PDF exceeds storage limit" }
                output.write(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }
    check(hasPdfMagicHeader(header, headerRead)) {
        "Shared stream is not a valid PDF document"
    }
    return totalBytes
}

private fun accumulateAndVerifyPdfHeader(
    buffer: ByteArray,
    read: Int,
    header: ByteArray,
    headerRead: Int,
): Int {
    if (headerRead >= PDF_MAGIC_BYTES.size) return headerRead
    val take = minOf(read, PDF_MAGIC_BYTES.size - headerRead)
    System.arraycopy(buffer, 0, header, headerRead, take)
    val nextHeaderRead = headerRead + take
    if (nextHeaderRead == PDF_MAGIC_BYTES.size) {
        check(hasPdfMagicHeader(header, nextHeaderRead)) {
            "Shared stream is not a valid PDF document"
        }
    }
    return nextHeaderRead
}
