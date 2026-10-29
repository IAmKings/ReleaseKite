package io.github.releasekite.internal

/**
 * 进程内前后台状态（供 Worker 在下载完成后决定「自动拉起安装器」还是「发点击安装通知」）。
 *
 * 由宿主生命周期（`ReleaseKite.onForeground()/onBackground()` 或 `ReleaseKiteHost`）更新。
 * 与 ZhiBox `AppForegroundTracker` 语义一致：进程被杀后由系统重建 Worker 时默认 false，
 * 因此冷启动的下载完成结果会走通知分支，不会在后台非法启动 Activity。
 *
 * 注意：状态只在本进程内有效；进程重启后默认后台，属于有意的保守降级。
 */
object ReleaseKiteForegroundTracker {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun onForeground() {
        isForeground = true
    }

    fun onBackground() {
        isForeground = false
    }
}
