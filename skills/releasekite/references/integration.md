# 接入宿主 App

目标：在一个 Android/Compose App 中完成依赖、初始化、全局 UI 和发布侧准备，使更新链路可运行。
完整细节：[docs/INTEGRATION.md](https://github.com/IAmKings/ReleaseKite/blob/main/docs/INTEGRATION.md)。

## 1. 前置条件

- JDK 21；Android `minSdk >= 24`，`compileSdk/targetSdk` 与库一致（当前 36）；
- 宿主 App 已有自己的签名 release 构建；
- 公开 GitHub 仓库（`owner/repo`），且 Release 资产按契约命名；
- 如果使用 Compose UI：`releasekite-compose` 通过 `api` 暴露 Compose BOM 约束与
  `compose-ui`/`compose-ui-graphics`（BOM 是库的编译/传递下限，宿主可用自己的 BOM 对齐/覆盖）；
  `material3`/`activity-compose` 仍由宿主声明。

## 2. 选择模块

| 依赖 | 适用场景 |
|---|---|
| `releasekite-compose` | 推荐：需要现成更新弹窗、设置行/设置区；API 传递 `releasekite` + `releasekite-core` |
| `releasekite` | 只要 Android 适配（WorkManager/下载/安装/通知/存储），UI 自绘 |
| `releasekite-core` | 纯 JVM 引擎，自建平台适配/工具；不含 Android 实现 |

三者不要同时重复声明；使用 `releasekite-compose` 时无需再手动加另外两个。

## 3. 添加仓库与依赖

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")

    // 宿主自己的 Compose 栈（版本按宿主工程；BOM 可用宿主自己的覆盖库传递的约束）
    implementation(platform("androidx.compose:compose-bom:<宿主 BOM 版本>"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // activity-compose 也由宿主声明（示例 sample 使用 1.13.0）
}
```

GitHub Packages 备选（需要 `read:packages` token；artifact 版本 `0.1.0`）见
[release-workflow.md](release-workflow.md) 的“GitHub Packages”一节；本地验证可先
`./gradlew publishToMavenLocal` 后加 `mavenLocal()`。

## 4. Application 初始化

初始化必须在任何 `ReleaseKite.manager` / `ReleaseKiteHost` 之前完成：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = ReleaseKiteConfig(
                repository = "your-name/your-app",
                apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
                metadataAssetSuffix = "-update.json",   // 灰度/强制必需；不用才置 null
                betaChannelEnabled = true,              // 是否允许用户开启 Beta（默认 true）
                downloadMirrors = listOf("https://ghproxy.net/"), // 可选，必须 / 结尾
            ),
        )
    }
}
```

- `repository` 必须匹配 `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`；
- `apkAssetPattern` 必须只命中一个 APK，否则 `IncompleteRelease`；
- `metadataAssetSuffix = null` 等于“始终 100%、非强制”；**不要照抄 sample 的 null 配置**；
- 其他节流/超时/缓存/镜像参数见 [API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md) §1.1。

## 5. 挂载全局 UI（Compose）

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppContent()
                    ReleaseKiteHost() // 全局更新弹窗 + 生命周期 + 通知权限
                }
            }
        }
    }
}
```

`ReleaseKiteHost()` 负责：每进程一次的 `onAppEntered()`、回到前台的 `onAppResumed()`、前后台状态、
Android 13+ `POST_NOTIFICATIONS` 请求。若不用 Compose 或不用 Host，必须自行补齐：

```kotlin
manager.onAppEntered()   // 冷启动每进程一次
manager.onAppResumed()   // 每次回到前台
ReleaseKite.onForeground() // Activity.onStart / 窗口进入前台
ReleaseKite.onBackground() // Activity.onStop / 窗口退出前台
```

如果不挂 `ReleaseKiteHost`，就自己用 `ReleaseKiteUpdateDialog(state, actions)` 渲染并调用 manager。

## 6. 设置页入口

```kotlin
val manager = ReleaseKite.manager

