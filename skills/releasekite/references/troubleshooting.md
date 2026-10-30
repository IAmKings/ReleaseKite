# 排障：接入、下载、安装与发布

目标：把“看起来失败”快速分类到**网络/资产/客户端策略/系统权限/构建**，并给出可验证的下一步。
完整失败码与状态语义见 [API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md) 与
[测试/验收](https://github.com/IAmKings/ReleaseKite/blob/main/docs/TESTING.md)。

## 1. 先拿到事实

```bash
# 宿主机上：
adb logcat -d | grep -iE 'ReleaseKite|WorkManager|ApkValidator|installer'
adb shell dumpsys package <applicationId> | grep -E 'versionName|versionCode|POST_NOTIFICATIONS'
adb shell run-as <applicationId> ls -l cache/releasekite/updates

# 发布侧：
gh release view <tag> --repo <owner/repo> --json assets,isPrerelease,url
gh release download <tag> --repo <owner/repo> --pattern '*.sha256' --dir /tmp/check
```

优先确认：**当前版本/可用版本/phase/failure**，而不是直接改代码。

## 2. 检查更新失败

| 症状/失败码 | 可能原因 | 处理 |
|---|---|---|
| `Http 403` / `rateLimited=true` | GitHub 匿名 API 限额（60/hr/IP） | 等待限额恢复；不要用多个 IP/代理绕过；降低自动检查频率 |
| `Http 404` / `NoRelease` | 仓库没有 Release / 版本被删 / tag 不存在 | 确认公开仓库与 Release；发布新版本；删除演练后检查 `releases/latest` |
| `Network` / `Timeout` | 直连 GitHub 资产/API 超时（尤其国内移动网络） | 配 `downloadMirrors`；换网络复测；不要加大超时掩盖 |
| `Parse` | Release JSON/元数据异常、HTML 错误页 | 检查 GitHub API 是否返回异常；`-update.json` 是否合法 JSON；坏元数据客户端会回退镜像/默认 |
| `IncompleteRelease` | APK 资产 0 个或多个；`apkAssetPattern` 不匹配；unsigned 混入 | 收紧 pattern（如 `.*-release-signed\.apk$`）；确保恰好一个签名 APK |
| `NoChecksum` | 既无 Release API digest 也无 `.sha256`，且 `requireChecksum=true` | 上传 `.sha256` 作为回退，或放宽 `requireChecksum`（不推荐） |
| `RolloutPending` | 灰度未命中（正常状态，不是错误） | 0%/100% 边界验收；中间比例无法从 UI 看分桶值 |
| 明明有更新但不提示 | 当前版本不低、Beta 关闭、灰度未命中、24h 节流、dismissed | 对照 phase/触发方式；手动检查；确认 semver/versionCode |

### 元数据/灰度/强制不生效

- 确认 `ReleaseKiteConfig.metadataAssetSuffix = "-update.json"`（sample 默认是 `null`）；
- 确认 Release 真的上传了 `-update.json`，且 `schema=1`、`rolloutPercent`/`minSupportedVersion` 类型正确；
- 确认资产名包含 `-update.json` 且 `apkAssetPattern` 仍能命中唯一 APK；
- 受限网络下元数据也走镜像；如果 APK 能下但灰度不生效，重点查元数据镜像/404；
- 覆盖同名资产后 CDN 可能返回旧值：用新 tag/新 Release 调整策略，别依赖 `--clobber` 立即生效。

## 3. 下载失败/续传

| 症状 | 原因 | 处理 |
|---|---|---|
| `ChecksumMismatch` | CDN/镜像返回旧 APK；上传后覆盖；镜像缓存 | 核对官方 `.sha256`；换镜像/新版本；不要跳过校验 |
| 下载到 100% 后失败 | 校验/空间/安装包问题 | 看 failure 是 Checksum/ApkInvalid/InsufficientSpace |
| 中断后 `.part` 还在但不继续 | 下载任务被取消/强制停止；需要重新触发 | 重新点更新/检查；`.part` 保留用于 Range 续传，不要手工删 |
| 永远 0% | 网络预检/镜像首包失败；WorkManager 启动看门狗 | 看 logcat Worker/Network；换网络/镜像；确认未被系统省电限制 |
| 下载并发冲突 | 唯一 WorkManager 任务互斥 | 不要同时点多个入口；等当前任务结束 |
| 后台下载被系统杀 | 省电/后台限制；FGS 受限 | 允许后台运行/电池优化白名单；Android 12+ FGS 受限时库会降级为无 FGS 下载 |

## 4. 安装/权限

| 症状 | 原因 | 处理 |
|---|---|---|
| `NeedInstallPermission` | 未知来源未授权 | `openInstallPermissionSettings()` 引导；宿主不要假定已授权 |
| `InstallFailed` | 安装器无法启动/被拦截 | 检查 `REQUEST_INSTALL_PACKAGES`、FileProvider、系统安装器可用；看 logcat |
| `ApkInvalid` | 包名不同、versionCode 不增、签名不一致 | 使用同一签名证书；versionCode 严格递增；不要用 debug 包覆盖 release |
| 安装器打开空白/闪退 | authority 冲突/Provider 被移除 | 保留 `io.github.releasekite.internal.ReleaseKiteFileProvider`，不要复用 authority |
| 通知点击不续装 | Activity 非 `singleTop` 或未处理 extra | 用 `singleTop`；读取 `io.github.releasekite.extra.INSTALL_UPDATE`；Host 回前台也会兜底 |
| 通知不显示但下载完成 | Android 13+ 通知权限被拒 | 这是允许路径；Host 回前台自动续装；不要误判下载失败 |

## 5. 构建/R8/发布

| 症状 | 原因 | 处理 |
|---|---|---|
| consumer R8 `Missing class` | WorkManager 被移除/改 runtimeOnly；consumer rules 未生效 | 保留默认 WorkManager 初始化；不要 `tools:node="remove"`；参考 sample 的 R8 门禁 |
| `IncompleteRelease` 在 release 才出现 | release 产物名不带 `-release-signed.apk` | 统一资产命名或调整 `apkAssetPattern` |
| Actions 找不到 signed APK | signingConfig 未接 Secrets / 只产出 unsigned | 按模板接 `RELEASE_STORE_*`；排除 `*-unsigned.apk` |
| tag/versionName 校验失败 | tag 与模块 `versionName` 不一致 | 改成 `v<versionName>` 并重新打 tag（不要复用旧 tag） |
| 证书连续性失败 | keystore 换了 / `EXPECTED_CERT_SHA256` 不匹配 | 恢复原 keystore；不要轮换签名；修复后发新版本 |
| GitHub Packages 409 | 同版本不可覆盖 | 发新版本；不要重发同版本 |
| GitHub Packages 401/403 | token 缺 `read:packages`；仓库/包权限不对 | 配置带 `read:packages` 的 token；组织包检查可见性 |
| JitPack 坐标解析失败 | tag 不存在/构建失败/Gradle module metadata group 不一致 | 看 JitPack build.log；确认 tag 与 `com.github.IAmKings.ReleaseKite:<module>:<tag>`；当前 group 已与 JitPack 对齐 |

## 6. 发布后“用户还看到旧策略”

1. 确认 `releases/latest` 指向新 tag、Release 不是 draft、asset 名正确；
2. 确认客户端拿到的是新 `-update.json`（不要只看 GitHub 网页）；
3. 普通缓存 6h、强制缓存 24h 会重新校验；CDN 可能滞后；
4. 换网络/清应用缓存复测；仍不对再用新 tag 发布策略；
5. 不要把“CDN 缓存”误判为客户端 bug，也不要用 `--clobber` 作为常规发布手段。

## 7. 需要停下来问人的情况

- 要更换签名证书、要“静默安装”、要绕过 GitHub 匿名限流/鉴权；
- 要发布到 Maven Central（需要 Sonatype/GPG/命名空间凭据）；
- 要在生产 policy 上把 `minSupportedVersion` 设为会强制大量用户的值；
- 资产/证书/密钥状态不明，或 CI 日志含 secrets。

这些不是可以自动猜测的配置；先确认再做。

