package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
