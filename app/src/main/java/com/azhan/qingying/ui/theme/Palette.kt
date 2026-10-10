package com.azhan.qingying.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 一套配色。
 *
 * 拟态（Neumorphism）的关键约束：元素与背景同色，只靠「左上受光 + 右下背光」
 * 两个方向的阴影塑造体积。所以这里必须同时给出 highlight / shadow 两色，
 * 而不是普通 UI 那样只给一个 elevation。
 */
data class NeuPalette(
    val background: Color,
    val surface: Color,
    /** 左上高光色，用于外阴影的亮侧与内阴影的亮侧。 */
    val highlight: Color,
    /** 右下背光色。 */
    val shadow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val divider: Color,
    val isDark: Boolean,
)

/** 深色外壳（列表 / 设置）。默认。 */
val DarkShell = NeuPalette(
    background = Color(0xFF16181D),
    surface = Color(0xFF16181D),
    highlight = Color(0xFF23272F),
    shadow = Color(0xFF0A0C0F),
    textPrimary = Color(0xFFD7DBE3),
    textSecondary = Color(0xFF828B99),
    accent = Color(0xFF6C8CFF),
    divider = Color(0xFF252932),
    isDark = true,
)

/** 浅色外壳。 */
val LightShell = NeuPalette(
    background = Color(0xFFEDEFF3),
    surface = Color(0xFFEDEFF3),
    highlight = Color(0xFFFFFFFF),
    shadow = Color(0xFFC2C8D2),
    textPrimary = Color(0xFF2B2F36),
    textSecondary = Color(0xFF878E98),
    accent = Color(0xFF4E62E0),
    divider = Color(0xFFDCE0E6),
    isDark = false,
)

/**
 * 播放页配色：恒为近黑。
 *
 * 视频画面周围必须足够暗，任何浅色底都会在画面边缘形成一圈刺眼的亮框；
 * 也不跟随外壳的亮/暗设置 —— 这是内容页，不是导航页。
 */
val PlayerPalette = NeuPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF0B0C0F),
    highlight = Color(0xFF1C1E23),
    shadow = Color(0xFF000000),
    textPrimary = Color(0xFFE3E6EB),
    textSecondary = Color(0xFF8A9099),
    accent = Color(0xFF6C8CFF),
    divider = Color(0xFF23262C),
    isDark = true,
)

val LocalNeuPalette = staticCompositionLocalOf { DarkShell }
