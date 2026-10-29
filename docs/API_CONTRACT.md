# ReleaseKite API 契约（冻结）

> 本文件由 Lead 维护，T1–T4 并行开发期间**不得修改**；若实现中确需调整，先报 Lead。
> 目标：`releasekite-core` 纯 JVM；`releasekite` 只做 Android 适配；`releasekite-compose` 只做 UI。
> 行为契约与回归不变量见 `docs/MIGRATION_ZHIBOX.md` 与 ZhiBox `docs/product/APP_UPDATE_SPEC.md`。

## 0. 源码映射

| ReleaseKite | ZhiBox 源 |
|---|---|
| `core/Version.kt` | `UpdateModels.kt` 的 `AppVersion` / `parseAppVersion` / `isNewerVersion` |
| `core/UpdateModels.kt` | `UpdateModels.kt` 的 `UpdateInfo` / `UpdateFailure` / `UpdateTrigger` / `UpdatePhase` / `UpdateUiState` |
| `core/UpdateMeta.kt` | `UpdateModels.kt` 的 `UpdateMeta` / `UpdateMetaParser` |
| `core/GitHubReleaseParser.kt` | `UpdateModels.kt` 的 `UpdateReleaseParser` / `plainTextFromMarkdown` |
| `core/UpdateSource.kt` | `UpdateApi.kt` |
| `core/UpdateDownloader.kt` | `UpdateDownloader.kt` |
| `core/UpdateStore.kt` | `UpdatePreferences.kt` 的 `UpdateStore`（接口） |
| `core/UpdatePolicy.kt` | `UpdatePolicy.kt` |
| `core/UpdateRollout.kt` | `UpdateRollout.kt` |
| `core/UpdateRoutes.kt` | `UpdateRoutes.kt` |
| `core/UpdateSchedule.kt` | `UpdateDownloadScheduler.kt` 的 `DownloadWorkState` / `UpdateDownloadScheduler` + 新增 `DownloadRequest` |
| `core/UpdateInstaller.kt` | `UpdateInstaller.kt` 的接口部分 |
| `core/UpdateNotifier.kt` | 由 `UpdateNotifications` 调用点抽象 |
| `core/UpdateEnvironment.kt` | 由 `Context/BuildConfig/AppForegroundTracker` 调用点抽象 |
| `core/UpdateManager.kt` | `AppUpdateManager.kt`（857 行，逐段移植） |

## 1. `releasekite-core`（包 `io.github.releasekite.core`）

### 1.1 配置与常量

```kotlin
data class ReleaseKiteConfig(
    val repository: String,
    val apkAssetPattern: Regex = Regex(""".*\.apk$""", RegexOption.IGNORE_CASE),
    val checksumAssetSuffix: String = ".sha256",
    val metadataAssetSuffix: String? = "-update.json",
    val requireChecksum: Boolean = true,
    val downloadMirrors: List<String> = emptyList(),
    val userAgent: String = "ReleaseKite",
    val downloadFilePrefix: String = "releasekite",
    val betaChannelEnabled: Boolean = true,
    val autoCheckIntervalMs: Long = UpdateDefaults.AUTO_CHECK_INTERVAL_MS,
    val autoRetryIntervalMs: Long = UpdateDefaults.AUTO_RETRY_INTERVAL_MS,
    val forcedCacheMaxAgeMs: Long = UpdateDefaults.FORCED_CACHE_MAX_AGE_MS,
    val cacheRevalidateIntervalMs: Long = UpdateDefaults.CACHE_REVALIDATE_INTERVAL_MS,
    val downloadTimeoutMs: Long = UpdateDefaults.DOWNLOAD_TIMEOUT_MS,
    val downloadStartTimeoutMs: Long = UpdateDefaults.DOWNLOAD_START_TIMEOUT_MS,
    val forcedEscapeFailures: Int = UpdateDefaults.FORCED_ESCAPE_FAILURES,
)
```
构造时 `require(...)` 校验：
- `repository` 匹配 `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`；
- `downloadMirrors` 每项 `https://` 且以 `/` 结尾；
- `apkAssetPattern` 非空；`checksumAssetSuffix` 非空且以 `.` 开头；
- 所有 `*Ms >= 0`，`forcedEscapeFailures >= 1`；
- `downloadFilePrefix` 仅允许 `[A-Za-z0-9._-]`（非法字符替换为 `_`，不抛异常）。

