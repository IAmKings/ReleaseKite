package io.github.releasekite.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用内更新的全局服务（宿主 App 作用域单例）。
 *
 * 逐段移植 ZhiBox `AppUpdateManager.kt`（857 行），**不简化任何**回归修复：
 * - 任务代际：手动检查取消在途检查并生成新任务，旧结果丢弃（源 `taskId`）；
 * - 下载代际 + 任务归属：迟到入队结果/旧 WorkRequest 回调不得影响新重试
 *   （4c2a56c / a253ff9 / 746b297 / 733f78a）；
 * - 20s 启动看门狗：异步入队失败或后台任务长时间不启动时主动失败，避免 0% 卡死（4c2a56c）；
 * - 成功去重：`consumedWorkId` + 待安装记录，防止重启后重复消费 SUCCEEDED（6110dfc）；
 * - 历史 FAILED/CANCELLED 静默：进程重启后回放的旧终态不重新弹「更新失败」（4ff01e7）；
 * - 强制缓存：冷启动缓存必须保留 `forced`（21ba14b），服务端不再提供时清缓存（0ea927d）；
 * - ReleaseUnavailable：清缓存/清强制/一次性可关闭失败（0ea927d）；
 * - 接管下载态：进程重启后首个 WAITING/RUNNING 被接管，冷启动检查不得覆盖恢复中的下载；
 * - 重试/取消规则：forced 不可 dismiss、连续失败达到阈值后允许暂时退出。
 *
 * 与源的替换点（契约 §1.9）：
 * - `BuildConfig.VERSION_NAME` → [UpdateEnvironment.currentVersionName]；
 * - `appContext.cacheDir/updates` → [UpdateEnvironment.workDir]；
 * - `UpdateConfig.*` → [ReleaseKiteConfig]；
 * - `UpdateNotifications.*` → [UpdateNotifier]；
 * - `foreground()` → [UpdateEnvironment.isAppForeground]；
 * - `withContext(Dispatchers.Main) { installer.install(...) }` → `installer.install(...)`（实现内部切 Main）；
 * - `installer.installPermissionIntent()` → `installer.openInstallPermissionSettings()`；
 * - 删除 `companion get(Context)`、`debugInjectMockState`、`UpdateConfig.ensureChannel` 调用
 *   （改为 `notifier.prepare()`）。
 */
