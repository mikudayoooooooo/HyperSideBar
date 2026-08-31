package com.lsp.hypersidebar.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 快捷方式入口状态机已随 nav3 迁移（§2.1）解散进 SettingsKey 栈：
// 列表（ShortcutList）/ 编辑-添加（ShortcutEdit）/ 选择器（ShortcutPicker）三级
// 见 Screen.kt 与 MainScreen.kt 的 entryProvider。本文件只承载跨文件共享的图标组件。

/**
 * 设置页专用的应用图标组件。
 * 经 IconLoader 异步加载（LruCache + IO 线程），不在组合期做 PackageManager IPC。
 */
@Composable
internal fun SettingsAppIcon(
    packageName: String,
    appName: String,
    size: Float
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 128, height = 128)
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = appName,
            modifier = Modifier.size(size.dp)
        )
    } else {
        FallbackSettingsIcon(appName, size)
    }
}

@Composable
private fun FallbackSettingsIcon(appName: String, size: Float) {
    val text = appName.take(1).ifEmpty { "?" }
    val colors = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFFF7043, 0xFFAB47BC)
    val colorIndex = remember(appName) { (appName.hashCode() and 0x7FFFFFFF) % colors.size }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(colors[colorIndex])),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MiuixTheme.textStyles.body1
        )
    }
}
