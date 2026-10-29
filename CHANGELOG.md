# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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

### Known limitations

- 不支持增量更新（APK 签名约束）；
- 只支持公开 GitHub 仓库、单个 APK 资产；
- 完全离线时强制更新只能失败 3 次后暂时退出；
- 灰度比例是本地确定性分桶，覆盖比例以 0%/100% 边界与抽样验收为准。
