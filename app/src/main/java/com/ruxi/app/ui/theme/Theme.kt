package com.ruxi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「如戏」配色。
 *
 * 基调：深紫罗兰 + 暖粉点缀。选紫色是因为它同时承载「戏剧感」（舞台幕布）
 * 和「夜间阅读舒适度」两个需求 —— 长时间读小说式对话，冷色底比纯黑更不容易疲劳。
 *
 * 强调色（粉紫 / 青）只用在**交互反馈**上（选中态、发送按钮、正在输入），
 * 正文区域保持低饱和，避免满屏彩色造成的廉价感。
 */
object RuxiColors {
    // 深色（默认）
    val DarkBg = Color(0xFF0D0918)
    val DarkSurface = Color(0xFF161027)
    val DarkSurfaceHigh = Color(0xFF1F1735)
    val DarkSurfaceHighest = Color(0xFF2A2046)
    val DarkOutline = Color(0xFF3A2E5C)

    val Primary = Color(0xFFB49BFF)
    val PrimaryDeep = Color(0xFF7C5CE0)
    val Secondary = Color(0xFFF2A6D8)
    val Tertiary = Color(0xFF7DE2D1)
    val Error = Color(0xFFFF8A9B)

    val TextPrimary = Color(0xFFF0ECFA)
    val TextSecondary = Color(0xFFA79FC0)
    val TextTertiary = Color(0xFF6F6789)

    // 浅色
    val LightBg = Color(0xFFF7F4FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEFE9FA)
    val LightOutline = Color(0xFFD8CFF0)
    val LightTextPrimary = Color(0xFF1D1630)
    val LightTextSecondary = Color(0xFF5C5478)
}

private val DarkScheme = darkColorScheme(
    primary = RuxiColors.Primary,
    onPrimary = Color(0xFF1A1030),
    primaryContainer = RuxiColors.PrimaryDeep,
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

private val LightScheme = lightColorScheme(
    primary = RuxiColors.PrimaryDeep,
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

/** 排版：正文行高放宽（对话阅读为主），标题收紧字距（更有设计感） */
private val RuxiTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun RuxiTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = RuxiTypography,
        content = content,
    )
}

/** 常用渐变：主按钮、头像占位、顶部光晕 */
object RuxiGradients {
    val primary = Brush.linearGradient(
        listOf(Color(0xFF9B7BFF), Color(0xFFE08CD0))
    )
    val cool = Brush.linearGradient(
        listOf(Color(0xFF6C5CE7), Color(0xFF3EC8C0))
    )
    val surfaceGlow = Brush.verticalGradient(
        listOf(Color(0x33B49BFF), Color(0x00000000))
    )
}
