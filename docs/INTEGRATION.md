# 接入指南（5 分钟）

> ReleaseKite 目标是「一个 Application 初始化 + 一个根布局挂载」即可获得完整更新能力。
> 行为契约见 [`API_CONTRACT.md`](API_CONTRACT.md)，安全设计见 [`SECURITY.md`](SECURITY.md)。

## 0. 环境要求

| 项 | 要求 |
|---|---|
| minSdk | 24 |
| compileSdk / targetSdk | 36 |
| Kotlin | 2.4.x（Compose 编译器插件同版本） |
| JDK | 17+（仓库构建用 JDK 21） |
| Compose BOM | 与仓库 `gradle/libs.versions.toml` 对齐（示例 `2026.06.01`） |
| 宿主仓库 | 必须是 **public** GitHub 仓库（匿名 API 查询 + 公开资产下载） |

## 1. 添加依赖（约 1 分钟）

### 1.1 JitPack（推荐，坐标占位符）

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
    // JitPack 多模块坐标：com.github.IAmKings.ReleaseKite:<module>:<tag>
    // Compose UI + Android 适配层 + core（api 传递依赖，一步到位）
    implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")

    // 宿主自己的 Compose 栈（库仅 api 暴露 compose-ui/ui-graphics；
    // BOM/material3/activity-compose 等由宿主按自己的 Compose 版本声明）
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
```

> **坐标说明**：本仓库 JitPack 坐标为 `com.github.IAmKings.ReleaseKite:<module>:<tag>`
> （tag 如 `v0.1.0`）；根工程本身不发布制品，不要依赖
> `com.github.IAmKings:ReleaseKite`。
>
> 只需要部分模块时使用同样的坐标格式：
>
> ```kotlin
> // Android 适配层（无 Compose UI）
> implementation("com.github.IAmKings.ReleaseKite:releasekite:v0.1.0")
> // 纯 JVM 引擎（自建平台适配/工具）
> implementation("com.github.IAmKings.ReleaseKite:releasekite-core:v0.1.0")
> ```
>
> 本地验证/离线场景用 `./gradlew publishToMavenLocal` 发布后走 `mavenLocal()`，
> 坐标为本仓库默认 groupId：`com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0`（见 §1.2）。

### 1.2 本地 Maven 仓库 / GitHub Packages

本仓库的发布配置（`gradle/publishing.gradle.kts`）会把三个模块发布到 Maven Local：

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings.gradle.kts
repositories { mavenLocal() }

// app/build.gradle.kts
dependencies {
    // mavenLocal artifact 版本为 0.1.0（不带 v；JitPack tag 为 v0.1.0）
    implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
}
```

### GitHub Packages（需要 token）

仓库的 `publish-github-packages.yml` 会在 GitHub Release published 或手动 dispatch 时发布到
`https://maven.pkg.github.com/IAmKings/ReleaseKite`（使用 `GITHUB_TOKEN`，`packages: write`）。
消费端需要带 `read:packages` 的 token：

```kotlin
// settings.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/IAmKings/ReleaseKite")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN") // read:packages
        }
    }
}

// app/build.gradle.kts（GitHub Packages artifact 版本 0.1.0）
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
```

## 2. Application 初始化（约 1 分钟）

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = ReleaseKiteConfig(
                // 你的公开 GitHub 仓库：owner/repo
                repository = "your-name/your-app",
                // 与该仓库 Release 资产命名保持一致（默认匹配任意 .apk，建议收紧）
                apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
                // 元数据资产后缀（灰度/强制）；不使用可置 null
                metadataAssetSuffix = "-update.json",
                // 国内网络建议配置镜像（必须 https:// 且以 / 结尾）；不需要则留空
                downloadMirrors = listOf("https://ghproxy.net/"),
            ),
        )
    }
}
```

要点：

- 必须在任何 `ReleaseKite.manager` / `ReleaseKiteHost` 使用之前初始化；
- `repository` 必须匹配 `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`，否则构造 `ReleaseKiteConfig` 抛异常；
- UA 默认由库生成（`ReleaseKite/<库版本> (<包名>)`），无需配置；
- 其余节流/超时/缓存参数都有默认值，见 [`API_CONTRACT.md`](API_CONTRACT.md) §1.1。

## 3. 挂载全局 UI（约 1 分钟）

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppContent()
                    // 全局更新弹窗 + 生命周期 + 通知权限
                    ReleaseKiteHost()
                }
            }
        }
    }
}
```

