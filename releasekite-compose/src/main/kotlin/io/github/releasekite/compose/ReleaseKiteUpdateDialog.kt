package io.github.releasekite.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import io.github.releasekite.core.UpdateUiState

/**
 * 全局更新弹窗（无状态）。
 *
 * 可见 phase 与取消规则与 ZhiBox `AppUpdateDialog` 完全一致：
 * - Available（普通）：可点外部/返回键关闭，按钮「立即更新 / 稍后」；
 * - Available（forced）：不可关闭、无「稍后」，只能更新；
 * - Downloading / Verifying：**不可取消**（无按钮、返回键/外部点击无效）；
 * - DownloadFailed：可重试；forced 时只有累计失败达到阈值（`escapeAllowed`）才给「暂时退出」；
 * - NeedInstallPermission：「去设置」+「取消」。
 *
 * 弹窗本身不做任何网络/状态操作，全部动作经 [actions] 注入，便于宿主复用与测试。
 * 文案全部来自 `releasekite_` 资源；更新说明为空时显示本地化占位（core 不再写兜底文案）。
 */
@Composable
fun ReleaseKiteUpdateDialog(
    state: UpdateUiState,
    actions: ReleaseKiteActions,
    modifier: Modifier = Modifier,
    style: ReleaseKiteDialogStyle = ReleaseKiteDialogStyle(),
) {
    val phase = state.phase
    val cancellable = isUpdateDialogCancellable(phase)
    val visible = phase is UpdatePhase.Available ||
        phase is UpdatePhase.Downloading ||
        phase is UpdatePhase.Verifying ||
        phase is UpdatePhase.DownloadFailed ||
        phase is UpdatePhase.NeedInstallPermission
    if (!visible) return

    AlertDialog(
        onDismissRequest = { if (cancellable) actions.onDismiss() },
        modifier = modifier.testTag(ReleaseKiteTestTags.DIALOG),
        properties = updateDialogProperties(phase),
        shape = style.shape,
        containerColor = style.containerColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(dialogTitleRes(phase)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    is UpdatePhase.Available -> AvailableContent(
                        currentVersion = state.currentVersionName,
                        info = phase.info,
                        forced = phase.forced,
                    )

                    is UpdatePhase.Downloading -> DownloadingContent(phase)
                    is UpdatePhase.Verifying -> VerifyingContent()
                    is UpdatePhase.DownloadFailed -> Text(
                        failureText(phase.failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdatePhase.NeedInstallPermission -> Text(
                        stringResource(R.string.releasekite_dialog_permission_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                is UpdatePhase.Available -> Button(
                    onClick = actions.onDownload,
                    modifier = Modifier.testTag(ReleaseKiteTestTags.UPDATE_NOW),
                ) {
                    Text(stringResource(R.string.releasekite_dialog_update_now))
                }

                is UpdatePhase.DownloadFailed -> Button(
                    onClick = actions.onRetry,
                    modifier = Modifier.testTag(ReleaseKiteTestTags.RETRY),
                ) {
                    Text(stringResource(R.string.releasekite_dialog_retry))
                }

                is UpdatePhase.NeedInstallPermission -> Button(
                    onClick = actions.onOpenInstallSettings,
                    modifier = Modifier.testTag(ReleaseKiteTestTags.SETTINGS),
                ) {
                    Text(stringResource(R.string.releasekite_dialog_open_settings))
                }

                else -> Unit
            }
        },
        dismissButton = {
            when (phase) {
                is UpdatePhase.Available -> if (!phase.forced) {
                    TextButton(
                        onClick = actions.onDismiss,
                        modifier = Modifier.testTag(ReleaseKiteTestTags.LATER),
                    ) {
                        Text(stringResource(R.string.releasekite_dialog_later))
                    }
                }

                is UpdatePhase.DownloadFailed -> if (!phase.forced || phase.escapeAllowed) {
                    TextButton(
                        onClick = actions.onDismiss,
                        modifier = Modifier.testTag(ReleaseKiteTestTags.LATER),
                    ) {
                        Text(
                            stringResource(
                                if (phase.forced) {
                                    R.string.releasekite_dialog_exit_temporarily
                                } else {
                                    R.string.releasekite_dialog_close
                                },
                            ),
                        )
                    }
                }

                is UpdatePhase.NeedInstallPermission -> TextButton(
                    onClick = actions.onDismiss,
                    modifier = Modifier.testTag(ReleaseKiteTestTags.LATER),
                ) {
                    Text(stringResource(R.string.releasekite_dialog_cancel))
                }

                else -> Unit
            }
        },
    )
}

/**
 * 弹窗是否可取消（返回键/点外部关闭）——与「稍后/关闭/暂时退出」按钮共用同一判定。
 *
 * 与 ZhiBox 源实现一致：Downloading/Verifying 不可取消；forced Available 不可取消；
 * forced DownloadFailed 需累计失败达到阈值（escapeAllowed）才允许暂时退出；
 * NeedInstallPermission 可取消。internal 供同模块测试直接断言语义。
 */
internal fun isUpdateDialogCancellable(phase: UpdatePhase): Boolean = when (phase) {
    is UpdatePhase.Available -> !phase.forced
    is UpdatePhase.DownloadFailed -> !phase.forced || phase.escapeAllowed
    is UpdatePhase.NeedInstallPermission -> true
    else -> false
}

/** 弹窗窗口属性：不可取消时同时禁用返回键与点外部关闭。 */
internal fun updateDialogProperties(phase: UpdatePhase): DialogProperties {
    val cancellable = isUpdateDialogCancellable(phase)
    return DialogProperties(
        dismissOnBackPress = cancellable,
        dismissOnClickOutside = cancellable,
    )
}

/** phase → 标题资源；与源实现的分支一一对应。 */
private fun dialogTitleRes(phase: UpdatePhase): Int = when (phase) {
    is UpdatePhase.Available -> if (phase.forced) {
        R.string.releasekite_dialog_title_forced
    } else {
        R.string.releasekite_dialog_title_available
    }

    is UpdatePhase.Downloading -> R.string.releasekite_dialog_title_downloading
    is UpdatePhase.Verifying -> R.string.releasekite_dialog_title_verifying
    is UpdatePhase.DownloadFailed -> R.string.releasekite_dialog_title_failed
    is UpdatePhase.NeedInstallPermission -> R.string.releasekite_dialog_title_permission
    else -> R.string.releasekite_dialog_title_available
}

/** 可用态内容：强制提示、版本行、大小/日期、更新说明（可滚动）与完整说明入口。 */
@Composable
private fun AvailableContent(
    currentVersion: String,
    info: UpdateInfo,
    forced: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    if (forced) {
        Text(
            stringResource(R.string.releasekite_dialog_forced_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        stringResource(
            R.string.releasekite_dialog_version_line,
            currentVersion,
            info.versionName,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    val dateText = formatReleaseDate(info.publishedAt)
    val sizeText = formatBytes(info.apkSizeBytes)
    Text(
        if (dateText != null) {
            stringResource(R.string.releasekite_dialog_meta, sizeText, dateText)
        } else {
            stringResource(R.string.releasekite_dialog_meta_size, sizeText)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.releasekite_dialog_notes_title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .testTag(ReleaseKiteTestTags.NOTES),
    ) {
        Text(
            // core 的 notes 为空时保持空串，占位文案由 UI 本地化。
            if (info.notes.isBlank()) {
                stringResource(R.string.releasekite_dialog_notes_empty)
            } else {
                info.notes
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (info.htmlUrl.isNotBlank()) {
        TextButton(
            modifier = Modifier.testTag(ReleaseKiteTestTags.VIEW_FULL),
            onClick = {
                // 宿主未提供 UriHandler 时（如预览）静默失败，不能因跳转失败崩溃。
                runCatching { uriHandler.openUri(info.htmlUrl) }
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                stringResource(R.string.releasekite_dialog_view_full),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 下载态：确定进度用比例，总大小未知用不确定进度；不提供任何按钮。 */
@Composable
private fun DownloadingContent(phase: UpdatePhase.Downloading) {
    val total = phase.totalBytes
    val fraction = if (total > 0L) {
        (phase.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val progressDesc = stringResource(R.string.releasekite_dialog_progress_desc)
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = progressDesc },
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val totalText = if (total > 0L) {
        formatBytes(total)
    } else {
        stringResource(R.string.releasekite_dialog_size_unknown)
    }
    Text(
        stringResource(
            R.string.releasekite_dialog_progress,
            progressPercent(phase.downloadedBytes, total),
            formatBytes(phase.downloadedBytes),
            totalText,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.releasekite_dialog_downloading_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 校验态：不确定进度 + 提示；不提供任何按钮。 */
@Composable
private fun VerifyingContent() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.releasekite_dialog_verifying_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 失败原因 → 可读文案（core 不引资源 ID，映射留在 UI 层）。
 *
 * 15 种 [UpdateFailure] 全覆盖；Http 按是否限流拆两条文案。
 * internal 供同模块测试逐项断言「无遗漏」。
 */
@Composable
internal fun failureText(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.Network -> stringResource(R.string.releasekite_error_network)
    UpdateFailure.Timeout -> stringResource(R.string.releasekite_error_timeout)
    is UpdateFailure.Http -> if (failure.rateLimited) {
        stringResource(R.string.releasekite_error_rate_limited)
    } else {
        stringResource(R.string.releasekite_error_http, failure.code)
    }

    UpdateFailure.Parse -> stringResource(R.string.releasekite_error_parse)
    UpdateFailure.NoRelease -> stringResource(R.string.releasekite_error_no_release)
    UpdateFailure.IncompleteRelease -> stringResource(R.string.releasekite_error_incomplete)
    UpdateFailure.NoChecksum -> stringResource(R.string.releasekite_error_no_checksum)
    UpdateFailure.ChecksumMismatch -> stringResource(R.string.releasekite_error_checksum)
    UpdateFailure.ApkInvalid -> stringResource(R.string.releasekite_error_invalid_apk)
    UpdateFailure.InsufficientSpace -> stringResource(R.string.releasekite_error_space)
    UpdateFailure.InstallPermission -> stringResource(R.string.releasekite_error_install_permission)
    UpdateFailure.InstallFailed -> stringResource(R.string.releasekite_error_install)
    UpdateFailure.FileUnavailable -> stringResource(R.string.releasekite_error_file)
    UpdateFailure.Cancelled -> stringResource(R.string.releasekite_error_cancelled)
    UpdateFailure.ReleaseUnavailable -> stringResource(R.string.releasekite_error_release_unavailable)
}

/** 预览用示例数据（不参与运行时代码路径）。 */
@Preview(showBackground = true, name = "Update dialog - available")
@Composable
private fun ReleaseKiteUpdateDialogPreview() {
    MaterialTheme {
        ReleaseKiteUpdateDialog(
            state = UpdateUiState(
                phase = UpdatePhase.Available(UpdateTrigger.MANUAL, previewInfo()),
                currentVersionName = "0.1.6",
            ),
            actions = ReleaseKiteActions(
                onDownload = {},
                onDismiss = {},
                onRetry = {},
                onOpenInstallSettings = {},
            ),
        )
    }
}

private fun previewInfo() = UpdateInfo(
    versionName = "0.1.7",
    tagName = "v0.1.7",
    title = "ReleaseKite v0.1.7",
    notes = "· Settings simplified\n· Bug fixes",
    publishedAt = "2026-10-27T00:00:00Z",
    htmlUrl = "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7",
    apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/app-release-signed.apk",
    apkSizeBytes = 70L * 1024 * 1024,
    sha256 = "a".repeat(64),
    sha256Url = null,
)
