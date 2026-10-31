package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 朗读（TTS）配置。
 *
 * ## 请求里两个角色的分工
 * - `user`   —— **控制风格**：基础音色 + 场景 + 语气
 * - `assistant` —— **要念的文本**
 *
 * 这点容易搞反。官方示例里 `user` 写 `"Give me a young male tone."`，
 * `assistant` 才是真正被念出来的那句话。
 *
 * ## 文本怎么分
 * 一条助手回复里通常既有台词又有旁白（「她叹了口气，"你来了"」）。
 * 按 [extractRule] 把台词抠出来当朗读文本，**剩下的部分**才是场景和语气的来源。
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
    /**
     * 提取规则：用什么标记把台词圈出来。
     *
     * 默认 `""`（双引号）。用户也可以填 `「」`、`（）` 之类。
     * 场景和语气从**这些标记之外**的文本里取。
     */
    val extractMark: String = DEFAULT_MARK,
    /** 克隆用的参考音频（data URL，仅 [MODEL_CLONE]） */
    val cloneAudioDataUrl: String = "",
) {
    companion object {
        const val MODEL_TTS = "mimo-v2.5-tts"
        const val MODEL_DESIGN = "mimo-v2.5-tts-voicedesign"
        const val MODEL_CLONE = "mimo-v2.5-tts-voiceclone"

        /** 默认提取标记：双引号（中英文都认） */
        const val DEFAULT_MARK = "\""

        val MODELS = listOf(
            MODEL_TTS to "内置音色（支持实时流式）",
            MODEL_DESIGN to "文字描述生成音色",
            MODEL_CLONE to "参考音频克隆",
        )

        /**
         * 按标记切分文本，返回（朗读文本, 剩下的旁白）。
         *
         * [mark] 为空或不成对时，整段都当朗读文本，旁白为空。
         */
        fun split(content: String, mark: String): Pair<String, String> {
            val text = content.trim()
            if (text.isEmpty()) return "" to ""

            val open = openOf(mark)
            val close = closeOf(mark)
            if (open == null || close == null) return text to ""

            val quoted = Regex(
                "${Regex.escape(open.toString())}([^${Regex.escape(close.toString())}]{1,500})${Regex.escape(close.toString())}"
            ).findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()

            if (quoted.isEmpty()) return text to ""

            val spoken = quoted.joinToString(" ")
            val aside = text.replace(
                Regex("${Regex.escape(open.toString())}[^${Regex.escape(close.toString())}]{1,500}${Regex.escape(close.toString())}"),
                ""
            ).replace(Regex("\\s+"), " ").trim().trim('，', '。', '、', '；')

            return spoken to aside
        }

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
            val t = (aside + content.takeLast(200))
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

        /** 把用户填的标记拆成开/闭两个字符 */
        private fun openOf(mark: String): Char? = mark.trim().firstOrNull()

        private fun closeOf(mark: String): Char? {
            val m = mark.trim()
            if (m.length >= 2) return m[1]
            // 只填了一个字符时，中文引号自动配对
            return when (m.firstOrNull()) {
                '"' -> '"'
                '「' -> '」'
                '『' -> '』'
                '（' -> '）'
                '(' -> ')'
                '“' -> '”'
                else -> null
            }
        }
    }
}
