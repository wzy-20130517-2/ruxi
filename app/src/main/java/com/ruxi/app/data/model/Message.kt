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
 * [raw] 保留模型**未经还原**的原始输出（带分隔符的形态）。
 * 界面默认展示还原后的文本；用户想看模型到底吐了什么时可以切到原始视图。
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
) {
    val isUser: Boolean get() = role == Role.USER
    val isAssistant: Boolean get() = role == Role.ASSISTANT
    val hasReasoning: Boolean get() = reasoning.isNotBlank()
}

/**
 * 一个会话。按角色卡隔离 —— 每个角色可以有多条并行会话。
 */
@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
