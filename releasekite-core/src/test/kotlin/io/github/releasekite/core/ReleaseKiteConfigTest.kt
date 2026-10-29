package io.github.releasekite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseKiteConfig] 新增测试：构造期快速失败校验 + 默认值 + 文件名前缀清洗。
 *
 * 契约要求（§1.1）：
 * - `repository` 匹配 `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`；
 * - `downloadMirrors` 每项 `https://` 且以 `/` 结尾；
 * - `apkAssetPattern` 非空；`checksumAssetSuffix` 非空且以 `.` 开头；
 * - 所有 `*Ms >= 0`，`forcedEscapeFailures >= 1`；
 * - `downloadFilePrefix` 仅允许 `[A-Za-z0-9._-]`（非法字符替换为 `_`，不抛异常）。
 */
class ReleaseKiteConfigTest {

    @Test
    fun defaultsMatchFrozenContract() {
        val config = ReleaseKiteConfig(repository = "IAmKings/ZhiBox")
        assertEquals("IAmKings/ZhiBox", config.repository)
        assertTrue("默认资产规则匹配任意 .apk", config.apkAssetPattern.matches("app-release.apk"))
        assertTrue("默认资产规则大小写不敏感", config.apkAssetPattern.matches("APP.APK"))
        assertEquals(".sha256", config.checksumAssetSuffix)
        assertEquals("-update.json", config.metadataAssetSuffix)
        assertTrue(config.requireChecksum)
        assertEquals(emptyList<String>(), config.downloadMirrors)
        assertEquals("ReleaseKite", config.userAgent)
        assertEquals("releasekite", config.sanitizedDownloadFilePrefix)
        assertTrue(config.betaChannelEnabled)
        assertEquals(UpdateDefaults.AUTO_CHECK_INTERVAL_MS, config.autoCheckIntervalMs)
        assertEquals(UpdateDefaults.AUTO_RETRY_INTERVAL_MS, config.autoRetryIntervalMs)
        assertEquals(UpdateDefaults.FORCED_CACHE_MAX_AGE_MS, config.forcedCacheMaxAgeMs)
        assertEquals(UpdateDefaults.CACHE_REVALIDATE_INTERVAL_MS, config.cacheRevalidateIntervalMs)
        assertEquals(UpdateDefaults.DOWNLOAD_TIMEOUT_MS, config.downloadTimeoutMs)
        assertEquals(UpdateDefaults.DOWNLOAD_START_TIMEOUT_MS, config.downloadStartTimeoutMs)
        assertEquals(UpdateDefaults.FORCED_ESCAPE_FAILURES, config.forcedEscapeFailures)
    }

    @Test
    fun rejectsMalformedRepository() {
        listOf("", "just-repo", "a/b/c", "a b/c", "owner/re po", "owner/repo/").forEach { repository ->
            assertThrows("应拒绝 repository=$repository", IllegalArgumentException::class.java) {
                ReleaseKiteConfig(repository = repository)
            }
        }
        // 合法的 owner/repo（含 . _ - 字符）
        ReleaseKiteConfig(repository = "I-Am_Kings/Zhi.Box_2")
    }

    @Test
    fun rejectsMalformedDownloadMirrors() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(
                repository = "a/b",
                downloadMirrors = listOf("http://mirror.example/"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(
                repository = "a/b",
                downloadMirrors = listOf("https://mirror.example"),
            )
        }
        ReleaseKiteConfig(
            repository = "a/b",
            downloadMirrors = listOf("https://mirror.example/", "https://gh-proxy.test/"),
        )
    }

    @Test
    fun rejectsEmptyAssetRules() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", apkAssetPattern = Regex(""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", checksumAssetSuffix = "sha256")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", checksumAssetSuffix = ".")
        }
    }

    @Test
    fun rejectsInvalidIntervalsAndForcedThreshold() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", autoCheckIntervalMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", autoRetryIntervalMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", forcedCacheMaxAgeMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", cacheRevalidateIntervalMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", downloadTimeoutMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", downloadStartTimeoutMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseKiteConfig(repository = "a/b", forcedEscapeFailures = 0)
        }
        // 边界合法：0 间隔 / 1 次失败阈值
        ReleaseKiteConfig(
            repository = "a/b",
            autoCheckIntervalMs = 0L,
            autoRetryIntervalMs = 0L,
            forcedCacheMaxAgeMs = 0L,
            cacheRevalidateIntervalMs = 0L,
            downloadTimeoutMs = 0L,
            downloadStartTimeoutMs = 0L,
            forcedEscapeFailures = 1,
        )
    }

    @Test
    fun invalidDownloadFilePrefixIsSanitizedWithoutThrowing() {
        val config = ReleaseKiteConfig(
            repository = "a/b",
            downloadFilePrefix = "my app/v2",
        )
        // 原始值保留（配置往返），实际使用点只读清洗值。
        assertEquals("my app/v2", config.downloadFilePrefix)
        assertEquals("my_app_v2", config.sanitizedDownloadFilePrefix)

        val copied = config.copy(downloadFilePrefix = "bad*prefix?")
        assertEquals("bad_prefix_", copied.sanitizedDownloadFilePrefix)
    }

    @Test
    fun defaultFilePrefixConstantMatchesSanitizerFallback() {
        assertEquals("releasekite", ReleaseKiteConfig(repository = "a/b").sanitizedDownloadFilePrefix)
    }
}
