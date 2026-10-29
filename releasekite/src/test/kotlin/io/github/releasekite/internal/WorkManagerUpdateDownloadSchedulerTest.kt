package io.github.releasekite.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.UpdateInfo
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WorkManagerUpdateDownloadScheduler] 的 WorkManager 集成回归（work-testing）。
 *
 * 覆盖契约 §2 的调度不变量：
 * - 唯一工作 `releasekite-update-download` + tag `releasekite-update`；
 * - **无** Network/Storage 约束（回归 4c2a56c：约束导致长期 ENQUEUED 的 0% 卡死）；
 * - `onRequestId` 在任务真正提交**之前**同步回调（回归 746b297/733f78a：
 *   Manager 先绑定 WorkRequest id，迟到入队结果才能被识别）；
 * - `cancel(workId)/cancel(null)` 分别映射到单任务/唯一工作取消。
 *
 * 注意：WorkManager 2.12 的 TestScheduler 会自动执行「无约束且无延迟」的任务，
 * 因此 cancel 用例改用带约束的同类 WorkRequest 保持 ENQUEUED 再取消，保证可观察且不依赖时序。
 * 输入 Data 的逐键映射由 [UpdateDownloadContractTest] 直接断言（WorkInfo 不暴露 inputData）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerUpdateDownloadSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun ensureTestWorkManager() {
        // Robolectric 同一 JVM 内多个测试类共享 WorkManager 单例：测试实现会替换 delegate，幂等安全。
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun enqueueBindsRequestIdBeforeSubmitAndKeepsNoConstraints() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)

            var callbackId: String? = null
            var workCountAtCallback = -1
            scheduler.enqueue(sampleRequest()) { id ->
                callbackId = id
                // 回归 746b297/733f78a：回调必须早于真正入队 —— 此刻唯一工作还不存在。
                workCountAtCallback = workManager
                    .getWorkInfosForUniqueWork(UpdateDownloadContract.UNIQUE_WORK)
                    .get(5, TimeUnit.SECONDS)
                    .size
            }

            val boundId = requireNotNull(callbackId) {
                "onRequestId 必须在 enqueue 返回前同步回调"
            }
            assertEquals("回调发生时任务尚未提交", 0, workCountAtCallback)

            val workInfo = workManager.workInfoOf(boundId)
            assertEquals(boundId, workInfo.id.toString())
            assertTrue("任务必须带 releasekite-update tag", workInfo.tags.contains(UpdateDownloadContract.TAG))
            // 回归 4c2a56c：无 Network/Storage 约束（任务状态不影响 WorkRequest 元数据）。
            assertEquals(NetworkType.NOT_REQUIRED, workInfo.constraints.requiredNetworkType)
            assertFalse(workInfo.constraints.requiresStorageNotLow())

            // TestScheduler 会自动执行无约束任务（Robolectric 下 Worker 网络预检失败 → FAILED）：
            // 等待其进入终态，避免测试结束仍残留后台执行。
            workManager.awaitFinished(boundId)
        }
    }

    @Test
    fun cancelByWorkIdCancelsOnlyThatWork() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)

            val pending = workManager.enqueueConstrainedWork()
            assertEquals(WorkInfo.State.ENQUEUED, workManager.workInfoOf(pending).state)

            scheduler.cancel(pending)

            assertEquals(WorkInfo.State.CANCELLED, workManager.workInfoOf(pending).state)
        }
    }

    @Test
    fun cancelWithoutWorkIdCancelsUniqueWork() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)

            val pending = workManager.enqueueConstrainedWork()
            assertEquals(WorkInfo.State.ENQUEUED, workManager.workInfoOf(pending).state)

            scheduler.cancel(null)

            assertEquals(WorkInfo.State.CANCELLED, workManager.workInfoOf(pending).state)
        }
    }

    /** 入队一个带「充电中」约束的同名任务：TestScheduler 不会自动执行，可稳定观察取消状态。 */
    private suspend fun WorkManager.enqueueConstrainedWork(): String {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<ReleaseKiteDownloadWorker>()
            .setInputData(UpdateDownloadContract.inputData(sampleRequest()))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .addTag(UpdateDownloadContract.TAG)
            .build()
        enqueueUniqueWork(
            UpdateDownloadContract.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        ).await()
        return request.id.toString()
    }

    private suspend fun WorkManager.workInfoOf(workId: String): WorkInfo {
        val id = UUID.fromString(workId)
        return getWorkInfoById(id).get(5, TimeUnit.SECONDS)
            ?: error("找不到 WorkRequest：$workId")
    }

    /** 轮询等待任务离开 ENQUEUED/RUNNING（避免测试结束后台任务仍在执行）。 */
    private suspend fun WorkManager.awaitFinished(workId: String) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (workInfoOf(workId).state.isFinished) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun sampleRequest() = DownloadRequest(
        info = UpdateInfo(
            versionName = "2.0.0",
            tagName = "v2.0.0",
            title = "2.0.0",
            notes = "",
            publishedAt = null,
            htmlUrl = "https://github.com/releasekite/ReleaseKite/releases/tag/v2.0.0",
            apkUrl = "https://github.com/releasekite/ReleaseKite/releases/download/v2.0.0/releasekite-2.0.0.apk",
            apkSizeBytes = 42L,
            sha256 = "d".repeat(64),
            sha256Url = null,
        ),
        repository = "releasekite/ReleaseKite",
        filePrefix = "releasekite",
        userAgent = "ReleaseKite/0.1.0 (host.app)",
        mirrors = emptyList(),
        requireChecksum = true,
        timeoutMs = 300_000L,
    )

    private companion object {
        const val WAIT_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
