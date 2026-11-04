package com.grayvines.roche.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `settings normalize server URL and persist updates`() = runTest {
        val store =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "settings_test.preferences_pb") }
            )
        val repository = SettingsRepository(store)

        repository.update {
            it.copy(
                serverUrl = "100.64.0.9:8080/",
                bearerToken = "  secret-token  ",
                swipeToArchive = false,
                themeMode = ThemeMode.LIGHT,
                headlineFont = HeadlineFont.SANS,
                ultraDenseRows = true,
            )
        }

        val current = repository.settings.first()
        assertEquals("http://100.64.0.9:8080", current.serverUrl)
        assertEquals("secret-token", current.bearerToken)
        assertFalse(current.swipeToArchive)
        assertEquals(ThemeMode.LIGHT, current.themeMode)
        assertEquals(HeadlineFont.SANS, current.headlineFont)
        assertEquals(true, current.ultraDenseRows)
    }
}
