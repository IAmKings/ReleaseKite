package io.github.releasekite.internal

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.releasekite.core.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker 前台服务启动 best-effort 回归（三方深审 P1）。
 *
 * Android 12+ 后台 FGS 启动受限：`prepare()/setForeground()` 抛异常时不能逃出 `doWork()`
 * （否则 WorkManager 置终态 FAILED 且无 failure Data，Manager 误报 Network）。
 * 这里直接测 [startForegroundBestEffort] 的语义：异常吞掉并记日志、取消异常继续传播、
 * 正常路径确实把进度通知交给 setForeground。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseKiteDownloadWorkerForegroundTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notifier = AndroidUpdateNotifier(context)

    private val info = UpdateInfo(
        versionName = "2.0.0",
        tagName = "v2.0.0",
        title = "2.0.0",
        notes = "",
        publishedAt = null,
        htmlUrl = "https://github.com/releasekite/ReleaseKite/releases/tag/v2.0.0",
        apkUrl = "https://github.com/releasekite/ReleaseKite/releases/download/v2.0.0/releasekite-2.0.0.apk",
        apkSizeBytes = 42L,
        sha256 = "d".repeat(64),
        sha256Url = null,
    )

    @Test
    fun foregroundStartFailureIsSwallowedAndLogged() = runBlocking {
        var loggedMessage: String? = null
        var loggedError: Throwable? = null

        startForegroundBestEffort(
            notifier = notifier,
            info = info,
            setForeground = { throw IllegalStateException("FGS start not allowed in background") },
            log = { message, error ->
                loggedMessage = message
                loggedError = error
            },
        )

        assertNotNull("FGS 启动异常必须被记录（可诊断）", loggedMessage)
        assertTrue("日志应说明降级为无 FGS 下载", loggedMessage!!.contains("无 FGS"))
        assertTrue("原始异常应传给日志", loggedError is IllegalStateException)
    }

    @Test
    fun foregroundSuccessPassesProgressNotificationToSetForeground() = runBlocking {
        var notification: Notification? = null
        var failureLogged = false

        startForegroundBestEffort(
            notifier = notifier,
            info = info,
            setForeground = { notification = it },
            log = { _, _ -> failureLogged = true },
        )

        assertNotNull("正常路径必须调用 setForeground", notification)
        assertFalse("正常路径不应记录失败日志", failureLogged)
    }

    @Test
    fun foregroundCancellationIsPropagated() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                startForegroundBestEffort(
                    notifier = notifier,
                    info = info,
                    setForeground = { throw CancellationException("worker stopped") },
                    log = { _, _ -> },
                )
            }
        }
    }
}
