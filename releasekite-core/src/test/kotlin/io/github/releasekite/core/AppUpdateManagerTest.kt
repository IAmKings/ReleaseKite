package io.github.releasekite.core

import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [UpdateManager] 状态机测试（迁移自 ZhiBox `AppUpdateManagerTest` 的 33 个用例）。
 *
 * **纯 JVM**：Robolectric/Context/`ApplicationProvider` 全部替换为 fake
 * [UpdateEnvironment] / [UpdateStore] / [UpdateDownloadScheduler] / [UpdateInstaller] /
 * [ApkValidator] / [UpdateNotifier]，配合 `kotlinx-coroutines-test` 的虚拟时钟。
 *
 * 覆盖重点（Leads 要求逐条确认）：
 * - 任务代际：`manualCheckCancelsInFlightAutoCheckAndUsesNewestResult`；
 * - 下载代际/迟到入队：`lateOldEnqueueResultCannotOverrideNewDownload`、
 *   `oldSuccessDuringNewEnqueueIsIgnored`、`oldCancelledWorkIdDoesNotCancelRetriedDownload`；
 * - 20s 启动看门狗：`waitingWorkStateTimesOutInsteadOfStickingAtZero`、
 *   `noWaitingCallbackStillTimesOutFromImmediateWatchdog`、`enqueueFailureDoesNotLeaveDialogStuck`、
 *   `earlyRunningAfterIdBindingCancelsWatchdog`；
 * - 历史 FAILED 静默：`historicalFailedWorkInfoAfterRestartDoesNotShowFailure`、
 *   `historicalFailedWorkInfoWithoutUpdateIsSilentlyIgnored`；
 * - 成功去重：`consumedSuccessResultIsNotInstalledTwice`、
 *   `duplicateSuccessKeepsPendingInstallAndPermissionState`、
 *   `staleWorkSuccessAfterAppAlreadyUpdatedDoesNotShowFailure`；
 * - forced 缓存：`cachedForcedUpdateOnLaunchIsNotDismissible`、
 *   `forcedCachedUpdateIsClearedWhenServerNoLongerOffersIt`、
 *   `forcedCachedUpdateStaysWhenVerificationFails`、`releaseUnavailableClearsForcedCacheAndIsNotForced`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testConfig = ReleaseKiteConfig(repository = "IAmKings/ZhiBox")

    // ── Fakes ────────────────────────────────────────────────────

    private class FakeStore : UpdateStore {
        override var lastSuccessAtMillis: Long = 0L
        override var lastAttemptAtMillis: Long = 0L
        override var dismissedVersion: String? = null
        override var autoCheckEnabled: Boolean = true
        override var channel: UpdateChannel = UpdateChannel.STABLE
        override var installId: String = "test-install-id"
        var cached: UpdateInfo? = null
        override var cachedMetaKnown: Boolean = true
        override var cachedAtMillis: Long = 1_700_000_000_000L
        override fun cachedInfo(): UpdateInfo? = cached
        override fun saveCached(info: UpdateInfo) {
            cached = info
        }

        override fun clearCache() {
            cached = null
        }

        private var routes: Map<String, RouteHealth> = emptyMap()
        override fun routeHealth(): Map<String, RouteHealth> = routes
        override fun updateRouteHealth(health: Map<String, RouteHealth>) {
            routes = health
        }

        override var downloadedVersion: String? = null
        override var downloadedApkPath: String? = null
        override var installerLaunchedVersion: String? = null
        override var consumedWorkId: String? = null
    }

    private class FakeSource(
        private val results: MutableList<Result<UpdateInfo>> = mutableListOf(),
        private val delayMs: Long = 0L,
    ) : UpdateSource {
        var calls: Int = 0
        override suspend fun fetchLatest(channel: UpdateChannel): Result<UpdateInfo> {
            calls++
            // 先取结果再 delay：模拟“请求已发出、结果已确定但尚未返回”，
            // 这样被取消的旧任务不会吃掉下一次调用的返回值。
            val result = results.removeFirstOrNull()
                ?: Result.failure(UpdateException(UpdateFailure.Network))
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakeScheduler : UpdateDownloadScheduler {
        var request: DownloadRequest? = null
        private val states = MutableSharedFlow<DownloadWorkState>(extraBufferCapacity = 64)
        var throwOnEnqueue: Boolean = false
        var nextWorkId: String = "work-1"
        var lastWorkId: String? = null
        var enqueueGates: MutableList<CompletableDeferred<Unit>> = mutableListOf()
        var runStateAfterId: DownloadWorkState? = null
        override suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit) {
            if (throwOnEnqueue) throw IllegalStateException("WorkManager unavailable")
            this.request = request
            lastWorkId = nextWorkId
            onRequestId(nextWorkId)
            runStateAfterId?.let { states.tryEmit(it) }
            enqueueGates.removeFirstOrNull()?.await()
        }

        /** 模拟 observe() 前 N 次抛异常（深审 P1 管道恢复用例）。 */
        var observeFailuresRemaining: Int = 0
        var observeCalls: Int = 0

        override fun observe(): Flow<DownloadWorkState> {
            observeCalls++
            if (observeFailuresRemaining > 0) {
                observeFailuresRemaining--
                return flow { throw IllegalStateException("observe unavailable") }
            }
            return states
        }

        var cancelCalls: Int = 0
        var cancelledWorkIds: MutableList<String?> = mutableListOf()
        override fun cancel(workId: String?) {
            cancelCalls++
            cancelledWorkIds.add(workId)
        }

        fun emit(work: DownloadWorkState) {
            check(states.tryEmit(work)) { "work state buffer full" }
        }
    }

    private class FakeValidator(private val failure: UpdateFailure? = null) : ApkValidator {
        override fun validate(apk: File): UpdateFailure? = failure
    }

    private class FakeInstaller(
        var result: InstallResult = InstallResult.Launched,
        var canInstall: Boolean = true,
    ) : UpdateInstaller {
        var installed: File? = null
        var installCalls: Int = 0
        var permissionSettingsOpened: Boolean = false
        override fun canInstallPackages(): Boolean = canInstall
        override suspend fun install(apk: File): InstallResult {
            installCalls++
            installed = apk
            return result
        }

        override fun openInstallPermissionSettings() {
            permissionSettingsOpened = true
        }
    }

    private class FakeNotifier : UpdateNotifier {
        var prepareCalls: Int = 0
        var notifyCompleteCalls: Int = 0
        var cancelCompleteCalls: Int = 0
        override fun prepare() {
            prepareCalls++
        }

        override fun notifyDownloadComplete(info: UpdateInfo) {
            notifyCompleteCalls++
        }

        override fun cancelDownloadComplete() {
            cancelCompleteCalls++
        }
    }

    private class FakeEnvironment(
        override val workDir: File,
        override var currentVersionName: String = "0.1.6",
        override var currentVersionCode: Long = 1L,
        override var isAppForeground: Boolean = true,
    ) : UpdateEnvironment {
        val logs: MutableList<String> = mutableListOf()
        override fun log(message: String, error: Throwable?) {
            logs += message
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────

    private fun info(
        version: String,
        rolloutPercent: Int = 100,
        minSupportedVersion: String? = null,
    ) = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = "ZhiBox v$version",
        notes = "更新内容",
        publishedAt = "2026-10-27T00:00:00Z",
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$version/x-release-signed.apk",
        apkSizeBytes = 16,
        sha256 = "e".repeat(64),
        sha256Url = null,
        rolloutPercent = rolloutPercent,
        minSupportedVersion = minSupportedVersion,
    )

    private fun writeApk(dir: File, name: String = "releasekite-0.1.7.apk"): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }

    private fun manager(
        scope: CoroutineScope,
        store: UpdateStore = FakeStore(),
        source: UpdateSource = FakeSource(),
        scheduler: FakeScheduler = FakeScheduler(),
        validator: ApkValidator = FakeValidator(),
        installer: UpdateInstaller = FakeInstaller(),
        notifier: UpdateNotifier = FakeNotifier(),
        now: Long = 1_700_000_000_000L,
        currentVersionName: String = "0.1.6",
        workDir: File = tempFolder.newFolder(),
        foreground: Boolean = true,
        config: ReleaseKiteConfig = testConfig,
        environment: FakeEnvironment? = null,
        clock: (() -> Long)? = null,
    ): UpdateManager = UpdateManager(
        config = config,
        environment = environment ?: FakeEnvironment(
            workDir = workDir,
            currentVersionName = currentVersionName,
            isAppForeground = foreground,
        ),
        source = source,
        scheduler = scheduler,
        installer = installer,
        validator = validator,
        store = store,
        notifier = notifier,
        scope = scope,
        clock = clock ?: { now },
    )

    private fun testScope(scope: kotlinx.coroutines.test.TestScope): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))

    // ── 检查节流/展示 ────────────────────────────────────────────

    @Test
    fun autoCheckSkippedWithin24h() = runTest {
        val now = 1_700_000_000_000L
        val store = FakeStore().apply { lastSuccessAtMillis = now - 1_000L }
        val source = FakeSource()
        val manager = manager(scope = testScope(this), store = store, source = source, now = now)
        manager.onAppEntered()
        advanceUntilIdle()
        assertEquals(0, source.calls)
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
    }

    @Test
    fun autoCheckFindsNewVersionAndShowsDialogState() = runTest {
        val now = 1_700_000_000_000L
        val store = FakeStore()
        val source = FakeSource(mutableListOf(Result.success(info("0.1.7"))))
        val manager = manager(scope = testScope(this), store = store, source = source, now = now)
        manager.onAppEntered()
        advanceUntilIdle()
        assertEquals(1, source.calls)
        val phase = manager.state.value.phase as UpdatePhase.Available
        assertEquals(UpdateTrigger.AUTO, phase.trigger)
        assertEquals("0.1.7", phase.info.versionName)
        assertEquals(now, store.lastSuccessAtMillis)
    }

    @Test
    fun manualCheckCancelsInFlightAutoCheckAndUsesNewestResult() = runTest {
        val source = FakeSource(
            results = mutableListOf(
                Result.success(info("0.1.7")),
                Result.success(info("0.1.8")),
            ),
            delayMs = 1_000L,
        )
        val manager = manager(scope = testScope(this), source = source)
        manager.onAppEntered()
        runCurrent() // 自动检查已发起并挂在 delay 上
        assertEquals(1, source.calls)

        manager.checkNow() // 取消旧任务 + 代际 +1
        advanceUntilIdle()

        assertEquals(2, source.calls)
        val phase = manager.state.value.phase as UpdatePhase.Available
        assertEquals(UpdateTrigger.MANUAL, phase.trigger)
        assertEquals("0.1.8", phase.info.versionName)
    }

    @Test
    fun manualCheckUpToDateStaysInline() = runTest {
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.6")))),
        )
        manager.checkNow()
        advanceUntilIdle()
        val phase = manager.state.value.phase as UpdatePhase.UpToDate
        assertEquals(UpdateTrigger.MANUAL, phase.trigger)
    }

    @Test
    fun cachedUpdateShowsWithoutNetwork() = runTest {
        val now = 1_700_000_000_000L
        val store = FakeStore().apply {
            lastSuccessAtMillis = now
            cached = info("0.1.7")
        }
        val source = FakeSource()
        val manager = manager(scope = testScope(this), store = store, source = source, now = now)
        manager.onAppEntered()
        advanceUntilIdle()
        assertEquals(0, source.calls)
        val phase = manager.state.value.phase as UpdatePhase.Available
        assertEquals("0.1.7", phase.info.versionName)
    }

    @Test
    fun forcedUpdateBypassesRolloutAndDismiss() = runTest {
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(
                mutableListOf(
                    Result.success(
                        info("0.1.7", rolloutPercent = 0, minSupportedVersion = "0.1.7"),
                    ),
                ),
            ),
        )
        manager.checkNow()
        advanceUntilIdle()
        val phase = manager.state.value.phase as UpdatePhase.Available
        assertTrue(phase.forced)
        manager.dismiss()
        advanceUntilIdle()
        // 强制更新不可取消
        assertTrue(manager.state.value.phase is UpdatePhase.Available)
    }

    @Test
    fun rolloutNotEligibleShowsPending() = runTest {
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7", rolloutPercent = 0)))),
        )
        manager.checkNow()
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.RolloutPending)
    }

    @Test
    fun dismissRecordsVersionAndSuppressesAutoPrompt() = runTest {
        val store = FakeStore()
        val manager = manager(
            scope = testScope(this),
            store = store,
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
        )
        manager.onAppEntered()
        advanceUntilIdle()
        manager.dismiss()
        advanceUntilIdle()
        assertEquals("0.1.7", store.dismissedVersion)
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)

        // 新进程再进入：缓存更新但已被取消 → 不再自动弹窗、也不请求网络。
        val source2 = FakeSource()
        val manager2 = manager(scope = testScope(this), store = store, source = source2)
        manager2.onAppEntered()
        advanceUntilIdle()
        assertEquals(0, source2.calls)
        assertTrue(manager2.state.value.phase is UpdatePhase.Idle)
    }

    @Test
    fun cachedForcedUpdateOnLaunchIsNotDismissible() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val manager = manager(scope = testScope(this), store = store)
        manager.onAppEntered()
        advanceUntilIdle()
        val phase = manager.state.value.phase as UpdatePhase.Available
        assertTrue("冷启动缓存也必须保留 forced", phase.forced)
        manager.dismiss()
        advanceUntilIdle()
        assertTrue("强制更新不可取消", manager.state.value.phase is UpdatePhase.Available)
    }

    @Test
    fun forcedCachedUpdateIsClearedWhenServerNoLongerOffersIt() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        // 服务端最新就是当前版本（force Release 已删除/回滚）
        val source = FakeSource(mutableListOf(Result.success(info("0.1.6"))))
        val manager = manager(scope = testScope(this), source = source, store = store)
        manager.onAppEntered()
        advanceUntilIdle()

        assertTrue(
            "服务端无更新后应回到最新态：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.UpToDate,
        )
        assertNull("失效的强制缓存应被清理", store.cachedInfo())
        assertEquals(1, source.calls)
    }

    @Test
    fun forcedCachedUpdateStaysWhenVerificationFails() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val source = FakeSource(
            mutableListOf(Result.failure(UpdateException(UpdateFailure.Network))),
        )
        val manager = manager(scope = testScope(this), source = source, store = store)
        manager.onAppEntered()
        advanceUntilIdle()

        val phase = manager.state.value.phase as UpdatePhase.Available
        assertTrue("离线时应保留缓存强制", phase.forced)
        assertNotNull(store.cachedInfo())
    }

    @Test
    fun historicalFailedWorkInfoAfterRestartDoesNotShowFailure() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val scheduler = FakeScheduler()
        val manager = manager(scope = testScope(this), store = store, scheduler = scheduler)
        manager.onAppEntered()
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.Available)

        // 模拟重启后后台调度回放上一会话的 FAILED：不能覆盖成「更新失败」。
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.Network,
            ),
        )
        advanceUntilIdle()

        val phase = manager.state.value.phase
        assertTrue("历史 FAILED 不应弹更新失败：$phase", phase is UpdatePhase.Available)
        assertTrue((phase as UpdatePhase.Available).forced)
        assertTrue(scheduler.cancelCalls >= 1)
    }

    @Test
    fun historicalFailedWorkInfoWithoutUpdateIsSilentlyIgnored() = runTest {
        val scheduler = FakeScheduler()
        val manager = manager(scope = testScope(this), scheduler = scheduler)
        advanceUntilIdle()
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.Network,
            ),
        )
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        assertTrue(scheduler.cancelCalls >= 1)
    }

    // ── 下载/校验/安装编排 ──────────────────────────────────────

    @Test
    fun downloadWorkSuccessVerifiesAndLaunchesInstaller() = runTest {
        val installer = FakeInstaller()
        val scheduler = FakeScheduler()
        val notifier = FakeNotifier()
        val workDir = tempFolder.newFolder()
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
            installer = installer,
            notifier = notifier,
            workDir = workDir,
        )
        manager.onAppEntered()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        assertEquals("0.1.7", scheduler.request?.info?.versionName)
        // 下载请求必须自带仓库/前缀等配置（Worker 进程内无法读宿主配置）。
        assertEquals("IAmKings/ZhiBox", scheduler.request?.repository)
        assertEquals("releasekite", scheduler.request?.filePrefix)
        assertEquals("ReleaseKite", scheduler.request?.userAgent)
        assertEquals(1, notifier.prepareCalls)

        val apk = writeApk(workDir, "zhibox-0.1.7.apk")
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()

        assertTrue(installer.installed != null)
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
    }

    @Test
    fun downloadWorkFailureBecomesDownloadFailed() = runTest {
        val scheduler = FakeScheduler()
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
        )
        manager.onAppEntered()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.ChecksumMismatch,
            ),
        )
        advanceUntilIdle()

        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertEquals(UpdateFailure.ChecksumMismatch, phase.failure)
    }

    @Test
    fun apkValidatorFailureBlocksInstaller() = runTest {
        val installer = FakeInstaller()
        val scheduler = FakeScheduler()
        val workDir = tempFolder.newFolder()
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
            validator = FakeValidator(UpdateFailure.ApkInvalid),
            installer = installer,
            workDir = workDir,
        )
        manager.onAppEntered()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        val apk = writeApk(workDir, "zhibox-0.1.7.apk")
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()

        assertNull(installer.installed)
        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertEquals(UpdateFailure.ApkInvalid, phase.failure)
    }

    @Test
    fun pendingInstallResumesWhenAppReturnsToForeground() = runTest {
        val installer = FakeInstaller()
        val store = FakeStore()
        val apk = writeApk(tempFolder.newFolder(), "pending-0.1.7.apk")
        store.downloadedVersion = "0.1.7"
        store.downloadedApkPath = apk.absolutePath
        val manager = manager(scope = testScope(this), store = store, installer = installer)
        manager.onAppResumed()
        advanceUntilIdle()
        assertTrue(installer.installed != null)
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
    }

    @Test
    fun backgroundDownloadSendsNotificationAndSkipsInstaller() = runTest {
        // 额外用例：后台完成（isAppForeground=false）必须发通知、不拉起安装器。
        val installer = FakeInstaller()
        val notifier = FakeNotifier()
        val scheduler = FakeScheduler()
        val workDir = tempFolder.newFolder()
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
            installer = installer,
            notifier = notifier,
            workDir = workDir,
            foreground = false,
        )
        manager.onAppEntered()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        val apk = writeApk(workDir, "zhibox-0.1.7.apk")
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()

        assertNull(installer.installed)
        assertEquals(1, notifier.notifyCompleteCalls)
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
    }

    @Test
    fun staleWorkSuccessAfterAppAlreadyUpdatedDoesNotShowFailure() = runTest {
        val store = FakeStore().apply { saveCached(info("0.1.7")) }
        val installer = FakeInstaller()
        val scheduler = FakeScheduler()
        val workDir = tempFolder.newFolder()
        val apk = writeApk(workDir, "stale-0.1.7.apk")
        val manager = manager(
            scope = testScope(this),
            store = store,
            scheduler = scheduler,
            installer = installer,
            // 模拟自更新已完成：当前进程已经是 0.1.7，而后台调度里还有旧的 SUCCEEDED。
            currentVersionName = "0.1.7",
            workDir = workDir,
        )
        advanceUntilIdle()
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()

        assertNull("已安装完成不应再拉起安装器", installer.installed)
        assertTrue(
            "已安装完成不应弹更新失败：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Idle,
        )
        assertFalse("残留下载文件应被清理", apk.exists())
        assertTrue("应取消旧后台任务", scheduler.cancelCalls >= 1)
    }

    @Test
    fun staleWorkSuccessEvenIfInstallersWereIdleIsSilent() = runTest {
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val manager = manager(
            scope = testScope(this),
            store = store,
            scheduler = scheduler,
            currentVersionName = "0.1.7",
        )
        advanceUntilIdle()
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = null,
            ),
        )
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        assertTrue(scheduler.cancelCalls >= 1)
    }

    @Test
    fun releaseUnavailableClearsForcedCacheAndIsNotForced() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val scheduler = FakeScheduler()
        // verify 返回同一强制目标，让弹窗保持在 Available(forced)
        val source = FakeSource(
            mutableListOf(Result.success(info("0.1.7", minSupportedVersion = "0.1.7"))),
        )
        val manager = manager(
            scope = testScope(this),
            source = source,
            store = store,
            scheduler = scheduler,
        )
        manager.onAppEntered()
        advanceUntilIdle()
        assertTrue((manager.state.value.phase as UpdatePhase.Available).forced)

        manager.startDownload()
        runCurrent()
        scheduler.emit(
            DownloadWorkState(
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.ReleaseUnavailable,
            ),
        )
        advanceUntilIdle()

        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertEquals(UpdateFailure.ReleaseUnavailable, phase.failure)
        assertFalse("版本已下架后不应继续强制", phase.forced)
        assertNull("版本已下架应清缓存", store.cachedInfo())
    }

    @Test
    fun startupCleanupRemovesOldApksButKeepsNewerAndPartial() = runTest {
        val dir = tempFolder.newFolder("updates")
        val old = File(dir, "releasekite-0.1.5.apk").apply { writeBytes(ByteArray(4)) }
        val newer = File(dir, "releasekite-0.1.7.apk").apply { writeBytes(ByteArray(4)) }
        val part = File(dir, "releasekite-0.1.7.apk.part").apply { writeBytes(ByteArray(4)) }

        val manager = manager(
            scope = testScope(this),
            currentVersionName = "0.1.6",
            workDir = dir,
        )
        manager.onAppEntered()
        advanceUntilIdle()

        assertFalse("已安装旧版本 APK 应清理：${old.name}", old.exists())
        assertTrue("更高版本应保留", newer.exists())
        assertTrue("下载中的 .part 应保留", part.exists())
    }

    // ── 看门狗 / 代际 / 任务归属 ────────────────────────────────

    @Test
    fun waitingWorkStateTimesOutInsteadOfStickingAtZero() = runTest {
        val scheduler = FakeScheduler()
        val source = FakeSource(
            mutableListOf(Result.success(info("0.1.7", minSupportedVersion = "0.1.7"))),
        )
        val manager = manager(scope = testScope(this), source = source, scheduler = scheduler)
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()

        scheduler.emit(DownloadWorkState(status = DownloadWorkState.Status.WAITING))
        runCurrent()
        advanceTimeBy(20_001)
        runCurrent()

        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertEquals(UpdateFailure.Timeout, phase.failure)
        assertTrue("卡在 WAITING 应取消任务", scheduler.cancelCalls >= 1)
    }

    @Test
    fun enqueueFailureDoesNotLeaveDialogStuck() = runTest {
        val scheduler = FakeScheduler().apply { throwOnEnqueue = true }
        val source = FakeSource(mutableListOf(Result.success(info("0.1.7"))))
        val manager = manager(scope = testScope(this), source = source, scheduler = scheduler)
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()

        assertTrue(manager.state.value.phase is UpdatePhase.DownloadFailed)
    }

    @Test
    fun noWaitingCallbackStillTimesOutFromImmediateWatchdog() = runTest {
        val scheduler = FakeScheduler()
        val source = FakeSource(mutableListOf(Result.success(info("0.1.7"))))
        val manager = manager(scope = testScope(this), source = source, scheduler = scheduler)
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        // 不发送 WAITING，只让启动看门狗到期
        advanceTimeBy(20_001)
        runCurrent()

        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertEquals(UpdateFailure.Timeout, phase.failure)
        assertTrue("应取消当前任务", scheduler.cancelCalls >= 1)
    }

    @Test
    fun oldCancelledWorkIdDoesNotCancelRetriedDownload() = runTest {
        val scheduler = FakeScheduler().apply { nextWorkId = "work-new" }
        val source = FakeSource(mutableListOf(Result.success(info("0.1.7"))))
        val manager = manager(scope = testScope(this), source = source, scheduler = scheduler)
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        assertEquals("work-new", scheduler.lastWorkId)

        scheduler.emit(
            DownloadWorkState(
                workId = "work-old",
                status = DownloadWorkState.Status.CANCELLED,
                failure = UpdateFailure.Cancelled,
            ),
        )
        runCurrent()
        assertTrue(
            "旧任务取消回调不能终止新下载：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Downloading,
        )

        scheduler.emit(
            DownloadWorkState(
                workId = "work-new",
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.Timeout,
            ),
        )
        runCurrent()
        assertEquals(
            UpdateFailure.Timeout,
            (manager.state.value.phase as UpdatePhase.DownloadFailed).failure,
        )
    }

    @Test
    fun coldStartDoesNotOverwriteRestoredDownloadingState() = runTest {
        val store = FakeStore().apply { cached = info("0.1.7") }
        val scheduler = FakeScheduler()
        val manager = manager(scope = testScope(this), store = store, scheduler = scheduler)
        advanceUntilIdle()
        scheduler.emit(
            DownloadWorkState(
                workId = "resumed-work",
                status = DownloadWorkState.Status.RUNNING,
                downloadedBytes = 1024,
                totalBytes = 2048,
            ),
        )
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)

        manager.onAppEntered()
        advanceUntilIdle()
        assertTrue(
            "冷启动检查不能覆盖恢复中的下载：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Downloading,
        )
    }

    @Test
    fun consumedSuccessResultIsNotInstalledTwice() = runTest {
        val installer = FakeInstaller()
        val store = FakeStore()
        val scheduler = FakeScheduler().apply { nextWorkId = "work-success" }
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            store = store,
            scheduler = scheduler,
            installer = installer,
        )
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        val apk = writeApk(tempFolder.newFolder(), "releasekite-0.1.7.apk")
        scheduler.emit(
            DownloadWorkState(
                workId = "work-success",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()
        assertEquals(1, installer.installCalls)
        assertEquals("work-success", store.consumedWorkId)

        // 模拟重启后后台调度再次回放同一 SUCCEEDED
        scheduler.emit(
            DownloadWorkState(
                workId = "work-success",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()
        assertEquals("同一任务只能安装一次", 1, installer.installCalls)
    }

    @Test
    fun coldStartDoesNotOverwriteRestoredInstallPermissionState() = runTest {
        val dir = tempFolder.newFolder()
        val apk = writeApk(dir, "zhibox-0.1.7.apk")
        val store = FakeStore().apply {
            cached = info("0.1.7")
            downloadedVersion = "0.1.7"
            downloadedApkPath = apk.absolutePath
        }
        val installer = FakeInstaller(canInstall = false)
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(),
            store = store,
            installer = installer,
        )
        manager.onAppEntered()
        advanceUntilIdle()

        assertTrue(
            "冷启动检查不能覆盖待授权状态：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.NeedInstallPermission,
        )
        assertEquals(0, installer.installCalls)
    }

    @Test
    fun lateOldEnqueueResultCannotOverrideNewDownload() = runTest {
        val installer = FakeInstaller()
        // 显式持有旧入队的 gate；FakeScheduler 取走队列后仍能由测试释放，
        // 避免测试因 gate 丢失而“未真正覆盖迟到结果”却仍通过。
        val oldGate = CompletableDeferred<Unit>()
        val scheduler = FakeScheduler().apply {
            nextWorkId = "work-old"
            enqueueGates.add(oldGate)
        }
        val source = FakeSource(mutableListOf(Result.success(info("0.1.7"))))
        val manager = manager(
            scope = testScope(this),
            source = source,
            scheduler = scheduler,
            installer = installer,
        )
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        // 第一轮入队卡在 await；看门狗超时后进入失败
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.DownloadFailed)

        scheduler.nextWorkId = "work-new"
        manager.retry()
        runCurrent()
        // 新任务已绑定 id；此时显式放行旧 await，它不能覆盖 activeWorkId。
        oldGate.complete(Unit)
        assertTrue(oldGate.isCompleted)
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)
        assertTrue(
            "迟到旧任务应被取消：${scheduler.cancelledWorkIds}",
            scheduler.cancelledWorkIds.contains("work-old"),
        )

        scheduler.emit(
            DownloadWorkState(
                workId = "work-old",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = "/tmp/old.apk",
            ),
        )
        runCurrent()
        assertEquals("旧入队结果不能触发安装", 0, installer.installCalls)
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)

        // 新任务终态必须仍能被接收：证明 activeWorkId 未被旧结果覆盖。
        scheduler.emit(
            DownloadWorkState(
                workId = "work-new",
                status = DownloadWorkState.Status.FAILED,
                failure = UpdateFailure.Timeout,
            ),
        )
        runCurrent()
        assertEquals(
            UpdateFailure.Timeout,
            (manager.state.value.phase as UpdatePhase.DownloadFailed).failure,
        )
    }

    @Test
    fun earlyRunningAfterIdBindingCancelsWatchdog() = runTest {
        val scheduler = FakeScheduler().apply {
            nextWorkId = "work-fast"
            runStateAfterId = DownloadWorkState(
                workId = "work-fast",
                status = DownloadWorkState.Status.RUNNING,
                downloadedBytes = 10,
                totalBytes = 100,
            )
        }
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
        )
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        // RUNNING 已到达并绑定，20s 后不应被看门狗误杀
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(
            "真实 RUNNING 应关闭启动看门狗：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Downloading,
        )
    }

    @Test
    fun oldSuccessDuringNewEnqueueIsIgnored() = runTest {
        val installer = FakeInstaller()
        val scheduler = FakeScheduler().apply {
            nextWorkId = "work-new"
            enqueueGates.add(CompletableDeferred())
        }
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            scheduler = scheduler,
            installer = installer,
        )
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        // 入队协程尚未运行、activeWorkId 为空时，旧成功结果必须忽略
        scheduler.emit(
            DownloadWorkState(
                workId = "work-old",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = "/tmp/old.apk",
            ),
        )
        runCurrent()
        assertEquals(0, installer.installCalls)
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)
    }

    @Test
    fun duplicateSuccessKeepsPendingInstallAndPermissionState() = runTest {
        val installer = FakeInstaller(
            result = InstallResult.PermissionRequired,
            canInstall = false,
        )
        val store = FakeStore()
        val scheduler = FakeScheduler().apply { nextWorkId = "work-perm" }
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(mutableListOf(Result.success(info("0.1.7")))),
            store = store,
            scheduler = scheduler,
            installer = installer,
        )
        manager.checkNow()
        advanceUntilIdle()
        manager.startDownload()
        runCurrent()
        val apk = writeApk(tempFolder.newFolder(), "releasekite-0.1.7.apk")
        scheduler.emit(
            DownloadWorkState(
                workId = "work-perm",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.NeedInstallPermission)
        assertEquals("0.1.7", store.downloadedVersion)

        // 同一 SUCCEEDED 回放（模拟重启）：不得清待安装记录或覆盖授权提示
        scheduler.emit(
            DownloadWorkState(
                workId = "work-perm",
                status = DownloadWorkState.Status.SUCCEEDED,
                apkPath = apk.absolutePath,
            ),
        )
        advanceUntilIdle()
        assertTrue(
            "重复成功回调不应覆盖授权提示：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.NeedInstallPermission,
        )
        assertEquals("0.1.7", store.downloadedVersion)
        assertNotNull(store.downloadedApkPath)
    }

    @Test
    fun forcedUpdateAllowsEscapeAfterThreeDownloadFailures() = runTest {
        val scheduler = FakeScheduler()
        val manager = manager(
            scope = testScope(this),
            source = FakeSource(
                mutableListOf(
                    Result.success(info("0.1.7", minSupportedVersion = "0.1.7")),
                ),
            ),
            scheduler = scheduler,
        )
        manager.checkNow()
        advanceUntilIdle()
        repeat(3) { attempt ->
            manager.startDownload()
            advanceUntilIdle()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.Network,
                ),
            )
            advanceUntilIdle()
            val failed = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertTrue(failed.forced)
            if (attempt < 2) {
                manager.retry()
                advanceUntilIdle()
            }
        }
        val failed = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertTrue("第三次失败应允许退出", failed.escapeAllowed)
        manager.dismiss()
        advanceUntilIdle()
        assertTrue(manager.state.value.phase is UpdatePhase.Idle)
    }
    @Test
    fun withdrawnForcedReleaseMustClearCachedPrompt() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val source = FakeSource(mutableListOf(Result.failure(UpdateException(UpdateFailure.NoRelease))))
        val manager = manager(scope = testScope(this), source = source, store = store)
        manager.onAppEntered()
        advanceUntilIdle()
        assertNull("404/NoRelease must revoke cached forced release; actual=${manager.state.value.phase}", store.cachedInfo())
    }

    @Test
    fun restoredForcedDownloadMustRemainForcedOnFailure() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        val scheduler = FakeScheduler()
        val manager = manager(scope = testScope(this), store = store, scheduler = scheduler)
        runCurrent()
        scheduler.emit(DownloadWorkState(workId = "restored", status = DownloadWorkState.Status.RUNNING))
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)
        scheduler.emit(DownloadWorkState(workId = "restored", status = DownloadWorkState.Status.FAILED, failure = UpdateFailure.Network))
        runCurrent()
        val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
        assertTrue("restored forced download was downgraded to optional: $phase", phase.forced)
    }

    @Test
    fun disablingBetaMustNotShowCachedBeta() = runTest {
        val store = FakeStore().apply {
            channel = UpdateChannel.BETA
            saveCached(info("0.1.7-beta.1").copy(channel = UpdateChannel.BETA))
        }
        val manager = manager(scope = testScope(this), store = store,
            config = testConfig.copy(betaChannelEnabled = false))
        manager.onAppEntered()
        advanceUntilIdle()
        assertEquals(UpdateChannel.STABLE, store.channel)
        val offered = (manager.state.value.phase as? UpdatePhase.Available)?.info
        assertTrue("beta disabled yet beta offered: $offered", offered?.channel != UpdateChannel.BETA)
    }

    @Test
    fun coldStartCheckMustNotRaceWithResumedDownloadIdentity() = runTest {
        val old = info("0.1.7")
        val newer = info("0.1.8")
        val store = FakeStore().apply { saveCached(old); cachedAtMillis = 1L }
        val scheduler = FakeScheduler()
        val source = FakeSource(mutableListOf(Result.success(newer)), delayMs = 100L)
        val originalScope = testScope(this)
        val manager = manager(scope = originalScope, store = store, scheduler = scheduler, source = source)
        manager.onAppEntered()
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.Checking)
        scheduler.emit(DownloadWorkState(workId = "restored", status = DownloadWorkState.Status.RUNNING))
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()
        assertTrue(manager.state.value.phase is UpdatePhase.Downloading)
        assertEquals("0.1.8", store.cachedInfo()?.versionName)
        originalScope.cancel()
        val restoredScheduler = FakeScheduler()
        val restored = manager(scope = testScope(this), store = store, scheduler = restoredScheduler,
            installer = FakeInstaller(result = InstallResult.PermissionRequired))
        runCurrent()
        restoredScheduler.emit(DownloadWorkState(workId = "restored", status = DownloadWorkState.Status.RUNNING, info = old))
        runCurrent()
        val oldApk = writeApk(tempFolder.newFolder())
        restoredScheduler.emit(DownloadWorkState(workId = "restored", status = DownloadWorkState.Status.SUCCEEDED,
            apkPath = oldApk.absolutePath, info = old))
        runCurrent()
        val phase = restored.state.value.phase as UpdatePhase.NeedInstallPermission
        assertEquals("running 0.1.7 APK must not be labeled/stored as 0.1.8", "0.1.7", phase.info.versionName)
    }

    // ── 2026-10-30 发布前深审回归（P1/P2） ─────────────────────

    /**
     * 深审 P1：forced 缓存的 verify 在途时用户已进入 Downloading，随后 verify 返回 NoRelease。
     * 该失败不得清缓存、不得把 phase 覆盖成 Idle（否则正在下载的 UI 被静默打断）。
     */
    @Test
    fun noReleaseDuringInFlightVerifyMustNotClobberDownloadingPhase() = runTest {
        val now = 1_700_000_000_000L
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
        }
        var armClockHook = false
        var managerRef: UpdateManager? = null
        val clock: () -> Long = {
            if (armClockHook) {
                armClockHook = false
                // 在 verify 处理失败前把 phase 推进到 Downloading（真实线程竞态的可控复现）。
                managerRef?.startDownload()
            }
            now
        }
        val source = object : UpdateSource {
            override suspend fun fetchLatest(channel: UpdateChannel): Result<UpdateInfo> {
                // 失败结果已确定、即将交给 manager 处理：武装 hook，等 onFailure 内首次 clock()
                // 时切到 Downloading —— 这正是 phase 守卫要覆盖的窗口。
                armClockHook = true
                return Result.failure(UpdateException(UpdateFailure.NoRelease))
            }
        }
        lateinit var manager: UpdateManager
        manager = manager(scope = testScope(this), store = store, source = source, clock = clock)
        managerRef = manager
        manager.onAppEntered()
        runCurrent()

        assertNotNull("下载中不得因 verify 的 NoRelease 清缓存", store.cachedInfo())
        assertTrue(
            "下载中 phase 不得被 verify 覆盖：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Downloading,
        )
    }

    /**
     * 深审 P1：下载状态管道是 UI 感知下载的唯一来源；observe() 首次抛异常后必须自动重连，
     * 恢复后仍能接收 RUNNING 并进入 Downloading。
     */
    @Test
    fun downloadStateCollectorRecoversAfterObserveFailure() = runTest {
        val scheduler = FakeScheduler().apply { observeFailuresRemaining = 1 }
        val environment = FakeEnvironment(workDir = tempFolder.newFolder())
        val manager = manager(
            scope = testScope(this),
            scheduler = scheduler,
            environment = environment,
        )
        advanceUntilIdle() // 首次 observe 抛错 → 记录日志 → 退避后重连
        assertEquals("必须自动重连 observe 管道", 2, scheduler.observeCalls)
        assertTrue(
            "管道异常必须写 environment.log：${environment.logs}",
            environment.logs.any { it.contains("下载状态管道异常") },
        )

        scheduler.emit(
            DownloadWorkState(
                workId = "recovered-work",
                status = DownloadWorkState.Status.RUNNING,
                downloadedBytes = 512,
                totalBytes = 1024,
                info = info("0.1.7"),
            ),
        )
        runCurrent()
        assertTrue(
            "重连后仍必须处理下载状态：${manager.state.value.phase}",
            manager.state.value.phase is UpdatePhase.Downloading,
        )
    }

    @Test
    fun expiredWithdrawnForcedReleaseMustNotFallBackToCache() = runTest {
        val store = FakeStore().apply {
            saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            cachedAtMillis = 1L
        }
        val source = FakeSource(mutableListOf(Result.failure(UpdateException(UpdateFailure.NoRelease))))
        val manager = manager(scope = testScope(this), source = source, store = store)
        manager.onAppEntered()
        advanceUntilIdle()
        assertNull("404/NoRelease must revoke cached forced release; actual=${manager.state.value.phase}", store.cachedInfo())
    }

}
