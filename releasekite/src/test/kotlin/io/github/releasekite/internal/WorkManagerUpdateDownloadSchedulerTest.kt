package io.github.releasekite.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.DownloadWorkState
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Before
    fun resetPersistedState() {
        listOf(SharedPrefsUpdateStore.PREFS_NAME, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
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
    @Test
    fun legacyWorkWithoutBoundTargetIsCancelled() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val id = workManager.enqueueConstrainedWork()
            val state = withTimeout(5_000L) {
                WorkManagerUpdateDownloadScheduler(context).observe().first()
            }
            assertEquals(io.github.releasekite.core.DownloadWorkState.Status.IDLE, state.status)
            assertEquals(WorkInfo.State.CANCELLED, workManager.workInfoOf(id).state)
        }
    }

    @Test
    fun downloadTargetSurvivesSchedulerRecreationAndCacheRefresh() = runBlocking {
        withContext(Dispatchers.IO) {
            val request = sampleRequest().copy(info = sampleRequest().info.copy(
                minSupportedVersion = "2.0.0", metaUrl = "https://github.com/meta"))
            val scheduler = WorkManagerUpdateDownloadScheduler(context)
            var id = ""
            scheduler.enqueue(request) { id = it }
            SharedPrefsUpdateStore(context).saveCached(request.info.copy(versionName = "3.0.0"))
            val restored = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
            assertEquals(request.info, restored.downloadTarget(id))
            val state = withTimeout(5_000L) {
                WorkManagerUpdateDownloadScheduler(context).observe().first { it.workId == id }
            }
            assertEquals(request.info, state.info)
            assertEquals(null, restored.downloadTarget("other-work"))
            WorkManager.getInstance(context).awaitFinished(id)
        }
    }

    // ── R2 加固回归（三方深审） ──────────────────────────────────

    @Test
    fun observeMapsBoundTargetForWaitingWork() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)
            val request = sampleRequest()
            val work = buildConstrainedWork(request)
            SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
                .bindDownloadTarget(work.id.toString(), request.info)
            workManager.enqueueUniqueWork(
                UpdateDownloadContract.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                work,
            ).await()

            val state = withTimeout(5_000L) {
                scheduler.observe().first { it.workId == work.id.toString() }
            }
            assertEquals(DownloadWorkState.Status.WAITING, state.status)
            assertEquals("WAITING 状态必须携带与任务 id 绑定的 target", request.info, state.info)
        }
    }

    @Test
    fun toDownloadStateMapsRunningWithTargetAndProgress() {
        val request = sampleRequest()
        val workInfo = workInfo(
            WorkInfo.State.RUNNING,
            progress = workDataOf(
                UpdateDownloadContract.KEY_DOWNLOADED to 512L,
                UpdateDownloadContract.KEY_TOTAL to 1_024L,
            ),
        )

        val state = workInfo.toDownloadState(request.info)

        assertEquals(DownloadWorkState.Status.RUNNING, state.status)
        assertEquals(workInfo.id.toString(), state.workId)
        assertEquals(512L, state.downloadedBytes)
        assertEquals(1_024L, state.totalBytes)
        assertEquals("RUNNING 必须携带绑定 target", request.info, state.info)
    }

    @Test
    fun toDownloadStateMapsSucceededWithTargetAndApkPath() {
        val request = sampleRequest()
        val apkPath = "/tmp/releasekite/2.0.0.apk"
        val workInfo = workInfo(
            WorkInfo.State.SUCCEEDED,
            outputData = workDataOf(UpdateDownloadContract.OUT_APK_PATH to apkPath),
        )

        val state = workInfo.toDownloadState(request.info)

        assertEquals(DownloadWorkState.Status.SUCCEEDED, state.status)
        assertEquals(apkPath, state.apkPath)
        assertEquals("SUCCEEDED 必须携带绑定 target（跨进程安装恢复依赖它）", request.info, state.info)
    }

    @Test
    fun toDownloadStateMapsWaitingAndFailedFixedCodeWithTarget() {
        val request = sampleRequest()

        val waiting = workInfo(WorkInfo.State.ENQUEUED).toDownloadState(request.info)
        assertEquals(DownloadWorkState.Status.WAITING, waiting.status)
        assertEquals(request.info, waiting.info)

        val failed = workInfo(
            WorkInfo.State.FAILED,
            outputData = workDataOf(UpdateDownloadContract.OUT_FAILURE to "Http:429:true"),
        ).toDownloadState(request.info)
        assertEquals(DownloadWorkState.Status.FAILED, failed.status)
        // 固定失败码契约（R1 fix）：Http:code:rateLimited 必须可解码。
        assertEquals(UpdateFailure.Http(429, rateLimited = true), failed.failure)
        assertEquals(request.info, failed.info)
    }

    @Test
    fun observeFlowSurvivesLegacyCancelAndMapsLaterTarget() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)
            val states = CopyOnWriteArrayList<DownloadWorkState>()
            val collector = launch { scheduler.observe().collect { states += it } }
            try {
                // 1) 旧任务无 target：observe 内 fire-and-forget 取消。若在 map 里 await 抛异常，
                //    collector 会终止，后续下载状态永远收不到（Manager 状态机永久丢失）。
                val legacyId = workManager.enqueueConstrainedWork()
                awaitUntil { workManager.workInfoOf(legacyId).state == WorkInfo.State.CANCELLED }
                assertTrue("旧任务取消期间状态流不能被终止", collector.isActive)

                // 2) 再入队带 target 的任务：同一条 flow 仍能映射出 WAITING + info。
                val request = sampleRequest().copy(
                    info = sampleRequest().info.copy(versionName = "3.0.0"),
                )
                val bound = buildConstrainedWork(request)
                SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
                    .bindDownloadTarget(bound.id.toString(), request.info)
                workManager.enqueueUniqueWork(
                    UpdateDownloadContract.UNIQUE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    bound,
                ).await()

                awaitUntil {
                    states.any {
                        it.workId == bound.id.toString() &&
                            it.status == DownloadWorkState.Status.WAITING &&
                            it.info == request.info
                    }
                }
                assertTrue("无 target 旧任务的取消路径之后，flow 必须仍存活", collector.isActive)
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun enqueueBindsSingleSlotTargetToLatestWork() = runBlocking {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val scheduler = WorkManagerUpdateDownloadScheduler(context)
            val first = sampleRequest()
            val second = sampleRequest().copy(
                info = sampleRequest().info.copy(versionName = "2.1.0"),
            )

            var firstId = ""
            var secondId = ""
            scheduler.enqueue(first) { firstId = it }
            scheduler.enqueue(second) { secondId = it }

            val targetStore = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
            // Mutex 保护的「绑定 target + 回调 + 入队」保证单槽 target 始终指向最新任务。
            assertEquals(second.info, targetStore.downloadTarget(secondId))
            assertNull("旧任务的 target 应被新任务覆盖（单槽语义）", targetStore.downloadTarget(firstId))

            val state = withTimeout(5_000L) {
                scheduler.observe().first { it.workId == secondId }
            }
            assertEquals(second.info, state.info)
            workManager.awaitFinished(secondId)
        }
    }

    /** 构造一个带「充电中」约束的 WorkRequest（不自动执行，便于稳定观察状态）。 */
    private fun buildConstrainedWork(request: DownloadRequest): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ReleaseKiteDownloadWorker>()
            .setInputData(UpdateDownloadContract.inputData(request))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .addTag(UpdateDownloadContract.TAG)
            .build()

    /** 构造任意状态的 WorkInfo（覆盖 observe 映射用；WorkInfo 无 inputData，target 单独注入）。 */
    private fun workInfo(
        state: WorkInfo.State,
        outputData: Data = Data.Builder().build(),
        progress: Data = Data.Builder().build(),
    ): WorkInfo = WorkInfo(
        UUID.randomUUID(),
        state,
        emptySet(),
        outputData,
        progress,
        0,
        0,
        Constraints.NONE,
        0L,
        null,
        Long.MAX_VALUE,
        WorkInfo.STOP_REASON_NOT_STOPPED,
        ReleaseKiteDownloadWorker::class.java.name,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(POLL_INTERVAL_MS)
        }
        fail("条件在 ${timeoutMs}ms 内未满足")
    }

    private suspend fun WorkManager.enqueueConstrainedWork(): String {
        val request = buildConstrainedWork(sampleRequest())
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
    @Test
    fun rejectedSubmissionPreservesNewerWorkAndItsTarget() = runBlocking {
        withContext(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            val request = sampleRequest()
            val newer = buildConstrainedWork(request)
            val targets = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
            targets.bindDownloadTarget(newer.id.toString(), request.info)
            wm.enqueueUniqueWork(UpdateDownloadContract.UNIQUE_WORK, ExistingWorkPolicy.REPLACE, newer).await()
            var rejectedId = ""
            try {
                WorkManagerUpdateDownloadScheduler(context).enqueue(request.copy(
                    info = request.info.copy(versionName = "1.0.0"))) { id ->
                    rejectedId = id
                    throw CancellationException("Superseded generation")
                }
                fail("Rejected request must abort enqueue")
            } catch (expected: CancellationException) {
                assertEquals(WorkInfo.State.ENQUEUED, wm.workInfoOf(newer.id.toString()).state)
                assertEquals(request.info, targets.downloadTarget(newer.id.toString()))
                assertNull(wm.getWorkInfoById(UUID.fromString(rejectedId)).get())
            }
        }
    }
}
