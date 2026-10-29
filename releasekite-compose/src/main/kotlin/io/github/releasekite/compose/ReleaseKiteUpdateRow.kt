package io.github.releasekite.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.releasekite.core.UpdateManager
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateUiState

/**
 * 设置页「检查更新」行（无状态版）：状态内联展示，整行可点 = `manager.checkNow()`。
 *
 * 与源实现一致：下载/校验进行中不可点击（避免打断不可取消的下载弹窗），
 * 其余 phase 点击都触发一次手动检查（取消在途检查并生成新任务）。
 */
@Composable
fun ReleaseKiteUpdateRow(
    state: UpdateUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val clickEnabled = phase !is UpdatePhase.Downloading && phase !is UpdatePhase.Verifying
    val title = stringResource(R.string.releasekite_row_title)
    val status = rowStatusText(phase, state.currentVersionName)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        // testTag 打在可点击的 Row 上：断言「下载/校验中不可点」时 disabled 语义在此节点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(ReleaseKiteTestTags.ROW)
                .clickable(
                    enabled = clickEnabled,
                    role = Role.Button,
                    onClickLabel = title,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (phase) {
                is UpdatePhase.Checking, is UpdatePhase.Downloading, is UpdatePhase.Verifying ->
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(4.dp)
                            .semantics { contentDescription = status },
                        strokeWidth = 2.dp,
                    )

                else -> Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 设置页「检查更新」行（manager 便捷版）：内部订阅 [UpdateManager.state]，
 * 点击即 `checkNow()`；适合直接塞进宿主的设置页。
 */
@Composable
fun ReleaseKiteUpdateRow(manager: UpdateManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsStateWithLifecycle()
    ReleaseKiteUpdateRow(
        state = state,
        onClick = manager::checkNow,
        modifier = modifier,
    )
}

/** phase → 行内状态文案（与源实现的分支一一对应）。 */
@Composable
private fun rowStatusText(phase: UpdatePhase, currentVersionName: String): String = when (phase) {
    is UpdatePhase.Checking -> stringResource(R.string.releasekite_row_checking)
    is UpdatePhase.UpToDate -> stringResource(
        R.string.releasekite_row_up_to_date,
        phase.versionName,
    )

    is UpdatePhase.Available -> stringResource(
        R.string.releasekite_row_available,
        phase.info.versionName,
    )

    is UpdatePhase.RolloutPending -> stringResource(
        R.string.releasekite_row_rollout_pending,
        phase.info.versionName,
        phase.rolloutPercent,
    )

    is UpdatePhase.Downloading -> stringResource(
        R.string.releasekite_row_downloading,
        progressPercent(phase.downloadedBytes, phase.totalBytes),
    )

    is UpdatePhase.Verifying -> stringResource(R.string.releasekite_row_verifying)
    is UpdatePhase.NeedInstallPermission -> stringResource(R.string.releasekite_row_permission)
    is UpdatePhase.CheckFailed -> stringResource(R.string.releasekite_row_check_failed)
    is UpdatePhase.DownloadFailed -> stringResource(R.string.releasekite_row_download_failed)
    else -> stringResource(R.string.releasekite_row_current, currentVersionName)
}
