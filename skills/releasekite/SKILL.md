---
name: releasekite
description: 处理 ReleaseKite 的接入、使用与发布：Android/Compose 依赖（JitPack/GitHub Packages）、Application 初始化与更新 UI、下载/安装 API、GitHub Release 资产与 Actions 发布流程，以及本库自身的测试、JitPack/GitHub Packages 发版和排障。
---

# ReleaseKite

本 skill 是 ReleaseKite 的**路由与约束层**：只保留任务分流、不可违反的事实和验证方式；
具体 API、代码与发布步骤在 `references/` 中按需加载，完整细节以 canonical docs 为准。

## 先判断任务

| 用户意图 | 先读 |
|---|---|
| 把 ReleaseKite 接入 Android/Compose 宿主 App、加依赖、初始化、挂 Host/设置页 | [`references/integration.md`](references/integration.md) |
| 使用更新 API、理解状态机、配置 Beta/灰度/强制、处理下载/安装状态 | [`references/api-usage.md`](references/api-usage.md) |
| 发布宿主 App：GitHub Actions、tag、`-update.json`、灰度/强制/Beta、发布后自检 | [`references/release-workflow.md`](references/release-workflow.md) |
| 维护本仓库：构建、测试、lint、JitPack/GitHub Packages 发版 | [`references/maintainer.md`](references/maintainer.md) |
| 失败、报错、异常行为、发布事故 | [`references/troubleshooting.md`](references/troubleshooting.md) |

判断规则：

- **接入/使用**是“宿主 App 如何依赖本库并调用它”；**发布**是“宿主 App 如何产出可更新的 GitHub Release”；
  **维护**是“修改 ReleaseKite 仓库本身并发布库制品”。三者不要混用。
- 如果用户没有给出 `owner/repo`、包名、模块名、签名/Secrets 现状，先问清楚，不要猜。
- 如果任务同时涉及接入与发布，先完成接入，再按发布 reference 配置资产。

## 硬约束

1. **版本源是公开 GitHub Release**：单仓库、单 universal APK、匿名 API（60 次/小时/IP），不做服务端、不绕过限流。
2. **签名是长期身份**：后续 APK 必须与已安装应用使用同一证书；换 key 会导致 `ApkValidator` 拒绝安装。
   release keystore/口令只放 CI Secrets，禁止打印、提交或写进日志。
3. **不静默安装**：必须经系统安装器与用户授权；`REQUEST_INSTALL_PACKAGES` 只用于拉起授权/安装器。
4. **tag 与版本一致**：tag 为 `v<versionName>`，`versionCode` 严格递增；不要覆盖已发布 tag 的 APK。
5. **资产契约**：Release 必须命中**恰好一个** APK；`requireChecksum=true` 时至少要有 Release API digest
   或 `.sha256` 资产之一（`.sha256` 是推荐的回退）；`-update.json` 可选，灰度/强制必须提供；
   纯 Beta 发布本身不需要 `-update.json`（只有 Beta 也要灰度/强制时才需要）。
6. **示例配置不是生产配置**：`sample/` 故意设置 `metadataAssetSuffix = null`（避免 0.0.1 被强制锁死）。
   接入真实 App 时使用 `"-update.json"`，除非明确不需要灰度/强制。
7. **网络受限时配置镜像**：直连 GitHub Release 资产可能超时；`downloadMirrors` 必须为 `https://` 且以 `/` 结尾。
8. **多进程宿主只在主进程初始化/挂 Host**；FileProvider authority `${applicationId}.releasekite.updates`
   不要复用，也不要重复声明库的 provider。
9. **验证先于宣布成功**：接入要能 `assembleDebug`；发布要核对资产、SHA、签名、tag 和 CI；不能用“看起来成功”代替检查。

## 最小事实

| 模块 | 何时使用 |
|---|---|
| `releasekite-compose` | 需要现成更新弹窗/设置区（推荐；api 传递 `releasekite` + `releasekite-core`） |
| `releasekite` | 只要 Android 适配（WorkManager/安装/通知/存储），UI 自绘 |
| `releasekite-core` | 纯 JVM 引擎，自建平台适配或工具链使用 |

当前版本：`v0.1.0`（JitPack tag）/ `0.1.0`（GitHub Packages artifact 版本）。

```kotlin
// JitPack
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:v0.1.0")

// GitHub Packages（消费端需 read:packages token）
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
```

环境：JDK 21、minSdk 24、compileSdk/targetSdk 36；`releasekite-compose` 通过 api 暴露 Compose BOM
约束与 ui/ui-graphics，但 material3/activity-compose 仍由宿主声明并对齐。
初始化顺序：`ReleaseKite.initialize(...)` **先于**任何 `ReleaseKite.manager` / `ReleaseKiteHost`。

## Canonical sources

安装到用户目录后，使用绝对 URL 读取完整文档：

- [README](https://github.com/IAmKings/ReleaseKite/blob/main/README.md)
- [接入指南](https://github.com/IAmKings/ReleaseKite/blob/main/docs/INTEGRATION.md)
- [发布流水线与资产契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/RELEASE_PIPELINE.md)
- [API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md)
- [安全模型](https://github.com/IAmKings/ReleaseKite/blob/main/docs/SECURITY.md)
- [测试与验收](https://github.com/IAmKings/ReleaseKite/blob/main/docs/TESTING.md)
- [宿主发布模板](https://github.com/IAmKings/ReleaseKite/blob/main/samples/github-workflows/release.yml)
- [release-policy 示例](https://github.com/IAmKings/ReleaseKite/blob/main/samples/release-policy.json)
- [可运行示例](https://github.com/IAmKings/ReleaseKite/tree/main/sample)

仓库内路径：文档在 `docs/`，示例在 `sample/`，发布模板在 `samples/`，脚本在 `scripts/`。
安装到用户目录后，`sample/`、`docs/` 等路径以 clone 后的仓库为准，不假设当前工作目录。

## 完成前验证

- **接入**：宿主 `assembleDebug` 通过；初始化早于 manager；Host 已挂载；Release 资产契约与配置匹配。
- **发布**：tag/versionName 一致；APK 唯一且签名连续；`.sha256` 正确；需要灰度/强制时 `-update.json` 存在且 schema 正确；
  Actions 的 draft→publish 成功；发布后按 reference 做自检。
- **维护本仓库**：按 `docs/TESTING.md` 跑门禁（单测、Debug/Release、lint、publish、consumer smoke），
  不把“部分测试通过”当成发布通过；不修改历史 tag。
- **任何改动**：最后报告改了什么、验证命令、未覆盖的边界；不要把 secrets、keystore、token 写进 repo 或对话输出。

