package io.github.releasekite.compose

import java.util.Locale

/**
 * UI 格式化工具。
 *
 * 刻意只依赖 `java.text`/字符串操作，**不用 java.time**：库 minSdk 24，
 * java.time 在 24–25 上需要 desugaring，会给宿主增加额外配置负担。
 */

/** 字节数 → 人类可读（下载/更新包以 MB 为主，超过 1GB 用 GB）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/**
 * GitHub `published_at`（ISO-8601，如 `2026-10-27T00:00:00Z`）→ 界面日期 `2026-10-27`。
 *
 * 只取日期前缀：更新说明里的日期不涉及时区换算，也不值得为它引入 java.time。
 * 非 ISO 日期格式（或空值）返回 null，由调用方决定是否显示。
 */
internal fun formatReleaseDate(publishedAt: String?): String? {
    val raw = publishedAt?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val datePart = raw.substringBefore('T')
    return datePart.takeIf { ISO_DATE_PREFIX.matches(it) }
}

/** 下载进度百分比（0–100）；总大小未知时返回 0。 */
internal fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
    if (totalBytes <= 0L) return 0
    val percent = (downloadedBytes.coerceAtLeast(0L) * 100L) / totalBytes
    return percent.toInt().coerceIn(0, 100)
}

private val ISO_DATE_PREFIX = Regex("""\d{4}-\d{2}-\d{2}""")
