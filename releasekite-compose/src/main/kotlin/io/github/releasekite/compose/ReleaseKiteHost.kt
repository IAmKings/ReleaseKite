package io.github.releasekite.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.releasekite.ReleaseKite
import io.github.releasekite.core.UpdateManager

/**
 * 全局更新 UI 挂载点：把弹窗、生命周期钩子与通知权限一次性接好。
 *
 * 宿主只需在根部（如 `setContent` 的最外层 `Box`）放一个 `ReleaseKiteHost()`：
 * - **冷启动**：`LaunchedEffect` 调一次 [UpdateManager.onAppEntered]（manager 内部用原子标志
 *   保证每进程只执行一次，重建 Activity 不会重复检查）；
 * - **回到前台**：Lifecycle `ON_RESUME` 调 [UpdateManager.onAppResumed]，用于「安装未知应用」
 *   授权返回后自动续装、以及通知被拒/后台下载完成后的兜底续装；
 * - **前后台**：`ON_START`/`ON_STOP` 调 [ReleaseKite.onForeground]/[ReleaseKite.onBackground]，
 *   供 core 的 `isAppForeground` 判断（前台下载完成自动拉起安装器、后台发通知）；
 * - **通知权限**：Android 13+ 点「立即更新」时请求 `POST_NOTIFICATIONS`，**授权与否都继续下载**
 *   （拒绝只是收不到通知，不影响安装链）。是否可请求以 `LocalActivityResultRegistryOwner`
 *   是否存在为准（ComponentActivity/ContextThemeWrapper 等提供 registry 的宿主都能请求）；
 *   Preview 或没有 registry 的宿主降级为直接开始下载。
 *
 * 宿主用 `requestNotificationPermission = false` 可关闭权限请求（例如宿主自己统一管理权限）。
 */
@Composable
fun ReleaseKiteHost(
    // Compose lint（ModifierParameter）要求 Modifier 是第一个可选参数；manager 带默认值（契约）
    // 只能排在 modifier 之后。全部命名参数调用保持兼容（如 `ReleaseKiteHost(manager = m)`）。
    modifier: Modifier = Modifier,
    manager: UpdateManager = ReleaseKite.manager,
    requestNotificationPermission: Boolean = true,
    style: ReleaseKiteDialogStyle = ReleaseKiteDialogStyle(),
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 「立即更新」入口：需要时先请求通知权限，回调里无论授权与否都 startDownload。
    // 能否请求以是否存在 ActivityResultRegistry 为准（ComponentActivity、ContextThemeWrapper
    // 等宿主都可）；Preview/无 registry 时降级为直接下载。
    val requestDownload: () -> Unit = if (
        requestNotificationPermission &&
        !inPreview &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        activityResultRegistryOwner != null
    ) {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            // 通知权限被拒也继续后台下载：完成时若通知不可见，回到前台由 onAppResumed 续装。
            manager.startDownload()
        }
        val requestDownloadWithPermission: () -> Unit = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                manager.startDownload()
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestDownloadWithPermission
    } else {
        // 无 ActivityResultRegistry / Preview / 宿主关闭请求：直接开始下载。
        { manager.startDownload() }
    }

    // 冷启动入口；manager 内部保证每进程一次，Activity 重建时重复调用是安全的。
    LaunchedEffect(manager) { manager.onAppEntered() }

    // 前后台跟踪 + 回到前台续装。未初始化时（Preview/纯 UI 演示）跳过全局前后台通知。
    DisposableEffect(lifecycleOwner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    if (ReleaseKite.isInitialized()) ReleaseKite.onForeground()

                Lifecycle.Event.ON_RESUME -> manager.onAppResumed()
                Lifecycle.Event.ON_STOP ->
                    if (ReleaseKite.isInitialized()) ReleaseKite.onBackground()

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier) {
        ReleaseKiteUpdateDialog(
            state = state,
            actions = ReleaseKiteActions(
                onDownload = requestDownload,
                onDismiss = manager::dismiss,
                onRetry = manager::retry,
                onOpenInstallSettings = manager::openInstallPermissionSettings,
            ),
            style = style,
        )
    }
}
