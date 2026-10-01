package com.ruxi.app.data.api

import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Protocol
import com.ruxi.app.data.model.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import android.util.Base64

/**
 * 把内部消息模型翻译成两家协议各自要的 JSON。
 *
 * ## 为什么单独抽出来
 * OpenAI 和 Anthropic 的差异不只是 URL：
 * - system 消息：OpenAI 放 messages 数组里，Anthropic 是**顶层字段**
 * - 图片：OpenAI 用 `image_url` + data URL，Anthropic 用 `source.base64` + 独立字段
 * - 角色名：Anthropic 只有 user/assistant，system 不能混在数组里
 *
 * 混在一个函数里写必然到处是 if，抽成两个 builder 清晰得多。
 */
object RequestBuilder {

    private val json = Json { encodeDefaults = true }

    fun buildBody(
        provider: Provider,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): String = when (provider.proto) {
        Protocol.OPENAI -> buildOpenAi(provider, messages, stream)
        Protocol.ANTHROPIC -> buildAnthropic(provider, messages, stream)
    }

    // ─────────────────────── OpenAI 协议 ───────────────────────

    private fun buildOpenAi(
        provider: Provider,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): String {
        val obj = buildJsonObject {
            put("model", provider.model)
            put("stream", stream)
            put("temperature", provider.temperature)
            put("max_tokens", provider.maxTokens)

            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        if (msg.images.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            // 多模态：content 是数组
                            put("content", buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    })
                                }
                                msg.images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", toDataUrl(img))
                                        }
                                    })
                                }
                            })
                        }
                    })
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ─────────────────────── Anthropic 协议 ───────────────────────

    private fun buildAnthropic(
        provider: Provider,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): String {
        // system 在 Anthropic 里是顶层字段，且只能有一段 —— 多段拼起来
        val systemText = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }

        val turns = messages.filter { it.role != "system" }

        val obj = buildJsonObject {
            put("model", provider.model)
            put("stream", stream)
            put("temperature", provider.temperature)
            put("max_tokens", provider.maxTokens)
            if (systemText != null) put("system", systemText)

            putJsonArray("messages") {
                turns.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        if (msg.images.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            put("content", buildJsonArray {
                                msg.images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", mediaTypeOf(img.path))
                                            put("data", readBase64(img.path))
                                        }
                                    })
                                }
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    })
                                }
                            })
                        }
                    })
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ─────────────────────── 工具 ───────────────────────

    /** 读文件转 data URL。失败返回空串（调用方会得到一张空图，但不会崩） */
    private fun toDataUrl(img: AttachedImage): String = runCatching {
        val bytes = File(img.path).readBytes()
        "data:${mediaTypeOf(img.path)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }.getOrDefault("")

    private fun readBase64(path: String): String = runCatching {
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun mediaTypeOf(path: String): String = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }

    /** 解析 `Key: Value` 形式的额外请求头 */
    fun parseExtraHeaders(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains(':') && !it.startsWith("#") }
            .associate { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
}
