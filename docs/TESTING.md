# 测试与验收

> 自动化矩阵 + 门禁命令 + 真机验收清单。自动化测试是回归的硬门禁；真机清单用于发布前
> 验证无法在 JVM/Robolectric 中覆盖的链路（系统安装器、WorkManager 跨进程、通知权限、
> 镜像网络、OEM 安装器）。

## 1. 测试分层

| 层 | 位置 | 技术 | 覆盖 |
|---|---|---|---|
| 纯 JVM 领域层 | `releasekite-core/src/test` | JUnit4 + `kotlinx-coroutines-test` + 自签 Mock HTTPS（`testing/TestTlsSupport.kt`） | 版本/解析/策略/灰度/线路/下载/状态机/代际/看门狗 |
| Android 适配层 | `releasekite/src/test` | Robolectric + WorkManager test + `kotlinx-coroutines-test` | SharedPreferences 持久化、Worker 数据契约、调度器、门面初始化 |
| Compose UI | `releasekite-compose/src/test` | Robolectric + `createComposeRule` | 弹窗各 phase/按钮/取消规则/testTag、设置行、设置区、中英文案、格式化 |
| 真机 | 宿主应用（或接入发布链路的 sample） | 人工 | 系统安装器、未知来源权限、通知、弱网/镜像、跨进程续传、OEM 差异 |

## 2. 自动化矩阵（当前 226 个用例 = 94 个迁移 + 132 个新增）

### 2.1 迁移用例（94，逐条对应 ZhiBox 行为，详见 [`MIGRATION_ZHIBOX.md`](MIGRATION_ZHIBOX.md)）

> 运行方式分布：**82 个纯 JVM**（`:releasekite-core`）+ **2 个 Robolectric**（`:releasekite` `SharedPrefsUpdateStoreTest`）+ **10 个 Robolectric/Compose**（`:releasekite-compose` `ReleaseKiteUpdateDialogTest`）。

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `:releasekite-core` `AppUpdateManagerTest` | 33（+8 新增） | 状态机：节流、手动检查代际、下载成功/失败、安装、dismiss、强制/灰度、历史 FAILED、成功去重、看门狗、迟到入队 |
| `:releasekite-core` `UpdateApiAndDownloaderTest` | 14（+3 新增） | API 200/404/403 限流、元数据直连/重定向/镜像/坏 JSON、下载镜像回退、SHA 不符、无 checksum、Content-Range、URL 安全负向 |
| `:releasekite-core` `UpdateModelsTest` | 23 | 版本解析/预发布排序、Release 解析（digest/.sha256/缺资产/多资产）、markdown 清洗、启动策略 |
| `:releasekite-core` `UpdateRolloutTest` | 6 | 分桶确定性/边界、灰度资格、强制判定、元数据 schema 校验 |
| `:releasekite-core` `UpdateRoutesTest` | 6 | 直连优先/冷却、镜像排序、失败冷却、成功清零 |
| `:releasekite` `SharedPrefsUpdateStoreTest` | 2（+4 新增） | 缓存元数据 save→read 往返、旧缓存无 schema 标记未知、target prefs 独立绑定/恢复 |
| `:releasekite-compose` `ReleaseKiteUpdateDialogTest` | 10（+12 新增） | 弹窗 5 态、强制/失败逃生、设置行状态、testTag、空 notes、按钮回调 |

