package io.github.releasekite.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import io.github.releasekite.core.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 中文资源（values-zh-rCN）渲染验收：确认弹窗、设置行、设置区在 zh-rCN 下文案完整，
 * 且「更新说明为空 → 本地化占位」与英文资源行为一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class ReleaseKiteUpdateDialogZhTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        phase: UpdatePhase,
        actions: ReleaseKiteActions = ReleaseKiteTestData.noopActions(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                ReleaseKiteUpdateDialog(
                    state = ReleaseKiteTestData.state(phase),
                    actions = actions,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun availableShowsChineseTexts() {
        render(ReleaseKiteTestData.available())
        composeRule.onNodeWithText("发现新版本").assertExists()
        composeRule.onNodeWithText("当前 0.1.6 → 新版本 0.1.7").assertExists()
        composeRule.onNodeWithText("70.0 MB · 2026-10-27").assertExists()
        composeRule.onNodeWithText("更新内容").assertExists()
        composeRule.onNodeWithText("立即更新").assertExists()
        composeRule.onNodeWithText("稍后").assertExists()
        composeRule.onNodeWithText("查看完整说明").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertExists()
    }

    @Test
    fun downloadingShowsChineseProgressTexts() {
        render(
            UpdatePhase.Downloading(
                ReleaseKiteTestData.info(),
                35L * 1024 * 1024,
                70L * 1024 * 1024,
            ),
        )
        composeRule.onNodeWithText("正在下载更新").assertExists()
        composeRule.onNodeWithText("50% · 35.0 MB / 70.0 MB").assertExists()
        composeRule.onNodeWithText("下载完成后将自动打开系统安装界面").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertDoesNotExist()
    }

    @Test
    fun verifyingShowsChineseTexts() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("正在校验安装包").assertExists()
        composeRule.onNodeWithText("校验通过后将自动打开系统安装界面").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.RETRY).assertDoesNotExist()
    }

    @Test
    fun forcedAvailableShowsChineseForcedTitleWithoutLater() {
        render(ReleaseKiteTestData.available(forced = true))
        composeRule.onNodeWithText("需要更新").assertExists()
        composeRule.onNodeWithText("此版本包含重要修复，请更新后继续使用。").assertExists()
        composeRule.onNodeWithText("立即更新").assertExists()
        composeRule.onNodeWithText("稍后").assertDoesNotExist()
    }

    @Test
    fun failedShowsChineseFailureTextAndRetry() {
        render(UpdatePhase.DownloadFailed(ReleaseKiteTestData.info(), UpdateFailure.ChecksumMismatch))
        composeRule.onNodeWithText("更新失败").assertExists()
        composeRule.onNodeWithText("安装包校验失败，已删除").assertExists()
        composeRule.onNodeWithText("重试").assertExists()
        composeRule.onNodeWithText("关闭").assertExists()
    }

    @Test
    fun forcedDownloadFailedEscapeShowsChineseExitTemporarily() {
        render(
            UpdatePhase.DownloadFailed(
                ReleaseKiteTestData.info(),
                UpdateFailure.Network,
                forced = true,
                escapeAllowed = true,
            ),
        )
        composeRule.onNodeWithText("暂时退出").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertExists()
    }

    @Test
    fun permissionShowsChineseSettingsEntry() {
        render(UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("需要安装权限").assertExists()
        composeRule.onNodeWithText(
            "系统需要你允许安装未知应用，才能完成更新。授权后返回应用会自动继续安装。",
        ).assertExists()
        composeRule.onNodeWithText("去设置").assertExists()
        composeRule.onNodeWithText("取消").assertExists()
    }

    @Test
    fun notesEmptyShowsChinesePlaceholder() {
        render(
            UpdatePhase.Available(
                UpdateTrigger.MANUAL,
                ReleaseKiteTestData.info(notes = ""),
            ),
        )
        composeRule.onNodeWithText("暂无更新说明。").assertExists()
    }

    @Test
    fun updateRowShowsChineseStates() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(UpdatePhase.Idle),
                        onClick = {},
                    )
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(
                            UpdatePhase.UpToDate(UpdateTrigger.MANUAL, "0.1.6"),
                        ),
                        onClick = {},
                    )
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(ReleaseKiteTestData.available()),
                        onClick = {},
                    )
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(
                            UpdatePhase.RolloutPending(ReleaseKiteTestData.info(), 35),
                        ),
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前版本 0.1.6").assertExists()
        composeRule.onNodeWithText("已是最新版本（0.1.6）").assertExists()
        composeRule.onNodeWithText("发现新版本 0.1.7").assertExists()
        composeRule.onNodeWithText("新版本 0.1.7 正在分批推送（35%）").assertExists()
    }

    @Test
    fun settingsSectionShowsChineseTextsAndSwitches() {
        var autoCheckChange: Boolean? = null
        var channelChange: UpdateChannel? = null
        composeRule.setContent {
            MaterialTheme {
                ReleaseKiteSettingsSection(
                    state = UpdateUiState(currentVersionName = "0.1.6"),
                    onAutoCheckChange = { autoCheckChange = it },
                    onChannelChange = { channelChange = it },
                    onResetRouteHealth = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("更新设置").assertExists()
        composeRule.onNodeWithText("自动检查更新").assertExists()
        composeRule.onNodeWithText("接收预发布版本（Beta）").assertExists()
        composeRule.onNodeWithText("重置线路状态").assertExists()

        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_AUTO_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(false, autoCheckChange)

        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_BETA_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(UpdateChannel.BETA, channelChange)
    }
}
