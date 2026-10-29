# 发布流水线与资产契约

> 本文说明 ReleaseKite 客户端**期望**的 GitHub Release 资产契约、发布模板用法、Beta/灰度/强制
> 操作与踩坑经验。可直接复制 [`samples/github-workflows/release.yml`](../samples/github-workflows/release.yml)
> 与 [`samples/release-policy.json`](../samples/release-policy.json) 到宿主仓库。

## 1. 资产契约（客户端视角）

一个可更新的 Release 至少包含：

| 资产 | 必需 | 说明 |
|---|---|---|
| `<app>-<versionName>-<sha7>-release-signed.apk` | ✅ | 匹配 `ReleaseKiteConfig.apkAssetPattern` 的**唯一** APK；客户端据此选择版本资产 |
| 同名 `.apk.sha256` | 推荐 | 纯文本 SHA-256；Release API 的 `digest` 优先，缺失时回退该资产（`requireChecksum = true` 时二者必须有一个） |
| `<app>-<versionName>-<sha7>-update.json` | 可选 | 灰度比例/强制下限元数据（`metadataAssetSuffix = "-update.json"`）；缺失时按 100% 全量、非强制处理 |
| `<app>-<versionName>-<sha7>-mapping.txt` | 可选 | R8 mapping，便于崩溃符号化；客户端不使用 |

命名没有硬编码要求，但建议统一为上面的形式，并让宿主配置匹配：

```kotlin
ReleaseKiteConfig(
    repository = "owner/repo",
    apkAssetPattern = Regex(""".*-release-signed\.apk$"""),
    checksumAssetSuffix = ".sha256",     // 与 APK 同前缀 + 该后缀
    metadataAssetSuffix = "-update.json",
)
```

- `apkAssetPattern.matches(name)` 必须**恰好命中一个**资产：0 个或多个都会被视为
  `IncompleteRelease`（失败安全，不猜测）；
- `sha256Url` 约定为 APK 资产 URL 的同名前缀 + `checksumAssetSuffix`；
- `metadataAssetSuffix = null` 时客户端跳过元数据拉取（等价于始终 100%/非强制）。

`.sha256` 内容采用 `sha256sum` 输出格式（hash + 文件名，客户端也接受只含 hash 的文本）：

```text
6f2d…c1a9  zhibox-0.1.17-733f78a-release-signed.apk
```

## 2. 版本号与 tag 校验

- tag 形如 `v<versionName>`（`v` 可省略解析，但发布约定必须一致）；
- **tag 必须与模块 `versionName` 完全一致**；发布模板会在构建前强校验，防止「tag 与包内版本
  不一致导致客户端永远提示更新」；
- `versionCode` 必须**单调递增**：客户端 `ApkValidator` 拒绝 `versionCode <= 当前版本` 的包；
- 发布tag 一旦被客户端看到就不要覆盖重发：同名 tag 的 APK 变化会让已下载缓存的旧 SHA 失效，
  也会让 `releases/latest` 指向内容不一致的资产。修复请发新的 patch 版本。

## 3. `-update.json` schema 1

由发布流水线根据 `release-policy.json` 生成并随 Release 上传（**可选**：policy 文件不存在时
模板跳过生成并发 warning，客户端按 100%/非强制处理）：

```json
{
  "schema": 1,
  "versionName": "0.1.17",
  "tagName": "v0.1.17",
  "channel": "stable",
  "rolloutPercent": 100,
  "minSupportedVersion": "0.1.0",
  "publishedAt": "2026-10-30T00:00:00Z",
  "htmlUrl": "https://github.com/owner/repo/releases/tag/v0.1.17",
  "apk": {
    "name": "myapp-0.1.17-733f78a-release-signed.apk",
    "size": 70428817,
    "sha256": "6f2d…c1a9"
  }
}
```

| 字段 | 客户端用途 |
|---|---|
| `schema` | 必须为 `1`；其它值解析失败 → 回退默认（100%/非强制），不阻断更新 |
| `versionName` / `tagName` / `channel` / `publishedAt` / `htmlUrl` / `apk` | 供运维与人工核对，客户端不依赖（版本/资产信息以 Release API 为准） |
| `rolloutPercent` | 灰度比例 0–100，整数；非法值 → 回退 100 |
| `minSupportedVersion` | 当前版本低于该值时强制更新；非法/缺失 → 不强制 |

`release-policy.json`（复制 [`samples/release-policy.json`](../samples/release-policy.json)）：

```json
{
  "schema": 1,
  "rolloutPercent": 100,
  "minSupportedVersion": "0.0.1"
}
```

- 每次修改 policy → 提交 → 打新 tag 发布；**不建议**发布后 `gh release upload --clobber`
  覆盖同名元数据后立刻验证（CDN 边缘可能短暂返回旧值，见 §7）；
