package io.github.releasekite.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.releasekite.core.RouteHealth
import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateManager
import io.github.releasekite.core.UpdateRouteSelector
import io.github.releasekite.core.UpdateUiState

/**
 * 更新设置区（无状态版）：自动检查开关 + Beta 通道开关 + 更新线路健康与重置。
 *
 * - 两个开关只改本地偏好，不触发网络请求（手动「检查更新」才重新查询）；
 * - Beta 开关只在库配置允许 Beta 通道（`state.betaChannelEnabled`）时展示，
 *   宿主关闭 `ReleaseKiteConfig.betaChannelEnabled` 后该行整体隐藏；
 * - 线路健康数据来自真实下载结果的本地统计，不发探测请求、不上报。
 */
@Composable
fun ReleaseKiteSettingsSection(
    state: UpdateUiState,
    onAutoCheckChange: (Boolean) -> Unit,
    onChannelChange: (UpdateChannel) -> Unit,
    onResetRouteHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.releasekite_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                ReleaseKiteSwitchRow(
                    title = stringResource(R.string.releasekite_settings_auto_title),
                    description = stringResource(R.string.releasekite_settings_auto_desc),
                    checked = state.autoCheckEnabled,
                    testTag = ReleaseKiteTestTags.SETTINGS_AUTO_SWITCH,
                    onCheckedChange = onAutoCheckChange,
                )
                if (state.betaChannelEnabled) {
                    ReleaseKiteSwitchRow(
                        title = stringResource(R.string.releasekite_settings_beta_title),
                        description = stringResource(R.string.releasekite_settings_beta_desc),
                        checked = state.channel == UpdateChannel.BETA,
                        testTag = ReleaseKiteTestTags.SETTINGS_BETA_SWITCH,
                        onCheckedChange = { beta ->
                            onChannelChange(if (beta) UpdateChannel.BETA else UpdateChannel.STABLE)
                        },
                    )
                }
            }
        }
        ReleaseKiteRouteHealthRow(
            routeHealth = state.routeHealth,
            onReset = onResetRouteHealth,
        )
    }
}

/**
 * 更新设置区（manager 便捷版）：订阅 [UpdateManager.state] 并把动作直接接到 manager。
 */
@Composable
fun ReleaseKiteSettingsSection(manager: UpdateManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsStateWithLifecycle()
    ReleaseKiteSettingsSection(
        state = state,
        onAutoCheckChange = manager::setAutoCheckEnabled,
        onChannelChange = manager::setChannel,
        onResetRouteHealth = manager::resetRouteHealth,
        modifier = modifier,
    )
}

/** 单个设置开关行：标题 + 说明 + 右侧 Switch。 */
@Composable
private fun ReleaseKiteSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .padding(start = 8.dp)
                .testTag(testTag)
                .semantics { contentDescription = title },
        )
    }
}

/** 更新线路健康摘要 + 「重置线路状态」。 */
@Composable
private fun ReleaseKiteRouteHealthRow(
    routeHealth: Map<String, RouteHealth>,
    onReset: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val direct = routeHealth[UpdateRouteSelector.DIRECT_ID]
    val mirrors = routeHealth.values
        .filter { it.id != UpdateRouteSelector.DIRECT_ID }
        .sortedBy { it.id }
        .take(2)
    val parts = buildList {
        add("${stringResource(R.string.releasekite_route_direct)} ${routeStatusText(direct, now)}")
        mirrors.forEach { route ->
            add(
                "${stringResource(R.string.releasekite_route_mirror, route.id)} " +
                    routeStatusText(route, now),
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.releasekite_route_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onReset,
            modifier = Modifier.testTag(ReleaseKiteTestTags.SETTINGS_RESET_ROUTES),
        ) {
            Text(
                stringResource(R.string.releasekite_route_reset),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 单条线路状态：无记录 / 冷却中 / 正常。 */
@Composable
private fun routeStatusText(health: RouteHealth?, now: Long): String = when {
    health == null -> stringResource(R.string.releasekite_route_none)
    health.cooldownUntil > now -> stringResource(R.string.releasekite_route_cooling)
    else -> stringResource(R.string.releasekite_route_ok)
}
