# 安全模型

> ReleaseKite 的威胁模型假设「工作目录、网络中间人、镜像服务、GitHub 仓库凭据」都可能
> 出问题，唯一可信的锚点是**已安装应用自己的签名证书**。任何修改 APK 的尝试都会在安装前
> 被系统签名校验与 `ApkValidator` 拒绝。

## 1. 安全链（按执行顺序）

```text
① 版本发现    API 只走 HTTPS api.github.com；仓库必须 public，不做 token 注入
② 资产选择    browser_download_url 必须以 https://github.com/ 开头；资产名匹配配置正则
③ 校验值      SHA-256 来自 API digest（优先）或同前缀 .sha256 资产；requireChecksum 默认 true
④ 传输        手动跟随重定向 ≤5 跳；每一跳 HTTPS + host 白名单；镜像仅可作前缀回退
⑤ 落盘        流式 SHA-256 与期望值逐字节比对；不一致删除 .part 并报 ChecksumMismatch
⑥ 安装前校验  ApkValidator：包名 == 当前应用 && versionCode > 当前 && 签名证书集合一致
⑦ 交付安装    FileProvider 只暴露 cacheDir/releasekite/updates；系统安装器；无静默安装
```

### 1.1 传输层

- **只允许 HTTPS**：API、APK、`.sha256`、`-update.json` 全部不允许明文；
- **逐跳 host 白名单**：`github.com`、`objects.githubusercontent.com`、
  `release-assets.githubusercontent.com`（GitHub 资产 302 链）；不使用
  `HttpURLConnection` 的自动跳转，避免被重定向到未校验 host；
- **重定向上限 5 跳**：超过即失败，防止重定向环/放大；
- **镜像威胁模型**：镜像前缀（如 `https://ghproxy.net/`）由宿主配置，镜像 host 从前缀解析并
  校验；镜像只处理公开 Release 资产（URL 必须包含本仓库资产路径），**镜像被投毒也无法通过**
  SHA-256 与安装前签名校验。镜像不能用于私有仓库；
- **元数据同样受约束**：`-update.json` 也逐跳校验 + 支持镜像回退，坏 JSON/不可达时回退默认
  值（100%/非强制），不会阻断正常更新，也不会因元数据缺失误强制。

### 1.2 完整性

- **SHA-256**：优先使用 GitHub API release asset 的 `digest` 字段；缺失时回退同前缀
  `.sha256` 资产。`requireChecksum = true`（默认）时两者都没有 → `NoChecksum` 拒绝下载；
- 下载完成后**整文件流式校验**，再原子 `rename`，失败删除 `.part`；`.part` 续传只接受
  `206 + Content-Range` 起点一致，否则丢弃半包重下；
- 已知大小用于磁盘预检（`apkSize * 2 + 50MB`）与 UI 展示，不作为唯一完整性依据。

### 1.3 安装前校验（最后防线）

`ApkValidator` 在发起系统安装器**之前**校验：

| 检查 | 不通过的结果 | 作用 |
|---|---|---|
| 包名 == 当前应用 `packageName` | `ApkInvalid` | 防止把别的应用的 APK 装成「更新」 |
| `versionCode > 当前 versionCode` | `ApkInvalid` | 防止降级/重放安装 |
| 签名证书 SHA-256 集合 == 已安装应用 | `ApkInvalid` | 防止换签名/伪造包；没有 release 私钥无法伪造 |

任何一项失败都只提示「安装包无效」并保留在应用内，不进入系统安装器。
这也是示例应用（`sample`）下载真实 ZhiBox APK 时**必然被拒绝**的原因：包名与签名都不同，
属于预期安全拦截，不是缺陷。

### 1.4 安装交付

- 通过库自带 `FileProvider`（authority `${applicationId}.releasekite.updates`）以
  `content://` 把 APK 交给系统安装器，只暴露 `cacheDir/releasekite/updates/`；
