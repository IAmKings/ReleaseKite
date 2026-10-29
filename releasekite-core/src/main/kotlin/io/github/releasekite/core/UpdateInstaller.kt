package io.github.releasekite.core

import java.io.File

/**
 * APK 安装与校验契约。
 *
 * 移植自 ZhiBox `UpdateInstaller.kt`：接口留在 core，Android 实现（FileProvider +
 * `ACTION_VIEW` + `canRequestPackageInstalls`）留在 `releasekite` 模块。
 */

/** APK 安装结果（manager 据此切换状态机）。 */
sealed interface InstallResult {
    /** 已拉起系统安装器（应用会进入后台）。 */
    data object Launched : InstallResult

    /** 缺少「安装未知应用」授权，需要用户去系统设置。 */
    data object PermissionRequired : InstallResult

    data class Failed(val failure: UpdateFailure) : InstallResult
}

/** APK 包名/版本号/签名校验（安装前的最后一道防线）。 */
interface ApkValidator {
    /** 通过返回 null；不通过返回对应失败原因。 */
    fun validate(apk: File): UpdateFailure?
}

/**
 * 系统安装器封装：
 * - Android 8+ 需要 `canRequestPackageInstalls()`（REQUEST_INSTALL_PACKAGES + 用户授权）；
 * - 通过 FileProvider 把工作目录下的 APK 交给安装器；
 * - 只允许安装本应用自身（包名/versionCode/签名由平台的 [ApkValidator] 校验）。
 */
interface UpdateInstaller {
    fun canInstallPackages(): Boolean

    /** 拉起安装器；**实现负责切 Main 线程**（契约 §1.9 替换点）。 */
    suspend fun install(apk: File): InstallResult

    /** 打开「安装未知应用」授权设置页（实现内部处理 Activity/Context 细节）。 */
    fun openInstallPermissionSettings()
}
