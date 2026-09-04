package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 扇形面板毛玻璃的"背后画面"采样器（批次 1.5）。
 *
 * 只保留一条主链：呼出瞬间 PixelCopy 自截宿主（launcher）窗口 → 深缩小
 * （长边 128px）→ Compose state。面板显示时把小图拉伸 6~10 倍铺进扇形——
 * 插值放大本身就是强糊化，面板拿到的永远是"已经糊好的图"，不存在
 * "先清晰后糊化"的闪现帧；无画面时 FanBackground 走着色弧兜底。
 *
 * 为什么深缩小替代模糊 API：RenderEffect 离线糊在这套 SDK 上不可用
 * （Paint.setRenderEffect 未解析），而缩小+放大的插值糊观感等同毛玻璃
 * 且零 API 风险、零 GPU 帧成本。壁纸 prewarm 已按用户拍板移除。
 *
 * 手势必在 launcher 前台（EdgeGestureHook 拦的是 launcher 手势视图），
 * PixelCopy 截到的必然是当前可见画面。
 */
object FanBackdrop {

    private const val TAG = "FanBackdrop"

    /** 深缩小目标长边：显示时拉伸 6~10 倍 = 强插值糊化。 */
    private const val TINY_MAX_DIM = 128

    /** 当前糊化画面；null=未就绪，FanBackground 走着色弧兜底。 */
    var image: ImageBitmap? by mutableStateOf(null)
        private set

    @Volatile private var capturing = false

    /**
     * PixelCopy 自截宿主（launcher）窗口，异步：截图 → 子线程深缩小 → state。
     * 调用线程=主线程（showFan 的 Runnable）。防重入；失败静默（兜底弧接管）。
     */
    fun captureHostWindow(view: View) {
        if (capturing) return
        val window = view.findActivityWindow() ?: return
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return
        capturing = true
        val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, shot, { result ->
                if (result == PixelCopy.SUCCESS) {
                    Thread {
                        try {
                            val tiny = downscaleTo(shot, TINY_MAX_DIM)
                            image = tiny.asImageBitmap()
                            Log.i(TAG, "backdrop ready: ${tiny.width}x${tiny.height}")
                        } catch (t: Throwable) {
                            Log.w(TAG, "shrink failed: ${t.message}")
                        } finally {
                            capturing = false
                        }
                    }.start()
                } else {
                    capturing = false
                    shot.recycle()
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure {
            capturing = false
            shot.recycle()
            Log.w(TAG, "pixelcopy request failed: ${it.message}")
        }
    }

    /** GestureStubView 的 context 链向上找 launcher Activity 的 window。 */
    private fun View.findActivityWindow(): Window? {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is android.app.Activity) return ctx.window
            ctx = ctx.baseContext
        }
        return null
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
