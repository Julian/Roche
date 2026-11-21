package com.grayvines.roche.releasesmoke

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    @Test
    fun smokeTestSignedRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        launchRoche(context, device, clearTask = true)
        assertVisible(device, BOTTOM_NAV_TAG, "Expected bottom navigation bar after launch")
        assertVisible(device, HOME_SCREEN_TAG, "Expected home screen after launch")

        verifyBottomTabs(device)
        verifySettingsSubpage(device)
        verifyBackgroundResume(context, device)
    }

    private fun verifyBottomTabs(device: UiDevice) {
        BOTTOM_TAB_TAGS.forEach { tabTag ->
            clickByResIdWithRetry(device, tabTag)
            assertVisible(
                device,
                BOTTOM_NAV_TAG,
                "App crashed or left foreground after tapping $tabTag",
            )
        }
    }

    private fun verifySettingsSubpage(device: UiDevice) {
        clickByResIdWithRetry(device, HOME_SETTINGS_BTN_TAG)
        assertVisible(device, SETTINGS_SERVER_ROW_TAG, "Expected Settings hub")

        clickByResIdWithRetry(device, SETTINGS_SERVER_ROW_TAG)
        assertVisible(device, SETTINGS_BACK_BTN_TAG, "Expected Settings server subpage")

        clickByResIdWithRetry(device, SETTINGS_BACK_BTN_TAG)
        assertVisible(device, SETTINGS_SERVER_ROW_TAG, "Expected return to Settings hub")

        clickByResIdWithRetry(device, BOTTOM_TAB_HOME_TAG)
        assertVisible(device, HOME_SCREEN_TAG, "Expected return to home screen")
    }

    private fun verifyBackgroundResume(context: Context, device: UiDevice) {
        device.pressHome()
        device.waitForIdle()
        launchRoche(context, device, clearTask = false)
        assertVisible(device, HOME_SCREEN_TAG, "Expected home screen after resume")
    }

    private fun launchRoche(context: Context, device: UiDevice, clearTask: Boolean) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        assertNotNull("No launch intent found for $PACKAGE", launchIntent)
        val intent =
            requireNotNull(launchIntent).apply {
                if (clearTask) {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        assertTrue(
            "Timed out waiting for $PACKAGE in foreground",
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS),
        )
        device.waitForIdle()
    }

    private fun assertVisible(device: UiDevice, resId: String, message: String) {
        assertTrue(message, device.wait(Until.hasObject(By.res(resId)), TIMEOUT_MS))
    }

    private fun clickByResIdWithRetry(device: UiDevice, resId: String) {
        repeat(MAX_CLICK_ATTEMPTS) { attempt ->
            try {
                val target = device.wait(Until.findObject(By.res(resId)), TIMEOUT_MS)
                assertNotNull("Timed out waiting for $resId", target)
                requireNotNull(target).click()
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
        const val HOME_SCREEN_TAG = "home_screen"
        const val BOTTOM_TAB_HOME_TAG = "bottom_tab_home"
        const val HOME_SETTINGS_BTN_TAG = "home_spare_settings_btn"
        const val SETTINGS_SERVER_ROW_TAG = "settings_server_row"
        const val SETTINGS_BACK_BTN_TAG = "settings_subpage_back_btn"
        const val TIMEOUT_MS = 15_000L
        const val MAX_CLICK_ATTEMPTS = 3
        val BOTTOM_TAB_TAGS =
            listOf(
                "bottom_tab_inbox",
                "bottom_tab_lists",
                "bottom_tab_feeds",
                "bottom_tab_starred",
                BOTTOM_TAB_HOME_TAG,
            )
    }
}