派生属性：`val sanitizedDownloadFilePrefix: String`
- 构造期由 `downloadFilePrefix` 清洗得到（非法字符替换为 `_`，清洗后为空回退 `"releasekite"`）；
- 原始 `downloadFilePrefix` 保留调用方原值以便配置往返；**落盘文件名一律使用该派生值**
  （`downloadFilePrefix` 本身不直接参与文件名拼接）。

```kotlin
object UpdateDefaults {
    const val NOTES_LIMIT = 4_000
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L
    const val AUTO_RETRY_INTERVAL_MS = 60 * 60 * 1_000L
    const val FORCED_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
    const val CACHE_REVALIDATE_INTERVAL_MS = 6 * 60 * 60 * 1_000L
    const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L
    const val DOWNLOAD_START_TIMEOUT_MS = 20_000L
    const val FORCED_ESCAPE_FAILURES = 3
    const val RELEASES_PER_PAGE = 10
    const val MAX_RELEASE_BODY_BYTES = 1_024 * 1_024
    const val MAX_META_BODY_BYTES = 64 * 1024
    const val MAX_SHA_FILE_BYTES = 4 * 1024
    const val MAX_REDIRECTS = 5
}
```

### 1.2 版本

```kotlin
data class SemanticVersion(
    val raw: String,
    val parts: List<Int>,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemanticVersion>

fun parseVersion(raw: String): SemanticVersion?
fun isNewerVersion(remote: String, current: String): Boolean
```
语义与源 `AppVersion` 完全一致（`v`/缺段/`+build` 容错；预发布排序；不可解析保守返回 false）。

### 1.3 模型

```kotlin
enum class UpdateChannel { STABLE, BETA }
enum class UpdateTrigger { AUTO, MANUAL }

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String?,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String?,
    val sha256Url: String?,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val rolloutPercent: Int = 100,
    val minSupportedVersion: String? = null,
    val metaUrl: String? = null,
)

sealed interface UpdateFailure {
    data object Network : UpdateFailure
    data object Timeout : UpdateFailure
    data class Http(val code: Int, val rateLimited: Boolean = false) : UpdateFailure
    data object Parse : UpdateFailure
    data object NoRelease : UpdateFailure
    data object IncompleteRelease : UpdateFailure
    data object NoChecksum : UpdateFailure
    data object ChecksumMismatch : UpdateFailure
    data object ApkInvalid : UpdateFailure
    data object InsufficientSpace : UpdateFailure
    data object InstallPermission : UpdateFailure
    data object InstallFailed : UpdateFailure
    data object FileUnavailable : UpdateFailure
    data object Cancelled : UpdateFailure
    data object ReleaseUnavailable : UpdateFailure
}

class UpdateException(
    val failure: UpdateFailure,
    message: String? = null,
) : Exception(message ?: failure.toString())

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Checking(val trigger: UpdateTrigger) : UpdatePhase
    data class UpToDate(val trigger: UpdateTrigger, val versionName: String) : UpdatePhase
    data class Available(val trigger: UpdateTrigger, val info: UpdateInfo, val forced: Boolean = false) : UpdatePhase
    data class RolloutPending(val info: UpdateInfo, val rolloutPercent: Int) : UpdatePhase
    data class Downloading(val info: UpdateInfo, val downloadedBytes: Long, val totalBytes: Long) : UpdatePhase
    data class Verifying(val info: UpdateInfo) : UpdatePhase
    data class NeedInstallPermission(val info: UpdateInfo) : UpdatePhase
    data class CheckFailed(val trigger: UpdateTrigger, val failure: UpdateFailure) : UpdatePhase
    data class DownloadFailed(
        val info: UpdateInfo,
        val failure: UpdateFailure,
        val forced: Boolean = false,
        val escapeAllowed: Boolean = false,
    ) : UpdatePhase
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val currentVersionName: String,
    val lastCheckAtMillis: Long = 0L,
    val autoCheckEnabled: Boolean = true,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val betaChannelEnabled: Boolean = true,
    val routeHealth: Map<String, RouteHealth> = emptyMap(),
    val forcedEscapeAllowed: Boolean = false,
)
```

