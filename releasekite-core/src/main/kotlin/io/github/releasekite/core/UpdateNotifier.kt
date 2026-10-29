package io.github.releasekite.core

/**
 * 通知契约：由 manager 在下载完成/清理待安装记录时调用。
 *
 * 移植自 ZhiBox `UpdateNotifications` 的调用点；Android 实现（通知渠道、通知权限、
 * PendingIntent、`R.drawable` 图标）留在 `releasekite` 模块，core 不引任何 android.*。
 */
interface UpdateNotifier {
    /** 创建通知渠道等一次性准备；等价源 `UpdateNotifications.ensureChannel` 的调用点。 */
    fun prepare()

    /** 后台下载完成通知：用户点击进入应用并触发安装。 */
    fun notifyDownloadComplete(info: UpdateInfo)

    /** 取消完成通知（等价源 `UpdateNotifications.cancelComplete`）。 */
    fun cancelDownloadComplete()
}
