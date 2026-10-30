package io.github.releasekite.internal

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.HttpUpdateDownloader
import io.github.releasekite.core.ReleaseKiteConfig
import io.github.releasekite.core.UpdateException
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.isNewerVersion
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 后台下载 Worker（契约 §2）：WorkManager 持久化任务，进程被杀后由系统重新调度；
 * `.part` 续传、镜像回退、SHA 校验全部复用 core 的 [HttpUpdateDownloader]。
 *
 * 与 ZhiBox `AppUpdateDownloadWorker` 的移植关系（行为全部保留）：
 * - 回归 4c2a56c：调度器不加 Network/Storage 约束，改在 Worker 内做网络预检，
 *   失败快速进入可重试状态，避免任务卡在 ENQUEUED 被 UI 映射成「正在下载 0%」；
 * - 回归 a253ff9：前台服务通知展示进度；即使 Manager/Activity 不在（系统仅为 Worker
 *   重建进程），也持久化待安装记录并按前后台决定是否发完成通知；
 * - 下载整体超时（`timeout_ms`，源 5min）→ [UpdateFailure.Timeout]；
 * - 失败原因编码写入输出 Data（`failure`），由调度器解码回 [UpdateFailure]。
 *
 * 相对源的差异：不再引用宿主 BuildConfig/UpdateConfig/UpdateNotifications/R，
 * 全部通过 [UpdateDownloadContract] 输入、core 配置重建与 [AndroidUpdateNotifier] 完成。
 */
class ReleaseKiteDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 输入解析失败（旧版本任务数据/非法入队）：快速失败，避免 WorkManager 反复重试。
        val request = UpdateDownloadContract.parseRequest(inputData)
            ?: return Result.failure(failureData(UpdateFailure.Parse))
        // 回归 4c2a56c：约束会让任务长期停留在 ENQUEUED（UI 显示 0% 且不可取消），
        // 改为 Worker 内网络预检——无网络立即失败，交给 Manager 展示可重试失败态。
        if (!hasInternet(applicationContext)) {
            return Result.failure(failureData(UpdateFailure.Network))
        }
        if (request.info.apkUrl.isBlank()) {
            return Result.failure(failureData(UpdateFailure.IncompleteRelease))
        }
        val environment = AndroidUpdateEnvironment(applicationContext)
        if (!isNewerVersion(request.info.versionName, environment.currentVersionName)) {
            // 目标版本已安装（用户可能在下载期间完成升级）：契约 §2 规定返回 success 且
            // 无 apk_path，Manager 按「SUCCEEDED + 无路径」清理（回归 746b297）。
            return Result.success()
        }

        val context = applicationContext
        val notifier = AndroidUpdateNotifier(context)
        // 三方深审 P1：Android 12+ 后台 FGS 启动受限时 prepare/setForeground 会抛异常；
        // 若逃出 doWork()，WorkManager 会把任务置终态 FAILED 且不带 failure Data（Manager 误报
        // Network）。这里降级为 best-effort：失败只记日志，继续无 FGS 下载（进度通知不可见）。
        startForegroundBestEffort(
            notifier = notifier,
            info = request.info,
            setForeground = { notification -> setForeground(foregroundInfo(notification)) },
            log = environment::log,
        )

        val store = SharedPrefsUpdateStore(context)
        val targetDir = ReleaseKitePaths.updatesDir(context)
        val downloadedBytes = AtomicLong(0L)
        val totalBytes = AtomicLong(-1L)
        return try {
            val file = coroutineScope {
                // WorkManager.setProgress 是 suspend，而下载回调是同步的：
                // 用独立 ticker 周期性上报进度 Data 与通知栏进度（源实现一致）。
                val ticker = launch {
                    while (isActive) {
                        delay(NOTIFY_INTERVAL_MS)
                        val downloaded = downloadedBytes.get()
                        val total = totalBytes.get()
                        if (downloaded > 0L) {
                            setProgress(
                                workDataOf(
                                    UpdateDownloadContract.KEY_DOWNLOADED to downloaded,
                                    UpdateDownloadContract.KEY_TOTAL to total,
                                ),
                            )
                            notifier.notifyProgress(request.info, downloaded, total)
                        }
                    }
                }
                try {
                    val download: suspend () -> File = {
                        downloader(request).download(
                            info = request.info,
                            targetDir = targetDir,
                            filePrefix = request.filePrefix,
                            routeHealth = store.routeHealth(),
                            onRouteResult = { updated ->
                                val health = store.routeHealth().toMutableMap()
                                health[updated.id] = updated
                                store.updateRouteHealth(health)
                            },
                            onProgress = { downloaded, total ->
                                downloadedBytes.set(downloaded)
                                totalBytes.set(total)
                            },
                        )
                    }
                    if (request.timeoutMs > 0L) {
                        withTimeout(request.timeoutMs) { download() }
                    } else {
                        download()
                    }
                } finally {
                    ticker.cancel()
                }
            }
            notifier.cancelProgress()
            // 回归 a253ff9：后台完成也写待安装记录；前台由 Manager 直接拉起安装器，
            // 后台发「点击安装」通知（Android 后台启动 Activity 受限）。
            store.downloadedVersion = request.info.versionName
            store.downloadedApkPath = file.absolutePath
            if (!environment.isAppForeground) {
                notifier.notifyDownloadComplete(request.info)
            }
            Result.success(workDataOf(UpdateDownloadContract.OUT_APK_PATH to file.absolutePath))
        } catch (e: TimeoutCancellationException) {
            notifier.cancelProgress()
            Result.failure(failureData(UpdateFailure.Timeout))
        } catch (e: UpdateException) {
            notifier.cancelProgress()
            Result.failure(failureData(e.failure))
        } catch (e: CancellationException) {
            // 用户取消/系统终止：保留取消语义，Manager 走 CANCELLED 分支清理。
            throw e
        } catch (e: Exception) {
            notifier.cancelProgress()
            Result.failure(failureData(UpdateFailure.Network))
        }
    }

    /**
     * Worker 可能在独立进程冷启动，拿不到门面持有的 config：
     * 用输入契约里的 repository/mirrors/requireChecksum 重建下载器配置。
     */
    private fun downloader(request: DownloadRequest): HttpUpdateDownloader {
        val config = ReleaseKiteConfig(
            repository = request.repository,
            // 镜像通过构造参数显式传入，避免 config 的 https/末尾斜杠校验与 Worker 兜底冲突。
            downloadMirrors = emptyList(),
            requireChecksum = request.requireChecksum,
            userAgent = request.userAgent,
        )
        return HttpUpdateDownloader(
            config = config,
            userAgent = request.userAgent,
            mirrorPrefixes = request.mirrors,
        )
    }

    private fun hasInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AndroidUpdateNotifier.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AndroidUpdateNotifier.PROGRESS_NOTIFICATION_ID, notification)
        }

    private fun failureData(failure: UpdateFailure) = workDataOf(
        UpdateDownloadContract.OUT_FAILURE to UpdateDownloadContract.encodeUpdateFailure(failure),
    )

    private companion object {
        const val NOTIFY_INTERVAL_MS = 1_000L
    }
}

/**
 * 前台服务通知启动的 best-effort 封装（三方深审 P1）。
 *
 * 背景：Android 12+ 对后台启动前台服务有严格限制，`setForeground()` 可能抛
 * `ForegroundServiceStartNotAllowedException` 等运行时异常；若任其逃出 `doWork()`，
 * WorkManager 会直接把 Worker 置为终态 FAILED 且没有 `failure` 输出 Data，
 * Manager 只能回退成 `UpdateFailure.Network`——把「通知没起来」误报成「网络失败」。
 *
 * 语义：
 * - 非取消异常 → 记日志后继续下载（通知/进度不可见，但 APK 下载、校验、待安装记录不受影响）；
 * - [CancellationException] → 原样抛出，Worker 被取消时保持 CANCELLED 语义。
 */
internal suspend fun startForegroundBestEffort(
    notifier: AndroidUpdateNotifier,
    info: UpdateInfo,
    setForeground: suspend (Notification) -> Unit,
    log: (String, Throwable?) -> Unit,
) {
    try {
        notifier.prepare()
        setForeground(notifier.progressNotification(info, 0L, info.apkSizeBytes))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("ReleaseKite：前台服务通知启动失败，降级为无 FGS 下载（下载/校验/待安装记录不受影响）", e)
    }
}