### 1.4 元数据 / 解析

```kotlin
data class UpdateMeta(val rolloutPercent: Int = 100, val minSupportedVersion: String? = null)

object UpdateMetaParser {
    fun parse(body: String): UpdateMeta        // schema != 1 / 非法值抛 UpdateException(Parse)
    fun apply(info: UpdateInfo, meta: UpdateMeta): UpdateInfo
}

fun plainTextFromMarkdown(markdown: String, limit: Int = UpdateDefaults.NOTES_LIMIT): String

object GitHubReleaseParser {
    fun parseLatestRelease(body: String, config: ReleaseKiteConfig): UpdateInfo
    fun parseReleaseList(body: String, config: ReleaseKiteConfig): UpdateInfo
    fun parseSha256File(text: String): String?
}
```
- 资产挑选：`config.apkAssetPattern.matches(name)`；0 个或多个 → `IncompleteRelease`。
- `browser_download_url` 必须 `https://github.com/`；`sha256Url` 必须与 APK 同名前缀 + `config.checksumAssetSuffix`。
- `requireChecksum=true` 且 `digest`/`.sha256` 皆无 → `NoChecksum`。
- `notes` 为空时保持空串（**不再在 core 写中文兜底**，由 UI 显示本地化占位）。
- metadata 资产按 `endsWith(config.metadataAssetSuffix)` 选取；`config.metadataAssetSuffix == null` 时跳过。

### 1.5 网络源

```kotlin
interface UpdateSource {
    suspend fun fetchLatest(channel: UpdateChannel = UpdateChannel.STABLE): Result<UpdateInfo>
}

class GitHubUpdateSource(
    private val config: ReleaseKiteConfig,
    private val releasesApiBase: String = "https://api.github.com/repos/${config.repository}/releases",
    private val userAgent: String = config.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateSource
```
- stable：`$releasesApiBase/latest`；beta：`$releasesApiBase?per_page=10`。
- 请求头：`Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2022-11-28`、`User-Agent`。
- 404 → `NoRelease`；403/429 → `Http(code, rateLimited)`；非 2xx → `Http(code)`；超时 → `Timeout`；IO → `Network`。
- 响应体上限 1MB；阻塞 IO 在 `Dispatchers.IO`；关键点 `ensureActive()`。
- `update.json` 拉取逻辑在 `GitHubUpdateSource` 内：cache-buster + `Cache-Control: no-cache`、手动重定向 ≤5 跳、每跳 https + host 白名单、直连失败走镜像、单线路坏 JSON 继续尝试、最终失败回退默认值（不阻断更新）。

### 1.6 下载器

```kotlin
interface UpdateDownloader {
    suspend fun download(
        info: UpdateInfo,
        targetDir: File,
        filePrefix: String,
        routeHealth: Map<String, RouteHealth> = emptyMap(),
        onRouteResult: (RouteHealth) -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File
}

class HttpUpdateDownloader(
    private val config: ReleaseKiteConfig,
    private val userAgent: String = config.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val mirrorPrefixes: List<String> = config.downloadMirrors,
    private val testAllowedHosts: Set<String> = emptySet(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : UpdateDownloader
```
行为与源 `HttpUpdateDownloader` 一致，差异：
- 文件名 `"$filePrefix-${info.versionName}.apk"`（versionName 清洗非法文件名字符）；
- `expectedSha` 可为 null（`config.requireChecksum=false` 且无校验值）→ 跳过 SHA，仅做大小/安装校验；
- `UpdateConfig.repoOwner/repoName` → `config.repository` 拆分；
- 其余超时/镜像/白名单/续传/404 判定不变。