- 客户端拉取元数据带 cache-buster + `Cache-Control: no-cache`，并在 6h 后重新校验缓存，
  但 CDN 行为仍不受客户端控制。

## 4. 发布工作流模板

模板文件：[`samples/github-workflows/release.yml`](../samples/github-workflows/release.yml)。

触发条件与步骤：

1. `push tag v*`；
2. **tag/versionName 校验**：从宿主模块 `build.gradle.kts` 读取 `versionName`，与 tag 去掉 `v`
   后比较，不一致直接失败；
3. JDK 21 + Gradle wrapper 校验 + Gradle 缓存；
4. 从 Secrets 解码 release keystore（`RELEASE_STORE_BASE64` / `RELEASE_STORE_PASSWORD` /
   `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`，模板内可改名）；
5. `:app:assembleRelease`（宿主按自己的模块名改 `APP_MODULE`）；签名配置由模板注释中的
   Gradle 片段或宿主现有 signingConfig 提供；
6. `apksigner verify --print-certs` 校验签名、`aapt2 dump badging` 确认包名且非 debuggable；
7. 产物重命名为 `<app>-<versionName>-<sha7>-release-signed.apk`，生成 `.sha256`；
8. 用 `release/update-policy.json` 生成 `-update.json`（schema 1）；**policy 文件缺失时**
   该步骤打印 `::warning::` 并跳过（不生成元数据，Release 仍然创建，客户端按 100%/非强制处理）；
9. tag 含 `-` 时自动 `--prerelease`（Beta），随后 `gh release create` 上传全部资产；
   `-update.json` / mapping 资产缺失时自动从上传列表中省略。

模板顶部 `env` 中的 `APP_MODULE` / `APP_NAME` / `APK_DIR` / `UPDATE_POLICY_FILE` 等为宿主需
修改的占位符；模板不包含任何具体包名，所有仓库/应用标识来自 `GITHUB_REPOSITORY` 与宿主配置。

> 签名密钥是长期身份：一旦客户端安装了某个签名的版本，后续更新 APK 必须使用同一证书，
> 否则 `ApkValidator` 会拒绝安装。keystore 与口令只放 CI Secrets，必须离线备份。

## 5. Beta（预发布）通道

- **tag 必须含 `-`**，例如 `v1.2.3-beta.1`；发布模板据此自动 `gh release create --prerelease`；
- 预发布不会进入 `releases/latest`，所以 stable 用户看不到；
- 客户端打开「接收预发布版本」后走 `releases?per_page=10`，按 semver 选最高版本
  （预发布排序：`1.2.3-beta.1 < 1.2.3-rc.1 < 1.2.3`）；
- Beta → 正式版：正式版 semver 更高时，Beta 客户端也能收到正式版；
- 不要把 beta 发成正式 Release：若 `prerelease` 标记缺失，stable 用户会被推送到测试版本。

## 6. 灰度与强制操作

### 6.1 灰度（staged rollout）

1. 发版前修改 `release-policy.json` 的 `rolloutPercent`（0–100）→ 提交 → 打新 tag；
2. 客户端取 `sha256(installId + "|" + versionName)` 的**前 8 个字节按大端拼成 64-bit**
   （清符号位）后 `% 100` 得到 `cohort`，命中条件为 `cohort < rolloutPercent`；
3. 未命中：不弹窗；手动检查时设置行显示「新版本 X 正在分批推送（N%）」（`RolloutPending`）；
4. 调整比例：官方建议**新建 Release/tag**；若确实要覆盖，见 §7 的 CDN 缓存注意事项；
5. 可验证边界是 **0% 与 100%**；中间比例是本地确定性抽样，无法从 UI 看分桶值。

### 6.2 强制更新（minSupportedVersion）

1. `release-policy.json` 设置 `minSupportedVersion` = 目标版本（高于要强制的旧版本）；
2. 当前版本低于该值 → 强制弹窗：不可取消、无「稍后」、绕过分桶与 dismissed；
3. 下载连续失败 3 次后允许「暂时退出」，避免离线用户被永久锁死（重试会重新计数）；
4. 只对关键版本使用；演练后**恢复 policy 为全量/非强制**并发布新的正式 Release，
   避免旧强制缓存继续生效（客户端对强制缓存有 24h 校验与下架清理，但不要依赖它）；
5. 不要在不可清空数据的测试机做强制演练（必要时清应用数据/卸载重装恢复）。

## 7. CDN 缓存经验（实战）

ZhiBox 发布过程中观测到的真实问题与处理方式：

