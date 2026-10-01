package com.ruxi.app.data.api

import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Protocol
import com.ruxi.app.data.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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

    /** 非流式的连通性测试。返回 (是否成功, 说明文本) */
    suspend fun test(provider: Provider): Pair<Boolean, String> {
        val key = provider.effectiveKeys.firstOrNull().orEmpty()
        if (key.isBlank()) return false to "未配置 API Key"

        return try {
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

        // 有的实现在 delta 里给 reasoning_content（思维链），不该混进正文
        val text = runCatching {
            first["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content
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
                val text = runCatching {
                    root["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content
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
    /** 增量文本 */
    data class Delta(val text: String) : StreamEvent
    /** 正常结束 */
    data object Done : StreamEvent
    /** 失败（message 已是可读中文） */
    data class Failed(val message: String) : StreamEvent
}
