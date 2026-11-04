package com.grayvines.roche.data.settings

import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode

data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val bearerToken: String = "",
    val swipeToArchive: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val headlineFont: HeadlineFont = HeadlineFont.SERIF,
    val ultraDenseRows: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER_URL = ""
    }
}

fun Settings.clamped(): Settings =
    copy(
        serverUrl = normalizeServerUrl(serverUrl),
        bearerToken = bearerToken.trim(),
    )

internal fun normalizeServerUrl(rawUrl: String): String {
    val trimmedUrl = rawUrl.trim().trimEnd('/')
    if (trimmedUrl.isBlank()) return ""
    if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
        return trimmedUrl
    }
    val hostPart = trimmedUrl.substringBefore('/').substringBefore(':').lowercase()
    val useCleartext =
        hostPart == "localhost" ||
            hostPart.startsWith("127.") ||
            hostPart.startsWith("100.") ||
            hostPart == "10.0.2.2"
    return if (useCleartext) "http://$trimmedUrl" else "https://$trimmedUrl"
}
