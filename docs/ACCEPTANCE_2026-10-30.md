# ReleaseKite 修复与真机验收记录（2026-10-30）

本次修复前次审核的 4 个 P1、2 个 P2。仅操作新安装的 ReleaseKite Sample，未修改设备上已有 ZhiBox 的数据。

## 修复与自动化

- 错误输出改用固定字符串编码，避免 R8 改名后无法解码。
- NoRelease 明确撤回强制缓存；覆盖新鲜缓存后台复查与过期缓存自动检查两条路径。
- 恢复后台下载时从任务目标重算强制标记。
- 提交 WorkRequest 前将完整 UpdateInfo 与 workId 原子保存到独立 prefs；恢复任务不依赖可变的检查缓存。复用现有缓存序列化，无新增依赖。
- 禁用 Beta 时在启动协程之前清除旧通道缓存并重置节流。
- 示例下载、校验演示在 3 秒后退出；真实下载的不可取消规则不变。
- 新增 8 个回归测试。**本轮验收时** core 113、Android 15、Compose 72，共 200 个测试；
  随后多轮深度审查继续补充测试，当前基线为 **230**（core 119 / Android 36 / Compose 75）。

自动化结果（验收时）：200/200 通过，0 失败、0 跳过；Debug 示例构建与三个 Android 模块 lintDebug 通过，无 error 级 lint。
发布前多轮深审后：230/230 通过；Debug/Release sample（R8/minify + resource shrink）、三模块 release AAR/JAR、lintDebug + lintRelease、publishToMavenLocal 全部通过。

最终源码的 R8 Release 构建已从「临时启用」升级为 `sample` release 变体的常驻门禁（`isMinifyEnabled=true` + `isShrinkResources=true`），CI 与本地门禁都会验证；真机安装、启动及首页渲染也已通过。

## 真机结果

设备：小米 23078PND5G，Android 14；本次仅一个设备、一个现有网络环境。

| 项目 | 结果与证据 |
| --- | --- |
| 真实检查 | GitHub 返回 ZhiBox 0.1.17，正确展示版本、大小、说明 |
| 通知权限拒绝 | 拒绝 Sample 通知权限后下载仍运行，前台服务 ID 4201 正常 |
| 退后台 | Home 后半包持续增长 |
| 进程重建 | 下载中 force-stop 后重开；保留约 12.8 MB 半包，恢复后持续增长到约 30.3、41.6、56.7 MB，最终完成 |
| 安装安全拒绝 | 真实 ZhiBox APK 完成下载后被拒绝，显示安装包无效/签名不一致，未进入安装器 |
| 同签名安装正向 | 本地构建同包名、同 debug 签名的 Sample 0.0.2（code 2），通过实际 AndroidApkValidator |
| 安装权限 | 实际 UpdateManager 显示权限弹窗；跳转系统设置，经小米风险确认授权后返回，自动拉起安装器 |
| 覆盖升级 | 系统安装器显示 0.0.1 → 0.0.2；安装后 dumpsys 确认 code 2 / versionName 0.0.2 |
| 数据保留 | 升级前创建的私有 files/releasekite_acceptance_marker 在升级后存在 |
| 演示退出 | 下载中、校验中演示均展示正确内容，并在超时后恢复到演示按钮列表 |
| 验收入口清理 | 临时 DeviceAcceptanceActivity 未写入仓库；普通示例恢复后无法再启动该入口，安装未知应用权限恢复 default/deny；示例自动检查恢复默认开启，设备临时 APK 和配置文件已移除 |

安装正向使用临时验收 Activity、注入的版本源/空调度器和本地复制的升级 APK；安装编排、APK 校验、权限页、FileProvider、安装器使用本项目真实实现。真实 GitHub 下载和后台恢复另由默认 Sample 实测。未将这两段描述为同一条生产发布链路。

## 验收边界

