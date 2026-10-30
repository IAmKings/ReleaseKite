package io.github.releasekite.internal

import androidx.work.Data
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.UpdateDefaults
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo

/**
 * WorkManager 下载任务契约（输入/输出 Data key，契约 §2）。
 *
 * 从 ZhiBox `UpdateDownloadContract` 扩展而来：唯一工作名/tag 与失败编码保持一致，
 * 新增 `repository`、`file_prefix`、`user_agent`、`mirrors`、`require_checksum`、
 * `timeout_ms`，把 core 配置里与下载相关的参数跨进程传给 Worker
 * （Worker 可能在独立进程中冷启动，拿不到门面持有的 `ReleaseKiteConfig`）。
 */
object UpdateDownloadContract {

    /** 唯一工作名：同一时刻只允许一个下载任务。 */
    const val UNIQUE_WORK = "releasekite-update-download"

    /** 任务 tag：宿主/测试可用它观测下载任务。 */
    const val TAG = "releasekite-update"

    const val KEY_VERSION = "version"
    const val KEY_APK_URL = "apk_url"
    const val KEY_APK_SIZE = "apk_size"
    const val KEY_SHA256 = "sha256"
    const val KEY_SHA256_URL = "sha256_url"
    const val KEY_HTML_URL = "html_url"
    const val KEY_REPOSITORY = "repository"
    const val KEY_FILE_PREFIX = "file_prefix"
    const val KEY_USER_AGENT = "user_agent"

    /** 多个镜像前缀，`\n` 分隔（契约 §2）。 */
    const val KEY_MIRRORS = "mirrors"
    const val KEY_REQUIRE_CHECKSUM = "require_checksum"
    const val KEY_TIMEOUT_MS = "timeout_ms"

    /** WorkManager 进度 Data（仅供 observe() 映射 RUNNING 进度，非持久化输出）。 */
    const val KEY_DOWNLOADED = "downloaded"
    const val KEY_TOTAL = "total"

    /** 成功输出：APK 落盘绝对路径（目标版本已安装时无此 key）。 */
    const val OUT_APK_PATH = "apk_path"

    /** 失败输出：失败原因编码（见 [encodeUpdateFailure]）。 */
    const val OUT_FAILURE = "failure"

    /** 缺省文件前缀/UA 与 core `ReleaseKiteConfig` 默认值保持一致，仅作旧任务数据兜底。 */
    private const val DEFAULT_FILE_PREFIX = "releasekite"
    private const val DEFAULT_USER_AGENT = "ReleaseKite"

    /** 失败原因 ↔ WorkManager Data 字符串：固定字符串不受混淆影响，Http 用 `Http:code:rateLimited`。 */
    fun encodeUpdateFailure(failure: UpdateFailure): String = when (failure) {
        is UpdateFailure.Http -> "Http:${failure.code}:${failure.rateLimited}"
        UpdateFailure.Network -> "Network"
        UpdateFailure.Timeout -> "Timeout"
        UpdateFailure.Parse -> "Parse"
        UpdateFailure.NoRelease -> "NoRelease"
        UpdateFailure.IncompleteRelease -> "IncompleteRelease"
        UpdateFailure.NoChecksum -> "NoChecksum"
        UpdateFailure.ChecksumMismatch -> "ChecksumMismatch"
        UpdateFailure.ApkInvalid -> "ApkInvalid"
        UpdateFailure.InsufficientSpace -> "InsufficientSpace"
        UpdateFailure.InstallPermission -> "InstallPermission"
        UpdateFailure.InstallFailed -> "InstallFailed"
        UpdateFailure.FileUnavailable -> "FileUnavailable"
        UpdateFailure.Cancelled -> "Cancelled"
        UpdateFailure.ReleaseUnavailable -> "ReleaseUnavailable"
    }

