package com.lsp.hypersidebar.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * hyperSidebar 主题模式。
 *
 * - system:         跟随系统浅色/深色
 * - light:          强制浅色
 * - dark:           强制深色
 * - monet_system:   Material You 动态取色，跟随系统
 * - monet_light:    Material You 动态取色，强制浅色
 * - monet_dark:     Material You 动态取色，强制深色
 */
typealias ThemeMode = String

object ThemeModes {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val MONET_SYSTEM = "monet_system"
    const val MONET_LIGHT = "monet_light"
    const val MONET_DARK = "monet_dark"

    val ALL = listOf(SYSTEM, LIGHT, DARK, MONET_SYSTEM, MONET_LIGHT, MONET_DARK)

    val BASE_MODES = listOf(SYSTEM, LIGHT, DARK)

    fun baseMode(mode: ThemeMode): ThemeMode = when (mode) {
        MONET_LIGHT -> LIGHT
        MONET_DARK -> DARK
        MONET_SYSTEM -> SYSTEM
        LIGHT, DARK, SYSTEM -> mode
        else -> SYSTEM
    }

    fun usesSystemColors(mode: ThemeMode): Boolean = mode in setOf(
        MONET_SYSTEM,
        MONET_LIGHT,
        MONET_DARK
    )

    fun compose(baseMode: ThemeMode, useSystemColors: Boolean): ThemeMode {
        val normalizedBase = baseMode(baseMode)
        if (!useSystemColors) return normalizedBase
        return when (normalizedBase) {
            LIGHT -> MONET_LIGHT
            DARK -> MONET_DARK
            else -> MONET_SYSTEM
        }
    }

    fun toDisplayName(mode: ThemeMode): String = when (mode) {
        SYSTEM -> "跟随系统"
        LIGHT -> "浅色模式"
        DARK -> "深色模式"
        MONET_SYSTEM -> "Monet 跟随系统"
        MONET_LIGHT -> "Monet 浅色"
        MONET_DARK -> "Monet 深色"
        else -> "跟随系统"
    }
}

val DefaultKeyColor = Color(0xFF3482FF)

/**
 * 语义化颜色，补充 MIUIX 未暴露的 success / warning。
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color
)

private val LightSemantic = SemanticColors(
    success = Color(0xFF4CAF50),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFF4CAF50).copy(alpha = 0.12f),
    warning = Color(0xFFFF9800),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFF9800).copy(alpha = 0.12f)
)

private val DarkSemantic = SemanticColors(
    success = Color(0xFF6ABF69),
    onSuccess = Color(0xFF000000),
    successContainer = Color(0xFF6ABF69).copy(alpha = 0.15f),
    warning = Color(0xFFFFB74D),
    onWarning = Color(0xFF000000),
    warningContainer = Color(0xFFFFB74D).copy(alpha = 0.15f)
)

val LocalSemanticColors = compositionLocalOf { LightSemantic }

@Composable
fun HyperSidebarTheme(
    colorMode: ThemeMode = ThemeModes.MONET_SYSTEM,
    keyColor: Color = DefaultKeyColor,
    content: @Composable () -> Unit
) {
    val controller = remember(colorMode, keyColor) {
        val schemeMode = when (colorMode) {
            ThemeModes.LIGHT -> ColorSchemeMode.Light
            ThemeModes.DARK -> ColorSchemeMode.Dark
            ThemeModes.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ThemeModes.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ThemeModes.MONET_DARK -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.System
        }
        ThemeController(
            colorSchemeMode = schemeMode,
            keyColor = keyColor,
            paletteStyle = ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.Spec2025
        )
    }

    val isDark = when (colorMode) {
        ThemeModes.DARK, ThemeModes.MONET_DARK -> true
        ThemeModes.LIGHT, ThemeModes.MONET_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val semantic = if (isDark) DarkSemantic else LightSemantic

    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MiuixTheme(controller = controller, content = content)
    }
}
