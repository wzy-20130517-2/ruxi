package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 全局外观设置。
 *
 * ⚠️ 背景图**不在这里** —— 它按角色卡独立（见 [CharacterCard.backgroundImage]）。
 * 全局只保留「跟角色无关」的那些：主题色、明暗模式、字号、气泡圆角。
 */
@Serializable
data class UiSettings(
    /** 主题色，ARGB 十六进制字符串（如 "#B49BFF"）。空 = 用默认紫色 */
    val accentColor: String = "",
    /** 深色 / 浅色 / 跟随系统 */
    val themeMode: String = "dark",
    /** 界面整体字号缩放 0.8~1.4 */
    val fontScale: Float = 1.0f,
    /** 气泡圆角 0~28 */
    val bubbleCorner: Int = 16,
    /** 是否在气泡里显示模型的思考过程（reasoning_content） */
    val showThinking: Boolean = true,
    /** 思考内容默认展开还是折叠 */
    val thinkingExpanded: Boolean = false,
) {
    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        /** 内置主题色预设：名字 + ARGB */
        val PRESETS = listOf(
            "紫罗兰" to "#B49BFF",
            "樱粉" to "#F2A6D8",
            "青碧" to "#7DE2D1",
            "琥珀" to "#F0B47C",
            "天蓝" to "#6AC5E8",
            "珊瑚" to "#FF8A9B",
            "薄荷" to "#9BE08C",
            "靛青" to "#8F7BF0",
        )
    }
}
