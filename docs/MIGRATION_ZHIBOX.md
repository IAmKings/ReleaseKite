# 从 ZhiBox 迁移：提交 → 不变量 → 测试

> 源工程：[ZhiBox](https://github.com/IAmKings/ZhiBox)（MIT，见 [`NOTICE`](../NOTICE)）。
> ReleaseKite 的行为基线是 **ZhiBox v0.1.17**（更新服务两轮第三方审查修复后的状态）。
> 本文是迁移的**可审计对照**：每个影响更新行为的源提交都必须能在 ReleaseKite 找到
> 对应的不变量与自动化测试。API 以 [`API_CONTRACT.md`](API_CONTRACT.md) 为准。

## 0. 迁移范围与统计

| 源测试类（ZhiBox） | 用例数 | ReleaseKite 测试类 | 迁移 | 新增 |
|---|---|---|---|---|
| `AppUpdateManagerTest` | 33 | `:releasekite-core` `AppUpdateManagerTest` | 33 | +8 |
| `UpdateApiAndDownloaderTest` | 14 | `:releasekite-core` `UpdateApiAndDownloaderTest` | 14 | +3 |
| `UpdateModelsTest` | 23 | `:releasekite-core` `UpdateModelsTest` | 23 | 0 |
| `UpdateRolloutTest` | 6 | `:releasekite-core` `UpdateRolloutTest` | 6 | 0 |
| `UpdateRoutesTest` | 6 | `:releasekite-core` `UpdateRoutesTest` | 6 | 0 |
| `UpdateStorePersistenceTest` | 2 | `:releasekite` `SharedPrefsUpdateStoreTest` | 2 | +4 |
| `AppUpdateDialogTest` | 10 | `:releasekite-compose` `ReleaseKiteUpdateDialogTest` | 10 | +12 |
| **合计** | **94** | | **94** | **132** |

> 迁移用例的运行方式分布：**82 个纯 JVM**（`:releasekite-core`：Manager 33 + API/Downloader 14 +
> Models 23 + Rollout 6 + Routes 6）+ **2 个 Robolectric**（`:releasekite` `SharedPrefsUpdateStoreTest`）
> + **10 个 Robolectric/Compose**（`:releasekite-compose` `ReleaseKiteUpdateDialogTest`）。
> 三个模块当前自动化总计 **226 = 94 迁移 + 132 新增**（core 118 / Android 35 / Compose 73）。
> 新增部分包含 T6 深审补齐的取消规则回归（15）、R1/R2/R3 发布前深审的并发/安全/韧性回归
> （URL 负向 3、verifyCachedForced 竞态、collector 恢复、FGS 降级、target 绑定/恢复、
> NeedInstallPermission 点击、前后台引用计数等）。

迁移原则：

- **用例名保持可搜索**：除去除厂商名的 `signedApk`→`apk` 等泛化，保留 ZhiBox 用例名，
  便于用 git history/旧文档交叉检索；
- **平台替换不降低断言强度**：Robolectric/Context 替换为 fake environment/store/scheduler，
  网络替换为自签 Mock HTTPS（`releasekite-core/src/test/.../testing/TestTlsSupport.kt`），
  断言仍覆盖原用例的状态与副作用；
- **新增用例只增不减**：ReleaseKite 的泛化能力（配置项、可选 checksum 等）与 UI 细节
  （testTag/双语/失败文案全覆盖）另有新增测试，见 [`TESTING.md`](TESTING.md) §2。

## 1. 提交 → 不变量 → 测试对照表

> 测试类前缀：`M` = `:releasekite-core` `AppUpdateManagerTest`；`API` =
> `:releasekite-core` `UpdateApiAndDownloaderTest`；`MODELS` = `UpdateModelsTest`；
> `ROLLOUT` = `UpdateRolloutTest`；`ROUTES` = `UpdateRoutesTest`；`CFG` =
> `UpdateConfigGeneralizationTest`；`STORE` = `:releasekite` `SharedPrefsUpdateStoreTest`；
`CONTRACT` = `:releasekite` `UpdateDownloadContractTest`；`SCHED` = `:releasekite`
`WorkManagerUpdateDownloadSchedulerTest`；`DIALOG` = `:releasekite-compose`
`ReleaseKiteUpdateDialogTest`；`ROW` = `ReleaseKiteUpdateRowTest`；`SETTINGS` =
`ReleaseKiteSettingsSectionTest`。

| # | 提交（日期） | 源提交主题 | ReleaseKite 必须保持的不变量 | 对应用例（ReleaseKite） |
|---|---|---|---|---|
| 1 | `15425ff`（2026-10-27） | 应用内更新：全局检查/下载/安装 + 公开仓库 + 设置「检查更新」 | 冷启动自动检查（成功 24h / 失败 1h 节流）；手动检查取消在途任务并改用新结果；只提示严格更新；5 态弹窗与取消规则（下载/校验不可取消，可用/失败/权限可关闭）；SHA-256（API digest 优先、`.sha256` 回退）+ 包名/versionCode/签名校验；未知来源权限引导与返回续装；release body→纯文本（≤4000） | `MODELS::parseVersion_toleratesPrefixSuffixAndMissingSegments`、`semverPrereleaseOrdering`、`compareVersions_normalizesSegments`、`parseLatestRelease_picksApkAndApiDigest`、`parseLatestRelease_fallsBackToShaAssetWhenDigestMissing`、`parseSha256File_acceptsHashAndFilenameFormat`；`M::autoCheckSkippedWithin24h`、`autoCheckFindsNewVersionAndShowsDialogState`、`manualCheckCancelsInFlightAutoCheckAndUsesNewestResult`、`manualCheckUpToDateStaysInline`、`downloadWorkSuccessVerifiesAndLaunchesInstaller`、`apkValidatorFailureBlocksInstaller`、`dismissRecordsVersionAndSuppressesAutoPrompt`、`pendingInstallResumesWhenAppReturnsToForeground`；`API::api_parsesLatestReleaseResponse`、`downloader_downloadsVerifiesAndRenames`、`downloader_rejectsShaMismatchAndDeletesPart`、`downloader_refusesWithoutAnyChecksum`、`api_404MapsToNoRelease`；`DIALOG::availableShowsVersionNotesAndActions`、`downloadingShowsProgressAndHidesActionButtons`、`verifyingHasNoActionButtons`、`failedShowsReadableReasonAndRetry`、`permissionShowsSettingsEntry`、`idleShowsNoDialog`、`availableExposesContractTestTags`、`updateNowClickInvokesDownload`、`laterClickInvokesDismiss`、`notesEmptyShowsLocalizedPlaceholder`、`viewFullReleaseNotesOpensHtmlUrl` |
| 2 | `da561e5`（2026-10-27） | 直连 CDN 失败回退 GitHub 代理镜像 | 直连 `Network`/`Timeout` 时按 `downloadMirrors` 顺序回退；镜像 host 必须在配置白名单内且 URL 必须包含本仓库 release 资产路径；所有线路均可续传（`allowResume=true`，`Network`/`Timeout` 保留 `.part`；ac810ad 起跨线路也保留）；TLS/重定向阶段 `SocketTimeout` 统一映射为 `Timeout`；完整性由 SHA-256 + 安装前签名/包名校验兜底 | `API::downloader_triesRemainingRouteWhenFirstIs404`、`downloader_allRoutes404MapsToReleaseUnavailable`；`ROUTES::directFirstWhenHealthy`、`directCoolingMovesToEnd`、`mirrorsSortedByFailuresThenLatency`、`failureCooldownAfterThreshold`、`successResetsHealth`、`allCoolingStillReturnsAllRoutes`；`CFG::configUserAgentAndMirrorsArePassedThrough` |
| 3 | `8de29e1`（2026-10-27） | 二期：后台下载/通知、自动检查开关、Beta、灰度/强制、线路健康 + 设置搜索 | WorkManager 后台下载 + 通知 + 跨进程续传；自动检查开关关闭后不联网也不展示缓存；Beta 通道 semver 预发布排序（`-beta.N < -rc.N < 正式`）且 prerelease 不进 stable latest；灰度 `sha256(installId|versionName)` 确定性分桶，未命中只显示分批推送；强制更新绕过 dismissed/灰度且不可取消、连续失败 3 次允许暂时退出；线路健康冷却；切换通道清缓存/节流 | `M::forcedUpdateBypassesRolloutAndDismiss`、`rolloutNotEligibleShowsPending`、`forcedUpdateAllowsEscapeAfterThreeDownloadFailures`、`backgroundDownloadSendsNotificationAndSkipsInstaller`；`ROLLOUT::cohortIsDeterministicAndBounded`、`eligibilityHonorsRolloutPercent`、`forcedWhenCurrentBelowMinSupported`、`parseUpdateMetaValidatesSchemaAndRange`、`parseUpdateMetaRejectsInvalidRollout`、`releaseParserReadsUpdateMetaAssetUrl`；`ROUTES` 全部 6 例；`MODELS::launchPolicy_skipsWhenAutoCheckDisabled`、`launchPolicy_cachedRolloutMissReturnsPending`、`launchPolicy_doesNotShowDismissedCachedVersion`、`parseReleaseList_picksHighestSemverIncludingPrerelease`、`parseReleaseList_stableListReturnsStableChannel`、`parseLatestRelease_rejectsPrerelease`；`DIALOG::settingsRowShowsCurrentVersionAndStates`、`forcedAvailableHidesLaterButton`、`forcedDownloadFailedOnlyAllowsRetryUntilEscape`、`forcedDownloadFailedAllowsEscapeAfterThreshold`；`ROW::rolloutPendingShowsRolloutPercent` 等；`SETTINGS::autoCheckSwitchToggleInvokesCallback`、`betaSwitchOnInvokesBetaChannel`、`betaSwitchOffInvokesStableChannel`、`betaSwitchHiddenWhenBetaChannelDisabled`；`CONTRACT::inputDataMapsAllContractKeysAndRoundTrips`；`SCHED::enqueueBindsRequestIdBeforeSubmitAndKeepsNoConstraints` |
| 4 | `ac810ad`（2026-10-27） | 跨线路续传修复 | 直连失败回退镜像时**保留** `.part` 并尝试 Range 续传；镜像不支持 Range 时丢弃半包重下；`.part` 续传只接受 `206 + Content-Range` 起点一致 | `API::downloader_parsesContentRangeStart`、`downloader_triesRemainingRouteWhenFirstIs404`、`downloader_rejectsShaMismatchAndDeletesPart`；`M::startupCleanupRemovesOldApksButKeepsNewerAndPartial` |
| 5 | `68d06f0`（2026-10-28） | 更新元数据重定向修复 + 通知降级续装 | `-update.json` 资产 URL 302 到 GitHub CDN 时必须跟随（否则灰度/强制被静默忽略）；初始 host 白名单保留，每一跳校验；通知被拒/后台完成后回到前台自动续装 | `API::api_metaRedirectIsFollowedWithHostValidation`、`api_appliesUpdateMetaFromReleaseAsset`；`M::pendingInstallResumesWhenAppReturnsToForeground` |
| 6 | `9a8fbea`（2026-10-28） | `update.json` 缓存绕过（灰度即时生效） | 元数据请求追加 cache-buster 并发送 `Cache-Control: no-cache`，避免同标签资产被覆盖后 CDN 返回旧值（实现保留；测试覆盖元数据拉取路径与 result 应用） | `API::api_appliesUpdateMetaFromReleaseAsset`；运维侧经验见 [`RELEASE_PIPELINE.md`](RELEASE_PIPELINE.md) §7 |
| 7 | `992e614`（2026-10-28） | `update.json` 镜像回退 | 直连元数据不可达 → 按镜像前缀回退；单线路坏 JSON 继续尝试镜像；全部失败才回退默认值（100%/非强制），不阻断更新 | `API::api_fallsBackToMirrorWhenMetaDirectUnreachable`、`api_badMetaJsonFallsBackToMirror`；`ROLLOUT::parseUpdateMetaValidatesSchemaAndRange`、`parseUpdateMetaRejectsInvalidRollout` |
| 8 | `6110dfc`（2026-10-28） | 修复自更新完成后误弹「更新失败」 | 处理成功结果以当前版本为真相：目标版本已安装 → 静默清理 APK/缓存、取消旧任务、不拉起安装器；校验通过后立即取消任务；迟到失败回调若已安装则静默 | `M::staleWorkSuccessAfterAppAlreadyUpdatedDoesNotShowFailure`、`staleWorkSuccessEvenIfInstallersWereIdleIsSilent`、`historicalFailedWorkInfoAfterRestartDoesNotShowFailure`、`startupCleanupRemovesOldApksButKeepsNewerAndPartial` |
| 9 | `21ba14b`（2026-10-28） | 冷启动缓存丢失 forced 导致强制可取消 | 缓存展示前重算 forced：强制缓存优先于 dismissed 与「自动检查关闭」；`ShowCached` 分支必须带 `forced=true` | `MODELS::launchPolicy_forcedCachedOverridesDismissedAndAutoCheckOff`；`M::cachedForcedUpdateOnLaunchIsNotDismissible` |
| 10 | `4ff01e7`（2026-10-28） | 正式 v0.1.14：强制缓存 + 历史 FAILED 回放修复 | 合并第 9 条 + 历史 FAILED/CANCELLED 终端状态按进程会话过滤（本进程未跟踪的下载不弹失败）；同会话失败仍提示；终端处理后取消唯一任务、重试重新入队 | `M::historicalFailedWorkInfoAfterRestartDoesNotShowFailure`、`historicalFailedWorkInfoWithoutUpdateIsSilentlyIgnored`、`cachedForcedUpdateOnLaunchIsNotDismissible` |
| 11 | `038699a`（2026-10-29） | 正式 v0.1.15：缓存元数据持久化 | 缓存持久化 `minSupportedVersion` / `rolloutPercent` / `channel` 与 `cache_meta_schema`；旧缓存标记未知并冷启动强制联网刷新一次；展示缓存前重新判定 forced 与灰度资格 | `STORE::saveCachedRoundTripsRolloutMinSupportedAndChannel`、`legacyCacheWithoutMetaSchemaIsMarkedUnknown`；`MODELS::launchPolicy_legacyCacheWithoutMetaTriggersCheck`、`launchPolicy_staleForcedCacheTriggersCheck`、`launchPolicy_staleNormalCacheTriggersCheck`、`launchPolicy_staleIneligibleCacheTriggersCheck` |
| 12 | `0ea927d`（2026-10-29） | v0.1.16：强制缓存失效与版本下架 | 强制缓存展示后后台校验（服务端无该版本/不再强制 → 清缓存并关闭）；强制缓存 24h 过期先联网、失败回退缓存（离线降级）；APK/`.sha256` 404/410 → `ReleaseUnavailable`：清缓存/强制态/任务，可关闭弹窗，「重试」改为重新检查；`cached_at` 持久化 | `M::forcedCachedUpdateIsClearedWhenServerNoLongerOffersIt`、`forcedCachedUpdateStaysWhenVerificationFails`、`releaseUnavailableClearsForcedCacheAndIsNotForced`；`API::downloader_404MapsToReleaseUnavailable`、`downloader_allRoutes404MapsToReleaseUnavailable`；`STORE::metaSchemaAndTimestampArePersistedThenCleared`；`MODELS::launchPolicy_staleForcedCacheTriggersCheck` |
| 13 | `5f598bb`（2026-10-29） | 内测 v0.1.17-beta.1：更新服务收尾 | 普通/灰度缓存 6h 重新校验；自动检查关闭时强制缓存不联网；404/410 必须遍历所有线路才判下架，403/429/5xx 换路；镜像重定向允许 GitHub CDN；`.sha256` 也支持镜像；下载整体超时 5min（保留续传）；安装后清理缓存；元数据逐跳 host 校验；安装防重入；下架后重置节流；多 APK 资产失败安全；匿名限流不绕过 | `MODELS::parseLatestRelease_rejectsMultipleApkAssets`、`parseLatestRelease_rejectsMissingApkAsset`、`launchPolicy_staleNormalCacheTriggersCheck`；`M::startupCleanupRemovesOldApksButKeepsNewerAndPartial`；`API::downloader_triesRemainingRouteWhenFirstIs404`、`downloader_allRoutes404MapsToReleaseUnavailable`；`ROUTES` 全部用例 |
| 14 | `4c2a56c`（2026-10-29） | 内测 v0.1.17-beta.2：下载不可卡死保护 | 移除下载任务的 Network/Storage 约束（避免永久 ENQUEUED）；Worker 网络预检；Scheduler 暴露 WAITING；20s 启动看门狗超时后取消任务进入可重试失败；捕获 `enqueue` 异常；混合线路失败（404 + 网络超时）不误判下架/不清缓存 | `M::waitingWorkStateTimesOutInsteadOfStickingAtZero`、`noWaitingCallbackStillTimesOutFromImmediateWatchdog`、`enqueueFailureDoesNotLeaveDialogStuck`；`SCHED::enqueueBindsRequestIdBeforeSubmitAndKeepsNoConstraints`；`API::downloader_triesRemainingRouteWhenFirstIs404` |
| 15 | `a253ff9`（2026-10-29） | 内测 v0.1.17-beta.3：第三方审查 8 项修复 | 异步入队等待 WorkManager Operation；`DownloadWorkState.workId` 任务归属（旧 CANCELLED/FAILED 不影响新重试）；冷启动恢复「是否接管安装」；`Downloading/Verifying/NeedInstallPermission` 不被检查/缓存展示覆盖；`Network/Timeout` 保留 `.part`；成功去重持久化 `consumedWorkId`/`installerLaunchedVersion`；仅全部线路 404/410 才 `ReleaseUnavailable`；元数据坏 JSON 继续换路；后台 Worker 独立持久化并自行发完成通知 | `M::oldCancelledWorkIdDoesNotCancelRetriedDownload`、`coldStartDoesNotOverwriteRestoredDownloadingState`、`coldStartDoesNotOverwriteRestoredInstallPermissionState`、`consumedSuccessResultIsNotInstalledTwice`、`duplicateSuccessKeepsPendingInstallAndPermissionState`、`backgroundDownloadSendsNotificationAndSkipsInstaller`；`API::api_badMetaJsonFallsBackToMirror`、`downloader_allRoutes404MapsToReleaseUnavailable` |
| 16 | `746b297`（2026-10-29） | 内测 v0.1.17-beta.4：第二轮审查修复 | `downloadGeneration` 校验入队回调/await 返回/异常/看门狗；入队真正提交前同步绑定 WorkRequest id（早到 RUNNING 可关闭看门狗）；成功去重区分「结果已处理」与「已交给安装器」（重复 SUCCEEDED 不清待安装/权限态）；新下载创建期忽略旧任务成功/失败回调 | `M::lateOldEnqueueResultCannotOverrideNewDownload`、`earlyRunningAfterIdBindingCancelsWatchdog`、`oldSuccessDuringNewEnqueueIsIgnored`、`duplicateSuccessKeepsPendingInstallAndPermissionState`、`consumedSuccessResultIsNotInstalledTwice`；`SCHED::enqueueBindsRequestIdBeforeSubmitAndKeepsNoConstraints` |
| 17 | `733f78a`（2026-10-29） | 补强迟到入队回归测试（仅测试变更） | 测试必须独立持有旧 gate；旧任务完成后被取消；旧 SUCCEEDED 不得安装、新 FAILED 必须被接收，证明 `activeWorkId` 未被旧回调覆盖（该测试语义在 ReleaseKite 中保留） | `M::lateOldEnqueueResultCannotOverrideNewDownload`、`oldSuccessDuringNewEnqueueIsIgnored` |

> 其它相关提交（不改变更新服务行为，未单列）：`9cfb51a`/`e664493` 等版本号与发布记录提交、
> `ead2d03` 人工验收手册（迁移为 [`TESTING.md`](TESTING.md) 的真机清单）、
> `dca0763`/`a044445`/`d9db3a2` 临时演练工作流（不迁移，模板见
> `samples/github-workflows/release.yml`）。

## 2. 文件映射

| ZhiBox（源） | ReleaseKite（目标） |
|---|---|
| `core/update/UpdateModels.kt`（版本/模型/状态/解析） | `releasekite-core/.../Version.kt`、`UpdateModels.kt`、`UpdateMeta.kt`、`GitHubReleaseParser.kt` |
| `core/update/UpdateApi.kt` | `releasekite-core/.../UpdateSource.kt`（`UpdateSource` + `GitHubUpdateSource`） |
| `core/update/UpdateDownloader.kt` | `releasekite-core/.../UpdateDownloader.kt` |
| `core/update/UpdatePreferences.kt` | `releasekite-core/.../UpdateStore.kt`（接口 + `InMemoryUpdateStore`）<br>`releasekite/.../internal/SharedPrefsUpdateStore.kt`（Android 实现） |
| `core/update/UpdatePolicy.kt` | `releasekite-core/.../UpdatePolicy.kt` |
| `core/update/UpdateRollout.kt` | `releasekite-core/.../UpdateRollout.kt` |
| `core/update/UpdateRoutes.kt` | `releasekite-core/.../UpdateRoutes.kt` |
| `core/update/UpdateDownloadScheduler.kt` + `AppUpdateDownloadWorker.kt` | `releasekite-core/.../UpdateSchedule.kt`（`DownloadRequest`/`DownloadWorkState`/接口）<br>`releasekite/.../internal/WorkManagerUpdateDownloadScheduler.kt`、`ReleaseKiteDownloadWorker.kt`、`UpdateDownloadContract.kt` |
| `core/update/UpdateInstaller.kt` | `releasekite-core/.../UpdateInstaller.kt`（`InstallResult`/`UpdateInstaller`/`ApkValidator`）<br>`releasekite/.../internal/AndroidApkValidator.kt`、`AndroidUpdateInstaller.kt` |
| `core/update/UpdateNotifications.kt` | `releasekite-core/.../UpdateNotifier.kt` + `releasekite/.../internal/AndroidUpdateNotifier.kt` |
| `core/update/AppUpdateManager.kt` | `releasekite-core/.../UpdateManager.kt` + `releasekite/.../internal/ReleaseKiteForegroundTracker.kt`、`AndroidUpdateEnvironment.kt` |
| `UpdateConfig`（`BuildConfig` 直连） | `releasekite-core/.../ReleaseKiteConfig.kt`（全部配置化，构造期校验） |
| `ui/update/AppUpdateDialog.kt` | `releasekite-compose/.../ReleaseKiteUpdateDialog.kt`、`ReleaseKiteUpdateRow.kt`、`ReleaseKiteSettingsSection.kt`、`ReleaseKiteActions.kt`、`ReleaseKiteDialogStyle.kt`、`ReleaseKiteFormatting.kt`、`ReleaseKiteTestTags.kt` |
| `MainActivity.kt` 根 `Box` 挂载 | `releasekite-compose/.../ReleaseKiteHost.kt`（生命周期/权限/弹窗统一挂载）+ `sample/` 示例 |
| `app/src/main/res/xml/update_file_paths.xml` | `releasekite/src/main/res/xml/releasekite_file_paths.xml` |
| `app/src/main/AndroidManifest.xml` 更新相关节点 | `releasekite/src/main/AndroidManifest.xml`（权限/FileProvider/FGS 类型由库清单合并） |
| `app/src/main/res/values/strings.xml`（`update_*`） | `releasekite-compose/src/main/res/values/strings.xml`（默认英文）与 `values-zh-rCN/strings.xml`（`releasekite_*`） |
| `release/update-policy.json` | `samples/release-policy.json`（宿主复制到自己的 `release/update-policy.json`） |
| `.github/workflows/release.yml`（ZhiBox 专用） | `samples/github-workflows/release.yml`（通用模板，无硬编码包名）+ `.github/workflows/ci.yml` |

## 3. 有意差异（泛化决定）

以下差异是**设计决定**，不是遗漏；每条都有对应测试或文档：

| 差异 | 原因 | 行为影响 |
|---|---|---|
| `apkAssetPattern` 配置化（默认 `.*\.apk$`） | 库不能假设宿主的资产命名；ZhiBox 的 `-release-signed.apk` 只是推荐约定 | 0 个或多个匹配 → `IncompleteRelease`（失败安全）；`sample` 显式配置 ZhiBox 的 pattern 以演示真实仓库 |
| `requireChecksum` 可关闭 / `checksumAssetSuffix` 可配置 | 部分宿主没有 `.sha256` 资产；离线内网源可能没有 digest | 默认仍开启；关闭后仅做大小/安装校验（文档标注为不推荐）；对应 `CFG::optionalChecksumAllowsReleaseWithoutAnyHash` 等 |
| `metadataAssetSuffix = null` 可关闭元数据 | 不需要灰度/强制的宿主少一次网络请求 | 等价于始终 100%/非强制；对应 `CFG::metadataCanBeDisabled` |
| core **不再写中文兜底文案** | 库需要中英双语；文案属于 UI 层 | `notes` 为空时保持空串，由 compose 资源显示本地化占位（`DIALOG::notesEmptyShowsLocalizedPlaceholder`、`DIALOG_ZH`） |
| 删除 ZhiBox 的 `companion get(Context)` 单例与 `debugInjectMockState` | 单例由 `ReleaseKite` 门面持有；调试 mock 属于应用层职责 | 示例改用 fake `UpdateInfo`/`UpdateUiState` 直接渲染无状态弹窗（`sample/FakeDemoStates.kt`） |
| 新增 `UpdateNotifier` / `UpdateEnvironment` 抽象 | core 纯 JVM，不能直接依赖 Android 通知/Context | 通知与版本/工作目录能力由 `:releasekite` 实现；ZhiBox 的行为（渠道/进度/完成/权限）在 Android 实现中保持 |
| `downloadFilePrefix` 可配置，并新增派生属性 `sanitizedDownloadFilePrefix` | 资产文件名前缀不应绑定 `zhibox-`；落盘名需要可预测且安全 | 下载文件名 `"<sanitizedPrefix>-<version>.apk"`；非法字符替换为 `_`，清洗后为空回退 `releasekite`（`CFG::customFilePrefixIsSanitizedAndUsedForDownloadName`） |
| Beta 偏好归一化与禁用守卫（`UpdateManager` init / `setChannel`） | 配置是最终事实来源：库配置 `betaChannelEnabled=false` 时不得让旧偏好停留在 BETA | init 时若 `store.channel == BETA` 则归一化为 `STABLE`；`setChannel(BETA)` 在禁用时被忽略（状态/持久化均不改变），切换通道仍清缓存/节流/dismiss |
| FileProvider authority 泛化 + 独立 provider 子类 | 库不能固定宿主包名；直接声明 `androidx.core.content.FileProvider` 会与宿主自有 provider 在 manifest merger 中按 `android:name` 冲突 | authority 固定 `${applicationId}.releasekite.updates`；库使用 `io.github.releasekite.internal.ReleaseKiteFileProvider`，宿主可同时声明自己的 FileProvider；路径仍只暴露 `cacheDir/releasekite/updates/` |
| 包名/versionCode/签名校验对**宿主自身**而非固定 `com.zhibox.memory` | 库只允许升级宿主应用自身 | `sample` 下载真实 ZhiBox APK 会被拒绝（预期安全拦截） |
| 默认英文 + `values-zh-rCN` | 库的国际化默认面向更广宿主 | ZhiBox 只有中文；ReleaseKite 保持语义一致、文案可本地化 |
| 去掉 DESIGN 设计令牌耦合，改 Material3 默认样式 + `ReleaseKiteDialogStyle` | 库不能依赖宿主设计系统 | 弹窗结构/取消规则/按钮行为与 ZhiBox 一致；颜色/圆角可配 |
| 未迁移「设置搜索」 | ZhiBox 产品功能，与更新库无关 | 无 |
| 未迁移 debug mock 注入、临时演练工作流 | 属于应用调试/运维流程 | 由 `sample` fake 演示与发布模板文档替代 |

## 4. 行为不变量（回归时优先看）

1. **取消规则**：`Downloading`/`Verifying` 不可取消；普通 `Available`/`DownloadFailed`/
   `NeedInstallPermission` 可关闭；强制态仅 `escapeAllowed` 后可退出；
2. **代际**：检查任务代际、下载代际、`workId` 归属，旧回调一律不得覆盖新状态；
3. **成功去重**：`consumedWorkId` + `installerLaunchedVersion`，重复 SUCCEEDED/取消安装后重启
   不重复拉起安装器、不清待安装状态；
4. **冷启动保护**：下载中/校验中/待安装权限不被缓存展示或检查覆盖；历史 FAILED 不回放；
5. **缓存 TTL**：普通/灰度 6h 重校验、强制 24h 先联网、旧缓存（无 meta schema）强制刷新一次；
6. **安全链**：HTTPS → 逐跳 host 白名单 → SHA-256 → 包名/versionCode/签名 → 系统安装器；
7. **失败安全**：多 APK 资产、元数据非法、未知 schema 等异常输入不得导致错误安装或错误强制。

## 5. 迁移验证方式

```bash
# core（纯 JVM）：82 个迁移用例 + 36 个新增 = 118 例
./gradlew :releasekite-core:test

# Android 适配（Robolectric）：SharedPrefs 迁移 2 + 新增 33 = 35 例
./gradlew :releasekite:testDebugUnitTest

# Compose UI（Robolectric）：Dialog 迁移 10 + 新增 63 = 73 例
./gradlew :releasekite-compose:testDebugUnitTest

# 真实仓库端到端（可选，会访问 GitHub；示例下载会被签名校验拒绝，属预期）
./gradlew :sample:assembleDebug
```

逐提交回归时，可先按上表找到「不变量」，再运行对应测试类；若需要对照 ZhiBox 原始用例，
源文件位于 ZhiBox 仓库 `app/src/test/java/com/zhibox/memory/{core,ui}/update/`。