`ReleaseKiteHost` 负责：

- `onAppEntered()`（每进程一次：冷启动检查/展示缓存）；
- `onAppResumed()`（回到前台：通知被拒/权限返回后的续装）；
- 前后台通知（决定下载完成后自动安装还是发通知）；
- Android 13+ `POST_NOTIFICATIONS` 运行时权限请求（`requestNotificationPermission = true`，默认）。

如果宿主不是 Compose Activity，可以用 `ComposeView` 只承载 `ReleaseKiteHost()`；
也可以不挂 Host、完全自绘更新 UI，但此时**必须自己补齐 Host 负责的生命周期调用**，
否则冷启动自动检查 / 下载完成续装都不会发生：

```kotlin
// 冷启动（每进程一次）：检查更新/展示缓存/恢复待安装
manager.onAppEntered()
// 每次回到前台：通知被拒或安装权限返回后的自动续装
manager.onAppResumed()
// 前后台切换：决定下载完成时自动拉起安装器还是发通知。
// 每个 Activity/窗口在 onStart/onStop 成对调用；内部是引用计数，多 Activity 重叠不会误判。
ReleaseKite.onForeground() // Activity.onStart / 窗口进入前台
ReleaseKite.onBackground() // Activity.onStop / 窗口退出前台
// 渲染：无状态组件，状态与动作全部由宿主提供
ReleaseKiteUpdateDialog(state = state, actions = actions)
```

`manager.onAppEntered()` 内部有每进程一次的原子守卫，重复调用安全；`onAppResumed()`
应在每个 `Activity.onResume`（或 `Lifecycle.Event.ON_RESUME`）调用。

## 4. 设置页入口（约 1 分钟）

```kotlin
// 方式 A：自带完整设置区（自动检查开关 / Beta 开关 / 检查更新 / 线路状态）
ReleaseKiteSettingsSection(manager = ReleaseKite.manager)

// 方式 B：只要一行「检查更新」，点击 = 手动检查
ReleaseKiteUpdateRow(manager = ReleaseKite.manager)

// 方式 C：完全自定义 UI，只消费状态
val state by ReleaseKite.manager.state.collectAsState()
Text(state.phase.toString())
Button(onClick = { ReleaseKite.manager.checkNow() }) { Text("检查更新") }
```

也可以先用无状态的 `ReleaseKiteUpdateRow(state = ..., onClick = ...)` /
`ReleaseKiteSettingsSection(state = ..., onAutoCheckChange = ..., ...)` 自己接状态，
再用 `ReleaseKiteActions` 组合 `ReleaseKiteUpdateDialog`。

## 5. 通知权限（Android 13+）

| 场景 | 行为 |
|---|---|
| Host 默认（`requestNotificationPermission = true`） | 首次需要下载时请求 `POST_NOTIFICATIONS`；`AndroidManifest` 中的权限由库清单合并 |
| 通知被允许 | 后台下载显示进度通知；完成后「更新已下载，点击安装」 |
| 通知被拒绝/通知不可见 | 后台下载**继续**，只是没有通知；用户回到应用前台时 `onAppResumed` 自动校验并拉起安装器 |
| 宿主自行管理权限 | `ReleaseKiteHost(requestNotificationPermission = false)`，在点击「立即更新」前自行请求，体验更可控 |

通知渠道：`releasekite_update`（由库创建）；进度 4201 / 完成 4202 通知 ID 与
PendingIntent requestCode 4203 均为库内部约定，宿主不要复用这些 ID。

### 5.1 通知点击续装（可选但推荐）

后台下载完成后点击通知会打开**宿主 launcher**（库通过 `PackageManager.getLaunchIntentForPackage`
构造 PendingIntent，不硬编码宿主 Activity 类名），并携带 boolean extra：

```text
io.github.releasekite.extra.INSTALL_UPDATE = true
```

