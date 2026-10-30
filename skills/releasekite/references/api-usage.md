# 使用更新 API

目标：在已经接入的宿主 App 中正确使用 `UpdateManager`、状态机、通道与策略。
完整签名：[API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md)。

## 1. 状态机（决定 UI 该展示什么）

| `UpdatePhase` | UI | 是否可取消 |
|---|---|---|
| `Idle` / `Checking` / `UpToDate` | 无弹窗；设置行内联状态 | — |
| `Available`（普通） | 版本/大小/日期/说明 +「立即更新 / 稍后」 | ✅ |
| `Available`（强制） | 标题「需要更新」，无「稍后」 | ❌ |
| `RolloutPending` | 无弹窗；设置行显示“正在分批推送（N%）” | — |
| `Downloading` / `Verifying` | 进度/校验，无操作按钮 | ❌ |
| `DownloadFailed` | 可读失败原因 +「重试」；强制态受逃生阈值限制 | ✅（强制态受限） |
| `NeedInstallPermission` | 「去设置 / 取消」 | ✅ |

完整失败原因（`UpdateFailure`）与文案映射见 API 契约 §1.3 / Compose 测试；不要在宿主自绘 UI 里
把不同 failure 都显示成“网络错误”。

## 2. Manager 公共操作

```kotlin
val manager = ReleaseKite.manager

manager.onAppEntered()              // 冷启动每进程一次：检查/展示缓存/恢复待安装
manager.onAppResumed()              // 每次回前台：通知权限/安装权限返回后续装
manager.checkNow()                  // 手动检查（下载/校验/待安装权限期间会被忽略）
manager.startDownload()             // Available -> Downloading
manager.retry()                     // DownloadFailed -> 重新下载
manager.dismiss()                   // 可取消状态下关闭弹窗
manager.setAutoCheckEnabled(false)  // 设置页开关
manager.setChannel(UpdateChannel.BETA)
manager.resetRouteHealth()
manager.installDownloadedIfReady()  // 点击通知/回前台后续装
manager.openInstallPermissionSettings()

val state: StateFlow<UpdateUiState> = manager.state
```

约束：

- `onAppEntered()` 有每进程一次的原子守卫，重复调用安全；`onAppResumed()` 每个 Activity/窗口的
  `onResume` 调用；
- `checkNow()` 在 `Downloading`/`Verifying`/`NeedInstallPermission` 期间忽略，避免打断不可取消流程；
- `setChannel(BETA)` 在 `config.betaChannelEnabled=false` 时被忽略；通道真正变化才清缓存/重置节流；
- 所有状态变更都通过 `state` 流；宿主不要自己维护一份 `phase` 副本。

## 3. 通道、灰度与强制

### Beta

- tag 含 `-`（例如 `v1.2.3-beta.1`）才进入 Beta 通道；
- 用户开启「接收预发布版本」后，客户端按 semver 在 `releases?per_page=10` 中选最高；
- Beta 客户端仍会收到更高的正式版；
- 不要把 beta 发成正式 Release，否则 stable 用户会被推送到测试版。

### 灰度

- `-update.json` 的 `rolloutPercent` 为 0–100；
- 客户端用 `sha256(installId + "|" + versionName)` 做确定性分桶；
- 未命中：不弹窗；手动检查显示 `RolloutPending`（“正在分批推送 N%”）；
- 可严格验收的边界是 **0% 和 100%**；中间比例无法从 UI 直接看到分桶值。

### 强制

- `minSupportedVersion` 高于当前版本时进入强制态；
- 强制态不可取消、无「稍后」、绕过分桶与 dismissed；
- 连续下载失败达到 `config.forcedEscapeFailures`（默认 3）后允许“暂时退出”；
- 只对关键版本使用；演练后恢复 policy 并发布新版本，不要依赖客户端缓存过期。

## 4. 配置项语义（最常改的）

| 参数 | 关键语义 |
|---|---|
| `repository` | `owner/repo`，公开 GitHub Release 来源 |
| `apkAssetPattern` | 必须恰好命中一个 APK；建议收紧到 `.*-release-signed\.apk$` |
| `metadataAssetSuffix` | `"-update.json"` 启用灰度/强制；`null` 等价 100%/非强制 |
| `requireChecksum` | 默认 true；无 digest 且无 `.sha256` 时拒绝下载（`NoChecksum`） |
| `downloadMirrors` | `https://` 且 `/` 结尾；直连失败按序回退（APK 与元数据都支持） |
| `autoCheckIntervalMs` | 成功检查后的节流；默认 24h |
| `cacheRevalidateIntervalMs` | 普通/灰度缓存重新校验；默认 6h |
| `forcedCacheMaxAgeMs` | 强制缓存过期后先联网校验；默认 24h |
| `downloadTimeoutMs` / `downloadStartTimeoutMs` | 下载整体超时 / WorkManager 启动看门狗 |
| `forcedEscapeFailures` | 强制下载连续失败多少次后允许暂时退出 |

## 5. 宿主自绘 UI（不挂 Host）

必须自行补齐：

```kotlin
manager.onAppEntered()
manager.onAppResumed()
ReleaseKite.onForeground()
ReleaseKite.onBackground()

val state by manager.state.collectAsState()
ReleaseKiteUpdateDialog(
    state = state,
    actions = ReleaseKiteActions(
        onDownload = manager::startDownload,
        onDismiss = manager::dismiss,
        onRetry = manager::retry,
        onOpenInstallSettings = manager::openInstallPermissionSettings,
    ),
)
```

如果不确定，用 `ReleaseKiteHost()` + `ReleaseKiteUpdateRow(manager)`，不要手写状态机。

## 6. 安装/下载恢复语义

- 下载状态由 WorkManager 唯一任务驱动；进程重启后按 `workId` 恢复，`.part` 保留；
- 通知权限被拒不会导致下载失败；后台完成时 Host 回前台会自动续装；
- 取消系统安装后不会在下次启动自动重装（`consumedWorkId`/`installedVersion` 记录）；
- 强制更新的“暂时退出”只到下一次进入 App/重试，不改变服务端 policy；
- 旧版本没有 target 绑定的任务会被取消并保留半包；不要手工删除 target prefs 来“修复”。

## 7. 何时升级/回退

- 修 bug/新增 API：发新的 patch/minor，保持同一签名证书；
- 灰度/强制策略变更：改 `release-policy.json` → 新 tag/新 Release，避免覆盖同名资产；
- 发现版本有问题：把 `rolloutPercent` 调 0 并发布新 policy，或删除 Release（客户端会
  `ReleaseUnavailable` 并清强制缓存），然后恢复 `releases/latest`；
- 不要用相同 tag 重新上传不同 APK。

## 8. 失败排查入口

状态/失败码与原因见 [troubleshooting.md](troubleshooting.md)；完整实现细节见
[API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md) 和
[安全模型](https://github.com/IAmKings/ReleaseKite/blob/main/docs/SECURITY.md)。

