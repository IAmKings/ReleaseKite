package io.github.releasekite

import android.content.Context
import io.github.releasekite.core.GitHubUpdateSource
import io.github.releasekite.core.ReleaseKiteConfig
import io.github.releasekite.core.UpdateManager
import io.github.releasekite.internal.AndroidApkValidator
import io.github.releasekite.internal.AndroidUpdateEnvironment
import io.github.releasekite.internal.AndroidUpdateInstaller
import io.github.releasekite.internal.AndroidUpdateNotifier
import io.github.releasekite.internal.ReleaseKiteForegroundTracker
import io.github.releasekite.internal.SharedPrefsUpdateStore
import io.github.releasekite.internal.WorkManagerUpdateDownloadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ReleaseKite Android 门面：按契约用默认实现组装 [UpdateManager]（进程内单例）。
 *
 * 生命周期约定：
 * - `Application.onCreate()`（或宿主自定义入口）调用一次 [initialize]；
 * - `onForeground()/onBackground()` 只维护下载完成时「自动拉起安装器 vs 发通知」需要的
 *   进程内前后台标记，**不会**驱动 `UpdateManager.onAppResumed()`；
 * - `onAppEntered()/onAppResumed()` 由 UI 层（`ReleaseKiteHost` 或宿主自己）按 Activity
 *   生命周期驱动，避免门面与 UI 重复触发同一状态机。
 */
object ReleaseKite {

    @Volatile
    private var instance: UpdateManager? = null

    /**
     * 初始化全局管理器；重复调用幂等（返回首次创建的实例，后续 config 被忽略）。
     *
     * 契约 §2：从 PackageManager 读取宿主版本名/版本号，默认 UA 注入库版本与宿主包名
     * （仅当 `config.userAgent` 仍是 core 默认值 `"ReleaseKite"` 时覆盖）。
     */
    fun initialize(context: Context, config: ReleaseKiteConfig): UpdateManager {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(context.applicationContext, config).also { instance = it }
        }
    }

    /** 已初始化的全局管理器；未初始化时抛 [IllegalStateException]。 */
    val manager: UpdateManager
        get() = instance
            ?: error("ReleaseKite 尚未初始化：请先在 Application/入口处调用 ReleaseKite.initialize(context, config)")

    fun isInitialized(): Boolean = instance != null

    /** 宿主回到前台：Worker 据此选择「直接拉起安装器」而不是发完成通知。 */
    fun onForeground() {
        ReleaseKiteForegroundTracker.onForeground()
    }

    /** 宿主退到后台：后台下载完成后改发点击安装通知。 */
    fun onBackground() {
        ReleaseKiteForegroundTracker.onBackground()
    }

    private fun create(appContext: Context, config: ReleaseKiteConfig): UpdateManager {
        val effectiveConfig = withDefaultUserAgent(appContext, config)
        return UpdateManager(
            config = effectiveConfig,
            environment = AndroidUpdateEnvironment(appContext),
            source = GitHubUpdateSource(effectiveConfig),
            scheduler = WorkManagerUpdateDownloadScheduler(appContext),
            installer = AndroidUpdateInstaller(appContext),
            validator = AndroidApkValidator(appContext),
            store = SharedPrefsUpdateStore(appContext),
            notifier = AndroidUpdateNotifier(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = System::currentTimeMillis,
        )
    }

    /** 宿主未显式覆盖 UA 时注入库版本与宿主包名，便于 GitHub 侧识别调用方。 */
    private fun withDefaultUserAgent(context: Context, config: ReleaseKiteConfig): ReleaseKiteConfig =
        if (config.userAgent == DEFAULT_USER_AGENT) {
            config.copy(
                userAgent = "ReleaseKite/${BuildConfig.RELEASEKITE_VERSION} (${context.packageName})",
            )
        } else {
            config
        }

    private const val DEFAULT_USER_AGENT = "ReleaseKite"
}
