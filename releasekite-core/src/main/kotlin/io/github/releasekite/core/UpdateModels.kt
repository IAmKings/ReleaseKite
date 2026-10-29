package io.github.releasekite.core

/**
 * 应用内更新的纯数据模型与状态机（逐段移植 ZhiBox `UpdateModels.kt` 的模型部分）。
 *
 * 与源相比只做两类变化：
 * - 去掉 `BuildConfig.VERSION_NAME` 依赖：当前版本由 [UpdateEnvironment] / 构造参数注入；
 * - 与配置泛化对齐：`notes` 不再写中文兜底（空串由 UI 显示本地化占位）。
 */

/** 更新通道：stable 只看正式版；beta 在 GitHub prerelease 中取最高 semver。 */
enum class UpdateChannel { STABLE, BETA }

/** 触发来源：自动（冷启动）或手动（设置里「检查更新」）。 */
enum class UpdateTrigger { AUTO, MANUAL }

/** 一条可下载的更新信息（GitHub release + 资产 + 校验值）。 */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    /** 更新说明（已 markdown→纯文本）；可为空串，占位文案由 UI 负责。 */
    val notes: String,
    val publishedAt: String?,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** 优先取 GitHub API 资产 digest；为空时用 [sha256Url] 回退下载 `.sha256` 文件。 */
    val sha256: String?,
    val sha256Url: String?,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    /** 灰度比例（0..100；默认 100 = 全量）。 */
    val rolloutPercent: Int = 100,
    /** 强制更新下限（当前版本低于它时必须更新）；null = 不强制。 */
    val minSupportedVersion: String? = null,
    /** `update.json` 元数据资产地址（内部用，不展示）。 */
    val metaUrl: String? = null,
)

/** 失败原因（core 不引资源 ID，由 UI 层映射为可读文案）。 */
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

    /** 下载时 Release 资产已不存在（404/410）：版本已下架，应清缓存并重新检查。 */
    data object ReleaseUnavailable : UpdateFailure
}

/** 携带结构化失败原因的异常；manager 以此切换状态机。 */
class UpdateException(
    val failure: UpdateFailure,
    message: String? = null,
) : Exception(message ?: failure.toString())

/**
 * 更新服务状态机（UI 只读；所有流转只在 [UpdateManager] 内发生）。
 */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    data class Checking(val trigger: UpdateTrigger) : UpdatePhase

    data class UpToDate(val trigger: UpdateTrigger, val versionName: String) : UpdatePhase

    data class Available(
        val trigger: UpdateTrigger,
        val info: UpdateInfo,
        /** 强制更新：弹窗不可取消（连续下载失败达到阈值后允许暂时退出）。 */
        val forced: Boolean = false,
    ) : UpdatePhase

    /** 新版本正在分批推送（命中灰度才提示）。 */
    data class RolloutPending(val info: UpdateInfo, val rolloutPercent: Int) : UpdatePhase

    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdatePhase

    data class Verifying(val info: UpdateInfo) : UpdatePhase

    data class NeedInstallPermission(val info: UpdateInfo) : UpdatePhase

    data class CheckFailed(val trigger: UpdateTrigger, val failure: UpdateFailure) : UpdatePhase

    data class DownloadFailed(
        val info: UpdateInfo,
        val failure: UpdateFailure,
        val forced: Boolean = false,
        /** 强制更新连续失败后允许暂时退出，避免永久锁死。 */
        val escapeAllowed: Boolean = false,
    ) : UpdatePhase
}

/** 供 Compose 消费的完整状态（Dialog 与设置行共用）。 */
data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val currentVersionName: String,
    val lastCheckAtMillis: Long = 0L,
    /** 自动检查开关（默认开；关闭后仅手动检查）。 */
    val autoCheckEnabled: Boolean = true,
    /** 更新通道（stable/beta）。 */
    val channel: UpdateChannel = UpdateChannel.STABLE,
    /** 配置是否允许 Beta 通道（UI 决定是否展示切换项）。 */
    val betaChannelEnabled: Boolean = true,
    /** 下载线路健康（直连/镜像；诊断展示）。 */
    val routeHealth: Map<String, RouteHealth> = emptyMap(),
    /** 强制更新失败后是否允许暂时退出。 */
    val forcedEscapeAllowed: Boolean = false,
)
