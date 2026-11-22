package com.grayvines.roche.data.feeds

import com.grayvines.roche.data.local.FeedRow

internal fun buildLocalOpmlXml(feeds: List<FeedRow>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<opml version="2.0">""")
    appendLine("  <head>")
    appendLine("    <title>Roche Feeds Export</title>")
    appendLine("  </head>")
    appendLine("  <body>")
    for (feed in feeds) {
        val titleAttr = escapeXmlAttr(feed.title)
        val xmlUrlAttr = escapeXmlAttr(feed.url)
        val rocheTypePart =
            if (feed.feedType.equals("podcast", ignoreCase = true)) {
                """ rocheFeedType="podcast""""
            } else {
                ""
            }
        val htmlUrlPart =
            feed.siteUrl?.takeIf { it.isNotBlank() }?.let { """ htmlUrl="${escapeXmlAttr(it)}"""" }
                ?: ""
        appendLine(
            """    <outline type="rss"$rocheTypePart text="$titleAttr" title="$titleAttr" xmlUrl="$xmlUrlAttr"$htmlUrlPart/>"""
        )
    }
    appendLine("  </body>")
    appendLine("</opml>")
}

private fun escapeXmlAttr(raw: String): String =
    buildString(raw.length) {
        for (ch in raw) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch.code < XML_CONTROL_MIN && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

private const val XML_CONTROL_MIN = 0x20