### 2.2 新增用例（132，ReleaseKite 泛化、加固与 UI 细节）

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `:releasekite-core` `ReleaseKiteConfigTest` | 7 | 配置校验：仓库格式、镜像格式、资产规则非空、间隔/阈值、文件前缀清洗、默认值对齐契约 |
| `:releasekite-core` `InMemoryUpdateStoreTest` | 6 | 测试 store 的接口契约（默认值/可初始化/缓存时间/routeHealth 替换） |
| `:releasekite-core` `UpdateConfigGeneralizationTest` | 12 | 自定义 APK pattern/checksum/metadata 后缀、可选 checksum（大小校验）、自定义超时/间隔、UA/镜像透传 |
| `:releasekite-core` `UpdateApiAndDownloaderTest` 新增部分 | 3 | 安全链负向：非 https / 非白名单 host / 非本仓库 release 路径 → `IncompleteRelease`（建连前拒绝） |
| `:releasekite-core` `AppUpdateManagerTest` | 8 | 后台完成通知；NoRelease 撤回强制缓存（新鲜/过期两路径）；恢复下载失败保持强制；禁用 Beta 不展示旧 Beta 缓存；冷启动与恢复下载身份竞争；`verifyCachedForced` 下载期 phase 守卫；状态管道异常恢复；看门狗/代际加固回归 |
| `:releasekite` `SharedPrefsUpdateStoreTest` | 4 | `cache_meta_schema`/`cached_at` 持久化清理、`cached_meta_url`、target prefs 绑定与不串版本 |
| `:releasekite` `UpdateDownloadContractTest` | 6 | Worker input/output 键、失败固定字符串编解码（逐类型 wire code）、缺 version 快速失败、常量对齐契约 |
| `:releasekite` `WorkManagerUpdateDownloadSchedulerTest` | 11 | 入队前同步 workId、无约束、精确取消、legacy 无目标取消、target 跨调度器/缓存刷新恢复、WAITING/RUNNING/SUCCEEDED target 映射、flow 异常不终止、并发 enqueue |
| `:releasekite` `ReleaseKiteFacadeTest` | 1 | `initialize` 幂等、读取宿主版本名/版本号 |
| `:releasekite` `AndroidApkValidatorSignatureTest` | 3 | API 28+ `signingInfo` 优先、缺失时回退 deprecated `signatures`、无签名信息返回空 |
| `:releasekite` `AndroidUpdateInstallerTest` | 2 | 安装权限 API 守卫、FileProvider/Intent 路径 |
| `:releasekite` `ReleaseKiteDownloadWorkerForegroundTest` | 3 | Android 12+ FGS 启动异常降级、不把通知失败误报成 Network、取消语义 |
| `:releasekite` `ReleaseKiteForegroundTrackerTest` | 3 | 多 Activity 引用计数：A/B 重叠保持前台、全部 stop 后后台、clamp 不为负 |
| `:releasekite-compose` `ReleaseKiteUpdateDialogTest` 新增部分 | 12 | testTag、空 notes 占位、无 publishedAt、按钮回调、失败文案全覆盖 |
| `:releasekite-compose` `ReleaseKiteUpdateRowTest` | 13 | 行内 phase 文案、下载/校验/待安装权限不可点、点击触发检查 |
| `:releasekite-compose` `ReleaseKiteSettingsSectionTest` | 8 | 两个开关回调、Beta 隐藏、重置线路、线路健康文案 |
| `:releasekite-compose` `ReleaseKiteUpdateDialogZhTest` | 10 | 中文资源：弹窗/强制/失败/权限/行/设置区 |
| `:releasekite-compose` `ReleaseKiteFormattingTest` | 5 | 字节/日期格式化、进度百分比边界 |
| `:releasekite-compose` `ReleaseKiteUpdateDialogCancellationTest` | 15 | 取消规则闭环：DialogProperties 语义、不可取消负向交互、可取消正相对照（返回键/点外部真实事件） |

## 3. 门禁命令

```bash
# 0) 本机 JDK 21（macOS）：系统 Java 25 不被 Gradle 8.14.3 支持
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 1) 三个模块单测（CI 同款）
./gradlew :releasekite-core:test :releasekite:testDebugUnitTest :releasekite-compose:testDebugUnitTest

# 2) Debug 示例构建
./gradlew :sample:assembleDebug

# 3) Release 门禁：库 AAR + sample（R8/minify + resource shrink）
./gradlew :releasekite:assembleRelease :releasekite-compose:assembleRelease :sample:assembleRelease

# 4) lint（error 级阻断；debug + release 变体）
./gradlew :releasekite:lintDebug :releasekite-compose:lintDebug :sample:lintDebug
./gradlew :releasekite:lintRelease :releasekite-compose:lintRelease :sample:lintRelease

# 5) 发布到本地 Maven（验证坐标/产物）
./gradlew :releasekite-core:publishToMavenLocal :releasekite:publishToMavenLocal :releasekite-compose:publishToMavenLocal
```

CI（`.github/workflows/ci.yml`）在每次 push/PR 执行：JDK 21 + Android SDK + Gradle wrapper 校验 →
三模块单测 → `:sample:assembleDebug` → debug lint → 库 `assembleRelease` + `:sample:assembleRelease`
（R8/资源裁剪门禁）→ release lint → 三模块 `publishToMavenLocal`；任一步失败会上传
`build/reports/**`、`build/test-results/**` 与构建产物报告。