    fun decodeUpdateFailure(value: String?): UpdateFailure {
        if (value.isNullOrBlank()) return UpdateFailure.Network
        if (value.startsWith("Http:")) {
            val parts = value.split(':')
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val rateLimited = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
            return UpdateFailure.Http(code, rateLimited)
        }
        return when (value) {
            "Timeout" -> UpdateFailure.Timeout
            "Parse" -> UpdateFailure.Parse
            "NoRelease" -> UpdateFailure.NoRelease
            "IncompleteRelease" -> UpdateFailure.IncompleteRelease
            "NoChecksum" -> UpdateFailure.NoChecksum
            "ChecksumMismatch" -> UpdateFailure.ChecksumMismatch
            "ApkInvalid" -> UpdateFailure.ApkInvalid
            "InsufficientSpace" -> UpdateFailure.InsufficientSpace
            "InstallPermission" -> UpdateFailure.InstallPermission
            "InstallFailed" -> UpdateFailure.InstallFailed
            "FileUnavailable" -> UpdateFailure.FileUnavailable
            "Cancelled" -> UpdateFailure.Cancelled
            "ReleaseUnavailable" -> UpdateFailure.ReleaseUnavailable
            else -> UpdateFailure.Network
        }
    }

    /** [DownloadRequest] → WorkManager 输入 Data（测试直接断言该映射，避免依赖真实网络/WorkManager 执行）。 */
    fun inputData(request: DownloadRequest): Data = Data.Builder()
        .putString(KEY_VERSION, request.info.versionName)
        .putString(KEY_APK_URL, request.info.apkUrl)
        .putLong(KEY_APK_SIZE, request.info.apkSizeBytes)
        .putString(KEY_SHA256, request.info.sha256)
        .putString(KEY_SHA256_URL, request.info.sha256Url)
        .putString(KEY_HTML_URL, request.info.htmlUrl)
        .putString(KEY_REPOSITORY, request.repository)
        .putString(KEY_FILE_PREFIX, request.filePrefix)
        .putString(KEY_USER_AGENT, request.userAgent)
        .putString(KEY_MIRRORS, request.mirrors.joinToString(MIRROR_SEPARATOR))
        .putBoolean(KEY_REQUIRE_CHECKSUM, request.requireChecksum)
        .putLong(KEY_TIMEOUT_MS, request.timeoutMs)
        .build()

    /** WorkManager 输入 Data → [DownloadRequest]；`version` 缺失视为非法任务数据返回 null。 */
    fun parseRequest(input: Data): DownloadRequest? {
        val version = input.getString(KEY_VERSION)?.takeIf { it.isNotBlank() } ?: return null
        // Worker 只需下载所需字段；标题/说明/元数据不参与下载，用最小占位值重建 UpdateInfo。
        val info = UpdateInfo(
            versionName = version,
            tagName = "v$version",
            title = version,
            notes = "",
            publishedAt = null,
            htmlUrl = input.getString(KEY_HTML_URL).orEmpty(),
            apkUrl = input.getString(KEY_APK_URL).orEmpty(),
            apkSizeBytes = input.getLong(KEY_APK_SIZE, 0L),
            sha256 = input.getString(KEY_SHA256),
            sha256Url = input.getString(KEY_SHA256_URL),
        )
        return DownloadRequest(
            info = info,
            repository = input.getString(KEY_REPOSITORY)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_REPOSITORY,
            filePrefix = input.getString(KEY_FILE_PREFIX)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_FILE_PREFIX,
            userAgent = input.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_USER_AGENT,
            mirrors = input.getString(KEY_MIRRORS)
                ?.split(MIRROR_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            requireChecksum = input.getBoolean(KEY_REQUIRE_CHECKSUM, true),
            timeoutMs = input.getLong(KEY_TIMEOUT_MS, UpdateDefaults.DOWNLOAD_TIMEOUT_MS),
        )
    }

    private const val DEFAULT_REPOSITORY = "releasekite/releasekite"
    private const val MIRROR_SEPARATOR = "\n"
}
