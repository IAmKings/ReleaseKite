package io.github.releasekite.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.releasekite.ReleaseKite
import io.github.releasekite.core.*
import io.github.releasekite.internal.ReleaseKiteForegroundTracker
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseKiteHostLifecycleTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val tempFolder = org.junit.rules.TemporaryFolder()

    private fun withHost(test: (LifecycleRegistry, (Boolean) -> Unit) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val manager = UpdateManager(
            ReleaseKiteConfig("example/app"),
            object : UpdateEnvironment {
                override val workDir = tempFolder.newFolder()
                override val currentVersionName = "1.0.0"
                override val currentVersionCode = 1L
                override val isAppForeground get() = ReleaseKiteForegroundTracker.isForeground
                override fun log(message: String, error: Throwable?) = Unit
            },
            object : UpdateSource {
                override suspend fun fetchLatest(channel: UpdateChannel): Result<UpdateInfo> = error("Unexpected check")
            },
            object : UpdateDownloadScheduler {
                override suspend fun enqueue(request: DownloadRequest, onRequestId: (String) -> Unit) = Unit
                override fun observe() = MutableSharedFlow<DownloadWorkState>()
                override fun cancel(workId: String?) = Unit
            },
            object : UpdateInstaller {
                override fun canInstallPackages() = true
                override suspend fun install(apk: File) = InstallResult.Launched
                override fun openInstallPermissionSettings() = Unit
            },
            object : ApkValidator { override fun validate(apk: File): UpdateFailure? = null },
            InMemoryUpdateStore(autoCheckEnabled = false),
            object : UpdateNotifier {
                override fun prepare() = Unit
                override fun notifyDownloadComplete(info: UpdateInfo) = Unit
                override fun cancelDownloadComplete() = Unit
            }, scope,
        )
        val instance = ReleaseKite::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        instance.set(null, manager)
        repeat(10) { ReleaseKite.onBackground() }
        val shown = mutableStateOf(true)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        try {
            composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            composeRule.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        if (shown.value) ReleaseKiteHost(manager = manager, requestNotificationPermission = false)
                    }
                }
            }
            composeRule.waitForIdle()
            assertTrue(ReleaseKiteForegroundTracker.isForeground)
            test(owner.lifecycle) { visible ->
                composeRule.runOnIdle { shown.value = visible }
                composeRule.waitForIdle()
            }
        } finally {
            composeRule.runOnIdle { shown.value = false }
            composeRule.waitForIdle()
            repeat(10) { ReleaseKite.onBackground() }
            instance.set(null, previous)
            scope.cancel()
        }
    }

    @Test
    fun removeAndRemountBalancesForegroundReferences() = withHost { _, show ->
        show(false)
        assertFalse(ReleaseKiteForegroundTracker.isForeground)
        show(true)
        assertTrue(ReleaseKiteForegroundTracker.isForeground)
        show(false)
        assertFalse(ReleaseKiteForegroundTracker.isForeground)
    }

    @Test
    fun stopThenDisposeDoesNotRemoveAnotherWindowsReference() = withHost { lifecycle, show ->
        ReleaseKite.onForeground()
        composeRule.runOnIdle { lifecycle.currentState = Lifecycle.State.CREATED }
        show(false)
        assertTrue(ReleaseKiteForegroundTracker.isForeground)
        ReleaseKite.onBackground()
        assertFalse(ReleaseKiteForegroundTracker.isForeground)
    }
}
