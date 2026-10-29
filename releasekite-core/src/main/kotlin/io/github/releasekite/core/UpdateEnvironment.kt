package io.github.releasekite.core

import java.io.File

/**
 * 宿主环境抽象：把 ZhiBox `AppUpdateManager` 里对 `Context` / `BuildConfig` /
 * `AppForegroundTracker` 的直接依赖收敛为可 fake 的接口（契约 §1.9 替换点）。
 *
 * 纯 JVM 单测只需实现本接口即可驱动完整状态机，无需 Robolectric/Context。
 */
interface UpdateEnvironment {
    /** APK 下载/安装工作目录（Android 侧为 `cacheDir/releasekite/updates`）。 */
    val workDir: File

    /** 当前已安装版本名（Android 侧来源 `PackageInfo.versionName`；替代 `BuildConfig.VERSION_NAME`）。 */
    val currentVersionName: String

    /** 当前已安装 versionCode（Android 安装校验/诊断使用）。 */
    val currentVersionCode: Long

    /** 应用是否在前台（后台完成下载时发通知、前台直接拉起安装器）。 */
    val isAppForeground: Boolean

    /** 日志出口（Android 侧映射到 Log；测试可忽略）。 */
    fun log(message: String, error: Throwable? = null)
}
