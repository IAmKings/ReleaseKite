<p align="center">
  <img src="docs/brand/releasekite-mark-512.png" alt="ReleaseKite" width="96">
</p>

<h1 align="center">ReleaseKite</h1>

<p align="center">
  以 GitHub Release 为版本源的 Android 应用内更新库<br>
  检查更新 · 更新弹窗 · 下载 · 安全校验 · 系统安装
</p>

<p align="center">
  <a href="https://jitpack.io/#IAmKings/ReleaseKite"><img src="https://jitpack.io/v/IAmKings/ReleaseKite.svg" alt="JitPack"></a>
  <a href="https://iamkings.github.io/ReleaseKite/">在线文档</a> ·
  <a href="#5-分钟接入">快速开始</a> ·
  <a href="https://github.com/IAmKings/ReleaseKite/releases">Releases</a>
</p>

ReleaseKite 把「检查更新 → 更新弹窗 → 下载 → 校验 → 安装」这一整套流程封装成可复用库。
版本源直接使用公开 GitHub Release，不需要自建更新服务：宿主 App 只需要按约定发布 APK
和可选的更新元数据，再接入少量初始化代码。

它源自 [ZhiBox](https://github.com/IAmKings/ZhiBox) 的更新模块（MIT），保留了其状态机、
安全校验、后台下载、Beta/灰度/强制更新等行为，并拆成纯 JVM、Android、Compose 三层，
可以按需使用。

## 为什么用它

- **零后端**：公开 GitHub Release 就是版本源，没有更新 API、数据库或额外运维。
- **安全闭环**：HTTPS + host 白名单 → SHA-256 → 包名/versionCode/签名校验 → 系统安装器，
  不做静默安装。
- **后台与弱网**：WorkManager 唯一任务、通知、跨进程 `.part` 续传、镜像/线路回退。
- **策略完整**：Beta 通道、灰度分桶、强制更新、缓存与失败逃生阈值。
- **Compose 原生**：自带 `ReleaseKiteHost`、更新弹窗、设置行/设置区；也可以只使用
  Android 适配层或纯 JVM core 自绘 UI。

## 5 分钟接入

### 1. 添加依赖

```kotlin
// settings.gradle.kts
repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

// app/build.gradle.kts
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")
// 宿主自己的 Compose BOM/material3/activity-compose 照常声明
```

`releasekite-compose` 会通过 `api` 带入 `releasekite` 和 `releasekite-core`。如果不需要
Compose UI，只依赖 `releasekite` 即可；纯 JVM 场景使用 `releasekite-core`。

### 2. 初始化

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = ReleaseKiteConfig(
                repository = "your-name/your-app",
                apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
                metadataAssetSuffix = "-update.json", // 启用灰度/强制；不需要可置 null
                // downloadMirrors = listOf("https://ghproxy.net/"), // 国内网络可选
            ),
        )
    }
}
```

### 3. 挂载更新 UI

```kotlin
setContent {
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            AppContent()
            ReleaseKiteHost() // 全局弹窗 + 生命周期 + 通知权限
        }
    }
}

// 设置页
val manager = ReleaseKite.manager
ReleaseKiteUpdateRow(manager = manager)        // 一行「检查更新」
ReleaseKiteSettingsSection(manager = manager)  // 自动检查 / Beta / 线路状态
```

完整步骤、通知点击续装、FileProvider、R8 和常见问题见
[接入指南](docs/INTEGRATION.md)。可运行示例在 [`sample/`](sample/)。

## 模块

| 模块 | 内容 | 适用场景 |
|---|---|---|
| `releasekite-compose` | Material3 更新弹窗、设置区、Host | 推荐；一步接入 UI |
| `releasekite` | WorkManager 下载、安装、通知、存储 | 自绘 UI，只要 Android 适配 |
| `releasekite-core` | 版本/策略/灰度/状态机/下载器 | 纯 JVM 或自建平台适配 |

## 发布一个更新

宿主 App 的 GitHub Release 至少包含一个 APK；推荐同时提供 `.sha256`，需要灰度/强制时再加
`-update.json`：

```text
myapp-1.2.3-<sha7>-release-signed.apk
myapp-1.2.3-<sha7>-release-signed.apk.sha256
myapp-1.2.3-<sha7>-update.json        # 可选：灰度比例 / 强制下限
```

约定：tag 为 `v<versionName>`，`versionCode` 递增，签名证书与已安装应用一致。
发布模板、Beta/灰度/强制操作和发布后自检见 [发布与资产](docs/RELEASE_PIPELINE.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [在线文档](https://iamkings.github.io/ReleaseKite/) | 文档站首页（GitHub Pages，支持搜索/深色模式） |
| [接入指南](docs/INTEGRATION.md) | 依赖、初始化、UI、权限、FileProvider、R8、FAQ |
| [API 参考](docs/API_CONTRACT.md) | 冻结 API 契约（实现与文档的唯一事实源） |
| [发布与资产](docs/RELEASE_PIPELINE.md) | 资产命名、Actions 模板、Beta/灰度/强制、镜像 |
| [架构](docs/ARCHITECTURE.md) / [安全](docs/SECURITY.md) | 模块边界、状态机、安全链、商店合规 |
| [测试与验收](docs/TESTING.md) | 自动化矩阵、门禁命令、真机验收清单 |
| [从 ZhiBox 迁移](docs/MIGRATION_ZHIBOX.md) | 行为对照与迁移不变量 |

## 构建与测试

需要 JDK 21 和 Android SDK（compileSdk 36）：

```bash
# 三个模块单测
./gradlew :releasekite-core:test :releasekite:testDebugUnitTest :releasekite-compose:testDebugUnitTest

# Debug 示例 + Release 门禁 + lint
./gradlew :sample:assembleDebug
./gradlew :releasekite:assembleRelease :releasekite-compose:assembleRelease :sample:assembleRelease
./gradlew :releasekite:lintDebug :releasekite-compose:lintDebug :sample:lintDebug \
          :releasekite:lintRelease :releasekite-compose:lintRelease :sample:lintRelease
```

完整门禁（含 `publishToMavenLocal` 和独立 consumer R8 冒烟）见
[`docs/TESTING.md`](docs/TESTING.md) 与 [CI workflow](.github/workflows/ci.yml)。

示例应用（`sample`）默认关闭灰度/强制元数据，避免 `versionName=0.0.1` 被真实仓库的
`minSupportedVersion` 锁死；真实接入请保留 `metadataAssetSuffix = "-update.json"`。

## AI / 自动化

仓库提供可安装的 AI skill，覆盖接入、API 使用、发布流程、维护与排障：

```bash
npx skills add IAmKings/ReleaseKite --skill releasekite -g
```

也可以直接阅读 [`skills/releasekite/SKILL.md`](skills/releasekite/SKILL.md)；安装与离线
fallback 见 [`skills/README.md`](skills/README.md)。

## English

ReleaseKite is a reusable in-app update library for Android/Jetpack Compose backed by
GitHub Releases (extracted from ZhiBox, MIT). It handles release checks, update UI,
background downloads, SHA-256/signature validation, installation and optional staged
rollout/forced updates without a backend.

```kotlin
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")
```

See the [documentation site](https://iamkings.github.io/ReleaseKite/),
[integration guide](docs/INTEGRATION.md) and [release pipeline](docs/RELEASE_PIPELINE.md).

## License

[MIT](LICENSE)。本项目源自 [ZhiBox](https://github.com/IAmKings/ZhiBox)（MIT），
版权与来源声明见 [`NOTICE`](NOTICE)。