### 1.7 存储 / 策略 / 灰度 / 线路

```kotlin
interface UpdateStore {
    var lastSuccessAtMillis: Long
    var lastAttemptAtMillis: Long
    var dismissedVersion: String?
    var autoCheckEnabled: Boolean
    var channel: UpdateChannel
    var installId: String
    fun cachedInfo(): UpdateInfo?
    fun saveCached(info: UpdateInfo)
    fun clearCache()
    val cachedMetaKnown: Boolean
    val cachedAtMillis: Long
    fun routeHealth(): Map<String, RouteHealth>
    fun updateRouteHealth(health: Map<String, RouteHealth>)
    var downloadedVersion: String?
    var downloadedApkPath: String?
    var installerLaunchedVersion: String?
    var consumedWorkId: String?
}

class InMemoryUpdateStore : UpdateStore   // 测试/preview 用，全部字段可初始化
```

```kotlin
object UpdatePolicy {
    sealed interface LaunchAction {
        data class ShowCached(val info: UpdateInfo) : LaunchAction
        data class RolloutPending(val info: UpdateInfo) : LaunchAction
        data object Check : LaunchAction
        data object Skip : LaunchAction
    }
    fun decideOnLaunch(
        nowMillis: Long,
        store: UpdateStore,
        currentVersionName: String,
        config: ReleaseKiteConfig,
    ): LaunchAction
    fun shouldAutoPrompt(info: UpdateInfo, currentVersionName: String, dismissedVersion: String?): Boolean
}
```

```kotlin
object UpdateRollout {
    fun cohort(installId: String, versionName: String): Int
    fun isEligible(installId: String, info: UpdateInfo): Boolean
    fun isForced(info: UpdateInfo, currentVersionName: String): Boolean
}

data class RouteHealth(
    val id: String,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastLatencyMs: Long = 0L,
    val cooldownUntil: Long = 0L,
) { val isCoolingDown: Boolean get() = cooldownUntil > 0L }

object UpdateRouteSelector {
    const val DIRECT_ID = "direct"
    const val FAILURE_THRESHOLD = 2
    const val COOLDOWN_MS = 6 * 60 * 60 * 1_000L
    fun plan(nowMillis: Long, mirrorIds: List<String>, health: Map<String, RouteHealth>, directId: String = DIRECT_ID): List<String>
    fun record(nowMillis: Long, previous: RouteHealth?, success: Boolean, latencyMs: Long, id: String): RouteHealth
}
```

### 1.8 调度 / 安装 / 通知 / 环境

```kotlin
data class DownloadRequest(
    val info: UpdateInfo,
    val repository: String,          // owner/repo：Worker 重建下载器时用于 release 路径白名单
    val filePrefix: String,
    val userAgent: String,
    val mirrors: List<String>,
    val requireChecksum: Boolean,
    val timeoutMs: Long,
)

data class DownloadWorkState(
    val workId: String? = null,
    val status: Status = Status.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val apkPath: String? = null,
    val failure: UpdateFailure? = null,
) {
    enum class Status { IDLE, WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

interface UpdateDownloadScheduler {
    suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit)
    fun observe(): Flow<DownloadWorkState>
    fun cancel(workId: String? = null)
}

sealed interface InstallResult {
    data object Launched : InstallResult
    data object PermissionRequired : InstallResult
    data class Failed(val failure: UpdateFailure) : InstallResult
}

interface UpdateInstaller {
    fun canInstallPackages(): Boolean
    suspend fun install(apk: File): InstallResult   // 实现负责切 Main
    fun openInstallPermissionSettings()
}

interface ApkValidator {
    fun validate(apk: File): UpdateFailure?
}

interface UpdateNotifier {
    fun prepare()                                  // 创建通知渠道等
    fun notifyDownloadComplete(info: UpdateInfo)
    fun cancelDownloadComplete()
}

interface UpdateEnvironment {
    val workDir: File
    val currentVersionName: String
    val currentVersionCode: Long
    val isAppForeground: Boolean
    fun log(message: String, error: Throwable? = null)
}
```