ReleaseKiteUpdateRow(manager = manager)          // 一行“检查更新”
ReleaseKiteSettingsSection(manager = manager)    // 自动检查 / Beta 通道 / 线路状态
```

`ReleaseKiteUpdateRow`/`ReleaseKiteSettingsSection` 在下载/校验/待安装权限期间会正确置灰；
自定义 UI 时必须保持同样的 phase/取消规则，参考 [api-usage.md](api-usage.md)。

## 7. 通知点击续装（可选但推荐）

后台下载完成后，通知会打开宿主 launcher 并携带 boolean extra
`io.github.releasekite.extra.INSTALL_UPDATE`。宿主 Activity 使用 `singleTop` 时可在
`onCreate`/`onNewIntent` 读取并主动续装：

```kotlin
if (intent?.getBooleanExtra("io.github.releasekite.extra.INSTALL_UPDATE", false) == true) {
    ReleaseKite.manager.installDownloadedIfReady()
}
```

不处理也不影响功能：`ReleaseKiteHost` 回到前台会自动校验并拉起安装器。

## 8. 权限、FileProvider、WorkManager、备份、R8

- **权限/组件**：库 manifest 自动合并 `INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS`、
  `FOREGROUND_SERVICE*`、`REQUEST_INSTALL_PACKAGES`、WorkManager 前台服务与 FileProvider；
- **FileProvider**：库使用独立子类 `io.github.releasekite.internal.ReleaseKiteFileProvider`，
  authority 为 `${applicationId}.releasekite.updates`；不要复用该 authority，也不要重复声明；
- **WorkManager**：不要禁用默认初始化；若宿主有自定义 `WorkerFactory`，确保
  `ReleaseKiteDownloadWorker(Context, WorkerParameters)` 可构造；
- **多进程**：只在主进程初始化/挂 Host；库未启用 WorkManager 多进程模式；
- **备份**：排除 `releasekite_update.xml` 与 `releasekite_download_target.xml`（示例见 INTEGRATION §6.3）；
- **R8**：库自带 consumer rules；宿主 release 开启 minify 后跑一次 Worker/FileProvider 验证。

## 9. 最小接入清单

- [ ] 已加 `releasekite-compose`（或按需 `releasekite`/`releasekite-core`）与宿主 Compose 栈；
- [ ] `ReleaseKite.initialize` 早于任何 manager/Host；
- [ ] 根布局挂了 `ReleaseKiteHost()`（或自绘 UI 补齐生命周期）；
- [ ] 设置页使用 `ReleaseKiteUpdateRow`/`ReleaseKiteSettingsSection`（或按 API 自绘）；
- [ ] 宿主已配置与 `ReleaseKiteConfig` 一致的 Release 资产；
- [ ] `assembleDebug` 通过；
- [ ] release 构建验证未知来源、通知权限、FileProvider 与 WorkManager。

## 10. 常见接入错误

| 现象 | 原因/处理 |
|---|---|
| `IllegalStateException: ReleaseKite 尚未初始化` | 在 `initialize` 前调了 manager/Host；把初始化移到 Application |
| 首启就被强制锁死 | 照抄 sample 时 metadata 仍为 `null`？或真实 policy 的 `minSupportedVersion` 过高；生产用 `-update.json` 并检查 policy |
| `IncompleteRelease` | APK 资产 0 个或多个；收紧 `apkAssetPattern`，排除 unsigned/旁路 APK |
| 国内网络一直超时 | 配 `downloadMirrors`（https + `/` 结尾），并让元数据/APK 都走镜像回退 |
| 通知不出现但下载完成 | 通知权限被拒是允许路径；Host 回到前台会自动续装；不要当成下载失败 |
| 安装器打不开 | 检查未知来源权限/`REQUEST_INSTALL_PACKAGES`；走 `NeedInstallPermission` 引导 |
| Release 合并冲突 | 宿主自定义 FileProvider 时保留库的独立 provider 节点，不要 `tools:node="remove"` |