- **覆盖同名资产后 CDN 会短暂返回旧值**：`gh release upload <tag> <update.json> --clobber`
  后，部分边缘节点仍返回旧的 `rolloutPercent`（实测设备持续读到 0%）。调整灰度/强制的可靠
  方式是**新 tag + 新 Release**；
- 客户端已缓解：元数据请求追加 cache-buster（`?t=timestamp`）并发送 `Cache-Control: no-cache`；
  灰度/普通缓存 6h 后重新校验，强制缓存 24h 后先联网校验；
- 发布后不要**立刻**断言失败：先等待缓存过期/换网络验证，再判定客户端问题；
- APK/`.sha256` 同样可能被 CDN 缓存：发布后核对 `sha256` 与 GitHub 上的资产一致；
- 删除 Release 后客户端会得到 `ReleaseUnavailable`（清强制缓存、弹可关闭的「该版本已下架」、
  重试改为重新检查），所以**删除演练 Release 后**要确认 `releases/latest` 恢复正常。

## 8. 镜像说明（受限网络）

直连 `objects.githubusercontent.com` 在部分网络（如国内移动网络）可能 TLS/读超时，客户端支持
按 `downloadMirrors` 前缀回退（APK 与 `-update.json` 都支持）：

```kotlin
ReleaseKiteConfig(
    repository = "owner/repo",
    downloadMirrors = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
    ),
)
```

- 每项必须是 `https://` 且以 `/` 结尾（构造时 `require` 校验）；
- 镜像 URL = 镜像前缀 + 原始 GitHub 资产 URL；镜像 host 由客户端从前缀解析并校验；
- 镜像只能看到**公开** APK/元数据；完整性由 SHA-256 + 安装前签名/包名校验保证——
  镜像投毒无法绕过安装校验（详见 [`SECURITY.md`](SECURITY.md)）；
- 镜像不可用/全部线路失败时显示可重试的网络/超时错误，不会静默降级；
- 镜像只应缓存公开资产；私有仓库无法使用镜像（匿名 API 本身也不支持）。

## 9. 发布后自检

```bash
# 1. stable latest 指向正确版本（beta 不应出现在这里）
curl -s https://api.github.com/repos/<owner>/<repo>/releases/latest | jq .tag_name

# 2. Release 资产齐全（APK / .sha256 / update.json / mapping）
gh release view v1.2.3 --json isPrerelease,assets

# 3. 元数据内容正确
gh release download v1.2.3 --pattern '*-update.json' --dir /tmp/meta --clobber
cat /tmp/meta/*-update.json | jq '.schema, .rolloutPercent, .minSupportedVersion'

# 4. APK 可下载且 SHA 一致（直连或镜像）
curl -sIL -o /dev/null -w '%{http_code}\n' \
  https://github.com/<owner>/<repo>/releases/download/v1.2.3/<asset>

# 5. 本地校验签名（可选）
apksigner verify --print-certs /path/to/<asset>
```

## 10. 发版顺序（推荐）

1. 代码冻结 → CI 全绿（单测/lint/sample 构建）；
2. 更新 `versionName` / `versionCode` 与发布说明（当前版本段落）；
3. 按计划设置 `release-policy.json`（全量或灰度/强制）；不需要灰度/强制时可不放该文件，
   模板会跳过 `-update.json` 生成（warning），客户端按 100%/非强制处理；
4. 打 tag `v<versionName>` 并推送 → 流水线签名构建并创建 Release；
5. 发布后自检（§9）→ 真机验收（[`TESTING.md`](TESTING.md) §4）；
6. 临时 Beta/灰度/强制演练完成后：删除临时 Release/tag、恢复 policy、确认 latest 未被污染。

## 11. 常见发布事故

| 事故 | 表现 | 处理 |
|---|---|---|
| tag 与 versionName 不一致 | 客户端永远提示更新/安装后版本不变 | 修正后发新 tag；不要覆盖旧 tag |
| 忘记上传 `.sha256` 且 API 无 digest | 客户端 `NoChecksum` 不下载 | 补传 `.sha256` 资产（或下次发版带 `requireChecksum=false`，不推荐） |
| APK 资产名不匹配 | `IncompleteRelease` | 统一命名或调整宿主 `apkAssetPattern` |
| Beta 没标 prerelease | stable 用户被推送到 Beta | 立即修正 Release 的 prerelease 标记；必要时删除并重发 |
| 签名变了 | 下载后拒绝安装 | 恢复原 keystore；已发布版本无法跨签名升级 |
| 强制版本被删除 | 旧客户端持续强制 → 失败 3 次逃生 | 发布一个更新的正式版本覆盖；客户端 `ReleaseUnavailable` 会清强制缓存 |
| 灰度改了但不生效 | 客户端仍读旧比例 | 新建 Release；或等待 CDN/客户端缓存过期（6h） |
