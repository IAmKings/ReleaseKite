package io.github.releasekite.core.internal

import io.github.releasekite.core.UpdateException
import io.github.releasekite.core.UpdateFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * core 内部网络/文件工具（不对外暴露）。
 *
 * 这些实现从 ZhiBox `UpdateApi.kt` / `UpdateDownloader.kt` 的私有伴生工具逐段抽取，
 * 行为保持不变：响应体硬上限、流式 SHA-256、`Content-Range` 解析与文件名清洗。
 */

/**
 * 允许的 GitHub 资产/元数据主机白名单。
 *
 * 直连下载初始地址必须是 `github.com`，重定向逐跳校验后只允许落到 GitHub CDN 域；
 * 元数据（`update.json`）同源逻辑见 `GitHubUpdateSource`。
 */
internal val GITHUB_HOSTS: Set<String> = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

/** 读取至多 [limit] 字节；超出即判失败（防止恶意/异常大响应拖垮内存）。 */
internal fun readLimited(input: InputStream, limit: Int): String {
    val buffer = ByteArray(16 * 1024)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) {
            throw UpdateException(UpdateFailure.Parse, "响应体超过 ${limit}B")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

/** 流式计算文件 SHA-256（小写 hex）；下载完成后整文件校验用。 */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/** 解析 `Content-Range: bytes START-END/TOTAL` 的 START；非法/缺失返回 null。 */
internal fun contentRangeStart(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
        .find(header) ?: return null
    return match.groupValues[1].toLongOrNull()
}

/** 文件名中不允许出现的字符（保留 `A-Za-z0-9._-`，其余替换为 `_`）。 */
private val ILLEGAL_FILE_CHARS = Regex("""[^A-Za-z0-9._-]""")

/** 清洗单个文件名片段（版本号/前缀），避免路径穿越或多级目录。 */
internal fun sanitizeFileComponent(raw: String): String = raw.replace(ILLEGAL_FILE_CHARS, "_")

/**
 * 清洗下载文件名前缀。
 *
 * 配置契约要求 `downloadFilePrefix` 只允许 `[A-Za-z0-9._-]`：非法字符替换为 `_`（不抛异常），
 * 全部非法而变空时回退到 [DEFAULT_FILE_PREFIX]，保证下载路径永远落在工作目录内。
 */
internal fun sanitizeFilePrefix(raw: String): String =
    sanitizeFileComponent(raw).ifBlank { DEFAULT_FILE_PREFIX }

internal const val DEFAULT_FILE_PREFIX: String = "releasekite"
