# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `skills/releasekite/`：AI agent skill，按“接入 / 使用 / 发布 / 维护 / 排障”路由到
  canonical docs，包含硬约束、最小事实和验证清单；
- `scripts/install-skills.sh`：把 canonical skill 安装到 `~/.agents/skills` /
  `~/.claude/skills`（symlink 或 copy）；
- `scripts/test-skills.py` + CI 校验：frontmatter、必备章节、相对链接、secrets/本机路径；
- `AGENTS.md`：仓库内 agent 的 skill 入口指针；README 文档表增加 skills 链接。

## [0.1.0] - 2026-10-30

首个公开版本：从 [ZhiBox](https://github.com/IAmKings/ZhiBox) 抽取的应用内更新库，
行为对齐 ZhiBox v0.1.17 的更新服务（含两轮第三方审查修复）。

### Added

- `releasekite-core`（纯 JVM）：
  - 语义化版本解析/比较（含预发布排序）、`UpdateInfo`/`UpdateFailure`/`UpdatePhase`/
    `UpdateUiState` 模型；
  - GitHub Release 解析（stable/beta）、markdown→纯文本说明（4000 字符上限）、
    `.sha256` 解析；
  - `ReleaseKiteConfig` 全量配置校验（仓库格式、镜像前缀、资产正则、超时/间隔等）；
  - `GitHubUpdateSource`：匿名 GitHub API、响应体上限、404/403(限流)/5xx 分类、
    `-update.json` 拉取（cache-buster + 手动重定向 + 逐跳 host 校验 + 镜像回退 + 坏 JSON 换路）；
  - `HttpUpdateDownloader`：HTTPS + 逐跳 host 白名单、镜像回退、`.part` 跨线路续传、
    流式 SHA-256、磁盘预检、原子落盘；
  - 节流/缓存策略（24h 成功、1h 失败退避、6h 缓存重校验、强制缓存 24h）、
    灰度分桶、强制更新判定、线路健康与冷却；
  - `UpdateManager` 状态机：检查代际、下载代际/WorkRequest 归属、20s 启动看门狗、
    成功去重、冷启动状态保护、后台完成通知与回前台续装、ReleaseUnavailable 处理。
- `releasekite`（Android 适配）：`ReleaseKite.initialize` 门面、WorkManager 后台下载
  Worker/调度器、SharedPreferences 存储、通知（渠道/进度/完成）、FileProvider、
  APK 包名/versionCode/签名校验、系统安装器与未知来源权限引导、前后台跟踪。
- `releasekite-compose`：`ReleaseKiteHost`、无状态 `ReleaseKiteUpdateDialog`、
  `ReleaseKiteUpdateRow`、`ReleaseKiteSettingsSection`；中英双语资源与 testTag；
  Material3 样式可配置（`ReleaseKiteDialogStyle`）。
- `sample`：可运行示例（真实 GitHub 仓库检查 + fake 状态 UI 走查）。
- 文档：`docs/ARCHITECTURE.md`、`docs/INTEGRATION.md`、`docs/RELEASE_PIPELINE.md`、
  `docs/SECURITY.md`、`docs/MIGRATION_ZHIBOX.md`、`docs/TESTING.md`、`docs/API_CONTRACT.md`。
- 发布模板：`.github/workflows/ci.yml`、`samples/github-workflows/release.yml`、
  `samples/release-policy.json`、`jitpack.yml`。

### Security

- 仅 HTTPS；重定向逐跳校验 host 白名单（github.com / objects.githubusercontent.com /
  release-assets.githubusercontent.com）；
- SHA-256 完整性校验（API digest 优先，`.sha256` 资产回退，`requireChecksum` 默认开启）；
- 安装前校验包名、`versionCode` 严格递增、签名证书集合与已安装应用一致；
- 不支持静默安装；始终经系统安装器与用户授权；
- 无遥测、无 token、匿名 API（60 次/小时/IP 限额，不绕过）。

### Fixed（发布前深度审查）

- 失败原因 wire code 改为固定字符串，不再依赖 R8 可能改名的 `failure::class.simpleName`；
- `NoRelease` 撤回强制缓存（新鲜缓存后台复查 + 过期缓存检查两条路径），避免已删除 Release
  的强制提示死循环；
- 下载恢复使用独立 `releasekite_download_target` prefs 按 `workId` 绑定完整 `UpdateInfo`，
  不再把刷新后的检查缓存误当成旧任务身份；旧的、无绑定目标的任务会取消并保留 `.part`；
- `verifyCachedForced` 的 `NoRelease` 分支增加 phase 守卫，下载/校验/待安装期间不再清缓存/覆盖状态；
- 下载状态流增加异常重连，`observe()` 取消操作移出 `Flow.map` 的 await 路径，避免管道静默死亡；
- Android 12+ 后台 FGS 启动受限时降级为无前台服务下载，不再把通知失败误报成网络失败；
- 前后台跟踪改为引用计数，修复多 Activity 重叠时误判后台导致不自动拉起安装器；
- APK 签名校验在 API 28+ `signingInfo==null` 时回退 deprecated `signatures`；
- `checkNow` 在下载/校验/待安装权限期间忽略，更新行同步置灰；
- `releasekite-compose` 以 `api` 暴露 Compose BOM/ui/ui-graphics，修复独立 consumer 编译失败；
- 下载任务 `onRequestId` 回调先做代际校验再持久化 target/提交任务，过期任务不覆盖新目标、不执行 REPLACE；
- `ReleaseKiteHost` 对前台引用做配对归还，多窗口重挂/销毁不会累计或误减引用计数；
- 发布策略 `minSupportedVersion: null` 归一化为缺省值，非法类型由 Python 回归 + CI 拒绝；
- 发布模板支持 `EXPECTED_CERT_SHA256` 强校验签名连续性；R8 使用 9.1.29 classpath 覆盖，
  消除 Kotlin 2.4 metadata 解析警告；
- sample 补 launcher icon/dataExtractionRules；Android 17 移动网络真机覆盖检查/下载/后台/
  force-stop 续传/安全拦截/同签名安装（设备矩阵见 `docs/ACCEPTANCE_2026-10-30.md`）；
- sample release 开启 R8/minify + resource shrink，新增独立 consumer smoke 与 CI release 门禁；
- 文档/测试矩阵同步为 230 例（core 119 / Android 36 / Compose 75 = 94 迁移 + 136 新增）。

### Known limitations

- 不支持增量更新（APK 签名约束）；
- 只支持公开 GitHub 仓库、单个 APK 资产；
- 完全离线时强制更新只能失败 3 次后暂时退出；
- 灰度比例是本地确定性分桶，覆盖比例以 0%/100% 边界与抽样验收为准。
