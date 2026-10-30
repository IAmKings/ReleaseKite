package io.github.releasekite.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.releasekite.ReleaseKite
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReleaseKiteForegroundTracker] 引用计数回归（Robolectric）。
 *
 * 回归（三方深审 P2）：Android 多 Activity 生命周期顺序是 A.onStart → B.onStart → A.onStop，
 * 单布尔实现会被 A.onStop 误置为后台；计数实现只在所有窗口都 stop 后才算后台。
 * 同时验证计数 clamp >= 0：重复 onBackground 不会产生负计数导致后续 onForeground 失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseKiteForegroundTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val environment = AndroidUpdateEnvironment(context)

    @Before
    fun setUp() {
        ReleaseKiteForegroundTracker.reset()
    }

    @After
    fun tearDown() {
        ReleaseKiteForegroundTracker.reset()
    }

    @Test
    fun multiActivityOverlapStaysForegroundUntilAllStopped() {
        // A.onStart → B.onStart
        ReleaseKite.onForeground()
        ReleaseKite.onForeground()
        assertTrue("A 停止前应在前台", environment.isAppForeground)

        // A.onStop：B 仍处于 started，不能误判后台
        ReleaseKite.onBackground()
        assertTrue("B 仍在前台时 A.onStop 不应把前台误置后台", environment.isAppForeground)

        // B.onStop：全部窗口停止 → 后台
        ReleaseKite.onBackground()
        assertFalse("所有窗口停止后应转为后台", environment.isAppForeground)
    }

    @Test
    fun repeatedBackgroundIsClampedAndRecoverable() {
        ReleaseKite.onBackground()
        ReleaseKite.onBackground()
        assertFalse(environment.isAppForeground)

        ReleaseKite.onForeground()
        assertTrue("clamp 到 0 后 onForeground 必须立即生效（不被负计数抵消）", environment.isAppForeground)

        ReleaseKite.onBackground()
        assertFalse(environment.isAppForeground)
        ReleaseKite.onBackground()
        assertFalse("再次多减仍应 clamp 到后台", environment.isAppForeground)
    }

    @Test
    fun defaultStateIsBackgroundAndResetClearsCount() {
        assertFalse("进程启动默认后台（Worker 冷启动走通知分支）", environment.isAppForeground)

        ReleaseKite.onForeground()
        ReleaseKite.onForeground()
        assertTrue(environment.isAppForeground)

        ReleaseKiteForegroundTracker.reset()
        assertFalse("reset 后计数清零", environment.isAppForeground)
    }
}
