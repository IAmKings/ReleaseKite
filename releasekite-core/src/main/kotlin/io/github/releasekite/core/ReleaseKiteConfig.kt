package io.github.releasekite.core

import io.github.releasekite.core.internal.DEFAULT_FILE_PREFIX
import io.github.releasekite.core.internal.sanitizeFilePrefix

/**
 * ReleaseKite 的全局配置（宿主 App 在初始化时提供一次）。
 *
 * 相对 ZhiBox `UpdateConfig`（编译期常量 + `BuildConfig`）的泛化点：
 * - 仓库/资产命名不再是硬编码常量，`apkAssetPattern` / `checksumAssetSuffix` /
 *   `metadataAssetSuffix` / `downloadFilePrefix` 全部可配；
 * - 校验强度可降级（`requireChecksum=false`，仅大小 + 安装校验）；
 * - 元数据资产可整体关闭（`metadataAssetSuffix=null`）；
 * - 各节流/超时/看门狗间隔可配（单测/预览可注入短间隔）。
 *
 * 构造时用 `require` 做**快速失败**校验（非法仓库名/镜像/间隔在初始化阶段就暴露）；
 * `downloadFilePrefix` 是唯一的例外：非法字符替换为 `_` 而不是抛异常（见
 * [sanitizedDownloadFilePrefix]）。
 */
data class ReleaseKiteConfig(
    /** GitHub 仓库，`owner/repo`。 */
    val repository: String,
    /** APK 资产名匹配规则；默认任意 `.apk` 结尾（大小写不敏感）。 */
    val apkAssetPattern: Regex = Regex(""".*\.apk$""", RegexOption.IGNORE_CASE),
    /** 校验值资产后缀（与 APK 同名 + 该后缀）；默认 `.sha256`。 */
    val checksumAssetSuffix: String = ".sha256",
    /** 发布元数据资产后缀（灰度/强制）；null = 不拉取元数据。默认 `-update.json`。 */
    val metadataAssetSuffix: String? = "-update.json",
    /** 是否强制要求 SHA-256（`requireChecksum=false` 且无 digest 时跳过 SHA 校验）。 */
    val requireChecksum: Boolean = true,
    /** 下载镜像前缀（按顺序回退；每项必须 `https://` 且以 `/` 结尾）。 */
    val downloadMirrors: List<String> = emptyList(),
    /** 请求 User-Agent。Android 侧默认会补成 `ReleaseKite/<version> (<package>)`。 */
    val userAgent: String = "ReleaseKite",
    /** 下载文件名前缀（只允许 `[A-Za-z0-9._-]`，非法字符替换为 `_`）。 */
    val downloadFilePrefix: String = DEFAULT_FILE_PREFIX,
    /** 是否开放 Beta（prerelease）通道；关闭后 UI 只允许 STABLE。 */
    val betaChannelEnabled: Boolean = true,
    /** 自动检查成功后的节流窗口（源：24h）。 */
    val autoCheckIntervalMs: Long = UpdateDefaults.AUTO_CHECK_INTERVAL_MS,
    /** 自动检查失败后的退避窗口（源：1h）。 */
    val autoRetryIntervalMs: Long = UpdateDefaults.AUTO_RETRY_INTERVAL_MS,
    /** 强制缓存的冷启动最大年龄（源：24h）。 */
    val forcedCacheMaxAgeMs: Long = UpdateDefaults.FORCED_CACHE_MAX_AGE_MS,
    /** 普通/灰度缓存的重新校验间隔（源：6h）。 */
    val cacheRevalidateIntervalMs: Long = UpdateDefaults.CACHE_REVALIDATE_INTERVAL_MS,
    /** 下载整体超时（传给后台下载调度，源：5min）。 */
    val downloadTimeoutMs: Long = UpdateDefaults.DOWNLOAD_TIMEOUT_MS,
    /** 下载启动看门狗：入队后这么久仍未进入 RUNNING 即失败重试（源：20s）。 */
    val downloadStartTimeoutMs: Long = UpdateDefaults.DOWNLOAD_START_TIMEOUT_MS,
    /** 强制更新连续下载失败多少次后允许暂时退出（源：3）。 */
    val forcedEscapeFailures: Int = UpdateDefaults.FORCED_ESCAPE_FAILURES,
) {

    init {
        require(REPOSITORY_PATTERN.matches(repository)) {
            "repository 必须形如 owner/repo（仅 A-Za-z0-9_.-）：$repository"
        }
        require(downloadMirrors.all { it.startsWith("https://") && it.endsWith("/") }) {
            "downloadMirrors 每项必须是 https:// 开头且以 / 结尾：$downloadMirrors"
        }
        require(apkAssetPattern.pattern.isNotEmpty()) { "apkAssetPattern 不能为空" }
        require(checksumAssetSuffix.startsWith(".") && checksumAssetSuffix.length > 1) {
            "checksumAssetSuffix 必须以 . 开头且非空：$checksumAssetSuffix"
        }
        require(autoCheckIntervalMs >= 0L) { "autoCheckIntervalMs 必须 >= 0" }
        require(autoRetryIntervalMs >= 0L) { "autoRetryIntervalMs 必须 >= 0" }
        require(forcedCacheMaxAgeMs >= 0L) { "forcedCacheMaxAgeMs 必须 >= 0" }
        require(cacheRevalidateIntervalMs >= 0L) { "cacheRevalidateIntervalMs 必须 >= 0" }
        require(downloadTimeoutMs >= 0L) { "downloadTimeoutMs 必须 >= 0" }
        require(downloadStartTimeoutMs >= 0L) { "downloadStartTimeoutMs 必须 >= 0" }
        require(forcedEscapeFailures >= 1) { "forcedEscapeFailures 必须 >= 1" }
    }

    /**
     * 实际参与文件名拼接的前缀。
     *
     * `downloadFilePrefix` 保留调用方原值（便于配置往返），真正落盘时统一经过清洗：
     * 非法字符替换为 `_`，清洗后为空则回退默认 `releasekite`。
     */
    val sanitizedDownloadFilePrefix: String = sanitizeFilePrefix(downloadFilePrefix)

    private companion object {
        val REPOSITORY_PATTERN = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
    }
}

