package io.github.releasekite.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.releasekite.ReleaseKite
import io.github.releasekite.compose.ReleaseKiteActions
import io.github.releasekite.compose.ReleaseKiteHost
import io.github.releasekite.compose.ReleaseKiteSettingsSection
import io.github.releasekite.compose.ReleaseKiteUpdateDialog
import io.github.releasekite.compose.ReleaseKiteUpdateRow
import io.github.releasekite.core.UpdateUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.releasekite.sample.ui.theme.ReleaseKiteSampleTheme

/**
 * 示例主界面：
 *
 * 1. **真实链路**：`ReleaseKite.manager` 驱动 [ReleaseKiteUpdateRow] 与
 *    [ReleaseKiteSettingsSection]；[ReleaseKiteHost] 挂在根 `Box`，负责全局更新弹窗、
 *    生命周期与通知权限请求。
 * 2. **fake 状态**：下方按钮直接渲染无状态 [ReleaseKiteUpdateDialog]，用本地构造的
 *    `UpdateInfo` / `UpdateUiState` 走查 available / downloading / verifying / failed /
 *    permission / rollout 六种形态，不发起任何网络请求。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 可选：后台下载完成的通知点击会带 INSTALL_UPDATE extra，入口处可主动续装。
        // 不处理也没关系 —— ReleaseKiteHost 的 onAppResumed 会自动兜底。
        handleInstallUpdateExtra(intent)
        setContent {
            ReleaseKiteSampleTheme {
                SampleScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallUpdateExtra(intent)
    }

    private fun handleInstallUpdateExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) {
            // 从持久化记录恢复待安装 APK 并拉起系统安装器（跨进程可用）。
            ReleaseKite.manager.installDownloadedIfReady()
        }
    }

    private companion object {
        /** 与 `AndroidUpdateNotifier.EXTRA_INSTALL_UPDATE` 一致的公开字符串常量。 */
        const val EXTRA_INSTALL_UPDATE = "io.github.releasekite.extra.INSTALL_UPDATE"
    }
}

@Composable
private fun SampleScreen() {
    // Application 中已初始化；这里直接取单例。
    val manager = ReleaseKite.manager
    // 生命周期感知收集：STOPPED 时停止收集，避免后台无意义重组（与库内 Host 一致）。
    val state by manager.state.collectAsStateWithLifecycle()
    var fakePhase by remember { mutableStateOf<FakeUpdatePhase?>(null) }
    LaunchedEffect(fakePhase) {
        if (fakePhase == FakeUpdatePhase.DOWNLOADING || fakePhase == FakeUpdatePhase.VERIFYING) {
            delay(3_000L)
            fakePhase = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageHeader()

                CurrentStateCard(state)

                Button(
                    onClick = { manager.checkNow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即检查更新")
                }
                Text(
                    text = "手动检查会取消在途的自动检查，并向 GitHub API 发起一次真实请求；" +
                        "检查结果只更新页面状态，不会自动下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionTitle("ReleaseKiteUpdateRow（真实管理器状态）")
                ReleaseKiteUpdateRow(
                    manager = manager,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle("ReleaseKiteSettingsSection（真实设置区）")
                ReleaseKiteSettingsSection(
                    manager = manager,
                    modifier = Modifier.fillMaxWidth(),
                )

                FakeStateDemoCard(onShowFake = { fakePhase = it })

                ExpectedRejectionCard()

                ConfigCard()
            }
        }

        // 全局更新弹窗（每进程一次 onAppEntered、前后台、通知权限请求均由 Host 处理）。
        ReleaseKiteHost(manager = manager)

        // fake 弹窗：直接渲染无状态组件，避免接触真实下载链路。
        fakePhase?.let { phase ->
            val fakeState = FakeUpdateStates.uiState(
                phase = phase,
                currentVersionName = state.currentVersionName,
            )
            ReleaseKiteUpdateDialog(
                state = fakeState,
                actions = ReleaseKiteActions(
                    onDownload = { fakePhase = null },
                    onDismiss = { fakePhase = null },
                    onRetry = { fakePhase = null },
                    onOpenInstallSettings = { fakePhase = null },
                ),
            )
        }
    }
}

@Composable
private fun PageHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "ReleaseKite Sample",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "GitHub Release 应用内更新 · 可复用库示例",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CurrentStateCard(state: UpdateUiState) {
    InfoCard(title = "当前状态") {
        InfoRow("当前版本", state.currentVersionName)
        InfoRow("演示仓库", DemoSettings.repository)
        InfoRow("更新阶段", FakeUpdateStates.phaseLabel(state.phase))
        InfoRow("最近检查", formatTimestamp(state.lastCheckAtMillis))
        InfoRow("更新通道", FakeUpdateStates.channelLabel(state.channel))
        InfoRow("自动检查", if (state.autoCheckEnabled) "已开启" else "已关闭")
        InfoRow(
            "线路健康",
            if (state.routeHealth.isEmpty()) "暂无记录" else "${state.routeHealth.size} 条线路记录",
        )
    }
}

@Composable
private fun FakeStateDemoCard(onShowFake: (FakeUpdatePhase) -> Unit) {
    InfoCard(title = "fake 状态演示（不联网）") {
        Text(
            text = "以下按钮使用本地构造的 UpdateInfo/UpdateUiState 直接渲染无状态 " +
                "ReleaseKiteUpdateDialog，用来逐帧检查 UI；关闭弹窗后不会影响真实管理器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FakeUpdatePhase.entries.forEach { phase ->
            OutlinedButton(
                onClick = { onShowFake(phase) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(phase.buttonLabel)
            }
        }
    }
}

@Composable
private fun ExpectedRejectionCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "预期行为：真实下载会被安全校验拦截",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "示例应用默认查询公开的 ZhiBox 仓库。点「立即检查」可以走通真实的检查/弹窗/" +
                    "下载/sha256 校验链路，但下载到的是 ZhiBox 的 APK：安装前的包名、versionCode " +
                    "与签名证书校验一定会失败，并提示安装包无效。这是 ReleaseKite 的安全设计" +
                    "（只允许升级应用自身），不是示例 bug。若想观察成功安装，请把 " +
                    "DemoSettings.repository 换成你自己的仓库和签名产物。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "另一处示例差异：demo 关闭了 -update.json 元数据" +
                    "（metadataAssetSuffix = null）。示例版本 0.0.1 低于 ZhiBox 仓库的 " +
                    "minSupportedVersion（0.1.0），若开启元数据会在首启进入不可取消的强制更新，" +
                    "演示将被锁死；关闭后按 100%/非强制处理。真实接入请保留默认元数据。" ,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ConfigCard() {
    val config = remember { DemoSettings.config() }
    InfoCard(title = "ReleaseKiteConfig（示例配置）") {
        InfoRow("repository", config.repository)
        InfoRow("apkAssetPattern", config.apkAssetPattern.pattern)
        InfoRow(
            "downloadMirrors",
            if (config.downloadMirrors.isEmpty()) "空（仅直连 github.com）" else config.downloadMirrors.joinToString(),
        )
        InfoRow("checksumAssetSuffix", config.checksumAssetSuffix)
        InfoRow("metadataAssetSuffix", config.metadataAssetSuffix ?: "关闭（demo 防强制锁死）")
        InfoRow("requireChecksum", config.requireChecksum.toString())
        InfoRow("betaChannelEnabled", config.betaChannelEnabled.toString())
        InfoRow("userAgent", "库默认（releasekite 初始化时按库版本与包名生成）")
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 本地时间格式化；仅用于示例展示。 */
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "从未"
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(millis))
}
