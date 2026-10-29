package io.github.releasekite.compose

import android.app.Dialog
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 「不可取消」自动化闭环验收（T6 P2-7）。
 *
 * 之前只断言「按钮不存在」，这里补两层证据：
 * 1. **DialogProperties 语义**：不可取消的 phase 必须同时关闭返回键与点外部
 *    （[isUpdateDialogCancellable] / [updateDialogProperties] 与弹窗实现共用同一判定）；
 * 2. **真实窗口交互**：拿到 Compose 弹窗背后的 android.app.Dialog，实际触发返回键
 *    （`onBackPressed`）与外部点击（`ACTION_OUTSIDE`），断言 fake `onDismiss` 计数与弹窗可见性。
 *
 * 覆盖矩阵：Downloading / Verifying / forced Available / forced DownloadFailed(escape=false)
 * 不可取消；forced DownloadFailed(escape=true)、普通 Available、普通 DownloadFailed、
 * NeedInstallPermission 可取消（对照组，防止「不可取消」断言变成永远为真的空断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReleaseKiteUpdateDialogCancellationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0

    private fun render(phase: UpdatePhase) {
        dismissals = 0
        composeRule.setContent {
            MaterialTheme {
                ReleaseKiteUpdateDialog(
                    state = ReleaseKiteTestData.state(phase),
                    actions = ReleaseKiteActions(
                        onDownload = {},
                        onDismiss = { dismissals++ },
                        onRetry = {},
                        onOpenInstallSettings = {},
                    ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun latestDialog(): Dialog =
        requireNotNull(ShadowDialog.getLatestDialog()) { "更新弹窗未显示" }

    /** 触发真实返回键（Dialog.onBackPressed → Compose Dialog 的取消策略）。 */
    private fun pressBack() {
        latestDialog().onBackPressed()
        composeRule.waitForIdle()
    }

    /**
     * 触发「点弹窗外部」。
     *
     * Compose 1.11 的 DialogWrapper.onTouchEvent 用 DOWN/UP 事件对 + `isInsideContent` 判定
     * 外部点击（ACTION_OUTSIDE 不参与该分支），因此这里按真实手势顺序投递两个落在内容区外的
     * MotionEvent；坐标取足够小的负值，保证被判为「内容区外」。
     */
    private fun clickOutside() {
        val dialog = latestDialog()
        val outsideX = -1000f
        val outsideY = -1000f
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, outsideX, outsideY, 0)
        val up = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, outsideX, outsideY, 0)
        try {
            dialog.onTouchEvent(down)
            dialog.onTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        composeRule.waitForIdle()
    }

    private fun downloading() = UpdatePhase.Downloading(
        ReleaseKiteTestData.info(),
        35L * 1024 * 1024,
        70L * 1024 * 1024,
    )

    private fun forcedDownloadFailed(escapeAllowed: Boolean) = UpdatePhase.DownloadFailed(
        ReleaseKiteTestData.info(),
        UpdateFailure.Network,
        forced = true,
        escapeAllowed = escapeAllowed,
    )

    // ── 1. DialogProperties 语义（确定性，不依赖窗口事件是否可注入） ──────────

    @Test
    fun blockingPhasesDisableBackPressAndOutsideClick() {
        val blockingPhases = listOf(
            downloading(),
            UpdatePhase.Verifying(ReleaseKiteTestData.info()),
            UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info(), forced = true),
            forcedDownloadFailed(escapeAllowed = false),
        )
        blockingPhases.forEach { phase ->
            val properties = updateDialogProperties(phase)
            assertFalse("$phase 不应允许返回键取消", properties.dismissOnBackPress)
            assertFalse("$phase 不应允许点外部取消", properties.dismissOnClickOutside)
            assertFalse("$phase 取消判定应为 false", isUpdateDialogCancellable(phase))
        }
    }

    @Test
    fun cancellablePhasesEnableBackPressAndOutsideClick() {
        val cancellablePhases = listOf(
            UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info()),
            UpdatePhase.DownloadFailed(ReleaseKiteTestData.info(), UpdateFailure.Network),
            forcedDownloadFailed(escapeAllowed = true),
            UpdatePhase.NeedInstallPermission(ReleaseKiteTestData.info()),
        )
        cancellablePhases.forEach { phase ->
            val properties = updateDialogProperties(phase)
            assertTrue("$phase 应允许返回键取消", properties.dismissOnBackPress)
            assertTrue("$phase 应允许点外部取消", properties.dismissOnClickOutside)
            assertTrue("$phase 取消判定应为 true", isUpdateDialogCancellable(phase))
        }
    }

    @Test
    fun invisiblePhasesAreNotCancellable() {
        val invisiblePhases = listOf(
            UpdatePhase.Idle,
            UpdatePhase.Checking(UpdateTrigger.AUTO),
            UpdatePhase.UpToDate(UpdateTrigger.AUTO, "0.1.6"),
            UpdatePhase.RolloutPending(ReleaseKiteTestData.info(), 35),
            UpdatePhase.CheckFailed(UpdateTrigger.AUTO, UpdateFailure.Network),
        )
        invisiblePhases.forEach { phase ->
            assertFalse("$phase 不展示弹窗，取消判定应为 false", isUpdateDialogCancellable(phase))
        }
    }

    // ── 2. 真实窗口交互：不可取消 phase ──────────────────────────────────

    @Test
    fun downloadingBackPressDoesNotDismiss() {
        render(downloading())
        pressBack()
        assertEquals(0, dismissals)
        assertTrue("下载中返回键后弹窗应保持显示", latestDialog().isShowing)
        composeRule.onNodeWithTag(ReleaseKiteTestTags.DIALOG).assertExists()
    }

    @Test
    fun downloadingOutsideClickDoesNotDismiss() {
        render(downloading())
        clickOutside()
        assertEquals(0, dismissals)
        assertTrue("下载中点击外部后弹窗应保持显示", latestDialog().isShowing)
    }

    @Test
    fun verifyingBackPressDoesNotDismiss() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        pressBack()
        assertEquals(0, dismissals)
        assertTrue("校验中返回键后弹窗应保持显示", latestDialog().isShowing)
    }

    @Test
    fun verifyingOutsideClickDoesNotDismiss() {
        render(UpdatePhase.Verifying(ReleaseKiteTestData.info()))
        clickOutside()
        assertEquals(0, dismissals)
        assertTrue("校验中点击外部后弹窗应保持显示", latestDialog().isShowing)
    }

    @Test
    fun forcedAvailableBackPressDoesNotDismiss() {
        render(UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info(), forced = true))
        pressBack()
        assertEquals(0, dismissals)
        assertTrue("强制更新返回键后弹窗应保持显示", latestDialog().isShowing)
    }

    @Test
    fun forcedAvailableOutsideClickDoesNotDismiss() {
        render(UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info(), forced = true))
        clickOutside()
        assertEquals(0, dismissals)
        assertTrue("强制更新点击外部后弹窗应保持显示", latestDialog().isShowing)
    }

    @Test
    fun forcedDownloadFailedWithoutEscapeBackPressDoesNotDismiss() {
        render(forcedDownloadFailed(escapeAllowed = false))
        pressBack()
        assertEquals(0, dismissals)
        assertTrue("强制失败未达阈值时返回键不应关闭", latestDialog().isShowing)
    }

    @Test
    fun forcedDownloadFailedWithoutEscapeOutsideClickDoesNotDismiss() {
        render(forcedDownloadFailed(escapeAllowed = false))
        clickOutside()
        assertEquals(0, dismissals)
        assertTrue("强制失败未达阈值时点击外部不应关闭", latestDialog().isShowing)
    }

    // ── 3. 对照组：可取消 phase 必须真的能关闭（防止空断言） ─────────────────

    @Test
    fun forcedDownloadFailedWithEscapeBackPressDismisses() {
        render(forcedDownloadFailed(escapeAllowed = true))
        pressBack()
        assertEquals("达到失败阈值后返回键应触发 onDismiss", 1, dismissals)
    }

    @Test
    fun forcedDownloadFailedWithEscapeOutsideClickDismisses() {
        render(forcedDownloadFailed(escapeAllowed = true))
        clickOutside()
        assertEquals("达到失败阈值后点外部应触发 onDismiss", 1, dismissals)
    }

    @Test
    fun normalAvailableBackPressDismisses() {
        render(UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info()))
        pressBack()
        assertEquals("普通可用态返回键应触发 onDismiss", 1, dismissals)
    }

    @Test
    fun normalAvailableOutsideClickDismisses() {
        render(UpdatePhase.Available(UpdateTrigger.MANUAL, ReleaseKiteTestData.info()))
        clickOutside()
        assertEquals("普通可用态点外部应触发 onDismiss", 1, dismissals)
    }
}