宿主可以在入口（Activity `onCreate`，或使用 `SingleTop` 时在 `onNewIntent`）读取该 extra 并
主动续装：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleInstallUpdateExtra(intent)
        setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { AppContent(); ReleaseKiteHost() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallUpdateExtra(intent)
    }

    private fun handleInstallUpdateExtra(intent: Intent?) {
        if (intent?.getBooleanExtra("io.github.releasekite.extra.INSTALL_UPDATE", false) == true) {
            // 跨进程可用：从持久化记录恢复待安装 APK 并拉起系统安装器。
            ReleaseKite.manager.installDownloadedIfReady()
        }
    }
}
```

**即使宿主完全不处理该 extra 也不影响功能**：`ReleaseKiteHost` 的 `onAppResumed()` 会在回到前台时
自动校验并拉起安装器（通知被拒/不可见时同样生效）；上面的入口处理只是让「点通知」的路径更直接。

## 6. FileProvider / WorkManager / 备份 / R8

### 6.1 FileProvider

库的 `AndroidManifest.xml` 已自带 **独立的 provider 子类**（不是直接声明
`androidx.core.content.FileProvider`，避免与宿主已有 FileProvider 在 manifest merger 中冲突）：

```xml
<provider
    android:name="io.github.releasekite.internal.ReleaseKiteFileProvider"
    android:authorities="${applicationId}.releasekite.updates"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/releasekite_file_paths" />
</provider>
```

- 宿主**可以同时声明自己的 FileProvider**（例如自己的 `androidx.core.content.FileProvider`），
  库的 provider 使用独立类名，manifest 合并不会冲突；
- authority 固定为 `${applicationId}.releasekite.updates`：宿主**不要复用**该 authority，
  也无需自行声明该 provider；
- 只暴露 `cacheDir/releasekite/updates/`，不暴露宿主 filesDir/数据库等其它目录；
- 若宿主 manifest 用 `tools:replace` 覆盖 `<application>`，请保留合并后的 provider/service 节点。

### 6.2 WorkManager

- 依赖由 `:releasekite` 传入，宿主无需额外声明；
- 库使用 WorkManager 默认实例与唯一工作名 `releasekite-update-download`、tag `releasekite-update`；
  下载任务互斥，不会并发下载两个版本；
- 如果宿主自定义了 `Configuration.Provider` / `WorkerFactory`，必须保证默认
  `ReleaseKiteDownloadWorker(Context, WorkerParameters)` 构造器可被实例化（consumer rules 已保留）；
- 不要禁用 WorkManager 的默认初始化（`androidx.work.WorkManagerInitializer`）；
- **多进程宿主**：`ReleaseKite.initialize` 会创建进程内单例、WorkManager 调度器与 SharedPreferences
  状态；库未启用 WorkManager 多进程模式，请只在**主进程**初始化/挂 Host，避免子进程重复处理
  下载状态或重复拉起安装器。若必须多进程，需要宿主自行保证只有主进程调用 `initialize`。

### 6.3 备份排除

更新状态写在两个 SharedPreferences 文件中：
- `releasekite_update`：检查节流/缓存/线路健康/待安装记录；
- `releasekite_download_target`：本次下载的完整 `UpdateInfo` 与 `workId` 绑定（跨进程恢复下载身份用）。
`cacheDir` 默认不参与 Auto Backup，但 SharedPreferences 会被备份/迁移。示例应用
（`sample`）直接 `android:allowBackup="false"`；若你的应用需要备份，请排除这两个文件：

```xml
<!-- res/xml/backup_rules.xml（Android 12+ 用 dataExtractionRules，12 以下用 fullBackupContent） -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="releasekite_update.xml" />
        <exclude domain="sharedpref" path="releasekite_download_target.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="releasekite_update.xml" />
        <exclude domain="sharedpref" path="releasekite_download_target.xml" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<manifest ...>
    <application
        android:dataExtractionRules="@xml/backup_rules"
        android:fullBackupContent="@xml/backup_rules_legacy">
