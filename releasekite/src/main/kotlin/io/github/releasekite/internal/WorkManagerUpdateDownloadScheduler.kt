package io.github.releasekite.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.DownloadWorkState
import io.github.releasekite.core.UpdateDownloadScheduler
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WorkManager 实现的 [UpdateDownloadScheduler]（契约 §2）。
 *
 * 契约要点（逐条对应 ZhiBox 回归修复）：
 * - 唯一工作 [UpdateDownloadContract.UNIQUE_WORK] + tag [UpdateDownloadContract.TAG]，
 *   `ExistingWorkPolicy.REPLACE` 保证重试不会叠任务；
 * - **无** Network/Storage 约束（回归 4c2a56c）：约束导致任务长期 ENQUEUED，
 *   UI 会把它映射成不可取消的「0% 下载」，网络预检改由 Worker 执行；
 * - `onRequestId` 在真正提交前同步回调（回归 746b297/733f78a）：Manager 先绑定
 *   WorkRequest id，迟到入队的旧结果才能被识别并清理；
 * - `enqueue` 等待异步入队结果（`await()` 失败抛异常，Manager 转为可重试失败态）。
 *
 * R2 加固（三方深审）：
 * - [enqueue] 用 [Mutex] 把「绑定 target → 回调 workId → 提交任务」包成原子步骤，
 *   并发入队时不会出现 A 的 target 被 B 覆盖后 A 才提交的单槽错位；
 * - [observe] 的 map 里**不再 await 取消操作**、target 读取 runCatching 兜底：
 *   map 内抛异常会终止整条 StateFlow 上游 collector，Manager 将永久失去下载状态流；
 * - WorkRequest → [DownloadWorkState] 映射抽成 [toDownloadState]，便于直接覆盖
 *   WAITING/RUNNING/SUCCEEDED 等状态（含绑定的 target info）。
 */
class WorkManagerUpdateDownloadScheduler(context: Context) : UpdateDownloadScheduler {

    private val appContext = context.applicationContext

    private val workManager = WorkManager.getInstance(appContext)

    /** 下载目标（UpdateInfo + workId）独立于主状态 prefs，避免检查缓存刷新污染任务归属。 */
    private val targets = SharedPrefsUpdateStore(
        appContext,
        SharedPrefsUpdateStore.TARGET_PREFS_NAME,
    )

    /** 单槽 target 的并发保护：绑定 + 回调 + 入队必须原子完成。 */
    private val enqueueMutex = Mutex()

    override suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit) {
        enqueueMutex.withLock {
            val workRequest = buildWorkRequest(request)
            val workId = workRequest.id.toString()
            // 先持久化「任务 id ↔ 下载目标」绑定（独立 prefs，进程被杀后仍可按 id 恢复）。
            targets.bindDownloadTarget(workId, request.info)
            // 回归 746b297/733f78a：必须在任务实际提交前同步回传 id，
            // 让 Manager 先绑定本次下载，避免 RUNNING 观察早于 id 绑定被看门狗误杀。
            onRequestId(workId)
            // 等待 WorkManager 异步入队结果；失败会抛异常，由 Manager 转为可重试失败态。
            workManager.enqueueUniqueWork(
                UpdateDownloadContract.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            ).await()
        }
    }

    override fun cancel(workId: String?) {
        if (workId == null) {
            workManager.cancelUniqueWork(UpdateDownloadContract.UNIQUE_WORK)
        } else {
            // 只取消指定任务，避免旧回调取消刚重试的新任务（回归 746b297）。
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
                .onFailure { workManager.cancelUniqueWork(UpdateDownloadContract.UNIQUE_WORK) }
        }
    }

    override fun observe(): Flow<DownloadWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(UpdateDownloadContract.UNIQUE_WORK)
            .map { infos ->
                val work = infos.firstOrNull() ?: return@map DownloadWorkState()
                // target 读取失败（prefs 异常/损坏）不能终止状态流：降级为「无目标任务」。
                val target = runCatching { targets.downloadTarget(work.id.toString()) }.getOrNull()
                if (target == null) {
                    // 旧任务没有可靠目标，保留半包供重试，不能把最新缓存误当作它的身份。
                    // 取消必须 fire-and-forget：在 map 里 await() 一旦抛异常会终止整个
                    // observe collector（Manager 永久收不到后续下载状态）。
                    if (!work.state.isFinished) {
                        runCatching { workManager.cancelWorkById(work.id) }
                    }
                    DownloadWorkState()
                } else {
                    work.toDownloadState(target)
                }
            }
            .distinctUntilChanged()

    private fun buildWorkRequest(request: DownloadRequest): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ReleaseKiteDownloadWorker>()
            // 不加 Network/Storage 约束（回归 4c2a56c，见类注释）；仅保留指数退避。
            .setInputData(UpdateDownloadContract.inputData(request))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(UpdateDownloadContract.TAG)
            .build()

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * [WorkInfo] → [DownloadWorkState] 映射（含与任务 id 绑定的持久化 target）。
 *
 * 抽成 internal 顶层函数便于测试直接构造 [WorkInfo] 覆盖 WAITING/RUNNING/SUCCEEDED
 * 等状态，而不依赖真实 Worker 执行时序（TestScheduler 会自动执行无约束任务）。
 */
internal fun WorkInfo.toDownloadState(target: UpdateInfo? = null): DownloadWorkState = when (state) {
    WorkInfo.State.RUNNING -> DownloadWorkState(
        workId = id.toString(),
        status = DownloadWorkState.Status.RUNNING,
        downloadedBytes = progress.getLong(UpdateDownloadContract.KEY_DOWNLOADED, 0L),
        totalBytes = progress.getLong(UpdateDownloadContract.KEY_TOTAL, -1L),
        info = target,
    )

    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadWorkState(
        workId = id.toString(),
        status = DownloadWorkState.Status.WAITING,
        info = target,
    )

    WorkInfo.State.SUCCEEDED -> DownloadWorkState(
        workId = id.toString(),
        status = DownloadWorkState.Status.SUCCEEDED,
        apkPath = outputData.getString(UpdateDownloadContract.OUT_APK_PATH),
        info = target,
    )

    WorkInfo.State.FAILED -> DownloadWorkState(
        workId = id.toString(),
        status = DownloadWorkState.Status.FAILED,
        failure = UpdateDownloadContract.decodeUpdateFailure(
            outputData.getString(UpdateDownloadContract.OUT_FAILURE),
        ),
        info = target,
    )

    WorkInfo.State.CANCELLED -> DownloadWorkState(
        workId = id.toString(),
        status = DownloadWorkState.Status.CANCELLED,
        failure = UpdateFailure.Cancelled,
        info = target,
    )
}