### 1.9 状态机

```kotlin
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
    val state: StateFlow<UpdateUiState>
    fun onAppEntered()
    fun onAppResumed()
    fun checkNow()
    fun startDownload()
    fun retry()
    fun dismiss()
    fun setAutoCheckEnabled(enabled: Boolean)
    fun setChannel(channel: UpdateChannel)
    fun resetRouteHealth()
    fun installDownloadedIfReady()
    fun openInstallPermissionSettings()
}
```
移植 `AppUpdateManager` 全部逻辑，替换点：
- `BuildConfig.VERSION_NAME` → `environment.currentVersionName`；
- `appContext.cacheDir/updates` → `environment.workDir`；
- `UpdateConfig.*` → `config.*`；
- `UpdateNotifications.*` → `notifier.prepare()/notifyDownloadComplete()/cancelDownloadComplete()`；
- `foreground()` → `environment.isAppForeground`；
- `withContext(Dispatchers.Main) { installer.install(...) }` → `installer.install(file)`（实现内部切 Main）；
- `installer.installPermissionIntent()` → `installer.openInstallPermissionSettings()`；
- 删除 `companion get(Context)` 与 `debugInjectMockState`；
- 删除 `UpdateConfig.ensureChannel` 调用，改为 `notifier.prepare()`。

Beta 偏好归一化与禁用守卫（配置是最终事实来源，见 MIGRATION_ZHIBOX §3）：
- 构造 `UpdateManager` 时（init）：若 `config.betaChannelEnabled == false` 且
  `store.channel == BETA`，把持久化通道归一化为 `STABLE`，再发布初始 `UpdateUiState`；
- `setChannel(BETA)`：`config.betaChannelEnabled == false` 时直接忽略（不修改 store/state）；
  通道真正变化时才清缓存、重置节流/失败退避与 `dismissedVersion`，并递增检查代际取消在途检查；
- `UpdateUiState.betaChannelEnabled` 始终来自 `config.betaChannelEnabled`，UI 据此隐藏 Beta 开关。

## 2. `releasekite`（Android，包 `io.github.releasekite` / `io.github.releasekite.internal`）

```kotlin
object ReleaseKite {
    fun initialize(context: Context, config: ReleaseKiteConfig): UpdateManager
    val manager: UpdateManager
    fun isInitialized(): Boolean
    fun onForeground()
    fun onBackground()
}
```
- `initialize` 读取 `packageManager.getPackageInfo(packageName, 0).versionName` 与 `PackageInfoCompat.getLongVersionCode`；默认 UA `"ReleaseKite/${BuildConfig.RELEASEKITE_VERSION} (${packageName})"`（若 config.userAgent 仍为默认 `"ReleaseKite"`）。
- 默认实现：`GitHubUpdateSource`、`HttpUpdateDownloader`、`WorkManagerUpdateDownloadScheduler`、`SharedPrefsUpdateStore`、`AndroidApkValidator`、`AndroidUpdateInstaller`、`AndroidUpdateNotifier`、`AndroidUpdateEnvironment`；`CoroutineScope(SupervisorJob() + Dispatchers.IO)`。

