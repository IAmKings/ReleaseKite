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
  随后发布前三方深度审查又补充 26 个测试，当前基线为 **226**（core 118 / Android 35 / Compose 73）。

自动化结果（验收时）：200/200 通过，0 失败、0 跳过；Debug 示例构建与三个 Android 模块 lintDebug 通过，无 error 级 lint。
发布前深审后：226/226 通过；Debug/Release sample（R8/minify + resource shrink）、三模块 release AAR/JAR、lintDebug + lintRelease、publishToMavenLocal 全部通过。

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
- R8 构建仍提示 Kotlin 元数据兼容警告，Gradle 仍提示后续 Kotlin 版本的最低 Gradle 版本变更；本次不升级构建工具链。
- 旧版本已经入队、未保存独立目标的任务会停止接管，保留半包供重新下载；不会将新的检查缓存误当作旧任务目标。
