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
import com.lsp.hypersidebar.UnfreezeRelayService
import com.lsp.hypersidebar.prefs.PrefKeys

private const val TAG = "UnfreezeBind"

/**
 * 方案 2（bind 唤醒 relay，2026-09-07）的 :ui 发送端（配对 UnfreezeRelayService）。
 *
 * 链路：:ui bindService → 模块 App 的解冻服务（bind 本身即唤醒——冻结进程由
 * SmartPower THAW（uid 1000 服务调用规则，磁贴侧 08:52:27 同款实证），死进程被
 * bind 冷启动）→ binder 请求 su 解冻磁贴宿主 → 应答后 :ui 再发磁贴点击。
 *
 * 取代广播 relay 的原因：冻结进程收不到广播（静默蒸发），却收得到 bind——
 * 解冻的执行者是系统，不是模块自己，故无须保活/白名单。
 * 取代 kill 预热的原因：su 直写 freeze=0 不杀任何进程，目标 App 状态零损失。
 *
 * 节流：3s 内不重复解冻（仅吸收双击——tobg 重冻以秒计实测，见 THROTTLE_MS 注），
 * fan 呼出预热与点击兜底共用此表。
 */
object UnfreezeBridge {

    /** bind 冷启动 + 令牌冷补 + su 执行的总预算 */
    private const val UNFREEZE_TIMEOUT_MS = 4000L

    /**
     * 节流仅吸收双击（3s）。曾用 30s——0908 实测盲区：quickpay 关页 tobg 后 4s 即被
     * 重冻（FZ from system），窗口内点击跳过 relay 撞重冻进程静默积压。su 写 freeze=0
     * 幂等无害、模块热时全链 65-164ms，点击时 relay 常开换确定性；解冻后应用被拉起
     * 置前不可冻结，3s 内的重试必落活进程。
     */
    private const val THROTTLE_MS = 3_000L
    private val lastUnfreezeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 应答回调线程：Messenger 的 replyTo 需要 looper，不能在调用方裸线程上建 */
    private val replyThread = HandlerThread("UnfreezeClient").apply { start() }

    /** 30s 内解冻过（进程大概率已活且未冻结） */
    fun isFresh(pkg: String): Boolean =
        SystemClock.elapsedRealtime() - (lastUnfreezeAt[pkg] ?: 0L) < THROTTLE_MS

    /**
     * bind 模块 App 解冻服务并请求 su 解冻 [pkg]，阻塞等待应答。
     * 须在后台线程调用。返回 true=su 解冻已执行成功；false=失败/超时
     * （调用方照常发点击——目标未必冻结，解冻只是尽力而为的防线）。
     */
    fun unfreezeBlocking(context: Context, pkg: String, token: String?, trace: String? = null): Boolean {
        if (isFresh(pkg)) return true
        val appCtx = context.applicationContext
        val t0 = SystemClock.elapsedRealtime()
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val replyHandler = object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                ok = msg.arg1 == 1
                latch.countDown()
            }
        }
        var conn: ServiceConnection? = null
        try {
            conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    runCatching {
                        val msg = Message.obtain(null, UnfreezeRelayService.MSG_UNFREEZE).apply {
                            data = Bundle().apply {
                                putString("pkg", pkg)
                                putString(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN, token)
                            }
                            replyTo = Messenger(replyHandler)
                        }
                        Messenger(service).send(msg)
                    }.onFailure {
                        Log.w(TAG, "send unfreeze failed: ${it.message}")
                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            appCtx.bindService(
                Intent().setClassName(FreeformLauncher.MODULE_PACKAGE, UnfreezeRelayService::class.java.name),
                conn, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "bind failed: ${e.message}")
        }
        val done = latch.await(UNFREEZE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { conn?.let { appCtx.unbindService(it) } }
        if (done) lastUnfreezeAt[pkg] = SystemClock.elapsedRealtime()
        Log.i(TAG, "[${trace ?: "-"}] unfreeze bind: pkg=$pkg ok=$ok done=$done " +
            "cost=${SystemClock.elapsedRealtime() - t0}ms")
        return ok
    }
}
