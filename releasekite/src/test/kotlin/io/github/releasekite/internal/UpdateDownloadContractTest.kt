package io.github.releasekite.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.releasekite.core.DownloadRequest
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WorkManager 下载契约（[UpdateDownloadContract]）的映射/编码回归。
 *
 * Worker 真实执行依赖网络与前台服务，这里按任务书允许的方式「直接验证契约映射」：
 * - [DownloadRequest] ↔ WorkManager 输入 Data 全键往返；
 * - 失败编码 `Http:code:rateLimited` / 类名语义与 ZhiBox 一致；
 * - 非法输入（缺 version）的 Worker 快速失败路径（不需要网络）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateDownloadContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sampleRequest() = DownloadRequest(
        info = UpdateInfo(
            versionName = "1.2.3",
            tagName = "v1.2.3",
            title = "1.2.3",
            notes = "",
            publishedAt = null,
            htmlUrl = "https://github.com/releasekite/ReleaseKite/releases/tag/v1.2.3",
            apkUrl = "https://github.com/releasekite/ReleaseKite/releases/download/v1.2.3/releasekite-1.2.3.apk",
            apkSizeBytes = 12_345_678L,
            sha256 = "c".repeat(64),
            sha256Url = null,
        ),
        repository = "releasekite/ReleaseKite",
        filePrefix = "releasekite",
        userAgent = "ReleaseKite/0.1.0 (host.app)",
        mirrors = listOf("https://mirror.example.com/", "https://mirror2.example.com/"),
        requireChecksum = true,
        timeoutMs = 300_000L,
    )

    @Test
    fun inputDataMapsAllContractKeysAndRoundTrips() {
        val request = sampleRequest()
        val data = UpdateDownloadContract.inputData(request)

        // 契约 §2 Worker input keys（逐键断言，防止未来改名悄悄破坏跨进程契约）。
        assertEquals("1.2.3", data.getString("version"))
        assertEquals(request.info.apkUrl, data.getString("apk_url"))
        assertEquals(request.info.apkSizeBytes, data.getLong("apk_size", 0L))
        assertEquals(request.info.sha256, data.getString("sha256"))
        assertNull(data.getString("sha256_url"))
        assertEquals(request.info.htmlUrl, data.getString("html_url"))
        assertEquals("releasekite/ReleaseKite", data.getString("repository"))
        assertEquals("releasekite", data.getString("file_prefix"))
        assertEquals(request.userAgent, data.getString("user_agent"))
        assertEquals("https://mirror.example.com/\nhttps://mirror2.example.com/", data.getString("mirrors"))
        assertEquals(true, data.getBoolean("require_checksum", false))
        assertEquals(300_000L, data.getLong("timeout_ms", 0L))

        assertEquals(request, UpdateDownloadContract.parseRequest(data))
    }

    @Test
    fun failureCodecMatchesZhiboxSemantics() {
        assertEquals(
            "Http:403:true",
            UpdateDownloadContract.encodeUpdateFailure(UpdateFailure.Http(403, rateLimited = true)),
        )
        assertEquals(
            "Http:500:false",
            UpdateDownloadContract.encodeUpdateFailure(UpdateFailure.Http(500)),
        )
        assertEquals(
            "ChecksumMismatch",
            UpdateDownloadContract.encodeUpdateFailure(UpdateFailure.ChecksumMismatch),
        )

        assertEquals(UpdateFailure.Http(403, true), UpdateDownloadContract.decodeUpdateFailure("Http:403:true"))
        assertEquals(UpdateFailure.Timeout, UpdateDownloadContract.decodeUpdateFailure("Timeout"))
        assertEquals(UpdateFailure.ReleaseUnavailable, UpdateDownloadContract.decodeUpdateFailure("ReleaseUnavailable"))
        assertEquals(UpdateFailure.Network, UpdateDownloadContract.decodeUpdateFailure(null))
        assertEquals(UpdateFailure.Network, UpdateDownloadContract.decodeUpdateFailure(""))
        // 未知编码（未来新增失败类型）回退 Network，保证旧 Manager 不会崩溃。
        assertEquals(UpdateFailure.Network, UpdateDownloadContract.decodeUpdateFailure("FutureFailure"))
    }

    @Test
    fun missingVersionIsRejectedByParser() {
        assertNull(UpdateDownloadContract.parseRequest(Data.Builder().build()))
    }

    @Test
    fun workerFailsFastWithParseWhenVersionMissing() = runBlocking {
        val worker = TestListenableWorkerBuilder<ReleaseKiteDownloadWorker>(context)
            .setInputData(Data.Builder().build())
            .build()

        val result = worker.doWork()

        assertEquals(
            "Parse",
            result.outputData.getString(UpdateDownloadContract.OUT_FAILURE),
        )
    }

    @Test
    fun contractConstantsMatchFrozenApi() {
        assertEquals("releasekite-update-download", UpdateDownloadContract.UNIQUE_WORK)
        assertEquals("releasekite-update", UpdateDownloadContract.TAG)
        assertTrue(UpdateDownloadContract.UNIQUE_WORK.startsWith("releasekite-"))
    }
    @Test
    fun allFailuresUseStableWireCodes() {
        val failures = listOf(
            UpdateFailure.Network, UpdateFailure.Timeout, UpdateFailure.Parse,
            UpdateFailure.NoRelease, UpdateFailure.IncompleteRelease, UpdateFailure.NoChecksum,
            UpdateFailure.ChecksumMismatch, UpdateFailure.ApkInvalid, UpdateFailure.InsufficientSpace,
            UpdateFailure.InstallPermission, UpdateFailure.InstallFailed, UpdateFailure.FileUnavailable,
            UpdateFailure.Cancelled, UpdateFailure.ReleaseUnavailable, UpdateFailure.Http(429, true),
        )
        failures.forEach { failure ->
            assertEquals(failure, UpdateDownloadContract.decodeUpdateFailure(
                UpdateDownloadContract.encodeUpdateFailure(failure)))
        }
        assertEquals("ReleaseUnavailable", UpdateDownloadContract.encodeUpdateFailure(UpdateFailure.ReleaseUnavailable))
    }
}
