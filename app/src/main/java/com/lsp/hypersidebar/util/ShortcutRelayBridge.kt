package com.lsp.hypersidebar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.lsp.hypersidebar.UnfreezeRelayService
import com.lsp.hypersidebar.prefs.PrefKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 快捷方式 root 代发的 **Binder 客户端**（2026-11-12）。
 *
 * 为什么不再用广播：[ShortcutRelayReceiver] 的 goAsync 线程在模块 App 被广播冷启后，
 * SmartPower 约 5s 就把进程冻结（真机 `freezeUid SUCCESS ... reason=new process`），
 * su 还没跑完就停住 ⇒ 第一次点击静默失败、很久后才报失败。bind 本服务会把模块 App
 * 提升为 bound 进程（[UnfreezeRelayService] 的既有定律），su 期间不被冻。
 *
 * **持久连接**（2026-11-12 真机第二轮）：一次性 bind 在模块 App 已被冻结时不可靠——
 * SmartPower 只把它抬回 background 3s，服务还没跑完又被冻，客户端 25s 超时。
 * 因此本 object 在宿主 attach（launcher / :ui）时就 bind 并**保持连接**，模块 App 全程
 * 处于 bound 状态；点击只复用这条连接。热重载时由 XposedInit 统一 [releaseForReload]。
 */
object ShortcutRelayBridge {

    private const val TAG = "ShortcutRelayBridge"
    private const val CONNECT_TIMEOUT_MS = 3_000L

    /** 代发结果；null 仅表示服务连接不可用（调用方可回退旧广播）。 */
    data class Outcome(
        val handled: Boolean,
        val ok: Boolean,
        val reason: String,
        val label: String
    )

    private val lock = Any()

    @Volatile
    private var conn: ServiceConnection? = null

    @Volatile
    private var messenger: Messenger? = null

    @Volatile
    private var connectLatch: CountDownLatch? = null

    @Volatile
    private var boundCtx: Context? = null

    /**
     * 宿主 attach 期调用：保持一条到模块 App 的 bind。幂等；绑定失败不抛（点击时再试）。
     * 调用方=launcher（EdgeGestureHook）/ :ui（FreeformRelayHook），两者都在 armHostSide 里调。
     */
    fun ensureBound(context: Context) {
        if (messenger != null) return
        val appCtx = context.applicationContext
        synchronized(lock) {
            if (conn != null) return
            val latch = CountDownLatch(1)
            connectLatch = latch
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null) {
                        messenger = Messenger(binder)
                        HLog.i(TAG, "relay service connected")
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    HLog.w(TAG, "relay service disconnected")
                    messenger = null
                    conn = null
                    latch.countDown()
                }

                override fun onBindingDied(name: ComponentName?) {
                    HLog.w(TAG, "relay binding died")
                    messenger = null
                    conn = null
                    latch.countDown()
                }

                override fun onNullBinding(name: ComponentName?) {
                    HLog.w(TAG, "relay null binding")
                    messenger = null
                    conn = null
                    latch.countDown()
                }
            }
            conn = c
            boundCtx = appCtx
            val intent = Intent().setClassName(
                FreeformLauncher.MODULE_PACKAGE, UnfreezeRelayService::class.java.name
            )
            val ok = runCatching {
                // BIND_IMPORTANT（2026-11-12）：启动它的调用方可能是普通 uid 桌面；
                // 让目标进程按前台重要性调度，绑定期间 su 才能稳定跑完。
                appCtx.bindService(
                    intent, c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
            }.onFailure { HLog.w(TAG, "bind failed: ${it.message}") }.getOrDefault(false)
            if (!ok) {
                conn = null
                boundCtx = null
                latch.countDown()
            }
        }
    }

    /** API 102 热重载：旧代退休前断开，避免旧 classloader/服务连接泄漏。 */
    fun releaseForReload() {
        val c: ServiceConnection?
        val ctx: Context?
        synchronized(lock) {
            c = conn
            ctx = boundCtx
            conn = null
            boundCtx = null
            messenger = null
            connectLatch = null
        }
        runCatching { if (c != null && ctx != null) ctx.unbindService(c) }
            .onFailure { HLog.w(TAG, "unbind failed: ${it.message}") }
    }

    /**
     * 阻塞等待服务应答（禁止主线程调用）。[timeoutMs] 覆盖首次 su 探测 + get-shortcuts +
     * am start；正常路径远小于此值。返回 null 表示连接不可用（调用方可回退旧广播）。
     */
    fun launchBlocking(
        context: Context,
        shortcut: ShortcutAction,
        token: String?,
        timeoutMs: Long = 25_000L
    ): Outcome? {
        // 先 startForegroundService：SmartPower 已把模块 App cgroup 冻结时，单纯 bind
        // 只能把它抬回 background 几秒；前台服务才能稳定拉出冻结态。
        val fgs = Intent().setClassName(
            FreeformLauncher.MODULE_PACKAGE, UnfreezeRelayService::class.java.name
        ).setAction(UnfreezeRelayService.ACTION_FOREGROUND_START)
        runCatching { context.applicationContext.startForegroundService(fgs) }
            .onFailure { HLog.w(TAG, "startForegroundService failed: ${it.message}") }
        ensureBound(context)
        val latch = connectLatch
        if (messenger == null) {
            runCatching { latch?.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        val svc = messenger ?: return null
        val replyLatch = CountDownLatch(1)
        val holder = AtomicReference<Outcome?>(null)
        val started = System.currentTimeMillis()
        val replyHandler = Handler(Looper.getMainLooper()) { msg ->
            val data = msg.data
            holder.set(
                Outcome(
                    handled = msg.arg1 == 1,
                    ok = data?.getBoolean(PrefKeys.RELAY_RESULT_EXTRA_OK, false) == true,
                    reason = data?.getString(PrefKeys.RELAY_RESULT_EXTRA_REASON).orEmpty(),
                    label = data?.getString(PrefKeys.RELAY_RESULT_EXTRA_LABEL)
                        ?: shortcut.label
                )
            )
            replyLatch.countDown()
            true
        }
        val msg = Message.obtain(null, UnfreezeRelayService.MSG_LAUNCH_SHORTCUT).apply {
            data = Bundle().apply {
                putString(PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT, shortcut.toJson().toString())
                putString(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN, token)
                putString(PrefKeys.RELAY_RESULT_EXTRA_LABEL, shortcut.label)
                putString(Trace.EXTRA, Trace.current)
                putBoolean(
                    PrefKeys.RELAY_LAUNCH_EXTRA_NO_BRIDGE,
                    runCatching { NativeGateBridge.isOs4NativeLauncher() }.getOrDefault(false)
                )
            }
            replyTo = Messenger(replyHandler)
        }
        runCatching { svc.send(msg) }.onFailure {
            HLog.w(TAG, "send launch failed: ${it.message}")
            return null
        }
        val arrived = runCatching { replyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        val outcome = holder.get()
        HLog.i(
            TAG,
            "launch ${shortcut.label}: arrived=$arrived handled=${outcome?.handled} " +
                "ok=${outcome?.ok} reason=${outcome?.reason} took=${System.currentTimeMillis() - started}ms"
        )
        return if (arrived) outcome else Outcome(false, false, "relay timeout", shortcut.label)
    }
}
