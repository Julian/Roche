package com.grayvines.roche.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
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
            device.wait(Until.hasObject(By.res(BOTTOM_NAV_TAG)), TIMEOUT_MS)
            device.waitForIdle()

            BOTTOM_TAB_TAGS.forEach { tabTag -> clickTabWithRetry(device, tabTag) }
        }

    private fun clickTabWithRetry(device: UiDevice, tabTag: String) {
        repeat(MAX_CLICK_ATTEMPTS) { attempt ->
            try {
                device.wait(Until.findObject(By.res(tabTag)), TIMEOUT_MS)?.click()
                device.waitForIdle()
                return
            } catch (err: StaleObjectException) {
                if (attempt == MAX_CLICK_ATTEMPTS - 1) throw err
                device.waitForIdle()
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.grayvines.roche"
        const val BOTTOM_NAV_TAG = "bottom_nav_bar"
        const val TIMEOUT_MS = 10_000L
        const val MAX_CLICK_ATTEMPTS = 3
        val BOTTOM_TAB_TAGS =
            listOf(
                "bottom_tab_home",
                "bottom_tab_feeds",
                "bottom_tab_starred",
                "bottom_tab_settings",
                "bottom_tab_inbox",
            )
    }
}
