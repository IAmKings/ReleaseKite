package io.github.releasekite.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.releasekite.core.ApkValidator
import io.github.releasekite.core.UpdateFailure
import java.io.File
import java.security.MessageDigest

/**
 * APK 安装前校验（契约 §2）：包名、versionCode、签名证书三重比对，防止被替换的安装包。
 *
 * 与 ZhiBox `AndroidApkValidator` 行为一致：
 * - 只允许安装本应用自身（`archive.packageName == context.packageName`）；
 * - 目标 versionCode 必须严格大于当前版本（防降级）；
 * - 签名证书 SHA-256 集合必须与当前安装完全一致（防重签名包）；
 * - 文件缺失/解析失败/读取异常统一映射为 [UpdateFailure.ApkInvalid] 或
 *   [UpdateFailure.FileUnavailable]，由 Manager 走可重试失败态。
 */
class AndroidApkValidator(context: Context) : ApkValidator {

    private val appContext = context.applicationContext

    override fun validate(apk: File): UpdateFailure? {
        if (!apk.isFile || apk.length() <= 0) return UpdateFailure.FileUnavailable
        val pm = appContext.packageManager
        // GET_SIGNATURES 在 API 28+ 被 GET_SIGNING_CERTIFICATES 取代，但为兼容旧签名仍保留（与源一致）。
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = try {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return UpdateFailure.ApkInvalid

        if (archive.packageName != appContext.packageName) return UpdateFailure.ApkInvalid

        val archiveVersionCode = archive.longVersionCodeCompat()
        val currentVersionCode = try {
            pm.getPackageInfo(appContext.packageName, 0).longVersionCodeCompat()
        } catch (e: Exception) {
            return UpdateFailure.ApkInvalid
        }
        if (archiveVersionCode <= currentVersionCode) return UpdateFailure.ApkInvalid

        val archiveCerts = archive.signatureSha256Set()
        val currentCerts = try {
            pm.getPackageInfo(appContext.packageName, flags).signatureSha256Set()
        } catch (e: Exception) {
            return UpdateFailure.ApkInvalid
        }
        if (archiveCerts.isEmpty() || archiveCerts != currentCerts) return UpdateFailure.ApkInvalid
        return null
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signatureSha256Set(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            signatures.orEmpty()
        }
        return signatures.mapTo(LinkedHashSet()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
