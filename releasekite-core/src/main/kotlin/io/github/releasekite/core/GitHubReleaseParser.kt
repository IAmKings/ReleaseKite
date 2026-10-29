package io.github.releasekite.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * markdown → 纯文本（更新说明只在弹窗里展示概要，完整内容跳浏览器）。
 *
 * 不做完整 markdown 渲染：去标题符号/加粗/行内代码/链接/引用，压缩空行并截断。
 * 与源 `plainTextFromMarkdown` 完全一致，仅上限改为 [UpdateDefaults.NOTES_LIMIT] 可覆盖。
 */
fun plainTextFromMarkdown(markdown: String, limit: Int = UpdateDefaults.NOTES_LIMIT): String {
    var text = markdown
        .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^#{1,6}\s*"""), "")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""__(.+?)__"""), "$1")
        .replace(Regex("""`([^`]*)`"""), "$1")
        .replace(Regex("""(?m)^>\s?"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    if (text.length > limit) {
        text = text.take(limit).trimEnd() + "…"
    }
    return text
}

/**
 * GitHub Release JSON 解析（`releases/latest` 与 `releases?per_page=N`）。
 *
 * 移植自 ZhiBox `UpdateReleaseParser`，差异仅在于资产挑选使用
 * [ReleaseKiteConfig.apkAssetPattern]（默认 `.*\.apk$`，源为 `-release-signed.apk` 后缀），
 * 以及校验值要求受 [ReleaseKiteConfig.requireChecksum] 控制。
 *
 * 关键行为（不得简化，相关回归提交见各分支注释）：
 * - 0 个或多个 APK 资产 → `IncompleteRelease`（多 APK 不能猜测，失败安全）；
 * - APK/SHA 下载地址必须是 `https://github.com/`；元数据地址只要求 https
 *   （host 白名单在 [GitHubUpdateSource] 拉取时逐跳校验）；
 * - `requireChecksum=true` 且 digest 与 `.sha256` 资产皆无 → `NoChecksum`；
 * - `notes` 为空保持空串（不再在 core 写中文兜底，由 UI 本地化）；
 * - 元数据资产按 `endsWith(config.metadataAssetSuffix)` 选取，suffix 为 null 时跳过。
 */
object GitHubReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("^[a-f0-9]{64}$")

    /** stable 通道：`releases/latest`（draft/prerelease 防御性拒绝）。 */
    fun parseLatestRelease(body: String, config: ReleaseKiteConfig): UpdateInfo {
        val root = parseObject(body)
        if (bool(root, "draft") || bool(root, "prerelease")) {
            throw UpdateException(UpdateFailure.NoRelease, "latest release 是 draft/prerelease")
        }
        return parseReleaseObject(root, UpdateChannel.STABLE, config)
    }

    /** beta 通道：`releases?per_page=N` 列表，取非 draft 的最高 semver（含 prerelease）。 */
    fun parseReleaseList(body: String, config: ReleaseKiteConfig): UpdateInfo {
        val array = try {
            json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.Parse, "release 列表 json 解析失败")
        }
        val candidates = array.filter { !bool(it, "draft") }
        if (candidates.isEmpty()) throw UpdateException(UpdateFailure.NoRelease)
        val best = candidates.maxByOrNull { root ->
            // 不可解析的 tag 排最后（与源一致：空版本 + 空段数）。
            parseVersion(str(root, "tag_name").orEmpty()) ?: SemanticVersion("", emptyList())
        } ?: throw UpdateException(UpdateFailure.NoRelease)
        val channel = if (bool(best, "prerelease")) UpdateChannel.BETA else UpdateChannel.STABLE
        return parseReleaseObject(best, channel, config)
    }

    /** 解析 `.sha256` 资产内容（`<hex>  <filename>` 或纯 hex）。 */
    fun parseSha256File(text: String): String? {
        val hex = text.trim().substringBefore(' ').substringBefore('\t').lowercase()
        return hex.takeIf { sha256Regex.matches(it) }
    }

    private fun parseReleaseObject(
        root: JsonObject,
        channel: UpdateChannel,
        config: ReleaseKiteConfig,
    ): UpdateInfo {
        val tagName = str(root, "tag_name")?.trim().orEmpty()
        parseVersion(tagName)
            ?: throw UpdateException(UpdateFailure.Parse, "tag_name 不可解析：$tagName")
        val versionName = tagName.removePrefix("v").removePrefix("V")

        val assets = try {
            root["assets"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.Parse, "assets 解析失败")
        }

        fun assetName(asset: JsonObject): String = str(asset, "name").orEmpty()

        // 泛化点：资产挑选由 config.apkAssetPattern 决定（默认任意 .apk）。
        val apkAssets = assets.filter { config.apkAssetPattern.matches(assetName(it)) }
        if (apkAssets.isEmpty()) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "release 缺少匹配的 APK 资产")
        }
        if (apkAssets.size > 1) {
            // 单一 universal APK 契约：多 APK（ABI split 等）不能猜测，失败安全（5f598bb）。
            throw UpdateException(
                UpdateFailure.IncompleteRelease,
                "release 包含多个 APK 资产，无法确定下载目标",
            )
        }
        val apkAsset = apkAssets.first()

        val apkName = assetName(apkAsset)
        val apkUrl = str(apkAsset, "browser_download_url").orEmpty()
        if (!apkUrl.startsWith("https://github.com/")) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "apk 下载地址非法：$apkUrl")
        }
        val apkSize = str(apkAsset, "size")?.toLongOrNull() ?: 0L

        val digest = str(apkAsset, "digest")
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { sha256Regex.matches(it) }

        // 元数据资产可整体关闭（config.metadataAssetSuffix == null → 不识别、不拉取）。
        val metaSuffix = config.metadataAssetSuffix
        val metaAsset = if (metaSuffix == null) {
            null
        } else {
            assets.firstOrNull { assetName(it).endsWith(metaSuffix) }
        }
        // 初始地址只要求 https；host 白名单由 GitHubUpdateSource 拉取元数据时逐跳校验。
        val metaUrl = metaAsset?.let { str(it, "browser_download_url") }
            ?.takeIf { it.startsWith("https://") }

        val shaAsset = assets.firstOrNull {
            assetName(it) == apkName + config.checksumAssetSuffix
        }
        val shaUrl = shaAsset?.let { str(it, "browser_download_url") }
            ?.takeIf { it.startsWith("https://github.com/") }
        if (config.requireChecksum) {
            if (digest == null && shaUrl == null) {
                throw UpdateException(
                    UpdateFailure.NoChecksum,
                    "release 缺少 SHA-256（digest 与 ${config.checksumAssetSuffix} 均无）",
                )
            }
        }

        val htmlUrl = str(root, "html_url").orEmpty()
        val notes = plainTextFromMarkdown(str(root, "body").orEmpty())

        return UpdateInfo(
            versionName = versionName,
            tagName = tagName,
            title = str(root, "name")?.trim().orEmpty().ifBlank { tagName },
            // 与源不同：notes 为空保持空串（不再在 core 写中文兜底）。
            notes = notes,
            publishedAt = str(root, "published_at"),
            htmlUrl = htmlUrl,
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            sha256 = digest,
            sha256Url = shaUrl,
            channel = channel,
            metaUrl = metaUrl,
        )
    }

    private fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw UpdateException(UpdateFailure.Parse, "release json 解析失败")
    }

    private fun str(node: JsonObject, key: String): String? =
        node[key]?.jsonPrimitive?.contentOrNull

    private fun bool(node: JsonObject, key: String): Boolean =
        str(node, key)?.toBooleanStrictOrNull() ?: false
}
