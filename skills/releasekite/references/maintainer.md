# 维护本仓库 / 发布库制品

目标：修改 ReleaseKite 源码或文档后，正确跑门禁、验证发布物、发 JitPack/GitHub Packages，
而不破坏版本契约或签名资产。完整命令：[docs/TESTING.md](https://github.com/IAmKings/ReleaseKite/blob/main/docs/TESTING.md)。

## 1. 仓库结构

```text
releasekite-core/     纯 JVM：版本/策略/灰度/状态机/下载器
releasekite/          Android 适配：WorkManager/安装/通知/SharedPreferences
releasekite-compose/  Compose UI：Host/弹窗/设置行/设置区
sample/               可运行示例（metadata 默认关闭，避免演示 App 被强制锁死）
samples/              宿主发布模板（release.yml / release-policy.json）
docs/                 canonical 文档（API/接入/发布/安全/测试）
scripts/              consumer smoke、release metadata 测试、skill 安装/校验
skills/               AI skill（本目录）
.github/workflows/    CI、GitHub Packages 发布
```

修改前先读 [API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md) 与
[迁移不变量](https://github.com/IAmKings/ReleaseKite/blob/main/docs/MIGRATION_ZHIBOX.md)；行为变更必须同步测试和文档。

## 2. 环境

- JDK 21（macOS 可用 Android Studio JBR；Gradle 8.14.3 不接受系统 Java 25）；
- Android SDK（compileSdk 36）；`local.properties` 指向本机 SDK；
- 发布物一律从仓库根执行，使用 Gradle wrapper。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --version
```

## 3. 门禁命令

```bash
# 三个模块单测（core 纯 JVM；android/compose Robolectric）
./gradlew :releasekite-core:test :releasekite:testDebugUnitTest :releasekite-compose:testDebugUnitTest

# Debug 示例
./gradlew :sample:assembleDebug

# Release 门禁：库 AAR + sample R8/minify + resource shrink
./gradlew :releasekite:assembleRelease :releasekite-compose:assembleRelease :sample:assembleRelease

# lint（Debug + Release；error 级阻断）
./gradlew :releasekite:lintDebug :releasekite-compose:lintDebug :sample:lintDebug \
          :releasekite:lintRelease :releasekite-compose:lintRelease :sample:lintRelease

# 发布到 Maven Local（坐标/产物）
./gradlew :releasekite-core:publishToMavenLocal :releasekite:publishToMavenLocal \
          :releasekite-compose:publishToMavenLocal

# 独立 consumer（mavenLocal AAR + R8）与发布元数据测试
bash scripts/consumer-smoke.sh
python3 scripts/test-release-metadata.py
python3 scripts/test-skills.py

# 发布物校验（可选）：包含 POM/坐标/consumer rules
```

`CI` 会跑同一组门禁；任何失败都上传 `**/build/reports/**` 等 artifact。不要只看单测通过就发布。

## 4. 测试/文档同步规则

- 新增行为 → 至少加一条回归测试；并发/安全/状态机改动优先 core 测试，Android 适配用 Robolectric；
- 改 API → 同步 `docs/API_CONTRACT.md`、README 示例、`sample/`（若示例使用）和迁移文档；
- 改发布流程/资产契约 → 同步 `docs/RELEASE_PIPELINE.md`、`samples/github-workflows/release.yml`、
  `scripts/test-release-metadata.py` 与 CI；
- 改测试数量/覆盖 → 同步 `README.md` 与 `docs/TESTING.md`；skill 只写“按 TESTING 当前门禁”，
  不复制易过期数字；
- 修改 `skills/` → 跑 `python3 scripts/test-skills.py`，并确保 references 链接和 frontmatter 有效。

## 5. 发布库制品

### 坐标

当前 group：`com.github.IAmKings.ReleaseKite`（同时兼容 JitPack 与 POM/mavenLocal）。
版本来自根 `gradle.properties` 的 `VERSION_NAME`。

```bash
# 本地/CI 验证
./gradlew :releasekite-core:publishToMavenLocal :releasekite:publishToMavenLocal \
          :releasekite-compose:publishToMavenLocal
```

### JitPack

1. `VERSION_NAME` 更新并提交；
2. 打 tag：`git tag v0.1.0 && git push origin v0.1.0`；
3. JitPack 首次请求 tag 时构建；坐标：
   `com.github.IAmKings.ReleaseKite:<module>:v0.1.0`；
4. 用独立 consumer 验证，不只看 JitPack 页面绿。

### GitHub Packages

`.github/workflows/publish-github-packages.yml` 在 `workflow_dispatch` 或 Release published 时：

- 用 `GITHUB_TOKEN`（`packages: write`）发布三个模块到
  `https://maven.pkg.github.com/IAmKings/ReleaseKite`；
- 版本不可覆盖（409）；同版本已存在时跳过重发并验证制品；
- 消费端需要 `read:packages` token，artifact 版本是 `0.1.0`（不带 `v`）。

### Maven Central

当前**未配置**。需要 Sonatype Central Portal 账号、`io.github.iamkings` 命名空间验证、GPG
签名密钥与 Secrets；不要使用其它项目的凭据。若未来接入，坐标用 `-PGROUP=io.github.iamkings`。

## 6. GitHub Actions

| workflow | 触发 | 作用 |
|---|---|---|
| `ci.yml` | push main/PR/manual | 单测、Debug/Release 构建、lint、publishToMavenLocal、consumer smoke、skill 校验 |
| `publish-github-packages.yml` | workflow_dispatch / release published | 发布库到 GitHub Packages，验证 POM/JAR/AAR 可下载 |

每次 push 后检查最新 run；不要只看“提交成功”。GitHub Actions 的 `setup-java@v4`/Node 20
deprecation 警告不是失败，但升级 actions 时要重新跑门禁。

## 7. 发布检查清单

- [ ] `VERSION_NAME`、tag、`build.gradle.kts` 的 versionName 一致；
- [ ] 单测 + Debug/Release + lint + publish + consumer smoke 全绿；
- [ ] `skills`/docs 链接校验通过；
- [ ] JitPack tag 构建成功、module metadata 的 sibling 依赖正确；
- [ ] GitHub Packages 制品 HTTP 200（若本次发布）；
- [ ] 更新 Release notes/CHANGELOG；
- [ ] 不修改历史 tag，不覆盖已发布 assets。

## 8. 安全/合规

- 不提交 keystore、token、口令、`local.properties`、构建产物；
- 不在日志/对话打印 secrets；发布模板遵循 `EXPECTED_CERT_SHA256` 证书连续性检查；
- 不实现静默安装；保持 `minSdk 24`、公开 GitHub Release、匿名 API 的既有边界；
- 发现安全/签名问题先停止发布，修复后发新版本，不回滚签名。

## 9. 相关文档

- [README](https://github.com/IAmKings/ReleaseKite/blob/main/README.md)
- [测试与验收](https://github.com/IAmKings/ReleaseKite/blob/main/docs/TESTING.md)
- [发布流水线](https://github.com/IAmKings/ReleaseKite/blob/main/docs/RELEASE_PIPELINE.md)
- [安全模型](https://github.com/IAmKings/ReleaseKite/blob/main/docs/SECURITY.md)
- [API 契约](https://github.com/IAmKings/ReleaseKite/blob/main/docs/API_CONTRACT.md)