**本地门禁通过标准**：以上命令全部 `BUILD SUCCESSFUL`，无失败/错误级 lint。不要求
`clean`（构建缓存在 CI 有独立环境；本地增量即可）。

## 4. 真机验收清单

> 改编自 ZhiBox [`APP_UPDATE_MANUAL_QA.md`](https://github.com/IAmKings/ZhiBox/blob/main/docs/product/APP_UPDATE_MANUAL_QA.md)。
> 库本身没有「版本」，因此端到端安装验收需要在**宿主应用**（或接入发布链路的 sample）上做：
> `sample` 默认只能验证「检查 → 弹窗 → 下载 → SHA/包名/签名拦截」，无法完成自更新安装
> （versionCode=1 且没有历史版本）。若要在 sample 上走完整链路，请把
> `sample/build.gradle.kts` 的 applicationId/version 接到发布模板，连续发布两个版本。

### 4.0 放行标准

1. 普通更新全链路（检查→弹窗→下载→校验→安装→版本正确）在 ≥2 台真机、2 种网络通过；
2. 灰度 **0% 不弹窗 / 100% 弹窗** 两个边界确定通过（中间比例抽样记录）；
3. 强制更新：不可取消、安装后解除、连续失败 3 次可暂时退出、重启后仍强制；
4. Beta：stable 看不到 prerelease，开启 Beta 后可见；
5. 受限网络下 APK 与 `-update.json` 都能镜像回退（灰度/强制仍生效）；
6. 通知被拒时后台下载继续、回前台自动续装；
7. 0 个 P0 缺陷；P1 缺陷有记录与规避方案。

### 4.1 环境与证据

- 设备 A：正常网络（可直连 GitHub），Android 13+，可清空数据；
- 设备 B：受限网络（直连 GitHub 资产超时），不同厂商/系统；
- 设备 C：可清空备用机（强制更新/失败逃生/降级）；
- 工具：`adb`（安装/截图/logcat/dumpsys）、`gh` CLI、Release 页面；
- 记录：型号/系统/网络/起始→目标版本/APK SHA-256/时间/截图或录屏；
- 异常日志：`adb logcat -d | grep ReleaseKite`、
  `adb shell dumpsys notification --noredact`、
  `adb shell dumpsys package <applicationId>`。

### 4.2 发布物准备（由发布负责人）

- 用正式流水线构建：tag 与 `versionName` 一致、release keystore 与历史版本一致；
- 内测建议发 prerelease（tag 含 `-`），避免影响 stable 用户；
- 产物必须包含：APK、`.sha256`、`-update.json`（mapping 可选）；
- 三种演练场景各自独立 tag（详见 [`RELEASE_PIPELINE.md`](RELEASE_PIPELINE.md) §6）：
  普通 Beta（`rolloutPercent=100`、`minSupportedVersion` 低）、灰度 drill（0/100）、
  强制 drill（`minSupportedVersion=目标版本`）；
- 演练后删除临时 Release/tag、恢复 policy，确认 `releases/latest` 未被污染。

### 4.3 普通更新（P0）

| 用例 | 步骤 | 预期 |
|---|---|---|
| R-01 首次启动自动检查 | 安装较旧稳定版，清除应用数据后首次启动 | 24h 内自动查询并弹窗（版本/大小/日期/说明）；`releasekite_update` 写入时间戳 |
| R-02 24h 节流与开关 | 关闭弹窗→多次冷启动；设置区关闭「自动检查」→冷启动 | 24h 内不重复自动弹窗；关闭后不联网、不展示缓存；手动检查仍可用 |
| R-03 手动检查与取消 | 设置行/设置区连续点「检查更新」 | 取消在途任务并以最新结果刷新；`UpToDate` 内联「已是最新」，失败内联可重试 |
| R-04 前台下载 + 安装 | 点「立即更新」→ 观察进度 → 安装 | 下载/校验不可取消（返回键无效）；进度/大小正确；校验后拉起系统安装器；安装后版本=目标版本、数据保留、无签名错误 |
| R-04b 离线点更新（继承 ZhiBox v0.1.17-beta.2 看门狗/网络预检语义） | 飞行模式点「立即更新」 | 快速进入可重试的「网络不可用」，不得永久停留「正在下载 0%」；恢复网络重试可续传 |
| R-05 后台下载 + 通知 | 开始下载后 Home/锁屏 | 通知栏进度；完成后通知「更新已下载」；点通知进入应用并拉起安装器 |
| R-06 跨进程续传 | 下载中 `adb shell am force-stop <applicationId>`，重新进入 | `.part` 续传（进度不归零），最终 SHA/签名通过；若走镜像续传仍生效 |
| R-07 未知来源权限 | 系统关闭「安装未知应用」→ 完成下载 | 弹「需要安装权限」→「去设置」→ 授权返回后自动续装，无需重新下载 |
| R-08 失败与安全拦截 | 下载中断网；构造错误 SHA/换签名 APK | 可读失败原因 + 可重试；SHA 不符/签名不符/包名不符均拒绝安装并提示安装包无效 |
| R-09 设置行/设置区状态 | 逐 phase 观察设置行 | 状态内联正确；下载/校验中行不可点；自动检查/Beta 开关持久化 |
| R-10 自更新后误弹「更新失败」回归（继承 ZhiBox v0.1.12 修复） | 连续两个版本自更新完成后重启 | 不出现「更新失败」；显示「已是最新」；旧 SUCCEEDED 被静默清理 |

### 4.4 Beta 通道（P0）

| 用例 | 步骤 | 预期 |
|---|---|---|
| B-01 stable 不可见 prerelease | 关闭 Beta，只有 `vX.Y.Z-beta.N` 时手动检查 | 显示「已是最新」；`releases/latest` 不返回 prerelease |
| B-02 开启 Beta 可见 | 打开「接收预发布版本」→ 手动检查 | 弹窗展示当前→beta；可下载安装（同签名） |
| B-03 Beta → 正式版 | 正式版 semver 高于 beta 时，Beta 客户端检查 | 能收到正式版；关闭 Beta 后不再显示 beta |

### 4.5 灰度（P0 边界）

| 用例 | 步骤 | 预期 |
|---|---|---|
| G-01 rollout=0 | 发布 rollout 0 的 drill，手动检查 | 设置行显示「正在分批推送（0%）」，**无弹窗**、不自动下载；多次检查一致；不写 dismissed |
| G-02 rollout=100 | 新建 rollout 100 的 release，手动检查 | 正常弹窗、可下载安装 |
| G-03 强制优先于灰度 | `rolloutPercent=0` + `minSupportedVersion=目标版本` | 仍弹强制更新（不可取消） |
| G-04 中间比例抽样（P1） | rollout=10/50，≥5 台全新安装 | 命中弹窗/未命中只提示分批推送；不要求精确比例，但不得出现「未命中却弹窗下载」等反向错误 |
| G-05 灰度缓存刷新（继承 ZhiBox v0.1.17 缓存 TTL） | rollout=0 检查后改 100（或等 6h TTL）再看 | 自动/手动重新检查后能变为可更新，不永久停留在 pending；调低后 TTL 到期不再弹 |

### 4.6 强制更新（P0，仅可清空设备）

| 用例 | 步骤 | 预期 |
|---|---|---|
| F-01 强制弹窗 | policy `minSupportedVersion=目标版本`，手动检查 | 标题「需要更新」+ 重要修复提示；无「稍后」；返回键/点外部不关闭；绕过 dismissed 与灰度 |
| F-02 强制下载安装 | 点「立即更新」→ 下载→校验→安装 | 安装后版本=目标；再次检查显示最新 |
| F-03 失败逃生与重启 | 连续制造 3 次下载失败；点击「暂时退出」后重启 | 第 3 次后出现「暂时退出」；重启后仍弹**不可取消**的强制弹窗（缓存 forced 不丢失）；未失败时无退出入口 |
| F-04 元数据异常 | 删除/破坏 `-update.json`，或写非法 `minSupportedVersion` | 回退 100%/非强制（普通更新），不得误强制/锁死 |
| F-05 恢复 | 删除 drill Release/tag、恢复 policy、清数据 | 不再强制；latest 正常 |

### 4.7 镜像/受限网络（P0 至少一台）

| 用例 | 步骤 | 预期 |
|---|---|---|
| N-01 APK 镜像回退 | 直连 GitHub 资产超时（国内网络） | 自动镜像回退；最终 SHA/签名通过，安装成功 |
| N-02 `-update.json` 镜像回退 | 同网络下检查灰度/强制版本 | 元数据仍生效（不因直连超时回退默认值） |
| N-03 线路诊断 | 设置区查看「更新线路」/重置线路后重查 | 显示直连/镜像健康状态；重置后重新排序；无额外探测请求 |

### 4.8 通知被拒 / 续传 / 历史状态（P1）

| 用例 | 步骤 | 预期 |
|---|---|---|
| N-04 通知权限拒绝 | Android 13+ 拒绝通知→后台下载 | 下载继续且无通知；回到前台自动校验并拉起安装器 |
| H-01 历史 FAILED 回放 | 下载失败后杀进程重启 | 历史 FAILED 被静默忽略（不重复弹失败）；同会话失败仍正常提示 |
| H-02 冷启动状态保护 | 下载中/校验中/待安装权限时重启 | 状态不被「检查更新」或缓存展示覆盖；安装器可恢复 |
| H-03 重复 SUCCEEDED | 安装器已拉起/取消安装后重启 | 不重复拉起安装器；待安装/权限状态不被清空 |
| H-04 取消系统安装后重启 | 系统安装器点取消 → 重启应用 | 不自动再次拉起安装器（`installerLaunchedVersion` 生效）；手动检查可重新提示 |

### 4.9 证据记录模板

| 用例 | 设备/系统/网络 | 起始→目标 | 操作 | 结果 | 证据 |
|---|---|---|---|---|---|
| R-01 | A/Android 14/Wi‑Fi | 1.2.0→1.2.1 | 清数据首启 | 自动弹窗/版本正确 | 截图 |
| G-01 | B/Android 14/移动 | 1.2.1→1.2.2-qa.1 | 手动检查 | 分批推送 0%、无弹窗 | 截图 |
| F-01 | C/Android 13/Wi‑Fi | 1.2.1→1.2.2-force.1 | 手动检查 | 需要更新、不可取消 | 录屏 |
| N-01 | B/Android 14/移动 | 1.2.0→1.2.1 | 下载 | 镜像回退 + SHA/签名通过 | 截图/logcat |

归档到发布记录；P0 用例全过 + P1 无阻塞缺陷才可放行。

## 5. 常见坑（来自 ZhiBox 实战 + 本库泛化）

- **不要只覆盖同名 `-update.json` 后立刻验证**：CDN 边缘可能返回旧值（客户端有 cache-buster
  + 6h 重校验，但仍建议新建 Release/资产）；
- **Beta tag 必须含 `-`**：否则 `releases/latest` 会把 Beta 当正式版；
- **灰度/强制基线版本要足够新**：很老的宿主版本可能不解析 `-update.json`；
- **厂商安装器差异**：MIUI/OPPO 可能多「未知来源」「风险确认」步骤，预留操作时间；
- **通知被拒不是失败**：下载继续，回前台自动续装，需要单独验证；
- **强制状态会缓存**：不要在不可清空设备做强制演练；
- **force-stop ≠ Home**：Home/锁屏 WorkManager 继续；force-stop 会取消任务，重进后恢复续传；
- **降级不覆盖**：versionCode 降低会被系统拒绝；历史版本测试先卸载；
- **示例下载会被拒绝**：`sample` 下载真实 ZhiBox APK 时包名/签名不符属于预期安全拦截；
- **`assembleDebug` 不 clean**：本地迭代直接增量构建即可；CI 环境全新构建；
- **release 变体也要跑**：sample 的 `assembleRelease` 开启 R8/minify + 资源裁剪，
  是库 consumer rules/WorkManager Worker 在混淆下可用的回归门禁（不能只测 debug）；
- **示例 demo 关闭元数据**：sample 的 `metadataAssetSuffix = null` 是防止 `0.0.1` 命中
  真实仓库 `minSupportedVersion` 而被强制锁死；真实接入不要照抄。

## 6. 当前自动化基线（0.1.0）

- `:releasekite-core:test`：118 例（线性 JVM，秒级）；
- `:releasekite:testDebugUnitTest`：35 例（Robolectric/WorkManager）；
- `:releasekite-compose:testDebugUnitTest`：73 例（Robolectric Compose，中英双语）；
- 合计 226 例，0 失败为门禁。

> 用例数随实现演进可能增加；以测试源码中的 `@Test` 计数与 CI 报告为准。
