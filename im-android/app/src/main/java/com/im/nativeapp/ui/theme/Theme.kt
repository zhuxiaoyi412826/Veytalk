package com.im.nativeapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 微信风配色（对齐微信移动端视觉规范）：
 * - 品牌绿 #07C160：导航选中、主按钮、强调色
 * - 自己气泡 #95EC69 浅绿底黑字；对方气泡白底黑字
 * - 聊天页背景 #EDEDED；列表/卡片白色
 */

/** 聊天气泡等不随 Material 语义色走的微信专属色，深浅色各一套 */
object WxColors {
    val Brand = Color(0xFF07C160)

    // 气泡
    val BubbleSelf = Color(0xFF95EC69)
    val BubbleSelfDark = Color(0xFF2E8B57)
    val BubblePeer = Color(0xFFFFFFFF)
    val BubblePeerDark = Color(0xFF2C2C2C)
    val OnBubble = Color(0xFF111111)
    val OnBubbleDark = Color(0xFFECECEC)

    // 聊天页背景
    val ChatBg = Color(0xFFEDEDED)
    val ChatBgDark = Color(0xFF111111)
}

/** 主题色预设（设置页的色块；green 为默认微信绿） */
val AccentPresets: List<Pair<String, Color>> = listOf(
    "green" to Color(0xFF07C160),
    "blue" to Color(0xFF1E90FF),
    "purple" to Color(0xFF8A2BE2),
    "orange" to Color(0xFFFF8C00),
    "red" to Color(0xFFE64340),
    "dark" to Color(0xFF333333)
)

fun accentColor(name: String): Color =
    AccentPresets.firstOrNull { it.first == name }?.second ?: WxColors.Brand

/** "#RRGGBB" → Color；非法值返回 null（自定义气泡色为空时回落到主题默认） */
fun colorFromHex(hex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

private val WxLightColors = lightColorScheme(
    primary = WxColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F5E3),
    onPrimaryContainer = Color(0xFF0B3D22),
    secondary = Color(0xFF576B95),          // 微信链接蓝（用于次要文字/说明）
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F7),          // 微信列表页底色
    surfaceVariant = Color(0xFFF0F0F0),
    outline = Color(0xFFE5E5E5)              // 微信分割线色
)

private val WxDarkColors = darkColorScheme(
    primary = Color(0xFF4CD787),
    onPrimary = Color(0xFF06391E),
    primaryContainer = Color(0xFF1B5E38),
    onPrimaryContainer = Color(0xFFD9F5E3),
    secondary = Color(0xFF7D8FAF),
    surface = Color(0xFF1E1E1E),
    background = Color(0xFF111111),
    surfaceVariant = Color(0xFF2A2A2A),
    outline = Color(0xFF333333)
)

/**
 * 应用主题：themeMode 取 light/dark/system（默认跟随系统）；
 * accent 为主题色名（AccentPresets 的 key）；fontScale 为全局字号倍率。
 */
@Composable
fun ImTheme(
    themeMode: String = "system",
    accent: String = "green",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = if (dark) WxDarkColors else WxLightColors
    val scheme = base.copy(
        primary = accentColor(accent),
        onPrimary = Color.White,
        primaryContainer = accentColor(accent).copy(alpha = 0.15f)
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale)
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
