package com.grayvines.roche.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_MS)
            device.waitForIdle()

            listOf("Feeds", "Queue", "Home", "Settings", "Inbox").forEach { tabLabel ->
                device.findObject(By.text(tabLabel))?.click()
                device.waitForIdle()
            }
        }

    private companion object {
        const val PACKAGE = "com.grayvines.roche"
        const val TIMEOUT_MS = 10_000L
    }
}
