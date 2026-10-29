package io.github.releasekite.sample

import io.github.releasekite.core.ReleaseKiteConfig

/**
 * 示例应用的演示配置。
 *
 * 与真实接入唯一的差别是「仓库」：这里默认指向公开的 ZhiBox 仓库，用来展示真实的
 * GitHub Release 查询结果；ZhiBox 的 APK 资产名同样以 `-release-signed.apk` 结尾，
 * 因此可以演示「检查 → 弹窗 → 下载 → 校验」全链路。
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

    /** 供 Application 初始化与页面展示共用的配置。 */
    fun config(): ReleaseKiteConfig = ReleaseKiteConfig(
        repository = repository,
        apkAssetPattern = apkAssetPattern,
        downloadMirrors = downloadMirrors,
    )
}
