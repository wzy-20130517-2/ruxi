package com.ruxi.app.data.api

import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Effort
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

            // ── 思考强度 ──
            // OpenAI 系（含 DeepSeek / 多数中转）认 `reasoning_effort`，
            // 取值 minimal / low / medium / high。关闭时不发这个字段，
            // 免得某些不支持的后端因为未知字段直接 400。
            when (provider.effortLevel) {
                Effort.NONE -> Unit
                Effort.LOW -> put("reasoning_effort", "low")
                Effort.MEDIUM -> put("reasoning_effort", "medium")
                Effort.HIGH -> put("reasoning_effort", "high")
            }

            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        // 先算出真正能发出去的图：读不到的（文件被删/路径失效）直接跳过。
                        // 一张坏图会让上游整条请求报 invalid_request_error，拖死整个对话。
                        val usable = msg.images.mapNotNull { img ->
                            toDataUrl(img)?.let { url ->
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", url) }
                                }
                            }
                        }
                        if (usable.isEmpty()) {
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
                                usable.forEach { add(it) }
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

            // ── 思考强度 ──
            // Anthropic 用 `thinking.budget_tokens`，且**必须小于 max_tokens**，
            // 否则上游直接报错。所以这里取两者较小值再留点余量给正文。
            when (val eff = provider.effortLevel) {
                Effort.NONE -> Unit
                else -> {
                    val budget = minOf(eff.budgetTokens, (provider.maxTokens * 0.75f).toInt())
                    if (budget >= 1024) {
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("budget_tokens", budget)
                        }
                    }
                }
            }

            putJsonArray("messages") {
                turns.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        val usable = msg.images.mapNotNull { img ->
                            readBase64(img.path)?.let { data ->
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", mediaTypeOf(img.path))
                                        put("data", data)
                                    }
                                }
                            }
                        }
                        if (usable.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            put("content", buildJsonArray {
                                usable.forEach { add(it) }
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

    /**
     * 读文件转 data URL。读不到（文件被删/路径失效）返回 null。
     *
     * ⚠️ 不能返回空串。空串会生成 `"url": ""`，上游会以
     * `param: image_url / invalid_request_error` 整条拒绝请求 ——
     * 一张坏图能让整个对话再也发不出去。调用方必须跳过 null 的那些。
     */
    private fun toDataUrl(img: AttachedImage): String? = runCatching {
        val f = File(img.path)
        if (!f.exists()) return@runCatching null
        val bytes = f.readBytes()
        if (bytes.isEmpty()) return@runCatching null
        "data:${mediaTypeOf(img.path)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }.getOrNull()

    private fun readBase64(path: String): String? = runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching null
        val bytes = f.readBytes()
        if (bytes.isEmpty()) return@runCatching null
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

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
