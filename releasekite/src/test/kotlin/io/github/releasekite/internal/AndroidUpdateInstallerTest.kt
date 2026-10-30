package io.github.releasekite.internal

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * [AndroidUpdateInstaller] 安装授权设置页的 API 版本守卫回归（三方深审 P2）。
 *
 * `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 仅 API 26+：低版本应直接 no-op，
 * 不能启动不存在的 Activity（InlinedApi 常量在低版本运行时会被内联进代码，风险真实存在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUpdateInstallerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private var originalSdk: Int = Build.VERSION.SDK_INT

    @After
    fun restoreSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
    }

    @Test
    fun openInstallPermissionSettingsIsNoOpBelowApi26() {
        val installer = AndroidUpdateInstaller(application)

        withSdkInt(Build.VERSION_CODES.LOLLIPOP_MR1) {
            installer.openInstallPermissionSettings()
            assertNull(
                "API < 26 不应启动不存在的「安装未知应用」设置页",
                shadowOf(application).nextStartedActivity,
            )
        }
    }

    @Test
    fun openInstallPermissionSettingsStartsSettingsFromApi26() {
        val installer = AndroidUpdateInstaller(application)

        withSdkInt(Build.VERSION_CODES.O) {
            installer.openInstallPermissionSettings()
            val intent = shadowOf(application).nextStartedActivity
            assertNotNull("API >= 26 应打开安装授权设置页", intent)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
            assertEquals("package:${application.packageName}", intent.data.toString())
        }
    }

    private fun withSdkInt(sdk: Int, block: () -> Unit) {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        try {
            block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
    }
}
