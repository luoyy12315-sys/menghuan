package com.menghuan.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条对话接口配置。v0.35.0 起用户可存多条（DeepSeek / 智谱 / OpenAI…），
 * 在聊天气泡页**长按头像**即可切换当前生效的接口。
 */
data class ApiProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 生图接口配置（与对话接口相互独立）。
 * 走 OpenAI 兼容的 `/images/generations`（各家路径不同，baseUrl 填到版本段为止）。
 */
data class ImageApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun toApiConfig(): ApiConfig = ApiConfig(baseUrl = baseUrl, apiKey = apiKey, model = model)
}

/** 默认接口的固定 id（老版本单接口配置迁过来时用它，保证升级后 id 稳定） */
const val DEFAULT_PROFILE_ID = "default"

/**
 * 落盘形态：密钥优先存 Keystore 密文（keyEnc）；
 * Keystore 不可用时（JVM 单测 / 极少数机型）退回明文（keyPlain），与 v0.2.3 的口径一致。
 */
data class StoredApiProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val keyEnc: String,
    val keyPlain: String,
) {
    companion object {
        fun of(id: String, name: String, baseUrl: String, model: String, key: String): StoredApiProfile {
            val sealed = if (key.isEmpty()) "" else (ApiKeyCipher.encrypt(key) ?: "")
            return StoredApiProfile(
                id = id,
                name = name,
                baseUrl = baseUrl,
                model = model,
                keyEnc = sealed,
                keyPlain = if (sealed.isEmpty()) key else "",
            )
        }
    }
}

/** 多接口配置的纯函数编解码器（JSON 数组；单测覆盖往返与脏数据容错） */
object ApiProfileCodec {

    fun encode(profiles: List<StoredApiProfile>): String {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("model", p.model)
                    .put("keyEnc", p.keyEnc)
                    .put("keyPlain", p.keyPlain)
            )
        }
        return array.toString()
    }

    /** 宽容解析：整体不是 JSON 就当没有；单条缺 id 就跳过，其余字段缺失补空串 */
    fun decode(raw: String): List<StoredApiProfile> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                add(
                    StoredApiProfile(
                        id = id,
                        name = obj.optString("name").trim().ifEmpty { "接口 ${size + 1}" },
                        baseUrl = obj.optString("baseUrl").trim(),
                        model = obj.optString("model").trim(),
                        keyEnc = obj.optString("keyEnc").trim(),
                        keyPlain = obj.optString("keyPlain"),
                    )
                )
            }
        }
    }
}