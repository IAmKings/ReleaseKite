package io.github.releasekite.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.activity.ComponentActivity
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 更新弹窗 Robolectric 渲染验收（英文默认资源）。
 *
 * 迁移自 ZhiBox `AppUpdateDialogTest` 的 10 个用例，断言改为英文文案 + 契约 testTag；
 * 另补充下载态无按钮、forced 不可取消、失败原因全覆盖、说明占位、跳转/回调接线等用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReleaseKiteUpdateDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    // ── 迁移自 ZhiBox 的 10 个用例 ──────────────────────────────────

    @Test
    fun availableShowsVersionNotesAndActions() {
        render(ReleaseKiteTestData.available())
        composeRule.onNodeWithText("Update available").assertExists()
        composeRule.onNodeWithText("Current 0.1.6 → New version 0.1.7").assertExists()
        composeRule.onNodeWithText("70.0 MB · 2026-10-27").assertExists()
        composeRule.onNodeWithText("Release notes").assertExists()
        composeRule.onNodeWithText(ReleaseKiteTestData.DEFAULT_NOTES).assertExists()
        composeRule.onNodeWithText("Update now").assertExists()
        composeRule.onNodeWithText("Later").assertExists()
        composeRule.onNodeWithText("View full release notes").assertExists()
    }

    @Test
    fun downloadingShowsProgressAndHidesActionButtons() {
        render(
            UpdatePhase.Downloading(
                ReleaseKiteTestData.info(),
                35L * 1024 * 1024,
                70L * 1024 * 1024,
            ),
        )
        composeRule.onNodeWithText("Downloading update").assertExists()
        composeRule.onNodeWithText("50% · 35.0 MB / 70.0 MB").assertExists()
        composeRule.onNodeWithContentDescription("Update download progress").assertExists()
        composeRule.onNodeWithText("The system installer opens automatically when the download completes")
            .assertExists()
        composeRule.onNodeWithText("Update now").assertDoesNotExist()
        composeRule.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun verifyingHasNoActionButtons() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("Verifying package").assertExists()
        composeRule.onNodeWithText("The system installer opens automatically after verification")
            .assertExists()
        composeRule.onNodeWithText("Update now").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun failedShowsReadableReasonAndRetry() {
        render(UpdatePhase.DownloadFailed(ReleaseKiteTestData.info(), UpdateFailure.ChecksumMismatch))
        composeRule.onNodeWithText("Update failed").assertExists()
        composeRule.onNodeWithText("Package verification failed and the file was deleted").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun permissionShowsSettingsEntry() {
        render(UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()))
        composeRule.onNodeWithText("Install permission required").assertExists()
        composeRule.onNodeWithText("Allow installing unknown apps to finish updating. Installation continues automatically when you return.")
            .assertExists()
        composeRule.onNodeWithText("Open settings").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun idleShowsNoDialog() {
        render(UpdatePhase.Idle)
        composeRule.onNodeWithTag(ReleaseKiteTestTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun settingsRowShowsCurrentVersionAndStates() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(UpdatePhase.Idle),
                        onClick = {},
                    )
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(
                            UpdatePhase.UpToDate(io.github.releasekite.core.UpdateTrigger.MANUAL, "0.1.6"),
                        ),
                        onClick = {},
                    )
                    ReleaseKiteUpdateRow(
                        state = ReleaseKiteTestData.state(ReleaseKiteTestData.available()),
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Current version 0.1.6").assertExists()
        composeRule.onNodeWithText("You're up to date (0.1.6)").assertExists()
        composeRule.onNodeWithText("New version 0.1.7 available").assertExists()
    }

    @Test
    fun forcedAvailableHidesLaterButton() {
        render(ReleaseKiteTestData.available(forced = true))
        composeRule.onNodeWithText("Update required").assertExists()
        composeRule.onNodeWithText("This version includes important fixes. Please update to continue.")
            .assertExists()
        composeRule.onNodeWithText("Update now").assertExists()
        composeRule.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun forcedDownloadFailedOnlyAllowsRetryUntilEscape() {
        render(
            UpdatePhase.DownloadFailed(
                ReleaseKiteTestData.info(),
                UpdateFailure.Network,
                forced = true,
                escapeAllowed = false,
            ),
        )
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("Close").assertDoesNotExist()
        composeRule.onNodeWithText("Exit for now").assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertDoesNotExist()
    }

    @Test
    fun forcedDownloadFailedAllowsEscapeAfterThreshold() {
        render(
            UpdatePhase.DownloadFailed(
                ReleaseKiteTestData.info(),
                UpdateFailure.Network,
                forced = true,
                escapeAllowed = true,
            ),
        )
        composeRule.onNodeWithText("Exit for now").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertExists()
    }

    // ── 新增：契约 testTag / 不可取消 / 占位 / 跳转 / 回调接线 ──────────

    @Test
    fun availableExposesContractTestTags() {
        render(ReleaseKiteTestData.available())
        composeRule.onNodeWithTag(ReleaseKiteTestTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertExists()
    }

    @Test
    fun downloadingHasNoActionButtonsByTag() {
        render(
            UpdatePhase.Downloading(
                ReleaseKiteTestData.info(),
                10L * 1024 * 1024,
                70L * 1024 * 1024,
            ),
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS).assertDoesNotExist()
    }

    @Test
    fun verifyingHasNoActionButtonsByTag() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS).assertDoesNotExist()
    }

    @Test
    fun permissionExposesSettingsAndCancelTags() {
        render(UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()))
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).assertExists()
    }

    @Test
    fun notesEmptyShowsLocalizedPlaceholder() {
        render(
            UpdatePhase.Available(
                io.github.releasekite.core.UpdateTrigger.MANUAL,
                ReleaseKiteTestData.info(notes = ""),
            ),
        )
        composeRule.onNodeWithText("No release notes provided.").assertExists()
    }

    @Test
    fun availableWithoutPublishedAtShowsSizeOnly() {
        render(
            UpdatePhase.Available(
                io.github.releasekite.core.UpdateTrigger.MANUAL,
                ReleaseKiteTestData.info(publishedAt = null),
            ),
        )
        composeRule.onNodeWithText("70.0 MB").assertExists()
    }

    @Test
    fun viewFullReleaseNotesOpensHtmlUrl() {
        // LocalUriHandler 在 Dialog 自己的 AndroidComposeView 里由框架重新提供（AndroidUriHandler），
        // 无法从测试外部用 CompositionLocal 覆盖；这里验证真实接线：点击后发出 ACTION_VIEW Intent。
        render(ReleaseKiteTestData.available())
        composeRule.onNodeWithTag(ReleaseKiteTestTags.VIEW_FULL)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals(ReleaseKiteTestData.HTML_URL, started.dataString)
    }

    @Test
    fun updateNowClickInvokesDownload() {
        var downloads = 0
        render(
            ReleaseKiteTestData.available(),
            ReleaseKiteTestData.noopActions().copy(onDownload = { downloads++ }),
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.UPDATE_NOW).performClick()
        composeRule.waitForIdle()
        assertEquals(1, downloads)
    }

    @Test
    fun laterClickInvokesDismiss() {
        var dismissals = 0
        render(
            ReleaseKiteTestData.available(),
            ReleaseKiteTestData.noopActions().copy(onDismiss = { dismissals++ }),
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.LATER).performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun retryClickInvokesRetry() {
        var retries = 0
        render(
            UpdatePhase.DownloadFailed(ReleaseKiteTestData.info(), UpdateFailure.Network),
            ReleaseKiteTestData.noopActions().copy(onRetry = { retries++ }),
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.RETRY).performClick()
        composeRule.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun openSettingsClickInvokesInstallSettings() {
        var opens = 0
        render(
            UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()),
            ReleaseKiteTestData.noopActions().copy(onOpenInstallSettings = { opens++ }),
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS).performClick()
        composeRule.waitForIdle()
        assertEquals(1, opens)
    }

    /**
     * 15 种 [UpdateFailure] 全覆盖（Http 拆普通/限流两条）：逐项渲染并断言文案存在，
     * 防止新增失败类型时漏翻译。
     */
    @Test
    fun failureTextCoversEveryUpdateFailureVariant() {
        val failures = listOf(
            UpdateFailure.Network,
            UpdateFailure.Timeout,
            UpdateFailure.Http(500),
            UpdateFailure.Http(503, rateLimited = true),
            UpdateFailure.Parse,
            UpdateFailure.NoRelease,
            UpdateFailure.IncompleteRelease,
            UpdateFailure.NoChecksum,
            UpdateFailure.ChecksumMismatch,
            UpdateFailure.ApkInvalid,
            UpdateFailure.InsufficientSpace,
            UpdateFailure.InstallPermission,
            UpdateFailure.InstallFailed,
            UpdateFailure.FileUnavailable,
            UpdateFailure.Cancelled,
            UpdateFailure.ReleaseUnavailable,
        )
        val expectedTexts = listOf(
            "Network unavailable. Check your connection and try again.",
            "Check timed out. Please try again later.",
            "Server error (HTTP 500)",
            "Too many requests. Please try again later.",
            "Failed to parse update information",
            "No release found",
            "Release information is incomplete",
            "Release has no checksum; download refused",
            "Package verification failed and the file was deleted",
            "Invalid package or signature mismatch; installation refused",
            "Not enough storage. Free up space and try again.",
            "Install permission missing",
            "Could not open the system installer",
            "Package file unavailable",
            "Update cancelled",
            "This release is no longer available. Check for updates again.",
        )
        assertEquals(failures.size, expectedTexts.size)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    failures.forEach { failure -> Text(failureText(failure)) }
                }
            }
        }
        composeRule.waitForIdle()
        expectedTexts.forEach { expected -> composeRule.onNodeWithText(expected).assertExists() }
    }
}