class UpdateManager(
    val config: ReleaseKiteConfig,
    private val environment: UpdateEnvironment,
    private val source: UpdateSource,
    private val scheduler: UpdateDownloadScheduler,
    private val installer: UpdateInstaller,
    private val validator: ApkValidator,
    private val store: UpdateStore,
    private val notifier: UpdateNotifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(
        UpdateUiState(currentVersionName = environment.currentVersionName),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val entered = AtomicBoolean(false)
    private var taskId = 0L
    private var checkJob: Job? = null
    private var verifyCachedJob: Job? = null
    private var downloadWatchdogJob: Job? = null

    /** 当前下载对应的后台任务 id；旧任务回调不能影响新重试。 */
    @Volatile
    private var activeDownloadWorkId: String? = null

    /**
     * 每次 startDownload 递增；迟到入队结果/看门狗只能操作自己的代际
     * （a253ff9 引入、746b297 补强：异常/await 返回/看门狗均校验代际）。
     */
    private var downloadGeneration = 0L

    /** 强制更新下载失败次数（达到配置阈值后允许暂时退出，避免锁死）。 */
    private var downloadFailureCount = 0
    private var activeForced = false

    /**
     * 本进程是否正在跟踪一次下载。
     * 后台调度会在进程重启后回放历史的 FAILED/CANCELLED 终端状态；若没有本进程的
     * 下载上下文（startDownload 或先观察到 RUNNING），这些历史失败不能重新弹「更新失败」。
     */
    @Volatile
    private var downloadActiveInProcess = false
    private var lastForcedVerifyAtMillis = 0L
    /** 安装流程防重入（5f598bb）：并发 onResume/成功回调只允许一次拉起安装器。 */
    private val installInProgress = AtomicBoolean(false)

    init {
        scope.launch {
            // Beta 通道关闭时把旧偏好归一化回 STABLE（配置是最终事实来源）。
            if (!config.betaChannelEnabled && store.channel == UpdateChannel.BETA) {
                store.channel = UpdateChannel.STABLE
            }
            _state.update {
                it.copy(
                    autoCheckEnabled = store.autoCheckEnabled,
                    channel = store.channel,
                    betaChannelEnabled = config.betaChannelEnabled,
                    routeHealth = store.routeHealth(),
                )
            }
        }
        // 后台下载状态是下载阶段的单一事实来源（跨进程恢复）：订阅一次即可。
        scope.launch {
            scheduler.observe().collect { work -> onWorkState(work) }
        }
    }

    // ── 检查 ─────────────────────────────────────────────────────

    fun onAppEntered() {
        if (!entered.compareAndSet(false, true)) return
        scope.launch {
            // 后台下载已完成但尚未安装：先处理安装，再做常规检查。
            // 如果安装恢复/授权提示已接管流程，不能被冷启动检查覆盖。
            if (installPendingIfAny()) return@launch
            cleanupOldDownloadCache()
            if (isDownloadInProgressPhase(_state.value.phase)) return@launch
            val action = UpdatePolicy.decideOnLaunch(
                nowMillis = clock(),
                store = store,
                currentVersionName = environment.currentVersionName,
                config = config,
            )
            when (action) {
                is UpdatePolicy.LaunchAction.ShowCached -> {
                    if (isDownloadInProgressPhase(_state.value.phase)) return@launch
                    // 冷启动展示缓存时也必须重新计算 forced 与灰度资格（21ba14b / 038699a）：
                    // - 否则「暂时退出」后重启的强制弹窗会丢失 forced，变成可取消；
                    // - 未命中灰度的设备也不能因为缓存而绕过 rollout。
                    val forced = UpdateRollout.isForced(
                        action.info,
                        environment.currentVersionName,
                    )
                    val eligible = UpdateRollout.isEligible(store.installId, action.info)
                    val phase = if (forced) {
                        UpdatePhase.Available(UpdateTrigger.AUTO, action.info, forced = true)
                    } else if (eligible) {
                        UpdatePhase.Available(UpdateTrigger.AUTO, action.info)
                    } else {
                        UpdatePhase.RolloutPending(action.info, action.info.rolloutPercent)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(
                                phase = phase,
                                lastCheckAtMillis = store.lastSuccessAtMillis,
                            )
                        }
                    }
                    if (forced) verifyCachedForced()
                }

                is UpdatePolicy.LaunchAction.RolloutPending -> {
                    if (isDownloadInProgressPhase(_state.value.phase)) return@launch
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(
                                phase = UpdatePhase.RolloutPending(
                                    action.info,
                                    action.info.rolloutPercent,
                                ),
                                lastCheckAtMillis = store.lastSuccessAtMillis,
                            )
                        }
                    }
                }

                UpdatePolicy.LaunchAction.Check -> startCheck(UpdateTrigger.AUTO)
                UpdatePolicy.LaunchAction.Skip -> Unit
            }
        }
    }

    /** 设置里的「检查更新」：取消在途检查 + 代际 +1，强制重查。 */
    fun checkNow() {
        startCheck(UpdateTrigger.MANUAL)
    }

    private fun startCheck(trigger: UpdateTrigger) {
        val id = ++taskId
        checkJob?.cancel()
        _state.update { it.copy(phase = UpdatePhase.Checking(trigger)) }
        checkJob = scope.launch {
            val result = source.fetchLatest(store.channel)
            // 任务代际：过期任务（含成功/失败）一律丢弃，不写状态。
            if (id != taskId) return@launch
            result.fold(
                onSuccess = { info ->
                    store.lastSuccessAtMillis = clock()
                    store.lastAttemptAtMillis = clock()
                    store.saveCached(info)
                    // 新检查结果代表新一轮更新：清空上一次强制更新的失败计数。
                    downloadFailureCount = 0
                    val newer = isNewerVersion(info.versionName, environment.currentVersionName)
                    val forced = UpdateRollout.isForced(info, environment.currentVersionName)
                    val eligible = UpdateRollout.isEligible(store.installId, info)
                    val phase = when {
                        !newer -> {
                            store.clearCache()
                            UpdatePhase.UpToDate(trigger, environment.currentVersionName)
                        }
                        // 强制更新优先于「用户取消过该版本」与灰度分桶。
                        forced -> UpdatePhase.Available(trigger, info, forced = true)
                        trigger == UpdateTrigger.AUTO &&
                            !UpdatePolicy.shouldAutoPrompt(
                                info,
                                environment.currentVersionName,
                                store.dismissedVersion,
                            ) -> UpdatePhase.UpToDate(trigger, environment.currentVersionName)

                        eligible -> UpdatePhase.Available(trigger, info)
                        else -> UpdatePhase.RolloutPending(info, info.rolloutPercent)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(phase = phase, lastCheckAtMillis = clock())
                        }
                    }
                },
                onFailure = { error ->
                    val failure = (error as? UpdateException)?.failure ?: UpdateFailure.Network
                    store.lastAttemptAtMillis = clock()
                    val fallbackCached = if (trigger == UpdateTrigger.AUTO) {
                        store.cachedInfo()?.takeIf {
                            isNewerVersion(it.versionName, environment.currentVersionName)
                        }
                    } else {
                        null
                    }
                    val fallbackPhase = when {
                        trigger == UpdateTrigger.MANUAL ->
                            UpdatePhase.CheckFailed(trigger, failure)

                        fallbackCached == null -> UpdatePhase.Idle

                        UpdateRollout.isForced(fallbackCached, environment.currentVersionName) ->
                            UpdatePhase.Available(trigger, fallbackCached, forced = true)

                        !UpdateRollout.isEligible(store.installId, fallbackCached) ->
                            UpdatePhase.RolloutPending(fallbackCached, fallbackCached.rolloutPercent)

                        fallbackCached.versionName == store.dismissedVersion ->
                            UpdatePhase.Idle

                        else -> UpdatePhase.Available(trigger, fallbackCached)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(phase = fallbackPhase, lastCheckAtMillis = clock())
                        }
                    }
                },
            )
        }
    }

    // ── 设置项 ───────────────────────────────────────────────────

    fun setAutoCheckEnabled(enabled: Boolean) {
        scope.launch {
            store.autoCheckEnabled = enabled
            _state.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    fun setChannel(channel: UpdateChannel) {
        scope.launch {
            // Beta 通道被配置关闭时忽略切换（配置是最终事实来源）。
            if (channel == UpdateChannel.BETA && !config.betaChannelEnabled) return@launch
            if (store.channel == channel) return@launch
            store.channel = channel
            store.clearCache()
            store.lastSuccessAtMillis = 0L
            store.lastAttemptAtMillis = 0L
            store.dismissedVersion = null
            taskId++
            checkJob?.cancel()
            _state.update { it.copy(channel = channel, phase = UpdatePhase.Idle) }
        }
    }

    fun resetRouteHealth() {
        scope.launch {
            store.updateRouteHealth(emptyMap())
            _state.update { it.copy(routeHealth = emptyMap()) }
        }
    }

    // ── 下载（后台调度） ─────────────────────────────────────────

    /** 弹窗点「立即更新」：入队后台下载。 */
    fun startDownload() {
        val available = _state.value.phase as? UpdatePhase.Available ?: return
        val info = available.info
        notifier.prepare()
        clearPendingInstall()
        store.installerLaunchedVersion = null
        store.consumedWorkId = null
        activeForced = available.forced
        downloadActiveInProcess = true
        activeDownloadWorkId = null
        cancelDownloadWatchdog()
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading(info, 0L, info.apkSizeBytes),
                forcedEscapeAllowed = false,
            )
        }
        val generation = ++downloadGeneration
        // 进入下载态立即武装启动看门狗（不能等 WAITING 回调，异步入队失败时也要能恢复）。
        armDownloadWatchdog(info, workId = null, generation = generation)
        scope.launch {
            var boundWorkId: String? = null
            try {
                scheduler.enqueue(
                    DownloadRequest(
                        info = info,
                        repository = config.repository,
                        filePrefix = config.sanitizedDownloadFilePrefix,
                        userAgent = config.userAgent,
                        mirrors = config.downloadMirrors,
                        requireChecksum = config.requireChecksum,
                        timeoutMs = config.downloadTimeoutMs,
                    ),
                ) { id ->
                    boundWorkId = id
                    if (generation == downloadGeneration) {
                        activeDownloadWorkId = id
                    } else {
                        // 迟到入队只清理自己的任务，不能覆盖/取消新代际。
                        scheduler.cancel(id)
                    }
                }
                // await 返回时如果已被看门狗/新重试淘汰，只清理自己的任务。
                if (generation != downloadGeneration) {
                    boundWorkId?.let { scheduler.cancel(it) }
                } else if (_state.value.phase !is UpdatePhase.Downloading) {
                    boundWorkId?.let { scheduler.cancel(it) }
                    activeDownloadWorkId = null
                }
            } catch (e: Exception) {
                if (generation != downloadGeneration) return@launch
                environment.log("更新下载入队失败", e)
                cancelDownloadWatchdog()
                activeDownloadWorkId = null
                downloadActiveInProcess = false
                if (_state.value.phase is UpdatePhase.Downloading) {
                    failDownload(info, UpdateFailure.Network)
                }
            }
        }
    }

    private suspend fun onWorkState(work: DownloadWorkState) {
        if (!claimWorkEvent(work)) return
        when (work.status) {
            DownloadWorkState.Status.IDLE -> Unit

            DownloadWorkState.Status.WAITING -> {
                val info = currentActionInfo() ?: store.cachedInfo() ?: return
                downloadActiveInProcess = true
                _state.update { current ->
                    if (current.phase is UpdatePhase.Downloading) {
                        current
                    } else {
                        current.copy(phase = UpdatePhase.Downloading(info, 0L, info.apkSizeBytes))
                    }
                }
                armDownloadWatchdog(info, work.workId, downloadGeneration)
            }

            DownloadWorkState.Status.RUNNING -> {
                cancelDownloadWatchdog()
                val info = currentActionInfo() ?: store.cachedInfo() ?: return
                downloadActiveInProcess = true
                _state.update { current ->
                    // 下载任务已在运行：即使冷启动检查尚在 Checking，也以真实下载态为准。
                    current.copy(
                        phase = UpdatePhase.Downloading(info, work.downloadedBytes, work.totalBytes),
                    )
                }
            }

            DownloadWorkState.Status.SUCCEEDED -> {
                cancelDownloadWatchdog()
                downloadGeneration++
                downloadActiveInProcess = false
                activeDownloadWorkId = null
                // 只取消当前任务；不要取消唯一任务队列，否则可能误伤刚重试的新任务。
                scheduler.cancel(work.workId)
                handleDownloadSucceeded(work.apkPath, work.workId)
            }

            DownloadWorkState.Status.FAILED, DownloadWorkState.Status.CANCELLED -> {
                cancelDownloadWatchdog()
                downloadGeneration++
                if (!downloadActiveInProcess) {
                    // 历史会话的 FAILED/CANCELLED（进程重启后回放，4ff01e7）：
                    // 静默清理，不再用缓存信息弹「更新失败」。
                    scheduler.cancel(work.workId)
                    clearPendingInstall()
                    _state.update { current ->
                        if (current.phase is UpdatePhase.Downloading ||
                            current.phase is UpdatePhase.Verifying
                        ) {
                            current.copy(phase = UpdatePhase.Idle)
                        } else {
                            current
                        }
                    }
                    return
                }
                downloadActiveInProcess = false
                activeDownloadWorkId = null
                val info = currentActionInfo() ?: store.cachedInfo()
                clearPendingInstall()
                // 失败弹窗展示后同样取消该任务；重试会重新入队，不再回放旧 FAILED。
                scheduler.cancel(work.workId)
                if (info != null) {
                    failDownload(info, work.failure ?: UpdateFailure.Network)
                } else {
                    _state.update { it.copy(phase = UpdatePhase.Idle) }
                }
            }
        }
    }

    /**
     * 判断回调是否属于当前下载：忽略旧任务的 CANCELLED/FAILED 回调，避免旧任务取消
     * 刚重试的新任务；进程重启时允许首个 RUNNING/WAITING 被接管。
     */
    private fun claimWorkEvent(work: DownloadWorkState): Boolean {
        val id = work.workId ?: return true
        val active = activeDownloadWorkId
        if (active != null) return id == active
        if (downloadActiveInProcess) {
            // 新下载正在创建、尚未绑定 id：此阶段任何事件都不能当作本轮结果
            // （尤其是旧任务的 SUCCEEDED/CANCELLED）。绑定后的真实事件会正常进入。
            return false
        }
        return when (work.status) {
            DownloadWorkState.Status.RUNNING,
            DownloadWorkState.Status.WAITING,
            -> {
                activeDownloadWorkId = id
                downloadActiveInProcess = true
                true
            }

            DownloadWorkState.Status.SUCCEEDED -> {
                // 进程重启后恢复的历史成功结果仍需消费（安装/通知），由 consumedWorkId 去重。
                activeDownloadWorkId = id
                true
            }

            DownloadWorkState.Status.FAILED,
            DownloadWorkState.Status.CANCELLED,
            DownloadWorkState.Status.IDLE,
            -> false
        }
    }

    private suspend fun handleDownloadSucceeded(apkPath: String?, workId: String?) {
        val info = currentActionInfo() ?: store.cachedInfo()
        if (info == null) {
            // 无对应更新信息：旧任务残留，静默清理，不能弹失败。
            cleanupStaleDownload(apkPath, workId)
            return
        }
        val pendingPath = store.downloadedApkPath
        val pendingMatches = store.downloadedVersion == info.versionName &&
            pendingPath != null && File(pendingPath).isFile
        if (store.installerLaunchedVersion == info.versionName) {
            // 已交给系统安装器但用户取消安装：不自动再次拉起。待安装记录若仍有效则保留。
            if (!pendingMatches) finishConsumedSuccess(workId)
            return
        }
        if (workId != null && store.consumedWorkId == workId) {
            // 同一个 SUCCEEDED 结果已处理过：若后台完成/待授权记录仍有效，不能清掉安装入口。
            if (pendingMatches) return
            finishConsumedSuccess(workId)
            return
        }
        if (!isNewerVersion(info.versionName, environment.currentVersionName)) {
            if (workId != null) store.consumedWorkId = workId
            cleanupStaleDownload(apkPath, workId)
            return
        }
        val file = apkPath?.let { File(it) }
        _state.update { it.copy(phase = UpdatePhase.Verifying(info)) }
        if (file == null || !file.isFile) {
            if (workId != null) store.consumedWorkId = workId
            failDownload(info, UpdateFailure.FileUnavailable)
            return
        }
        val invalid = validator.validate(file)
        if (invalid != null) {
            file.delete()
            if (workId != null) store.consumedWorkId = workId
            failDownload(info, invalid)
            return
        }
        store.downloadedVersion = info.versionName
        store.downloadedApkPath = file.absolutePath
        // 校验通过并写入待安装记录后再标记消费；重复成功回调不会清掉这条记录。
        if (workId != null) store.consumedWorkId = workId
        if (environment.isAppForeground) {
            if (installInProgress.compareAndSet(false, true)) {
                try {
                    launchInstaller(info, file)
                } finally {
                    installInProgress.set(false)
                }
            }
        } else {
            // Android 后台启动 Activity 受限：发通知，用户点通知/下次进入时安装。
            notifier.notifyDownloadComplete(info)
            _state.update { it.copy(phase = UpdatePhase.Idle) }
        }
    }

    /** 已消费的成功结果：只清待安装记录/取消旧任务，不删除安装器可能仍在读取的 APK。 */
    private fun finishConsumedSuccess(workId: String?) {
        if (workId != null) store.consumedWorkId = workId
        clearPendingInstall()
        scheduler.cancel(workId)
        _state.update { if (it.phase is UpdatePhase.Idle) it else it.copy(phase = UpdatePhase.Idle) }
    }

    /** 清理“目标版本已安装/无更新信息”场景的残留下载与后台任务状态。 */
    private fun cleanupStaleDownload(apkPath: String?, workId: String? = null) {
        runCatching { apkPath?.let { File(it).delete() } }
        runCatching { environment.workDir.listFiles()?.forEach { it.delete() } }
        clearPendingInstall()
        scheduler.cancel(workId)
        _state.update { if (it.phase is UpdatePhase.Idle) it else it.copy(phase = UpdatePhase.Idle) }
    }

    /** 前台/点通知后安装已下载的 APK（从持久化 store 恢复，跨进程可用）。 */
    fun installDownloadedIfReady() {
        scope.launch { installPendingIfAny() }
    }

    /** @return true 表示安装/授权/校验流程已接管 UI，冷启动检查不得覆盖。 */
    private suspend fun installPendingIfAny(): Boolean {
        if (!installInProgress.compareAndSet(false, true)) return true
        return try {
            installPendingIfAnyLocked()
        } finally {
            installInProgress.set(false)
        }
    }

    private suspend fun installPendingIfAnyLocked(): Boolean {
        val version = store.downloadedVersion ?: return false
        val path = store.downloadedApkPath ?: return false
        if (!isNewerVersion(version, environment.currentVersionName)) {
            File(path).delete()
            clearPendingInstall()
            return false
        }
        if (store.installerLaunchedVersion == version) {
            // 已经拉起过安装器（用户取消/未完成）：不自动重复拉起。
            clearPendingInstall()
            return false
        }
        val file = File(path)
        if (!file.isFile) {
            clearPendingInstall()
            return false
        }
        val info = store.cachedInfo()?.takeIf { it.versionName == version }
            ?: UpdateInfo(
                versionName = version,
                tagName = "v$version",
                title = version,
                notes = "",
                publishedAt = null,
                htmlUrl = "",
                apkUrl = "",
                apkSizeBytes = 0L,
                sha256 = null,
                sha256Url = null,
            )
        _state.update { it.copy(phase = UpdatePhase.Verifying(info)) }
        val invalid = validator.validate(file)
        if (invalid != null) {
            file.delete()
            clearPendingInstall()
            failDownload(info, invalid)
            return true
        }
        if (!installer.canInstallPackages()) {
            _state.update { it.copy(phase = UpdatePhase.NeedInstallPermission(info)) }
            return true
        }
        launchInstaller(info, file)
        return true
    }

    private suspend fun launchInstaller(info: UpdateInfo, file: File) {
        // 与源不同：Main 线程切换由 UpdateInstaller 实现负责（契约 §1.9）。
        val result = installer.install(file)
        when (result) {
            InstallResult.Launched -> {
                store.installerLaunchedVersion = info.versionName
                clearPendingInstall()
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            InstallResult.PermissionRequired ->
                _state.update { it.copy(phase = UpdatePhase.NeedInstallPermission(info)) }

            is InstallResult.Failed -> failDownload(info, result.failure)
        }
    }

    /**
     * 强制缓存展示后后台校验：Release 被删除/元数据回滚时，服务端结果会清缓存并关闭弹窗
     * （0ea927d）；网络失败则保留缓存的强制提示（离线降级）。
     */
    private fun verifyCachedForced() {
        if (!store.autoCheckEnabled) return
        if (verifyCachedJob?.isActive == true) return
        val now = clock()
        if (now - lastForcedVerifyAtMillis < config.autoRetryIntervalMs) return
        lastForcedVerifyAtMillis = now
        val id = taskId
        verifyCachedJob = scope.launch {
            val result = source.fetchLatest(store.channel)
            if (id != taskId) return@launch
            if (_state.value.phase !is UpdatePhase.Available) return@launch
            result.onSuccess { latest ->
                store.lastSuccessAtMillis = clock()
                store.lastAttemptAtMillis = clock()
                val newer = isNewerVersion(latest.versionName, environment.currentVersionName)
                val forced = UpdateRollout.isForced(latest, environment.currentVersionName)
                val eligible = UpdateRollout.isEligible(store.installId, latest)
                val phase = when {
                    !newer -> {
                        store.clearCache()
                        UpdatePhase.UpToDate(UpdateTrigger.AUTO, environment.currentVersionName)
                    }

                    forced -> {
                        store.saveCached(latest)
                        UpdatePhase.Available(UpdateTrigger.AUTO, latest, forced = true)
                    }

                    !eligible -> {
                        store.saveCached(latest)
                        UpdatePhase.RolloutPending(latest, latest.rolloutPercent)
                    }

                    else -> {
                        store.saveCached(latest)
                        UpdatePhase.Available(UpdateTrigger.AUTO, latest)
                    }
                }
                _state.update { it.copy(phase = phase, lastCheckAtMillis = clock()) }
            }.onFailure {
                // 离线/网络异常：保留缓存强制，退避窗口内不再重复后台校验（手动检查不受限）。
                store.lastAttemptAtMillis = clock()
            }
        }
    }

    private fun armDownloadWatchdog(info: UpdateInfo, workId: String?, generation: Long) {
        if (downloadWatchdogJob?.isActive == true) return
        downloadWatchdogJob = scope.launch {
            delay(config.downloadStartTimeoutMs)
            if (generation != downloadGeneration) return@launch
            if (_state.value.phase is UpdatePhase.Downloading) {
                // 异步入队失败/后台调度长时间不启动：主动失败并取消任务，
                // 避免不可取消的「0%」界面卡死（4c2a56c）。先使代际失效，迟到的入队结果只能清理自己。
                downloadGeneration++
                val currentId = workId ?: activeDownloadWorkId
                scheduler.cancel(currentId)
                activeDownloadWorkId = null
                downloadActiveInProcess = false
                environment.log("更新下载启动看门狗超时：$currentId")
                failDownload(info, UpdateFailure.Timeout)
            }
        }
    }

    private fun cancelDownloadWatchdog() {
        downloadWatchdogJob?.cancel()
        downloadWatchdogJob = null
    }

    private fun isDownloadInProgressPhase(phase: UpdatePhase): Boolean =
        phase is UpdatePhase.Downloading ||
            phase is UpdatePhase.Verifying ||
            phase is UpdatePhase.NeedInstallPermission

    /** 清理已安装/过期的 APK 缓存（5f598bb：安装后清理）；活跃下载（.part 或更高版本）保留。 */
    private fun cleanupOldDownloadCache() {
        val files = environment.workDir.listFiles() ?: return
        val now = clock()
        val prefix = config.sanitizedDownloadFilePrefix + "-"
        files.forEach { file ->
            val name = file.name
            val version = name
                .removePrefix(prefix)
                .removeSuffix(".part")
                .removeSuffix(".apk")
            val keepForUpdate = version.isNotBlank() &&
                isNewerVersion(version, environment.currentVersionName)
            val tooOld = now - file.lastModified() >= 7L * 24 * 60 * 60 * 1_000L
            if (!keepForUpdate || tooOld) file.delete()
        }
    }

    private fun currentActionInfo(): UpdateInfo? = when (val phase = _state.value.phase) {
        is UpdatePhase.Available -> phase.info
        is UpdatePhase.Downloading -> phase.info
        is UpdatePhase.Verifying -> phase.info
        is UpdatePhase.NeedInstallPermission -> phase.info
        is UpdatePhase.DownloadFailed -> phase.info
        else -> null
    }

    private fun failDownload(info: UpdateInfo, failure: UpdateFailure) {
        if (failure == UpdateFailure.ReleaseUnavailable) {
            // Release/资产已下架（0ea927d + 5f598bb 重置节流）：清缓存与强制状态，弹可关闭的一次性失败提示，
            // 避免用同一缓存 URL 无限重试/重启后再次强制。
            store.clearCache()
            store.lastSuccessAtMillis = 0L
            store.lastAttemptAtMillis = 0L
            clearPendingInstall()
            scheduler.cancel()
            downloadActiveInProcess = false
            activeForced = false
            _state.update {
                it.copy(
                    phase = UpdatePhase.DownloadFailed(
                        info = info,
                        failure = failure,
                        forced = false,
                        escapeAllowed = true,
                    ),
                    forcedEscapeAllowed = true,
                )
            }
            return
        }
        if (!isNewerVersion(info.versionName, environment.currentVersionName)) {
            // 已安装完成后的迟到失败（旧任务/旧回调）不应再打扰用户。
            cleanupStaleDownload(null)
            return
        }
        val forced = activeForced || (_state.value.phase as? UpdatePhase.DownloadFailed)?.forced == true
        downloadFailureCount++
        val escapeAllowed = forced && downloadFailureCount >= config.forcedEscapeFailures
        _state.update {
            it.copy(
                phase = UpdatePhase.DownloadFailed(
                    info = info,
                    failure = failure,
                    forced = forced,
                    escapeAllowed = escapeAllowed,
                ),
                forcedEscapeAllowed = escapeAllowed,
            )
        }
    }

    private fun clearPendingInstall() {
        store.downloadedVersion = null
        store.downloadedApkPath = null
        notifier.cancelDownloadComplete()
    }

    // ── 弹窗交互 ────────────────────────────────────────────────

    fun dismiss() {
        when (val phase = _state.value.phase) {
            is UpdatePhase.Available -> {
                if (phase.forced) return
                val version = phase.info.versionName
                scope.launch { store.dismissedVersion = version }
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            is UpdatePhase.DownloadFailed -> {
                if (phase.forced && !phase.escapeAllowed) return
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            is UpdatePhase.NeedInstallPermission ->
                _state.update { it.copy(phase = UpdatePhase.Idle) }

            else -> Unit
        }
    }

    fun retry() {
        when (_state.value.phase) {
            is UpdatePhase.CheckFailed -> checkNow()
            is UpdatePhase.DownloadFailed -> {
                val failed = _state.value.phase as UpdatePhase.DownloadFailed
                if (failed.failure == UpdateFailure.ReleaseUnavailable) {
                    checkNow()
                    return
                }
                _state.update {
                    it.copy(
                        phase = UpdatePhase.Available(
                            UpdateTrigger.MANUAL,
                            failed.info,
                            forced = failed.forced,
                        ),
                    )
                }
                startDownload()
            }

            else -> Unit
        }
    }

    /** 从「安装未知应用」设置页返回后：已授权则继续安装（源 `onResumeForInstallPermission`）。 */
    private fun onResumeForInstallPermission() {
        if (_state.value.phase !is UpdatePhase.NeedInstallPermission) return
        if (!installer.canInstallPackages()) return
        scope.launch { installPendingIfAny() }
    }

    /**
     * 应用回到前台：处理「安装权限」返回续装，以及**通知被拒/后台完成**场景。
     * 后台下载完成时若通知不可见，管理器只会把 APK 落到 store；用户下一次回到应用时
     * 在这里自动继续安装（无需等进程重启）。
     */
    fun onAppResumed() {
        onResumeForInstallPermission()
        scope.launch {
            when (_state.value.phase) {
                is UpdatePhase.Idle, is UpdatePhase.UpToDate -> installPendingIfAny()
                else -> Unit
            }
        }
    }

    fun openInstallPermissionSettings() {
        runCatching { installer.openInstallPermissionSettings() }
    }
}
