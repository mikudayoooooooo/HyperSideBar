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
 * 扇形面板毛玻璃的"背后画面"采样器（批次 1.5）。
 *
 * 面板是 TYPE_APPLICATION_OVERLAY 窗口——miuix-blur 的 LayerBackdrop 只能记录
 * 同窗口图层，采不到窗外真实内容；宿主恰好是 launcher（com.miui.home）进程，
 * 壁纸零权限在手：prewarm 取当前壁纸缩半缓存，作为毛玻璃的糊化源。
 *
 * 观感口径：糊的是真实壁纸（桌面的主体背景），桌面图标/小部件是前景元素本就
 * 应"透"出；壁纸滚动偏移与状态栏错位在糊化后不可辨（Image 用 Crop 铺满即可）。
 *
 * PixelCopy 窗口自截（壁纸+图标+小部件全量合成）为后续增强：需要 hook 侧把
 * launcher Activity window 传入，本轮未接。
 */
object FanBackdrop {

    private const val TAG = "FanBackdrop"

    /** 缩放目标（约屏宽 1/2）：糊化层无细节需求，省 4x 内存且天然抑制锯齿。 */
    private const val SAMPLE_MAX_DIM = 720

    /** 当前背后画面（壁纸快照）；null=未就绪/采样失败，FanBackground 走着色弧兜底。 */
    var image: ImageBitmap? by mutableStateOf(null)
        private set

    @Volatile private var started = false

    /** 幂等预热：首次调用启动后台采样（launcher 进程内，壁纸读取无权限要求）。 */
    fun prewarm(context: Context) {
        if (started) return
        started = true
        Thread {
            runCatching {
                val wm = android.app.WallpaperManager.getInstance(context)
                val drawable = wm.peekDrawable() ?: wm.drawable ?: return@Thread
                val full = when (drawable) {
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
                val sampled = downscale(full)
                image = sampled.asImageBitmap()
                Log.i(TAG, "wallpaper sampled: ${sampled.width}x${sampled.height}")
            }.onFailure { Log.w(TAG, "wallpaper sample failed: ${it.message}") }
        }.start()
    }

    /** 长边压到 [SAMPLE_MAX_DIM]；小图原样返回（copy 防共享可变位图）。 */
    private fun downscale(src: Bitmap): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= SAMPLE_MAX_DIM) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        val scale = SAMPLE_MAX_DIM.toFloat() / long
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
