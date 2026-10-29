package io.github.releasekite.core

/**
 * 更新检查的持久化状态：节流时间戳、缓存的最新版本、已取消（dismissed）版本、线路健康、
 * 待安装 APK 记录。
 *
 * 移植自 ZhiBox `UpdatePreferences.kt` 的 `UpdateStore` 接口；持久化实现（SharedPreferences）
 * 留在 Android 模块，core 只保留契约与测试/预览用的 [InMemoryUpdateStore]。
 *
 * 跨进程恢复相关字段（`downloaded*` / `installerLaunchedVersion` / `consumedWorkId`）
 * 是 [UpdateManager] 成功去重与安装恢复的关键（回归提交 6110dfc）。
 */
interface UpdateStore {
    var lastSuccessAtMillis: Long
    var lastAttemptAtMillis: Long
    var dismissedVersion: String?

    /** 自动检查开关（默认 true；关闭后仅手动检查）。 */
    var autoCheckEnabled: Boolean

    /** 更新通道（默认 STABLE；BETA 接收 GitHub prerelease）。 */
    var channel: UpdateChannel

    /** 本地安装标识（随机 UUID；仅用于灰度分桶，不上报）。 */
    var installId: String

    fun cachedInfo(): UpdateInfo?
    fun saveCached(info: UpdateInfo)
    fun clearCache()

    /** 缓存是否包含灰度/强制元数据（旧缓存为 false，不能用于灰度/强制判断）。 */
    val cachedMetaKnown: Boolean

    /** 缓存写入时间（0=旧缓存/未知）；用于强制缓存的过期校验。 */
    val cachedAtMillis: Long

    /** 下载线路健康统计（本地，不上报）。 */
    fun routeHealth(): Map<String, RouteHealth>
    fun updateRouteHealth(health: Map<String, RouteHealth>)

    /** 后台下载完成、等待安装的 APK（跨进程恢复用）。 */
    var downloadedVersion: String?
    var downloadedApkPath: String?

    /** 已经拉起过系统安装器的版本（防止用户取消安装后重启又自动拉起）。 */
    var installerLaunchedVersion: String?

    /** 已经消费过的下载任务 id（防止重启后重复消费 SUCCEEDED，回归提交 6110dfc）。 */
    var consumedWorkId: String?
}

/**
 * 内存实现：测试 / Compose preview / 宿主自定义存储的起点。
 *
 * 全部字段可在构造时初始化；[saveCached] 会同时刷新 [cachedAtMillis] 并标记
 * [cachedMetaKnown] = true（等价 SharedPreferences 实现写入 `cached_meta_schema=2`）。
 */
class InMemoryUpdateStore(
    lastSuccessAtMillis: Long = 0L,
    lastAttemptAtMillis: Long = 0L,
    dismissedVersion: String? = null,
    autoCheckEnabled: Boolean = true,
    channel: UpdateChannel = UpdateChannel.STABLE,
    installId: String = DEFAULT_INSTALL_ID,
    cachedInfo: UpdateInfo? = null,
    cachedMetaKnown: Boolean = cachedInfo != null,
    cachedAtMillis: Long = 0L,
    routeHealth: Map<String, RouteHealth> = emptyMap(),
    downloadedVersion: String? = null,
    downloadedApkPath: String? = null,
    installerLaunchedVersion: String? = null,
    consumedWorkId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : UpdateStore {

    override var lastSuccessAtMillis: Long = lastSuccessAtMillis
    override var lastAttemptAtMillis: Long = lastAttemptAtMillis
    override var dismissedVersion: String? = dismissedVersion
    override var autoCheckEnabled: Boolean = autoCheckEnabled
    override var channel: UpdateChannel = channel
    override var installId: String = installId

    private var cached: UpdateInfo? = cachedInfo

    override var cachedMetaKnown: Boolean = cachedMetaKnown
        private set

    override var cachedAtMillis: Long = cachedAtMillis
        private set

    private var routes: Map<String, RouteHealth> = routeHealth

    override var downloadedVersion: String? = downloadedVersion
    override var downloadedApkPath: String? = downloadedApkPath
    override var installerLaunchedVersion: String? = installerLaunchedVersion
    override var consumedWorkId: String? = consumedWorkId

    override fun cachedInfo(): UpdateInfo? = cached

    override fun saveCached(info: UpdateInfo) {
        cached = info
        cachedMetaKnown = true
        cachedAtMillis = clock()
    }

    override fun clearCache() {
        cached = null
        // 与 SharedPreferences 实现一致：清缓存同时移除元数据标记与写入时间戳。
        cachedMetaKnown = false
        cachedAtMillis = 0L
    }

    override fun routeHealth(): Map<String, RouteHealth> = routes

    override fun updateRouteHealth(health: Map<String, RouteHealth>) {
        routes = health.toMap()
    }

    private companion object {
        const val DEFAULT_INSTALL_ID = "in-memory-install"
    }
}
