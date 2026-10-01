package com.ruxi.app.data.prompt

import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.PromptSettings
import com.ruxi.app.data.model.Provider
import com.ruxi.app.data.model.Role
import com.ruxi.app.data.model.Session

/**
 * 提示词引擎 —— 本应用最核心的一段逻辑。
 *
 * ## 职责
 * 1. 组装每轮发给模型的 messages（系统提示词每轮注入）
 * 2. 对系统提示词、用户消息做逐字分隔
 * 3. 首轮注入角色卡图片（含备注文本）
 * 4. 把模型返回的分隔文本还原成人类可读文本
 *
 * ## 分隔算法的边界处理（这些细节决定了模型会不会「学歪」）
 * - 换行符**不参与**分隔：否则 `你·好·\n·世·界` 这种形态会让模型以为换行也要加符
 * - 空白行整行跳过：避免产生 `·` 单独成行的脏输出
 * - 已有的分隔符先剥掉再重新分：防止用户/模型已带分隔符时出现 `你··好`
 * - 空字符串直接返回：`"".join` 类操作在 Kotlin 里不会崩，但没必要走一遍
 */
object PromptEngine {

    /** 分隔符占位符，模板里写 {SEP} 会被替换成实际分隔符 */
    private const val SEP_TOKEN = "{SEP}"

    // ─────────────────────────── 分隔 / 还原 ───────────────────────────

