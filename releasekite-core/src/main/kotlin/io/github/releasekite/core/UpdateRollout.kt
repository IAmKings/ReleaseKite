package io.github.releasekite.core

/**
 * 灰度与强制更新的纯逻辑（逐段移植 ZhiBox `UpdateRollout.kt`）。
 *
 * - 分桶：`sha256(installId + "|" + versionName)` 前 8 个字节转非负 Long 后取模 100；
 *   同一安装/版本结果稳定，仅本地计算，不上报；
 * - 命中：`rolloutPercent >= 100 || cohort < rolloutPercent`；
 * - 强制：当前版本低于 `minSupportedVersion`（强制优先于灰度/取消）。
 */
object UpdateRollout {

    /** 返回 0..99 的稳定分桶值（绑定 installId + versionName）。 */
    fun cohort(installId: String, versionName: String): Int {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$installId|$versionName".toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        return (value and Long.MAX_VALUE).mod(100L).toInt()
    }

    /** 当前安装是否落在该版本的灰度比例内。 */
    fun isEligible(installId: String, info: UpdateInfo): Boolean =
        info.rolloutPercent >= 100 || cohort(installId, info.versionName) < info.rolloutPercent

    /** 是否强制更新（当前版本低于 minSupportedVersion）。 */
    fun isForced(info: UpdateInfo, currentVersionName: String): Boolean =
        info.minSupportedVersion?.let { isNewerVersion(it, currentVersionName) } ?: false
}
