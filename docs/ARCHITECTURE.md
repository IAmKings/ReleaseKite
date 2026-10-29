# ReleaseKite 架构

> 面向维护者与高级接入者。契约以 [`API_CONTRACT.md`](API_CONTRACT.md) 为准；行为不变量与
> ZhiBox 迁移对照见 [`MIGRATION_ZHIBOX.md`](MIGRATION_ZHIBOX.md)。

## 1. 定位与边界

ReleaseKite 是从 [ZhiBox](https://github.com/IAmKings/ZhiBox) 中抽取的可复用 Android
应用内更新库：以 **GitHub Release 为版本源**，提供「检查 → 更新弹窗 → 下载 → 校验 →
系统安装器」全链路，并支持后台下载、通知、Beta 通道、灰度分桶与强制更新。

明确**不做**的事：

- 不做增量更新（bsdiff）：APK v2/v3 签名覆盖整个文件，应用内无法用 release 私钥重新签名；
- 不做服务端：版本信息直接来自公开仓库的 GitHub API 与 Release 资产，匿名访问；
- 不做私有仓库鉴权、不做遥测、不做多 APK/ABI 选择；
- 不替代应用商店更新通道（Play 分发场景见 [`SECURITY.md`](SECURITY.md) 的合规提醒）。

## 2. 模块与依赖方向

```text
sample（:sample，io.github.releasekite.sample）
  └── releasekite-compose（:releasekite-compose，Compose UI）
        └── releasekite（:releasekite，Android 适配层）
              └── releasekite-core（:releasekite-core，纯 JVM 领域层）
```

| 模块 | 包 | 类型 | 职责 |
|---|---|---|---|
| `releasekite-core` | `io.github.releasekite.core` | Kotlin/JVM（无 Android 依赖） | 版本比较、模型、解析、状态机、策略/灰度/线路、下载与校验逻辑 |
| `releasekite` | `io.github.releasekite` / `io.github.releasekite.internal` | Android Library | `ReleaseKite` 门面、WorkManager 调度、SharedPreferences 存储、通知、APK 校验、系统安装器、FileProvider |
| `releasekite-compose` | `io.github.releasekite.compose` | Android Library（Compose） | 更新弹窗、更新行、设置区；双语资源；无业务逻辑 |
| `sample` | `io.github.releasekite.sample` | Android App | 可运行示例 + fake 状态 UI 走查 |

依赖是**单向**的：`core` 不依赖 `releasekite`/`compose`；`releasekite` 不依赖 Compose；
UI 模块不持有业务状态（只消费 `StateFlow` 并回调 manager 方法）。新增功能时优先落在
`core`；只有需要 Android API 时才下沉到 `releasekite`；只有影响展示时才放 `compose`。

## 3. `releasekite-core` 结构

源码位于 `releasekite-core/src/main/kotlin/io/github/releasekite/core/`：

| 文件 | 职责 |
|---|---|
| `Version.kt` | `SemanticVersion` / `parseVersion` / `isNewerVersion`（含预发布排序） |
| `ReleaseKiteConfig.kt` | `ReleaseKiteConfig`（含派生属性 `sanitizedDownloadFilePrefix`）与 `UpdateDefaults` 常量 |
| `UpdateModels.kt` | `UpdateInfo`、`UpdateFailure`、`UpdateException`、`UpdatePhase`、`UpdateUiState`、`UpdateChannel`、`UpdateTrigger` |
| `UpdateMeta.kt` | `UpdateMeta` / `UpdateMetaParser`（`-update.json` schema 1：灰度比例、强制下限） |
| `GitHubReleaseParser.kt` | GitHub Release JSON → `UpdateInfo`；`plainTextFromMarkdown`；`.sha256` 文本解析 |
| `UpdateSource.kt` | `UpdateSource` 接口 + `GitHubUpdateSource`（stable/beta 查询、元数据拉取与镜像回退） |
| `UpdateDownloader.kt` | `UpdateDownloader` 接口 + `HttpUpdateDownloader`（白名单/重定向/镜像/续传/SHA-256） |
| `UpdateStore.kt` | `UpdateStore` 接口 + `InMemoryUpdateStore`（节流、缓存、dismiss、通道、安装记录） |
| `UpdatePolicy.kt` | 冷启动决策纯函数（节流、缓存、强制优先、自动检查开关） |
| `UpdateRollout.kt` | 灰度分桶 `cohort` / `isEligible`、强制判定 `isForced` |
| `UpdateRoutes.kt` | `RouteHealth` + `UpdateRouteSelector`（直连/镜像线路排序、冷却、记录） |
| `UpdateSchedule.kt` | `DownloadRequest`、`DownloadWorkState`、`UpdateDownloadScheduler` 接口 |
| `UpdateInstaller.kt` | `InstallResult`、`UpdateInstaller`、`ApkValidator` 接口 |
| `UpdateNotifier.kt` | 通知接口（渠道准备、完成通知、取消完成通知） |
| `UpdateEnvironment.kt` | 平台环境抽象（工作目录、当前版本、前后台、日志） |
| `UpdateManager.kt` | 状态机与编排：检查、下载、校验、安装、缓存策略、代际/看门狗 |

`internal/` 下的网络 IO 辅助（如 `HttpIo.kt`）不属于公开 API，接入方不应依赖。

## 4. 抽象接口与默认实现

`UpdateManager` 的全部协作者都是接口，默认装配发生在 `releasekite` 的
`ReleaseKite.initialize(context, config)`：

| 接口 | 默认实现（`:releasekite`） | 测试替身 |
|---|---|---|
| `UpdateSource` | `GitHubUpdateSource` | fake source / Mock HTTPS |
| `UpdateDownloader` | `HttpUpdateDownloader` | fake downloader / Mock HTTPS |
| `UpdateDownloadScheduler` | `WorkManagerUpdateDownloadScheduler` | `FakeScheduler`（测试内） |
| `UpdateInstaller` | `AndroidUpdateInstaller` | fake installer |
| `ApkValidator` | `AndroidApkValidator` | fake validator |
| `UpdateStore` | `SharedPrefsUpdateStore` | `InMemoryUpdateStore` |
| `UpdateNotifier` | `AndroidUpdateNotifier` | fake notifier |
| `UpdateEnvironment` | `AndroidUpdateEnvironment` | fake environment |

可注入的底层细节：`GitHubUpdateSource(openConnection = ...)`、
`HttpUpdateDownloader(openConnection/freeBytes/mirrorPrefixes/testAllowedHosts/nowMillis)`、
`UpdateManager(scope/clock/...)`。这些参数用于单测（Mock HTTPS、内存 store、可控时钟），
接入方通常不需要覆盖。

## 5. 数据流

### 5.1 冷启动（AUTO）

```text
ReleaseKiteHost / Activity 首次进入
  └─ UpdateManager.onAppEntered()（每进程一次）
       └─ UpdatePolicy.decideOnLaunch(...) 纯函数决策
            ├─ ShowCached：缓存版本可更新且未被 dismiss / 强制缓存 → 直接 Available 或 RolloutPending
            ├─ Skip：自动检查关闭、成功未满 24h、失败未满 1h
            └─ Check：Checking(AUTO) → GitHubUpdateSource.fetchLatest(STABLE|BETA)
                 └─ 成功：比较版本（严格更新才提示）；拉取 -update.json 元数据
                      ├─ 可更新且命中灰度 → Available(AUTO[, forced])
                      ├─ 可更新但未命中灰度 → RolloutPending（不弹窗）
                      └─ 无更新 → Idle（自动检查失败亦静默）
```

### 5.2 手动检查（MANUAL）

设置行/「立即检查」→ `UpdateManager.checkNow()`：取消在途检查任务、递增任务代际，
重新查询并展示结果（`UpToDate` 内联显示「已是最新」；失败内联显示可重试提示）。

### 5.3 灰度与强制元数据

- Release 资产 `*-update.json` 由发布流水线生成（见 [`RELEASE_PIPELINE.md`](RELEASE_PIPELINE.md)）；
- `GitHubUpdateSource` 拉取元数据失败/非法时回退默认值（100% / 非强制），不阻断更新；
- `UpdateRollout` 用 `sha256(installId + "|" + versionName)` 的**前 8 个字节按大端拼成
  64-bit（清符号位）后 `% 100`** 分桶，结果确定且无上报；
- `minSupportedVersion > 当前版本` 时强制更新，绕过 dismissed 与本机灰度资格。

### 5.4 下载

```text
Available → 用户「立即更新」→ UpdateManager.startDownload()
  └─ 构造 DownloadRequest（URL/大小/SHA/镜像/UA/超时/文件前缀）→ scheduler.enqueue()
       └─ WorkManagerUpdateDownloadScheduler → ReleaseKiteDownloadWorker（后台唯一任务）
            └─ HttpUpdateDownloader.download(...)（WorkManager 线程）
                 ├─ 线路计划（直连优先/镜像回退）→ 手动跟随重定向（每跳 HTTPS+host 白名单）
                 ├─ .part 续传（206 + Content-Range 起点一致）
                 ├─ 流式 SHA-256 → 原子 rename → cacheDir/releasekite/updates/releasekite-<ver>.apk
                 └─ 结果写入 Worker output（apk_path / failure）
       └─ DownloadWorkState Flow → UpdateManager 状态机
            ├─ SUCCEEDED → Verifying → ApkValidator.validate()
            │     ├─ 前台：安装器 → Launched → Idle
            │     └─ 后台：完成通知（点击回到应用 → 自动安装）
            └─ FAILED / WAITING 超时 → DownloadFailed（可重试/关闭）
```

### 5.5 安装

1. `ApkValidator`：包名 == 当前应用、`versionCode > 当前`、签名证书集合与已安装应用一致；
2. `AndroidUpdateInstaller`：`canRequestPackageInstalls()` 检查 → 未授权返回 `PermissionRequired`；
3. 通过 `FileProvider`（`${applicationId}.releasekite.updates`）以 `content://` +
   `application/vnd.android.package-archive` 拉起系统安装器；
4. 用户从系统设置返回后 `onAppResumed` / `installDownloadedIfReady` 继续安装，已处理过的
   成功结果不会重复消费（`consumedWorkId` / `installerLaunchedVersion`）。

### 5.6 后台完成与通知

Worker 在应用不在前台时自行落盘并发出「更新已下载」通知（不依赖 Activity/Manager 存活）；
点击通知回到应用（launcher intent 带 `io.github.releasekite.extra.INSTALL_UPDATE` extra，宿主入口可
调用 `installDownloadedIfReady()` 立即续装）；通知被拒绝或不可见时，下次回到前台由
`onAppResumed` 自动续装。

## 6. 状态机

`UpdatePhase` 是 UI 的唯一数据源（`UpdateUiState.phase`）：

```text
Idle ──checkNow/onAppEntered──▶ Checking(trigger)
Checking ──▶ Available(trigger, info[, forced])      // 发现严格更新的新版本
Checking ──▶ UpToDate(trigger, versionName)           // 手动检查时内联展示
Checking ──▶ CheckFailed(trigger, failure)            // 手动检查失败展示；自动检查静默回 Idle
Available ──startDownload──▶ Downloading(info, downloaded, total)
Available ──dismiss──▶ Idle（记录 dismissedVersion，自动提示不再打扰）
Available ──rollout 未命中──▶ RolloutPending(info, rolloutPercent)
RolloutPending ──后续刷新命中/灰度调整──▶ Available
Downloading ──进度回调──▶ Downloading
Downloading ──Worker SUCCEEDED──▶ Verifying
Downloading ──Worker FAILED/超时──▶ DownloadFailed(info, failure[, forced, escapeAllowed])
Verifying ──校验通过且前台──▶ 拉起安装器 → Idle
Verifying ──校验通过且后台──▶ 通知；回到前台自动安装
Verifying ──校验失败──▶ DownloadFailed(info, ApkInvalid/...)
安装器 ──无安装权限──▶ NeedInstallPermission(info) ──授权后 onAppResumed──▶ 拉起安装器
```

关键不变量：

- **取消规则**：`Downloading` / `Verifying` 阶段弹窗不可取消（无按钮、返回键/点外部无效）；
  `Available` / `DownloadFailed` / `NeedInstallPermission` 可关闭；强制更新在
  `escapeAllowed == false` 时不可取消（下载连续失败达到阈值后允许「暂时退出」）。
- **任务代际**：检查任务用递增 taskId；手动检查取消旧任务并丢弃旧结果。
- **下载归属**：`DownloadWorkState.workId` 标识任务；旧任务回调不得覆盖新任务状态；
  `DownloadWorkState.Status.WAITING` 启动 20s 看门狗，避免 WorkManager ENQUEUED 卡死。
- **成功去重**：`consumedWorkId` / `installerLaunchedVersion` 持久化，重复 SUCCEEDED、
  取消系统安装后重启都不会重复拉起安装器。
- **冷启动保护**：`Downloading` / `Verifying` / `NeedInstallPermission` 状态不会被
  「检查更新」或缓存展示覆盖；历史 FAILED/CANCELLED 只在本进程跟踪的任务上展示。

## 7. 线程模型

| 执行体 | 线程/调度器 | 职责 |
|---|---|---|
| `UpdateManager` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | 单一状态写者；编排检查/下载/安装 |
| `GitHubUpdateSource` | `Dispatchers.IO` | 阻塞 `HttpURLConnection` 请求 |
| `HttpUpdateDownloader` | `Dispatchers.IO` | 阻塞下载、SHA-256、文件操作 |
| `ReleaseKiteDownloadWorker` | WorkManager 线程 | 后台下载，结果写 Worker output |
| `UpdateDownloadScheduler.observe()` | WorkManager `getWorkInfosForUniqueWorkFlow` | 以 `WorkInfo` 为唯一持久化状态源 |
| `UpdateInstaller.install()` | 实现内部切 Main | `startActivity` 必须在主线程 |
| `UpdateNotifier` | 主线程（`NotificationManager` 可在任意线程，但保持主线程调用） | 通知渠道/进度/完成 |
| UI（Compose） | Main | `collectAsState()` 订阅 `StateFlow`，回调 manager 方法 |

- 状态只通过 `MutableStateFlow` 更新（`Atomic`/单协程写者保证一致）；UI 线程不做 IO。
- `ReleaseKite.onForeground()/onBackground()`（由 `ReleaseKiteHost` 生命周期驱动）维护
  `UpdateEnvironment.isAppForeground`，决定下载完成后「自动安装」还是「发通知」。
- 阻塞操作都有超时（API connect/read、下载整体超时、20s 下载看门狗），避免主线程/状态卡死。

## 8. 为什么 core 是纯 JVM

1. **测试即回归**：ZhiBox 的 94 个更新用例已全部迁移为自动化测试：其中 **82 个纯 JVM
   用例**（core：fake store/installer/source + Mock HTTPS，无 Robolectric/真机，秒级跑完），
   其余 12 个是 Android/UI 专属用例（`:releasekite` 的 SharedPrefs 2 个、`:releasekite-compose`
   的弹窗 10 个，均用 Robolectric）。更新逻辑（版本/策略/灰度/状态机）由纯 JVM 用例守住，
   这是它能被持续回归的前提。
2. **边界由编译器强制**：`core` 不引入 Android 依赖，任何误用 `Context`/`PackageManager`
   的代码在编译期就失败，避免平台细节渗透进领域层。
3. **可移植性**：版本比较、Release JSON 解析、灰度分桶、线路选择、下载协议（含 SHA/重定向/
   续传）都只依赖 `java.*`；将来可以复用到桌面端、CI 校验工具或服务端发布检查。
4. **可注入性**：所有 IO 与平台能力以接口/函数参数暴露（`openConnection`、`freeBytes`、
   `clock`、`UpdateStore`…），单测可以构造确定性的失败场景（超时、坏 JSON、SHA 不符、
   历史 FAILED 回放等）。

代价：无法直接在 core 里使用 `Context/PackageManager/WorkManager/Notification`。对策是把
这些能力定义为最小接口（`UpdateEnvironment`/`UpdateInstaller`/`UpdateNotifier`/
`UpdateDownloadScheduler`），Android 实现全部集中在 `:releasekite`，并由
`ReleaseKite.initialize` 完成默认装配。

## 9. 生命周期与初始化

```text
Application.onCreate()
  └─ ReleaseKite.initialize(context, config)   // 只调用一次
       ├─ 读取 packageManager 的 versionName / longVersionCode
       ├─ 组装默认实现（source/downloader/scheduler/store/validator/installer/notifier/environment）
       └─ new UpdateManager(...)

Activity（或根 Composable）
  └─ ReleaseKiteHost()
       ├─ onAppEntered()   // 每进程一次：冷启动检查/缓存展示
       ├─ onAppResumed()   // 回到前台：通知被拒续装、权限返回续装
       ├─ 前后台通知       // ReleaseKite.onForeground()/onBackground()
       └─ 通知权限请求     // Android 13+ POST_NOTIFICATIONS（requestNotificationPermission=true）
```

`ReleaseKiteHost` 必须在应用根布局挂载一次；`ReleaseKite.manager` 只能在 initialize 之后访问。

## 10. 扩展点

- **配置层**：`ReleaseKiteConfig` 覆盖仓库、资产正则、校验/元数据后缀、镜像、UA、超时、
  自动检查/重试间隔、强制缓存时长等（见 [`API_CONTRACT.md`](API_CONTRACT.md) §1.1）。
- **高级装配**：`UpdateManager` 构造器公开，可注入自定义 `UpdateSource`/`UpdateDownloader`/
  `UpdateStore`/`UpdateDownloadScheduler` 等实现（例如企业内部源、不同的存储实现），
  此时不使用 `ReleaseKite.initialize` 的默认装配。
- **UI 定制**：`ReleaseKiteDialogStyle`（形状/容器色）与 `ReleaseKiteActions` 回调；
  也可以不用 `ReleaseKiteHost`，自行根据 `manager.state` 渲染（`ReleaseKiteUpdateDialog`
  是无状态的）。

## 11. 可观测性

- 唯一对外状态是 `UpdateManager.state: StateFlow<UpdateUiState>`：阶段、当前版本、上次检查
  时间、自动检查/通道开关、线路健康、强制逃生状态；
- `UpdateStore.routeHealth()` 记录直连/镜像的成功/失败/冷却，不额外发探测请求；
- 日志经 `UpdateEnvironment.log(...)`，`releasekite` 默认写 Logcat；
- **无遥测、无上报**：`installId` 只在本地用于灰度分桶。

## 12. 已知边界

- 只解析 GitHub Release 的**单 APK 资产**（多 APK 资产视为 `IncompleteRelease`，失败安全）；
- 灰度分桶是确定性的本地抽样，无法证明真实用户覆盖比例（0%/100% 是可验证边界）；
- 强制更新在完全离线时只能「失败 3 次后暂时退出」，无法强制联网；
- GitHub 匿名 API 限流 60 次/小时/IP，客户端不做绕过；
- 不支持增量更新、私有仓库、企业内网源（需自行实现 `UpdateSource`）。
