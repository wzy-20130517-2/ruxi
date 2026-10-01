package com.ruxi.app.data.api

import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Protocol
import com.ruxi.app.data.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式聊天客户端。
 *
 * ## 连接池隔离（踩过的坑）
 * CCM 项目里出现过「一次流超时后，同进程后续所有请求全部卡死、重启才恢复」。
 * 根因是坏连接留在 HTTP 连接池里被后续请求复用。
 *
 * 这里从设计上规避：[streamClient] 是**独立实例**，且每个流式请求都带
 * `Connection: close`，连接用完即弃，坏连接无法污染下一个请求。
 * 非流式请求（如「测试连接」）走 [normalClient]，保留连接复用。
 *
 * ## 超时设计
 * - connectTimeout 60s：只管**建连**阶段，快速失败好过干等
 * - readTimeout 0（不限）：流式响应可能长时间没有新 token（模型在思考），
 *   设 readTimeout 会把正常的慢响应误杀。真正的兜底交给上层的手动 watchdog。
 */
class ChatClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 流式专用：独立连接池 + 不限制读取超时 */
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 普通请求：用于测试连通性等短请求 */
    private val normalClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 发起流式对话。
     *
     * 返回的 Flow 会依次吐出 [StreamEvent]：
     * - [StreamEvent.Delta] 增量文本
     * - [StreamEvent.Done]  正常结束
     * - [StreamEvent.Failed] 出错（含可读的中文提示）
     */
    fun stream(
        provider: Provider,
        messages: List<ChatMessage>,
        keys: List<String> = provider.effectiveKeys,
    ): Flow<StreamEvent> = callbackFlow {
        val key = keys.firstOrNull().orEmpty()
        if (key.isBlank()) {
            trySend(StreamEvent.Failed("未配置 API Key，请到「设置 → 供应商」填写"))
            close(); return@callbackFlow
        }

        val body = RequestBuilder.buildBody(provider, messages, stream = true)
        val request = buildRequest(provider, key, body, stream = true)

        var call: Call? = null
        var source: BufferedSource? = null

        try {
            call = streamClient.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                val errText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                trySend(StreamEvent.Failed(describeHttpError(response.code, errText)))
                response.close()
                close(); return@callbackFlow
            }

            source = response.body?.source()
            if (source == null) {
                trySend(StreamEvent.Failed("服务器返回了空响应体"))
                response.close()
                close(); return@callbackFlow
            }

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue

                // SSE 格式：`data: xxx` / `data:xxx` 都见过，统一处理
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue

                if (payload == "[DONE]") {
                    trySend(StreamEvent.Done)
                    break
                }

                val delta = parseDelta(provider.proto, payload)
                when (delta) {
                    is DeltaResult.Text -> if (delta.text.isNotEmpty()) {
                        trySend(StreamEvent.Delta(delta.text))
                    }
                    is DeltaResult.Reasoning -> if (delta.text.isNotEmpty()) {
                        trySend(StreamEvent.Reasoning(delta.text))
                    }
                    is DeltaResult.Finished -> {
                        trySend(StreamEvent.Done)
                        break
                    }
                    is DeltaResult.Error -> {
                        trySend(StreamEvent.Failed(delta.message))
                        break
                    }
                    DeltaResult.Ignore -> Unit
                }
            }

            // 流自然结束但没收到 [DONE]（部分中转站如此），也认为完成
            if (!isClosedForSend) trySend(StreamEvent.Done)
        } catch (e: IOException) {
            if (!isClosedForSend) {
                trySend(StreamEvent.Failed(friendlyMessage(e)))
            }
        } catch (e: Exception) {
            if (!isClosedForSend) {
                trySend(StreamEvent.Failed("请求异常：${e.message ?: e::class.simpleName}"))
            }
        } finally {
            runCatching { source?.close() }
            runCatching { call?.cancel() }
            close()
        }

        awaitClose { runCatching { call?.cancel() } }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式的连通性测试。返回 (是否成功, 说明文本)
     *
     * ⚠️ 必须 withContext(Dispatchers.IO)：`suspend` **不会**自动切线程，
     * 调用方通常在 rememberCoroutineScope()（主线程调度器）里调用它，
     * 直接 execute() 会抛 NetworkOnMainThreadException。
     */
    suspend fun test(provider: Provider): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = provider.effectiveKeys.firstOrNull().orEmpty()
        if (key.isBlank()) return@withContext false to "未配置 API Key"

        try {
            val body = RequestBuilder.buildBody(
                provider,
                listOf(ChatMessage(role = "user", content = "hi")),
                stream = false,
            )
            val request = buildRequest(provider, key, body, stream = false)
            normalClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "连接正常 · ${provider.model}"
                } else {
                    val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    false to describeHttpError(resp.code, text)
                }
            }
        } catch (e: Exception) {
            false to friendlyMessage(e)
        }
    }

    /**
     * 拉取可用模型列表（GET /models）。
     *
     * 返回 (模型 id 列表, 错误说明)。失败时列表为空。
     *
     * 注意：不是所有中转站都实现 /models，有些会返回 404 或空数组。
     * 这不算错误 —— 调用方应该让用户仍能手动输入模型名。
     */
    suspend fun listModels(provider: Provider): Pair<List<String>, String?> =
        withContext(Dispatchers.IO) {
            val key = provider.effectiveKeys.firstOrNull().orEmpty()
            if (key.isBlank()) return@withContext emptyList<String>() to "未配置 API Key"

            val base = provider.normalizedBase()
            // Anthropic 的模型列表端点与 OpenAI 不同；且不少中转站只实现了 OpenAI 那个。
            // 统一先试 OpenAI 风格，失败再试 anthropic 风格。
            val candidates = when (provider.proto) {
                Protocol.OPENAI -> listOf("$base/models")
                Protocol.ANTHROPIC -> listOf("$base/models", "$base/v1/models")
            }

            var lastError: String? = null
            for (url in candidates.distinct()) {
                try {
                    val builder = Request.Builder().url(url).get()
                    when (provider.proto) {
                        Protocol.OPENAI -> builder.header("Authorization", "Bearer $key")
                        Protocol.ANTHROPIC -> {
                            builder.header("x-api-key", key)
                            builder.header("anthropic-version", "2023-06-01")
                        }
                    }
                    RequestBuilder.parseExtraHeaders(provider.extraHeaders).forEach { (k, v) ->
                        runCatching { builder.header(k, v) }
                    }

                    normalClient.newCall(builder.build()).execute().use { resp ->
                        val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        if (!resp.isSuccessful) {
                            lastError = describeHttpError(resp.code, text)
                            return@use
                        }
                        val ids = parseModelIds(text)
                        if (ids.isNotEmpty()) return@withContext ids to null
                        lastError = "服务器返回的模型列表是空的"
                    }
                } catch (e: Exception) {
                    lastError = friendlyMessage(e)
                }
            }
            return@withContext emptyList<String>() to (lastError ?: "无法获取模型列表")
    }

    /**
     * 从响应里挖出模型 id。
     *
     * 兼容三种常见形态：
     *   1. OpenAI 标准：`{"data":[{"id":"gpt-4o"}, ...]}`
     *   2. 直接数组：`[{"id":"..."}]` 或 `["model-a", "model-b"]`
     *   3. 键名变体：`{"models":[...]}` / `{"result":[...]}`
     * 中转站实现五花八门，多兼容几种能少很多「拉不到列表」的投诉。
     */
    private fun parseModelIds(raw: String): List<String> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()

        val arr = when {
            root is kotlinx.serialization.json.JsonArray -> root
            root is JsonObject -> {
                val keys = listOf("data", "models", "result", "list")
                keys.firstNotNullOfOrNull { k ->
                    runCatching { root[k]?.jsonArray }.getOrNull()
                }
            }
            else -> null
        } ?: return emptyList()

        return arr.mapNotNull { el ->
            runCatching {
                when (el) {
                    is JsonObject -> el["id"]?.jsonPrimitive?.content
                        ?: el["name"]?.jsonPrimitive?.content
                        ?: el["model"]?.jsonPrimitive?.content
                    else -> el.jsonPrimitive.content
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

    // ─────────────────────── 内部 ───────────────────────

    private fun buildRequest(
        provider: Provider,
        key: String,
        body: String,
        stream: Boolean,
    ): Request {
        val builder = Request.Builder()
            .url(provider.endpoint())
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))

        when (provider.proto) {
            Protocol.OPENAI -> builder.header("Authorization", "Bearer $key")
            Protocol.ANTHROPIC -> {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            }
        }
        builder.header("Content-Type", "application/json")
        builder.header("Accept", if (stream) "text/event-stream" else "application/json")

        // 连接用完即弃：防止坏连接留在池里污染后续请求（见类注释）
        if (stream) builder.header("Connection", "close")

        RequestBuilder.parseExtraHeaders(provider.extraHeaders).forEach { (k, v) ->
            runCatching { builder.header(k, v) }
        }

        return builder.build()
    }

    private sealed interface DeltaResult {
        data class Text(val text: String) : DeltaResult
        /** 思考过程（思维链）。与正文分开，界面上单独渲染。 */
        data class Reasoning(val text: String) : DeltaResult
        data object Finished : DeltaResult
        data class Error(val message: String) : DeltaResult
        data object Ignore : DeltaResult
    }

    /** 从一帧 SSE 数据里抽增量文本，兼容两家协议 */
    private fun parseDelta(proto: Protocol, payload: String): DeltaResult {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return DeltaResult.Ignore

        // 错误帧（两家都可能有）
        root["error"]?.let { errEl ->
            val msg = runCatching {
                errEl.jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: errEl.toString()
            return DeltaResult.Error(msg)
        }

        return when (proto) {
            Protocol.OPENAI -> parseOpenAi(root)
            Protocol.ANTHROPIC -> parseAnthropic(root)
        }
    }

    private fun parseOpenAi(root: JsonObject): DeltaResult {
        // 部分中转站在流里用 finish_reason 结束
        val choices = root["choices"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return DeltaResult.Ignore
        if (choices.isEmpty()) return DeltaResult.Ignore

        val first = runCatching { choices[0].jsonObject }.getOrNull() ?: return DeltaResult.Ignore
        val deltaObj = runCatching { first["delta"]?.jsonObject }.getOrNull()

        // ── 思考内容 ──
        // 各家字段名不统一，实测见过这几种：
        //   reasoning_content（DeepSeek / 多数中转）
        //   reasoning（部分网关）
        //   thinking（GLM 系）
        // 顺序不能反：reasoning_content 最通用，放最前面优先匹配。
        if (deltaObj != null) {
            for (field in listOf("reasoning_content", "reasoning", "thinking")) {
                val r = runCatching {
                    deltaObj[field]?.jsonPrimitive?.content
                }.getOrNull()
                if (!r.isNullOrEmpty()) return DeltaResult.Reasoning(r)
            }
        }

        val text = runCatching {
            deltaObj?.get("content")?.jsonPrimitive?.content
        }.getOrNull().orEmpty()

        if (text.isNotEmpty()) return DeltaResult.Text(text)

        // 非流式形态的兜底（个别中转站忽略 stream=true，直接返回完整 message）
        val full = runCatching {
            first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        if (full.isNotEmpty()) return DeltaResult.Text(full)

        return DeltaResult.Ignore
    }

    private fun parseAnthropic(root: JsonObject): DeltaResult {
        val type = runCatching { root["type"]?.jsonPrimitive?.content }.getOrNull()
        return when (type) {
            "content_block_delta" -> {
                val deltaObj = runCatching { root["delta"]?.jsonObject }.getOrNull()
                // Anthropic 的思考增量是 thinking_delta，字段名也是 thinking
                val thinking = runCatching {
                    deltaObj?.get("thinking")?.jsonPrimitive?.content
                }.getOrNull()
                if (!thinking.isNullOrEmpty()) return DeltaResult.Reasoning(thinking)

                val text = runCatching {
                    deltaObj?.get("text")?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                if (text.isEmpty()) DeltaResult.Ignore else DeltaResult.Text(text)
            }
            "message_stop" -> DeltaResult.Finished
            "error" -> {
                val msg = runCatching {
                    root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: "未知错误"
                DeltaResult.Error(msg)
            }
            else -> DeltaResult.Ignore
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的中文提示 */
    private fun describeHttpError(code: Int, body: String): String {
        // 从错误体里挖出服务端原始信息（通常比状态码有用得多）
        val detail = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val err = obj["error"]
            when {
                err is JsonObject -> err["message"]?.jsonPrimitive?.content
                err != null -> err.jsonPrimitive.content
                else -> obj["message"]?.jsonPrimitive?.content
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200).trim()

        val prefix = when (code) {
            401, 403 -> "认证失败（$code）—— 检查 API Key 是否正确、是否已过期"
            404 -> "接口不存在（404）—— 检查 Base URL 是否要带 /v1"
            429 -> "请求过于频繁或额度用尽（429）"
            in 500..599 -> "服务端错误（$code）"
            else -> "请求失败（$code）"
        }
        return if (detail.isBlank()) prefix else "$prefix\n$detail"
    }

    private fun friendlyMessage(e: Exception): String = when {
        e is java.net.UnknownHostException -> "无法解析域名，检查网络或 Base URL"
        e is java.net.SocketTimeoutException -> "连接超时，检查网络或换个节点"
        e is javax.net.ssl.SSLException -> "SSL 握手失败，检查 Base URL 协议是否为 https"
        e.message?.contains("Failed to connect") == true -> "连接被拒绝，检查 Base URL 和端口"
        else -> "网络错误：${e.message ?: e::class.simpleName}"
    }

    companion object {
        @Volatile
        private var instance: ChatClient? = null

        fun get(): ChatClient =
            instance ?: synchronized(this) { instance ?: ChatClient().also { instance = it } }
    }
}

/** 流式事件 */
sealed interface StreamEvent {
    /** 正文增量 */
    data class Delta(val text: String) : StreamEvent
    /** 思考过程增量（思维链）。界面单独渲染，不计入正文 */
    data class Reasoning(val text: String) : StreamEvent
    /** 正常结束 */
    data object Done : StreamEvent
    /** 失败（message 已是可读中文） */
    data class Failed(val message: String) : StreamEvent
}