</manifest>
```

> 排除只是最佳实践：更新状态丢失只会导致重新检查一次，不会影响已安装应用。

### 6.4 ProGuard / R8

- `:releasekite` 已提供 `consumer-rules.pro`，自动保留 WorkManager Worker 构造器；
- 其它实现均为普通 Kotlin 代码 + `org.json`/`kotlinx.serialization` DOM，无需额外 keep；
- 若宿主使用 full-mode R8 且遇到 `Missing class` 警告，先确认 WorkManager 是 `runtimeOnly` 还是
  被 `tools:node="remove"` 移除；`sample` 的 release 变体已开启 `isMinifyEnabled=true` +
  `isShrinkResources=true`，是 consumer rules/Worker 在混淆下的持续回归门禁。

## 7. 发布侧准备（约 1 分钟）

宿主仓库需要提供与配置匹配的 Release 资产（详见 [`RELEASE_PIPELINE.md`](RELEASE_PIPELINE.md)）：

```text
your-app-<version>-<sha7>-release-signed.apk
your-app-<version>-<sha7>-release-signed.apk.sha256
your-app-<version>-<sha7>-update.json        # 可选：灰度/强制；metadataAssetSuffix = "-update.json"
```

- tag 形如 `v1.2.3`，且与 `versionName` 一致；
- 预发布用 `v1.2.3-beta.1`（tag 含 `-`），GitHub Release 必须标记 prerelease；
- 资产选择规则：`apkAssetPattern` 匹配**恰好一个**资产，否则报 `IncompleteRelease`；
- APK 的 `versionCode` 必须严格大于已安装版本，签名证书必须与已安装应用一致。

## 8. 常见问题（FAQ）

| 现象 | 原因 / 处理 |
|---|---|
| `ReleaseKite.manager` 抛未初始化异常 | `initialize` 未调用或晚于 UI；放到 `Application.onCreate` 第一行区域，并可在使用前 `ReleaseKite.isInitialized()` 判断 |
| 检查结果 `NoRelease` | 仓库不是 public、`repository` 写成 URL、或只有 draft/prerelease（stable 通道不会返回 prerelease） |
| 检查结果 `IncompleteRelease` | 资产名不匹配 `apkAssetPattern`，或同一 Release 匹配到 0/多个 APK；检查发布产物命名 |
| `NoChecksum` | 未上传 `.sha256` 且 Release API 没有 digest；建议保留 `requireChecksum = true`（默认） |
| 下载总是 `Network`/`Timeout` | 国内直连 GitHub CDN 不稳定：配置 `downloadMirrors`（`https://` 且以 `/` 结尾）；镜像仅看到公开 APK，完整性由 SHA-256 + 签名兜底 |
| 下载完成但安装提示「安装包无效或签名不一致」 | 包名、`versionCode`（必须更大）或签名证书与已安装应用不一致——自更新必须同签名、版本号递增 |
| 通知不出现 | Android 13+ `POST_NOTIFICATIONS` 被拒；下载仍继续，回前台会自动续装（也可让 Host 请求权限） |
| 弹「需要安装权限」 | 首次安装未知应用需授权；点「去设置」授权后返回即可自动继续安装 |
| 冷启动/隔几小时不自动检查 | 设计节流：成功后 24h、失败后 1h 退避；手动「立即检查」始终绕过 |
| 打开 Beta 仍看不到预发布 | 通道开关未开（`setChannel(BETA)` / 设置区开关）、tag 未含 `-`、或 Release 未标记 prerelease |
| 新版本存在但只显示「分批推送」 | 灰度未命中本机分桶（`rolloutPercent < 100`）；0% 与 100% 是可验证边界 |
| 下载长时间 0% | 旧版本 WorkManager 约束问题；新版本有 20s 启动看门狗 + 网络预检，会转为可重试失败 |
| 上架 Google Play 被拒 | `REQUEST_INSTALL_PACKAGES` 自更新政策限制；Play 分发应改用 Play In-App Updates，见 [`SECURITY.md`](SECURITY.md) |

## 9. 最小接入清单

- [ ] `settings.gradle.kts` 添加 JitPack/本地仓库；
- [ ] `app/build.gradle.kts` 依赖 `releasekite-compose`；
- [ ] `Application.onCreate` 调用 `ReleaseKite.initialize(...)`；
- [ ] 根布局挂 `ReleaseKiteHost()`；
- [ ] 设置页放 `ReleaseKiteSettingsSection(manager = ReleaseKite.manager)` 或
      `ReleaseKiteUpdateRow(manager = ReleaseKite.manager)`；
- [ ] Release 流水线产出 APK + `.sha256` + （可选）`-update.json`，tag = `v<versionName>`；
- [ ] 真机走查普通更新 / Beta / 灰度 0%、100% / 强制 / 镜像（清单见 [`TESTING.md`](TESTING.md)）。
