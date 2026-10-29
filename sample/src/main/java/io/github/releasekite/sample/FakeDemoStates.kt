package io.github.releasekite.sample

import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import io.github.releasekite.core.UpdateUiState

/**
 * Fake 状态演示：用于在**不触发真实下载**的情况下走查 ReleaseKite 的每一种弹窗形态。
 *
 * 数据全部为本地构造的 [UpdateInfo] / [UpdateUiState]，不做任何网络请求；
 * 真实管理器（`ReleaseKite.manager`）只驱动页面上的更新行与设置区。
 */
enum class FakeUpdatePhase(val buttonLabel: String) {
    AVAILABLE("available：发现新版本"),
    DOWNLOADING("downloading：下载中"),
    VERIFYING("verifying：校验中"),
    FAILED("failed：下载失败"),
    PERMISSION("permission：等待安装权限"),
    ROLLOUT("rollout：灰度分批推送"),
}

object FakeUpdateStates {

    private const val DEMO_VERSION = "9.9.9-demo"
    private const val DEMO_APK_SIZE = 70_428_817L

    /** 演示用 UpdateInfo：字段完整，URL 使用示例仓库占位，不会被真实下载。 */
    fun info(): UpdateInfo {
        val tag = "v$DEMO_VERSION"
        val base = "https://github.com/${DemoSettings.repository}"
        val apkName = "demo-$DEMO_VERSION-release-signed.apk"
        return UpdateInfo(
            versionName = DEMO_VERSION,
            tagName = tag,
            title = "ReleaseKite Demo $DEMO_VERSION",
            notes = buildString {
                appendLine("这是由示例应用本地构造的演示数据，不会发起网络请求。")
                appendLine()
                appendLine("· 演示更新弹窗的版本、大小、日期与说明区布局")
                appendLine("· 演示下载进度、校验、失败原因、安装权限等状态")
                appendLine("· 真实下载请点「立即检查」使用 ReleaseKite.manager")
            },
            publishedAt = "2026-10-30T00:00:00Z",
            htmlUrl = "$base/releases/tag/$tag",
            apkUrl = "$base/releases/download/$tag/$apkName",
            apkSizeBytes = DEMO_APK_SIZE,
            sha256 = "0123456789abcdef".repeat(4),
            sha256Url = "$base/releases/download/$tag/$apkName.sha256",
            channel = UpdateChannel.STABLE,
            rolloutPercent = 100,
            minSupportedVersion = null,
            metaUrl = "$base/releases/download/$tag/demo-$DEMO_VERSION-update.json",
        )
    }

    /** 按阶段构造 fake 状态；`currentVersionName` 取自真实管理器以便弹窗展示「当前版本」。 */
    fun uiState(phase: FakeUpdatePhase, currentVersionName: String): UpdateUiState {
        val info = info()
        val updatePhase: UpdatePhase = when (phase) {
            FakeUpdatePhase.AVAILABLE -> UpdatePhase.Available(UpdateTrigger.MANUAL, info)
            FakeUpdatePhase.DOWNLOADING -> UpdatePhase.Downloading(
                info = info,
                downloadedBytes = 26_000_000L,
                totalBytes = DEMO_APK_SIZE,
            )
            FakeUpdatePhase.VERIFYING -> UpdatePhase.Verifying(info)
            FakeUpdatePhase.FAILED -> UpdatePhase.DownloadFailed(
                info = info,
                failure = UpdateFailure.ChecksumMismatch,
            )
            FakeUpdatePhase.PERMISSION -> UpdatePhase.NeedInstallPermission(info)
            FakeUpdatePhase.ROLLOUT -> UpdatePhase.RolloutPending(info, rolloutPercent = 25)
        }
        return UpdateUiState(
            phase = updatePhase,
            currentVersionName = currentVersionName,
        )
    }

    /** 页面展示用的阶段文案（非库文案；库内 UI 使用 `releasekite_` 前缀的本地化资源）。 */
    fun phaseLabel(phase: UpdatePhase): String = when (phase) {
        UpdatePhase.Idle -> "空闲"
        is UpdatePhase.Checking -> "正在检查（${triggerLabel(phase.trigger)}）"
        is UpdatePhase.UpToDate -> "已是最新（${phase.versionName}）"
        is UpdatePhase.Available -> buildString {
            append("发现新版本 ${phase.info.versionName}")
            if (phase.forced) append("（强制更新）")
        }
        is UpdatePhase.RolloutPending -> "新版本 ${phase.info.versionName} 正在分批推送（${phase.rolloutPercent}%）"
        is UpdatePhase.Downloading -> {
            val total = phase.totalBytes.takeIf { it > 0 }
            val percent = if (total != null) (phase.downloadedBytes * 100 / total).coerceIn(0, 100) else null
            if (percent != null) {
                "正在下载 ${phase.info.versionName}（$percent%）"
            } else {
                "正在下载 ${phase.info.versionName}"
            }
        }
        is UpdatePhase.Verifying -> "正在校验 ${phase.info.versionName}"
        is UpdatePhase.NeedInstallPermission -> "等待安装权限（${phase.info.versionName}）"
        is UpdatePhase.CheckFailed -> "检查失败：${failureLabel(phase.failure)}"
        is UpdatePhase.DownloadFailed -> buildString {
            append("下载失败：${failureLabel(phase.failure)}")
            if (phase.escapeAllowed) append("（可暂时退出）")
        }
    }

    fun triggerLabel(trigger: UpdateTrigger): String = when (trigger) {
        UpdateTrigger.AUTO -> "自动"
        UpdateTrigger.MANUAL -> "手动"
    }

    fun channelLabel(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "正式（stable）"
        UpdateChannel.BETA -> "预发布（beta）"
    }

    /** 失败原因的展示文案；真实 UI 由 releasekite-compose 的本地化资源负责。 */
    fun failureLabel(failure: UpdateFailure): String = when (failure) {
        UpdateFailure.Network -> "网络不可用"
        UpdateFailure.Timeout -> "连接超时"
        is UpdateFailure.Http -> if (failure.rateLimited) {
            "GitHub API 限流（${failure.code}）"
        } else {
            "HTTP 错误（${failure.code}）"
        }
        UpdateFailure.Parse -> "响应解析失败"
        UpdateFailure.NoRelease -> "仓库没有可用 Release"
        UpdateFailure.IncompleteRelease -> "Release 资产不完整"
        UpdateFailure.NoChecksum -> "缺少 SHA-256 校验值"
        UpdateFailure.ChecksumMismatch -> "SHA-256 校验不一致"
        UpdateFailure.ApkInvalid -> "APK 包名/版本/签名校验失败"
        UpdateFailure.InsufficientSpace -> "存储空间不足"
        UpdateFailure.InstallPermission -> "缺少安装未知应用权限"
        UpdateFailure.InstallFailed -> "拉起系统安装器失败"
        UpdateFailure.FileUnavailable -> "安装包文件不可用"
        UpdateFailure.Cancelled -> "已取消"
        UpdateFailure.ReleaseUnavailable -> "该更新版本已下架"
    }
}
