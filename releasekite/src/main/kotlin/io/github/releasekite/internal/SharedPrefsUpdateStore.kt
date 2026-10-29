package io.github.releasekite.internal

import android.content.Context
import io.github.releasekite.core.RouteHealth
import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * SharedPreferences 实现的 [UpdateStore]（契约 §2）。
 *
 * 独立 prefs 文件 `releasekite_update`，key 与 ZhiBox `SharedPrefsUpdateStore` **逐字一致**，
 * 便于把存量宿主（ZhiBox v0.1.15+）的用户状态无痛迁移/对照：
 * - 时间戳/取消版本/自动检查/通道/installId/线路健康/下载落盘记录；
 * - 缓存字段额外持久化 `cached_meta_schema`（[CACHE_META_SCHEMA] = 2）、`cached_at`、
 *   `cached_rollout_percent`、`cached_min_supported`、`cached_channel`
 *   （回归 5f598bb：v0.1.15 之前只存旧字段，重启后 forced/灰度丢失）。
 */
class SharedPrefsUpdateStore(context: Context) : UpdateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastSuccessAtMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()
        }

    override var lastAttemptAtMillis: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_ATTEMPT, value).apply()
        }

    override var dismissedVersion: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) {
            prefs.edit().putString(KEY_DISMISSED, value).apply()
        }

    override var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
        }

    override var channel: UpdateChannel
        get() = when (prefs.getString(KEY_CHANNEL, null)) {
            UpdateChannel.BETA.name -> UpdateChannel.BETA
            else -> UpdateChannel.STABLE
        }
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    override var installId: String
        get() {
            val existing = prefs.getString(KEY_INSTALL_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
            return generated
        }
        set(value) {
            prefs.edit().putString(KEY_INSTALL_ID, value).apply()
        }

    override fun cachedInfo(): UpdateInfo? {
        val version = prefs.getString(KEY_C_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        return UpdateInfo(
            versionName = version,
            tagName = prefs.getString(KEY_C_TAG, null).orEmpty(),
            title = prefs.getString(KEY_C_TITLE, null).orEmpty(),
            notes = prefs.getString(KEY_C_NOTES, null).orEmpty(),
            publishedAt = prefs.getString(KEY_C_PUBLISHED, null),
            htmlUrl = prefs.getString(KEY_C_HTML, null).orEmpty(),
            apkUrl = prefs.getString(KEY_C_APK_URL, null).orEmpty(),
            apkSizeBytes = prefs.getLong(KEY_C_APK_SIZE, 0L),
            sha256 = prefs.getString(KEY_C_SHA, null),
            sha256Url = prefs.getString(KEY_C_SHA_URL, null),
            channel = when (prefs.getString(KEY_C_CHANNEL, null)) {
                UpdateChannel.BETA.name -> UpdateChannel.BETA
                else -> UpdateChannel.STABLE
            },
            rolloutPercent = prefs.getInt(KEY_C_ROLLOUT, 100).coerceIn(0, 100),
            minSupportedVersion = prefs.getString(KEY_C_MIN_SUPPORTED, null),
        )
    }

    override fun saveCached(info: UpdateInfo) {
        prefs.edit()
            .putString(KEY_C_VERSION, info.versionName)
            .putString(KEY_C_TAG, info.tagName)
            .putString(KEY_C_TITLE, info.title)
            .putString(KEY_C_NOTES, info.notes)
            .putString(KEY_C_PUBLISHED, info.publishedAt)
            .putString(KEY_C_HTML, info.htmlUrl)
            .putString(KEY_C_APK_URL, info.apkUrl)
            .putLong(KEY_C_APK_SIZE, info.apkSizeBytes)
            .putString(KEY_C_SHA, info.sha256)
            .putString(KEY_C_SHA_URL, info.sha256Url)
            .putInt(KEY_C_ROLLOUT, info.rolloutPercent.coerceIn(0, 100))
            .putString(KEY_C_MIN_SUPPORTED, info.minSupportedVersion)
            .putString(KEY_C_CHANNEL, info.channel.name)
            .putInt(KEY_C_META_SCHEMA, CACHE_META_SCHEMA)
            .putLong(KEY_C_AT, System.currentTimeMillis())
            .apply()
    }

    override val cachedMetaKnown: Boolean
        get() = prefs.getInt(KEY_C_META_SCHEMA, 0) >= CACHE_META_SCHEMA

    override val cachedAtMillis: Long
        get() = prefs.getLong(KEY_C_AT, 0L)

    override var downloadedVersion: String?
        get() = prefs.getString(KEY_DOWNLOADED_VERSION, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_VERSION, value).apply()
        }

    override var downloadedApkPath: String?
        get() = prefs.getString(KEY_DOWNLOADED_APK_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_APK_PATH, value).apply()
        }

    override var installerLaunchedVersion: String?
        get() = prefs.getString(KEY_INSTALLER_LAUNCHED_VERSION, null)
        set(value) {
            prefs.edit().putString(KEY_INSTALLER_LAUNCHED_VERSION, value).apply()
        }

    override var consumedWorkId: String?
        get() = prefs.getString(KEY_CONSUMED_WORK_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CONSUMED_WORK_ID, value).apply()
        }

    override fun routeHealth(): Map<String, RouteHealth> {
        val raw = prefs.getString(KEY_ROUTE_HEALTH, null) ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (id, element) ->
                val obj = element.jsonObject
                RouteHealth(
                    id = id,
                    lastSuccessAt = obj["lastSuccessAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    lastFailureAt = obj["lastFailureAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    consecutiveFailures = obj["consecutiveFailures"]?.jsonPrimitive?.intOrNull ?: 0,
                    lastLatencyMs = obj["lastLatencyMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cooldownUntil = obj["cooldownUntil"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        }.getOrDefault(emptyMap())
    }

    override fun updateRouteHealth(health: Map<String, RouteHealth>) {
        val obj = buildJsonObject {
            health.forEach { (id, route) ->
                put(
                    id,
                    buildJsonObject {
                        put("lastSuccessAt", route.lastSuccessAt)
                        put("lastFailureAt", route.lastFailureAt)
                        put("consecutiveFailures", route.consecutiveFailures)
                        put("lastLatencyMs", route.lastLatencyMs)
                        put("cooldownUntil", route.cooldownUntil)
                    },
                )
            }
        }
        prefs.edit().putString(KEY_ROUTE_HEALTH, obj.toString()).apply()
    }

    override fun clearCache() {
        prefs.edit()
            .remove(KEY_C_VERSION)
            .remove(KEY_C_TAG)
            .remove(KEY_C_TITLE)
            .remove(KEY_C_NOTES)
            .remove(KEY_C_PUBLISHED)
            .remove(KEY_C_HTML)
            .remove(KEY_C_APK_URL)
            .remove(KEY_C_APK_SIZE)
            .remove(KEY_C_SHA)
            .remove(KEY_C_SHA_URL)
            .remove(KEY_C_ROLLOUT)
            .remove(KEY_C_MIN_SUPPORTED)
            .remove(KEY_C_CHANNEL)
            .remove(KEY_C_META_SCHEMA)
            .remove(KEY_C_AT)
            .apply()
    }

    internal companion object {
        internal val json = Json

        /** 契约 §2：独立 prefs 文件名（与宿主其它偏好隔离）。 */
        const val PREFS_NAME = "releasekite_update"

        /** 缓存元数据 schema：2 = v0.1.15+（含 rollout/minSupported/channel/cachedAt）。 */
        const val CACHE_META_SCHEMA = 2

        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_AUTO_CHECK = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_ROUTE_HEALTH = "route_health"
        const val KEY_DOWNLOADED_VERSION = "downloaded_version"
        const val KEY_DOWNLOADED_APK_PATH = "downloaded_apk_path"
        const val KEY_INSTALLER_LAUNCHED_VERSION = "installer_launched_version"
        const val KEY_CONSUMED_WORK_ID = "consumed_work_id"
        const val KEY_C_VERSION = "cached_version"
        const val KEY_C_TAG = "cached_tag"
        const val KEY_C_TITLE = "cached_title"
        const val KEY_C_NOTES = "cached_notes"
        const val KEY_C_PUBLISHED = "cached_published"
        const val KEY_C_HTML = "cached_html"
        const val KEY_C_APK_URL = "cached_apk_url"
        const val KEY_C_APK_SIZE = "cached_apk_size"
        const val KEY_C_SHA = "cached_sha"
        const val KEY_C_SHA_URL = "cached_sha_url"
        const val KEY_C_ROLLOUT = "cached_rollout_percent"
        const val KEY_C_MIN_SUPPORTED = "cached_min_supported"
        const val KEY_C_CHANNEL = "cached_channel"
        const val KEY_C_META_SCHEMA = "cached_meta_schema"
        const val KEY_C_AT = "cached_at"
    }
}
