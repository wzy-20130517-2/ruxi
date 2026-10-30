package com.ruxi.app.data.prompt

import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Role
import com.ruxi.app.data.model.Session

/**
 * 提示词引擎 —— 本应用最核心的一段逻辑。
 *
 * ## 职责
 * 1. 组装每轮发给模型的 messages（系统提示词每轮注入）
 * 2. 首轮注入角色卡图片（含备注文本）
 * 3. 附加角色卡 / 全局的隐藏要求
 */
object PromptEngine {

    /**
     * 组装本轮请求的完整消息列表。
     *
     * @param card     当前角色卡
     * @param session  当前会话（用来判断是否首轮、拿历史）
     * @param userText 用户本轮输入
     * @param userImages 用户本轮随消息发送的图片
     * @return 组装好的消息列表
     */
    fun build(
        card: CharacterCard,
        session: Session,
        userText: String,
        userImages: List<AttachedImage>,
        /** 是否把历史里的思考过程也回传给模型（默认否，省 token） */
        replayThinking: Boolean = false,
    ): BuildResult {
        val messages = mutableListOf<ChatMessage>()

        // ── 1. 系统提示词（每轮注入） ──
        val systemText = composeSystem(card)
        if (systemText.isNotBlank()) {
            messages += ChatMessage(role = Role.SYSTEM, content = systemText)
        }

        // ── 2. 历史消息 ──
        session.messages.forEach { msg ->
            if (msg.isError) return@forEach          // 失败提示不进上下文
            if (msg.content.isBlank() && msg.images.isEmpty()) return@forEach

            messages += msg.copy(
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
        // 判断依据是「历史里还有没有带着角色卡图片的消息」，而不是一个开关位。
        // 用户删掉首轮消息后，历史里那些路径就没了，下一轮自然重新注入。
        val cardPaths = card.images.mapTo(mutableSetOf()) { it.path }
        val alreadyInjected = cardPaths.isNotEmpty() &&
            session.messages.any { msg -> msg.images.any { it.path in cardPaths } }
        val injectCardImages = !alreadyInjected && card.images.isNotEmpty()

        val notePart = if (injectCardImages) buildImageNote(card.images) else ""

        val combinedText = listOf(notePart, userText)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        messages += ChatMessage(
            role = Role.USER,
            content = combinedText,
            // 角色卡图片 + 用户本轮图片，合并成一条消息的附件
            images = (if (injectCardImages) card.images else emptyList()) + userImages,
        )

        return BuildResult(messages = messages)
    }

    /**
     * 拼系统提示词：系统设定 + 用户设定。
     */
    private fun composeSystem(card: CharacterCard): String {
        val parts = mutableListOf<String>()

        if (card.systemSetting.isNotBlank()) {
            parts += card.systemSetting.trim()
        }
        if (card.userSetting.isNotBlank()) {
            parts += card.userSetting.trim()
        }

        return parts.joinToString("\n\n")
    }

    /** 图片备注文本。备注作为文本出现，模型才能把「图」和「身份」对应起来。 */
    private fun buildImageNote(images: List<AttachedImage>): String {
        val lines = mutableListOf<String>()
        lines += "以下 ${images.size} 张图片是本次设定的参考资料，请结合它们理解设定。"
        images.forEachIndexed { idx, img ->
            val note = img.note.ifBlank { "第 ${idx + 1} 张" }
            lines += "图片${idx + 1}：$note"
        }
        return lines.joinToString("\n")
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
        recentTurns: Int = 8,
    ): List<ChatMessage> {
        val recent = session.messages
            .filterNot { it.isError }
            .takeLast(recentTurns * 2)

        val transcript = recent.joinToString("\n") { msg ->
            val who = if (msg.isUser) "我（用户）" else card.name
            "$who：${msg.content.trim()}"
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

    /** 组装结果 */
    data class BuildResult(
        val messages: List<ChatMessage>,
    )
}
