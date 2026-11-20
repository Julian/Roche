package com.grayvines.roche.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val READ_BUFFER_CHARS = 8_192
internal const val DEFAULT_MAX_BOUNDED_TEXT_CHARS = 64 * 1024 * 1024
private val UNSAFE_FILENAME_REGEX = Regex("""[^a-zA-Z0-9_-]""")

/**
 * Reads up to [maxChars] UTF-8 characters from [input] so untrusted or oversized inputs cannot
 * exhaust heap memory.
 */
internal fun readBoundedText(
    input: InputStream,
    maxChars: Int = DEFAULT_MAX_BOUNDED_TEXT_CHARS,
): String =
    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        val builder = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        var total = 0
        while (total < maxChars) {
            val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - total))
            if (read == -1) break
            builder.append(buffer, 0, read)
            total += read
        }
        builder.toString()
    }

/**
 * Atomically replaces [targetFile] with [tempFile], falling back to a non-atomic replace when the
 * underlying filesystem does not support atomic cross-rename semantics.
 */
internal fun moveFileAtomically(tempFile: File, targetFile: File) {
    targetFile.parentFile?.mkdirs()
    runCatching {
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
        .getOrElse {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
}

/**
 * Writes [bytes] to a sibling temp file and atomically moves it into [targetFile] so concurrent
 * readers never observe a half-written cache artifact.
 */
internal fun writeBytesAtomicallyToFile(bytes: ByteArray, targetFile: File): Boolean {
    targetFile.parentFile?.mkdirs()
    val temp = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
    return try {
        temp.writeBytes(bytes)
        moveFileAtomically(temp, targetFile)
        true
    } finally {
        if (temp.exists()) {
            runCatching { temp.delete() }
        }
    }
}

internal fun sanitizeCacheFileName(raw: String): String {
    val cleaned = raw.trim().replace(UNSAFE_FILENAME_REGEX, "_").trim('_').trim('.')
    require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
        "Invalid cache file name"
    }
    return cleaned
}

/**
 * Resolves a sanitized direct child inside [parentDir] and verifies via canonical paths that the
 * resulting file cannot escape [parentDir].
 */
internal fun resolveSafeChildFile(parentDir: File, childName: String): File {
    val safeName = sanitizeCacheFileName(childName)
    val parentCanonical = parentDir.canonicalFile
    val childCanonical = File(parentCanonical, safeName).canonicalFile
    check(
        childCanonical.parentFile == parentCanonical &&
            childCanonical.toPath().startsWith(parentCanonical.toPath())
    ) {
        "Resolved path escapes cache directory"
    }
    return childCanonical
}

/** Returns true if [targetFile]'s canonical path is strictly inside [allowedDir]. */
internal fun isCanonicalFileWithinDir(targetFile: File, allowedDir: File): Boolean = runCatching {
    val canonicalTarget = targetFile.canonicalPath
    val prefix = allowedDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
    canonicalTarget.startsWith(prefix)
}
    .getOrDefault(false)

/**
 * Returns true if [targetFile]'s canonical path is strictly inside at least one of [allowedDirs].
 */
internal fun isCanonicalFileWithinDirs(targetFile: File, allowedDirs: List<File>): Boolean {
    if (allowedDirs.isEmpty()) return false
    return runCatching {
            val canonicalTarget = targetFile.canonicalPath
            allowedDirs.any { allowedDir ->
                val prefix = allowedDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
                canonicalTarget.startsWith(prefix)
            }
        }
        .getOrDefault(false)
}
