package com.ruxi.app.data.model

import kotlinx.serialization.Serializable

/**
 * 外观设置。
 *
 * ## 为什么把「主题色」和「透明度」拆成两组
 * - [accentColor] 是**单一强调色**（发送按钮、选中态、正在输入）。
 *   只让用户调一个色相，比给一堆颜色各自调更不容易调丑。
 * - [backgroundImage] + [backgroundDim] + [backgroundBlur] 是一组：
 *   背景图本身往往太花，必须能压暗/模糊才看得清字。三个参数联动才可用。
 *
 * ## 透明度为什么用 Int 而不是 Float
 * kotlinx.serialization 对 Float 的序列化在各平台有精度差异，
 * 而这里是「用户拖动滑块」的场景，0~100 的整数完全够用且无歧义。
 */
@Serializable
data class UiSettings(
    /** 主题色，ARGB 十六进制字符串（如 "#B49BFF"）。空 = 用默认紫色 */
    val accentColor: String = "",
    /** 深色 / 浅色 / 跟随系统 */
    val themeMode: String = "dark",

    /** 自定义背景图路径（应用私有目录）。空 = 用纯色/渐变背景 */
    val backgroundImage: String = "",
    /** 背景压暗程度 0~90（%）。字压在图上看不清时调大 */
    val backgroundDim: Int = 45,
    /** 背景模糊半径 0~40（px）。把细节糊掉，让正文成为视觉焦点 */
    val backgroundBlur: Int = 0,
    /** 背景图透明度 0~100（%）。100 = 完全不透明 */
    val backgroundOpacity: Int = 100,

    /** 界面整体字号缩放 0.8~1.4 */
    val fontScale: Float = 1.0f,
    /** 聊天气泡里是否显示原始（带分隔符）文本 */
    val showRawText: Boolean = false,
    /** 是否显示「这轮注入了什么」的调试信息 */
    val showPromptInspector: Boolean = false,
    /** 气泡圆角 0~28 */
    val bubbleCorner: Int = 16,
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
