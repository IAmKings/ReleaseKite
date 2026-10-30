package io.github.releasekite.sample

import io.github.releasekite.core.ReleaseKiteConfig

/**
 * 示例应用的演示配置。
 *
 * 与真实接入的差异：
 * 1. **仓库**指向公开的 ZhiBox 仓库，用来展示真实的 GitHub Release 查询结果；ZhiBox 的
 *    APK 资产名同样以 `-release-signed.apk` 结尾，因此可以演示「检查 → 弹窗 → 下载 → 校验」。
 * 2. **元数据关闭**（`metadataAssetSuffix = null`）：示例的 `versionName = 0.0.1`，而
 *    ZhiBox 仓库的 `-update.json` 里 `minSupportedVersion = 0.1.0`。若开启元数据，示例首次
 *    启动就会命中**强制更新且不可取消**，演示被锁死在弹窗上。关闭元数据后客户端按
 *    「100% 灰度 / 非强制」处理，示例可以自由点开各状态。
 *    真实接入请保留默认 `-update.json` 并参考 docs/RELEASE_PIPELINE.md 配置灰度/强制。
 *
 * 注意：下载到真实 ZhiBox APK 后，安装前的包名/签名校验必然失败（示例应用不是
 * `com.zhibox.memory`，签名也不同）。这是**预期的安全拦截**，不是 bug。
 */
object DemoSettings {

    /** 演示仓库（默认 IAmKings/ZhiBox，可通过 Gradle 属性 releasekite.demoRepo 覆盖）。 */
    val repository: String = BuildConfig.DEMO_REPO

    /** APK 资产名匹配规则：与示例仓库的 Release 资产命名约定保持一致。 */
    val apkAssetPattern: Regex = Regex(""".*-release-signed\.apk$""")

    /** 演示不配置镜像；真实接入可参考 docs/RELEASE_PIPELINE.md 的镜像说明。 */
    val downloadMirrors: List<String> = emptyList()

    /**
     * 演示关闭元数据（灰度/强制），避免 `0.0.1 < minSupportedVersion` 导致首启被强制锁死。
     * 真实接入应使用默认值 `"-update.json"`。
     */
    val metadataAssetSuffix: String? = null

    /** 供 Application 初始化与页面展示共用的配置。 */
    fun config(): ReleaseKiteConfig = ReleaseKiteConfig(
        repository = repository,
        apkAssetPattern = apkAssetPattern,
        downloadMirrors = downloadMirrors,
        metadataAssetSuffix = metadataAssetSuffix,
    )
}