- 自动化与本机冒烟通过后可继续内测；尚不满足 TESTING.md 要求的至少两台设备、两种网络的完整发布放行标准。
- 尚未在真实发布仓库演练 Beta/灰度/强制策略变更、镜像回退、离线重试和用户取消系统安装后的重启行为；对应逻辑主要由自动化回归覆盖。
- R8 构建已通过 `com.android.tools:r8:9.1.29` classpath 覆盖（与 Kotlin 2.4 metadata 对齐），
  release 构建不再出现 Kotlin metadata 解析警告；Gradle 仍提示后续 Kotlin 版本的最低 Gradle
  版本变更，本次不升级构建工具链。
- 旧版本已经入队、未保存独立目标的任务会停止接管，保留半包供重新下载；不会将新的检查缓存误当作旧任务目标。


## 第二轮复审修复与针对性真机验收

修复本轮 1 个 P1、2 个 P2：

- 过期下载代际在 `onRequestId` 回调中抛取消异常，调度器先执行回调校验，再持久化目标并提交任务。拒绝的任务既不覆盖 target，也不执行 REPLACE。
- Host 记录自身是否持有前台引用，STOP 或 dispose 时配对归还一次；重挂和多窗口不会累计或误减计数。
- 发布策略的 `minSupportedVersion: null` 归一化为缺省值；保留对非法版本、布尔值、数组和对象的拒绝。Python 回归加入 CI。

自动化：新增 4 个 Kotlin/Android/Compose 回归，当前为 core 119 / Android 36 / Compose 75，共 230 个；另有 2 个发布模板 Python 测试。全部通过。Debug/Release sample、Debug/Release lint、独立 mavenLocal consumer 的 R8 发布物消费测试均通过。

同一台小米 23078PND5G / Android 14 的针对性实测：

- 实际 ReleaseKiteHost 移除、重新挂载后，前台标记按 true → false → true → false 变化。
- 实际 WorkManager 调度器先提交新任务，再拒绝旧代际；新 WorkRequest 仍存在，旧 id 未入库，observe 返回的目标仍是新任务目标。
- 临时 Activity 显示 PASS；其源码与 manifest 均在独立临时目录，不进入仓库。最终恢复普通示例包。
- 普通示例恢复后，真实手动检查返回 ZhiBox 0.1.17，正常显示可稍后关闭的更新弹窗。

本轮针对任务调度和 Host 生命周期验收，未重复前次同签名覆盖升级、完整下载或跨设备/跨网络验收；仍不代替完整发布放行清单。

## 第三轮：双设备真机放行矩阵（K60U / Wi‑Fi + PJZ110 / 移动网络）

设备 A：小米 K60U（23078PND5G），Android 14，Wi‑Fi；本轮重新连接后执行主链路
（真实检查/下载/Wi‑Fi 中断后 force-stop 续传/安全拦截/同签名安装）。
设备 B：OnePlus PJZ110，Android 17（SDK 37），中国广电移动网络（Wi‑Fi 关闭，VPN FlyShadow
开启）。两台设备均安装 sample debug 0.0.1 后开始；本轮没有修改设备上其它应用的数据。
临时验收入口/0.0.2 APK 只在 `/tmp` 独立工程构建，不进入仓库；验收后两台设备均已卸载测试包
并恢复普通 sample 0.0.1。

