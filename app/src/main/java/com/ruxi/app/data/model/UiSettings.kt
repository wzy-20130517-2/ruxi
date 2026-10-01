package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/** 界面外观设置 */
@Serializable
data class UiSettings(
    /** 是否显示「这轮注入了什么」的调试面板 */
    val showPromptInspector: Boolean = false,
    /** 聊天气泡里是否显示原始（带分隔符）文本 */
    val showRawText: Boolean = false,
    /** 字号缩放，1.0 = 默认 */
    val fontScale: Float = 1.0f,
    /** 深色 / 浅色 / 跟随系统 */
    val themeMode: String = "dark",
) {
    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"
    }
}
