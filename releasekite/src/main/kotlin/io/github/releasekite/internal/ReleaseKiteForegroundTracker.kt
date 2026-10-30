package io.github.releasekite.internal

import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内前后台状态（引用计数版），供 Worker 在下载完成后决定「自动拉起安装器」还是「发点击安装通知」。
 *
 * 为什么用计数而不是布尔（三方深审 P2）：Android 多 Activity 生命周期不是严格成对回调，
 * 典型顺序是 A.onStart → B.onStart → A.onStop；单布尔会在 A.onStop 时把前台误置后台，
 * 导致后台完成通知/自动安装判断错误。计数语义下多窗口重叠仍保持前台，全部 stop 才回后台。
 *
 * 调用约定：宿主在每个窗口/Activity 的 onStart/回到前台时 [onForeground]（+1），
 * onStop/退到后台时 [onBackground]（-1）；计数 clamp 在 >= 0，重复 onBackground 不会变负。
 *
 * 与 ZhiBox `AppForegroundTracker` 语义一致的部分：状态只在本进程内有效；
 * 进程被杀后系统重建 Worker 时计数为 0（后台），因此冷启动的下载完成结果会走通知分支，
 * 不会在后台非法启动 Activity，属于有意的保守降级。
 */
object ReleaseKiteForegroundTracker {

    private val foregroundCount = AtomicInteger(0)

    /** 前台 = 至少一个窗口处于 started 状态。 */
    val isForeground: Boolean
        get() = foregroundCount.get() > 0

    /** 一个窗口回到前台（引用 +1）。 */
    fun onForeground() {
        foregroundCount.incrementAndGet()
    }

    /** 一个窗口退到后台（引用 -1，clamp >= 0）。 */
    fun onBackground() {
        foregroundCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    /** 仅测试 / 进程级重置使用：计数清零（等价于无任何前台窗口）。 */
    internal fun reset() {
        foregroundCount.set(0)
    }
}
