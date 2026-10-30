package io.github.releasekite.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页「检查更新」行渲染验收（英文默认资源）。
 *
 * 覆盖源实现里行内联的全部状态（当前版本/检查中/最新/可用/灰度/下载/校验/权限/检查失败/下载失败），
 * 以及「下载/校验中不可点」这条防打断约束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReleaseKiteUpdateRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(phase: UpdatePhase, onClick: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                ReleaseKiteUpdateRow(
                    state = ReleaseKiteTestData.state(phase),
                    onClick = onClick,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun idleShowsCurrentVersion() {
        render(UpdatePhase.Idle)
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).assertExists()
        composeRule.onNodeWithText("Check for updates").assertExists()
        composeRule.onNodeWithText("Current version 0.1.6").assertExists()
    }

    @Test
    fun checkingShowsCheckingText() {
        render(UpdatePhase.Checking(UpdateTrigger.MANUAL))
        composeRule.onNodeWithText("Checking for updates…").assertExists()
    }

    @Test
    fun upToDateShowsLatestVersion() {
        render(UpdatePhase.UpToDate(UpdateTrigger.MANUAL, "0.1.6"))
        composeRule.onNodeWithText("You're up to date (0.1.6)").assertExists()
    }

    @Test
    fun availableShowsNewVersion() {
        render(ReleaseKiteTestData.available())
        composeRule.onNodeWithText("New version 0.1.7 available").assertExists()
    }

    @Test
    fun rolloutPendingShowsRolloutPercent() {
        render(UpdatePhase.RolloutPending(ReleaseKiteTestData.info(), 35))
        composeRule.onNodeWithText("Version 0.1.7 is rolling out (35%)").assertExists()
    }

    @Test
    fun downloadingShowsPercentAndIsNotClickable() {
        render(
            UpdatePhase.Downloading(
                ReleaseKiteTestData.info(),
                35L * 1024 * 1024,
                70L * 1024 * 1024,
            ),
        )
        composeRule.onNodeWithText("Downloading 50%").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).assertIsNotEnabled()
    }

    @Test
    fun verifyingIsNotClickable() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("Verifying package…").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).assertIsNotEnabled()
    }

    @Test
    fun needInstallPermissionShowsWaitingTextAndIsNotClickable() {
        render(UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("Waiting for install permission").assertExists()
        // checkNow() 会被 manager 的「下载进行中」守卫忽略，行保持可点只会产生死点击。
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).assertIsNotEnabled()
    }

    @Test
    fun checkFailedShowsRetryHint() {
        render(UpdatePhase.CheckFailed(UpdateTrigger.MANUAL, UpdateFailure.Network))
        composeRule.onNodeWithText("Check failed, tap to retry").assertExists()
    }

    @Test
    fun downloadFailedShowsRetryHint() {
        render(UpdatePhase.DownloadFailed(ReleaseKiteTestData.info(), UpdateFailure.Network))
        composeRule.onNodeWithText("Download failed, tap to retry").assertExists()
    }

    @Test
    fun clickInvokesCheckWhenIdle() {
        var clicks = 0
        render(UpdatePhase.Idle) { clicks++ }
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun clickDuringDownloadDoesNotInvokeCheck() {
        var clicks = 0
        render(
            UpdatePhase.Downloading(
                ReleaseKiteTestData.info(),
                10L * 1024 * 1024,
                70L * 1024 * 1024,
            ),
        ) { clicks++ }
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).performClick()
        composeRule.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test
    fun clickWhileWaitingForInstallPermissionDoesNotInvokeCheck() {
        var clicks = 0
        render(UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info())) { clicks++ }
        composeRule.onNodeWithTag(ReleaseKiteTestTags.ROW).performClick()
        composeRule.waitForIdle()
        assertEquals(0, clicks)
    }
}
