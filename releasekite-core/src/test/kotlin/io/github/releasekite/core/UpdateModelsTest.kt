package io.github.releasekite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内更新纯逻辑测试（迁移自 ZhiBox `UpdateModelsTest`，33+ 用例中的 23 个）：
 * 版本比较 / Release JSON 解析 / markdown 清洗 / 启动节流策略。
 * 全部纯 JVM（无 Robolectric、无网络），配置统一由 [ReleaseKiteConfig] 注入。
 */
class UpdateModelsTest {

    private val config = ReleaseKiteConfig(repository = "IAmKings/ZhiBox")

    // ── 版本 ─────────────────────────────────────────────────────

    @Test
    fun parseVersion_toleratesPrefixSuffixAndMissingSegments() {
        assertEquals(listOf(0, 1, 6), parseVersion("v0.1.6")!!.parts)
        assertEquals(listOf(0, 1, 6), parseVersion("0.1.6-rc1")!!.parts)
        assertEquals(listOf("rc1"), parseVersion("0.1.6-rc1")!!.prerelease)
        assertEquals(listOf(0, 1), parseVersion("v0.1")!!.parts)
        assertEquals(listOf(1, 0, 0), parseVersion("1.0.0+build7")!!.parts)
        assertNull(parseVersion(""))
        assertNull(parseVersion("v"))
        assertNull(parseVersion("release"))
    }

    @Test
    fun semverPrereleaseOrdering() {
        assertTrue(isNewerVersion("0.1.7", "0.1.7-rc.1"))
        assertTrue(isNewerVersion("0.1.7-rc.1", "0.1.7-beta.2"))
        assertTrue(isNewerVersion("0.1.7-beta.2", "0.1.7-beta.1"))
        assertTrue(isNewerVersion("0.1.8-beta.1", "0.1.7"))
        assertFalse(isNewerVersion("0.1.7-beta.2", "0.1.7"))
    }

    @Test
    fun compareVersions_normalizesSegments() {
        assertTrue(isNewerVersion("0.1.6", "0.1.5"))
        assertTrue(isNewerVersion("v0.2", "0.1.9"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
        assertFalse(isNewerVersion("0.1.5", "0.1.5"))
        assertFalse(isNewerVersion("0.1.5", "0.1.6"))
        assertFalse(isNewerVersion("garbage", "0.1.5"))
    }

    // ── Release JSON ─────────────────────────────────────────────

    private val releaseJson = """
        {
          "tag_name": "v0.1.6",
          "name": "ZhiBox v0.1.6",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-27T02:00:00Z",
          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.6",
          "body": "## 0. v0.1.6\n\n- **设置页** 简化\n- 下载地址：[Release](https://example.com)\n",
          "assets": [
            {"name": "zhibox-0.1.6-abc1234-mapping.txt", "size": 100,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-mapping.txt",
             "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"name": "zhibox-0.1.6-abc1234-release-signed.apk", "size": 70345697,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-release-signed.apk",
             "digest": "sha256:08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06"},
            {"name": "zhibox-0.1.6-abc1234-release-signed.apk.sha256", "size": 106,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-release-signed.apk.sha256",
             "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
          ]
        }
    """.trimIndent()

    @Test
    fun parseLatestRelease_rejectsMultipleApkAssets() {
        val extraApk = """
            {"name": "zhibox-0.1.6-abc1234-arm64-release-signed.apk", "size": 100,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-arm64-release-signed.apk",
             "digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
        """
        val json = releaseJson.replace(
            """{"name": "zhibox-0.1.6-abc1234-release-signed.apk.sha256",""",
            extraApk + """{"name": "zhibox-0.1.6-abc1234-release-signed.apk.sha256",""",
        )
        val error = runCatching { GitHubReleaseParser.parseLatestRelease(json, config) }
            .exceptionOrNull()
        assertTrue(error is UpdateException)
        assertEquals(UpdateFailure.IncompleteRelease, (error as UpdateException).failure)
    }

    @Test
    fun parseLatestRelease_picksApkAndApiDigest() {
        val info = GitHubReleaseParser.parseLatestRelease(releaseJson, config)
        assertEquals("0.1.6", info.versionName)
        assertEquals("v0.1.6", info.tagName)
        assertEquals(70_345_697L, info.apkSizeBytes)
        assertEquals(
            "08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06",
            info.sha256,
        )
        assertTrue(info.apkUrl.endsWith("-release-signed.apk"))
        assertTrue(info.sha256Url!!.endsWith(".sha256"))
        // markdown 清洗：去掉 ##、**、链接语法
        assertFalse(info.notes.contains("##"))
        assertFalse(info.notes.contains("**"))
        assertFalse(info.notes.contains("]("))
        assertTrue(info.notes.contains("设置页"))
    }

    @Test
    fun parseReleaseList_picksHighestSemverIncludingPrerelease() {
        val beta = releaseJson
            .replace("\"tag_name\": \"v0.1.6\"", "\"tag_name\": \"v0.1.7-beta.1\"")
            .replace("\"prerelease\": false", "\"prerelease\": true")
        val body = "[$releaseJson, $beta]"
        val info = GitHubReleaseParser.parseReleaseList(body, config)
        assertEquals("0.1.7-beta.1", info.versionName)
        assertEquals(UpdateChannel.BETA, info.channel)
    }

    @Test
    fun parseReleaseList_stableListReturnsStableChannel() {
        val info = GitHubReleaseParser.parseReleaseList("[$releaseJson]", config)
        assertEquals("0.1.6", info.versionName)
        assertEquals(UpdateChannel.STABLE, info.channel)
    }

    @Test
    fun parseLatestRelease_fallsBackToShaAssetWhenDigestMissing() {
        val json = releaseJson
            .replace(
                "\"digest\": \"sha256:08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06\"",
                "\"digest\": null",
            )
        val info = GitHubReleaseParser.parseLatestRelease(json, config)
        assertNull(info.sha256)
        assertTrue(info.sha256Url!!.endsWith(".sha256"))
    }

    @Test(expected = UpdateException::class)
    fun parseLatestRelease_rejectsPrerelease() {
        GitHubReleaseParser.parseLatestRelease(
            releaseJson.replace("\"prerelease\": false", "\"prerelease\": true"),
            config,
        )
    }

    @Test(expected = UpdateException::class)
    fun parseLatestRelease_rejectsMissingApkAsset() {
        GitHubReleaseParser.parseLatestRelease(
            releaseJson.replace(
                "zhibox-0.1.6-abc1234-release-signed.apk\"",
                "zhibox-0.1.6-abc1234-release-signed.apk.tmp\"",
            ),
            config,
        )
    }

    @Test
    fun parseSha256File_acceptsHashAndFilenameFormat() {
        val hex = "c".repeat(64)
        assertEquals(hex, GitHubReleaseParser.parseSha256File("$hex  zhibox.apk\n"))
        assertEquals(hex, GitHubReleaseParser.parseSha256File(hex))
        assertNull(GitHubReleaseParser.parseSha256File("not-a-hash"))
    }

    // ── 节流策略 ─────────────────────────────────────────────────

    private fun info(
        version: String,
        minSupportedVersion: String? = null,
        rolloutPercent: Int = 100,
    ) = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = "t",
        notes = "n",
        publishedAt = null,
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$version/x-release-signed.apk",
        apkSizeBytes = 1,
        sha256 = "d".repeat(64),
        sha256Url = null,
        minSupportedVersion = minSupportedVersion,
        rolloutPercent = rolloutPercent,
    )

    @Test
    fun launchPolicy_skipsWhenAutoCheckDisabled() {
        val store = InMemoryUpdateStore(autoCheckEnabled = false)
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(1_700_000_000_000L, store, "0.1.6", config),
        )
    }

