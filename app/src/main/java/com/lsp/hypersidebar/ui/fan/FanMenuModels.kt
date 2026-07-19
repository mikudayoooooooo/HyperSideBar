package com.lsp.hypersidebar.ui.fan

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color

data class FanAppInfo(
    val packageName: String,
    val appName: String = "",
    val actionHandle: ((android.content.Context) -> Unit)? = null
) {
    val isAction: Boolean get() = actionHandle != null
}

data class FanConfig(
    val iconSizeDp: Float = 48f,
    val quickIconSizeDp: Float = 36f,
    val innerRadiusDp: Float = 150f,
    val outerRadiusDp: Float = 200f,
    val defaultSpanAngle: Float = 150f,
    val landscapeSpanAngle: Float = 75f,
    val deadZoneDp: Float = 12f,
    val activeZoneDp: Float = 60f,
    val useDualRing: Boolean = true,
    val minRadiusDp: Float = 60f,
    val maxAppsOuter: Int = 7,
    val maxAppsInner: Int = 4,
    val landscapeIconSizeDp: Float = 48f,
    val landscapeMaxAppsOuter: Int = 5,
    val landscapeMaxAppsInner: Int = 3,
    val landscapeInnerRadiusDp: Float = 150f,
    val landscapeOuterRadiusDp: Float = 200f
)

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

internal data class AppIcon(
    val app: FanAppInfo,
    val drawable: Drawable?,
    val fallbackColor: Int
)
