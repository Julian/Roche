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
        const val DEFAULT_SERVER_URL = "http://100.64.0.4:8080"
    }
}

fun Settings.clamped(): Settings {
    val trimmedUrl = serverUrl.trim().trimEnd('/').ifBlank { Settings.DEFAULT_SERVER_URL }
    val normalizedUrl =
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            trimmedUrl
        } else {
            "http://$trimmedUrl"
        }
    return copy(
        serverUrl = normalizedUrl,
        bearerToken = bearerToken.trim(),
    )
}
