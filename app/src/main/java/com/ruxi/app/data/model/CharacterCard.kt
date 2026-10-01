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
    // ── 每角色独立的外观（2026-10-01 从全局 UiSettings 挪过来）──
    //
    // 为什么该按角色卡分：背景图是**氛围的一部分**。
    // 一个古风角色配水墨背景、一个赛博角色配霓虹夜景，
    // 全局一张背景等于强迫所有角色共用一个氛围，那还不如不做这个功能。
    /** 该角色的对话背景图路径。空 = 用全局默认背景 */
    val backgroundImage: String = "",
    /** 背景压暗 0~90（%），字压在图上看不清时调大 */
    val backgroundDim: Int = 45,
    /** 背景模糊半径 0~40 */
    val backgroundBlur: Int = 0,
    /** 背景透明度 0~100（%） */
    val backgroundOpacity: Int = 100,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 卡片是否已配置到可用状态（列表页用来提示「未配置」） */
    val isConfigured: Boolean get() = systemSetting.isNotBlank()

    /** 是否有自定义背景 */
    val hasBackground: Boolean get() = backgroundImage.isNotBlank()
}
