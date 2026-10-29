package io.github.releasekite.core

import io.github.releasekite.core.internal.GITHUB_HOSTS
import io.github.releasekite.core.internal.readLimited
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * GitHub Release 查询抽象（便于 manager 单测注入 fake）。
 *
 * 移植自 ZhiBox `UpdateApi.kt` 的 `UpdateApi` 接口。
 */
interface UpdateSource {
    /**
     * 查询最新版本。
     * - [UpdateChannel.STABLE]：`releases/latest`；
     * - [UpdateChannel.BETA]：`releases?per_page=N` 中取最高 semver（含 prerelease）。
     */
    suspend fun fetchLatest(channel: UpdateChannel = UpdateChannel.STABLE): Result<UpdateInfo>
}

/**
 * 匿名 GitHub API 客户端（逐段移植 ZhiBox `HttpUpdateApi`）。
 *
 * 设计约束（见 ZhiBox `docs/product/APP_UPDATE_SPEC.md` 与 ReleaseKite 契约 §1.5）：
 * - 只允许 HTTPS + 固定 api.github.com 地址（测试可注入 base URL / 连接工厂）；
 * - 响应体上限 1MB（beta 通道的 releases 列表包含多个 release body）；
 * - 403/429 区分「限流」与普通错误；404 = 无 Release；
 * - 阻塞 IO 全部在 [Dispatchers.IO]，取消时在关键点 `ensureActive()`；
 * - `update.json` 元数据：cache-buster + `Cache-Control: no-cache`、手动重定向 ≤5 跳、
 *   每跳 https + host 白名单、直连失败走镜像、单线路坏 JSON 继续尝试、
 *   最终失败回退默认值（不阻断更新）。相关回归提交：9a8fbea（缓存绕过）、
 *   68d06f0（重定向修复）、992e614（镜像回退）、5f598bb（逐跳 host 校验+坏 JSON 换路）。
 */
class GitHubUpdateSource(
    private val config: ReleaseKiteConfig,
    private val releasesApiBase: String =
        "https://api.github.com/repos/${config.repository}/releases",
    private val userAgent: String = config.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : UpdateSource {

    override suspend fun fetchLatest(
        channel: UpdateChannel,
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val endpoint = when (channel) {
            UpdateChannel.STABLE -> "$releasesApiBase/latest"
            UpdateChannel.BETA -> "$releasesApiBase?per_page=${UpdateDefaults.RELEASES_PER_PAGE}"
        }
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(URL(endpoint)).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", userAgent)
            }
            val code = connection.responseCode
            currentCoroutineContext().ensureActive()
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND ->
                    Result.failure(UpdateException(UpdateFailure.NoRelease, "release 404"))

                code == 403 || code == 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    val rateLimited = remaining == "0" || code == 429
                    Result.failure(UpdateException(UpdateFailure.Http(code, rateLimited)))
                }

                code !in 200..299 ->
                    Result.failure(UpdateException(UpdateFailure.Http(code)))

                else -> {
                    val body = connection.inputStream.use {
                        readLimited(it, UpdateDefaults.MAX_RELEASE_BODY_BYTES)
                    }
                    currentCoroutineContext().ensureActive()
                    val info = when (channel) {
                        UpdateChannel.STABLE -> GitHubReleaseParser.parseLatestRelease(body, config)
                        UpdateChannel.BETA -> GitHubReleaseParser.parseReleaseList(body, config)
                    }
                    Result.success(applyReleaseMeta(info))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(UpdateException(UpdateFailure.Timeout))
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateFailure.Network))
        } catch (e: Exception) {
            Result.failure(
                (e as? UpdateException) ?: UpdateException(UpdateFailure.Parse, e.message),
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 读取 Release 资产中的 `*-update.json`（灰度/强制元数据）。
     * 失败时不阻断更新：保持 100% / 非强制默认值。
     */
    private fun applyReleaseMeta(info: UpdateInfo): UpdateInfo {
        val metaUrl = info.metaUrl ?: return info
        val direct = runCatching { URL(metaUrl) }.getOrNull() ?: return info
        if (!direct.protocol.equals("https", ignoreCase = true)) return info
        val allowedHosts = GITHUB_HOSTS
        val mirrorPrefixes = config.downloadMirrors.filter {
            it.startsWith("https://") && it.endsWith("/")
        }
        val mirrorHosts = mirrorPrefixes.mapNotNull {
            runCatching { URL(it).host?.lowercase(Locale.ROOT) }.getOrNull()
        }.toSet()

        // 与 APK 下载一致：直连（GitHub 资产 CDN）不可达时按镜像前缀回退。
        // 每条线路都加 cache-buster + no-cache，保证灰度/强制元数据调整后立即生效（9a8fbea）。
        val candidates = buildList {
            add(metaUrl to false)
            mirrorPrefixes.forEach { prefix -> add(prefix + metaUrl to true) }
        }
        for ((candidate, mirrored) in candidates) {
            val url = runCatching { URL(candidate) }.getOrNull() ?: continue
            if (!url.protocol.equals("https", ignoreCase = true)) continue
            val host = url.host?.lowercase(Locale.ROOT)
            if (mirrored) {
                if (host == null || host !in mirrorHosts) continue
            } else {
                if (host == null || host !in allowedHosts) continue
            }
            val cacheBusted = runCatching {
                URL(
                    candidate + (if ('?' in candidate) "&" else "?") +
                        "t=" + System.currentTimeMillis(),
                )
            }.getOrNull() ?: url
            val body = fetchMetaFollowingRedirects(
                start = cacheBusted,
                initialMirrored = mirrored,
                allowedHosts = allowedHosts,
                mirrorHosts = mirrorHosts,
            ) ?: continue
            // 单条线路 200 但 JSON/内容损坏时继续尝试镜像，不能一条坏节点阻断检查。
            val parsed = try {
                UpdateMetaParser.parse(body)
            } catch (e: Exception) {
                continue
            }
            return UpdateMetaParser.apply(info, parsed)
        }
        return info
    }

    /**
     * 手动跟随元数据重定向（≤5 跳），逐跳校验 https + host；
     * 不使用 HttpURLConnection 的自动跟随，避免跳转到未校验域名（68d06f0）。
     */
    private fun fetchMetaFollowingRedirects(
        start: URL,
        initialMirrored: Boolean,
        allowedHosts: Set<String>,
        mirrorHosts: Set<String>,
    ): String? {
        var current = start
        repeat(UpdateDefaults.MAX_REDIRECTS + 1) { hop ->
            if (!current.protocol.equals("https", ignoreCase = true)) return null
            val host = current.host?.lowercase(Locale.ROOT) ?: return null
            val hostAllowed = when {
                !initialMirrored && hop == 0 -> host in allowedHosts
                initialMirrored && hop == 0 -> host in mirrorHosts
                else -> host in allowedHosts || host in mirrorHosts
            }
            if (!hostAllowed) return null
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(current).apply {
                    requestMethod = "GET"
                    connectTimeout = if (initialMirrored) MIRROR_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
                    readTimeout = if (initialMirrored) MIRROR_READ_TIMEOUT_MS else READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) return null
                    current = runCatching { URL(current, location) }.getOrNull() ?: return null
                } else if (code in 200..299) {
                    return connection.inputStream.use {
                        readLimited(it, UpdateDefaults.MAX_META_BODY_BYTES)
                    }
                } else {
                    return null
                }
            } catch (e: Exception) {
                return null
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
        return null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MIRROR_CONNECT_TIMEOUT_MS = 8_000
        const val MIRROR_READ_TIMEOUT_MS = 10_000
    }
}
