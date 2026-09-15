package com.lsp.hypersidebar.util

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * 壁纸位图缓存（0914 壁纸磨砂：竖屏 launcher fan 背后恒为壁纸——取进窗口内垫底+
 * miuix textureBlur 内部采样=真磨砂，零系统模糊 API）。老实现"呼出现场取壁纸→现场
 * 模糊"的闪烁病根=取+糊在关键路径；本类把两段全挪出呼出：半分辨率位图（~2.5MB，
 * 模糊采样足够）init 空闲期预载，壁纸变更广播+TTL 失效重取，呼出时 peek 恒命中、
 * 首帧即模糊。缓存未就绪 peek=null（调用方退亚克力，同样不闪）。
 */
object WallpaperSampler {

    private const val TAG = "WallpaperSampler"
    private const val TTL_MS = 5 * 60_000L

    @Volatile private var cached: Bitmap? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var receiverRegistered = false

    /** 呼出路径用（主线程安全、零 binder） */
    fun peek(): Bitmap? = cached

    /** init 空闲期调用：注册壁纸变更监听+后台首载；重复调用幂等 */
    fun ensure(context: Context) {
        val appCtx = context.applicationContext
        if (!receiverRegistered) {
            receiverRegistered = true
            runCatching {
                appCtx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) = refreshAsync(appCtx)
                    },
                    IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
                )
            }
        }
        refreshAsync(appCtx)
    }

    /** TTL 过期（第三方改壁纸可能不发广播）：呼出前顺手异步刷新，不影响本次 peek */
    fun refreshIfStale(context: Context) {
        if (System.currentTimeMillis() - fetchedAt > TTL_MS) ensure(context)
    }

    private fun refreshAsync(appCtx: Context) {
        Thread {
            runCatching {
                val drawable = WallpaperManager.getInstance(appCtx).drawable ?: return@Thread
                val full = drawable.toFullBitmap()
                val bounds = appCtx.resources.displayMetrics
                val targetW = bounds.widthPixels / 2
                val targetH = bounds.heightPixels / 2
                // 与桌面一致的居中等比裁剪：壁纸原图通常比屏幕宽（滚动壁纸更甚），直接
                // 缩放到屏幕尺寸会把画面横向压扁 → 板内磨砂与真实壁纸错位。先按屏幕宽高比
                // 居中裁剪，再降到半分辨率（半分辨率足够模糊采样，位图 ~2.5MB）
                val ratio = targetW.toFloat() / targetH
                val srcRatio = full.width.toFloat() / full.height
                val cropW: Int
                val cropH: Int
                if (srcRatio > ratio) {
                    cropH = full.height
                    cropW = (full.height * ratio).roundToInt().coerceIn(1, full.width)
                } else {
                    cropW = full.width
                    cropH = (full.width / ratio).roundToInt().coerceIn(1, full.height)
                }
                val cropped = Bitmap.createBitmap(
                    full, (full.width - cropW) / 2, (full.height - cropH) / 2, cropW, cropH
                )
                val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                cached = scaled
                // 只回收本类自己创建的中间位图：full 来自 WallpaperManager 的 Drawable，
                // 可能是系统共享实例，回收它会在桌面进程里造成 "recycled bitmap" 崩溃
                if (cropped !== full && cropped !== scaled) cropped.recycle()
                fetchedAt = System.currentTimeMillis()
                HLog.i(TAG, "wallpaper cached ${targetW}x$targetH (crop ${cropW}x$cropH of ${full.width}x${full.height})")
            }.onFailure { HLog.w(TAG, "wallpaper sample failed: ${it.message}") }
        }.apply { isDaemon = true; name = "WallpaperSampler" }.start()
    }

    private fun Drawable.toFullBitmap(): Bitmap =
        when (this) {
            is BitmapDrawable -> bitmap
            else -> {
                val w = intrinsicWidth.coerceAtLeast(1)
                val h = intrinsicHeight.coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    setBounds(0, 0, w, h)
                    draw(Canvas(it))
                }
            }
        }
}
