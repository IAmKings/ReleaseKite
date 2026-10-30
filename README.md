# ReleaseKite

[![Release](https://jitpack.io/v/IAmKings/ReleaseKite.svg)](https://jitpack.io/#IAmKings/ReleaseKite)
仓库: https://github.com/IAmKings/ReleaseKite · 文档: [`docs/`](docs/)

以 **GitHub Release 为版本源**的 Android 应用内更新库：检查更新、更新弹窗、下载进度、
SHA-256 + 签名校验、系统安装器闭环，支持后台下载/通知、Beta 通道、灰度分桶、强制更新与
下载线路/镜像回退。

- 纯 JVM 领域层（`releasekite-core`）+ Android 适配层（`releasekite`）+ Compose UI
  （`releasekite-compose`），可只用其中一层；
- 全部行为从 [ZhiBox](https://github.com/IAmKings/ZhiBox) v0.1.17 的更新服务抽取并泛化，
  保留其两轮第三方审查修复后的状态机与安全语义（详见 [`docs/MIGRATION_ZHIBOX.md`](docs/MIGRATION_ZHIBOX.md)）；
- 无服务端、无遥测、无 token：仓库 public 即可用。

## 特性

| 能力 | 说明 |
|---|---|
| 版本检查 | 冷启动自动检查（成功 24h / 失败 1h 节流）+ 手动检查（取消在途任务、绕过节流） |
| 更新 UI | Material3 全局弹窗：版本/大小/日期/更新说明、下载进度、校验、失败原因、安装权限 |
| 安全下载 | 仅 HTTPS、重定向逐跳 host 白名单、SHA-256（API digest / `.sha256` 回退）、`.part` 续传 |
| 安装安全 | 包名 + `versionCode` 严格递增 + 签名证书一致校验；FileProvider + 系统安装器，无静默安装 |
| 后台下载 | WorkManager 唯一任务、通知进度、进程被杀后续传、完成通知/回前台自动续装 |
| Beta 通道 | GitHub prerelease（tag 含 `-`）+ semver 预发布排序；stable 永远看不到 Beta |
| 灰度/强制 | Release 资产 `-update.json`（schema 1）：`rolloutPercent` 本地确定性分桶、`minSupportedVersion` 强制更新 |
| 弱网可用 | 直连 GitHub CDN 失败时按镜像前缀回退；线路健康/冷却/自动换路 |
| 可测试 | 94 个从 ZhiBox 迁移的用例 + 136 个新增，共 **230** 个自动化用例（core 119 / Android 36 / Compose 75），全部协作者可注入 |

## 与 ZhiBox 的关系

ReleaseKite 是 ZhiBox 更新模块的**可复用化抽取**（MIT License，见 [`NOTICE`](NOTICE)）：

- 行为契约对齐 ZhiBox `docs/product/APP_UPDATE_SPEC.md`，实现对齐 v0.1.17；
- 状态机、取消规则、节流/缓存 TTL、灰度/强制、线路回退、看门狗/代际等不变量逐条保留，
  每个 ZhiBox 修复提交都能在 [`docs/MIGRATION_ZHIBOX.md`](docs/MIGRATION_ZHIBOX.md) 的
  「提交 → 不变量 → ReleaseKite 测试」对照表中找到对应测试；
- 泛化差异（配置化资产匹配、可选 checksum、core 无文案、可注入平台适配等）同样记录在该文档。

## 5 分钟接入

```kotlin
// settings.gradle.kts
repositories {
    google(); mavenCentral()
    maven("https://jitpack.io")
}

// app/build.gradle.kts
dependencies {
    // JitPack 多模块坐标：com.github.IAmKings.ReleaseKite:<module>:v0.1.0
    // Compose UI 模块（api 传递依赖会带入 releasekite 与 releasekite-core）
    implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")

    // 宿主自己的 Compose 栈（库的公开 API 用 api 暴露了 compose-ui/ui-graphics；
    // 宿主页面自身仍需 BOM/material3/activity-compose，版本按你的工程选择）
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
```

```kotlin
// Application
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = ReleaseKiteConfig(
                repository = "your-name/your-app",
                apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
                downloadMirrors = listOf("https://ghproxy.net/"), // 可选
            ),
        )
    }
}

// Activity：挂在根 Box（全局弹窗 + 生命周期 + 通知权限）
setContent {
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            AppContent()
            ReleaseKiteHost()
        }
    }
}

// 设置页（manager 来自 Application 中的 ReleaseKite.initialize，也可直接用 ReleaseKite.manager）
val manager = ReleaseKite.manager
ReleaseKiteUpdateRow(manager = manager)          // 一行「检查更新」
ReleaseKiteSettingsSection(manager = manager)    // 完整设置区（自动检查 / Beta / 线路状态）
```

完整步骤与注意事项见 [`docs/INTEGRATION.md`](docs/INTEGRATION.md)；`sample/` 是可运行示例。

后台下载完成后点击通知会打开宿主 launcher，并携带 boolean extra
`io.github.releasekite.extra.INSTALL_UPDATE`（PendingIntent requestCode 4203）。宿主可选在入口
读取该 extra 并调用 `ReleaseKite.manager.installDownloadedIfReady()` 主动续装；**不处理也不影响
功能**，`ReleaseKiteHost` 的 `onAppResumed` 会自动兜底（通知被拒/不可见时同样生效）。

## 配置表（`ReleaseKiteConfig`）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `repository` | —（必填） | `owner/repo`，必须匹配 `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$` |
| `apkAssetPattern` | `.*\.apk$`（忽略大小写） | Release APK 资产匹配；必须恰好命中一个，否则 `IncompleteRelease` |
| `checksumAssetSuffix` | `".sha256"` | 与 APK 同名前缀的校验资产后缀 |
| `metadataAssetSuffix` | `"-update.json"` | 灰度/强制元数据资产后缀；`null` = 关闭 |
| `requireChecksum` | `true` | 无 digest 且无 `.sha256` 时拒绝下载（`NoChecksum`） |
| `downloadMirrors` | 空 | `https://` 且以 `/` 结尾的镜像前缀，直连失败时按序回退 |
| `userAgent` | `"ReleaseKite"` | 库初始化时自动变为 `ReleaseKite/<版本> (<包名>)`；可覆盖 |
| `downloadFilePrefix` | `"releasekite"` | 下载文件名前缀（非法字符替换为 `_`） |
| `betaChannelEnabled` | `true` | 是否允许用户打开 Beta 通道 |
| `autoCheckIntervalMs` | 24h | 自动检查成功后的节流间隔 |
| `autoRetryIntervalMs` | 1h | 自动检查失败后的退避间隔 |
| `forcedCacheMaxAgeMs` | 24h | 强制缓存过期后先联网校验 |
| `cacheRevalidateIntervalMs` | 6h | 普通/灰度缓存重新校验间隔 |
| `downloadTimeoutMs` | 5min | 单次下载整体超时（保留 `.part` 续传） |
| `downloadStartTimeoutMs` | 20s | 下载启动看门狗（WorkManager 长时间 WAITING） |
| `forcedEscapeFailures` | 3 | 强制更新下载连续失败多少次后允许「暂时退出」 |

网络/安全实现细节见 [`docs/SECURITY.md`](docs/SECURITY.md)。

## Release 资产契约

```text
myapp-1.2.3-<sha7>-release-signed.apk          # 必需：唯一匹配 apkAssetPattern 的 APK
myapp-1.2.3-<sha7>-release-signed.apk.sha256   # 推荐：sha256sum 格式
myapp-1.2.3-<sha7>-update.json                 # 可选：灰度比例 / 强制下限（schema 1）
myapp-1.2.3-<sha7>-mapping.txt                 # 可选：R8 mapping
```

- tag 必须等于 `v<versionName>`，`versionCode` 必须递增，签名证书必须与已安装应用一致；
- `-update.json`：`{schema:1, versionName, tagName, channel, rolloutPercent, minSupportedVersion,
  publishedAt, htmlUrl, apk{name,size,sha256}}`；不需要灰度/强制时可以不放 policy 文件，
  发布模板会跳过元数据生成（warning），客户端按 100%/非强制处理；
- 发布流水线模板：[`samples/github-workflows/release.yml`](samples/github-workflows/release.yml)
  与 [`samples/release-policy.json`](samples/release-policy.json)；
- 详细操作、Beta/灰度/强制与 CDN 缓存经验见 [`docs/RELEASE_PIPELINE.md`](docs/RELEASE_PIPELINE.md)。

## 状态机与安全链（速览）

| 阶段（`UpdatePhase`） | 可见 UI | 可取消 |
|---|---|---|
| `Idle` / `Checking` / `UpToDate` | 无弹窗；设置行内联状态 | — |
| `Available`（普通） | 版本/大小/日期/说明 +「立即更新 / 稍后」 | ✅ |
| `Available`（强制） | 无「稍后」，标题「需要更新」 | ❌ |
| `RolloutPending` | 无弹窗；设置行「正在分批推送（N%）」 | — |
| `Downloading` / `Verifying` | 进度/校验，无操作按钮 | ❌ |
| `DownloadFailed` | 可读失败原因 +「重试」（强制态按逃生阈值） | ✅（强制态受限） |
| `NeedInstallPermission` | 「去设置 / 取消」 | ✅ |

安全链：HTTPS → 逐跳 host 白名单 → SHA-256 → 包名/versionCode/签名 → FileProvider →
系统安装器（用户授权）。详见 [`docs/SECURITY.md`](docs/SECURITY.md)。

## JitPack / GitHub Packages 坐标

> 本仓库 JitPack 坐标：`com.github.IAmKings.ReleaseKite:<module>:<tag>`（tag 如 `v0.1.0`）；
> 根工程本身不发布制品，不要依赖 `com.github.IAmKings:ReleaseKite`。

```kotlin
// JitPack：完整 UI（api 传递依赖带入 releasekite 与 releasekite-core）
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")

// 只要 Android 适配层（无 Compose UI）
implementation("com.github.IAmKings.ReleaseKite:releasekite:v0.1.0")

// 只要纯 JVM 引擎（自建平台适配/工具）
implementation("com.github.IAmKings.ReleaseKite:releasekite-core:v0.1.0")

// 本地 Maven 备选：先 ./gradlew publishToMavenLocal（artifact 版本为 0.1.0，不带 v）
implementation("com.github.IAmKings.ReleaseKite:releasekite-core:0.1.0")
implementation("com.github.IAmKings.ReleaseKite:releasekite:0.1.0")
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
```

### GitHub Packages（需要 token）

仓库已内置 `.github/workflows/publish-github-packages.yml`：GitHub Release published 或手动
`workflow_dispatch` 时，用 `GITHUB_TOKEN`（`packages: write`）把三个模块发布到 GitHub Packages。
消费端需要带 `read:packages` 的 token：

```kotlin
// settings.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/IAmKings/ReleaseKite")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN") // 需要 read:packages
        }
    }
}

// app/build.gradle.kts（GitHub Packages 使用 artifact 版本 0.1.0）
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
```

> 不适合作为公开"零配置"渠道：消费者必须持有 GitHub token；公开分发优先用 JitPack。

> **JitPack 构建**：仓库使用与 JitPack 一致的 group（`com.github.IAmKings.ReleaseKite`），
> 推送 tag 后 JitPack 首次请求会自动构建；本地可用 `scripts/consumer-smoke.sh` 验证
> mavenLocal 发布物。若未来发布到 Maven Central，可用
> `-PGROUP=io.github.iamkings` 覆盖坐标（POM url/scm 已指向本仓库）。

## 构建与测试

本地需要 JDK 21（macOS 可用 Android Studio JBR）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 三个模块的单元测试（core 纯 JVM；android/compose 用 Robolectric）
./gradlew :releasekite-core:test :releasekite:testDebugUnitTest :releasekite-compose:testDebugUnitTest

# Debug 示例 + Release 门禁（AAR + sample R8/minify）+ lint
./gradlew :sample:assembleDebug
./gradlew :releasekite:assembleRelease :releasekite-compose:assembleRelease :sample:assembleRelease
./gradlew :releasekite:lintDebug :releasekite-compose:lintDebug :sample:lintDebug
./gradlew :releasekite:lintRelease :releasekite-compose:lintRelease :sample:lintRelease

# 发布到本地 Maven（验证坐标）；再跑独立 consumer + R8 冒烟
./gradlew publishToMavenLocal
scripts/consumer-smoke.sh
```

CI 门禁见 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)：JDK 21 + wrapper 校验 +
三模块单测 + Debug sample + Debug/Release lint + Release AAR/sample（R8）+ `publishToMavenLocal`，
失败上传报告。测试矩阵与真机验收清单见 [`docs/TESTING.md`](docs/TESTING.md)。

示例应用（`sample`）默认仓库为 `IAmKings/ZhiBox`：可以走通真实的检查/下载链路，但下载到的
ZhiBox APK 会在包名/签名校验处被拒绝——这是**预期的安全拦截**（只允许升级应用自身）。
示例通过 `metadataAssetSuffix = null` 关闭灰度/强制元数据，避免 `versionName=0.0.1` 命中
真实仓库的 `minSupportedVersion` 而首启被强制弹窗锁死；真实接入请保留默认值。

## FAQ

| 问题 | 回答 |
|---|---|
| 为什么下载后提示「安装包无效/签名不一致」？ | 自更新只能安装**同签名**且 `versionCode` 更大的自身 APK；换签名/换包名/降级都会被拒绝 |
| 为什么检查结果显示无 Release？ | 仓库必须 public；stable 通道不返回 prerelease；`repository` 需为 `owner/repo` |
| 国内下载总是超时？ | 直连 GitHub CDN 不稳；配置 `downloadMirrors`（`https://` 且以 `/` 结尾） |
| 通知不显示？ | Android 13+ 通知权限被拒；下载继续，回前台会自动续装 |
| 通知点击后如何立刻安装？ | 读取 launcher intent 的 `io.github.releasekite.extra.INSTALL_UPDATE` extra 后调 `ReleaseKite.manager.installDownloadedIfReady()`；不处理时 `ReleaseKiteHost` 也会在回到前台时自动续装 |
| 为什么冷启动不立即检查？ | 设计节流：成功后 24h、失败后 1h；手动检查始终可用 |
| 灰度版本为什么没有弹窗？ | 本机未命中分桶（`RolloutPending`）；0%/100% 是可验证边界 |
| 能上架 Google Play 吗？ | `REQUEST_INSTALL_PACKAGES` 有商店政策限制，Play 分发应改用 Play In-App Updates，见 `docs/SECURITY.md` |

更多问题见 [`docs/INTEGRATION.md`](docs/INTEGRATION.md) §8。

## English Quick Start

ReleaseKite is a reusable Android in-app update library backed by GitHub Releases
(extracted from ZhiBox, MIT). Requirements: minSdk 24, compileSdk 36, Kotlin 2.4.x, JDK 17+.

```kotlin
// 1) Repositories
maven("https://jitpack.io")

// 2) Dependencies (JitPack multi-module coordinate; replace <owner>/<version>)
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")
// Host app's own Compose stack (library exposes compose-ui/ui-graphics via api)
implementation(platform("androidx.compose:compose-bom:2026.06.01"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.13.0")

// 3) Application.onCreated
ReleaseKite.initialize(
    context = this,
    config = ReleaseKiteConfig(
        repository = "your-name/your-app",
        apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
    ),
)

// 4) Root layout
setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { AppContent(); ReleaseKiteHost() } } }
```

Publish releases with tags `v<versionName>` and assets
`<app>-<version>-<sha7>-release-signed.apk` (+ `.sha256`, optional `-update.json`).
See [`docs/INTEGRATION.md`](docs/INTEGRATION.md), [`docs/RELEASE_PIPELINE.md`](docs/RELEASE_PIPELINE.md)
and [`docs/SECURITY.md`](docs/SECURITY.md).

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md) | 冻结 API 契约（实现与文档的唯一事实源） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块边界、接口、数据流、状态机、线程模型、为何 core 是纯 JVM |
| [`docs/INTEGRATION.md`](docs/INTEGRATION.md) | 5 分钟接入、通知/FileProvider/WorkManager/备份/R8、FAQ |
| [`docs/RELEASE_PIPELINE.md`](docs/RELEASE_PIPELINE.md) | 资产命名、`-update.json`、tag 校验、Beta/灰度/强制、CDN 与镜像 |
| [`docs/SECURITY.md`](docs/SECURITY.md) | 威胁模型、HTTPS/白名单/SHA/签名、隐私、商店合规 |
| [`docs/MIGRATION_ZHIBOX.md`](docs/MIGRATION_ZHIBOX.md) | ZhiBox 提交 → 不变量 → ReleaseKite 测试对照、文件映射、有意差异 |
| [`docs/TESTING.md`](docs/TESTING.md) | 自动化矩阵、门禁命令、真机验收清单 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更记录 |

## License

[MIT](LICENSE)。本项目源自 [ZhiBox](https://github.com/IAmKings/ZhiBox)（MIT），
版权与来源声明见 [`NOTICE`](NOTICE)。