    @Test
    fun launchPolicy_skipsWithinSuccessInterval() {
        val store = InMemoryUpdateStore(lastSuccessAtMillis = 100_000L)
        val action = UpdatePolicy.decideOnLaunch(
            nowMillis = 100_000L + UpdateDefaults.AUTO_CHECK_INTERVAL_MS - 1,
            store = store,
            currentVersionName = "0.1.6",
            config = config,
        )
        assertEquals(UpdatePolicy.LaunchAction.Skip, action)
    }

    @Test
    fun launchPolicy_checksAfterInterval() {
        val store = InMemoryUpdateStore(lastSuccessAtMillis = 100_000L)
        val action = UpdatePolicy.decideOnLaunch(
            nowMillis = 100_000L + UpdateDefaults.AUTO_CHECK_INTERVAL_MS + 1,
            store = store,
            currentVersionName = "0.1.6",
            config = config,
        )
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_skipsDuringFailureBackoff() {
        val store = InMemoryUpdateStore(lastAttemptAtMillis = 100_000L)
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(
                100_000L + UpdateDefaults.AUTO_RETRY_INTERVAL_MS - 1,
                store,
                "0.1.6",
                config,
            ),
        )
    }

    @Test
    fun launchPolicy_showsNewerCachedVersion() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7"),
            cachedAtMillis = 500_000L,
        )
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config)
        assertTrue(action is UpdatePolicy.LaunchAction.ShowCached)
    }

    @Test
    fun launchPolicy_forcedCachedOverridesDismissedAndAutoCheckOff() {
        val store = InMemoryUpdateStore(
            autoCheckEnabled = false,
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7", minSupportedVersion = "0.1.7"),
            cachedAtMillis = 500_000L,
            dismissedVersion = "0.1.7",
        )
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config)
        assertTrue(
            "强制缓存必须展示（即使取消过/关闭自动检查）",
            action is UpdatePolicy.LaunchAction.ShowCached,
        )
    }

    @Test
    fun launchPolicy_staleForcedCacheTriggersCheck() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7", minSupportedVersion = "0.1.7"),
            cachedAtMillis = 500_000L - UpdateDefaults.FORCED_CACHE_MAX_AGE_MS - 1,
        )
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config)
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_cachedRolloutMissReturnsPending() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7", rolloutPercent = 0),
            cachedAtMillis = 500_000L,
        )
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config)
        assertTrue("未命中灰度不应展示更新框", action is UpdatePolicy.LaunchAction.RolloutPending)
    }

    @Test
    fun launchPolicy_staleNormalCacheTriggersCheck() {
        val now = 1_700_000_000_000L
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = now,
            cachedInfo = info("0.1.7"),
            cachedAtMillis = now - UpdateDefaults.CACHE_REVALIDATE_INTERVAL_MS - 1,
        )
        val action = UpdatePolicy.decideOnLaunch(now, store, "0.1.6", config)
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_staleIneligibleCacheTriggersCheck() {
        val now = 1_700_000_000_000L
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = now,
            cachedInfo = info("0.1.7", rolloutPercent = 0),
            cachedAtMillis = now - UpdateDefaults.CACHE_REVALIDATE_INTERVAL_MS - 1,
        )
        val action = UpdatePolicy.decideOnLaunch(now, store, "0.1.6", config)
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_legacyCacheWithoutMetaTriggersCheck() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7"),
            cachedMetaKnown = false,
            cachedAtMillis = 500_000L,
        )
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config)
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_doesNotShowDismissedCachedVersion() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 500_000L,
            cachedInfo = info("0.1.7"),
            cachedAtMillis = 500_000L,
            dismissedVersion = "0.1.7",
        )
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6", config),
        )
    }
}
