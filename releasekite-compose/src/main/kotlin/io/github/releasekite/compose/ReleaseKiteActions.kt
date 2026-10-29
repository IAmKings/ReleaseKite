package io.github.releasekite.compose

/**
 * 更新弹窗的动作集合。
 *
 * 弹窗本身无状态、不持有 [io.github.releasekite.core.UpdateManager]；宿主（或测试）把
 * manager 的方法包装成这里的四个回调即可复用弹窗，也便于预览与单元测试注入 fake 动作。
 */
data class ReleaseKiteActions(
    /** 「立即更新」：开始下载。 */
    val onDownload: () -> Unit,
    /** 关闭弹窗（可用态「稍后」/失败态「关闭」「暂时退出」/权限态「取消」）。 */
    val onDismiss: () -> Unit,
    /** 失败态「重试」：重新入队下载。 */
    val onRetry: () -> Unit,
    /** 安装权限态「去设置」：跳系统「安装未知应用」页。 */
    val onOpenInstallSettings: () -> Unit,
)
