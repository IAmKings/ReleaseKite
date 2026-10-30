# 发布：宿主 App 的 GitHub Release 与 Actions 流程

目标：让宿主 App 产出一个 ReleaseKite 能消费的 GitHub Release：唯一 APK + `.sha256` +
（需要灰度/强制时）`-update.json`，并用 Actions 保证 tag/签名/资产一致。
完整操作：[docs/RELEASE_PIPELINE.md](https://github.com/IAmKings/ReleaseKite/blob/main/docs/RELEASE_PIPELINE.md)。

> 区分两条“发布”：
> - **宿主 App 发布**：发布你的 App 的 APK + 元数据到你的仓库（本文件主内容）；
> - **ReleaseKite 库发版**：发布 `releasekite-core` / `releasekite` / `releasekite-compose`
>   到 JitPack/GitHub Packages（见 [maintainer.md](maintainer.md)）。

## 1. 复制模板

在宿主仓库根目录执行（模板在 ReleaseKite 仓库，不要直接改 ReleaseKite 的模板）：

```bash
mkdir -p .github/workflows release
curl -fsSL https://raw.githubusercontent.com/IAmKings/ReleaseKite/main/samples/github-workflows/release.yml \
  -o .github/workflows/release.yml
curl -fsSL https://raw.githubusercontent.com/IAmKings/ReleaseKite/main/samples/release-policy.json \
  -o release/update-policy.json
```

如果宿主的 `.gitignore` 忽略了 `release/`，给 policy 加例外（或改 `UPDATE_POLICY_FILE` 路径）。

## 2. 修改模板 env

模板顶部：

| 变量 | 含义 | 示例 |
|---|---|---|
| `APP_MODULE` | Gradle 应用模块名 | `app` |
| `APP_NAME` | 资产文件名前缀 | `myapp` |
| `APK_DIR` | `assembleRelease` 输出目录 | `app/build/outputs/apk/release` |
| `UPDATE_POLICY_FILE` | 灰度/强制 policy 路径 | `release/update-policy.json` |
| `EXPECTED_CERT_SHA256` | 可选：固定签名证书 SHA-256 | 空或 `${{ vars.EXPECTED_CERT_SHA256 }}` |
| `RELEASE_NOTES_FILE` | 可选：只含当前版本说明的文本文件 | 空 = GitHub 自动说明 |

另外把模板注释中的 signingConfig 片段接到宿主 `build.gradle.kts`，或使用宿主已有的
`signingConfig`（必须指向同一个 release keystore）。

## 3. 配置 Secrets / Variables

GitHub 仓库 → Settings → Secrets and variables → Actions：

| Secret | 用途 |
|---|---|
| `RELEASE_STORE_BASE64` | release keystore（JKS/PKCS12 单文件）base64 |
| `RELEASE_STORE_PASSWORD` | keystore 口令 |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key 口令 |

Variable（建议）：

| Variable | 用途 |
|---|---|
| `EXPECTED_CERT_SHA256` | 固定签名证书 SHA-256；换 key 时 CI 直接失败，防止全量用户无法覆盖升级 |

不要把 keystore/口令提交到仓库、写进 `gradle.properties` 或打印到日志。

## 4. 版本、tag 与资产契约

- `versionName` 必须等于 tag 去掉 `v`（例如 `versionName = "1.2.3"` ↔ `v1.2.3`）；
- `versionCode` 必须严格递增；
- 同一 tag 不要重新上传不同 APK；修复发新的 patch；
- Release 至少包含：

```text
myapp-1.2.3-<sha7>-release-signed.apk          # 必需：恰好命中一个 apkAssetPattern
myapp-1.2.3-<sha7>-release-signed.apk.sha256   # 推荐；API digest 缺失且 requireChecksum=true 时必须
myapp-1.2.3-<sha7>-update.json                 # 可选；灰度/强制必需
myapp-1.2.3-<sha7>-mapping.txt                 # 可选
```

- APK 的包名、`versionCode > 当前`、签名证书必须与已安装应用一致；
- 资产匹配由宿主 `ReleaseKiteConfig.apkAssetPattern` 决定，必须只命中一个，否则
  `IncompleteRelease`；发布模板会排除 `*-unsigned.apk`；
- 模板校验 tag/versionName、APK 非 debuggable、签名与证书指纹；它**不比较上一次 versionCode**，
  也不强制 applicationId 等于某个固定值。宿主必须在发版前自行确认 versionCode 递增、包名未变
  （可加一步 `aapt2 dump badging` 与已知包名/包名清单对比）。

## 5. 触发与发布顺序

1. 修改 `release/update-policy.json`（见下节）并提交；
2. 确保 `build.gradle.kts` 的 `versionName/versionCode` 已更新；
3. 打 tag 并推送：`git tag v1.2.3 && git push origin v1.2.3`；
4. Actions 校验 tag/versionName → 解码 keystore → `assembleRelease` → `apksigner`/`aapt2` 校验
   → 生成 `.sha256`/`-update.json` → **draft** 上传 → publish；
5. 发布后做第 8 节自检。

tag 含 `-`（如 `v1.2.3-beta.1`）模板自动 `--prerelease`；Beta 不应进入 stable 的
`releases/latest`。

## 6. `-update.json` 与策略

`release/update-policy.json`：

```json
{
  "schema": 1,
  "rolloutPercent": 100,
  "minSupportedVersion": "0.0.1"
}
```

- `rolloutPercent`：0–100；0% 用于暂停，100% 全量；中间值由客户端按 installId 确定性分桶；
- `minSupportedVersion`：当前版本低于它时强制更新；仅关键版本使用；
- policy 文件缺失时模板会跳过元数据生成（warning），客户端按 100%/非强制处理；
- 客户端生成的 `-update.json` 还包含 `versionName/tagName/channel/publishedAt/htmlUrl/apk{...}`；
  `apk.sha256` 必须与实际上传 APK 一致；
- 调整灰度/强制优先发**新 Release/tag**；`gh release upload --clobber` 可能被 CDN 缓存导致
  不同边缘返回旧值。

## 7. Beta / 灰度 / 强制操作摘要

- **Beta**：`v1.2.3-beta.1` tag 含 `-`，Release 标 prerelease；客户端开启 Beta 后按 semver 选择；
- **灰度**：policy `rolloutPercent` → 新 tag；未命中用户不弹窗，手动检查显示 `RolloutPending`；
- **强制**：policy `minSupportedVersion` → 新 tag；强制态不可取消；连续失败达到
  `forcedEscapeFailures` 后才允许暂时退出；
- **Beta 的强制范围**：pre-release 不进入 `releases/latest`，stable 客户端根本看不到它；
  Beta 里的 `minSupportedVersion` 只影响已开启 Beta 通道的用户。要强制全体 stable 用户，
  必须发布不带 `-` 的正式 Release（并确认 semver/versionCode 高于线上版本），不要把 beta 的
  `minSupportedVersion` 当成全量强制开关；
- **回滚**：把 `rolloutPercent` 设为 0 发新版本，或删除 Release（客户端 `ReleaseUnavailable`），
  再发布修复版本；不要覆盖旧 tag 的 APK。

## 8. 发布后自检

```bash
TAG=v1.2.3
REPO=owner/repo
rm -rf /tmp/rk-check && mkdir -p /tmp/rk-check
gh release view "$TAG" --repo "$REPO" --json assets,isDraft,isPrerelease,url
gh release download "$TAG" --repo "$REPO" --dir /tmp/rk-check
# 下载全部资产后核对：
# 1) APK 恰好 1 个；2) sha256sum -c *.sha256 通过；3) update.json schema/rollout/minSupported 正确；
# 4) apksigner verify --print-certs 与 EXPECTED_CERT_SHA256 一致；
# 5) aapt2 dump badging APK 的包名/versionCode 符合预期；6) stable latest 不指向 beta、Release 非 draft。
```

如果客户端仍读旧策略：先等待 CDN/客户端缓存（普通 6h/强制 24h 校验窗口），换网络复测；
不要立刻断言客户端 bug。

## 9. GitHub Packages（发布库制品，不是宿主 App）

ReleaseKite 自身发版时，GitHub Packages 的消费端配置（需要 `read:packages` token）：

```kotlin
// settings.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/IAmKings/ReleaseKite")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN") // read:packages
        }
    }
}

// app/build.gradle.kts（artifact 版本 0.1.0，不带 v）
implementation("com.github.IAmKings.ReleaseKite:releasekite-compose:0.1.0")
```

GitHub Packages 版本不可覆盖：同版本重复发布返回 409；发新版本而不是重发同版本。
公开分发优先 JitPack；GitHub Packages 适合有 GitHub token 的团队/内部消费。

## 10. 发布事故速查

| 现象 | 优先检查 |
|---|---|
| Actions 在 tag/versionName 校验失败 | tag 是否 `v1.2.3`、模块 `versionName` 是否一致 |
| 找不到 signed APK | signingConfig 是否接上 Secrets；输出目录是否排除 unsigned |
| 客户端永远提示更新 | tag 与 versionName 不一致，或 versionCode 未递增 |
| `ApkInvalid` | 签名证书换了、包名不对、versionCode 不增 |
| 灰度不生效 | `-update.json` 没上传、schema/rolloutPercent 错、APK 资产名不匹配 |
| 强制不生效 | `minSupportedVersion` 不高于当前版本，或 metadataAssetSuffix 被设为 null |
| 所有用户同时被强制 | policy 误设 `minSupportedVersion`；立即发新 policy 恢复 |
| 资产 404/缓存旧值 | CDN/镜像；换新 tag 或等待缓存过期，核对 `.sha256` |

参见 [troubleshooting.md](troubleshooting.md)。

