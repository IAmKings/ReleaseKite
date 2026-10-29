package io.github.releasekite.core

import io.github.releasekite.core.testing.MockHttpsServer
import io.github.releasekite.core.testing.MockResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置泛化新增测试（ReleaseKite 相对 ZhiBox 的扩展点）：
 * - 自定义 APK 资产 pattern / 自定义 checksum 后缀；
 * - 可选 checksum（`requireChecksum=false` 且无校验值 → 跳过 SHA，仅大小校验）；
 * - 关闭 metadata 资产（`metadataAssetSuffix=null`）；
 * - 自定义下载文件名前缀（含非法字符清洗）；
 * - 自定义节流/强制缓存间隔在 [UpdatePolicy] 中生效。
 */
class UpdateConfigGeneralizationTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(assetName: String, url: String, extra: String = ""): String = """
        {
          "tag_name": "v1.2.3",
          "name": "ReleaseKite v1.2.3",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-27T00:00:00Z",
          "html_url": "https://github.com/acme/app/releases/tag/v1.2.3",
          "body": "- 更新",
          "assets": [
            {"name": "$assetName", "size": 9,
             "browser_download_url": "$url",
             "digest": "sha256:${"a".repeat(64)}"}$extra
          ]
        }
    """.trimIndent()

    // ── 自定义 APK 资产 pattern ─────────────────────────────────

    @Test
    fun customApkAssetPatternSelectsMatchingAssetOnly() {
        val config = ReleaseKiteConfig(
            repository = "acme/app",
            apkAssetPattern = Regex(""".*-universal\.apk$""", RegexOption.IGNORE_CASE),
        )
        // 两个 APK 资产，只有 universal 命中自定义 pattern → 唯一命中，不触发「多 APK」失败。
        val json = """
            {
              "tag_name": "v1.2.3", "name": "v1.2.3", "draft": false, "prerelease": false,
              "html_url": "https://github.com/acme/app/releases/tag/v1.2.3",
              "body": "notes",
              "assets": [
                {"name": "app-1.2.3-arm64.apk", "size": 10,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-arm64.apk",
                 "digest": "sha256:${"b".repeat(64)}"},
                {"name": "app-1.2.3-universal.apk", "size": 9,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-universal.apk",
                 "digest": "sha256:${"a".repeat(64)}"}
              ]
            }
        """.trimIndent()
        val info = GitHubReleaseParser.parseLatestRelease(json, config)
        assertEquals("1.2.3", info.versionName)
        assertEquals(
            "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-universal.apk",
            info.apkUrl,
        )
        assertNull("默认 .sha256 后缀资产不存在", info.sha256Url)
    }

    @Test
    fun customChecksumAssetSuffixIsRecognized() {
        val config = ReleaseKiteConfig(
            repository = "acme/app",
            checksumAssetSuffix = ".sha256sum",
        )
        val apkName = "app-1.2.3.apk"
        val json = """
            {
              "tag_name": "v1.2.3", "name": "v1.2.3", "draft": false, "prerelease": false,
              "html_url": "https://github.com/acme/app/releases/tag/v1.2.3",
              "body": "notes",
              "assets": [
                {"name": "$apkName", "size": 9,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/$apkName"},
                {"name": "$apkName.sha256sum", "size": 100,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/$apkName.sha256sum"}
              ]
            }
        """.trimIndent()
        val info = GitHubReleaseParser.parseLatestRelease(json, config)
        assertNull("未提供 API digest", info.sha256)
        assertEquals(
            "https://github.com/acme/app/releases/download/v1.2.3/$apkName.sha256sum",
            info.sha256Url,
        )
    }

    // ── 关闭 metadata ──────────────────────────────────────────

    @Test
    fun metadataCanBeDisabled() {
        val config = ReleaseKiteConfig(repository = "acme/app", metadataAssetSuffix = null)
        val json = """
            {
              "tag_name": "v1.2.3", "name": "v1.2.3", "draft": false, "prerelease": false,
              "html_url": "https://github.com/acme/app/releases/tag/v1.2.3",
              "body": "notes",
              "assets": [
                {"name": "app-1.2.3.apk", "size": 9,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3.apk",
                 "digest": "sha256:${"a".repeat(64)}"},
                {"name": "app-1.2.3-update.json", "size": 100,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-update.json"}
              ]
            }
        """.trimIndent()
        val info = GitHubReleaseParser.parseLatestRelease(json, config)
        assertNull("metadataAssetSuffix=null 时不得识别元数据资产", info.metaUrl)
        assertEquals(100, info.rolloutPercent)
    }

    @Test
    fun customMetadataSuffixIsRecognized() {
        val config = ReleaseKiteConfig(repository = "acme/app", metadataAssetSuffix = "-meta.json")
        val json = """
            {
              "tag_name": "v1.2.3", "name": "v1.2.3", "draft": false, "prerelease": false,
              "html_url": "https://github.com/acme/app/releases/tag/v1.2.3",
              "body": "notes",
              "assets": [
                {"name": "app-1.2.3.apk", "size": 9,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3.apk",
                 "digest": "sha256:${"a".repeat(64)}"},
                {"name": "app-1.2.3-meta.json", "size": 100,
                 "browser_download_url": "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-meta.json"}
              ]
            }
        """.trimIndent()
        val info = GitHubReleaseParser.parseLatestRelease(json, config)
        assertEquals(
            "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3-meta.json",
            info.metaUrl,
        )
    }

    // ── 可选 checksum ──────────────────────────────────────────

    @Test
    fun optionalChecksumAllowsReleaseWithoutAnyHash() {
        val config = ReleaseKiteConfig(repository = "acme/app", requireChecksum = false)
        val info = GitHubReleaseParser.parseLatestRelease(
            releaseJson(
                assetName = "app-1.2.3.apk",
                url = "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3.apk",
            ).replace("\"digest\": \"sha256:${"a".repeat(64)}\"", "\"digest\": null"),
            config,
        )
        assertNull(info.sha256)
        assertNull(info.sha256Url)
    }

    @Test
    fun optionalChecksumDownloadSkipsShaAndOnlyChecksSize() = runBlocking {
        val payload = "no-checksum-apk".toByteArray(Charsets.UTF_8)
        val dir = java.nio.file.Files.createTempDirectory("releasekite-no-sha").toFile()
        try {
            MockHttpsServer {
                MockResponse(200, String(payload, Charsets.ISO_8859_1), "application/octet-stream")
            }.use { server ->
                val config = ReleaseKiteConfig(repository = "acme/app", requireChecksum = false)
                val downloader = HttpUpdateDownloader(
                    config = config,
                    testAllowedHosts = setOf("127.0.0.1"),
                    freeBytes = { Long.MAX_VALUE },
                )
                val info = UpdateInfo(
                    versionName = "1.2.3",
                    tagName = "v1.2.3",
                    title = "v1.2.3",
                    notes = "",
                    publishedAt = null,
                    htmlUrl = "https://github.com/acme/app/releases",
                    apkUrl = server.baseUrl("/download/app-1.2.3.apk"),
                    apkSizeBytes = payload.size.toLong(),
                    sha256 = null,
                    sha256Url = null,
                )
                val file = downloader.download(info, dir, "releasekite")
                assertArrayEquals(payload, file.readBytes())
                assertTrue(file.name.endsWith(".apk"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun optionalChecksumStillRejectsSizeMismatch() = runBlocking {
        val payload = "size-mismatch".toByteArray(Charsets.UTF_8)
        val dir = java.nio.file.Files.createTempDirectory("releasekite-bad-size").toFile()
        try {
            MockHttpsServer {
                MockResponse(200, String(payload, Charsets.ISO_8859_1), "application/octet-stream")
            }.use { server ->
                val config = ReleaseKiteConfig(repository = "acme/app", requireChecksum = false)
                val downloader = HttpUpdateDownloader(
                    config = config,
                    testAllowedHosts = setOf("127.0.0.1"),
                    freeBytes = { Long.MAX_VALUE },
                )
                val info = UpdateInfo(
                    versionName = "1.2.3",
                    tagName = "v1.2.3",
                    title = "v1.2.3",
                    notes = "",
                    publishedAt = null,
                    htmlUrl = "https://github.com/acme/app/releases",
                    apkUrl = server.baseUrl("/download/app-1.2.3.apk"),
                    apkSizeBytes = payload.size.toLong() + 1,
                    sha256 = null,
                    sha256Url = null,
                )
                val error = runCatching { downloader.download(info, dir, "releasekite") }
                    .exceptionOrNull() as UpdateException
                assertEquals(UpdateFailure.ChecksumMismatch, error.failure)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 自定义文件前缀 ─────────────────────────────────────────

    @Test
    fun customFilePrefixIsSanitizedAndUsedForDownloadName() = runBlocking {
        val payload = "prefix-apk".toByteArray(Charsets.UTF_8)
        val dir = java.nio.file.Files.createTempDirectory("releasekite-prefix").toFile()
        try {
            MockHttpsServer {
                MockResponse(200, String(payload, Charsets.ISO_8859_1), "application/octet-stream")
            }.use { server ->
                val downloader = HttpUpdateDownloader(
                    config = ReleaseKiteConfig(repository = "acme/app"),
                    testAllowedHosts = setOf("127.0.0.1"),
                    freeBytes = { Long.MAX_VALUE },
                )
                val info = UpdateInfo(
                    versionName = "1.2.3",
                    tagName = "v1.2.3",
                    title = "v1.2.3",
                    notes = "",
                    publishedAt = null,
                    htmlUrl = "https://github.com/acme/app/releases",
                    apkUrl = server.baseUrl("/download/app-1.2.3.apk"),
                    apkSizeBytes = payload.size.toLong(),
                    sha256 = sha256(payload),
                    sha256Url = null,
                )
                val file = downloader.download(info, dir, "my app/v2")
                assertEquals("my_app_v2-1.2.3.apk", file.name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 自定义间隔 ─────────────────────────────────────────────

    @Test
    fun customCheckIntervalDrivesLaunchPolicy() {
        val config = ReleaseKiteConfig(
            repository = "acme/app",
            autoCheckIntervalMs = 1_000L,
            autoRetryIntervalMs = 500L,
            cacheRevalidateIntervalMs = 2_000L,
            forcedCacheMaxAgeMs = 3_000L,
        )
        val store = InMemoryUpdateStore(lastSuccessAtMillis = 10_000L)
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(10_500L, store, "1.0.0", config),
        )
        assertEquals(
            UpdatePolicy.LaunchAction.Check,
            UpdatePolicy.decideOnLaunch(11_001L, store, "1.0.0", config),
        )
    }

    @Test
    fun customForcedCacheMaxAgeDrivesLaunchPolicy() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 10_000L,
            cachedInfo = UpdateInfo(
                versionName = "1.2.3",
                tagName = "v1.2.3",
                title = "v1.2.3",
                notes = "",
                publishedAt = null,
                htmlUrl = "",
                apkUrl = "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3.apk",
                apkSizeBytes = 1,
                sha256 = "a".repeat(64),
                sha256Url = null,
                minSupportedVersion = "1.0.0",
            ),
            cachedAtMillis = 10_000L,
        )
        val strictConfig = ReleaseKiteConfig(repository = "acme/app", forcedCacheMaxAgeMs = 1_000L)
        assertEquals(
            UpdatePolicy.LaunchAction.Check,
            UpdatePolicy.decideOnLaunch(11_500L, store, "0.9.0", strictConfig),
        )
        val lenientConfig = ReleaseKiteConfig(repository = "acme/app", forcedCacheMaxAgeMs = 5_000L)
        assertTrue(
            UpdatePolicy.decideOnLaunch(11_500L, store, "0.9.0", lenientConfig) is
                UpdatePolicy.LaunchAction.ShowCached,
        )
    }

    @Test
    fun customCacheRevalidateIntervalDrivesLaunchPolicy() {
        val now = 1_700_000_000_000L
        val cached = UpdateInfo(
            versionName = "1.2.3",
            tagName = "v1.2.3",
            title = "v1.2.3",
            notes = "",
            publishedAt = null,
            htmlUrl = "",
            apkUrl = "https://github.com/acme/app/releases/download/v1.2.3/app-1.2.3.apk",
            apkSizeBytes = 1,
            sha256 = "a".repeat(64),
            sha256Url = null,
        )
        val config = ReleaseKiteConfig(repository = "acme/app", cacheRevalidateIntervalMs = 60_000L)
        val freshStore = InMemoryUpdateStore(
            lastSuccessAtMillis = now,
            cachedInfo = cached,
            cachedAtMillis = now - 30_000L,
        )
        assertTrue(
            UpdatePolicy.decideOnLaunch(now, freshStore, "1.0.0", config) is
                UpdatePolicy.LaunchAction.ShowCached,
        )
        val staleStore = InMemoryUpdateStore(
            lastSuccessAtMillis = now,
            cachedInfo = cached,
            cachedAtMillis = now - 60_001L,
        )
        assertEquals(
            UpdatePolicy.LaunchAction.Check,
            UpdatePolicy.decideOnLaunch(now, staleStore, "1.0.0", config),
        )
    }

    @Test
    fun configUserAgentAndMirrorsArePassedThrough() {
        val config = ReleaseKiteConfig(
            repository = "acme/app",
            userAgent = "ReleaseKite/9.9.9 (com.acme.app)",
            downloadMirrors = listOf("https://gh-proxy.test/"),
        )
        assertEquals("ReleaseKite/9.9.9 (com.acme.app)", config.userAgent)
        assertEquals(listOf("https://gh-proxy.test/"), config.downloadMirrors)
    }
}
