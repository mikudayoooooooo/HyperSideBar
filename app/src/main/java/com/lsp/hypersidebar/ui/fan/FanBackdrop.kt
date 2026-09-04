package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 扇形面板毛玻璃的"背后画面"（批次 1.5 定稿）。
 *
 * 收敛后的唯一主链：**壁纸一次缓存 + 深缩糊化**——WallpaperManager 取壁纸
 * （宿主=launcher 进程零权限、永不失败）→ 一次性深缩到长边 [TINY_MAX_DIM]
 * → Compose state 缓存，进程内永不变更。面板显示时拉伸 6~10 倍，插值放大
 * 即强糊化。从始至终只有一张图：无切换、无跳变、无清晰帧闪现。
 *
 * 被否定的路径存档：
 * - 实时 RenderEffect（textureBlur）：源图必须先绘制，糊化首帧未就绪露清晰帧
 * - 离线 RenderEffect 高斯糊：开发机 SDK 37 上 Paint.setRenderEffect 编译不过
 * - PixelCopy 自截 launcher window：MIUI 桌面壁纸层是独立 surface（live
 *   wallpaper 引擎），不在 window view 渲染里——截不到壁纸，内容无效
 * - 壁纸→截图双源切换：用户实测跳变不可接受
 *
 * 壁纸滚动偏移/状态栏错位在 8x 糊化后不可辨；壁纸切换低频，hook 进程重启
 * 自然刷新（不引入监听复杂度）。
 */
object FanBackdrop {

    private const val TAG = "FanBackdrop"

    /** 深缩小目标长边：显示时拉伸 6~10 倍 = 强插值糊化。 */
    private const val TINY_MAX_DIM = 128

    /** 糊化画面（壁纸快照）；null=未就绪，FanBackground 走着色弧兜底。 */
    var image: ImageBitmap? by mutableStateOf(null)
        private set

    @Volatile private var started = false

    /** 幂等预热：首次调用启动后台采样（ComposeFanHost.show 入口）。 */
    fun prewarm(context: Context) {
        if (started) return
        started = true
        Thread {
            runCatching {
                val wm = android.app.WallpaperManager.getInstance(context)
                val drawable = wm.peekDrawable() ?: wm.drawable ?: return@Thread
                val full = drawableToBitmap(drawable)
                val tiny = downscaleTo(full, TINY_MAX_DIM)
                image = tiny.asImageBitmap()
                Log.i(TAG, "backdrop ready: ${tiny.width}x${tiny.height}")
            }.onFailure { Log.w(TAG, "wallpaper sample failed: ${it.message}") }
        }.start()
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap =
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val w = maxOf(1, drawable.intrinsicWidth)
                val h = maxOf(1, drawable.intrinsicHeight)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(raw)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                raw
            }
        }

    /** 长边压到 [target]（双线性过滤；一次性大比例缩小即糊化本体）。 */
    private fun downscaleTo(src: Bitmap, target: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= target) return src
        val scale = target.toFloat() / long
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
