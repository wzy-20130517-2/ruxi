package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 一条提取规则：用正则从回复里圈出「要念的部分」。
 *
 * ## 为什么用正则而不是固定标记
 * 角色回复的排版千变万化 —— 有时用 `"..."`，有时 `「...」`，有时整段没引号。
 * 固定标记只能覆盖一种，换个写法就整段被当成旁白念出来。
 *
 * ## 分组约定
 * 正则里**必须有一个捕获组**，第 1 组就是台词本身。
 * 例如 `["“]([^"”]+)["”]` 的第 1 组是引号中间的内容。
 *
 * 没有捕获组时，整段匹配结果都算台词（含引号本身）。
 */
@Serializable
data class TtsRule(
    /** 规则名，给人看 */
    val name: String,
    /** 正则。第 1 个捕获组是台词 */
    val pattern: String,
    val enabled: Boolean = true,
) {
    /**
     * 编译并执行这条规则，返回所有命中的（台词, 匹配范围）。
     *
     * 正则写错时返回空 —— 一条坏规则不该让整个朗读失效。
     */
    fun findAll(text: String): List<Hit> {
        if (!enabled || pattern.isBlank()) return emptyList()
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()

        return re.findAll(text).mapNotNull { m ->
            // 有捕获组用第 1 组，没有就整段
            val value = if (m.groupValues.size > 1) m.groupValues[1] else m.value
            val trimmed = value.trim()
            if (trimmed.isBlank()) null else Hit(trimmed, m.range.first, m.range.last + 1)
        }.toList()
    }

    /** 一次命中：台词文本 + 在原文里的区间 */
    data class Hit(val text: String, val start: Int, val end: Int)
}

/**
 * 规则树：一组规则的集合，按优先级顺序尝试。
 *
 * ## 树的结构
 * ```
 * TtsRuleSet
 * ├── 规则 1（引号类：中英文双引号、直角引号）
 * ├── 规则 2（括号类：（）【】）
 * ├── 规则 3（动作提示：*动作* 或 （动作））
 * └── 兜底规则（整段当台词）
 * ```
 *
 * ## 怎么合并
 * 所有启用的规则**同时**跑一遍，命中的区间取并集 —— 这样一条回复里
 * 混用 `"..."` 和 `「...」` 也能一起抓到。
 * 没被任何规则命中的部分就是旁白，用来推断场景和语气。
 *
 * ## 优先级
 * [fallbackToWhole] 为 true 且所有规则都没命中时，整段当台词、旁白为空。
 * 这是默认行为 —— 没有引号的回复，说明整条都是说话内容。
 */
@Serializable
data class TtsRuleSet(
    val rules: List<TtsRule> = defaultRules(),
    /**
     * 所有规则都没命中时，整段都念。
     *
     * 默认 false —— 角色回复里没引号，通常意味着这条全是旁白/动作描写，
     * 不是角色开口说的话。念出来等于把旁白当台词读，很怪。
     * 想要「没引号就全念」的用户可以自己打开。
     */
    val fallbackToWhole: Boolean = false,
) {
    /**
     * 切分文本。
     *
     * @return 朗读文本 + 旁白（去掉台词后剩下的部分）
     */
    fun split(content: String): Split {
        val text = content.trim()
        if (text.isEmpty()) return Split("", "")

        // 所有规则的命中区间取并集
        val hits = rules.flatMap { it.findAll(text) }
        if (hits.isEmpty()) {
            return if (fallbackToWhole) Split(text, "")
            else Split("", text)
        }

        // 按起点排序，重叠区间合并（避免同一段被算两次）
        val merged = mutableListOf<IntRange>()
        for (h in hits.sortedBy { it.start }) {
            val r = h.start until h.end
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }

        // 台词按原文顺序拼接；旁白 = 挖掉所有命中区间后剩下的
        val spoken = hits.sortedBy { it.start }.joinToString(" ") { it.text }
        val aside = buildAside(text, merged)

        return Split(spoken, aside)
    }

    /** 挖掉 [ranges] 后剩下的文本，顺手清掉多余标点 */
    private fun buildAside(text: String, ranges: List<IntRange>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (r in ranges) {
            if (r.first > cursor) sb.append(text, cursor, r.first)
            cursor = maxOf(cursor, r.last + 1)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)

        return sb.toString()
            .replace(Regex("\\s+"), " ")
            // 台词被挖走后，前面常留下「小声说：」「问道：」这种悬空的冒号。
            // 冒号是引出下文的，下文没了它就成了断口，去掉。
            .replace(Regex("[:：]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('，', '。', '、', '；', ',', '.', ';')
            .trim()
    }

    /** 切分结果 */
    data class Split(val spoken: String, val aside: String)

    companion object {
        /**
         * 默认规则集。
         *
         * 顺序有讲究：越靠前的越常见，但因为是并集合并，顺序不影响结果，
         * 只影响设置页里的展示顺序。
         */
        fun defaultRules(): List<TtsRule> = listOf(
            TtsRule(
                name = "中英文双引号",
                // 直引号、弯引号都认，且允许开头结尾用不同的（模型常混用）
                pattern = "[\"“]([^\"”\\n]{1,500})[\"”]",
            ),
            TtsRule(
                name = "直角引号「」",
                pattern = "[「『]([^」』\\n]{1,500})[」』]",
            ),
            TtsRule(
                name = "中文书名号《》",
                pattern = "《([^》\\n]{1,200})》",
                enabled = false,   // 书名默认不念，用户想用可以打开
            ),
        )

        /** 规则名 → 一句话说明，设置页展示用 */
        val RULE_HINTS = mapOf(
            "中英文双引号" to "匹配 \"…\" 和 “…”，模型最常用的写法",
            "直角引号「」" to "匹配 「…」 和 『…』，中文排版常见",
            "中文书名号《》" to "匹配 《…》，默认关闭（书名通常不该念）",
        )
    }
}
