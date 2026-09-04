package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

object IconLoader {
    private val cache = LruCache<String, Drawable>(128)

    suspend fun load(context: Context, packageName: String): Drawable? = withContext(Dispatchers.IO) {
        if (packageName.isEmpty()) return@withContext null
        synchronized(cache) {
            cache.get(packageName)
        } ?: runCatching {
            val icon = context.packageManager.getApplicationIcon(packageName)
            synchronized(cache) { cache.put(packageName, icon) }
            icon
        }.getOrNull()
    }

    fun clear() {
        cache.evictAll()
    }
}

@Composable
fun rememberAppIcon(
    context: Context,
    app: FanAppInfo
): Pair<Drawable?, Int> {
    val state = remember(app.packageName) {
        mutableStateOf<Drawable?>(null)
    }

    LaunchedEffect(app.packageName) {
        state.value = if (app.isAction && app.packageName.isEmpty()) {
            null
        } else {
            IconLoader.load(context, app.packageName)
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

/**
 * 图标主色提取器（批次 1.5 毛玻璃 v2）：把每个应用的图标平均色提取出来，
 * 作为扇形/快捷栏毛玻璃**源层的柔色光斑**——糊化后呈现"应用背后的色彩晕染"
 * （HyperOS 控制中心/文件夹同款观感），每次呼出的底色跟应用组合走。
 *
 * 平均色 = 图标缩到 1x1 的像素值（标准做法）；per-pkg 缓存，呼出时批量
 * 后台提取，[revision] 驱动 Compose 重组让新色斑即时出现。
 */
object IconPalette {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 提取批次计数：Compose 读它驱动源层重绘（新主色到位即出现色斑）。 */
    var revision by mutableStateOf(0)
        private set

    /** 已提取的主色（ARGB）；未提取返回 null（该位置无色斑，渐变底兜底）。 */
    fun colorOf(pkg: String): Int? = cache[pkg]

    /** 批量后台提取（去重、跳过已缓存）。完成一批后 [revision] 自增。 */
    fun extractAsync(context: Context, pkgs: List<String>, onBatch: () -> Unit) {
        Thread {
            var extracted = 0
            pkgs.distinct().filter { it.isNotEmpty() }.forEach { pkg ->
                if (cache.containsKey(pkg)) return@forEach
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(pkg)
                    val bmp = drawable.toBitmap(48, 48)
                    val avg = android.graphics.Bitmap.createScaledBitmap(bmp, 1, 1, true)
                        .getPixel(0, 0)
                    cache[pkg] = avg
                    extracted++
                }
            }
            if (extracted > 0) {
                revision++
                onBatch()
            }
        }.start()
    }
}
