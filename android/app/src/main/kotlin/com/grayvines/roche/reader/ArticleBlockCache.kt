package com.grayvines.roche.reader

import androidx.collection.LruCache
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Injectable two-tier cache for single-pass parsed HTML article documents.
 *
 * Uses a fast reference-identity tier on the UI thread (zero hashing) backed by a content-addressed
 * SHA-256 tier populated on a background dispatcher.
 */
class ArticleBlockCache(maxEntries: Int = MAX_CACHED_PARSED_DOCS) {
    private data class CachedIdentityDocument(
        val rawHtml: String,
        val baseUrl: String?,
        val articleTitle: String?,
        val document: ParsedArticleDocument,
    )

    private val parsedDocumentsByHash = LruCache<String, ParsedArticleDocument>(maxEntries)
    private val recentIdentityDocuments = LruCache<Int, CachedIdentityDocument>(maxEntries)

    internal fun lookupFast(
        cleanHtml: String,
        baseUrl: String?,
        articleTitle: String? = null,
    ): ParsedArticleDocument? {
        val identityKey = System.identityHashCode(cleanHtml)
        return synchronized(recentIdentityDocuments) {
            recentIdentityDocuments[identityKey]
                ?.takeIf {
                    it.rawHtml === cleanHtml &&
                        it.baseUrl == baseUrl &&
                        it.articleTitle == articleTitle
                }
                ?.document
        }
    }

    internal suspend fun getOrParse(
        cleanHtml: String,
        baseUrl: String?,
        articleTitle: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ParsedArticleDocument {
        lookupFast(cleanHtml, baseUrl, articleTitle)?.let {
            return it
        }
        val document =
            withContext(dispatcher) {
                val shaKey = articleDocumentCacheKey(cleanHtml, baseUrl, articleTitle)
                val cached = synchronized(parsedDocumentsByHash) { parsedDocumentsByHash[shaKey] }
                if (cached != null) {
                    cached
                } else {
                    val parsed =
                        parseHtmlArticleDocument(
                            rawHtml = cleanHtml,
                            baseUrl = baseUrl,
                            articleTitle = articleTitle,
                        )
                    synchronized(parsedDocumentsByHash) {
                        parsedDocumentsByHash.put(shaKey, parsed)
                    }
                    parsed
                }
            }
        cacheIdentityDocument(cleanHtml, baseUrl, articleTitle, document)
        return document
    }

    fun clear() {
        synchronized(parsedDocumentsByHash) { parsedDocumentsByHash.evictAll() }
        synchronized(recentIdentityDocuments) { recentIdentityDocuments.evictAll() }
    }

    private fun cacheIdentityDocument(
        cleanHtml: String,
        baseUrl: String?,
        articleTitle: String?,
        document: ParsedArticleDocument,
    ) {
        val identityKey = System.identityHashCode(cleanHtml)
        synchronized(recentIdentityDocuments) {
            recentIdentityDocuments.put(
                identityKey,
                CachedIdentityDocument(
                    rawHtml = cleanHtml,
                    baseUrl = baseUrl,
                    articleTitle = articleTitle,
                    document = document,
                ),
            )
        }
    }

    internal companion object {
        internal const val MAX_CACHED_PARSED_DOCS = 32
    }
}

internal fun articleDocumentCacheKey(
    cleanHtml: String,
    baseUrl: String?,
    articleTitle: String? = null,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(baseUrl.orEmpty().toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(articleTitle.orEmpty().toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(cleanHtml.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}