内部契约：
- 工作目录：`File(context.cacheDir, "releasekite/updates")`。
- prefs：`releasekite_update`（key 与 ZhiBox `SharedPrefsUpdateStore` 一致；`CACHE_META_SCHEMA=2`）。
- 通知：channel `releasekite_update`，进度 ID 4201，完成 ID 4202，PendingIntent requestCode 4203；小图标 `R.drawable.releasekite_ic_notification`。
- FileProvider authority：`"${context.packageName}.releasekite.updates"`。
- Worker：`io.github.releasekite.internal.ReleaseKiteDownloadWorker`；唯一工作名 `releasekite-update-download`，tag `releasekite-update`；无 Network/Storage 约束。
- Worker input keys：`version`、`apk_url`、`apk_size`、`sha256`、`sha256_url`、`html_url`、`repository`、`file_prefix`、`user_agent`、`mirrors`（`\n` 分隔）、`require_checksum`、`timeout_ms`；output：`apk_path`、`failure`（编码规则同 ZhiBox：`Http:code:rateLimited`，其余类名）。不保留 `installed_already`：目标版本已安装时 Worker 返回 `Result.success()` 且无 `apk_path`，Manager 按 SUCCEEDED + 无 apkPath 清理。
- 进度通知（前台服务/通知栏）是 Android 实现细节：`AndroidUpdateNotifier` 可提供 `progressNotification()/notifyProgress()/cancelProgress()` 等 public 方法供 Worker 使用，**不要求** core 的 `UpdateNotifier` 接口包含它们。
- `UpdateDownloadContract.encodeUpdateFailure/decodeUpdateFailure` 语义与源一致。

## 3. `releasekite-compose`（包 `io.github.releasekite.compose`）

```kotlin
data class ReleaseKiteActions(
    val onDownload: () -> Unit,
    val onDismiss: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenInstallSettings: () -> Unit,
)

data class ReleaseKiteDialogStyle(
    val shape: Shape = RoundedCornerShape(24.dp),
    val containerColor: Color? = null,
)

@Composable fun ReleaseKiteHost(
    manager: UpdateManager = ReleaseKite.manager,
    modifier: Modifier = Modifier,
    requestNotificationPermission: Boolean = true,
    style: ReleaseKiteDialogStyle = ReleaseKiteDialogStyle(),
)

@Composable fun ReleaseKiteUpdateDialog(
    state: UpdateUiState,
    actions: ReleaseKiteActions,
    modifier: Modifier = Modifier,
    style: ReleaseKiteDialogStyle = ReleaseKiteDialogStyle(),
)

@Composable fun ReleaseKiteUpdateRow(state: UpdateUiState, onClick: () -> Unit, modifier: Modifier = Modifier)
@Composable fun ReleaseKiteUpdateRow(manager: UpdateManager, modifier: Modifier = Modifier)

@Composable fun ReleaseKiteSettingsSection(
    state: UpdateUiState,
    onAutoCheckChange: (Boolean) -> Unit,
    onChannelChange: (UpdateChannel) -> Unit,
    onResetRouteHealth: () -> Unit,
    modifier: Modifier = Modifier,
)
@Composable fun ReleaseKiteSettingsSection(manager: UpdateManager, modifier: Modifier = Modifier)
```
- 可见 phase 与取消规则、按钮、进度、失败文案、设置行状态与 ZhiBox `AppUpdateDialog.kt` 完全一致。
- `ReleaseKiteHost` 负责 `onAppEntered`（每进程一次）、`onAppResumed`、前后台、通知权限请求；UI 只用 `stringResource`，资源前缀 `releasekite_`。
- 默认英文，`values-zh-rCN` 中文；失败原因全覆盖；`testTag`：`releasekite_update_dialog`、`releasekite_update_now`、`releasekite_update_later`、`releasekite_update_retry`、`releasekite_update_settings`、`releasekite_update_row`。

## 4. 构建/测试命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :releasekite-core:test
./gradlew :releasekite:testDebugUnitTest
./gradlew :releasekite-compose:testDebugUnitTest
./gradlew :sample:assembleDebug
./gradlew publishToMavenLocal
```

## 5. 文件所有权

| owner | 写入范围 |
|---|---|
| Lead | `settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`gradle/**`、`gradlew*`、`.gitignore`、`docs/API_CONTRACT.md` |
| core-eng | `releasekite-core/**` |
| platform-eng | `releasekite/**` |
| compose-eng | `releasekite-compose/**` |
| release-eng | `sample/**`、`docs/**`（除 API_CONTRACT.md）、`samples/**`、`README.md`、`CHANGELOG.md`、`LICENSE`、`NOTICE`、`jitpack.yml`、`.github/**` |
