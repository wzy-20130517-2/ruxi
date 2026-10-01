package com.ruxi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ruxi.app.data.model.UiSettings

/**
 * 「如戏」配色。
 *
 * 基调：深紫罗兰。选紫色是因为它同时承载「戏剧感」（舞台幕布）
 * 和「夜间阅读舒适度」——长时间读对话，冷色底比纯黑更不容易疲劳。
 *
 * 强调色可被用户覆盖（[RuxiColors.accentOverride]），
 * 其余中性色保持固定，这样「换主题色」不会把界面搞乱。
 */
object RuxiColors {
    // 深色底
    val DarkBg = Color(0xFF0D0918)
    val DarkSurface = Color(0xFF161027)
    val DarkSurfaceHigh = Color(0xFF1F1735)
    val DarkSurfaceHighest = Color(0xFF2A2046)
    val DarkOutline = Color(0xFF3A2E5C)

    // 默认强调色
    val Primary = Color(0xFFB49BFF)
    val PrimaryDeep = Color(0xFF7C5CE0)
    val Secondary = Color(0xFFF2A6D8)
    val Tertiary = Color(0xFF7DE2D1)
    val Error = Color(0xFFFF8A9B)

    val TextPrimary = Color(0xFFF0ECFA)
    val TextSecondary = Color(0xFFA79FC0)
    val TextTertiary = Color(0xFF6F6789)

    // 浅色底
    val LightBg = Color(0xFFF7F4FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEFE9FA)
    val LightOutline = Color(0xFFD8CFF0)
    val LightTextPrimary = Color(0xFF1D1630)
    val LightTextSecondary = Color(0xFF5C5478)

    /**
     * 用户自定义的强调色。
     *
     * 用静态变量而不是 CompositionLocal：主题色在**很多非 Composable 上下文**
     * 里被用到（如渐变工厂函数、气泡颜色计算），走 Local 会到处传参。
     * 单进程单主题，静态变量足够且更简单。
     */
    @Volatile
    var accentOverride: Color? = null

    /** 当前生效的强调色（用户设了就用用户的） */
    val accent: Color get() = accentOverride ?: Primary

    /** 强调色的深色版本，用于按钮底色 */
    val accentDeep: Color get() = accentOverride?.darken(0.28f) ?: PrimaryDeep

    private fun Color.darken(f: Float) = Color(
        red = red * (1 - f),
        green = green * (1 - f),
        blue = blue * (1 - f),
        alpha = alpha,
    )
}

/** 从 "#RRGGBB" 解析颜色，失败返回 null */
fun parseHexColor(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    return runCatching {
        val v = s.toLong(16)
        if (s.length == 6) Color(0xFF000000L or v) else Color(v)
    }.getOrNull()
}

/** 颜色转 "#RRGGBB" */
fun Color.toHexString(): String {
    val argb = toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

private fun buildDarkScheme(accent: Color): androidx.compose.material3.ColorScheme {
    val deep = Color(
        red = accent.red * 0.68f,
        green = accent.green * 0.68f,
        blue = accent.blue * 0.68f,
    )
    return darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF1A1030),
        primaryContainer = deep,
        onPrimaryContainer = Color(0xFFEDE6FF),
        secondary = RuxiColors.Secondary,
        onSecondary = Color(0xFF3A1029),
        tertiary = RuxiColors.Tertiary,
        onTertiary = Color(0xFF04302A),
        background = RuxiColors.DarkBg,
        onBackground = RuxiColors.TextPrimary,
        surface = RuxiColors.DarkSurface,
        onSurface = RuxiColors.TextPrimary,
        surfaceVariant = RuxiColors.DarkSurfaceHigh,
        onSurfaceVariant = RuxiColors.TextSecondary,
        surfaceContainer = RuxiColors.DarkSurfaceHigh,
        surfaceContainerHigh = RuxiColors.DarkSurfaceHighest,
        outline = RuxiColors.DarkOutline,
        outlineVariant = Color(0xFF2A2046),
        error = RuxiColors.Error,
        onError = Color(0xFF3A0A14),
    )
}

private fun buildLightScheme(accent: Color): androidx.compose.material3.ColorScheme {
    val deep = Color(
        red = accent.red * 0.72f,
        green = accent.green * 0.72f,
        blue = accent.blue * 0.72f,
    )
    return lightColorScheme(
        primary = deep,
        onPrimary = Color.White,
        secondary = Color(0xFFB85C9E),
        onSecondary = Color.White,
        tertiary = Color(0xFF1F9E8A),
        onTertiary = Color.White,
        background = RuxiColors.LightBg,
        onBackground = RuxiColors.LightTextPrimary,
        surface = RuxiColors.LightSurface,
        onSurface = RuxiColors.LightTextPrimary,
        surfaceVariant = RuxiColors.LightSurfaceHigh,
        onSurfaceVariant = RuxiColors.LightTextSecondary,
        outline = RuxiColors.LightOutline,
        error = Color(0xFFC4384F),
    )
}

/** 排版：正文行高放宽（对话阅读为主），标题收紧字距 */
private fun typographyFor(scale: Float): Typography {
    fun sp(v: Float) = (v * scale).sp
    return Typography(
        displaySmall = TextStyle(fontSize = sp(30f), fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontSize = sp(24f), fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = TextStyle(fontSize = sp(20f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontSize = sp(18f), fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = sp(16f), fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = sp(16f), lineHeight = sp(26f)),
        bodyMedium = TextStyle(fontSize = sp(14f), lineHeight = sp(22f)),
        bodySmall = TextStyle(fontSize = sp(12f), lineHeight = sp(18f)),
        labelLarge = TextStyle(fontSize = sp(14f), fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = sp(12f), fontWeight = FontWeight.Medium),
    )
}

/** 把外观设置透传给深层组件（气泡圆角等） */
data class RuxiAppearance(
    val bubbleCorner: Int = 16,
    val fontScale: Float = 1.0f,
)

val LocalAppearance = staticCompositionLocalOf { RuxiAppearance() }

@Composable
fun RuxiTheme(
    settings: UiSettings = UiSettings(),
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        UiSettings.THEME_LIGHT -> false
        UiSettings.THEME_SYSTEM -> isSystemInDarkTheme()
        else -> true
    }

    val accent = parseHexColor(settings.accentColor) ?: RuxiColors.Primary
    RuxiColors.accentOverride = if (settings.accentColor.isBlank()) null else accent

    CompositionLocalProvider(
        LocalAppearance provides RuxiAppearance(
            bubbleCorner = settings.bubbleCorner,
            fontScale = settings.fontScale,
        )
    ) {
        MaterialTheme(
            colorScheme = if (dark) buildDarkScheme(accent) else buildLightScheme(accent),
            typography = typographyFor(settings.fontScale),
            content = content,
        )
    }
}

/** 常用渐变 */
object RuxiGradients {
    val primary: Brush
        get() = Brush.linearGradient(
            listOf(RuxiColors.accent, RuxiColors.Secondary)
        )
    val cool: Brush
        get() = Brush.linearGradient(
            listOf(RuxiColors.accentDeep, RuxiColors.Tertiary)
        )
}
