package io.github.releasekite.compose

import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import io.github.releasekite.core.UpdateUiState

/**
 * UI 测试共享数据：字段结构与真实 GitHub Release 一致，避免各用例重复构造。
 *
 * 注意：`notes` 故意保留中文内容——它来自 Release body（服务端数据），
 * 与 UI 资源语言无关，中文用例也直接断言这段原始文本。
 */
internal object ReleaseKiteTestData {

    const val CURRENT_VERSION = "0.1.6"
    const val HTML_URL = "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7"
    const val DEFAULT_NOTES = "· 设置页简化\n· 修复已知问题"

    fun info(
        versionName: String = "0.1.7",
        notes: String = DEFAULT_NOTES,
        publishedAt: String? = "2026-10-27T00:00:00Z",
        htmlUrl: String = HTML_URL,
        apkSizeBytes: Long = 70L * 1024 * 1024,
    ) = UpdateInfo(
        versionName = versionName,
        tagName = "v$versionName",
        title = "ReleaseKite v$versionName",
        notes = notes,
        publishedAt = publishedAt,
        htmlUrl = htmlUrl,
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$versionName/app-release-signed.apk",
        apkSizeBytes = apkSizeBytes,
        sha256 = "a".repeat(64),
        sha256Url = null,
    )

    fun state(
        phase: UpdatePhase,
        currentVersionName: String = CURRENT_VERSION,
    ) = UpdateUiState(phase = phase, currentVersionName = currentVersionName)

    fun available(forced: Boolean = false) = UpdatePhase.Available(
        trigger = UpdateTrigger.MANUAL,
        info = info(),
        forced = forced,
    )

    /** 无副作用动作集合（只关心渲染的用例用）。 */
    fun noopActions() = ReleaseKiteActions(
        onDownload = {},
        onDismiss = {},
        onRetry = {},
        onOpenInstallSettings = {},
    )
}
