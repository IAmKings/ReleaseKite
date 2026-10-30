package io.github.releasekite.internal

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [AndroidApkValidator] 签名读取回退回归（三方深审 P2）。
 *
 * 回归：API 28+ 某些实现 `signingInfo` 为 null，但旧字段 `signatures` 有值；
 * 只看 signingInfo 会把有效 APK 误判为 ApkInvalid（安装被拒）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class AndroidApkValidatorSignatureTest {

    @Test
    fun fallsBackToLegacySignaturesWhenSigningInfoMissing() {
        val cert = "releasekite-legacy-cert".toByteArray()
        val packageInfo = PackageInfo().apply {
            // 模拟 API 28+ 但解析器只填了旧字段：signingInfo == null。
            signatures = arrayOf(Signature(cert))
        }

        val digests = packageInfo.apkSignatureDigests()

        assertEquals("应回退 deprecated signatures 而不是误判无签名", 1, digests.size)
        assertEquals(sha256Hex(cert), digests.first())
    }

    @Test
    fun returnsEmptySetWhenNoSignatureInfoAtAll() {
        assertTrue("无任何签名信息时应返回空集合（调用方判 ApkInvalid）", PackageInfo().apkSignatureDigests().isEmpty())
    }

    @Test
    fun prefersSigningCertificatesWhenSigningInfoAvailable() {
        val cert = "releasekite-modern-cert".toByteArray()
        val signature = Signature(cert)
        // Robolectric 的 SigningInfo 构造器不会填充 apkContentsSigners；
        // 用官方 ShadowSigningInfo#setSignatures 模拟 API 28+ 正常提供 APK 签名。
        val signingInfo = SigningInfo().also { info ->
            Shadows.shadowOf(info).setSignatures(arrayOf(signature))
        }
        val packageInfo = PackageInfo().apply {
            this.signingInfo = signingInfo
        }

        assertEquals(setOf(sha256Hex(cert)), packageInfo.apkSignatureDigests())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
