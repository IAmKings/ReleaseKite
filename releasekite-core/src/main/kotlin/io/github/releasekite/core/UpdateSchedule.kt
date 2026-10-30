package io.github.releasekite.core

import kotlinx.coroutines.flow.Flow

/**
 * 下载调度契约（Android 模块用 WorkManager 实现，测试用 fake）。
 *
 * 移植自 ZhiBox `UpdateDownloadScheduler.kt`；相对源接口多了一个 [DownloadRequest]：
 * 源把 `UpdateInfo` 直接交给 WorkManager 调度器（调度器内部再读 `UpdateConfig.*`），
 * ReleaseKite 把下载所需配置随请求一起下发，core 不依赖任何编译期常量。
 */

/**
 * 一次后台下载请求。
 *
 * 字段与 Android Worker 的 input Data 一一对应（契约 §2）：
 * `version` / `apk_url` / `apk_size` / `sha256` / `sha256_url` / `file_prefix` /
 * `user_agent` / `mirrors` / `require_checksum` / `timeout_ms`。
 */
data class DownloadRequest(
    val info: UpdateInfo,
    /** GitHub 仓库 `owner/repo`：Worker 进程内重建下载器时校验 release 路径白名单。 */
    val repository: String,
    /** 下载文件名前缀（已清洗，见 [ReleaseKiteConfig.sanitizedDownloadFilePrefix]）。 */
    val filePrefix: String,
    val userAgent: String,
    val mirrors: List<String>,
    val requireChecksum: Boolean,
    val timeoutMs: Long,
)

/** 下载工作状态（manager 订阅后映射为 [UpdatePhase]）。 */
data class DownloadWorkState(
    /** 后台任务 id（a253ff9：任务归属判断，跨进程/旧回调不能影响新重试）。 */
    val workId: String? = null,
    val status: Status = Status.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val apkPath: String? = null,
    val failure: UpdateFailure? = null,
    /** 与任务 ID 绑定的持久化下载目标，独立于最新版本检查缓存。 */
    val info: UpdateInfo? = null,
) {
    enum class Status { IDLE, WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

/** 后台下载调度抽象（便于 manager 单测注入 fake，不依赖 WorkManager/Robolectric）。 */
interface UpdateDownloadScheduler {
    /**
     * 入队并等待后台任务结果。
     *
     * [onRequestId] 必须在任务实际提交前同步回调，让 Manager 先绑定任务 id，
     * 避免 RUNNING 观察早于 enqueue 返回时被看门狗误杀（回归提交 4c2a56c/746b297/733f78a）。
     * 回调抛异常表示拒绝提交；实现必须在修改持久化目标和入队之前调用并传播异常。
     */
    suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit)

    fun observe(): Flow<DownloadWorkState>

    /** 终止并清理下载任务；传入 workId 时只取消该任务，避免旧回调取消新重试。 */
    fun cancel(workId: String? = null)
}
