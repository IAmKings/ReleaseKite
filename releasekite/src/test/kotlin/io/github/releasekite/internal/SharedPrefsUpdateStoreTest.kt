package io.github.releasekite.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdateRollout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SharedPrefsUpdateStore] 的真实序列化回归（迁移自 ZhiBox `UpdateStorePersistenceTest`）。
 *
 * 核心不变量：v0.1.15 之前缓存只保存旧字段，导致重启后 forced/灰度状态丢失。
 * ReleaseKite 的缓存额外持久化 rollout/minSupported/channel 与 `cached_meta_schema=2`，
 * 旧缓存必须被判定为「元数据未知」（[SharedPrefsUpdateStore.cachedMetaKnown] = false）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsUpdateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val prefs = context.getSharedPreferences(
        SharedPrefsUpdateStore.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val targetPrefs = context.getSharedPreferences(
        SharedPrefsUpdateStore.TARGET_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    @Before
    fun resetPrefs() {
        prefs.edit().clear().commit()
        targetPrefs.edit().clear().commit()
    }

    @Test
    fun saveCachedRoundTripsRolloutMinSupportedAndChannel() {
        val store = SharedPrefsUpdateStore(context)
        val info = UpdateInfo(
            versionName = "0.1.15-force.1",
            tagName = "v0.1.15-force.1",
            title = "t",
            notes = "n",
            publishedAt = null,
            htmlUrl = "https://github.com/releasekite/ReleaseKite/releases",
            apkUrl = "https://github.com/releasekite/ReleaseKite/releases/download/v0.1.15-force.1/app-release-signed.apk",
            apkSizeBytes = 1L,
            sha256 = "a".repeat(64),
            sha256Url = null,
            channel = UpdateChannel.BETA,
            rolloutPercent = 25,
            minSupportedVersion = "0.1.15",
        )
        store.saveCached(info)

        assertTrue("新缓存应标记 meta 已知", store.cachedMetaKnown)
        assertTrue("缓存时间应被记录", store.cachedAtMillis > 0L)
        val cached = store.cachedInfo()!!
        assertEquals("0.1.15-force.1", cached.versionName)
        assertEquals(25, cached.rolloutPercent)
        assertEquals("0.1.15", cached.minSupportedVersion)
        assertEquals(UpdateChannel.BETA, cached.channel)
        // forced 由 minSupportedVersion 推导：0.1.15 > 0.1.14。
        assertTrue(UpdateRollout.isForced(cached, "0.1.14"))
    }

    @Test
    fun legacyCacheWithoutMetaSchemaIsMarkedUnknown() {
        // 模拟旧版本（v0.1.15 之前）写入的缓存：只有旧字段，没有 meta schema/时间戳。
        prefs.edit()
            .putString("cached_version", "0.1.7")
            .putString("cached_tag", "v0.1.7")
            .putString("cached_title", "t")
            .putString("cached_notes", "n")
            .putString("cached_html", "https://github.com/releasekite/ReleaseKite/releases")
            .putString("cached_apk_url", "https://github.com/releasekite/ReleaseKite/releases/download/v0.1.7/app.apk")
            .putLong("cached_apk_size", 1L)
            .putString("cached_sha", "b".repeat(64))
            .commit()

        val store = SharedPrefsUpdateStore(context)
        assertFalse(store.cachedMetaKnown)
        assertEquals(0L, store.cachedAtMillis)
        val cached = store.cachedInfo()!!
        assertEquals(100, cached.rolloutPercent)
        assertNull(cached.minSupportedVersion)
        assertEquals(UpdateChannel.STABLE, cached.channel)
        assertFalse(UpdateRollout.isForced(cached, "0.1.6"))
    }

    @Test
    fun metaSchemaAndTimestampArePersistedThenCleared() {
        val store = SharedPrefsUpdateStore(context)
        store.saveCached(
            UpdateInfo(
                versionName = "1.0.0",
                tagName = "v1.0.0",
                title = "t",
                notes = "n",
                publishedAt = null,
                htmlUrl = "",
                apkUrl = "",
                apkSizeBytes = 0L,
                sha256 = null,
                sha256Url = null,
                rolloutPercent = 40,
                minSupportedVersion = "0.9.0",
            ),
        )

        // 契约要求：meta schema=2 与 cached_at 落盘（供强制缓存过期校验）。
        assertEquals(2, prefs.getInt("cached_meta_schema", 0))
        assertTrue(prefs.getLong("cached_at", 0L) > 0L)
        assertEquals(40, prefs.getInt("cached_rollout_percent", -1))
        assertEquals("0.9.0", prefs.getString("cached_min_supported", null))

        store.clearCache()
        assertNull(store.cachedInfo())
        assertFalse(store.cachedMetaKnown)
        assertEquals(0L, store.cachedAtMillis)
        assertEquals(0, prefs.getInt("cached_meta_schema", 0))
    }

    @Test
    fun cachedInfoRoundTripsMetaUrl() {
        val store = SharedPrefsUpdateStore(context)
        val info = sampleInfo(versionName = "1.1.0", metaUrl = "https://github.com/o/r/releases/download/v1.1.0/update.json")

        store.saveCached(info)

        // R1 fix：metaUrl 必须持久化，否则冷启动强制校验拿不到 update.json 地址。
        assertEquals(info.metaUrl, store.cachedInfo()!!.metaUrl)
    }

    @Test
    fun downloadTargetIsIsolatedFromMainCacheRefresh() {
        val targetStore = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
        val bound = sampleInfo(versionName = "2.0.0")
        targetStore.bindDownloadTarget("work-1", bound)

        // 主缓存刷新成更新版本，不能污染进行中任务的 target（独立 prefs 文件）。
        SharedPrefsUpdateStore(context).saveCached(sampleInfo(versionName = "3.0.0"))

        // 重新构造实例（模拟进程重启）后仍能按 workId 恢复。
        val restored = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
        assertEquals(bound, restored.downloadTarget("work-1"))
        assertNull("workId 不匹配时不能返回当前缓存（旧任务归属判定）", restored.downloadTarget("work-2"))
    }

    @Test
    fun downloadTargetPersistsFullMetadataForForcedInstall() {
        val targetStore = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
        val bound = sampleInfo(
            versionName = "2.2.0",
            metaUrl = "https://github.com/o/r/releases/download/v2.2.0/update.json",
        ).copy(rolloutPercent = 25, minSupportedVersion = "2.0.0", channel = UpdateChannel.BETA)

        targetStore.bindDownloadTarget("work-full", bound)

        val restored = SharedPrefsUpdateStore(context, SharedPrefsUpdateStore.TARGET_PREFS_NAME)
            .downloadTarget("work-full")
        assertEquals(bound, restored)
        assertTrue("目标记录也必须保留强制/灰度元数据", UpdateRollout.isForced(restored!!, "1.9.0"))
    }

    private fun sampleInfo(
        versionName: String,
        metaUrl: String? = null,
    ) = UpdateInfo(
        versionName = versionName,
        tagName = "v$versionName",
        title = "t",
        notes = "n",
        publishedAt = null,
        htmlUrl = "https://github.com/releasekite/ReleaseKite/releases",
        apkUrl = "https://github.com/releasekite/ReleaseKite/releases/download/v$versionName/app.apk",
        apkSizeBytes = 1L,
        sha256 = "a".repeat(64),
        sha256Url = null,
        metaUrl = metaUrl,
    )
}
