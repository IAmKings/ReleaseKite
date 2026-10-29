package io.github.releasekite.core

/**
 * 可比较的应用版本（semver 子集，逐段移植 ZhiBox `AppVersion` / `parseAppVersion`）。
 *
 * 规则（与源完全一致，`UpdateModelsTest` 覆盖）：
 * - `v`/`V` 前缀、段数不足、`+build` 后缀容错；
 * - 预发布后缀 `0.1.7-beta.2 < 0.1.7-rc.1 < 0.1.7`（无预发布 > 有预发布）；
 * - 数字标识符按数值比较、字母按字典序、数字 < 字母、前缀短者更小；
 * - 任一无法解析时 `isNewerVersion` 保守返回 false（不打扰用户）。
 */
data class SemanticVersion(
    val raw: String,
    val parts: List<Int>,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val left = parts.getOrElse(i) { 0 }
            val right = other.parts.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        val shared = minOf(prerelease.size, other.prerelease.size)
        for (i in 0 until shared) {
            val cmp = compareIdentifier(prerelease[i], other.prerelease[i])
            if (cmp != 0) return cmp
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }
}

/** 解析版本号；不可解析返回 null（等价源 `parseAppVersion`）。 */
fun parseVersion(raw: String): SemanticVersion? {
    val text = raw.trim().removePrefix("v").removePrefix("V")
    if (text.isEmpty()) return null
    val withoutBuild = text.substringBefore('+')
    val core = withoutBuild.substringBefore('-')
    if (core.isEmpty()) return null
    val parts = core.split('.').map { segment ->
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    }
    if (parts.isEmpty()) return null
    val prerelease = withoutBuild.substringAfter('-', missingDelimiterValue = "")
        .split('.')
        .filter { it.isNotBlank() }
    return SemanticVersion(raw, parts, prerelease)
}

/** 远端版本是否严格比当前版本新；任一无法解析时返回 false（保守不发提示）。 */
fun isNewerVersion(remote: String, current: String): Boolean {
    val remoteVersion = parseVersion(remote) ?: return false
    val currentVersion = parseVersion(current) ?: return false
    return remoteVersion > currentVersion
}
