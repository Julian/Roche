package com.grayvines.roche.playback

import com.grayvines.roche.core.decodeHtmlEntities
import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.InboxEntry
import java.util.Locale
import kotlin.math.roundToLong

enum class PlayerDrawerTab(val label: String) {
    SHOW_NOTES("Show Notes"),
    CHAPTERS("Chapters"),
    TRANSCRIPT("Transcript"),
}

data class PodcastChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
) {
    val formattedStart: String
        get() = formatPlaybackClock(startMs)
}

internal fun extractBestChaptersFromEntry(entry: InboxEntry): List<PodcastChapter> =
    listOfNotNull(entry.contentHtml, entry.contentText, entry.summary)
        .firstNotNullOfOrNull { notes ->
            parseChaptersFromEpisodeNotes(notes).takeIf { it.isNotEmpty() }
        }
        .orEmpty()

internal fun formatPlaybackClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / MILLIS_PER_SEC
    val hours = totalSeconds / SECS_PER_HOUR
    val minutes = totalSeconds % SECS_PER_HOUR / SECS_PER_MIN
    val seconds = totalSeconds % SECS_PER_MIN
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    val clamped = clampPlaybackSpeed(speed)
    val hundredths = (clamped * HUNDREDTHS_SCALE).roundToLong()
    return if (hundredths % TENTHS_DIVISOR == 0L) {
        String.format(Locale.US, "%.1fx", clamped)
    } else {
        String.format(Locale.US, "%.2fx", clamped)
    }
}

internal fun parseChaptersFromEpisodeNotes(rawHtmlOrText: String): List<PodcastChapter> {
    if (rawHtmlOrText.isBlank()) return emptyList()
    val plainLines =
        decodeHtmlEntities(
                rawHtmlOrText
                    .replace(BR_TAG_REGEX, "\n")
                    .replace(BLOCK_END_TAG_REGEX, "\n")
                    .replace(ANY_TAG_REGEX, " ")
            )
            .replace('\u00A0', ' ')
            .lines()

    return plainLines
        .mapNotNull(::parseSingleChapterLine)
        .distinctBy { it.first }
        .sortedBy { it.first }
        .mapIndexed { idx, (startMs, title) ->
            PodcastChapter(index = idx + 1, title = title, startMs = startMs)
        }
}

private fun parseSingleChapterLine(line: String): Pair<Long, String>? {
    val match = CHAPTER_LINE_REGEX.find(line.trim())
    val timeToken = match?.groupValues?.get(1).orEmpty()
    val title =
        match?.groupValues?.get(2)?.trim()?.trimStart('-', '–', '—', ':', '·')?.trim().orEmpty()
    val seconds =
        if (title.isBlank()) {
            null
        } else {
            parseDurationToSecondsLong(timeToken)
                ?: if (timeToken in ZERO_TIMESTAMP_TOKENS) 0L else null
        }
    return seconds?.takeIf { it >= 0L }?.let { it * MILLIS_PER_SEC to title }
}

private const val MILLIS_PER_SEC = 1_000L
private const val SECS_PER_MIN = 60L
private const val SECS_PER_HOUR = 3_600L
private const val HUNDREDTHS_SCALE = 100f
private const val TENTHS_DIVISOR = 10L
private val ZERO_TIMESTAMP_TOKENS = setOf("00:00", "0:00", "00:00:00")
private val BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val BLOCK_END_TAG_REGEX = Regex("""</(p|li|div|h[1-6])>""", RegexOption.IGNORE_CASE)
private val ANY_TAG_REGEX = Regex("""<[^>]+>""")
private val CHAPTER_LINE_REGEX =
    Regex("""^(?:[\[(])?(\d{1,2}:\d{2}(?::\d{2})?)(?:[\])])?\s+(.+)$""")
