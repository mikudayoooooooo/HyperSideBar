package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.lsp.hypersidebar.util.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * 扇形图标状态（迭代六 2026-09-13 与 AllApps 的 AppIconCache 统一，IconLoader Drawable 缓存退役）：
 * 与 AllApps 面板共用进程级 LruCache（pkg → 128px Bitmap），预热（FanPrewarmer）与渲染同源。
 * 初值主线程同步查缓存（LruCache 查询无 binder）——命中则首帧即真图标，消灭此前
 * "每次呼出都从字母占位起步、异步换图闪变"（组合随池化逐呼出重建，旧异步路径必晚 1-2 帧）；
 * 仅真 miss 才走 IO 加载，到手后免主线程解码（旧路径 toBitmap 在组合期做，掉帧落在入场动画上）。
 */
@Composable
fun rememberAppIcon(
    context: Context,
    app: FanAppInfo
): Pair<Bitmap?, Int> {
    val isRealIcon = !(app.isAction && app.packageName.isEmpty())
    val state = remember(app.packageName) {
        mutableStateOf(if (isRealIcon) AppIconCache.peek(app.packageName) else null)
    }

    LaunchedEffect(app.packageName) {
        if (isRealIcon && state.value == null) {
            state.value = withContext(Dispatchers.IO) { AppIconCache.load(context, app.packageName) }
        }
    }

    return state.value to generateFallbackColor(app.appName)
}

fun generateFallbackColor(text: String): Int {
    val colors = listOf(
        Color(0xFF7986CB),
        Color(0xFF4DB6AC),
        Color(0xFF4FC3F7),
        Color(0xFFA5D6A7),
        Color(0xFFFFCC80),
        Color(0xFFCE93D8),
        Color(0xFF90A4AE),
        Color(0xFFEF9A9A)
    )
    return colors[text.hashCode().absoluteValue % colors.size].toArgb()
}
