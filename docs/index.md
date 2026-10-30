<div class="rk-hero" markdown>
<p align="center">
  <img src="brand/releasekite-mark.svg" alt="ReleaseKite" class="rk-hero-mark">
</p>
<h1 align="center">ReleaseKite</h1>
<p align="center" class="rk-tagline">
  以 GitHub Release 为版本源的 Android 应用内更新库<br>
  Compose 更新 UI · 安全校验 · 后台下载 · Beta / 灰度 / 强制更新
</p>
<p align="center" class="rk-actions">
  <a class="md-button md-button--primary" href="INTEGRATION.html">5 分钟接入</a>
  <a class="md-button" href="API_CONTRACT.html">API 参考</a>
  <a class="md-button" href="https://github.com/IAmKings/ReleaseKite">GitHub</a>
</p>
<p align="center" class="rk-meta">
  当前版本 v0.1.0 · MIT · minSdk 24 · JDK 21
</p>
</div>

## 为什么用 ReleaseKite

<div class="grid cards" markdown>

-   **零服务端**

    ---

    版本源就是公开 GitHub Release；不需要自建更新 API、后台或数据库。

-   **安全链闭环**

    ---

    HTTPS + host 白名单 → SHA-256 → 包名 / versionCode / 签名校验 → FileProvider →
    系统安装器；不做静默安装。

-   **后台下载可恢复**

    ---

    WorkManager 唯一任务、通知、前台服务降级、跨进程 `.part` 续传；Wi-Fi 中断/force-stop
    后可继续下载。

-   **策略完整**

    ---

    Beta 通道、灰度分桶、强制更新、镜像/线路回退、缓存与强制逃生阈值。

-   **Compose 原生**

    ---

    `ReleaseKiteHost` + 更新弹窗 + 设置行/设置区；也可只用 Android 适配层或纯 JVM core 自绘。

-   **可裁剪的模块**

    ---

    `releasekite-core`（纯 JVM）、`releasekite`（Android）、`releasekite-compose`（UI）按需引入。

</div>

## 5 分钟接入

```kotlin
// settings.gradle.kts
repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

// app/build.gradle.kts
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")
```

```kotlin
// Application：必须早于任何 manager / Host
ReleaseKite.initialize(
    context = this,
    config = ReleaseKiteConfig(
        repository = "your-name/your-app",
        apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
        metadataAssetSuffix = "-update.json", // 灰度/强制需要
    ),
)

// Compose 根布局
Box(Modifier.fillMaxSize()) {
    AppContent()
    ReleaseKiteHost() // 弹窗 + 生命周期 + 通知权限
}
```

完整初始化、权限、FileProvider、R8、通知点击续装见 [接入指南](INTEGRATION.md)。

## 模块

| 模块 | 内容 | 适用 |
|---|---|---|
| `releasekite-compose` | Material3 更新弹窗、设置行/设置区、Host | 推荐；api 带入另外两层 |
| `releasekite` | WorkManager 下载、安装、通知、存储 | 只要 Android 适配，UI 自绘 |
| `releasekite-core` | 版本/策略/灰度/状态机/下载器 | 纯 JVM 或自建平台适配 |

## 发布侧

宿主 App 产出这样的 GitHub Release 即可：

```text
myapp-1.2.3-<sha7>-release-signed.apk
myapp-1.2.3-<sha7>-release-signed.apk.sha256
myapp-1.2.3-<sha7>-update.json        # 可选；灰度/强制需要
```

tag `v1.2.3`、`versionCode` 递增、同一签名证书是硬约束。Actions 模板、Beta/灰度/强制与
发布后自检见 [发布与资产](RELEASE_PIPELINE.md)。

## 文档与源码

- [接入指南](INTEGRATION.md)
- [API 参考](API_CONTRACT.md)
- [架构](ARCHITECTURE.md) / [安全](SECURITY.md) / [测试与验收](TESTING.md)
- [GitHub 仓库](https://github.com/IAmKings/ReleaseKite)
- [JitPack](https://jitpack.io/#IAmKings/ReleaseKite)

> ReleaseKite 源自 [ZhiBox](https://github.com/IAmKings/ZhiBox) 的更新服务（MIT），
> 行为基线与迁移对照见 [从 ZhiBox 迁移](MIGRATION_ZHIBOX.md)。
