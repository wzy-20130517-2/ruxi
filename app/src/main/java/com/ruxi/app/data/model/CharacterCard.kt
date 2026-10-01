package com.ruxi.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 角色卡 —— 本应用的核心单位。
 *
 * 「每个角色卡独立提示词」意味着下面这些字段都是**按角色**隔离的：
 * 系统设定、用户设定、隐藏要求、图片附件。
 *
 * 字段设计说明：
 * - [systemSetting] 每轮注入（作为 system message 的正文）
 * - [userSetting] 拼在 system 尾部，用于描述「你是谁、我（用户）是谁」这类框架
 * - [hiddenRequirement] 破甲/输出规范。留空则用全局默认模板；填了就覆盖。
 *   之所以放在角色卡上而不是全局：不同角色卡需要的约束强度不同。
 * - [images] 仅在**会话首轮**注入，之后轮次不再发送（省 token，也避免模型重复描述）
 * - [greeting] 开场白，新建会话时若不为空则直接作为第一条 assistant 消息插入
 */
@Serializable
data class CharacterCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新角色",
    val avatar: String? = null,
    /** 一句话简介，列表页展示 */
    val subtitle: String = "",
    val systemSetting: String = "",
    val userSetting: String = "",
    val hiddenRequirement: String = "",
    val images: List<AttachedImage> = emptyList(),
    val greeting: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 卡片是否已配置到可用状态（列表页用来提示「未配置」） */
    val isConfigured: Boolean get() = systemSetting.isNotBlank()
}
