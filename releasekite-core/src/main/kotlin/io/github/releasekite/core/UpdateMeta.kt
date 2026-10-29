package io.github.releasekite.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 发布元数据（Release 资产 `*-update.json`）：灰度比例与强制更新下限。
 *
 * 源实现见 ZhiBox `UpdateModels.kt` 的 `UpdateMeta` / `UpdateMetaParser`；
 * 解析失败抛 [UpdateException]（[UpdateFailure.Parse]），调用方负责降级为默认值。
 */

/** 灰度比例（0..100）+ 强制更新下限；默认 100% / 非强制。 */
data class UpdateMeta(
    val rolloutPercent: Int = 100,
    val minSupportedVersion: String? = null,
)

object UpdateMetaParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 `update.json`；schema != 1 或字段非法时抛 Parse。 */
    fun parse(body: String): UpdateMeta {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.Parse, "update.json 解析失败")
        }
        val schema = root["schema"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        if (schema != 1) {
            throw UpdateException(UpdateFailure.Parse, "update.json schema 不支持：$schema")
        }
        val rollout = root["rolloutPercent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100
        if (rollout !in 0..100) {
            throw UpdateException(UpdateFailure.Parse, "rolloutPercent 非法：$rollout")
        }
        val minSupported = root["minSupportedVersion"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (minSupported != null && parseVersion(minSupported) == null) {
            throw UpdateException(UpdateFailure.Parse, "minSupportedVersion 不可解析：$minSupported")
        }
        return UpdateMeta(rolloutPercent = rollout, minSupportedVersion = minSupported)
    }

    /** 将元数据合并进 [UpdateInfo]（缺省值保持 100% / 非强制）。 */
    fun apply(info: UpdateInfo, meta: UpdateMeta): UpdateInfo = info.copy(
        rolloutPercent = meta.rolloutPercent,
        minSupportedVersion = meta.minSupportedVersion,
    )
}