- 不申请额外的存储权限，不写公共目录；
- `REQUEST_INSTALL_PACKAGES` + Android 8+「安装未知应用」由用户显式授权；
- 不做静默安装/后台自动安装：始终需要用户在系统安装器确认。

## 2. 版本与降级

- 客户端只提示**严格更新**（远端 semver 大于当前）；
- 预发布排序：`x.y.z-beta.N < x.y.z-rc.N < x.y.z`；stable 通道不会拿到 prerelease
  （GitHub `releases/latest` 天然排除 prerelease，Beta 通道由用户显式开启）；
- `versionCode` 必须递增，否则安装前校验拒绝；降级只能卸载重装（系统也会拒绝覆盖降级）；
- `minSupportedVersion` 只影响「是否强制提示」，**不是安全边界**：它来自发布侧元数据，
  被篡改最多让用户少一次强制提示，真正阻止旧版本继续联网/读写的是宿主自己的业务策略。

## 3. 隐私

- 不做遥测、不上报安装 ID/设备信息/使用统计；
- 网络请求只有：GitHub API 查询、Release 资产下载（含镜像）；对 GitHub 暴露的信息与普通浏览器
  访问相同（IP、User-Agent）；
- `installId` 是本地随机 UUID，仅用于灰度分桶，永不离开设备；
- 更新状态存于两个 SharedPreferences：`releasekite_update`（节流/缓存/线路健康/待安装记录）与
  `releasekite_download_target`（本次下载的 `UpdateInfo` + `workId` 绑定，用于跨进程恢复下载身份）；
  后者只含公开的 Release 元数据（版本、说明、URL、SHA），不含用户数据。示例应用
  `allowBackup=false`；宿主如需备份请排除这两个文件（见 [`INTEGRATION.md`](INTEGRATION.md) §6.3）；
- 通知只包含版本号与「更新已下载」，不含敏感内容。

## 4. GitHub 匿名 API 限流

- 客户端**不带 token**，使用 GitHub 匿名 API，限额约 **60 请求/小时/IP**；
- 请求策略已尽量节省配额：冷启动成功后 24h 才再查、失败 1h 退避、手动检查才会绕过；
  同进程内检查任务有代际取消，避免快速连点叠加请求；
- 403/429 归类为限流失败（`UpdateFailure.Http(code, rateLimited = true)`），展示可重试提示；
- 不实现 token/代理绕过限流；需要更高频率时应自行实现 `UpdateSource`（例如企业内网版本服务）。

## 5. 商店合规提醒（重要）

`REQUEST_INSTALL_PACKAGES` 属于敏感权限，Google Play 对「应用自更新」有严格政策：

- **GitHub / 自有渠道侧载分发**：本库的预期场景，可以正常使用；
- **Google Play 分发**：Play 政策限制应用通过自下载 APK 更新自身；应改用
  [Play In-App Updates](https://developer.android.com/guide/playcore/in-app-updates)，
  或在本库中禁用安装链路（只做「发现新版本」跳转 Play 商店）；
- 国内应用商店、企业 MDM 分发请按各自政策评估；上架前应确认权限声明与商店描述一致；
- 不要用本库绕过商店审核更新代码（例如下发未审核功能/动态代码），这违反多数商店政策。

## 6. 已知边界与不做的事

| 项 | 说明 |
|---|---|
| 不做增量更新 | APK v2/v3 签名覆盖整文件，应用内无 release 私钥无法重签补丁 |
| 不信任镜像 | 镜像只作为可用性回退，完整性始终由 SHA-256 + 签名保证 |
| 不防「用户主动安装恶意包」 | 系统安装器与用户授权是最终边界；本库只保证不把被篡改/错误的包交给安装器 |
| 不防仓库被完全接管 | 若 GitHub 账号/Release 被攻破，攻击者可发布高 versionCode 的**同签名**恶意包（需要私钥）；应保护仓库与签名密钥 |
| 无遥测 | 灰度覆盖依赖确定性分桶与 0/100 边界验证，不做数据回传 |
| 通知权限被拒 | 下载仍继续；回前台自动续装，不绕过系统权限 |
