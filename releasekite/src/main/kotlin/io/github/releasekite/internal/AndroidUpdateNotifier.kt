package io.github.releasekite.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.releasekite.R
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdateNotifier

/**
 * 应用更新通知（契约 §2）：渠道 `releasekite_update`，进度 4201 / 完成 4202 /
 * PendingIntent requestCode 4203，小图标 `R.drawable.releasekite_ic_notification`。
 *
 * 与 ZhiBox `UpdateNotifications` 的差异：
 * - 通知点击不再硬编码宿主 MainActivity，而是用 PackageManager 的 launch intent 打开宿主，
 *   并携带 [EXTRA_INSTALL_UPDATE] 供宿主决定是否立即 `installDownloadedIfReady()`；
 * - 进度通知/取消是 Android 实现细节（契约 §2），以 public 方法供 Worker 使用，
 *   core 的 [UpdateNotifier] 接口保持 prepare/notifyDownloadComplete/cancelDownloadComplete。
 */
class AndroidUpdateNotifier(context: Context) : UpdateNotifier {

    private val appContext = context.applicationContext

    /** 创建通知渠道；重复调用幂等（渠道已存在时直接返回）。 */
    override fun prepare() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.releasekite_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.releasekite_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    /** 前台服务/通知栏进度通知（不直接 notify；由 Worker 的 setForeground 负责）。 */
    fun progressNotification(
        info: UpdateInfo,
        downloadedBytes: Long,
        totalBytes: Long,
    ): Notification {
        prepare()
        val total = totalBytes.takeIf { it > 0 }
        val pct = if (total != null) {
            (downloadedBytes * 100 / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.releasekite_ic_notification)
            .setContentTitle(appContext.getString(R.string.releasekite_notification_progress_title))
            .setContentText(
                appContext.getString(
                    R.string.releasekite_notification_progress_text,
                    info.versionName,
                    pct,
                ),
            )
            .setProgress(100, pct, total == null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 更新通知栏进度（轮询调用；权限被拒/渠道关闭时静默失败）。
     *
     * 通知是 **best-effort**：Android 13+ 的 POST_NOTIFICATIONS 与系统渠道开关已由
     * [canPostNotifications] 在调用前检查，`runCatching` 再兜底 SecurityException；
     * 权限被拒时通知静默不可见，但下载/校验/前台续装链路完全不受影响（源实现同样 best-effort）。
     * 因此这里用 @SuppressLint 声明「已显式检查 + 已处理异常」，而不是把检查内联到 notify 紧邻处重复。
     */
    @SuppressLint("MissingPermission")
    fun notifyProgress(info: UpdateInfo, downloadedBytes: Long, totalBytes: Long) {
        runCatching {
            if (!canPostNotifications()) return
            NotificationManagerCompat.from(appContext).notify(
                PROGRESS_NOTIFICATION_ID,
                progressNotification(info, downloadedBytes, totalBytes),
            )
        }
    }

    /** 下载结束（成功/失败/取消）时清掉常驻进度通知。 */
    fun cancelProgress() {
        runCatching { NotificationManagerCompat.from(appContext).cancel(PROGRESS_NOTIFICATION_ID) }
    }

    /**
     * 后台下载完成通知：点击进入宿主应用并携带安装标记。
     *
     * 与 [notifyProgress] 相同的 best-effort 语义：权限/渠道检查在前（[canPostNotifications]），
     * `runCatching` 兜底 SecurityException；通知不可见不影响 APK 已持久化到 UpdateStore，
     * 用户下次回到应用时 Manager 仍会自动续装（`onAppResumed → installDownloadedIfReady`）。
     */
    @SuppressLint("MissingPermission")
    override fun notifyDownloadComplete(info: UpdateInfo) {
        runCatching {
            prepare()
            if (!canPostNotifications()) return
            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.releasekite_ic_notification)
                .setContentTitle(
                    appContext.getString(R.string.releasekite_notification_complete_title),
                )
                .setContentText(
                    appContext.getString(
                        R.string.releasekite_notification_complete_text,
                        info.versionName,
                    ),
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            launchPendingIntent()?.let { builder.setContentIntent(it) }
            NotificationManagerCompat.from(appContext)
                .notify(COMPLETE_NOTIFICATION_ID, builder.build())
        }
    }

    override fun cancelDownloadComplete() {
        runCatching { NotificationManagerCompat.from(appContext).cancel(COMPLETE_NOTIFICATION_ID) }
    }

    /**
     * 宿主启动入口的 PendingIntent：库态下不引用宿主 Activity 类名，
     * 用 `getLaunchIntentForPackage` + 安装标记 extra（宿主在入口读取并调用安装）。
     */
    private fun launchPendingIntent(): PendingIntent? {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_INSTALL_UPDATE, true)
        return PendingIntent.getActivity(
            appContext,
            REQUEST_INSTALL,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    companion object {
        /** 通知渠道 id（契约 §2）。 */
        const val CHANNEL_ID = "releasekite_update"

        /** 下载进度/前台服务通知 id（契约 §2）。 */
        const val PROGRESS_NOTIFICATION_ID = 4201

        /** 下载完成通知 id（契约 §2）。 */
        const val COMPLETE_NOTIFICATION_ID = 4202

        /** 「点击安装」PendingIntent requestCode（契约 §2）。 */
        const val REQUEST_INSTALL = 4203

        /** 通知点击时附带给宿主的 extra：宿主入口可据此调用 installDownloadedIfReady()。 */
        const val EXTRA_INSTALL_UPDATE = "io.github.releasekite.extra.INSTALL_UPDATE"
    }
}
