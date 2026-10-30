package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 提示词引擎的全局设置。
 *
 * ## 隐藏要求是干什么的
 *
 * 给角色卡附加一段「输出规范」—— 世界观之外，额外约束模型该怎么写。
 * 角色卡上的 [CharacterCard.hiddenRequirement] 优先，留空则用全局模板。
 */
@Serializable
data class PromptSettings(
    /** 是否附加隐藏要求。关掉后就是一个普通 RP 应用。 */
    val hiddenEnabled: Boolean = true,

    /** 隐藏要求正文。留空 = 不附加任何隐藏要求。 */
    val hiddenTemplate: String = "",
)
