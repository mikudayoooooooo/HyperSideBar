package com.lsp.hypersidebar.ui.fan

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color

/**
 * 扇形菜单中单个应用/快捷项的数据模型。
 */
data class FanAppInfo(
    val packageName: String,
    val appName: String = "",
    val actionHandle: ((android.content.Context) -> Unit)? = null
) {
    val isAction: Boolean get() = actionHandle != null
}

/**
 * 扇形菜单配置，由设置页和 TurboLayout 注入。
 */
data class FanConfig(
    /** 图标直径 dp */
    val iconSizeDp: Float = 48f,
    /** 快捷栏图标直径 dp */
    val quickIconSizeDp: Float = 36f,
    /** 内圈半径 dp（双圈内层） */
    val innerRadiusDp: Float = 150f,
    /** 外圈最大半径 dp（双圈外层） */
    val outerRadiusDp: Float = 200f,
    /** 默认扇形张角（角度） */
    val defaultSpanAngle: Float = 150f,
    /** 摇杆死区 dp */
    val deadZoneDp: Float = 12f,
    /** 摇杆有效区半径 dp，触摸超出此范围视为放弃 */
    val activeZoneDp: Float = 60f,
    /** 是否使用双层环 */
    val useDualRing: Boolean = true,
    /** 最小可用半径 dp，低于此值回退到单环 */
    val minRadiusDp: Float = 80f
)

/**
 * 扇形菜单运行时颜色。
 *
 * 由于 hook 侧运行在系统进程，无法直接复用 Compose LocalProvider，
 * 因此把需要使用的颜色打包传入。
 */
data class FanThemeColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val background: Color,
    val isDark: Boolean
) {
    companion object {
        fun default(isDark: Boolean = false): FanThemeColors = FanThemeColors(
            primary = Color(0xFF3482FF),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEAF2FF),
            onPrimaryContainer = Color(0xFF3482FF),
            surface = if (isDark) Color(0xFF000000) else Color(0xFFF7F7F7),
            surfaceContainer = if (isDark) Color(0xFF242424) else Color.White,
            surfaceContainerHigh = if (isDark) Color(0xFF2D2D2D) else Color(0xFFE8E8E8),
            onSurface = if (isDark) Color(0xFFF2F2F2) else Color.Black,
            onSurfaceVariant = if (isDark) Color(0xFF737373) else Color(0xFF959595),
            outline = if (isDark) Color(0xFF404040) else Color(0xFFD9D9D9),
            background = if (isDark) Color(0xFF242424) else Color.White,
            isDark = isDark
        )
    }
}

/**
 * 加载后的图标包装。
 */
internal data class AppIcon(
    val app: FanAppInfo,
    val drawable: Drawable?,
    val fallbackColor: Int
)
