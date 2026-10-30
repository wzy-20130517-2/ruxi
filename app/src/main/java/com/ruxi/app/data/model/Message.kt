package com.ruxi.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** 角色取值，与 OpenAI 协议的 role 字符串一一对应。 */
object Role {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/**
 * 一条对话消息。
 *
 * ## 版本（groupId / versionIndex）
 * 同一个位置可以有多个版本：用户编辑了消息、或让 AI 重新生成，
 * 旧内容不会被丢掉，而是作为同一 [groupId] 的另一个版本存下来。
 *
 * - [groupId] 相同 = 同一个位置的不同版本。新建消息时等于自己的 id。
 * - [versionIndex] 组内序号，越大越新。用序号而不是 timestamp 排序 ——
 *   连续两次编辑可能落在同一毫秒，时间戳分不出先后。
 *
 * 界面只渲染每组的一个版本（见 [Session.activeVersion]），左右箭头切换。
 */
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    /** 模型的思考过程（思维链）。与 [content] 分开存，界面单独渲染。 */
    val reasoning: String = "",
    val images: List<AttachedImage> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** 该条是否为失败提示（界面用红色渲染，且不参与后续上下文） */
    val isError: Boolean = false,
    /** 生成该条时是否还在流式中（进程被杀后残留的未完成消息） */
    val incomplete: Boolean = false,
    /** 同一位置的多个版本共享此 id；默认自成一组 */
    val groupId: String = "",
    /** 组内序号，越大越新 */
    val versionIndex: Int = 0,
) {
    val isUser: Boolean get() = role == Role.USER
    val isAssistant: Boolean get() = role == Role.ASSISTANT
    val hasReasoning: Boolean get() = reasoning.isNotBlank()
    /** 反序列化时 groupId 可能为空（旧数据），用 id 兜底 */
    val group: String get() = groupId.ifBlank { id }
}

/**
 * 一个会话。按角色卡隔离 —— 每个角色可以有多条并行会话。
 *
 * [activeVersion] 记录每组当前选中的版本：groupId -> messageId。
 * 没有记录的组默认显示最新一版。存 messageId 而不是下标，
 * 这样插入/删除消息后不会错位到别的版本上。
 */
@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val activeVersion: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 每组当前显示的版本（没记录的组用最新一版） */
    fun visibleMessages(): List<ChatMessage> {
        if (messages.all { it.group == it.id }) return messages   // 没有多版本，原样返回
        val byGroup = messages.groupBy { it.group }
        // 按组在 messages 里的首次出现顺序输出，保持对话顺序
        return messages.map { it.group }.distinct().mapNotNull { g ->
            val versions = byGroup[g] ?: return@mapNotNull null
            val chosen = activeVersion[g]
            versions.firstOrNull { it.id == chosen }
                ?: versions.maxByOrNull { it.versionIndex }
        }
    }

    /** 某组的所有版本，按版本号升序 */
    fun versionsOf(groupId: String): List<ChatMessage> =
        messages.filter { it.group == groupId }.sortedBy { it.versionIndex }

    /** 某组当前选中的版本在组内的下标（0 起） */
    fun versionCursor(groupId: String): Int {
        val versions = versionsOf(groupId)
        if (versions.isEmpty()) return 0
        val chosen = activeVersion[groupId]
        val idx = versions.indexOfFirst { it.id == chosen }
        return if (idx >= 0) idx else versions.lastIndex
    }
}
