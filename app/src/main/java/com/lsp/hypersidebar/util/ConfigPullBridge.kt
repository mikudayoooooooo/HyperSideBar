package com.lsp.hypersidebar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import com.lsp.hypersidebar.ConfigPullService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ConfigPullBind"

/**
 * 配置拉取客户端（迭代六 §11.1，hook 进程侧，配对模块 App 的 ConfigPullService）。
 *
 * 兜底通道：ConfigSync 的广播推送对冻结宿主静默丢弃（用户改设置时宿主大概率在后台
 * 被冻结），SYNC 丢失后配置停留旧快照。本桥在廉价点（手势 DOWN、呼出装配前）检查
 * [ConfigSync.isStale]——一次 volatile 读，关键路径零成本——过期才后台 bind 模块
 * App 拉全量配置（bind 唤醒冻结/死进程，UnfreezeRelayService 同定律；热 43ms/
 * 冷 415~736ms 既有实测），拉到即 applySync，新配置下次读取生效。
 *
 * 节流：[PULL_MIN_INTERVAL_MS] 内至多一次（模块不可达时也不放大重试）；单飞锁防并发
 * 重复 bind。调用方绝不阻塞——[refreshIfStale] 恒立即返回。
 */
object ConfigPullBridge {

    /** 两次拉取的最小间隔（与 ConfigSync.STALE_MS 同量级：过期窗口内至多补拉一次） */
    private const val PULL_MIN_INTERVAL_MS = 60_000L

    /** bind 冷启动（模块进程死）+ 应答的总预算（冷 bind 实测 415~736ms，取整档余量） */
    private const val PULL_TIMEOUT_MS = 4000L

    @Volatile private var lastPullAt = 0L
    private val pulling = AtomicBoolean(false)

    /** 应答回调线程：Messenger 的 replyTo 需要 looper */
    private val replyThread = HandlerThread("ConfigPullClient").apply { start() }

    /**
     * 廉价检查点入口（手势 DOWN / 呼出装配前调用）：配置新鲜则零开销返回；
     * 过期且距上次拉取超过节流窗口才投一次后台拉取。可在任意线程调用。
     */
    fun refreshIfStale(context: Context?) {
        if (!ConfigSync.isStale()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPullAt < PULL_MIN_INTERVAL_MS) return
        if (!pulling.compareAndSet(false, true)) return
        val appCtx = context?.applicationContext
        if (appCtx == null) {
            pulling.set(false)
            return
        }
        lastPullAt = now
        Thread({
            try {
                pullBlocking(appCtx)
            } finally {
                pulling.set(false)
            }
        }, "ConfigPull").apply { isDaemon = true }.start()
    }

    /** 后台线程调用：bind 模块 App 拉全量配置，应答落 ConfigSync 缓存。 */
    private fun pullBlocking(appCtx: Context) {
        val t0 = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        var gotMap: Map<String, Any>? = null
        val replyHandler = object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                @Suppress("UNCHECKED_CAST")
                gotMap = msg.data?.getSerializable("map") as? Map<String, Any>
                latch.countDown()
            }
        }
        var conn: ServiceConnection? = null
        try {
            conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    runCatching {
                        val msg = Message.obtain(null, ConfigPullService.MSG_PULL_CONFIG).apply {
                            replyTo = Messenger(replyHandler)
                        }
                        Messenger(service).send(msg)
                    }.onFailure {
                        Log.w(TAG, "send pull failed: ${it.message}")
                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            appCtx.bindService(
                Intent().setClassName(FreeformLauncher.MODULE_PACKAGE, ConfigPullService::class.java.name),
                conn, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "bind failed: ${e.message}")
        }
        val done = latch.await(PULL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runCatching { conn?.let { appCtx.unbindService(it) } }
        val map = gotMap
        if (done && map != null) {
            ConfigSync.applySync(map)
            Log.i(TAG, "config pulled: ${map.size} keys, cost=${SystemClock.elapsedRealtime() - t0}ms")
        } else {
            Log.w(TAG, "pull timeout/unavailable: done=$done cost=${SystemClock.elapsedRealtime() - t0}ms")
        }
    }
}
