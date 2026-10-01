package com.ruxi.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** 请求协议。[OPENAI] 走 /chat/completions，[ANTHROPIC] 走 /v1/messages。 */
enum class Protocol(val id: String, val label: String) {
    OPENAI("openai", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Anthropic"),
    ;

    companion object {
        fun from(id: String?): Protocol =
            Protocol.entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENAI
    }
}

/**
 * 供应商配置。支持多份并存、随时切换。
 *
 * [apiKeys] 为轮换池（多个 key 依次使用，遇到 429/额度错误自动切下一个），
 * 与 [apiKey] 二选一 —— 池非空时以池为准。
 */
@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新供应商",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val apiKeys: List<String> = emptyList(),
    val model: String = "gpt-4o-mini",
    val protocol: String = Protocol.OPENAI.id,
    val temperature: Float = 0.85f,
    val maxTokens: Int = 2048,
    /** 额外请求头，一行一个 `Key: Value`。中转站常要求自定义头。 */
    val extraHeaders: String = "",
) {
    val proto: Protocol get() = Protocol.from(protocol)

    /** 实际可用的 key 列表（池优先） */
    val effectiveKeys: List<String>
        get() = apiKeys.filter { it.isNotBlank() }.ifEmpty {
            if (apiKey.isNotBlank()) listOf(apiKey) else emptyList()
        }

    /** 展示用：打码后的 key */
    val maskedKey: String
        get() = effectiveKeys.firstOrNull()?.let { k ->
            if (k.length <= 10) "***" else "${k.take(6)}…${k.takeLast(4)}"
        } ?: "未填写"

    /** 规范化 base，去掉尾部斜杠 */
    fun normalizedBase(): String = baseUrl.trim().trimEnd('/')

    /** 拼接实际请求端点 */
    fun endpoint(): String {
        val base = normalizedBase()
        return when (proto) {
            Protocol.OPENAI -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            // anthropic 官方 base 不带 /v1，这里兼容两种写法
            Protocol.ANTHROPIC -> when {
                base.endsWith("/messages") -> base
                base.endsWith("/v1") -> "$base/messages"
                else -> "$base/v1/messages"
            }
        }
    }
}