| 矩阵项 | A：K60U / Android 14 / Wi‑Fi（本轮） | B：PJZ110 / Android 17 / 移动网络（本轮） |
|---|---|---|
| 真实 GitHub 检查 / 弹窗 | ✅ 0.1.17，版本/67.2MB/说明正确 | ✅ 0.1.17，版本/67.2MB/更新时间/说明正确；弹窗可「稍后」关闭 |
| 通知权限拒绝后继续下载 | ✅（历史记录；本轮 MIUI 自动授予通知权限，未重复拒绝路径） | ✅ 拒绝通知权限；下载继续，WorkManager 前台服务 ID 4201 启动，进度到 27%+ 并完成 |
| 后台下载 / Home | ✅（历史记录） | ✅ Home 后 WorkManager 继续，worker 以 SUCCESS 结束 |
| 跨进程 `.part` 续传 | ✅ Wi‑Fi 中断时 `.part`=34,797,731 B；force-stop；恢复 Wi‑Fi 重开后同一 `.part` 保留并从 34.8MB 续到完成 | ✅ 禁用移动数据后 `.part`=14,994,560 B；force-stop；恢复网络重开进程后同一 `.part` 保留，重试从 15.4MB 继续到 45.7MB 并完成 |
| SHA-256 / 包名 / 签名拦截 | ✅ 真实 ZhiBox APK 被拒绝：「安装包无效或签名不一致」 | ✅ 同左，未进入安装器 |
| Android 17 通知拒绝 + FGS | — | ✅ 通知拒绝下 FGS `dataSync` 启动、下载不崩；完成后回前台可重试/续装路径正常 |
| 未知来源权限 / 系统安装器 / 同签名覆盖升级 | ✅ 真实 `AndroidApkValidator` 校验 0.0.2 通过；真实 Installer 经 FileProvider 拉起 MIUI 安装器；0.0.1→0.0.2（versionCode 2）安装成功，私有 files 标记保留 | ✅ 真实 Validator 校验 0.0.2 通过；真实 Installer 经 FileProvider 拉起安装器；OPPO 风险确认后 0.0.1→0.0.2（versionCode 2）安装成功，私有 files 标记保留 |
| Beta / 灰度 0%、100% / 强制 / 镜像回退 | ✅ 真实 ZhiBox `update.json`（`minSupportedVersion=0.1.0`）强制演练：标题「需要更新」、无「稍后」、返回键/点外部均不关闭；force-stop 重启后仍强制（缓存 forced）。Beta/灰度 0%、100% 与 APK 镜像未演练 | 未演练（需真实发布仓库；逻辑由 230 自动化用例覆盖） |
| 0 P0 | ✅ | ✅ 本轮未发现 P0 |

证据（A 设备，本轮）：
- [检查弹窗](device-matrix-2026-10-30/k60u-01-check-dialog.png)
- [Wi‑Fi 断开后保留 `.part`（更新失败态）](device-matrix-2026-10-30/k60u-02-download-paused.png)
- [重开后从 34.8MB 续传](device-matrix-2026-10-30/k60u-03-resumed.png)
- [签名/包名拦截](device-matrix-2026-10-30/k60u-04-signature-rejected.png)
- [同签名升级到 0.0.2](device-matrix-2026-10-30/k60u-05-installed-0.0.2.png)
- [真实 update.json 强制更新](device-matrix-2026-10-30/k60u-06-forced-dialog.png)
- [强制更新 force-stop 重启后仍强制](device-matrix-2026-10-30/k60u-07-forced-after-restart.png)

证据（B 设备，本轮）：
- [检查弹窗](device-matrix-2026-10-30/pjz110-01-check-dialog.png)
- [通知拒绝后下载中](device-matrix-2026-10-30/pjz110-02-downloading-notif-denied.png)
- [签名/包名拦截](device-matrix-2026-10-30/pjz110-03-signature-rejected.png)
- [数据关闭后保留 `.part`](device-matrix-2026-10-30/pjz110-05-download-paused.png)
- [重开进程后续传](device-matrix-2026-10-30/pjz110-06-resumed.png)
- [同签名升级安装完成](device-matrix-2026-10-30/pjz110-07-install-complete.png)

边界：A/B 均已覆盖「检查 → 弹窗 → 下载 → force-stop/续传 → 安全拦截 → 同签名系统安装」；
A 另用真实 ZhiBox `update.json`（镜像回退）完成强制更新演练（不可取消 + 重启后仍强制），
历史记录另有通知拒绝/后台/0.1.x 升级链；B 本轮覆盖 Android 17 通知拒绝与 FGS。Beta/灰度
0%/100% 与 APK 镜像的发布侧演练仍需单独测试仓库与 Release policy。同签名安装正向使用临时
验收 Activity + 本地 0.0.2 APK（库真实 `AndroidApkValidator`/`AndroidUpdateInstaller`/FileProvider），
临时入口与 APK 未写入仓库。移动网络下载 67.2MB 属真实验收数据消耗；设备网络/VPN 环境仅代表
当前一次实测，不等价于所有运营商/受限网络组合。
