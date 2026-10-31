package com.ruxi.app.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.ruxi.app.data.model.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 朗读（TTS）客户端，走小米 MiMo。
 *
 * ## 为什么 key 硬编码
 * 对话用的 Provider 是用户自己配的中转站，未必支持 `audio` 字段。
 * 朗读固定用这一家，跟对话链路解耦 —— 换 Provider 不影响朗读。
 *
 * ## 请求里两个角色的分工（容易搞反）
 * - `user`      —— 控制风格：基础音色 + 场景 + 语气
 * - `assistant` —— 要念的文本
 *
 * ## 流式
 * 三个模型里只有 [TtsConfig.MODEL_TTS] 是真流式（边推理边吐 PCM）。
 * 另外两个的 `stream: true` 是兼容模式，等推理完才一次性返回。
 * 所以是否流式由模型决定，不是用户开关。
 */
object TtsClient {

    private const val API_KEY = "sk-cdmb5rg4ia7nwt5fq5ns3bc5oxxa1rbunjm24u7f7n25qkqh"
    private const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val SAMPLE_RATE = 24000

    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun supportsRealtimeStream(model: String): Boolean = model == TtsConfig.MODEL_TTS

    /**
     * 合成并朗读。
     *
     * @param spoken 要念的文本（从回复里按标记提取出来的台词）
     * @param style  控制风格的那段（基础音色 + 场景 + 语气）
     */
    fun speak(spoken: String, style: String, cfg: TtsConfig): Flow<TtsEvent> = flow {
        if (spoken.isBlank()) { emit(TtsEvent.Failed("没有可朗读的内容")); return@flow }

        val track = createTrack()
        try {
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("api-key", API_KEY)
                .post(buildBody(spoken, style, cfg).toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()

            val failure: String? = response.use { resp ->
                if (!resp.isSuccessful) {
                    describeHttp(resp.code, resp.body?.string().orEmpty())
                } else {
                    val body = resp.body
                    if (body == null) {
                        "响应为空"
                    } else {
                        val stream = supportsRealtimeStream(cfg.model)
                        track.play()
                        var wrote = false
                        val pending = StringBuilder()

                        val source = body.source()
                        loop@ while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue

                            val payload = if (stream) {
                                if (!line.startsWith("data:")) continue
                                val p = line.removePrefix("data:").trim()
                                if (p == "[DONE]") break@loop
                                p
                            } else {
                                pending.append(line)
                                continue
                            }

                            extractPcm(payload)?.let { pcm ->
                                if (pcm.isNotEmpty()) { track.write(pcm, 0, pcm.size); wrote = true }
                            }
                        }

                        // 非流式：整段 JSON 拼完再解析
                        if (!stream && pending.isNotEmpty()) {
                            extractPcm(pending.toString())?.let { pcm ->
                                if (pcm.isNotEmpty()) { track.write(pcm, 0, pcm.size); wrote = true }
                            }
                        }

                        if (wrote) null else "没有收到音频数据"
                    }
                }
            }

            if (failure != null) emit(TtsEvent.Failed(failure)) else emit(TtsEvent.Done)
        } catch (e: Exception) {
            emit(TtsEvent.Failed(describeIo(e)))
        } finally {
            // 等缓冲区播完再释放，直接 stop() 会把尾部切掉
            runCatching {
                val remainingMs = track.bufferSizeInFrames * 1000L / SAMPLE_RATE
                if (remainingMs > 0) delay(remainingMs + 100)
                track.stop()
                track.release()
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── 内部 ──────────────────────────────────────────

    private fun createTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 组装请求体。
     *
     * 三个模型的字段差异：
     * - tts：`user` 放风格描述，`audio.voice` 放内置音色名
     * - voicedesign：`user` 放音色描述，不传 voice
     * - voiceclone：`user` 留空，`audio.voice` 放参考音频 data URL
     */
    private fun buildBody(spoken: String, style: String, cfg: TtsConfig): String {
        val stream = supportsRealtimeStream(cfg.model)

        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", if (cfg.model == TtsConfig.MODEL_CLONE) "" else style)
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", spoken)
            })
        }

        val audio = buildJsonObject {
            put("format", "pcm16")
            when {
                cfg.model == TtsConfig.MODEL_TTS && cfg.voice.isNotBlank() ->
                    put("voice", cfg.voice)
                cfg.model == TtsConfig.MODEL_CLONE && cfg.cloneAudioDataUrl.isNotBlank() ->
                    put("voice", cfg.cloneAudioDataUrl)
            }
            if (cfg.model == TtsConfig.MODEL_DESIGN) {
                put("optimize_text_preview", cfg.optimizeText)
            }
        }

        return buildJsonObject {
            put("model", cfg.model)
            put("messages", messages)
            put("audio", audio)
            put("stream", stream)
        }.toString()
    }

    /** 取 `choices[0].(delta|message).audio.data`，base64 解码成 PCM */
    private fun extractPcm(payload: String): ByteArray? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val node = first["delta"]?.jsonObject ?: first["message"]?.jsonObject ?: return null
        val audio = node["audio"]?.jsonObject ?: return null
        val data = runCatching { audio["data"]!!.jsonPrimitive.content }.getOrNull() ?: return null
        return runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
    }

    private fun describeHttp(code: Int, body: String): String = when (code) {
        401, 403 -> "朗读失败：key 无效或无权限（$code）"
        429 -> "朗读失败：请求太频繁（429）"
        in 500..599 -> "朗读失败：服务端错误（$code）"
        else -> "朗读失败：HTTP $code ${body.replace(Regex("\\s+"), " ").take(160)}"
    }

    private fun describeIo(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "朗读失败：连接超时"
        is IOException -> "朗读失败：网络错误（${e.message ?: "未知"}）"
        else -> "朗读失败：${e.message ?: e::class.simpleName}"
    }

    sealed interface TtsEvent {
        data object Done : TtsEvent
        data class Failed(val message: String) : TtsEvent
    }
}
