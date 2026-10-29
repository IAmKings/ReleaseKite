package io.github.releasekite.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.DownloadWorkState
import io.github.releasekite.core.UpdateDownloadScheduler
import io.github.releasekite.core.UpdateFailure
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
 */
class WorkManagerUpdateDownloadScheduler(context: Context) : UpdateDownloadScheduler {

    private val appContext = context.applicationContext

    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit) {
        val workRequest = OneTimeWorkRequestBuilder<ReleaseKiteDownloadWorker>()
            // 不加 Network/Storage 约束（回归 4c2a56c，见类注释）；仅保留指数退避。
            .setInputData(UpdateDownloadContract.inputData(request))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(UpdateDownloadContract.TAG)
            .build()
        // 回归 746b297/733f78a：必须在任务实际提交前同步回传 id，
        // 让 Manager 先绑定本次下载，避免 RUNNING 观察早于 id 绑定被看门狗误杀。
        onRequestId(workRequest.id.toString())
        // 等待 WorkManager 异步入队结果；失败会抛异常，由 Manager 转为可重试失败态。
        workManager.enqueueUniqueWork(
            UpdateDownloadContract.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        ).await()
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
            .map { infos -> infos.firstOrNull()?.toDownloadState() ?: DownloadWorkState() }
            .distinctUntilChanged()

    private fun WorkInfo.toDownloadState(): DownloadWorkState = when (state) {
        WorkInfo.State.RUNNING -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.RUNNING,
            downloadedBytes = progress.getLong(UpdateDownloadContract.KEY_DOWNLOADED, 0L),
            totalBytes = progress.getLong(UpdateDownloadContract.KEY_TOTAL, -1L),
        )

        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.WAITING,
        )

        WorkInfo.State.SUCCEEDED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.SUCCEEDED,
            apkPath = outputData.getString(UpdateDownloadContract.OUT_APK_PATH),
        )

        WorkInfo.State.FAILED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.FAILED,
            failure = UpdateDownloadContract.decodeUpdateFailure(
                outputData.getString(UpdateDownloadContract.OUT_FAILURE),
            ),
        )

        WorkInfo.State.CANCELLED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.CANCELLED,
            failure = UpdateFailure.Cancelled,
        )
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
