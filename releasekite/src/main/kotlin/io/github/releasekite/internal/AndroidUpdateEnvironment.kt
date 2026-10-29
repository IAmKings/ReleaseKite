package io.github.releasekite.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.github.releasekite.core.UpdateEnvironment
import java.io.File

/**
 * Android 实现的 [UpdateEnvironment]（契约 §2）：
 * - [workDir]：`cacheDir/releasekite/updates`（由 [ReleaseKitePaths] 统一推导）；
 * - [currentVersionName]/[currentVersionCode]：读取宿主 PackageManager 信息
 *   （替代 ZhiBox 直接引用 `BuildConfig.VERSION_NAME`，库态下 host 版本必须在运行时读取）；
 * - [isAppForeground]：委托 [ReleaseKiteForegroundTracker]；
 * - 日志走 `android.util.Log`，tag 固定 `ReleaseKite`。
 */
class AndroidUpdateEnvironment(context: Context) : UpdateEnvironment {

    private val appContext = context.applicationContext

    private val packageInfo: PackageInfo? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    override val workDir: File = ReleaseKitePaths.updatesDir(appContext)

    override val currentVersionName: String = packageInfo?.versionName.orEmpty()

    override val currentVersionCode: Long =
        packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

    override val isAppForeground: Boolean
        get() = ReleaseKiteForegroundTracker.isForeground

    override fun log(message: String, error: Throwable?) {
        if (error == null) {
            Log.d(TAG, message)
        } else {
            Log.w(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "ReleaseKite"
    }
}
