package io.github.releasekite.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.releasekite.core.InstallResult
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统安装器封装（契约 §2）：
 * - Android 8+ 需要 `canRequestPackageInstalls()`（REQUEST_INSTALL_PACKAGES + 用户授权）；
 * - 通过 FileProvider（authority `${packageName}.releasekite.updates`）把
 *   `cacheDir/releasekite/updates` 下的 APK 以 content:// 交给系统安装器；
 * - `install()` 是 suspend 且**实现内部切 Main**（契约 §1.8）：
 *   Manager 不再负责线程切换，`startActivity` 必须在主线程发起。
 */
class AndroidUpdateInstaller(context: Context) : UpdateInstaller {

    private val appContext = context.applicationContext

    private val authority = "${appContext.packageName}.releasekite.updates"

    override fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return appContext.packageManager.canRequestPackageInstalls()
    }

    override suspend fun install(apk: File): InstallResult = withContext(Dispatchers.Main) {
        if (!canInstallPackages()) return@withContext InstallResult.PermissionRequired
        try {
            val uri: Uri = FileProvider.getUriForFile(appContext, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            InstallResult.Launched
        } catch (e: Exception) {
            InstallResult.Failed(UpdateFailure.InstallFailed)
        }
    }

    /**
     * 打开「安装未知应用」授权设置页。
     *
     * [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] 从 API 26 起才有：低版本没有该设置页
     * （`canInstallPackages()` 在 API < 26 恒 true），直接 no-op，避免启动不存在的 Activity
     * 或触发 InlinedApi 误用（三方深审 P2）。
     */
    @SuppressLint("InlinedApi")
    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