/**
 * core 的默认常量（与 ZhiBox `UpdateConfig` 数值保持一一对应）。
 *
 * 这些值只是 [ReleaseKiteConfig] 的默认参数，宿主可逐项覆盖。
 */
object UpdateDefaults {
    /** 更新说明本地展示上限（完整内容可跳浏览器）。 */
    const val NOTES_LIMIT = 4_000

    /** 自动检查成功后的节流窗口。 */
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L

    /** 自动检查失败后的退避窗口。 */
    const val AUTO_RETRY_INTERVAL_MS = 60 * 60 * 1_000L

    /** 强制缓存超过该年龄时冷启动先联网校验（失败再回退缓存）。 */
    const val FORCED_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

    /** 普通/灰度缓存超过该年龄时自动重新校验。 */
    const val CACHE_REVALIDATE_INTERVAL_MS = 6 * 60 * 60 * 1_000L

    /** 下载整体超时（后台调度使用）。 */
    const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L

    /** 下载启动看门狗：入队 20s 未进入 RUNNING 即失败。 */
    const val DOWNLOAD_START_TIMEOUT_MS = 20_000L

    /** 强制更新连续下载失败多少次后允许暂时退出。 */
    const val FORCED_ESCAPE_FAILURES = 3

    /** Beta 通道列表每页条数（`releases?per_page=N`）。 */
    const val RELEASES_PER_PAGE = 10

    /** Release 列表/详情响应体上限（beta 列表包含多个 release body）。 */
    const val MAX_RELEASE_BODY_BYTES = 1_024 * 1_024

    /** `update.json` 元数据响应体上限。 */
    const val MAX_META_BODY_BYTES = 64 * 1024

    /** `.sha256` 资产响应体上限。 */
    const val MAX_SHA_FILE_BYTES = 4 * 1024

    /** 重定向最大跳数（下载与元数据一致）。 */
    const val MAX_REDIRECTS = 5
}
