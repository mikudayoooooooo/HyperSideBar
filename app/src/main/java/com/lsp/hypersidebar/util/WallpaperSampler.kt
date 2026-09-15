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
                val w = bounds.widthPixels / 2
                val h = bounds.heightPixels / 2
                cached = Bitmap.createScaledBitmap(full, w, h, true)
                    .also { if (it !== full) full.recycle() }
                fetchedAt = System.currentTimeMillis()
                HLog.i(TAG, "wallpaper cached ${w}x$h")
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
