package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 朗读（TTS）配置。
 *
 * ## 请求里两个角色的分工
 * - `user`      —— **控制风格**：基础音色 + 场景 + 语气
 * - `assistant` —— **要念的文本**
 *
 * 这点容易搞反。官方示例里 `user` 写 `"Give me a young male tone."`，
 * `assistant` 才是真正被念出来的那句话。
 *
 * ## 文本怎么分
 * 一条助手回复里通常既有台词又有旁白（「她叹了口气，"你来了"」）。
 * 按 [ruleSet] 把台词抠出来当朗读文本，**剩下的部分**才是场景和语气的来源。
 * 所以两者天然互补，不会重复念。
 */
@Serializable
data class TtsConfig(
    val enabled: Boolean = false,
    val model: String = MODEL_TTS,
    /** 内置音色名（仅 [MODEL_TTS]） */
    val voice: String = "Chloe",
    /** 是否智能润色朗读文本（仅 [MODEL_DESIGN]） */
    val optimizeText: Boolean = false,
    /** 提取规则集。多条规则并行匹配，取并集 */
    val ruleSet: TtsRuleSet = TtsRuleSet(),
    /** 克隆用的参考音频（data URL，仅 [MODEL_CLONE]） */
    val cloneAudioDataUrl: String = "",
) {
    companion object {
        const val MODEL_TTS = "mimo-v2.5-tts"
        const val MODEL_DESIGN = "mimo-v2.5-tts-voicedesign"
        const val MODEL_CLONE = "mimo-v2.5-tts-voiceclone"

        val MODELS = listOf(
            MODEL_TTS to "内置音色（支持实时流式）",
            MODEL_DESIGN to "文字描述生成音色",
            MODEL_CLONE to "参考音频克隆",
        )

        /**
         * 从旁白里提炼场景。
         *
         * 只取第一个分句 —— 太长会把音色描述淹掉，而且 TTS 也不需要知道全部。
         */
        fun sceneOf(aside: String): String {
            if (aside.isBlank()) return ""
            val first = aside.split('，', '。', '、', '；', '\n')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: return ""
            return first.take(40)
        }

        /**
         * 从旁白里判断语气。
         *
         * 先看动作词再看标点 —— 「她叹了口气」后面可能只是个句号，
         * 但动作本身已经说明情绪了，光看标点会判成「平稳」，跟画面对不上。
         */
        fun moodOf(aside: String, content: String): String {
            val t = aside + content.takeLast(200)
            return when {
                t.contains("叹") || t.contains("低声") || t.contains("喃喃") -> "有点迟疑"
                t.contains("笑") || t.contains("喊") || t.contains("嚷") -> "有点激动"
                t.contains("哭") || t.contains("哽") || t.contains("颤") -> "有点发颤"
                t.contains("！") || t.contains("!") -> "有点激动"
                t.contains("？") || t.contains("?") -> "尾音上扬"
                t.contains("…") || t.contains("...") -> "慢一点"
                else -> "平稳自然"
            }
        }
    }
}
