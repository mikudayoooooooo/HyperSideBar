package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun QuickAppsBar(
    geometry: FanGeometry,
    selectedIndex: Int,
    colors: FanThemeColors,
    onQuickAppSelected: (FanAppInfo) -> Unit
) {
    val context = LocalContext.current
    val quickApps = geometry.quickApps.take(6)
    if (quickApps.isEmpty()) return

    val iconSizeDp = geometry.quickIconSize
    val density = LocalDensity.current.density
    val pxIconSize = iconSizeDp * density
    val pxSpacing = pxIconSize * 0.35f
    val barPadding = pxIconSize * 0.5f

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .offset {
                    IntOffset(
                        geometry.quickBarX.toInt(),
                        geometry.quickBarY.toInt()
                    )
                }
                .clip(RoundedCornerShape((iconSizeDp / 2f + 4f).dp))
                .background(colors.surfaceContainer.copy(alpha = 0.9f))
                .padding(
                    horizontal = (iconSizeDp * 0.25f).dp,
                    vertical = (iconSizeDp * 0.25f).dp
                ),
            horizontalArrangement = Arrangement.spacedBy((iconSizeDp * 0.35f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            quickApps.forEachIndexed { index, app ->
                QuickAppIcon(
                    context = context,
                    app = app,
                    iconSize = iconSizeDp,
                    isSelected = index == selectedIndex,
                    colors = colors,
                    onClick = { onQuickAppSelected(app) }
                )
            }
        }
    }
}

@Composable
private fun QuickAppIcon(
    context: Context,
    app: FanAppInfo,
    iconSize: Float,
    isSelected: Boolean,
    colors: FanThemeColors,
    onClick: () -> Unit
) {
    val (drawable, fallbackColor) = rememberAppIcon(context, app)
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val targetAlpha = if (isSelected) 1f else 0.75f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))
    val iconAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(100))

    Box(
        modifier = Modifier
            .size(iconSize.dp)
            .scale(iconScale)
            .alpha(iconAlpha)
            .clip(CircleShape)
            .background(
                if (isSelected) colors.primaryContainer.copy(alpha = 0.9f)
                else colors.surfaceContainerHigh
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIconImage(
            drawable = drawable,
            fallbackColor = fallbackColor,
            appName = app.appName,
            size = iconSize,
            colors = colors
        )

        if (isSelected) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = colors.primary,
                    radius = size.minDimension / 2f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    alpha = iconAlpha
                )
            }
        }
    }
}

@Composable
fun AppIconImage(
    drawable: Drawable?,
    fallbackColor: Int,
    appName: String,
    size: Float,
    colors: FanThemeColors
) {
    if (drawable != null) {
        val bitmap = rememberIconBitmap(drawable)
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = appName,
                modifier = Modifier.size(size.dp)
            )
        } else {
            FallbackIcon(appName, size, fallbackColor, colors)
        }
    } else {
        FallbackIcon(appName, size, fallbackColor, colors)
    }
}

@Composable
private fun rememberIconBitmap(drawable: Drawable): android.graphics.Bitmap? {
    return remember(drawable) {
        runCatching { drawable.toBitmap(width = 128, height = 128) }.getOrNull()
    }
}

@Composable
private fun FallbackIcon(
    appName: String,
    size: Float,
    fallbackColor: Int,
    colors: FanThemeColors
) {
    val text = appName.take(1).ifEmpty { "?" }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(fallbackColor)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.onPrimary,
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1
        )
    }
}
