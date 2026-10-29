package io.github.releasekite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.releasekite.core.ReleaseKiteConfig
import io.github.releasekite.internal.AndroidUpdateEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReleaseKite] 门面回归（契约 §2）：
 * - `initialize` 幂等：重复调用返回同一 [io.github.releasekite.core.UpdateManager]；
 * - 读取宿主 PackageManager 的 versionName（替代 BuildConfig 编译期版本）；
 * - 默认 User-Agent 注入库版本 + 宿主包名，且只覆盖 core 默认值；
 * - `onForeground()/onBackground()` 只更新进程内前台标记（驱动下游
 *   [AndroidUpdateEnvironment.isAppForeground]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseKiteFacadeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initializeIsIdempotentAndReadsHostVersion() {
        // 门面默认实现包含 WorkManagerUpdateDownloadScheduler，需先初始化测试 WorkManager。
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(context) }

        val manager = ReleaseKite.initialize(
            context,
            ReleaseKiteConfig(repository = "releasekite/ReleaseKite"),
        )

        assertTrue(ReleaseKite.isInitialized())
        assertSame(manager, ReleaseKite.manager)
        // 幂等：第二次 initialize（即使 config 不同）返回首次实例。
        assertSame(
            manager,
            ReleaseKite.initialize(context, ReleaseKiteConfig(repository = "someone/else")),
        )

        // 版本读取：与 PackageManager 当前安装信息一致。
        val packageVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        assertEquals(packageVersion, manager.state.value.currentVersionName)

        // 默认 UA：ReleaseKite/<libVersion> (<package>)。
        assertEquals(
            "ReleaseKite/${BuildConfig.RELEASEKITE_VERSION} (${context.packageName})",
            manager.config.userAgent,
        )

        // 前后台标记：门面只维护 tracker，不驱动 Manager.onAppResumed()。
        val environment = AndroidUpdateEnvironment(context)
        ReleaseKite.onForeground()
        assertTrue(environment.isAppForeground)
        ReleaseKite.onBackground()
        assertFalse(environment.isAppForeground)
    }
}