    /**
     * 逐字插入分隔符。
     *
     * @param text 原始文本
     * @param sep  分隔符（可为多字符，如 "·" 或 " | "）
     */
    fun split(text: String, sep: String): String {
        if (text.isEmpty() || sep.isEmpty()) return text
        val sb = StringBuilder(text.length * (1 + sep.length))
        // 按行处理：行内字符逐个分隔，行间保留原始换行
        val lines = text.split('\n')
        lines.forEachIndexed { lineIdx, line ->
            if (line.isNotEmpty()) {
                line.forEachIndexed { chIdx, ch ->
                    if (chIdx > 0) sb.append(sep)
                    sb.append(ch)
                }
            }
            // 最后一行之后不补换行，中间行之间补回来
            if (lineIdx < lines.size - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 还原：把模型输出的带分隔文本还原成人话。
     *
     * 容错策略（模型不会 100% 听话，必须容忍各种偏差）：
     * - 分隔符带空格变体（`· `、` ·`）一并吞掉
     * - 连续多个分隔符压成一个（`你··好` → `你好`）
     * - 换行处的分隔符也清掉（`你·\n·好` → `你\n好`）
     * - 未带分隔符的普通文本原样保留（模型偶尔忘加，不该把内容搞坏）
     */
    fun restore(text: String, sep: String): String {
        if (text.isEmpty() || sep.isEmpty()) return text
        val escaped = Regex.escape(sep)
        // ⚠️ 这里必须用 [ \t] 而不是 \s —— \s 包含 \n，
        // 会把「你·\n好」中间的换行一起吞掉，导致段落被拼成一行。
        // 换行是结构信息，还原时只能清掉它旁边的分隔符，不能清掉换行本身。
        var out = text.replace(Regex("[ \t]*$escaped[ \t]*"), sep)
        // 连续分隔符压成一个
        out = out.replace(Regex("(?:$escaped)+"), sep)
        // 行首/行尾/换行处残留的分隔符清掉
        out = out.replace(Regex("^$escaped"), "")
        out = out.replace(Regex("$escaped$"), "")
        out = out.replace(Regex("$escaped\\n"), "\n")
        out = out.replace(Regex("\\n$escaped"), "\n")
        // 分隔符 -> 空
        return out.replace(sep, "")
    }

    /**
     * 启发式判断一段文本「是否已被分隔」。
     *
     * 用途：用户消息自动分隔前先判断，避免重复分隔。
     * 判据：分隔符出现次数 ≥ 字符数的一半。正常文本里分隔符极少，
     * 而分隔文本里几乎每个字后面都跟一个，所以这个阈值很安全。
     */
    fun looksSplit(text: String, sep: String): Boolean {
        if (text.isEmpty() || sep.isEmpty()) return false
        val count = Regex(Regex.escape(sep)).findAll(text).count()
        val visible = text.count { !it.isWhitespace() }
        return visible > 4 && count * 2 >= visible
    }

    // ─────────────────────────── 组装 messages ───────────────────────────

    /**
     * 组装本轮请求的完整消息列表。
     *
     * @param card     当前角色卡
     * @param session  当前会话（用来判断是否首轮、拿历史）
     * @param userText 用户本轮输入
     * @param userImages 用户本轮随消息发送的图片
     * @param settings 全局提示词设置
     * @return 组装好的消息列表 + 本轮实际使用的隐藏要求（便于界面展示「这轮注入了什么」）
     */
    fun build(
        card: CharacterCard,
        session: Session,
        userText: String,
        userImages: List<AttachedImage>,
        settings: PromptSettings,
        /** 是否把历史里的思考过程也回传给模型（默认否，省 token） */
        replayThinking: Boolean = false,
    ): BuildResult {
        val sep = settings.separator.ifEmpty { "·" }
        val messages = mutableListOf<ChatMessage>()

        // ── 1. 系统提示词（每轮注入） ──
        val systemText = composeSystem(card, settings, sep)
        if (systemText.isNotBlank()) {
            messages += ChatMessage(role = Role.SYSTEM, content = systemText)
        }

        // ── 2. 历史消息 ──
        session.messages.forEach { msg ->
            if (msg.isError) return@forEach          // 失败提示不进上下文
            if (msg.content.isBlank() && msg.images.isEmpty()) return@forEach

            // 历史里的用户消息保持已分隔的形态（模型看到的一致格式更不容易走样）
            val content = when {
                msg.isUser && settings.splitUser && msg.content.isNotBlank() ->
                    ensureSplit(msg.content, sep)
                else -> msg.content
            }
            messages += msg.copy(
                content = content,
                // 思考过程默认不回传：它通常很长，且模型并不需要重读自己的旧思路。
                // 需要时可以开（有些场景确实要求它「记得自己想过什么」）。
                reasoning = if (replayThinking) msg.reasoning else "",
            )
        }

        // ── 3. 本轮用户输入（首轮时把角色卡图片一并挂上） ──
        //
        // ⚠️ 图片**不能**作为单独一条 user 消息插进来。
        // Anthropic 协议硬性要求 user/assistant 严格交替，
        // 两条连续的 user 消息会被直接拒绝（"roles must alternate"）。
        // 所以把图片和备注合并进本轮这条消息里。
        val injectCardImages = !session.injectedImages && card.images.isNotEmpty()

        val textPart = if (settings.splitUser && userText.isNotBlank()) {
            ensureSplit(userText, sep)
        } else {
            userText
        }
        val notePart = if (injectCardImages) buildImageNote(card.images, sep, settings) else ""

        val combinedText = listOf(notePart, textPart)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        messages += ChatMessage(
            role = Role.USER,
            content = combinedText,
            // 角色卡图片 + 用户本轮图片，合并成一条消息的附件
            images = (if (injectCardImages) card.images else emptyList()) + userImages,
        )

        return BuildResult(
            messages = messages,
            hiddenUsed = resolveHidden(card, settings, sep),
        )
    }

    /** 本轮实际生效的隐藏要求（角色卡优先于全局） */
    fun resolveHidden(
        card: CharacterCard,
        settings: PromptSettings,
        sep: String = settings.separator,
    ): String {
        if (!settings.hiddenEnabled) return ""
        val raw = card.hiddenRequirement.ifBlank { settings.hiddenTemplate }
            .ifBlank { PromptSettings.DEFAULT_HIDDEN }
        return raw.replace(SEP_TOKEN, sep)
    }

    /**
     * 拼系统提示词：系统设定 + 用户设定 + 隐藏要求。
     *
     * 顺序有讲究：隐藏要求放**最后**。模型的注意力对末尾内容更强，
     * 放前面容易被大段人设设定淹没。
     */
    private fun composeSystem(
        card: CharacterCard,
        settings: PromptSettings,
        sep: String,
    ): String {
        val parts = mutableListOf<String>()

        if (card.systemSetting.isNotBlank()) {
            parts += card.systemSetting.trim()
        }
        if (card.userSetting.isNotBlank()) {
            parts += card.userSetting.trim()
        }
        val hidden = resolveHidden(card, settings, sep)
        if (hidden.isNotBlank()) {
            parts += hidden
        }

        val merged = parts.joinToString("\n\n")
        // 系统提示词也做逐字分隔 —— 这是给模型的格式示范，
        // 比纯文字要求有效得多（模型看到什么格式就更可能输出什么格式）
        return if (settings.splitSystem && merged.isNotBlank()) split(merged, sep) else merged
    }

    /** 图片备注文本。备注作为文本出现，模型才能把「图」和「身份」对应起来。 */
    private fun buildImageNote(
        images: List<AttachedImage>,
        sep: String,
        settings: PromptSettings,
    ): String {
        val lines = mutableListOf<String>()
        lines += "以下 ${images.size} 张图片是本次设定的参考资料，请结合它们理解设定。"
        images.forEachIndexed { idx, img ->
            val note = img.note.ifBlank { "第 ${idx + 1} 张" }
            lines += "图片${idx + 1}：$note"
        }
        val merged = lines.joinToString("\n")
        return if (settings.splitUser) split(merged, sep) else merged
    }

    /**
     * 构造「帮我回复」请求 —— 立场对调的 RP。
     *
     * ## 跟普通对话的关系
     * 完全对称，只是**谁说话反过来了**：
     * - 普通对话：模型演 [CharacterCard.systemSetting] 里那个角色，回答用户
     * - 帮我回复：模型演 [CharacterCard.userSetting] 里那个「用户」角色，替用户说话
     *
     * 所以**系统设定照常注入**（世界观、语境、说话风格都是共用的），
     * 只是把「你现在要扮演谁」和「你在对谁说话」对调。
     *
     * ## 为什么不用 messages 数组喂历史
     * 如果照常传完整 messages，模型会顺着最后一条 assistant 消息
     * 继续以**角色**身份说话。所以这里把历史压成一段「对话记录」文本，
     * 并明确指示它演另一方 —— 结构上不给它「接着演角色」的机会。
     *
     * @param recentTurns 喂多少轮上下文。太少写不贴切，太多容易让它复述。
     */
    fun buildAssistReply(
        card: CharacterCard,
        session: Session,
        settings: PromptSettings,
        recentTurns: Int = 8,
    ): List<ChatMessage> {
        val sep = settings.separator.ifEmpty { "·" }

        // 历史还原成人类可读形态：模型看「你·好」不利于理解语境，
        // 而且这轮它要输出的是用户的话，不该被分隔格式带跑。
        val recent = session.messages
            .filterNot { it.isError }
            .takeLast(recentTurns * 2)

        val transcript = recent.joinToString("\n") { msg ->
            val who = if (msg.isUser) "我（用户）" else card.name
            val text = restore(msg.content, sep).trim()
            "$who：$text"
        }.ifBlank { "（对话还没开始）" }

        val systemPrompt = buildString {
            // 世界观照旧 —— 两边演的是同一个故事
            if (card.systemSetting.isNotBlank()) {
                append(card.systemSetting.trim())
                append("\n\n")
            }

            append("——————\n\n")
            append("【本次任务：换边演出】\n\n")

            if (card.userSetting.isNotBlank()) {
                append("这一轮你要扮演的是**用户**，用户在这个故事里的身份是：\n")
                append(card.userSetting.trim())
                append("\n\n")
            } else {
                append("这一轮你要扮演的是**用户**。\n\n")
            }

            append("你要以这个身份，对「")
            append(card.name)
            append("」说一句话 —— 就像用户在正常聊天那样。\n\n")

            append("【输出要求】\n")
            append("1. 只写用户说的那一句话，不要任何前缀、引号或解释\n")
            append("2. **简短**，一两句就够，像真人聊天\n")
            append("3. 用第一人称，符合上面那个身份的语气和立场\n")
            append("4. 不要替「")
            append(card.name)
            append("」说话，也不要复述对话\n")
        }

        val userPrompt = buildString {
            append("【目前的对话记录】\n")
            append(transcript)
            append("\n\n")
            append("请写出我（用户）接下来该说的那句话。")
        }

        return listOf(
            ChatMessage(role = Role.SYSTEM, content = systemPrompt),
            ChatMessage(role = Role.USER, content = userPrompt),
        )
    }

    /** 若文本尚未分隔则分隔之，已分隔则原样返回 */
    private fun ensureSplit(text: String, sep: String): String =
        if (looksSplit(text, sep)) text else split(text, sep)

    /** 组装结果 */
    data class BuildResult(
        val messages: List<ChatMessage>,
        val hiddenUsed: String,
    )
}
