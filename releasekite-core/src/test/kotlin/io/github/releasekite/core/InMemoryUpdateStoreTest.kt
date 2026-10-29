package io.github.releasekite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InMemoryUpdateStore] 新增测试：全部字段可初始化、缓存写入时间/元数据标记、
 * 线路健康、跨进程恢复字段（成功去重依赖）。
 */
class InMemoryUpdateStoreTest {

    private fun info(version: String = "0.1.7") = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = "t",
        notes = "",
        publishedAt = null,
        htmlUrl = "",
        apkUrl = "https://github.com/a/b/releases/download/v$version/x.apk",
        apkSizeBytes = 1,
        sha256 = "a".repeat(64),
        sha256Url = null,
    )

    @Test
    fun defaultsMatchInterfaceContract() {
        val store = InMemoryUpdateStore()
        assertEquals(0L, store.lastSuccessAtMillis)
        assertEquals(0L, store.lastAttemptAtMillis)
        assertNull(store.dismissedVersion)
        assertTrue(store.autoCheckEnabled)
        assertEquals(UpdateChannel.STABLE, store.channel)
        assertTrue(store.installId.isNotBlank())
        assertNull(store.cachedInfo())
        assertFalse(store.cachedMetaKnown)
        assertEquals(0L, store.cachedAtMillis)
        assertTrue(store.routeHealth().isEmpty())
        assertNull(store.downloadedVersion)
        assertNull(store.downloadedApkPath)
        assertNull(store.installerLaunchedVersion)
        assertNull(store.consumedWorkId)
    }

    @Test
    fun allFieldsAreInitializable() {
        val store = InMemoryUpdateStore(
            lastSuccessAtMillis = 10L,
            lastAttemptAtMillis = 20L,
            dismissedVersion = "0.1.5",
            autoCheckEnabled = false,
            channel = UpdateChannel.BETA,
            installId = "install-1",
            cachedInfo = info(),
            cachedAtMillis = 30L,
            routeHealth = mapOf("direct" to RouteHealth(id = "direct", consecutiveFailures = 1)),
            downloadedVersion = "0.1.7",
            downloadedApkPath = "/tmp/x.apk",
            installerLaunchedVersion = "0.1.7",
            consumedWorkId = "work-1",
        )
        assertEquals(10L, store.lastSuccessAtMillis)
        assertEquals(20L, store.lastAttemptAtMillis)
        assertEquals("0.1.5", store.dismissedVersion)
        assertFalse(store.autoCheckEnabled)
        assertEquals(UpdateChannel.BETA, store.channel)
        assertEquals("install-1", store.installId)
        assertEquals("0.1.7", store.cachedInfo()?.versionName)
        assertTrue(store.cachedMetaKnown)
        assertEquals(30L, store.cachedAtMillis)
        assertEquals(1, store.routeHealth()["direct"]?.consecutiveFailures)
        assertEquals("0.1.7", store.downloadedVersion)
        assertEquals("/tmp/x.apk", store.downloadedApkPath)
        assertEquals("0.1.7", store.installerLaunchedVersion)
        assertEquals("work-1", store.consumedWorkId)
    }

    @Test
    fun saveCachedRefreshesTimestampAndMetaFlag() {
        val store = InMemoryUpdateStore(clock = { 42_000L })
        assertFalse(store.cachedMetaKnown)
        store.saveCached(info())
        assertEquals("0.1.7", store.cachedInfo()?.versionName)
        assertTrue("saveCached 等价写入 CACHE_META_SCHEMA=2", store.cachedMetaKnown)
        assertEquals(42_000L, store.cachedAtMillis)
    }

    @Test
    fun clearCacheRemovesCachedInfoAndMetaMarker() {
        val store = InMemoryUpdateStore(clock = { 42_000L })
        store.saveCached(info())
        store.clearCache()
        assertNull(store.cachedInfo())
        // 与 SharedPreferences 实现一致：清缓存同时移除 meta schema/时间戳。
        assertFalse(store.cachedMetaKnown)
        assertEquals(0L, store.cachedAtMillis)
    }

    @Test
    fun mutablePropertiesRoundTrip() {
        val store = InMemoryUpdateStore()
        store.lastSuccessAtMillis = 1L
        store.lastAttemptAtMillis = 2L
        store.dismissedVersion = "1.0.0"
        store.autoCheckEnabled = false
        store.channel = UpdateChannel.BETA
        store.installId = "id"
        store.downloadedVersion = "1.0.1"
        store.downloadedApkPath = "/tmp/a.apk"
        store.installerLaunchedVersion = "1.0.1"
        store.consumedWorkId = "w"

        assertEquals(1L, store.lastSuccessAtMillis)
        assertEquals(2L, store.lastAttemptAtMillis)
        assertEquals("1.0.0", store.dismissedVersion)
        assertFalse(store.autoCheckEnabled)
        assertEquals(UpdateChannel.BETA, store.channel)
        assertEquals("id", store.installId)
        assertEquals("1.0.1", store.downloadedVersion)
        assertEquals("/tmp/a.apk", store.downloadedApkPath)
        assertEquals("1.0.1", store.installerLaunchedVersion)
        assertEquals("w", store.consumedWorkId)
    }

    @Test
    fun routeHealthIsReplacedNotMerged() {
        val store = InMemoryUpdateStore()
        val first = mapOf("direct" to RouteHealth(id = "direct", consecutiveFailures = 1))
        store.updateRouteHealth(first)
        assertEquals(first, store.routeHealth())

        val second = mapOf("m.example" to RouteHealth(id = "m.example", lastLatencyMs = 5))
        store.updateRouteHealth(second)
        assertEquals(second, store.routeHealth())
        assertTrue(store.routeHealth()["direct"] == null)
    }
}
